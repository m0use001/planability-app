/* Automation engine (phone AutomationWorker port, PC v1) — ported from COD planability-ai
 * automation.ts. Definitions live next to the data store; a 60s sweep executes due prompts
 * with a bounded tool-less non-stream request; results land in an append-only md log +
 * a system notification. vscode fs/Uri/toast → node fs + injected notify. */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type { Store } from '../store/store';
import type { AutomationKind } from '../../../shared/ipc';
import {
	dispatchAgentTask, isAgentTask, normalizeSpec, TaskLog,
	type TaskRunnerDeps as AutomationTaskDeps, type TaskLogEntry,
} from './automation-task';

export interface Automation {
	id: number;
	name: string;
	prompt: string;
	intervalMinutes?: number;
	dailyHour?: number;
	dailyMinute?: number;
	enabled: boolean;
	lastRun: number;
	createdAt: number;
	/**
	 * M20-2: 'ask' (default) is the historical single-shot call; 'agent' dispatches a workflow
	 * through the AgentManager. Optional and defaulted on read so every EXISTING automations.json
	 * keeps working untouched — an unknown value also falls back to 'ask', because silently
	 * turning someone's daily summary into an agent dispatch would be the worst possible surprise.
	 *
	 * The union itself lives in shared/ipc.ts (`AutomationKind`): this is the persistence shape and
	 * shared is the view the renderer sees, and if each spells the union separately they drift —
	 * which is exactly how shared ended up with no `kind` field at all while the engine had it.
	 */
	kind?: AutomationKind;
	/** M20-2: which workflow to run, for agent tasks. */
	workflowId?: string;
	/** M20-2: the goal, overriding the workflow's own default. */
	taskText?: string;
	/** M20-2: hard ceiling for the whole run. */
	timeoutMinutes?: number;
}

const MAX_PROMPT_CHARS = 4000;

export class AutomationEngine {
	private automations: Automation[] = [];
	private timer: NodeJS.Timeout | undefined;
	private running = false;

	/**
	 * M20-2 hooks. All optional: an engine constructed without them behaves EXACTLY as before,
	 * which is what keeps the existing 'ask' automations untouched.
	 */
	private taskDeps?: AutomationTaskDeps;
	/** Per-automation in-flight guards. A long agent run must not block a short ask. */
	private readonly inFlight = new Set<number>();
	/**
	 * The callback fires AFTER the map is updated, so a run's entry is written whether or not the
	 * reader ever asks for it. Persistence belongs here rather than in `dispatchAgentTask` because
	 * that module stays free of fs — see workflow-store.ts for the same split.
	 */
	private readonly taskLog = new TaskLog((id, entries) => { void this.writeTaskLog(id, entries); });

	constructor(
		private readonly store: Store,
		private readonly callMain: (system: string, user: string) => Promise<string>,
		private readonly notify: (title: string, body: string, error?: boolean) => void,
		taskDeps?: AutomationTaskDeps,
	) {
		if (taskDeps) { this.taskDeps = taskDeps; }
	}

	/**
	 * Recent run entries for one automation, for the UI's log view (newest last).
	 *
	 * Async on purpose: the map only holds what this process has seen, so the first call after a
	 * launch has to read the file. `has` then short-circuits every later call, including for an
	 * automation with no file at all — otherwise the history button would hit disk each click to
	 * keep discovering that there is nothing there.
	 */
	async taskHistory(id: number, n = 50): Promise<TaskLogEntry[]> {
		if (!this.taskLog.has(id)) { this.taskLog.load(id, await this.readTaskLog(id)); }
		// Sanitised rather than trusted: `slice(-0)` is the WHOLE array, and a negative count
		// indexes from the front, so an unvalidated `n` turns "last 20" into something else
		// entirely. The stored list is already bounded, so anything implausible falls back.
		const take = Number.isFinite(n) && n >= 1 ? Math.floor(n) : 50;
		return this.taskLog.list({ id }, take);
	}

	/**
	 * Persist one automation's task log. Best effort: a log write failing must never take a run
	 * down with it, and the entry is already in memory either way.
	 *
	 * `.tasklog.json` rather than joining the existing `${id}.md`: that file is the ask path's
	 * human-readable tail, appended as markdown. These are structured records with a runId to
	 * link back to, and JSON is what lets a reader take `runId` without parsing prose.
	 */
	private async writeTaskLog(id: number, entries: TaskLogEntry[]): Promise<void> {
		try {
			await fs.mkdir(this.logDir, { recursive: true });
			await fs.writeFile(path.join(this.logDir, `${id}.tasklog.json`), JSON.stringify(entries), 'utf8');
		} catch { /* best effort — see above */ }
	}

	/** Prior history for one automation. Missing, unreadable or malformed all read as "none". */
	private async readTaskLog(id: number): Promise<TaskLogEntry[]> {
		try {
			const raw = await fs.readFile(path.join(this.logDir, `${id}.tasklog.json`), 'utf8');
			const arr: unknown = JSON.parse(raw);
			if (!Array.isArray(arr)) { return []; }
			// A hand-edited or truncated file must not poison the view: drop anything that is not
			// an entry with a timestamp rather than throwing and showing the button as broken.
			return arr.filter((e): e is TaskLogEntry =>
				!!e && typeof (e as TaskLogEntry).at === 'number' && typeof (e as TaskLogEntry).ok === 'boolean');
		} catch { return []; }
	}

	private get file(): string { return path.join(this.store.dataDir, 'automations.json'); }
	private get logDir(): string { return path.join(this.store.dataDir, 'automation-logs'); }

	async start(): Promise<void> {
		await this.reload();
		this.timer = setInterval(() => { this.sweep().then(undefined, () => { /* keep sweeping */ }); }, 60_000);
	}

	stop(): void {
		if (this.timer) { clearInterval(this.timer); this.timer = undefined; }
	}

	private async reload(): Promise<void> {
		try {
			this.automations = JSON.parse(await fs.readFile(this.file, 'utf8'));
		} catch { this.automations = []; }
	}

	private async persist(): Promise<void> {
		await fs.mkdir(this.store.dataDir, { recursive: true });
		await fs.writeFile(this.file, JSON.stringify(this.automations, null, 1), 'utf8');
	}

	async list(): Promise<Automation[]> { await this.reload(); return this.automations; }

	async create(def: Omit<Automation, 'id' | 'createdAt' | 'lastRun' | 'enabled'>, enabled = true): Promise<Automation> {
		await this.reload();
		const next: Automation = { ...def, id: Date.now(), createdAt: Date.now(), lastRun: 0, enabled };
		this.automations.push(next);
		await this.persist();
		return next;
	}

	async setEnabled(id: number, enabled: boolean): Promise<boolean> {
		await this.reload();
		const a = this.automations.find(x => x.id === id);
		if (!a) { return false; }
		a.enabled = enabled;
		await this.persist();
		return true;
	}

	/**
	 * Patch an existing automation. Returns null when no such id — callers must not treat that
	 * as success, because an edit aimed at the wrong id otherwise looks like it worked.
	 *
	 * THE RULES ARE PHONE'S, MIRRORED ON PURPOSE. `automation_edit` in ToolExecImpls.kt does:
	 *
	 *   - a BLANK string is IGNORED, not applied. You cannot clear a prompt by sending "".
	 *   - numeric / boolean fields apply when the KEY IS PRESENT, whatever the value.
	 *
	 * Two different answers for the same question, and it is not an inconsistency — it is the
	 * difference between "I did not mention it" and "I said it is empty". Diverging from phone here
	 * would be the twelfth D-class drift, so this is written to read like the Kotlin.
	 *
	 * One PC-only consequence worth stating: `taskText` empty is MEANINGFUL on PC — for kind
	 * "agent" it is the goal that OVERRIDES the workflow's own default, so "" means "use the
	 * workflow's task". That is exactly why a blank must be ignored rather than applied:
	 * applying it would silently drop the override the user had set.
	 */
	async update(id: number, patch: Partial<Omit<Automation, 'id' | 'createdAt' | 'lastRun'>>): Promise<Automation | null> {
		await this.reload();
		const cur = this.automations.find(x => x.id === id);
		if (!cur) { return null; }

		// Strings: blank means "not mentioned". Mirrors takeIf { it.isNotBlank() } on phone.
		if (patch.name !== undefined && String(patch.name).trim()) { cur.name = String(patch.name).trim(); }
		if (patch.prompt !== undefined && String(patch.prompt).trim()) { cur.prompt = String(patch.prompt); }
		if (patch.kind !== undefined) { cur.kind = patch.kind; }
		if (patch.workflowId !== undefined && String(patch.workflowId).trim()) { cur.workflowId = String(patch.workflowId); }
		if (patch.taskText !== undefined && String(patch.taskText).trim()) { cur.taskText = String(patch.taskText); }

		// Numbers and booleans: presence is the signal, so a 0 or a false is applied.
		if (patch.intervalMinutes !== undefined) { cur.intervalMinutes = Number(patch.intervalMinutes); }
		if (patch.dailyHour !== undefined) { cur.dailyHour = Number(patch.dailyHour); }
		if (patch.dailyMinute !== undefined) { cur.dailyMinute = Number(patch.dailyMinute); }
		if (patch.timeoutMinutes !== undefined) { cur.timeoutMinutes = Number(patch.timeoutMinutes); }
		if (patch.enabled !== undefined) { cur.enabled = !!patch.enabled; }

		await this.persist();
		return cur;
	}

	async remove(id: number): Promise<boolean> {
		await this.reload();
		const before = this.automations.length;
		this.automations = this.automations.filter(x => x.id !== id);
		await this.persist();
		return this.automations.length !== before;
	}

	private isDue(a: Automation, now: Date): boolean {
		if (!a.enabled) { return false; }
		if (a.intervalMinutes && a.intervalMinutes > 0) {
			return now.getTime() - a.lastRun >= a.intervalMinutes * 60_000;
		}
		if (a.dailyHour !== undefined && a.dailyHour >= 0 && a.dailyHour <= 23) {
			const today = new Date(now.getFullYear(), now.getMonth(), now.getDate(), a.dailyHour, a.dailyMinute ?? 0).getTime();
			return now.getTime() >= today && a.lastRun < today;
		}
		return false;
	}

	async sweep(): Promise<void> {
		if (this.running) { return; }
		this.running = true;
		try {
			await this.reload();
			const now = new Date();
			for (const a of this.automations) {
				if (!this.isDue(a, now)) { continue; }
				// Advance lastRun BEFORE dispatching, in both branches. If the app dies mid-run the
				// task must not re-fire on next launch and burn the quota twice — the original
				// ordering, kept deliberately rather than by accident.
				a.lastRun = now.getTime();
				await this.persist();

				// M20-2: agent tasks go to the workflow runner. Dispatched WITHOUT awaiting: an
				// agent run takes minutes and this sweep runs every 60s over every automation.
				if (isAgentTask(a)) {
					if (this.taskDeps) {
						dispatchAgentTask(a, normalizeSpec({
							workflowId: a.workflowId, task: a.taskText, timeoutMinutes: a.timeoutMinutes,
						}), { ...this.taskDeps, log: this.taskLog, notify: this.notify }, this.inFlight);
					} else {
						// No task runner configured: say so rather than silently doing nothing.
						this.notify('Planability 自动化未执行',
							`「${a.name}」是 agent 任务，但任务运行器未就绪。`, true);
					}
					continue;
				}
				try {
					// Data snapshot injection: the executor is tool-less, so real numbers
					// (counts + open todos + upcoming schedules) ride into the prompt.
					const snap = await this.snapshot();
					const prompt = a.prompt.slice(0, Math.max(500, MAX_PROMPT_CHARS - snap.length));
					const result = await this.callMain(AUTOMATION_SYSTEM, `${snap}\n\n---\n\n指令：${prompt}`);
					await this.log(a, result);
					this.notify('Planability 自动化', `「${a.name}」执行完成，结果已写入日志。`);
				} catch (e) {
					await this.log(a, `[FAIL] ${String(e)}`);
					this.notify('Planability 自动化失败', `「${a.name}」：${String(e)}`, true);
				}
			}
		} finally {
			this.running = false;
		}
	}

	/** Compact real-data snapshot so tool-less runs answer from facts, not guesses. */
	private async snapshot(): Promise<string> {
		const [s, t, n, m, p] = await Promise.all([
			this.store.live<any>('schedules'),
			this.store.live<any>('todos'),
			this.store.live('notes'),
			this.store.live('memories'),
			this.store.live('projects')
		]);
		const today = new Date().toISOString().slice(0, 10);
		const open = t.filter(x => x.status !== 'done');
		const upcoming = s
			.filter(x => (x.date ?? x.startIso?.slice(0, 10) ?? '') >= today)
			.sort((a, b) => String(a.date ?? a.startIso).localeCompare(String(b.date ?? b.startIso)))
			.slice(0, 8);
		const lines = [
			`数据快照（${new Date().toLocaleString('zh-CN')}）：日程 ${s.length} · 待办 ${t.length}（未完成 ${open.length}）· 笔记 ${n.length} · 记忆 ${m.length} · 项目 ${p.length}`,
			'未完成待办：' + (open.slice(0, 10).map(x => `${x.title}${x.date ? `(${x.date})` : ''}${x.priority ? `[优先级${x.priority}]` : ''}`).join('、') || '无'),
			'近期日程：' + (upcoming.map(x => `${(x.date ?? x.startIso?.slice(0, 10)) ?? ''} ${x.startIso?.slice(11, 16) ?? ''} ${x.title}`).join('、') || '无')
		];
		return lines.join('\n');
	}

	private async log(a: Automation, text: string): Promise<void> {
		await fs.mkdir(this.logDir, { recursive: true });
		const file = path.join(this.logDir, `${a.id}.md`);
		const header = `## ${new Date().toLocaleString('zh-CN')} — ${a.name}\n\n${a.prompt}\n\n---\n\n`;
		let prev = '';
		try { prev = await fs.readFile(file, 'utf8'); } catch { prev = `# ${a.name} 自动化日志\n`; }
		await fs.writeFile(file, prev + header + text + '\n\n', 'utf8');
	}

	/** Tail of an automation's log for the management UI (newest run first, bounded). */
	async recentLog(id: number): Promise<string> {
		try {
			const raw = await fs.readFile(path.join(this.logDir, `${id}.md`), 'utf8');
			return raw.slice(-3000);
		} catch { return ''; }
	}
}

const AUTOMATION_SYSTEM = [
	'你是 Planability 自动化执行器。根据给定指令直接产出结果，不寒暄。',
	'输出使用简洁中文 markdown；含日程/待办建议时逐条列出标题与时间。',
].join('\n');
