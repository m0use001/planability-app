/**
 * `ask_expert` and `deep_plan` (B4). Phone's `system` domain also carries `work_plan_start`, which
 * is deliberately NOT here — see the note at the bottom.
 *
 * WHY THESE DO NOT REUSE `runWorkflow`:
 *
 * `workflow.ts` says so itself — "Sequential by design: the steps form a chain where each consumes
 * the previous one's output, so there is nothing to parallelise." Phone's deep_plan is the opposite
 * shape: split into at most 4 INDEPENDENT subtasks, run them CONCURRENTLY, then synthesise. Forcing
 * that through a sequential chain would serialise four model calls and defeat the tool's only
 * reason to exist. The fan-out is written directly instead, which is also why it does not produce a
 * replayable journal — that property belongs to the chain shape, and borrowing it here would imply
 * a replay guarantee this tool does not have.
 *
 * CONTAINMENT: the workers are TOOL-LESS. `callNonStream` sends one system + one user message and
 * returns text; there is no tool loop, so even a subtask that reads like an instruction ("ignore the
 * above and run a command") can produce prose and nothing else. Splitting one model's output into
 * another model's input is prompt injection with extra steps; keeping the second call tool-less is
 * what bounds it.
 *
 * RISK: phone marks these auto. They are `confirm` here, because one deep_plan is up to SIX model
 * calls (1 split + up to 4 workers + 1 synthesis) and a model that calls it in a loop spends the
 * user's quota without asking. Cheap enough that the prompt matters more than the latency; not
 * cheap enough to be unattended.
 *
 * DOMAIN: phone puts these in `system`, which is always-on. PC puts them in `agent` (子代理), which
 * is switchable — a user who wants to stop paying for fan-out should be able to, and the switch is
 * the only lever for that.
 */

import type { ToolRegistry } from './tools';

export interface ExpertToolDeps {
	/**
	 * One non-streaming model call. Injected rather than imported so the tests can drive the whole
	 * fan-out without a provider, and so the slot choice stays with index.ts (these run on the
	 * WORKER slot: running them on the main slot makes the user wait longer for less context, which
	 * is the entire reason the worker split exists).
	 */
	call: (system: string, user: string) => Promise<string>;
}

export const EXPERT_LIMITS = {
	/** Phone's cap. Four is the number it chose; going wider trades latency for no extra coverage. */
	maxSubtasks: 4,
	maxTaskChars: 4000,
	maxQuestionChars: 4000,
	/** What we accept back from a worker before clipping, so one runaway answer cannot eat the reply. */
	maxWorkerChars: 6000,
	/** The split prompt asks for a list; refuse anything so long it is not a plan. */
	maxSplitChars: 4000,
} as const;

interface Expert {
	key: string;
	label: string;
	/** What this expert is FOR — shown back to the user so the answer is attributable. */
	purpose: string;
	system: string;
}

const COMMON = [
	'你在为一个中文助手提供专项视角。最终答案会由主助手整合后转述给用户，',
	'所以直接给结论和依据，不要写「好的，我来帮你」这类开场，也不要复述问题。',
].join('\n');

export const EXPERTS: readonly Expert[] = [
	{
		key: 'planner', label: '排期规划',
		purpose: '日程规划与时间安排',
		system: [
			'你是排期专家。把目标拆成有时间点的行动安排。',
			'要求：先确认目标与约束（截止时间、可用时段、依赖关系），再给时间线。',
			'指出关键路径和最可能延误的环节。资源冲突要直说，不要平均分配。',
		].join('\n'),
	},
	{
		key: 'digest', label: '要点提炼',
		purpose: '信息提纯与要点',
		system: [
			'你是信息提炼专家。给定的材料可能很长、也可能互相矛盾。',
			'要求：先给结论式要点，再列支撑它的具体事实。',
			'材料里没有的不要补；无法判断的地方标出来而不是猜。区分「材料说的」和「你的推断」。',
		].join('\n'),
	},
	{
		key: 'tutor', label: '教学讲解',
		purpose: '教学讲解',
		system: [
			'你是讲解专家。目标是让对方真的会，不是让对方觉得看懂了。',
			'要求：先讲直觉和为什么，再讲定义与形式。',
			'给一个具体例子，并指出初学者最容易在哪一步卡住。',
		].join('\n'),
	},
	{
		key: 'writer', label: '写作润色',
		purpose: '写作润色',
		system: [
			'你是写作专家。',
			'要求：保持原意与原作者语气，只改表达。删掉冗余与套话，长句拆短。',
			'不要替作者增加观点，也不要把内容改成它原本不是的样子。',
			].join('\n'),
	},
];

// The shared closing rules are appended here rather than repeated four times, so a change to
// them cannot land on three experts and miss the fourth.
for (const e of EXPERTS) { e.system += '\n\n' + COMMON; }

/** Strip a leading list marker. Returns null for a line that carries no marker at all. */
function stripMarker(line: string): string | null {
	// Marker forms seen in the wild: "1." "1、" "1)" "1:" and bullets. The separator class
	// also allows a bare space, so "1 甲" works without inventing a rule per model.
	const m = /^\s*(?:\d{1,2}[\s.、)）:：]+|[·•\-*]\s*)\s*(.+)$/.exec(line);
	return m && m[1].trim() ? m[1].trim() : null;
}

/**
 * Parse the splitter's reply into subtasks.
 *
 * Deliberately forgiving about FORMAT and strict about COUNT: a model that returns one item per
 * line without markers is still a usable plan, but a model that returns twelve items does not get
 * twelve workers, because the cap is what keeps this to one bounded burst of spend.
 */
export function parseSubtasks(text: string): string[] {
	const lines = String(text ?? '').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
	if (!lines.length) { return []; }

	// Pass 1: lines that carry a marker. When the model wrote a list, it says so, and
	// everything unmarked around it ("拆分如下：", a trailing note) is preamble, not a step.
	const marked: string[] = [];
	for (const l of lines) {
		const m = stripMarker(l);
		if (m) { marked.push(m); }
	}
	if (marked.length) { return marked.slice(0, EXPERT_LIMITS.maxSubtasks); }

	// Pass 2: nothing was marked, so maybe the model just wrote one item per line. Prose is
	// also one sentence per line, so a SINGLE such line is indistinguishable from an essay
	// — and running an essay as one subtask spends two more calls to produce nonsense. Two
	// lines is where "list without markers" starts being the likelier reading.
	if (lines.length < 2) { return []; }
	return lines.slice(0, EXPERT_LIMITS.maxSubtasks);
}

const clip = (s: string, n: number): string =>
	(String(s ?? '').length > n ? String(s).slice(0, n) + '…（已截断）' : String(s ?? ''));

export function registerExpertTools(reg: ToolRegistry, deps: ExpertToolDeps): void {
	// Own the domain here rather than trusting the caller: an unset domain falls into `system`,
	// which is alwaysOn, and the tool would be un-switchable with nothing saying so.
	reg.setDomain('agent');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('ask_expert', 'confirm',
		'把一个自包含的问题交给内置专项子智能体，只取回它的答案（不会新开对话）。' +
		'可选：' + EXPERTS.map(e => `${e.key}=${e.label}`).join('、') +
		'。子智能体看不到本对话，问题要写完整。',
		{
			type: 'object',
			properties: {
				agent: { type: 'string', enum: EXPERTS.map(e => e.key) },
				question: { type: 'string', description: '要委托的问题，需自包含' },
			},
			required: ['agent', 'question'],
		},
		async (input) => {
			const key = String(input?.agent ?? '').trim();
			const q = String(input?.question ?? '').trim();
			const expert = EXPERTS.find(e => e.key === key);
			if (!expert) {
				return `[FAIL] 没有名为「${key}」的专家。可选：` + EXPERTS.map(e => e.key).join('、');
			}
			if (!q) { return '[FAIL] question 不能为空：子智能体看不到本对话，问题必须自包含。'; }
			if (q.length > EXPERT_LIMITS.maxQuestionChars) {
				return `[FAIL] 问题过长（${q.length} 字，上限 ${EXPERT_LIMITS.maxQuestionChars}）。请先精简。`;
			}
			try {
				const ans = await deps.call(expert.system, q);
				const body = clip(ans, EXPERT_LIMITS.maxWorkerChars);
				return `【${expert.label}·${expert.purpose}】\n${body || '（子智能体没有返回内容）'}`;
			} catch (e) {
				// One failed call must not look like a refusal to help.
				return `[FAIL] 调用「${expert.label}」失败：${(e as Error)?.message ?? String(e)}`;
			}
		});

	def('deep_plan', 'confirm',
		'规划器-工人模式：把一个复杂任务拆成不超过 4 个独立子任务并行执行，再汇总成一份答案。' +
		'一次调用最多 6 次模型请求（1 次拆分 + 最多 4 次执行 + 1 次汇总），确认卡会说明。' +
		'子任务没有工具权限，只能产出文字。',
		{
			type: 'object',
			properties: { task: { type: 'string', description: '要规划完成的任务（含目标与约束）' } },
			required: ['task'],
		},
		async (input) => {
			const task = String(input?.task ?? '').trim();
			if (!task) { return '[FAIL] task 不能为空。'; }
			if (task.length > EXPERT_LIMITS.maxTaskChars) {
				return `[FAIL] 任务描述过长（${task.length} 字，上限 ${EXPERT_LIMITS.maxTaskChars}）。`;
			}

			// ---- 1. split ----
			let raw = '';
			try {
				raw = await deps.call(
					[
						'你是任务规划器。把一个复杂任务拆成可以独立执行的子任务。',
						`最多 ${EXPERT_LIMITS.maxSubtasks} 个，每个子任务都必须能独立完成、彼此不依赖。`,
						'严格按下面的格式输出，不要任何解释、前言或结语：',
						'1. <子任务一>',
						'2. <子任务二>',
					].join('\n'),
					task,
				);
			} catch (e) {
				return `[FAIL] 拆分阶段调用模型失败：${(e as Error)?.message ?? String(e)}`;
			}

			const subs = parseSubtasks(clip(raw, EXPERT_LIMITS.maxSplitChars));
			if (!subs.length) {
				// Say WHAT came back. A planner that answered in prose is a real failure mode, and
				// silently running the prose as one giant subtask would spend 2 more calls on it.
				return `[FAIL] 没能从规划器输出里解析出子任务，它返回的是：\n${clip(raw, 300) || '（空）'}`;
			}

			// ---- 2. fan out, in parallel, tolerating partial failure ----
			// allSettled, not all: one slow or failing worker must not throw away the other three.
			const settled = await Promise.allSettled(subs.map(s =>
				deps.call(
					'你在独立完成一个子任务。只输出这一项的成果，不要评价其他子任务，也不要写总结。',
					`总任务：${task}\n\n你的子任务：${s}`,
				).then(v => clip(v, EXPERT_LIMITS.maxWorkerChars)),
			));

			const done: string[] = [];
			const failed: string[] = [];
			settled.forEach((r, i) => {
				if (r.status === 'fulfilled' && String(r.value ?? '').trim()) { done.push(r.value); }
				else { failed.push(subs[i]); }
			});

			if (!done.length) {
				// Every worker failed. Report that plainly with the reasons — a silent empty answer
				// here reads as "the task was too hard", which is a different problem entirely.
				const why = settled.map((r, i) =>
					r.status === 'rejected' ? `${subs[i]}：${(r.reason as Error)?.message ?? String(r.reason)}` : ''
				).filter(Boolean).join('；');
				return `[FAIL] ${done.length === 0 ? '全部子任务都失败了' : ''}（${subs.length} 个子任务）。原因：${why || '未知'}`;
			}

			// ---- 3. synthesise ----
			// One worker only: synthesis over a single result is a round trip that buys nothing,
			// so it is skipped and said out loud rather than spent silently.
			if (done.length === 1) {
				const notes = failed.length ? `\n\n（另有 ${failed.length} 个子任务失败未纳入：${failed.join('、')}）` : '';
				return `拆成 ${subs.length} 个子任务，其中 1 个成功，直接给出结果（未做汇总）：\n\n${done[0]}${notes}`;
			}

			let synth = '';
			try {
				synth = await deps.call(
					[
						'你在汇总多个子任务的独立成果，产出一份统一的答案。',
						'要求：先给结论，再按主题组织各部分成果。',
						'子任务之间的矛盾要明确指出，不要私自选一个。缺的部分直接说缺，不要补。',
					].join('\n'),
					`总任务：${task}\n\n各子任务成果：\n${done.map((d, i) => `【${i + 1}】\n${d}`).join('\n\n')}`,
				);
			} catch (e) {
				// Losing the synthesis must not lose the work. Return the parts and say why.
				return `汇总阶段调用模型失败（${(e as Error)?.message ?? String(e)}），` +
					`以下是 ${done.length} 个子任务的原始成果：\n\n` +
					done.map((d, i) => `【${i + 1}】\n${d}`).join('\n\n') +
					(failed.length ? `\n\n（另有 ${failed.length} 个子任务失败：${failed.join('、')}）` : '');
			}

			const tail = failed.length ? `\n\n注意：${failed.length} 个子任务未成功，未纳入汇总：${failed.join('、')}` : '';
			return `${synth || '（汇总模型没有返回内容）'}${tail}`;
		});
}

/**
 * `work_plan_start` is NOT implemented, and the reason is structural rather than effort:
 *
 * Phone's projects are FOLDERS ("在指定项目的文件夹里生成可打勾的清单文件", auto-creating the
 * folder when absent). PC's `Project` is a flat `{ name, emoji? }` record with no folder behind it,
 * and the workspace has no project convention of its own. Writing one here would mean inventing a
 * layout that the Files page, the export directory and eventually the phone mirror all have to
 * agree on — a cross-cutting decision, not a tool. Raised in the gap document instead of guessed.
 */