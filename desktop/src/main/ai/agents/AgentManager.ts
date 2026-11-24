/* M17 multi-agent kernel: multiple primary agents, sub-agent derivation, the SendMessage
 * bus and the concurrency gate (plan §3 M17 items 1, 2, 4, 5).
 *
 * This module owns *scheduling and identity*; it deliberately does NOT own the tool loop —
 * that stays in ChatAgent (main/ai/agent.ts), which a primary agent drives. AgentManager
 * decides who runs, on which slot, with which budget, and how results come back.
 *
 * Everything here is main-process. Nothing crosses IPC except the plain data shapes. */

import type { AiDb } from '../db';
import {
	DEFAULT_BUDGET, PRESET_TEAMS, newAgentId, rowToProfile, toPermMode,
	type AgentBudget, type AgentProfile,
} from './profile';

/** One running agent turn. */
export interface Run {
	id: string;
	agentId: string;
	sessionId: string;
	parentId: string;
	depth: number;
	/** 'primary' = user-addressable agent, 'sub' = derived via the task tool. */
	kind: 'primary' | 'sub';
	status: 'queued' | 'running' | 'done' | 'error';
	startedAt: number;
	endedAt?: number;
	error?: string;
	result?: string;
	budget: AgentBudget;
	/** The user (or parent agent) instruction driving this turn. */
	prompt: string;
}

export type BusKind = 'note' | 'steer' | 'result' | 'terminal';

export interface BusMessage {
	id: string;
	fromAgent: string;
	toAgent: string;
	sessionId: string;
	kind: BusKind;
	body: string;
	readAt?: number;
	createdAt: number;
}

/** Events the renderer (or IPC layer) can observe. */
export type ManagerEvent =
	| { type: 'run_queued'; run: Run }
	| { type: 'run_started'; runId: string; agentId: string; sessionId: string }
	| { type: 'run_done'; runId: string; agentId: string; ok: boolean; ms: number; result?: string; error?: string }
	| { type: 'bus'; message: BusMessage }
	| { type: 'quota_wait'; agentId: string; running: number; limit: number };

export interface AgentManagerDeps {
	db: AiDb;
	/** Execute one turn for the given agent. Provided by the ChatAgent wiring. */
	run: (req: { agent: AgentProfile; sessionId: string; budget: AgentBudget; depth: number; prompt: string; isAborted: () => boolean }) => Promise<string>;
	/** Concurrency ceiling shared with the provider (plan §3 risk section). Default 4. */
	concurrency?: number;
	/** Base budget for a run; per-agent maxTurns still wins. Defaults to DEFAULT_BUDGET. */
	baseBudget?: Partial<AgentBudget>;
	emit?: (e: ManagerEvent) => void;
}

/** Max derivation depth — a sub-agent may spawn, but not forever (loop guard). */
const MAX_DEPTH = 3;

export class AgentManager {
	private readonly runs = new Map<string, Run>();
	private readonly byAgent = new Map<string, Set<string>>(); // agentId -> running runIds
	private readonly queue: string[] = []; // runIds waiting for a slot
	private seq = 0;

	private readonly limit: number;
	private readonly baseBudget: AgentBudget;
	private readonly emit: (e: ManagerEvent) => void;

	constructor(private readonly deps: AgentManagerDeps) {
		this.limit = Math.max(1, deps.concurrency ?? 4);
		this.baseBudget = { ...DEFAULT_BUDGET, ...(deps.baseBudget ?? {}) };
		this.emit = deps.emit ?? (() => { });
	}

	// ---- agents (SQLite `agents` table) ----

	listAgents(): AgentProfile[] {
		const rows = this.deps.db.raw.prepare('SELECT * FROM agents ORDER BY builtin DESC, createdAt ASC').all() as any[];
		return rows.map(rowToProfile);
	}

	getAgent(id: string): AgentProfile | null {
		const r = this.deps.db.raw.prepare('SELECT * FROM agents WHERE id=?').get(id) as any;
		return r ? rowToProfile(r) : null;
	}

	upsertAgent(p: Partial<AgentProfile> & { name: string }): AgentProfile {
		const now = Date.now();
		const prev = p.id ? this.getAgent(p.id) : null;
		const rec: AgentProfile = {
			id: p.id || newAgentId(),
			name: p.name,
			color: p.color ?? prev?.color ?? '#4C8DFF',
			systemPrompt: p.systemPrompt ?? prev?.systemPrompt ?? '',
			tools: p.tools ?? prev?.tools ?? [],
			memoryScope: p.memoryScope ?? prev?.memoryScope ?? [],
			// Narrow on the way in — a bogus value from IPC/JSON must never reach the DB column.
			permMode: toPermMode(p.permMode ?? prev?.permMode),
			maxTurns: p.maxTurns ?? prev?.maxTurns ?? DEFAULT_BUDGET.maxTurns,
			builtin: prev?.builtin ?? false,
			createdAt: prev?.createdAt ?? now,
			updatedAt: now,
		};
		this.deps.db.raw.prepare(
			`INSERT INTO agents (id,name,color,systemPrompt,tools,memoryScope,permMode,maxTurns,builtin,createdAt,updatedAt)
			 VALUES (@id,@name,@color,@systemPrompt,@tools,@memoryScope,@permMode,@maxTurns,@builtin,@createdAt,@updatedAt)
			 ON CONFLICT(id) DO UPDATE SET name=@name,color=@color,systemPrompt=@systemPrompt,tools=@tools,
			   memoryScope=@memoryScope,permMode=@permMode,maxTurns=@maxTurns,updatedAt=@updatedAt`,
		).run({
			id: rec.id, name: rec.name, color: rec.color, systemPrompt: rec.systemPrompt,
			tools: JSON.stringify(rec.tools), memoryScope: JSON.stringify(rec.memoryScope),
			permMode: rec.permMode, maxTurns: rec.maxTurns, builtin: rec.builtin ? 1 : 0,
			createdAt: rec.createdAt, updatedAt: rec.updatedAt,
		});
		return rec;
	}

	deleteAgent(id: string): boolean {
		const r = this.deps.db.raw.prepare('DELETE FROM agents WHERE id=? AND builtin=0').run(id);
		return r.changes > 0;
	}

	/** Seed preset teams once (idempotent — never clobbers a user edit). */
	seedPresetTeams(): number {
		let seeded = 0;
		for (const team of PRESET_TEAMS) {
			for (const m of team.members) {
				const exists = this.deps.db.raw.prepare('SELECT id FROM agents WHERE id=?').get(m.key);
				if (exists) { continue; }
				const now = Date.now();
				this.deps.db.raw.prepare(
					`INSERT INTO agents (id,name,color,systemPrompt,tools,memoryScope,permMode,maxTurns,builtin,createdAt,updatedAt)
					 VALUES (?,?,?,?,?,?,?,?,1,?,?)`,
				).run(m.key, m.name, m.color, m.systemPrompt, JSON.stringify(m.tools), JSON.stringify(m.memoryScope), m.permMode, m.maxTurns, now, now);
				seeded++;
			}
		}
		return seeded;
	}

	// ---- sessions (SQLite `sessions` tree) ----

	ensureSession(id: string, title = '新对话', agentId = '', parentId = ''): void {
		const now = Date.now();
		const depth = parentId ? ((this.deps.db.raw.prepare('SELECT depth FROM sessions WHERE id=?').get(parentId) as any)?.depth ?? 0) + 1 : 0;
		this.deps.db.raw.prepare(
			`INSERT INTO sessions (id,title,agentId,parentId,depth,status,pinned,createdAt,updatedAt)
			 VALUES (?,?,?,?,?,'idle',0,?,?)
			 ON CONFLICT(id) DO UPDATE SET title=excluded.title, updatedAt=excluded.updatedAt`,
		).run(id, title, agentId, parentId, depth, now, now);
	}

	listSessions(): { id: string; title: string; agentId: string; parentId: string; depth: number; status: string; pinned: boolean }[] {
		return (this.deps.db.raw.prepare('SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC').all() as any[])
			.map(r => ({ id: r.id, title: r.title, agentId: r.agentId, parentId: r.parentId, depth: r.depth, status: r.status, pinned: !!r.pinned }));
	}

	/** Session badge state: 'idle' is a real state here (no run active), unlike Run.status. */
	setSessionStatus(id: string, status: 'idle' | 'running' | 'done' | 'error'): void {
		// UPDATE on a session row that does not exist yet is a silent no-op, which would
		// hide a missing ensureSession() from the caller. Create on demand.
		// Best-effort: a badge must never crash an in-flight run (e.g. on app shutdown).
		try {
			this.deps.db.raw.prepare(
				`INSERT INTO sessions (id,title,agentId,parentId,depth,status,pinned,createdAt,updatedAt)
				 VALUES (?,'','','',0,?,0,?,?)
				 ON CONFLICT(id) DO UPDATE SET status=excluded.status, updatedAt=excluded.updatedAt`,
			).run(id, status, Date.now(), Date.now());
		} catch { /* db closing / table missing — badge is not worth failing a run */ }
	}

	// ---- usage ledger (SQLite `usage`) ----

	recordUsage(agentId: string, sessionId: string, tokensIn: number, tokensOut: number, cost: number): void {
		this.deps.db.raw.prepare('INSERT INTO usage (agentId,sessionId,tokensIn,tokensOut,cost,at) VALUES (?,?,?,?,?,?)')
			.run(agentId, sessionId, tokensIn, tokensOut, cost, Date.now());
	}

	usageByAgent(): { agentId: string; tokensIn: number; tokensOut: number; cost: number; calls: number }[] {
		return this.deps.db.raw.prepare(
			`SELECT agentId, SUM(tokensIn) tokensIn, SUM(tokensOut) tokensOut, SUM(cost) cost, COUNT(*) calls
			 FROM usage GROUP BY agentId ORDER BY tokensIn DESC`,
		).all() as any[];
	}

	// ---- SendMessage bus (SQLite `agent_messages`) ----

	/** Queue a bus message. `steer` is delivered while the target runs; `terminal` ends it. */
	post(fromAgent: string, toAgent: string, body: string, kind: BusKind = 'note', sessionId = ''): BusMessage {
		const msg: BusMessage = {
			id: `am_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`,
			fromAgent, toAgent, sessionId, kind, body, createdAt: Date.now(),
		};
		this.deps.db.raw.prepare(
			'INSERT INTO agent_messages (id,fromAgent,toAgent,sessionId,kind,body,readAt,createdAt) VALUES (?,?,?,?,?,?,NULL,?)',
		).run(msg.id, msg.fromAgent, msg.toAgent, msg.sessionId, msg.kind, msg.body, msg.createdAt);
		this.emit({ type: 'bus', message: msg });
		return msg;
	}

	/** Unread queue for one agent, oldest first. */
	inbox(toAgent: string): BusMessage[] {
		return this.deps.db.raw.prepare(
			'SELECT * FROM agent_messages WHERE toAgent=? AND readAt IS NULL ORDER BY createdAt ASC',
		).all(toAgent) as BusMessage[];
	}

	markRead(toAgent: string): number {
		return this.deps.db.raw.prepare(
			'UPDATE agent_messages SET readAt=? WHERE toAgent=? AND readAt IS NULL',
		).run(Date.now(), toAgent).changes;
	}

	// ---- runs + concurrency gate ----

	activeRuns(): Run[] { return [...this.runs.values()].filter(r => r.status === 'running' || r.status === 'queued'); }
	runningCount(): number { return [...this.runs.values()].filter(r => r.status === 'running').length; }
	/** The ceiling this gate enforces — surfaced so the IPC handler and the agent_runs tool
	 *  report the same number instead of each hardcoding their own copy of it. */
	get concurrency(): number { return this.limit; }
	getRun(id: string): Run | undefined { return this.runs.get(id); }

	/**
	 * Submit a primary-agent turn. Queued (not awaited) when the gate is full — the caller
	 * gets the Run immediately and can observe completion via events / getRun.
	 */
	submitPrimary(agentId: string, sessionId: string, prompt: string): Run {
		return this.enqueue({ agentId, sessionId, prompt, kind: 'primary', parentId: '', depth: 0 });
	}

	/** Derive a sub-agent turn (the task tool). Depth-capped by MAX_DEPTH. */
	submitSub(agentId: string, sessionId: string, parentId: string, prompt: string): Run {
		const depth = ((this.deps.db.raw.prepare('SELECT depth FROM sessions WHERE id=?').get(parentId) as any)?.depth ?? -1) + 1;
		if (depth > MAX_DEPTH) {
			// Loop guard: refuse to go deeper, answer inline instead of spawning.
			const run: Run = {
				id: `run_${++this.seq}`, agentId, sessionId, parentId, depth, kind: 'sub',
				status: 'error', startedAt: Date.now(), endedAt: Date.now(),
				error: `派生子代理超过最大深度 ${MAX_DEPTH}，已拒绝`,
				budget: { ...this.baseBudget, maxTurns: 1 },
				prompt,
			};
			this.runs.set(run.id, run);
			return run;
		}
		return this.enqueue({ agentId, sessionId, prompt, kind: 'sub', parentId, depth });
	}

	private enqueue(args: { agentId: string; sessionId: string; prompt: string; kind: Run['kind']; parentId: string; depth: number }): Run {
		const agent = this.getAgent(args.agentId);
		const budget: AgentBudget = {
			maxTurns: agent?.maxTurns ?? this.baseBudget.maxTurns,
			maxMs: this.baseBudget.maxMs,
			maxTokens: this.baseBudget.maxTokens,
		};
		const run: Run = {
			id: `run_${++this.seq}`,
			agentId: args.agentId,
			sessionId: args.sessionId,
			parentId: args.parentId,
			depth: args.depth,
			kind: args.kind,
			status: 'queued',
			startedAt: Date.now(),
			budget,
			prompt: args.prompt,
		};
		this.runs.set(run.id, run);
		this.queue.push(run.id);
		this.emit({ type: 'run_queued', run });
		this.drain();
		return run;
	}

	/** Start queued runs while slots are free. Over-limit runs wait (no error). */
	private drain(): void {
		while (this.queue.length) {
			if (this.runningCount() >= this.limit) {
				this.emit({ type: 'quota_wait', agentId: this.runs.get(this.queue[0])?.agentId ?? '', running: this.runningCount(), limit: this.limit });
				return;
			}
			const runId = this.queue.shift()!;
			void this.execute(runId);
		}
	}

	private async execute(runId: string): Promise<void> {
		const run = this.runs.get(runId);
		if (!run) { return; }
		const agent = this.getAgent(run.agentId);
		if (!agent) {
			run.status = 'error';
			run.error = `未知 agent ${run.agentId}`;
			run.endedAt = Date.now();
			this.emit({ type: 'run_done', runId, agentId: run.agentId, ok: false, ms: 0, error: run.error });
			this.drain();
			return;
		}

		run.status = 'running';
		run.startedAt = Date.now();
		let set = this.byAgent.get(run.agentId);
		if (!set) { set = new Set(); this.byAgent.set(run.agentId, set); }
		set.add(runId);
		this.setSessionStatus(run.sessionId, 'running');
		this.emit({ type: 'run_started', runId, agentId: run.agentId, sessionId: run.sessionId });

		// Budget: wall-clock ceiling is enforced by an abort flag the runner polls.
		const deadline = run.startedAt + run.budget.maxMs;
		let aborted = false;
		const isAborted = () => aborted || Date.now() > deadline;

		try {
			const result = await this.deps.run({
				agent, sessionId: run.sessionId, budget: run.budget, depth: run.depth,
				prompt: run.prompt, isAborted,
			});
			run.status = 'done';
			run.result = result;
		} catch (e) {
			run.status = 'error';
			run.error = String((e as Error)?.message ?? e);
		} finally {
			run.endedAt = Date.now();
			this.byAgent.get(run.agentId)?.delete(runId);
			this.setSessionStatus(run.sessionId, run.status === 'error' ? 'error' : 'idle');
			this.emit({
				type: 'run_done', runId, agentId: run.agentId,
				ok: run.status === 'done', ms: run.endedAt - run.startedAt,
				result: run.result, error: run.error,
			});
			this.drain();
		}
	}

	/** Abort every run for an agent (user pressed stop). */
	abortAgent(agentId: string): number {
		let n = 0;
		for (const r of this.runs.values()) {
			if (r.agentId === agentId && (r.status === 'running' || r.status === 'queued')) {
				if (r.status === 'queued') {
					const i = this.queue.indexOf(r.id);
					if (i >= 0) { this.queue.splice(i, 1); }
				}
				r.status = 'error';
				r.error = '已停止';
				r.endedAt = Date.now();
				n++;
			}
		}
		return n;
	}
}