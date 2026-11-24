/* D1–D5 cross-end parameter alignment (2026-10-29 decisions, landed 2026-11-04).
 *
 * These assertions pin the three tools whose schemas move toward phone's shape. The point is not
 * "the field exists" but the SEMANTICS a model on either end must get the same answer for:
 *
 *   - D1 `automation_log`: id is OPTIONAL. Without it the answer is "recent runs across all
 *     tasks", not "[FAIL] 需要 id". `limit` caps the merged list.
 *   - D2 `data_history`: `days` filters by updatedAt (fallback createdAt). Absent means the full
 *     history — and the reply says which window it used, so the model does not have to guess.
 *   - D5 `schedule_create`: all-day is EXPLICIT (`allDay=true` + `date`); anything else needs a
 *     real startIso. A schedule with neither is refused instead of silently becoming an
 *     ambiguous row that isDue/ICS have to paper over.
 *
 * Run: npm run test:align
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-align.txt');
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
const { registerAutomationTools } = require(path.join(ai, 'automation-tools.js'));
const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-align-'));
const store = new Store(dir);
const undo = { push: async () => { }, undoLast: async () => null };
const memLog = { append: async () => ({ seq: 1 }), project: async () => ({ applied: 0, rejected: 0 }) };
const reg = registerPlanabilityTools(store, undo, {
	memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
});

// Two automations with interleaved history — the all-task view must merge, not concatenate.
const automations = [
	{ id: 1, name: '早报', prompt: 'p', enabled: true, lastRun: 0, createdAt: 0 },
	{ id: 2, name: '周报', prompt: 'p', enabled: true, lastRun: 0, createdAt: 0 },
];
const history = {
	1: [{ at: 1000, ok: true, runId: 'r1' }, { at: 3000, ok: false, error: 'boom' }],
	2: [{ at: 2000, ok: true, runId: 'r2' }],
};
registerAutomationTools(reg, {
	list: async () => automations,
	create: async () => automations[0],
	update: async () => null,
	remove: async () => false,
	setEnabled: async () => true,
	recentLog: async () => '',
	taskHistory: async (id, n) => (history[id] || []).slice(0, n ?? 20),
});

const call = async (name, input) => reg.get(name).invoke(input);

(async () => {
	// ---- D1 automation_log ----
	{
		const def = reg.get('automation_log');
		check('D1: id is optional in schema', !(def.inputSchema.required || []).includes('id'));
		check('D1: limit is advertised', typeof def.inputSchema.properties.limit === 'object');
		const all = await call('automation_log', {});
		check('D1: no id -> all-task view, newest first', all.includes('全部任务')
			&& all.indexOf('早报') >= 0 && all.indexOf('周报') >= 0
			&& all.indexOf('boom') < all.indexOf('周报') /* 3000 的失败行排在 2000 的周报行前 */);
		const lim = await call('automation_log', { limit: 1 });
		check('D1: limit caps the merged list', lim.split('\n').length === 2); // 标题 + 1 行
		const one = await call('automation_log', { id: 1 });
		check('D1: id still answers that task only', one.includes('运行记录') && !one.includes('周报'));
		const bad = await call('automation_log', { id: 99 });
		check('D1: unknown id refuses', String(bad).startsWith('[FAIL]'));
	}

	// ---- D2 data_history ----
	{
		const def = reg.get('data_history');
		check('D2: days is advertised', typeof def.inputSchema.properties.days === 'object');
		await store.upsert('notes', { id: '', createdAt: 0, updatedAt: 0, title: 'n1', body: 'b' });
		const plain = await call('data_history', { type: 'note' });
		check('D2: no days -> no window banner', !plain.includes('最近') || !plain.includes('天有更新'));
		const win = await call('data_history', { type: 'note', days: 30 });
		check('D2: days -> banner says the window', win.includes('最近 30 天'));
		check('D2: fresh rows are inside the window', /笔记 1 条/.test(win));
		// Nothing is older than 365d here, but the filter must still run (clamped, not crashed).
		const wide = await call('data_history', { type: 'note', days: 9999 });
		check('D2: days clamps to 365 instead of trusting the model', wide.includes('最近 365 天'));
	}

	// ---- D5 schedule_create ----
	{
		const noStart = await call('schedule_create', { title: '无时间' });
		check('D5: no start and no allDay is refused', String(noStart).startsWith('[FAIL]'));
		const badIso = await call('schedule_create', { title: '坏时间', startIso: '不是时间' });
		check('D5: unparsable startIso is refused', String(badIso).startsWith('[FAIL]'));
		const badAllDay = await call('schedule_create', { title: '全天缺日期', allDay: true });
		check('D5: allDay without date is refused', String(badAllDay).startsWith('[FAIL]'));
		const allDay = await call('schedule_create', { title: '全天会议', allDay: true, date: '2026-11-04' });
		check('D5: allDay + date creates', String(allDay).includes('已创建全天日程'));
		const timed = await call('schedule_create', { title: '评审', startIso: '2026-11-04T15:00:00+08:00' });
		check('D5: startIso alone creates and derives the date', String(timed).includes('2026-11-04'));
		const rows = await store.live('schedules');
		const ad = rows.find(r => r.title === '全天会议');
		const tm = rows.find(r => r.title === '评审');
		check('D5: all-day row carries no startIso', ad && !ad.startIso && ad.date === '2026-11-04');
		check('D5: timed row carries startIso and its date', tm && tm.startIso === '2026-11-04T15:00:00+08:00' && tm.date === '2026-11-04');
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
