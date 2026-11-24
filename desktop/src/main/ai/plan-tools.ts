/* 计划域第二批（phone ai/ptl/PlanTools.kt 对齐，2026-10-31 assign → PC 2026-11-05）。
 *
 * 手机端把 todo_edit/todo_delete/checkin/分类做成了独立文件（execute 撞 64KB 上限前的分流）；
 * PC 这边没有那个限制，但同题拆文件的另一个理由在这里同样成立：这批工具的语义要逐条对照
 * 手机实现写（repeat 契约、软删进回收站、打卡按记录类型校验），混进 tools.ts 会变成第三种口径。
 *
 * 与手机的有意分歧（都写进了契约文档，不是偷懒）：
 * - `tag`/`labels` 在 PC 是自由字符串：PC 没有「分类管理」UI，按手机"tag 必须已存在"的规则，
 *   模型在 PC 上永远用不了标签（plan_category_list 恒空）。PC 的 plan_category_list 改为
 *   聚合在用标签 + 使用次数。
 * - `checkin` 不要求待办先开启「数据记录」（PC 编辑器没有这个开关）；记录类型走参数 kind，
 *   缺省 time。打卡数据放 sidecar 文件（checkins.json），不进 dump——手机端打卡同样不在 dump 里。
 * - `schedule_delete` PC 早已有（danger/softDelete），本模块不重复注册。
 */

import type { ToolRegistry } from './tools';
import type { Store } from '../store/store';
import { readJsonFile, writeJsonFile } from '../jsonfile';
import { repeatParamOf, repeatText, REPEAT_SYNTAX } from './recurrence';

const pad2 = (n: number): string => String(n).padStart(2, '0');

function localToday(): string {
	const d = new Date();
	return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

// ---- 打卡 sidecar（checkins.json；不进 dump——手机端打卡同样不在 dump 里） ----

export interface CheckinRecord {
	todoId: string;
	date: string; // yyyy-MM-dd
	at: number;
	kind: 'time' | 'number' | 'count' | 'text' | 'tags';
	numValue?: number;
	unit?: string;
	textValue?: string;
	tags?: string;
}

export async function readCheckins(file: string): Promise<CheckinRecord[]> {
	const { value } = await readJsonFile<CheckinRecord[]>(file, []);
	return value;
}

export async function appendCheckin(file: string, rec: CheckinRecord): Promise<void> {
	const all = await readCheckins(file);
	all.push(rec);
	await writeJsonFile(file, all.slice(-2000), 1);
}

const KIND_LABEL: Record<string, string> = {
	time: '时间', number: '数值', count: '计数', text: '短文本', tags: '多选',
};

function fmtCheckin(c: CheckinRecord): string {
	const at = new Date(c.at).toISOString().slice(0, 16).replace('T', ' ');
	const val = c.kind === 'number' || c.kind === 'count'
		? String(c.numValue ?? 0) + (c.unit ? c.unit : '')
		: (c.textValue || c.tags || '');
	return `  ${c.date} ${at}  ${val}（${KIND_LABEL[c.kind] ?? '打卡'}）`;
}

export function registerPlanTools(reg: ToolRegistry, store: Store, checkinFile: string): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	// ---- 待办域：todo_edit / todo_delete / checkin_query / checkin_add ----
	reg.setDomain('todo');

	def('todo_edit', 'confirm', '修改一条待办（标题/日期/时刻/备注/地点/标签/重复，按 id 改，只改传入字段）',
		{
			type: 'object',
			properties: {
				id: { type: 'string' },
				title: { type: 'string' },
				date: { type: 'string', description: '归属日 YYYY-MM-DD' },
				due: { type: 'string', description: '截止/时刻：HH:MM 或完整 ISO 时间（归一到 HH:MM 存 time 字段）' },
				priority: { type: 'number', description: '1 高 / 2 中 / 3 低' },
				note: { type: 'string' },
				place: { type: 'string' },
				tag: { type: 'string' },
				labels: { type: 'string' },
				repeat: { type: 'string', description: REPEAT_SYNTAX + '；none=取消重复' },
				repeat_until: { type: 'string', description: 'yyyy-MM-dd，缺省=永久' },
			},
			required: ['id'],
		},
		async (input) => {
			const id = String(input?.id ?? '');
			const list = await store.live<any>('todos');
			const cur = list.find(x => x.id === id);
			if (!cur) { return `[FAIL] 待办 id=${id} 不存在`; }
			// repeat 先解析：失败早退，别把半套改动写下去（phone todoEdit 同款纪律）。
			const rep = repeatParamOf(input);
			if (rep.error) { return `[FAIL] ${rep.error}`; }
			const patch: Record<string, unknown> = {};
			for (const k of ['title', 'note', 'place', 'tag', 'labels']) {
				const v = input?.[k];
				if (typeof v === 'string' && v.trim() !== '') { patch[k] = v.trim(); }
			}
			if (typeof input?.date === 'string' && input.date.trim()) {
				if (!/^\d{4}-\d{2}-\d{2}$/.test(input.date.trim())) {
					return '[FAIL] 日期要 YYYY-MM-DD（相对日期先用 now 换算）。';
				}
				patch.date = input.date.trim();
			}
			if (input?.due !== undefined && String(input.due).trim() !== '') {
				// 手机 due parity：PC 的 todo.time 就是同一概念（home timeline 的出现时刻）。
				const due = String(input.due).trim();
				const hm = /^\d{1,2}:\d{2}$/.test(due) ? due
					: (/^\d{4}-\d{2}-\d{2}T/.test(due) ? due.slice(11, 16) : '');
				if (!hm) { return '[FAIL] due 要 HH:MM 或完整 ISO 时间。'; }
				patch.time = hm;
			}
			if (input?.priority !== undefined && input?.priority !== null && input?.priority !== '') {
				const p = Number(input.priority);
				if (![1, 2, 3].includes(p)) { return '[FAIL] priority 要 1（高）/2（中）/3（低）。'; }
				patch.priority = p;
			}
			if (rep.rule) { patch.repeat = rep.rule; } else if (rep.clear) { patch.repeat = undefined; }
			if (!Object.keys(patch).length) { return '[FAIL] 没有任何要改的字段（至少传一个）。'; }
			const rec = await store.upsert('todos', { ...cur, ...patch });
			return `已修改待办「${rec.title}」（编号 ${rec.id}）${repeatText(rec.repeat, rec.date)}`;
		});

	def('todo_delete', 'danger', '删除一条待办（danger，需用户确认；进回收站可撤销），需 id',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const id = String(input?.id ?? '');
			const list = await store.live<any>('todos');
			const cur = list.find(x => x.id === id);
			if (!cur) { return `[FAIL] 待办 id=${id} 不存在`; }
			await store.softDelete('todos', id);
			return `已删除待办「${cur.title}」（进了回收站，可用 trash_restore 恢复）`;
		});

	def('checkin_query', 'auto', '查询待办的打卡/数据记录（体重、喝水、习惯打卡等）',
		{
			type: 'object',
			properties: { todo_id: { type: 'string', description: '待办 id（可选；缺省=列出所有有打卡记录的待办）' } },
		},
		async (input) => {
			const all = await readCheckins(checkinFile);
			const tid = String(input?.todo_id ?? '').trim();
			if (tid) {
				const todo = (await store.live<any>('todos')).find(x => x.id === tid);
				if (!todo) { return `[FAIL] 待办 id=${tid} 不存在`; }
				const rows = all.filter(c => c.todoId === tid).sort((a, b) => b.at - a.at);
				if (!rows.length) { return `「${todo.title}」还没有打卡记录`; }
				return `「${todo.title}」最近记录：\n` + rows.slice(0, 30).map(fmtCheckin).join('\n') +
					`\n共 ${rows.length} 笔`;
			}
			const byTodo = new Map<string, CheckinRecord[]>();
			for (const c of all) {
				const arr = byTodo.get(c.todoId) ?? [];
				arr.push(c);
				byTodo.set(c.todoId, arr);
			}
			if (!byTodo.size) { return '还没有任何打卡记录'; }
			const lives = await store.live<any>('todos');
			const lines: string[] = [];
			for (const [tid, rows] of byTodo) {
				const todo = lives.find(x => x.id === tid);
				const latest = rows.slice().sort((a, b) => b.at - a.at)[0];
				lines.push(`「${todo?.title ?? tid}」（编号 ${tid}）：${rows.length} 笔，最近` + fmtCheckin(latest).trim());
			}
			return lines.join('\n');
		});

	def('checkin_add', 'confirm', '为待办补录一笔打卡/数据记录',
		{
			type: 'object',
			properties: {
				todo_id: { type: 'string' },
				date: { type: 'string', description: '哪一天 yyyy-MM-dd，缺省=今天' },
				kind: { type: 'string', enum: ['time', 'number', 'count', 'text', 'tags'], description: '记录类型，缺省 time' },
				value: { type: 'number', description: '数值/计数型的值（number/count 必传）' },
				text: { type: 'string', description: '短文本型的内容' },
				tags: { type: 'string', description: '多选型的选项，逗号分隔' },
				unit: { type: 'string', description: '数值单位（kg、ml 等，可选）' },
			},
			required: ['todo_id'],
		},
		async (input) => {
			const tid = String(input?.todo_id ?? '');
			const todo = (await store.live<any>('todos')).find(x => x.id === tid);
			if (!todo) { return `[FAIL] 待办 id=${tid} 不存在`; }
			const kind = (String(input?.kind ?? 'time').trim() || 'time') as CheckinRecord['kind'];
			if (!KIND_LABEL[kind]) { return '[FAIL] kind 要 time/number/count/text/tags。'; }
			const date = String(input?.date ?? '').trim() || localToday();
			if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) { return '[FAIL] 日期要 yyyy-MM-dd。'; }
			const num = input?.value === undefined || input?.value === null || input?.value === ''
				? undefined : Number(input.value);
			if ((kind === 'number' || kind === 'count') && (num === undefined || Number.isNaN(num))) {
				return `[FAIL] 这个待办记录${KIND_LABEL[kind]}，要传 value（数值）。`;
			}
			const text = String(input?.text ?? '').trim();
			if (kind === 'text' && !text && num === undefined) { return '[FAIL] 这个待办记录短文本，要传 text。'; }
			const rec: CheckinRecord = {
				todoId: tid, date, at: Date.now(), kind,
				numValue: num,
				unit: String(input?.unit ?? '').trim() || undefined,
				textValue: text,
				tags: String(input?.tags ?? '').trim() || undefined,
			};
			await appendCheckin(checkinFile, rec);
			return '已记一笔：\n' + fmtCheckin(rec) + `\n（「${todo.title}」）`;
		});

	// ---- schedule 域：plan_category_list ----
	reg.setDomain('schedule');

	def('plan_category_list', 'auto', '列出计划数据在用的分类标签与使用次数。kind: tag/all（PC 没有「地点/文件夹」分类，这两类恒空）',
		{ type: 'object', properties: { kind: { type: 'string', enum: ['tag', 'all'], description: '缺省 all' } } },
		async (input) => {
			const kind = String(input?.kind ?? 'all').toLowerCase();
			if (kind !== 'tag' && kind !== 'all') { return '[FAIL] kind 要 tag/all（PC 没有 place/folder 分类）。'; }
			const count = new Map<string, number>();
			for (const rows of [await store.live<any>('todos'), await store.live<any>('schedules')]) {
				for (const r of rows) {
					const t = String(r.tag ?? '').trim();
					if (t) { count.set(t, (count.get(t) ?? 0) + 1); }
				}
			}
			if (!count.size) { return '还没有任何在用标签（todo_create/todo_edit/schedule_create 传 tag=「名字」即可用起来）'; }
			const sorted = [...count.entries()].sort((a, b) => b[1] - a[1]);
			return '标签：' + sorted.map(([n, c]) => `${n}×${c}`).join('、');
		});
}
