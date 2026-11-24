/**
 * Workflow tools — the chain-shaped half of what B4 deliberately left out.
 *
 * WHAT THIS IS. `deep_plan` fans OUT (independent subtasks, concurrent). `runWorkflow` chains
 * DOWN: each step consumes the previous one's output, `{{prev}}` and `{{task}}` are the only
 * variables, and the journal records exactly what was sent — which is what makes replay possible.
 * In B4 I refused to force the fan-out through the chain. This is the other direction: the chain
 * finally gets a tool, and the fan-out keeps its own shape.
 *
 * THE ONE DESIGN POINT THAT MATTERS: the IPC handler is fire-and-forget.
 *
 * `ipcMain.handle(IPC_CHAT.workflowRun, ...)` returns `{ ok: true, runId }` and lets the run
 * continue in the background. That is right for a UI with a progress panel and WRONG for a tool:
 * the model's turn ends when the tool returns, so a runId it cannot wait on is a dead end — the
 * same shape as the `agent_runs` lie N5 caught in task's reply. So `workflow_run` AWAITS the run,
 * with a bounded wait, and if the wait expires it points at `workflow_runs` and `workflow_replay`,
 * which are real tools. A pointer to something that exists, or nothing — never a pointer to
 * something that does not.
 *
 * Domain is `agent` (子代理), the same one as `task` and `deep_plan`: these dispatch agent work,
 * and the switch is the lever a user reaches for to stop quota being spent on it.
 */

import type { ToolRegistry } from './tools';
import { validateWorkflow } from './workflow';
import type { Workflow, RunOutcome, RunJournal } from './workflow';

export interface WorkflowEntry {
	id: string;
	name: string;
	task: string;
	steps: { id: string; role: string; prompt: string }[];
	problems?: string[];
}

export interface RunSummary {
	runId: string;
	workflowId: string;
	workflowName: string;
	task: string;
	status: string;
	failedAt?: string;
	steps: { stepId: string; role: string; status: string }[];
	startedAt: number;
	endedAt: number;
}

export interface WorkflowToolDeps {
	listWorkflows: () => WorkflowEntry[];
	/** `undefined`, not `null`, because index.ts' resolveWorkflow returns undefined for a miss —
	 *  and narrowing `null` there would force a pointless coercion at the call site. */
	resolveWorkflow: (id: string) => Workflow | undefined;
	/**
	 * Start AND wait for a run. Injected, not written here, because it needs the agent manager, the
	 * active session, role resolution and the journal store — all owned by index.ts.
	 *
	 * `waitMs` is the tool's own bound; a run that outlives it keeps going and lands in the journal.
	 * Resolves with `outcome: undefined` when the wait expired, so the caller can say "still
	 * running, here is how to look" instead of pretending it finished.
	 */
	runWorkflow: (wf: Workflow, opts: { waitMs: number }) => Promise<{
		outcome?: RunOutcome;
		runId: string;
	}>;
	listRuns: (limit: number) => RunSummary[];
	replay: (runId: string) => { ok: boolean; text?: string };
}

export const WORKFLOW_LIMITS = {
	/** How long workflow_run waits before saying "still running". Bounded because the model's
	 *  turn is blocked for the whole wait; the run itself keeps its own per-step timeout. */
	waitMs: 120_000,
	maxRuns: 50,
	/** The journal can be long; the reply carries the steps, not the full outputs. */
	maxReplyChars: 4000,
} as const;

const clip = (s: string, n: number): string =>
	(String(s ?? '').length > n ? String(s).slice(0, n) + '…（已截断）' : String(s ?? ''));

/** One line per step: who ran, how it ended. Enough to tell a broken step from a working one. */
function journalLines(j: RunJournal): string {
	const head = `工作流「${j.workflowName}」run ${j.runId}：${j.status}`;
	if (!j.entries.length) { return head + '（没有任何步骤执行）'; }
	const rows = j.entries.map(e => {
		const mark = e.status === 'done' ? '完成' : e.status === 'failed' ? '失败' : e.status;
		const err = e.error ? ` —— ${e.error}` : '';
		const chars = e.output ? `（${e.output.length} 字）` : '';
		return `  ${e.stepId} [${e.role}] ${mark}${chars}${err}`;
	});
	return head + '\n' + rows.join('\n');
}

export function registerWorkflowTools(reg: ToolRegistry, deps: WorkflowToolDeps): void {
	// Own the domain rather than trusting the caller: an unset domain falls into `system`, which
	// is alwaysOn, and the tool would be un-switchable with nothing saying a switch ignored it.
	reg.setDomain('agent');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('workflow_list', 'auto',
		'列出可运行的工作流（只读）：id、名称、目标、步骤（含每步的角色与提示词模板）。',
		{ type: 'object', properties: {} },
		async () => {
			const list = deps.listWorkflows();
			if (!list.length) { return '（没有可运行的工作流）'; }
			return list.map(w => {
				const steps = (w.steps ?? []).map(s => `${s.id}:${s.role}`).join(' -> ') || '（无步骤）';
				const bad = w.problems?.length ? `\n  ⚠ ${w.problems.join('；')}` : '';
				return `${w.id}  ${w.name}\n  目标：${w.task}\n  步骤：${steps}${bad}`;
			}).join('\n\n');
		});

	def('workflow_validate', 'auto',
		'校验一份工作流定义是否可运行（只读）。给 workflow 对象（含 id/name/task/steps，steps 每项要有 id/role/prompt）。',
		{
			type: 'object',
			properties: { workflow: { type: 'object', description: '工作流定义' } },
			required: ['workflow'],
		},
		async (input) => {
			const w = input?.workflow;
			if (!w || typeof w !== 'object') { return '[FAIL] 需要一个 workflow 对象。'; }
			const { validateWorkflow } = require('./workflow') as typeof import('./workflow');
			const v = validateWorkflow(w);
			if (v.ok === true) { return '校验通过，这份工作流可以运行。'; }
			return `校验不通过：\n${v.errors.map((e: string) => '  ' + e).join('\n')}`;
		});

	def('workflow_run', 'confirm',
		'运行一个工作流（写操作，需用户确认）。工作流是链式的：每一步吃上一步的输出，'
		+ `本工具会等它跑完，最多等 ${Math.round(WORKFLOW_LIMITS.waitMs / 1000)} 秒；`
		+ '超时的话它仍在后台跑，用 workflow_runs / workflow_replay 查。',
		{
			type: 'object',
			properties: {
				id: { type: 'string', description: '工作流 id，来自 workflow_list' },
				task: { type: 'string', description: '本次要完成的目标；省略则用工作流自带的 task' },
			},
			required: ['id'],
		},
		async (input) => {
			const id = String(input?.id ?? '').trim();
			if (!id) { return '[FAIL] 缺少工作流 id，先用 workflow_list 看看有哪些。'; }
			const w = deps.resolveWorkflow(id);
			if (!w) {
				return `[FAIL] 没有 id 为「${id}」的工作流。可用 workflow_list 查看可运行的那些。`;
			}
			const goal = String(input?.task ?? '').trim() || w.task;
			if (!goal || goal === '（未指定任务）') {
				// Same guard as the IPC: running the demo with a literal placeholder feeds the
				// agents a placeholder, which the leftover-placeholder check exists to prevent.
				return '[FAIL] 需要提供任务描述（task 参数），或工作流自带的 task 不能是占位符。';
			}
			const wf: Workflow = { ...w, task: goal };

			const { validateWorkflow } = require('./workflow') as typeof import('./workflow');
			const v = validateWorkflow(wf);
			if (v.ok === false) {
				// Refuse BEFORE dispatching: a failed journal costs the same work and then has to be
				// written, listed and read to say what validate says now.
				return `[FAIL] 工作流定义无效：${v.errors.join('；')}`;
			}

			let res: { outcome?: RunOutcome; runId: string };
			try {
				res = await deps.runWorkflow(wf, { waitMs: WORKFLOW_LIMITS.waitMs });
			} catch (e) {
				return `[FAIL] 运行工作流失败：${(e as Error)?.message ?? String(e)}`;
			}
			if (!res.outcome) {
				// Still running. Say that, and say where to look — both of those are real tools.
				return `工作流「${w.name}」已在后台开始（run ${res.runId}），但在 ` +
					`${Math.round(WORKFLOW_LIMITS.waitMs / 1000)} 秒内还没跑完。` +
					'用 workflow_runs 看状态，用 workflow_replay 看已完成步骤的具体输入输出。';
			}
			const body = journalLines(res.outcome.journal);
			const tail = res.outcome.ok
				? '\n\n各步骤产出：\n' + res.outcome.journal.entries.map(e =>
					`【${e.stepId}】${e.output.slice(0, 600) || '（无输出）'}`).join('\n')
				: `\n\n失败原因：${res.outcome.error ?? '未知'}`;
			return clip(body + tail, WORKFLOW_LIMITS.maxReplyChars);
		});

	def('workflow_runs', 'auto',
		'列出最近的工作流运行记录（只读）：run id、工作流、目标、状态、每步结果。',
		{
			type: 'object',
			properties: { limit: { type: 'number', description: `条数，1..${WORKFLOW_LIMITS.maxRuns}` } },
		},
		async (input) => {
			const raw = Number(input?.limit ?? 20);
			const n = Number.isFinite(raw) ? Math.min(Math.max(Math.trunc(raw), 1), WORKFLOW_LIMITS.maxRuns) : 20;
			const runs = deps.listRuns(n);
			if (!runs.length) { return '（还没有任何工作流运行记录）'; }
			return runs.map(r => {
				const steps = r.steps.map(s => `${s.stepId}:${s.status}`).join(' ');
				return `${r.runId}  ${r.workflowName}  ${r.status}${r.failedAt ? `（失败于 ${r.failedAt}）` : ''}\n  目标：${r.task}\n  步骤：${steps}`;
			}).join('\n\n');
		});

	def('workflow_replay', 'auto',
		'回放一次工作流运行（只读）：逐步给出每步的完整输入与输出。需要 run id，来自 workflow_runs。',
		{
			type: 'object',
			properties: { runId: { type: 'string', description: 'run id，来自 workflow_runs' } },
			required: ['runId'],
		},
		async (input) => {
			const runId = String(input?.runId ?? '').trim();
			if (!runId) { return '[FAIL] 缺少 run id，先用 workflow_runs 看看有哪些运行。'; }
			const r = deps.replay(runId);
			if (!r.ok || !r.text) {
				return `[FAIL] 没有 run ${runId} 的运行记录（记录数有上限，较早的运行可能已被淘汰）。`;
			}
			return clip(r.text, WORKFLOW_LIMITS.maxReplyChars);
		});
}