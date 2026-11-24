/* 计划域第二批 + 生活数据工具（phone PlanTools/LifeTools 对齐）的行为钉子。
 *
 * 覆盖三块：
 *  1. repeat 引擎（ai/recurrence.ts）——跨端契约，语法与展开语义必须与手机 data/Recurrence.kt 同源；
 *  2. 新计划域工具 todo_edit、todo_delete、checkin 两件、plan_category_list；
 *  3. 生活数据 focus 两件、weekly_query、trash 两件、notebook 两件、note_edit/note_delete + note_create notebook。
 *
 * Run: npm run test:planlife
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-planlife.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { registerPlanabilityTools } = require(path.join(ai, 'tools.js'));
const { registerPlanTools, readCheckins } = require(path.join(ai, 'plan-tools.js'));
const { registerLifeTools } = require(path.join(ai, 'life-tools.js'));
const rec = require(path.join(ai, 'recurrence.js'));
const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-planlife-'));
const store = new Store(dir);
const undo = { push: async () => { }, undoLast: async () => null };
const memLog = { append: async () => ({ seq: 1 }), project: async () => ({ applied: 0, rejected: 0 }) };
const reg = registerPlanabilityTools(store, undo, {
	memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
});
const checkinFile = path.join(dir, 'checkins.json');
registerPlanTools(reg, store, checkinFile);
registerLifeTools(reg, store, dir);

const call = async (name, input) => reg.get(name).invoke(input);
const pad2 = n => String(n).padStart(2, '0');
function localDay(d = new Date()) {
	return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}
function isoWd(ds) {
	const [y, m, d] = ds.split('-').map(Number);
	return ((new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7) + 1;
}
const today = localDay();

(async () => {
	// ---- 1. repeat 引擎（契约核心） ----
	{
		const r = rec.parseRepeat('weekly:1,3');
		check('repeat: weekly:1,3 parses weekdays', r.repeats && r.kind === 'weekly' && r.weekdays.join(',') === '1,3');
		const bw = rec.parseRepeat('biweekly:1');
		check('repeat: biweekly flag', bw.repeats && bw.biweekly);
		check('repeat: unknown rule refuses', rec.parseRepeat('每周三') === null);
		const p = rec.repeatParamOf({ repeat: 'daily', repeat_until: '2026-12-31' });
		check('repeat: repeat_until -> until segment (epochDay)', /;until:\d+$/.test(p.rule));
		const occ = rec.occurrences(rec.parseRepeat(p.rule), '2026-12-01', '2026-12-01', '2026-12-31');
		check('repeat: occurrences stop at until', occ.length > 0 && occ[occ.length - 1] === '2026-12-31');
		check('repeat: none clears', rec.repeatParamOf({ repeat: 'none' }).clear === true);
		check('repeat: bad syntax errors with syntax text', (rec.repeatParamOf({ repeat: '每周三' }).error || '').includes('interval:14'));

		// 每周一，锚点 2026-11-02（周一）：biweekly 周序
		const bi = rec.occurrences(rec.parseRepeat('biweekly:1'), '2026-11-02', '2026-11-02', '2026-11-15');
		check('repeat: biweekly keeps week parity (11-02 in, 11-09 out)', bi.includes('2026-11-02') && !bi.includes('2026-11-09'));
		// monthly 钳月末：锚点 1/31 → 2 月只有 28
		const mo = rec.occurrences(rec.parseRepeat('monthly'), '2026-01-31', '2026-02-01', '2026-02-28');
		check('repeat: monthly clamps to month length (2/28)', mo.length === 1 && mo[0] === '2026-02-28');
		// monthlyW:2:3 = 每月第 2 个周三；2026-11 的第二个周三 = 11-11
		const mw = rec.occurrences(rec.parseRepeat('monthlyW:2:3'), '2026-11-01', '2026-11-01', '2026-11-30');
		check('repeat: monthlyW:2:3 hits 2026-11-11', mw.length === 1 && mw[0] === '2026-11-11');
		// interval:14 相位从锚点起算
		const iv = rec.occurrences(rec.parseRepeat('interval:14'), '2026-01-01', '2026-01-01', '2026-02-01');
		check('repeat: interval:14 phase-aligned (1/1, 1/15, 1/29)', iv.join(',') === '2026-01-01,2026-01-15,2026-01-29');
	}

	// ---- 2. 计划域工具 ----
	{
		const created = await call('todo_create', {
			title: '周报', repeat: 'weekly:1,3', tag: '工作', due: '2026-11-05T09:30:00+08:00',
		});
		check('todo_create: repeat + tag + due land', created.includes('[重复:') && created.includes('已创建待办'));
		const rows = await store.live('todos');
		const rpt = rows.find(t => t.title === '周报');
		check('todo_create: stores rule verbatim + due->time', rpt.repeat === 'weekly:1,3' && rpt.time === '09:30' && rpt.tag === '工作');

		const badRep = await call('todo_edit', { id: rpt.id, repeat: '每周三' });
		check('todo_edit: bad repeat refuses (no partial write)', String(badRep).startsWith('[FAIL]'));
		const edited = await call('todo_edit', { id: rpt.id, note: '写周报', repeat: 'weekly:1,3', repeat_until: '2026-12-31' });
		check('todo_edit: note + repeat_until apply', edited.includes('已修改待办') && edited.includes('12/31'));
		const afterEdit = (await store.live('todos')).find(t => t.title === '周报');
		check('todo_edit: stored rule carries until', /;until:\d+$/.test(afterEdit.repeat));

		const clearRep = await call('todo_edit', { id: rpt.id, repeat: 'none' });
		check('todo_edit: repeat none clears', !(await store.live('todos')).find(t => t.title === '周报').repeat);
		check('todo_edit: no-op refuses', String(await call('todo_edit', { id: rpt.id })).startsWith('[FAIL]'));

		// 打卡：number 型必须带 value
		const ck = await call('checkin_add', { todo_id: rpt.id, kind: 'number', value: 62.5, unit: 'kg' });
		check('checkin_add: number record lands', ck.includes('62.5'));
		const badCk = await call('checkin_add', { todo_id: rpt.id, kind: 'number' });
		check('checkin_add: number without value refuses', String(badCk).startsWith('[FAIL]'));
		const q = await call('checkin_query', { todo_id: rpt.id });
		check('checkin_query: lists the record', q.includes('62.5') && q.includes('共 1 笔'));
		const stored = await readCheckins(checkinFile);
		check('checkin: sidecar file holds records (not in dump collections)', stored.length === 1 && stored[0].todoId === rpt.id);

		const del = await call('todo_delete', { id: rpt.id });
		check('todo_delete: soft-deletes into trash', del.includes('回收站'));
		const trash = await call('trash_query');
		check('trash_query: sees the deleted todo', trash.includes('周报'));
		const restored = await call('trash_restore', { kind: 'todo', id: rpt.id });
		check('trash_restore: brings it back', restored.includes('已从回收站恢复'));
		check('trash_restore: todo is live again', (await store.live('todos')).some(t => t.id === rpt.id));
	}

	// ---- 3. 日程 repeat 展开 ----
	{
		const wd = isoWd(today);
		const hit = await call('schedule_create', { title: '周会', allDay: true, date: today, repeat: `weekly:${wd}` });
		check('schedule_create: weekly rule stored', hit.includes('已创建') && hit.includes('[重复:'));
		const miss = await call('schedule_create', {
			title: '周三例会', allDay: true, date: today, repeat: `weekly:${wd === 7 ? 1 : wd + 1}`,
		});
		const q = await call('schedule_query', { range: 'today' });
		check('schedule_query: recurring item expands into today', q.includes('周会') && !q.split('\n').some(l => l.includes('周三例会')));
		const q2 = await call('schedule_query', { range: 'all' });
		check('schedule_query: all view lists base rows with repeat text', q2.includes('周三例会') && q2.includes('[重复:'));
	}

	// ---- 4. 分类聚合 ----
	{
		const list = await call('plan_category_list');
		check('plan_category_list: aggregates tags with counts', list.includes('工作×1'));
		const bad = await call('plan_category_list', { kind: 'place' });
		check('plan_category_list: place/folder refused honestly', String(bad).startsWith('[FAIL]'));
	}

	// ---- 5. 生活数据 ----
	{
		const fa = await call('focus_add', { planned_min: 25 });
		check('focus_add: lands a record', fa.includes('25 分钟（完成）'));
		const badF = await call('focus_add', { planned_min: 999 });
		check('focus_add: 1-240 clamps refuse', String(badF).startsWith('[FAIL]'));
		const fq = await call('focus_query', { days: 7 });
		check('focus_query: daily line + totals', fq.includes('分钟 × 1 次') && fq.includes('累计 25 分钟'));

		const wq = await call('weekly_query');
		check('weekly_query: PC statistical summary', wq.includes('统计口径') && wq.includes('专注：25 分钟'));

		const nb = await call('notebook_create', { name: '读书笔记' });
		check('notebook_create: creates', nb.includes('已创建笔记本'));
		const dup = await call('notebook_create', { name: '读书笔记' });
		check('notebook_create: duplicate refuses', String(dup).startsWith('[FAIL]'));
		const nc = await call('note_create', { title: '读书记录', body: '第一章', notebook: '读书笔记' });
		check('note_create: notebook param files the note', nc.includes('已创建笔记'));
		const badNb = await call('note_create', { title: 'x', notebook: '不存在的本' });
		check('note_create: unknown notebook refuses', String(badNb).startsWith('[FAIL]'));
		const nl = await call('notebook_list');
		check('notebook_list: counts per notebook', nl.includes('「读书笔记」：1 篇'));

		const noteId = (await store.live('notes')).find(n => n.title === '读书记录').id;
		const ne = await call('note_edit', { id: noteId, content: '第二章', title: '读书记录v2' });
		check('note_edit: content + title patch', ne.includes('读书记录v2'));
		const moved = await call('note_edit', { id: noteId, notebook: '读书笔记' });
		check('note_edit: no-op with same notebook still ok', moved.includes('已修改笔记'));

		const nd = await call('note_delete', { id: noteId });
		check('note_delete: goes to trash', nd.includes('回收站'));
		const tq = await call('trash_query');
		check('trash_query: note section uses 《》', tq.includes('《读书记录v2》'));
		const tr = await call('trash_restore', { kind: 'note', id: noteId });
		check('trash_restore: note restored', tr.includes('已从回收站恢复'));
	}

	fs.rmSync(dir, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
