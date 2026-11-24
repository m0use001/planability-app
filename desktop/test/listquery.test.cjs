/* list_query tool tests — the last N-class gap.
 *
 * The assertion that matters most is the FIRST one about the schema:
 *
 *   A model query and a card query must be the SAME query, through the SAME gate.
 *
 * `list` cards are re-renderable without a model round trip because the card carries a query and
 * `normalizeListQuery` validates it (unknown key refused, limit clamped, applied limit echoed).
 * If this tool accepted a looser shape, the model could ask something the card cannot express —
 * and then the model's answer and the card's contents would be two different views of the store.
 * So the tool hands its args to the very same validator and reports the APPLIED limit, not the
 * asked-for one.
 *
 * The other thing pinned here: the reply names the COLLECTION and says read-only. The collection
 * names (schedules/todos/notes) are the same words the write tools use, and a model reading
 * "schedules" in a reply about a query it just ran might otherwise assume it created one.
 *
 * Run: npm run test:listquery
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-listquery.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { ToolRegistry } = require(path.join(ai, 'tools.js'));
const { registerListQueryTool } = require(path.join(ai, 'listquery-tools.js'));
// LIST_LIMIT_MAX is exported by list-card.ts (the clamp lives there), not the tool module.
const { LIST_LIMIT_MAX } = require(path.join(ai, 'list-card.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

const TS = (s) => new Date(s).toISOString();

function env(rows, over) {
	const seen = [];
	const reg = new ToolRegistry();
	registerListQueryTool(reg, Object.assign({
		run: async (q) => {
			seen.push({ q });
			// Mirror the real runListQuery: it slices by q.limit, so a clamped limit shows up in
// the reply as fewer rows rather than being silently ignored.
const sliced = rows.slice(0, q.limit);
return { query: q, rows: sliced, total: (over && over.total !== undefined) ? over.total : sliced.length };
		},
	}, over || {}));
	return { reg, seen, call: (i) => reg.get('list_query').invoke(i) };
}

const row = (label, when, detail) => ({ id: 'r' + Math.random(), label, when, detail });

(async () => {
	// ================= 1. registration =================
	{
		const e = env([]);
		check('registered: list_query', typeof e.reg.get('list_query')?.invoke === 'function');
		check('domain: system (read-only store lookups, same as data_history)',
			e.reg.get('list_query')?.domain === 'system', e.reg.get('list_query')?.domain);
		check('risk: auto — runListQuery only calls store.live, there is no write path',
			e.reg.get('list_query')?.risk === 'auto', e.reg.get('list_query')?.risk);
		check('description: says read-only', /只读/.test(String(e.reg.get('list_query')?.description ?? '')),
			e.reg.get('list_query')?.description);
	}

	// ================= 2. the happy path =================
	{
		const e = env([row('今日评审', TS('2026-10-16T09:00:00')), row('写周报', TS('2026-10-16T10:30:00'))]);
		const r = await e.call({ collection: 'todos' });
		check('run: the collection reached the deps', e.seen[0]?.q.collection === 'todos');
		check('reply: names the collection', r.includes('todos'), r);
		check('reply: SAYS read-only (the collection name reads like a write subject)',
			/只读/.test(r), r);
		check('reply: lists each row by label', r.includes('今日评审') && r.includes('写周报'), r);
		check('reply: and the stored timestamp', r.includes('2026-10-16 10:30'), r);
		check('reply: states total and returned separately (a clipped view is not the whole)',
			r.includes('命中 2 条') && r.includes('前 2 条'), r);
		check('reply: names the sort order', r.includes('更新时间'), r);
	}

	// ================= 3. SAME GATE as the card =================
	{
		// An unknown where key must be refused by normalizeListQuery, not silently ignored —
		// that is the property that keeps a model query from being a looser dialect than a card.
		const e = env([]);
		const bad = await e.call({ collection: 'todos', where: { nonsense: 1 } });
		check('gate: an unknown where key is refused, not ignored', bad.includes('[FAIL]'), bad);

		// limit clamped to the max, and the APPLIED limit is what the reply reports.
		const many = env(Array.from({ length: 80 }, (_, i) => row('第' + i + '条')));
		const r2 = await many.call({ collection: 'notes', limit: 999 });
		check('gate: a huge limit is clamped to LIST_LIMIT_MAX',
			many.seen[0]?.q.limit === LIST_LIMIT_MAX, String(many.seen[0]?.q.limit));
		check('gate: the reply reports the APPLIED limit, not the asked-for one',
			r2.includes('前 ' + LIST_LIMIT_MAX + ' 条'), r2);

		// An unknown collection is refused by the validator too.
		check('gate: an unknown collection is refused',
			(await env([]).call({ collection: 'userTable' })).includes('[FAIL]'));
	}

	// ================= 4. empty and clipping =================
	{
		const e = env([]);
		const r = await e.call({ collection: 'schedules' });
		check('empty: says the collection was queried and nothing matched',
			r.includes('schedules') && r.includes('命中 0 条'), r);

		// total > rows is the clipping case; the reply must show both.
		const clipped = env([row('a'), row('b'), row('c')], { total: 37 });
		const r2 = await clipped.call({ collection: 'notes', limit: 3 });
		check('clip: when total exceeds returned, BOTH are stated',
			r2.includes('37') && r2.includes('前 3 条'), r2);
	}

	// ================= 5. failure passthrough =================
	{
		const e = env([], { run: async () => { throw new Error('store 被锁'); } });
		const r = await e.call({ collection: 'notes' });
		check('fail: a store error is reported with its cause, not swallowed',
			r.includes('[FAIL]') && r.includes('store 被锁'), r);
	}

	// ================= 6. gating =================
	{
		const reg = new ToolRegistry();
		registerListQueryTool(reg, { run: async () => ({ query: {}, rows: [], total: 0 }) });
		// system is alwaysOn: the switch cannot hide a read-only lookup, and that is intended.
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:system': false }));
		check('gate: system is alwaysOn, so the read-only lookup survives a system-off attempt',
			!!reg.get('list_query'), 'still present');
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});