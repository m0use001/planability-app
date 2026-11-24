/* 生活数据工具（phone ai/ptl/LifeTools.kt 对齐，2026-10-31 assign → PC 2026-11-05）。
 *
 * 域归属照手机：focus_query/focus_add = 新「focus」域（shared/tool-domains.ts 已加）；
 * weekly_query/trash_query/trash_restore = system；notebook_* 与 note_edit/note_delete = note。
 *
 * 与手机的有意分歧（契约文档有记，不是漏做）：
 * - `weekly_query` 手机读的是信息页生成的 LLM 周小结存档；PC 没有这个系统，本端改为
 *   **按库统计的当周小结**（待办完成/日程/笔记/专注分钟/打卡），回复里标明统计口径。
 * - `achievement_query`、`pending_add` 不在 PC 注册：PC 没有 XP/徽章系统，也没有草稿确认流的
 *   批准 UI（写了没人批）——PC 的"半写"闸门就是 confirm 审批卡本身。跳过属 assign 预授权的
 *   「PC 无此概念可不实现」类（与 screen_time_query 同款处理），均已在清单注明。
 * - `focus_add` 写的 focus.json 与主进程专注计时器共用同一份（IPC.focusLog 同形状，
 *   追加 completed 字段；旧记录无该字段按完成计）。
 */

import type { ToolRegistry } from './tools';
import type { Store } from '../store/store';
import { readJsonFile, writeJsonFile } from '../jsonfile';

const pad2 = (n: number): string => String(n).padStart(2, '0');

function localDayOf(at: number): string {
	const d = new Date(at);
	return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

function localToday(): string {
	return localDayOf(Date.now());
}

interface FocusEntry { at: number; seconds: number; completed?: boolean }

async function readFocus(file: string): Promise<FocusEntry[]> {
	const { value } = await readJsonFile<FocusEntry[]>(file, []);
	return value;
}

async function writeFocus(file: string, entries: FocusEntry[]): Promise<void> {
	await writeJsonFile(file, entries.slice(-500), 1);
}

export function registerLifeTools(reg: ToolRegistry, store: Store, dataDir: string): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	const focusFile = `${dataDir}/focus.json`;

	// ---- focus 域（手机 2026-10-31 新增域的 PC 对应） ----
	reg.setDomain('focus');

	def('focus_query', 'auto', '查询最近的专注记录（每日分钟数/次数/完成数，含累计）',
		{ type: 'object', properties: { days: { type: 'number', description: '查询最近几天，缺省 7' } } },
		async (input) => {
			const days = Math.max(1, Math.min(90, Math.floor(Number(input?.days) || 7)));
			const sessions = await readFocus(focusFile);
			const todayEd = localToday();
			const fromEd = (() => {
				const d = new Date(Date.now() - (days - 1) * 864e5);
				return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
			})();
			const totalMin = Math.round(sessions.reduce((s, x) => s + (Number(x.seconds) || 0), 0) / 60);
			const completed = sessions.filter(x => x.completed !== false).length;
			const inWindow = sessions.filter(x => {
				const dk = localDayOf(Number(x.at) || 0);
				return dk >= fromEd && dk <= todayEd;
			});
			if (!inWindow.length) {
				return `最近 ${days} 天没有专注记录（累计：${totalMin} 分钟，完成 ${completed} 次）`;
			}
			const byDay = new Map<string, FocusEntry[]>();
			for (const s of inWindow) {
				const dk = localDayOf(Number(s.at) || 0);
				const arr = byDay.get(dk) ?? [];
				arr.push(s);
				byDay.set(dk, arr);
			}
			const lines = [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1)).map(([dk, ss]) =>
				`  ${dk}：${Math.round(ss.reduce((s, x) => s + (Number(x.seconds) || 0), 0) / 60)} 分钟 × ${ss.length} 次` +
				`（完成 ${ss.filter(x => x.completed !== false).length}）`);
			return `最近 ${days} 天的专注：\n${lines.join('\n')}\n累计 ${totalMin} 分钟，完成 ${completed} 次`;
		});

	def('focus_add', 'confirm', '补记一次刚结束的专注（按现在往回推开始时刻）。不能替用户开始/结束专注',
		{
			type: 'object',
			properties: {
				planned_min: { type: 'number', description: '专注时长（分钟，1-240）' },
				completed: { type: 'boolean', description: '是否完成，缺省 true' },
			},
			required: ['planned_min'],
		},
		async (input) => {
			const min = Math.floor(Number(input?.planned_min) || 0);
			if (min <= 0 || min > 240) { return '[FAIL] planned_min 要是 1-240 的分钟数'; }
			const completed = input?.completed !== false;
			const sessions = await readFocus(focusFile);
			sessions.push({ at: Date.now(), seconds: min * 60, completed });
			await writeFocus(focusFile, sessions);
			return `已补记一次专注：${min} 分钟` + (completed ? '（完成）' : '（未完成）');
		});

	// ---- system 域：周小结（统计口径） + 回收站 ----
	reg.setDomain('system');

	def('weekly_query', 'auto', '查询本周小结（PC 端为按库统计口径：待办完成/日程/笔记/专注/打卡，最近 7 天）',
		{ type: 'object', properties: {} },
		async () => {
			const cutoff = Date.now() - 7 * 864e5;
			const inWindow = (x: { updatedAt?: number; createdAt?: number }): boolean =>
				Number(x.updatedAt ?? x.createdAt ?? 0) >= cutoff;
			const todos = (await store.live<any>('todos')).filter(inWindow);
			const done = todos.filter((t: any) => t.status === 'done');
			const schedules = (await store.live<any>('schedules')).filter(inWindow);
			const notes = (await store.live<any>('notes')).filter(inWindow);
			const focus = (await readFocus(`${dataDir}/focus.json`)).filter(x => Number(x.at) >= cutoff);
			const focusMin = Math.round(focus.reduce((s, x) => s + (Number(x.seconds) || 0), 0) / 60);
			const lines = [
				'最近 7 天（统计口径；PC 端没有 LLM 周小结存档，手机端才有）：',
				`  待办：动了 ${todos.length} 条，其中完成 ${done.length} 条`,
				`  日程：动了 ${schedules.length} 条${schedules.length ? '：' + schedules.slice(-3).map((s: any) => s.title).join('、') : ''}`,
				`  笔记：新建/更新 ${notes.length} 篇`,
				`  专注：${focusMin} 分钟（${focus.length} 次）`,
			];
			return lines.join('\n');
		});

	def('trash_query', 'auto', '列出回收站里已删除的待办/日程/笔记',
		{ type: 'object', properties: {} },
		async () => {
			const trashed = async (name: Parameters<Store['load']>[0]) =>
				(await store.load(name)).filter((x: any) => x.deletedAt);
			const section = (name: string, rows: any[], open: string, close: string): string | null => {
				if (!rows.length) { return null; }
				return name + '：\n' + rows.map((r: any) =>
					`  ${open}${r.title}${close}（编号 ${r.id}，删于 ${new Date(Number(r.deletedAt)).toISOString().slice(0, 10)}）`).join('\n');
			};
			const parts = [
				section('待办', (await trashed('todos')).slice(-10), '「', '」'),
				section('日程', (await trashed('schedules')).slice(-10), '「', '」'),
				section('笔记', (await trashed('notes')).slice(-10), '《', '》'),
			].filter(Boolean);
			return parts.join('\n') || '回收站是空的';
		});

	def('trash_restore', 'confirm', '从回收站恢复一条待办/日程/笔记',
		{
			type: 'object',
			properties: { kind: { type: 'string', enum: ['todo', 'schedule', 'note'] }, id: { type: 'string' } },
			required: ['kind', 'id'],
		},
		async (input) => {
			const kind = String(input?.kind ?? '').toLowerCase();
			const id = String(input?.id ?? '');
			const col = kind === 'todo' ? 'todos' : kind === 'schedule' ? 'schedules' : kind === 'note' ? 'notes' : null;
			if (!col) { return '[FAIL] kind 要 todo/schedule/note。'; }
			const rows = await store.load(col);
			const item: any = rows.find((x: any) => x.id === id);
			if (!item) { return `[FAIL] ${kind} id=${id} 不存在`; }
			if (!(item as any).deletedAt) { return `[FAIL]「${item.title}」不在回收站里（可能已恢复）`; }
			// upsert 展开会把 deletedAt=undefined 的键丢掉（JSON 无 undefined）= 恢复。
			await store.upsert(col, { ...item, deletedAt: undefined } as any);
			return `已从回收站恢复（编号 ${id}）`;
		});

	// ---- note 域：笔记本 ×2 + 笔记补全 ×2（手机 note 域 note+4 的对齐） ----
	reg.setDomain('note');

	def('notebook_list', 'auto', '列出笔记本（含各本笔记数）',
		{ type: 'object', properties: {} },
		async () => {
			const nbs = await store.live<any>('notebooks');
			const notes = await store.live<any>('notes');
			if (!nbs.length) { return '还没有笔记本（notebook_create 建）'; }
			const lines = nbs.map((nb: any) => {
				const count = notes.filter((n: any) => n.notebookId === nb.id).length;
				return `「${nb.name}」：${count} 篇（编号 ${nb.id}）`;
			});
			const loose = notes.filter((n: any) => !n.notebookId).length;
			if (loose) { lines.push(`（未归类 ${loose} 篇——note_edit 传 notebook=「...」可归入）`); }
			return lines.join('\n');
		});

	def('notebook_create', 'confirm', '新建一个笔记本',
		{ type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
		async (input) => {
			const name = String(input?.name ?? '').trim();
			if (!name) { return '[FAIL] 笔记本名字为空'; }
			const exists = (await store.live<any>('notebooks')).some((nb: any) => nb.name === name);
			if (exists) { return `[FAIL] 已有同名笔记本「${name}」`; }
			const rec = await store.upsert('notebooks', { id: '', createdAt: 0, updatedAt: 0, name });
			return `已创建笔记本「${name}」（编号 ${rec.id}），note_create 传 notebook=「${name}」即可写进来`;
		});

	def('note_edit', 'confirm', '修改笔记（标题/正文/移动到别的笔记本）',
		{
			type: 'object',
			properties: {
				id: { type: 'string' },
				title: { type: 'string' },
				content: { type: 'string', description: '新正文（整体替换，手机 note_edit 同名参数）' },
				notebook: { type: 'string', description: '目标笔记本名（移动，可选）' },
			},
			required: ['id'],
		},
		async (input) => {
			const id = String(input?.id ?? '');
			const rows = await store.live<any>('notes');
			const cur = rows.find((x: any) => x.id === id);
			if (!cur) { return `[FAIL] 笔记 id=${id} 不存在`; }
			const patch: Record<string, unknown> = {};
			if (typeof input?.title === 'string' && input.title.trim()) { patch.title = input.title.trim(); }
			if (input?.content !== undefined && String(input.content).trim() !== '') { patch.body = String(input.content); }
			let moveTo: string | null = null;
			if (typeof input?.notebook === 'string' && input.notebook.trim()) {
				const nb = (await store.live<any>('notebooks')).find((x: any) => x.name === input.notebook.trim());
				if (!nb) { return `[FAIL] 没有叫「${input.notebook.trim()}」的笔记本（用 notebook_list 查）`; }
				moveTo = nb.id;
			}
			if (!Object.keys(patch).length && moveTo === null) {
				return '[FAIL] 没有任何要改的字段（至少传一个）';
			}
			const rec = await store.upsert('notes', { ...cur, ...patch, ...(moveTo ? { notebookId: moveTo } : {}) });
			return `已修改笔记《${rec.title}》（编号 ${id}）`;
		});

	def('note_delete', 'danger', '删除一条笔记（danger，需用户确认；进回收站可撤销）',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const id = String(input?.id ?? '');
			const rows = await store.live<any>('notes');
			const cur = rows.find((x: any) => x.id === id);
			if (!cur) { return `[FAIL] 笔记 id=${id} 不存在`; }
			await store.softDelete('notes', id);
			return `已删除笔记《${cur.title}》（进了回收站，可撤销）`;
		});
}
