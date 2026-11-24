/**
 * The automation tools — the last of the N-class gaps (6 handlers, 0 tools).
 *
 * WHAT THIS CLOSES. `automationCreate/List/Remove/SetEnabled/Log/TaskLog` all had IPC and no tool,
 * so the UI could schedule recurring AI work and the model could not. `automationUpdate` was added
 * with this file (engine method + IPC channel + shared contract) because Android's
 * `automation_edit` has no PC counterpart and patching through remove+create would change the id.
 *
 * THE SEMANTICS ARE PHONE'S, MIRRORED RATHER THAN INVENTED.
 *
 * `automation_create` requires a TRIGGER. Phone's `hasTrigger` refuses an automation with neither
 * `intervalMinutes` nor a valid `dailyHour`, and PC's engine has the same `isDue` shape — but PC's
 * `create` does NOT refuse one, so a PC-created automation sits in the file and never runs. That gap
 * is invisible in the UI (it is listed, it looks enabled) and it is exactly what a model would
 * produce when told "remind me every day" without a time. The tool refuses instead.
 *
 * THE BLANK-STRING RULE, AND WHY IT IS TWO ANSWERS ONE QUESTION:
 *
 * On create, a blank prompt is a refusal. On update, a blank prompt is IGNORED — phone does
 * `takeIf { it.isNotBlank() }`, so you cannot clear a prompt by sending "". Numbers and booleans
 * apply on presence alone. Those are not inconsistent: they are the difference between "I did not
 * mention it" and "I said it is empty".
 *
 * AND THE THING I HAD WRONG BEFORE WRITING THIS FILE:
 *
 * I recorded that `automation_edit` needed `taskText` empty-string semantics "aligned with phone".
 * That was a misreading. Phone has no `taskText` at all. On PC, `taskText` is a kind='agent' field
 * that OVERRIDES the workflow's own default goal — so "" legitimately means "use the workflow's
 * task". That is precisely why the blank-is-ignored rule is correct here rather than merely
 * inherited: applying a blank would silently drop an override the user had set.
 */

import type { ToolRegistry } from './tools';
import type { Automation } from './automation';

export interface AutomationToolDeps {
	list: () => Promise<Automation[]>;
	create: (input: {
		name: string; prompt: string;
		intervalMinutes?: number; dailyHour?: number; dailyMinute?: number;
		kind?: string; workflowId?: string; taskText?: string; timeoutMinutes?: number;
	}) => Promise<Automation>;
	/** null when no such id — the caller MUST NOT read that as success. */
	update: (id: number, patch: Partial<Automation>) => Promise<Automation | null>;
	remove: (id: number) => Promise<boolean>;
	setEnabled: (id: number, on: boolean) => Promise<boolean>;
	recentLog: (id: number) => Promise<string>;
	taskHistory: (id: number, n?: number) => Promise<any[]>;
}

const MAX = { nameChars: 80, promptChars: 4000, listLimit: 50, historyLimit: 20 } as const;

const clip = (s: string, n: number): string =>
	(String(s ?? '').length > n ? String(s).slice(0, n) + '…（已截断）' : String(s ?? ''));

/** Human-readable trigger, so the reply says WHEN it fires rather than "configured". */
export function triggerText(a: Partial<Automation>): string {
	const iv = Number(a.intervalMinutes ?? 0);
	if (iv > 0) { return iv >= 1440 ? `每 ${Math.round(iv / 1440)} 天` : iv >= 60 ? `每 ${iv / 60} 小时` : `每 ${iv} 分钟`; }
	const h = a.dailyHour;
	if (h !== undefined && h >= 0 && h <= 23) {
		return `每天 ${String(h).padStart(2, '0')}:${String(a.dailyMinute ?? 0).padStart(2, '0')}`;
	}
	return '（没有触发条件）';
}

/** Has a trigger at all? The same question phone's `hasTrigger` and PC's `isDue` both ask. */
export function hasTrigger(a: Partial<Automation>): boolean {
	const iv = Number(a.intervalMinutes ?? 0);
	const h = a.dailyHour;
	return iv > 0 || (h !== undefined && h >= 0 && h <= 23);
}

/** One row for the list. The trigger is the part a reader acts on, so it is always shown. */
function rowOf(a: Automation): string {
	const on = a.enabled ? '启用' : '停用';
	const kind = a.kind === 'agent' ? `workflow=${a.workflowId ?? '?'}${a.taskText ? '（有自定义目标）' : ''}` : '单次提问';
	return `${a.id}  ${a.name}  [${on}]  ${triggerText(a)}  ${kind}`;
}

export function registerAutomationTools(reg: ToolRegistry, deps: AutomationToolDeps): void {
	// Own the domain rather than trusting the caller: an unset domain lands in `system`, which is
	// alwaysOn, and the tool would be un-switchable with nothing saying a switch ignored it.
	// Android keeps automation in its own domain; PC folds it into `extend`, which is where the
	// assistant/skill/mcp tools already live — one switch for "the model may manage my extensions".
	reg.setDomain('extend');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('automation_list', 'auto',
		'列出自动化任务（只读）：编号、名称、启停、触发时间、以及是单次提问还是跑工作流。',
		{ type: 'object', properties: {} },
		async () => {
			const all = await deps.list();
			if (!all.length) { return '（还没有自动化任务）'; }
			const shown = all.slice(0, MAX.listLimit);
			return shown.map(rowOf).join('\n') + (all.length > shown.length
				? `\n\n（共 ${all.length} 个，只列前 ${shown.length} 个）` : '');
		});

	def('automation_create', 'confirm',
		'创建自动化任务：到点自动执行一段 AI 指令或跑一个工作流（写操作，需用户确认）。'
		+ '必须给触发条件：intervalMinutes（间隔分钟）或 dailyHour（0-23）+ dailyMinute。',
		{
			type: 'object',
			properties: {
				name: { type: 'string', description: '任务名称' },
				prompt: { type: 'string', description: '每次触发执行的 AI 指令' },
				intervalMinutes: { type: 'number', description: '间隔分钟，如 60' },
				dailyHour: { type: 'number', description: '每日小时 0-23' },
				dailyMinute: { type: 'number', description: '每日分钟 0-59' },
				kind: { type: 'string', enum: ['ask', 'agent'], description: 'ask=单次提问（默认），agent=跑工作流' },
				workflowId: { type: 'string', description: 'kind=agent 时的工作流 id，来自 workflow_list' },
				taskText: { type: 'string', description: 'kind=agent 时的目标；留空=用工作流自带的 task' },
				timeoutMinutes: { type: 'number', description: '整个 run 的硬上限（分钟）' },
			},
			required: ['name', 'prompt'],
		},
		async (input) => {
			const name = String(input?.name ?? '').trim();
			const prompt = String(input?.prompt ?? '').trim();
			// Both required, exactly as phone refuses an empty one.
			if (!name || !prompt) { return '[FAIL] 名称与指令都不能为空。'; }
			if (name.length > MAX.nameChars) { return `[FAIL] 名称过长（上限 ${MAX.nameChars} 字）。`; }
			if (prompt.length > MAX.promptChars) { return `[FAIL] 指令过长（上限 ${MAX.promptChars} 字）。`; }

			const kind = String(input?.kind ?? 'ask');
			if (kind === 'agent' && !String(input?.workflowId ?? '').trim()) {
				// The engine would accept this and every run would fail with "unknown workflow".
				return '[FAIL] kind=agent 时必须给 workflowId（来自 workflow_list）。';
			}

			const draft = {
				name, prompt,
				intervalMinutes: num(input?.intervalMinutes),
				dailyHour: num(input?.dailyHour),
				dailyMinute: num(input?.dailyMinute),
				kind: kind === 'agent' ? 'agent' as const : 'ask' as const,
				workflowId: input?.workflowId ? String(input.workflowId) : undefined,
				taskText: input?.taskText ? String(input.taskText) : undefined,
				timeoutMinutes: num(input?.timeoutMinutes),
			};
			// THE refusal that matters. PC's create() does not check, so an automation with no
			// trigger is written, listed, looks enabled, and never fires — invisible in the UI.
			if (!hasTrigger(draft)) {
				return '[FAIL] 需要配置触发：给 intervalMinutes，或 dailyHour（0-23）+ dailyMinute。' +
					'没有触发条件的自动化会被存下来、列得出来、看着是启用的，但永远不会执行。';
			}
			if (draft.dailyHour !== undefined && (draft.dailyHour < 0 || draft.dailyHour > 23)) {
				return '[FAIL] dailyHour 必须在 0-23 之间。';
			}

			const rec = await deps.create(draft);
			return `已创建自动化「${rec.name}」（编号 ${rec.id}），${triggerText(rec)}，` +
				`${rec.kind === 'agent' ? `到点跑工作流 ${rec.workflowId}` : '到点执行一次 AI 提问'}。`;
		});

	def('automation_update', 'confirm',
		'修改一个自动化任务（写操作，需用户确认）。只改给出的字段。'
		+ '注意：空白字符串等于"没提这个字段"，所以不能用空串清空指令；'
		+ '数字和布尔只要给了就算，包括 0 和 false。',
		{
			type: 'object',
			properties: {
				id: { type: 'number', description: '自动化编号，来自 automation_list' },
				name: { type: 'string' },
				prompt: { type: 'string' },
				intervalMinutes: { type: 'number' },
				dailyHour: { type: 'number' },
				dailyMinute: { type: 'number' },
				enabled: { type: 'boolean' },
				kind: { type: 'string', enum: ['ask', 'agent'] },
				workflowId: { type: 'string' },
				taskText: { type: 'string', description: '留空=沿用工作流自带的 task' },
				timeoutMinutes: { type: 'number' },
			},
			required: ['id'],
		},
		async (input) => {
			const id = Number(input?.id);
			if (!Number.isFinite(id) || id <= 0) { return '[FAIL] id 无效，先用 automation_list 看看编号。'; }
			const patch: Partial<Automation> = {};
			for (const k of ['name', 'prompt', 'intervalMinutes', 'dailyHour', 'dailyMinute',
				'enabled', 'kind', 'workflowId', 'taskText', 'timeoutMinutes']) {
				if (input?.[k] !== undefined) { (patch as any)[k] = input[k]; }
			}
			if (!Object.keys(patch).length) { return '[FAIL] 没有给出任何要改的字段。'; }
			const after = await deps.update(id, patch);
			// "no such id" must not read as success: an edit aimed at a stale id otherwise looks done.
			if (!after) { return `[FAIL] 没有编号为 ${id} 的自动化任务（可能已删）。`; }
			return `已修改自动化「${after.name}」（编号 ${id}）：${triggerText(after)}，` +
				`${after.enabled ? '启用' : '停用'}。` +
				(Object.prototype.hasOwnProperty.call(patch, 'prompt') && !String(patch.prompt ?? '').trim()
					? '\n注意：这次没有清空指令 —— 空字符串算「没提这个字段」，要清得先在设置里手动改。'
					: '');
		});

	def('automation_delete', 'danger',
		'删除一个自动化任务（danger，需用户确认）。删除后它不再执行。',
		{
			type: 'object',
			properties: { id: { type: 'number', description: '自动化编号' } },
			required: ['id'],
		},
		async (input) => {
			const id = Number(input?.id);
			if (!Number.isFinite(id) || id <= 0) { return '[FAIL] id 无效。'; }
			const all = await deps.list();
			const hit = all.find(a => a.id === id);
			if (!hit) { return `[FAIL] 没有编号为 ${id} 的自动化任务。`; }
			const ok = await deps.remove(id);
			return ok ? `已删除自动化「${hit.name}」（编号 ${id}）。它不再执行。` : `[FAIL] 删除 ${id} 失败。`;
		});

	def('automation_set_enabled', 'confirm',
		'暂停或恢复一个自动化任务（写操作，需用户确认）。暂停后它还在，只是不再执行。',
		{
			type: 'object',
			properties: {
				id: { type: 'number', description: '自动化编号' },
				enabled: { type: 'boolean', description: 'true=恢复，false=暂停' },
			},
			required: ['id', 'enabled'],
		},
		async (input) => {
			const id = Number(input?.id);
			if (!Number.isFinite(id) || id <= 0) { return '[FAIL] id 无效。'; }
			if (typeof input?.enabled !== 'boolean') { return '[FAIL] enabled 必须是 true/false。'; }
			const ok = await deps.setEnabled(id, input.enabled);
			if (!ok) { return `[FAIL] 没有编号为 ${id} 的自动化任务。`; }
			return `已${input.enabled ? '恢复' : '暂停'}编号 ${id} 的自动化任务。` +
				(input.enabled ? '' : '任务还在列表里，只是不再执行。');
		});

	def('automation_log', 'auto',
		'看自动化的执行记录（只读）。id 可选：不传=查全部任务最近的记录；limit 条数（1-50，缺省 20）。id 来自 automation_list。',
		{
			type: 'object',
			properties: {
				id: { type: 'number', description: '自动化编号；不传=查全部任务' },
				limit: { type: 'number', description: '最多返回多少条（1-50，缺省 20）' },
			},
		},
		async (input) => {
			const limit = Math.max(1, Math.min(50, Math.floor(Number(input?.limit) || MAX.historyLimit)));
			const rawId = input?.id;
			const id = rawId === undefined || rawId === null || rawId === '' ? null : Number(rawId);
			const all = await deps.list();
			if (id !== null) {
				if (!Number.isFinite(id) || id <= 0) { return '[FAIL] id 无效。'; }
				if (!all.some(a => a.id === id)) { return `[FAIL] 没有编号为 ${id} 的自动化任务。`; }
				// Two histories: the structured one (runId, machine-readable) and the markdown tail the
				// ask path appends. Both are shown; neither can stand in for the other.
				const rows = await deps.taskHistory(id, limit);
				const structured = rows.length
					? '运行记录：\n' + rows.map(fmtHistoryRow).join('\n')
					: '（还没有结构化运行记录）';
				let md = '';
				try { md = await deps.recentLog(id); } catch { md = ''; }
				return structured + (md ? '\n\n日志尾部：\n' + clip(md, 2000) : '');
			}
			// No id: merge every task's structured rows, newest first. Phone's automation_log has the
			// same shape ("id 不传 = 查全部任务的最近记录") — the model must get the same answer on
			// both ends (D1 of the 2026-10-29 cross-end alignment), not "[FAIL] 需要 id".
			const merged: { at: number; name: string; ok: boolean; runId?: string; error?: string }[] = [];
			for (const a of all) {
				let rows: any[] = [];
				try { rows = await deps.taskHistory(a.id, limit); } catch { rows = []; }
				for (const r of rows) {
					merged.push({ at: Number(r.at ?? 0), name: a.name, ok: !!r.ok, runId: r.runId, error: r.error });
				}
			}
			merged.sort((x, y) => y.at - x.at);
			const top = merged.slice(0, limit);
			if (!top.length) { return '（所有任务都还没有结构化运行记录）'; }
			return `最近执行记录（${top.length} 条，全部任务，按时间倒序）：\n` + top.map(r => {
				const at = new Date(r.at).toISOString().slice(0, 16).replace('T', ' ');
				return `  ${at}  ${r.name}  ${r.ok ? '成功' : '失败'}${r.runId ? `  run=${r.runId}` : ''}${r.error ? '  ' + r.error : ''}`;
			}).join('\n');
		});
}

/** One structured history row, shared by the single-task and all-task views. */
function fmtHistoryRow(r: any): string {
	const at = new Date(Number(r.at ?? 0)).toISOString().slice(0, 16).replace('T', ' ');
	return `  ${at}  ${r.ok ? '成功' : '失败'}${r.runId ? `  run=${r.runId}` : ''}${r.error ? '  ' + r.error : ''}`;
}

/** Number or undefined. A non-numeric string must not become NaN and write itself to disk. */
function num(v: unknown): number | undefined {
	if (v === undefined || v === null || v === '') { return undefined; }
	const n = Number(v);
	return Number.isFinite(n) ? n : undefined;
}