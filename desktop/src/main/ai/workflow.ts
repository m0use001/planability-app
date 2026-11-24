/* M20-1 declarative multi-agent workflows.
 *
 * A workflow is a list of STEPS; each step names a role and an input template; the run threads
 * the previous step's output into the next one. That is the whole v1 — the plan calls it "ZCode
 * dynamic workflow 的简化声明版", and the simplification is deliberate.
 *
 * FOUR THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. THE JOURNAL IS THE PRODUCT, NOT A LOG FILE. The plan's acceptance criterion is "跑通并可回放".
 *    A run you cannot replay is a run you cannot debug, and a multi-agent run that misbehaves
 *    ALWAYS misbehaves in a way you need to replay. So every step's full input and output is
 *    recorded, and replay re-runs from the journal WITHOUT touching the model.
 *
 * 2. A FAILED STEP DOES NOT SILENTLY PASS. It halts the run and records the failure. A workflow
 *    that continues past a failed step produces confident nonsense downstream, which is far worse
 *    than stopping.
 *
 * 3. STEP INPUT IS TEMPLATED, AND AN UNRESOLVED PLACEHOLDER IS AN ERROR. `{{prev}}` with no
 *    previous output must not silently become the literal string "{{prev}}" — that text would
 *    then be fed to an agent as if it were the previous step's findings.
 *
 * 4. REPLAY MUST NOT HAVE SIDE EFFECTS. Notes, files and memories written during a live run are
 *    NOT re-created on replay, because replay exists to inspect what happened, and re-running
 *    the side effects would duplicate the user's data.
 */

import type { Run } from './agents/AgentManager';

export type StepStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped';

export interface WorkflowStep {
	id: string;
	/** Which agent plays this step. Resolved to an agent id by the caller. */
	role: string;
	/** Prompt template. Supports {{prev}} and {{task}}. */
	prompt: string;
	/**
	 * When true, a failure of this step is tolerated and the run continues. Off by default —
	 * see decision 2.
	 */
	optional?: boolean;
	/** Cap on this step's output, to stop one step flooding the next. */
	maxOutputChars?: number;
}

export interface Workflow {
	id: string;
	name: string;
	/** The overall goal; substituted into {{task}}. */
	task: string;
	steps: WorkflowStep[];
}

export interface StepJournalEntry {
	stepId: string;
	role: string;
	/** Exactly what was sent, after templating. This is what makes replay possible. */
	input: string;
	/** The agent's output, or '' when the step failed. */
	output: string;
	status: StepStatus;
	startedAt: number;
	endedAt: number;
	error?: string;
}

export interface RunJournal {
	runId: string;
	workflowId: string;
	workflowName: string;
	task: string;
	entries: StepJournalEntry[];
	startedAt: number;
	endedAt: number;
	status: 'done' | 'failed' | 'running';
	/** Set when the run stopped early, with the step that caused it. */
	failedAt?: string;
	error?: string;
}

export const LIMITS = {
	maxSteps: 12,
	maxTemplateLen: 100_000,
	maxStepOutput: 20_000,
	/** Journals are kept in memory for replay; bound them so a long session cannot grow forever. */
	maxJournals: 200,
} as const;

/**
 * Substitute `{{prev}}` and `{{task}}`.
 *
 * Throws on an unresolved placeholder rather than emitting the literal text — see decision 3.
 */
export function renderPrompt(template: string, vars: { prev: string; task: string }): string {
	const src = String(template ?? '').slice(0, LIMITS.maxTemplateLen);
	const out = src.replace(/\{\{\s*(prev|task)\s*\}\}/g, (_m, key: string) => {
		return key === 'task' ? vars.task : vars.prev;
	});
	// Anything left that looks like a placeholder is a typo (or a missing {{prev}}).
	const leftover = /\{\{[^}]*\}\}/.exec(out);
	if (leftover) {
		throw new Error(`模板占位符无法解析：${leftover[0]}（只支持 {{task}} 与 {{prev}}）`);
	}
	return out;
}

/** Validate a workflow at load time, so a broken one fails before any agent is dispatched. */
export function validateWorkflow(w: Workflow): { ok: true } | { ok: false; errors: string[] } {
	const errors: string[] = [];
	if (!w?.name?.trim()) { errors.push('缺少 name'); }
	if (!w?.task?.trim()) { errors.push('缺少 task'); }
	const steps = w?.steps ?? [];
	if (!steps.length) { errors.push('至少要有一个步骤'); }
	if (steps.length > LIMITS.maxSteps) { errors.push(`步骤过多（${steps.length} > ${LIMITS.maxSteps}）`); }
	const seen = new Set<string>();
	for (const [i, s] of steps.entries()) {
		if (!s?.id?.trim()) { errors.push(`第 ${i + 1} 步缺少 id`); }
		else if (seen.has(s.id)) { errors.push(`步骤 id 重复：${s.id}`); }
		seen.add(s.id);
		if (!s?.role?.trim()) { errors.push(`步骤 ${s?.id ?? i + 1} 缺少 role`); }
		if (!s?.prompt?.trim()) { errors.push(`步骤 ${s?.id ?? i + 1} 缺少 prompt`); }
		// {{prev}} in the first step has nothing to resolve. Catching it here means the run never
		// starts, instead of failing on step 1 after the user waited for setup.
		if (i === 0 && /\{\{\s*prev\s*\}\}/.test(s?.prompt ?? '')) {
			errors.push(`第一步 ${s?.id} 用了 {{prev}}，但此时还没有上一步的输出`);
		}
	}
	return errors.length ? { ok: false, errors } : { ok: true };
}

/** How the runner is given an agent. One turn, one answer. */
export type StepRunner = (role: string, input: string) => Promise<string>;

export interface RunOptions {
	runId: string;
	/** Injected so tests can produce deterministic ids and timestamps. */
	now?: () => number;
	/** Called after each step so a UI can stream progress. */
	onStep?: (e: StepJournalEntry) => void;
}

export interface RunOutcome {
	journal: RunJournal;
	/** The last step's output, or '' when the run failed immediately. */
	output: string;
	ok: boolean;
	error?: string;
}

/**
 * Execute a workflow. Sequential by design: the steps form a chain where each consumes the
 * previous one's output, so there is nothing to parallelise. Running them concurrently would
 * also break the journal's ordering, which is the thing that makes replay possible.
 */
export async function runWorkflow(w: Workflow, runStep: StepRunner, opts: RunOptions): Promise<RunOutcome> {
	const now = opts.now ?? (() => Date.now());
	const v = validateWorkflow(w);
	if (v.ok === false) {
		const journal: RunJournal = {
			runId: opts.runId, workflowId: w?.id ?? '', workflowName: w?.name ?? '',
			task: w?.task ?? '', entries: [], startedAt: now(), endedAt: now(),
			status: 'failed', error: `工作流定义无效：${v.errors.join('；')}`,
		};
		return { journal, output: '', ok: false, error: journal.error };
	}

	const entries: StepJournalEntry[] = [];
	let prev = '';
	for (const [i, step] of w.steps.entries()) {
		let input = '';
		try {
			input = renderPrompt(step.prompt, { prev, task: w.task });
		} catch (e) {
			// A template error is a DEFINITION bug, not a runtime one. Stop the run.
			const entry: StepJournalEntry = {
				stepId: step.id, role: step.role, input: '', output: '',
				status: 'failed', startedAt: now(), endedAt: now(),
				error: (e as Error)?.message ?? String(e),
			};
			entries.push(entry); opts.onStep?.(entry);
			const journal: RunJournal = {
				runId: opts.runId, workflowId: w.id, workflowName: w.name, task: w.task,
				entries, startedAt: entries[0].startedAt, endedAt: now(),
				status: 'failed', failedAt: step.id, error: entry.error,
			};
			return { journal, output: '', ok: false, error: entry.error };
		}

		const entry: StepJournalEntry = {
			stepId: step.id, role: step.role, input, output: '',
			status: 'running', startedAt: now(), endedAt: 0,
		};
		try {
			const raw = await runStep(step.role, input);
			entry.output = String(raw ?? '').slice(0, step.maxOutputChars ?? LIMITS.maxStepOutput);
			entry.status = 'done';
		} catch (e) {
			entry.status = 'failed';
			entry.error = (e as Error)?.message ?? String(e);
		}
		entry.endedAt = now();
		entries.push(entry);
		opts.onStep?.(entry);

		if (entry.status === 'failed') {
			if (step.optional) {
				// An optional step contributes nothing, but the run continues with the PREVIOUS
				// output rather than an empty string — passing '' would silently erase context the
				// next step was meant to build on.
				continue;
			}
			const journal: RunJournal = {
				runId: opts.runId, workflowId: w.id, workflowName: w.name, task: w.task,
				entries, startedAt: entries[0].startedAt, endedAt: now(),
				status: 'failed', failedAt: step.id, error: entry.error,
			};
			return { journal, output: '', ok: false, error: `${step.id}: ${entry.error}` };
		}
		prev = entry.output;
		void i;
	}

	const journal: RunJournal = {
		runId: opts.runId, workflowId: w.id, workflowName: w.name, task: w.task,
		entries, startedAt: entries[0]?.startedAt ?? now(), endedAt: now(), status: 'done',
	};
	return { journal, output: prev, ok: true };
}

/**
 * Replay a journal WITHOUT calling any agent.
 *
 * This is the acceptance criterion "可回放": the whole point is to see what each step was given
 * and produced. It also means replay cannot create side effects — no notes, no memories, no
 * files — which is why there is no runner parameter here at all.
 */
export function replayJournal(j: RunJournal): {
	/** A human-readable transcript of the whole run. */
	lines: string;
	/** The reconstructed chain of outputs, one per step. */
	outputs: { stepId: string; role: string; output: string; status: StepStatus }[];
} {
	const outputs: { stepId: string; role: string; output: string; status: StepStatus }[] = [];
	const lines: string[] = [
		`工作流回放：${j.workflowName || j.workflowId}（run ${j.runId}，${j.status}）`,
		`任务：${j.task}`,
	];
	if (j.failedAt) { lines.push(`中断于步骤：${j.failedAt}`); }
	for (const e of j.entries) {
		lines.push('', `── 步骤 ${e.stepId}（${e.role}）[${e.status}] ──`);
		lines.push('输入：', e.input || '（空）');
		lines.push('输出：', e.status === 'failed' ? `（失败：${e.error ?? '未知'}）` : (e.output || '（空）'));
		outputs.push({ stepId: e.stepId, role: e.role, output: e.output, status: e.status });
	}
	return { lines: lines.join('\n'), outputs };
}

/** Bounded journal store, newest last. Replay needs history, so this is kept, but bounded. */
export class JournalStore {
	private readonly items: RunJournal[] = [];
	private seq = 0;

	add(j: RunJournal): void {
		this.items.push(j);
		if (this.items.length > LIMITS.maxJournals) { this.items.splice(0, this.items.length - LIMITS.maxJournals); }
	}

	nextRunId(): string {
		this.seq += 1;
		return `wf-${Date.now().toString(36)}-${this.seq}`;
	}

	list(n = 20): RunJournal[] { return this.items.slice(-n); }
	get(runId: string): RunJournal | null { return this.items.find(j => j.runId === runId) ?? null; }
	get size(): number { return this.items.length; }
}

/**
 * The three-step demo from the acceptance criterion.
 *
 * `task` is the CALLER'S text and is substituted verbatim — it must not itself contain
 * `{{...}}`, which renderPrompt rejects on purpose. The first version set `task: '{{task}}'`,
 * and the leftover-placeholder check immediately caught it: the goal text reached the agent as
 * the literal string "{{task}}". That guard is the reason this is worth stating.
 */
/**
 * Adapter: turn a role name into "run one turn and wait for it".
 *
 * The AgentManager is ASYNC by design — submitPrimary returns immediately and the result arrives
 * via events, because the concurrency gate queues work. A workflow step must WAIT for its answer,
 * so the adapter polls getRun. Polling rather than subscribing is deliberate: the run already
 * lives in a map, and an event subscription needs unsubscribing on every failure path, which is
 * where leaks come from.
 */
export interface AgentRunAdapter {
	/** Start a turn for a role. Returns the run id. */
	start(role: string, prompt: string): string;
	/** Current run state. */
	peek(runId: string): { status: 'queued' | 'running' | 'done' | 'error'; result?: string; error?: string } | undefined;
	/** Resolve a role name to an agent id. Throws when no such agent exists. */
	resolveRole(role: string): string;
}

export function agentStepRunner(a: AgentRunAdapter, opts: {
	pollMs?: number; timeoutMs?: number; now?: () => number;
} = {}): StepRunner {
	const pollMs = opts.pollMs ?? 60;
	const timeoutMs = opts.timeoutMs ?? 180_000;
	const now = opts.now ?? (() => Date.now());
	return async (role, input) => {
		const runId = a.start(role, input);
		const deadline = now() + timeoutMs;
		for (;;) {
			const r = a.peek(runId);
			if (r) {
				if (r.status === 'done') { return r.result ?? ''; }
				if (r.status === 'error') { throw new Error(r.error || '步骤执行失败'); }
			}
			if (now() > deadline) {
				// Say WHICH role and how long it waited. "Timeout" alone is not actionable.
				throw new Error(`步骤 ${role} 超过 ${Math.round(timeoutMs / 1000)}s 未完成`);
			}
			await new Promise(r => setTimeout(r, pollMs));
		}
	};
}

/**
 * Abstract role names a workflow step may use, mapped to the preset agents that play them.
 *
 * Why this table exists: a workflow is meant to stay READABLE ("researcher") rather than naming a
 * row id that a user can re-key. But the presets are keyed `team_plan_research` / 研究员, so
 * `a.id === 'researcher'`, `a.name === 'researcher'` and even `'研究员'.includes('researcher')` are
 * all false — every lookup missed, resolveRole threw "no agent called researcher", and the demo
 * workflow could never complete step 1. Nobody could run a workflow at all.
 *
 * The entries are semantic, not mechanical: researcher -> team_plan_research is guessable,
 * but planner -> team_plan_scheduler (策划师) and recorder -> team_plan_scribe (记录员) are naming
 * choices that only a human can make, which is why this is a literal table and not a stemmer.
 *
 * Every candidate is tried in the SAME ways resolveRole already looks (id, name, then substring),
 * so a user who names an agent after the role keeps winning — aliases are consulted last.
 */
export const ROLE_ALIASES: Readonly<Record<string, readonly string[]>> = {
	researcher: ['team_plan_research'],
	planner: ['team_plan_scheduler'],
	recorder: ['team_plan_scribe'],
};

/**
 * Look a role up through ROLE_ALIASES, using the SAME id / name / substring ladder that a literal
 * match uses, so an alias is never a weaker match than a direct one.
 *
 * Each candidate is tried by id first, then name, then substring: the point of the ladder is that
 * `researcher` still finds `team_plan_research` if that agent is renamed to something friendlier,
 * without the table having to track renames.
 */
function findByAlias<T extends { id: string; name?: string }>(list: T[], role: string): T | undefined {
	for (const candidate of ROLE_ALIASES[role] ?? []) {
		const hit = list.find(a => a.id === candidate)
			?? list.find(a => a.name === candidate)
			?? list.find(a => (a.name ?? '').includes(candidate))
			?? list.find(a => (a.id ?? '').includes(candidate));
		if (hit) { return hit; }
	}
	return undefined;
}

/**
 * Match a step's role against the available agents. Returns undefined when nothing plays it.
 *
 * The ORDER is the entire design and is why this is a named function rather than a chain inline:
 * id, then name, then substring, and ONLY THEN the aliases. A user who creates an agent actually
 * called "researcher" must win over the preset mapping to team_plan_research — otherwise renaming
 * their own agent would silently do nothing and they could never work out why. Aliases are the
 * fallback for roles nobody has an agent for, which is the demo workflow's `researcher`/`planner`/
 * `recorder` (`研究员`.includes(`researcher`) is false, so every literal lookup misses).
 *
 * Returns undefined instead of throwing so a caller can either report or refuse — resolveRole
 * throws for the interactive path, checkTask turns this into a pre-flight refusal.
 */
export function matchRole<T extends { id: string; name?: string }>(list: T[], role: string): T | undefined {
	return list.find(a => a.id === role)
		?? list.find(a => a.name === role)
		?? list.find(a => (a.name ?? '').includes(role))
		?? findByAlias(list, role);
}

export function demoWorkflow(id = 'research-plan-note', task = '（未指定任务）'): Workflow {
	return {
		id, name: '收集→排程→记录',
		task,
		steps: [
			{ id: 'research', role: 'researcher', prompt: '收集与以下任务相关的事实，只给要点：{{task}}' },
			{ id: 'plan', role: 'planner', prompt: '根据这些事实排出可执行计划：\n{{prev}}' },
			{ id: 'note', role: 'recorder', prompt: '把计划整理成一份笔记：\n{{prev}}' },
		],
	};
}