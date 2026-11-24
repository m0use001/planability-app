/* M20-2 automation → agent tasks.
 *
 * The existing AutomationEngine calls `callNonStream` once per tick: one prompt, one answer, no
 * tools, no session, no trace. That is fine for "summarise my todos" and useless for anything a
 * team would actually want scheduled — you cannot ask a tool-less single call to research, plan
 * and file notes.
 *
 * So this adds a TASK KIND to an automation, without rewriting the scheduler:
 *
 *   kind: 'ask'   (default) — the existing single-shot call. Unchanged.
 *   kind: 'agent'          — run a WORKFLOW through the AgentManager (M20-1).
 *   kind: 'workflow'       — an alias people will actually type; same thing.
 *
 * FOUR THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. THE SCHEDULER'S `running` FLAG IS A GLOBAL MUTEX, AND IT IS THE WRONG SHAPE FOR TASKS.
 *    A 5-minute agent run must not block a 1-minute ask. So the new kinds are dispatched
 *    WITHOUT awaiting, and each carries its own in-flight guard keyed by automation id. The
 *    global guard still protects the `sweep` bookkeeping, which is what it was for.
 *
 * 2. A BACKGROUND RUN MUST NOT STEAL THE USER'S SESSION. A workflow step dispatched through
 *    `submitPrimary(..., sessions.active(), ...)` would write the background agent's turns into
 *    whatever conversation the user happens to have open. So background runs get their OWN
 *    session, named, and it is never activated.
 *
 * 3. LAST-RUN IS ADVANCED BEFORE THE WORK STARTS, not after. If the app is killed mid-run the
 *    task must not immediately re-fire on next launch and burn the quota twice. The original
 *    engine already did this and it is worth keeping that ordering deliberately.
 *
 * 4. FAILURES ARE LOGGED WITH THE REASON AND SURFACED, NEVER SWALLOWED. A scheduled job that
 *    silently stops working for a month is worse than one that fails loudly.
 */

import type { Automation } from './automation';
import type { RunJournal, Workflow } from './workflow';
import { validateWorkflow } from './workflow';

/**
 * Re-exported from shared so there is one definition of this union.
 *
 * This used to be a third independent copy (here, in automation.ts, and none at all in shared).
 * Three identical spellings is three places that can drift apart one field at a time — shared is
 * what the renderer sees, so when it was the one missing the field the automation page simply had
 * no `kind` to switch on. shared is the source; everything else names it.
 */
export type { AutomationKind, TaskLogEntry } from '../../../shared/ipc';
import type { AutomationKind, TaskLogEntry } from '../../../shared/ipc';

export interface AutomationTaskSpec {
	/** Which workflow to run. For kind='agent' this is the only required field. */
	workflowId: string;
	/** The goal. Empty means "use the workflow's own default". */
	task?: string;
	/** Hard ceiling for the whole run, in minutes. */
	timeoutMinutes?: number;
}

/*
 * `TaskLogEntry` now lives in shared/ipc.ts and is re-exported above, for the third time in this
 * file's history doing the same job as `AutomationKind`: the automation page renders it and links
 * `runId` to a replay, so a main-process-only spelling gave the renderer no type to build against
 * while the runtime value was there all along.
 */

export const TASK_LIMITS = {
	defaultTimeoutMinutes: 20,
	maxTimeoutMinutes: 240,
	/** How many log lines to keep per automation. */
	maxLogLines: 200,
	/** A run whose last step produced more than this is truncated in the log, not in the journal. */
	maxSummaryChars: 2000,
} as const;

/** Classify an automation. Anything unknown is an 'ask', which is the historical behaviour. */
export function taskKind(a: Partial<Automation> & { kind?: string }): AutomationKind {
	const k = String(a?.kind ?? 'ask').toLowerCase();
	if (k === 'agent' || k === 'workflow') { return 'agent'; }
	return 'ask';
}

/** Is this an automation that dispatches real agents? */
export function isAgentTask(a: Partial<Automation> & { kind?: string }): boolean {
	return taskKind(a) === 'agent';
}

export function normalizeSpec(raw: unknown): AutomationTaskSpec {
	const q = (raw ?? {}) as Partial<AutomationTaskSpec>;
	const t = Number(q.timeoutMinutes);
	return {
		workflowId: String(q.workflowId ?? '').trim(),
		task: String(q.task ?? '').trim(),
		timeoutMinutes: Number.isFinite(t)
			? Math.min(Math.max(Math.round(t), 1), TASK_LIMITS.maxTimeoutMinutes)
			: TASK_LIMITS.defaultTimeoutMinutes,
	};
}

/** Why a task automation would refuse to fire. Returned so the UI can explain, not just log. */
export type TaskRefusal =
	| 'not-an-agent-task'
	| 'no-workflow-id'
	| 'unknown-workflow'
	| 'invalid-workflow'
	/** A step names a role no agent plays. Checked BEFORE the session is created. */
	| 'unresolvable-role'
	| 'already-running';

export interface TaskCheck { ok: boolean; reason?: TaskRefusal; detail?: string }

export function checkTask(
	a: Partial<Automation> & { kind?: string },
	spec: AutomationTaskSpec,
	resolve: (id: string) => Workflow | undefined,
	inFlight: ReadonlySet<number>,
	/**
	 * Can this workflow's role names actually be played by an agent right now?
	 *
	 * Optional purely so existing callers and tests that only care about the workflow shape do not
	 * have to stub an agent manager. When omitted the check is skipped and the run fails on step 1
	 * instead — the old behaviour, not a new one.
	 *
	 * This exists because the session was created BEFORE the run: role resolution failing on step 1
	 * left an empty session per attempt, so an interval=1 automation that can never succeed
	 * accumulated one junk session per tick — 85 inside an hour and a half, and nothing about a
	 * failed run ever cleans them up. Refusing here means no session is created at all.
	 */
	canResolveRole?: (role: string) => boolean,
): TaskCheck {
	if (!isAgentTask(a)) { return { ok: false, reason: 'not-an-agent-task' }; }
	if (!spec.workflowId) { return { ok: false, reason: 'no-workflow-id' }; }
	const w = resolve(spec.workflowId);
	if (!w) { return { ok: false, reason: 'unknown-workflow', detail: spec.workflowId }; }
	const v = validateWorkflow({ ...w, task: spec.task || w.task });
	if (v.ok === false) { return { ok: false, reason: 'invalid-workflow', detail: v.errors.join('；') }; }
	if (canResolveRole) {
		// First unresolved role, reported BY NAME: "role researcher has no agent" tells the user
		// which one to create or rename, where a bare boolean would only say "no".
		const missing = (w.steps ?? []).map(s => s.role).find(r => !safeCanResolve(canResolveRole, r));
		if (missing) { return { ok: false, reason: 'unresolvable-role', detail: missing }; }
	}
	// Keyed by automation id, so a slow task blocks only ITSELF.
	if (inFlight.has(a.id ?? -1)) { return { ok: false, reason: 'already-running' }; }
	return { ok: true };
}

/**
 * The real resolver THROWS when no agent matches, and checkTask answers with a refusal rather
 * than raising — swallowing is confined to this boolean test, so the failure still reaches the
 * user as `unresolvable-role` with the role name instead of vanishing.
 */
function safeCanResolve(fn: (role: string) => boolean, role: string): boolean {
	try { return !!fn(role); } catch { return false; }
}

/** The session name a background run gets. Never the active session. */
export function backgroundSessionName(a: Automation, at: number): string {
	const stamp = new Date(at).toISOString().slice(0, 16).replace('T', ' ');
	return `自动化 · ${a.name} · ${stamp}`;
}

/**
 * One line per run, for the log file. Bounded so a daily job does not grow without limit.
 *
 * In memory by default, with an optional `onChanged` hook to persist. The hook is not decoration:
 * the map was the ONLY home these entries had, so every restart wiped the history the automation
 * page's 历史 button exists to show. A record that is there while the app is running and gone the
 * moment it isn't is a cache, not history.
 *
 * The signatures take `{ id: number }` rather than `Automation`: this class has never read any
 * other field, and the previous spelling forced callers to fabricate `name/prompt/enabled` just
 * to ask for a list.
 */
export class TaskLog {
	private readonly lines = new Map<number, TaskLogEntry[]>();

	constructor(private readonly onChanged?: (automationId: number, entries: TaskLogEntry[]) => void) {}

	append(a: { id: number }, e: TaskLogEntry): void {
		const list = this.lines.get(a.id) ?? [];
		list.push(e);
		if (list.length > TASK_LIMITS.maxLogLines) { list.splice(0, list.length - TASK_LIMITS.maxLogLines); }
		this.lines.set(a.id, list);
		this.onChanged?.(a.id, list);
	}

	list(a: { id: number }, n = 50): TaskLogEntry[] { return (this.lines.get(a.id) ?? []).slice(-n); }
	clear(a: { id: number }): void { this.lines.delete(a.id); }

	/** Has this automation's history been loaded from disk yet? */
	has(automationId: number): boolean { return this.lines.has(automationId); }

	/**
	 * Seed the map from disk. Deliberately does NOT fire `onChanged`: the file was just read, and
	 * writing it back on load would be churn with no reader — the bound only needs to be
	 * re-applied when a run appends past it.
	 */
	load(automationId: number, entries: TaskLogEntry[]): void {
		this.lines.set(automationId, entries.slice(-TASK_LIMITS.maxLogLines));
	}

	get size(): number { return this.lines.size; }
}

export interface TaskRunnerDeps {
	/** Resolve a workflow by id. */
	resolve(workflowId: string): Workflow | undefined;
	/**
	 * Can this role be played by an agent? Optional: when absent, role resolution is only
	 * attempted inside the run and a failure there leaves an empty background session behind.
	 * Supplied by the host so the refusal happens before one is created.
	 */
	canResolveRole?(role: string): boolean;
	/** Create (or reuse) a dedicated background session and return its id. Never the active one. */
	sessionFor(a: Automation, at: number): Promise<string>;
	/** Run the workflow; resolves with the journal. Rejects on a hard failure. */
	run(workflow: Workflow, opts: { runId: string; sessionId: string; timeoutMs: number }): Promise<RunJournal>;
	/** Run id generator, injected so ids are deterministic in tests. */
	nextRunId(): string;
	now?: () => number;
	log?: TaskLog;
	/** Progress notification, so a long background run is not invisible. */
	notify?: (title: string, body: string, error?: boolean) => void;
	/** Per-step progress, mirrored into the renderer. */
	onProgress?: (a: Automation, e: TaskLogEntry) => void;
}

export interface DispatchResult {
	started: boolean;
	reason?: TaskRefusal;
	detail?: string;
	runId?: string;
}

/**
 * Dispatch one agent-task automation.
 *
 * Returns IMMEDIATELY: this is called from a 60s sweep that must not block, and an agent run can
 * take minutes. The in-flight guard is set synchronously BEFORE the first await, otherwise two
 * ticks landing in the same second would both fire.
 */
export function dispatchAgentTask(
	a: Automation, spec: AutomationTaskSpec, deps: TaskRunnerDeps,
	inFlight: Set<number>,
): DispatchResult {
	// Defensive on `a`: this is called from a sweep over data that came off disk, and a null
	// automation must produce a refusal rather than a TypeError that kills the whole sweep.
	// Defensive on `a`: this runs from a sweep over data that came off disk, and a null automation
	// must produce a refusal rather than a TypeError that kills the whole sweep. Hence the
	// placeholder object used only on the logging paths.
	const safe: Automation = a ?? { id: -1, name: '自动化', prompt: '', enabled: false, lastRun: 0, createdAt: 0 };
	// Note the order: every check, including role resolvability, runs BEFORE `sessionFor` below.
	// A refusal is cheap; an empty session is permanent.
	const check = checkTask(a, spec, deps.resolve, inFlight, deps.canResolveRole);
	if (!check.ok) {
		// already-running is not an error worth logging: it is the guard working.
		if (check.reason !== 'already-running') {
			const entry: TaskLogEntry = { at: deps.now?.() ?? Date.now(), ok: false, summary: '未执行', error: refusalText(check.reason, check.detail) };
			deps.log?.append(safe, entry);
			deps.notify?.('Planability 自动化未执行', `「${a?.name ?? '自动化'}」：${entry.error}`, true);
		}
		return { started: false, reason: check.reason, detail: check.detail };
	}
	const w = deps.resolve(spec.workflowId)!;
	const name = a?.name ?? '自动化';
	// Set synchronously — before any await. See the docstring.
	inFlight.add(a.id);

	const runId = deps.nextRunId();
	const timeoutMs = (spec.timeoutMinutes ?? TASK_LIMITS.defaultTimeoutMinutes) * 60_000;
	void (async () => {
		const at = deps.now?.() ?? Date.now();
		let sessionId = '';
		try {
			sessionId = await deps.sessionFor(safe, at);
			deps.notify?.('Planability 自动化', `「${name}」开始执行（${w.steps.length} 步）。`);
			const journal = await deps.run({ ...w, task: spec.task || w.task }, { runId, sessionId, timeoutMs });
			const entry: TaskLogEntry = {
				at: deps.now?.() ?? Date.now(),
				ok: journal.status === 'done',
				runId,
				summary: summarize(journal),
				error: journal.status === 'done' ? undefined : (journal.error ?? `中断于 ${journal.failedAt ?? '未知步骤'}`),
				steps: journal.entries.map(e => ({ stepId: e.stepId, role: e.role, status: e.status })),
			};
			deps.log?.append(safe, entry);
			deps.onProgress?.(safe, entry);
			deps.notify?.(
				entry.ok ? 'Planability 自动化' : 'Planability 自动化失败',
				`「${name}」${entry.ok ? '执行完成' : '未完成'}：${entry.summary.slice(0, 120)}`,
				!entry.ok,
			);
		} catch (e) {
			// Swallowing here is how a scheduled job dies quietly for a month.
			const entry: TaskLogEntry = {
				at: deps.now?.() ?? Date.now(), ok: false, runId,
				summary: '执行异常', error: (e as Error)?.message ?? String(e),
			};
			deps.log?.append(safe, entry);
			deps.onProgress?.(safe, entry);
			deps.notify?.('Planability 自动化失败', `「${name}」：${entry.error}`, true);
		} finally {
			// Always released, including on a throw — otherwise the task never runs again.
			inFlight.delete(safe.id);
			void sessionId;
		}
	})();
	return { started: true, runId };
}

export function refusalText(r?: TaskRefusal, detail?: string): string {
	switch (r) {
		case 'not-an-agent-task': return '不是 agent 任务类型';
		case 'no-workflow-id': return '未指定工作流';
		case 'unknown-workflow': return `找不到工作流${detail ? `「${detail}」` : ''}`;
		case 'invalid-workflow': return `工作流定义无效：${detail ?? ''}`;
		case 'unresolvable-role': return `工作流里有 agent 扮演不了的角色：${detail ?? ''}（在 agent 列表里补上它，或改工作流里的 role）`;
		case 'already-running': return '上一次执行尚未结束';
		default: return '未知原因';
	}
}

/** One-line summary of a journal. Never hides which steps failed. */
export function summarize(j: RunJournal): string {
	const done = j.entries.filter(e => e.status === 'done').length;
	const failed = j.entries.filter(e => e.status === 'failed');
	const head = `${j.workflowName || j.workflowId}：${done}/${j.entries.length} 步完成`;
	if (!failed.length) {
		const last = j.entries[j.entries.length - 1]?.output ?? '';
		return `${head}。${last.slice(0, TASK_LIMITS.maxSummaryChars)}`.trim();
	}
	const names = failed.map(e => `${e.stepId}(${e.role})`).join('、');
	return `${head}，失败：${names}。${(failed[0].error ?? '').slice(0, 200)}`;
}