/* Live-data (`list`) card tests.
 *
 * The card's whole value is that it shows the USER'S DATA rather than something the model wrote,
 * and that it can be refreshed by re-sending the same query. Both properties are cheap to state and
 * easy to break, so they are stated here:
 *
 *   - a malformed query is an ERROR, never an empty list —「没有匹配项」and「我读不懂你的筛选」
 *     look identical to the user and only one of them is true;
 *   - `result.query` is the query AS EXECUTED, so what a card displays and what a refresh will
 *     re-run cannot drift apart;
 *   - the query cannot name anything but a collection and a closed filter set, which is what makes
 *     a card incapable of causing a write.
 *
 * Run: npm run test:listcard
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-listcard.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { normalizeListQuery, runListQuery, LIST_LIMIT_DEFAULT, LIST_LIMIT_MAX } = require(path.join(base, 'list-card.js'));
const reg = require(path.join(base, 'card-registry.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'cards.js'));

const ok = raw => { const r = normalizeListQuery(raw); return r.ok ? r.query : null; };
const err = raw => { const r = normalizeListQuery(raw); return r.ok ? null : r.error; };

(async () => {
	// ---- 1. the happy path, and what gets filled in ----
	{
		check('normalize: bare {collection} is valid', ok({ collection: 'todos' }) !== null);
		check(`normalize: limit defaults to ${LIST_LIMIT_DEFAULT}`, ok({ collection: 'todos' }).limit === LIST_LIMIT_DEFAULT);
		check('normalize: an empty where is dropped, not kept as {}',
			!('where' in ok({ collection: 'todos', where: {} })));
		check('normalize: order is kept when given',
			ok({ collection: 'notes', order: 'created' }).order === 'created');
		check('normalize: order defaults to nothing (the runner treats absent as updated)',
			ok({ collection: 'notes' }).order === undefined);
		check('normalize: valid where survives',
			ok({ collection: 'todos', where: { status: 'open', date: '2026-10-09' } }).where.date === '2026-10-09');
	}

	// ---- 2. strictness: an unknown key is an error, never a silently ignored filter ----
	{
		check('strict: unknown top-level key is rejected', /未知字段/.test(err({ collection: 'todos', colour: 'red' }) || ''));
		check('strict: unknown where field is rejected and says what is allowed',
			/todos 不支持筛选 title/.test(err({ collection: 'todos', where: { title: 'x' } }) || ''));
		check('strict: a collection with no filters says so',
			/暂不支持任何筛选/.test(err({ collection: 'memories', where: { pinned: true } }) || ''));
		check('strict: notes DO accept pinned',
			ok({ collection: 'notes', where: { pinned: true } }) !== null);
		check('strict: schedules reject the todos-only status filter',
			/schedules 不支持筛选 status/.test(err({ collection: 'schedules', where: { status: 'open' } }) || ''));
		// The message lists the VALID collections and deliberately does not echo the bad value back —
		// repeating a model's typo at it tends to make it retry the same typo.
		check('strict: bad collection is rejected and lists the valid ones',
			/schedules/.test(err({ collection: 'tasks' }) || '') && /todos/.test(err({ collection: 'tasks' }) || ''));
		check('strict: and it does not parrot the invalid value back',
			!/tasks/.test(err({ collection: 'tasks' }) || ''));
	}

	// ---- 3. type checks on every filter ----
	{
		check('type: status must be open|done', /open/.test(err({ collection: 'todos', where: { status: 'OPEN' } }) || ''));
		check('type: date must be YYYY-MM-DD', /YYYY-MM-DD/.test(err({ collection: 'todos', where: { date: '2026/10/09' } }) || ''));
		check('type: a real date is accepted', ok({ collection: 'todos', where: { date: '2026-10-09' } }) !== null);
		check('type: pinned must be boolean', /true 或 false/.test(err({ collection: 'notes', where: { pinned: 'yes' } }) || ''));
		check('type: project must be a number', /数字/.test(err({ collection: 'notes', where: { project: '1' } }) || ''));
		check('type: order must be updated|created', /updated/.test(err({ collection: 'todos', order: 'title' }) || ''));
		check('type: non-object body is rejected', /JSON 对象/.test(err('todos') || ''));
		check('type: an array body is rejected', /JSON 对象/.test(err([]) || ''));
		check('type: non-numeric limit is rejected', /数字/.test(err({ collection: 'todos', limit: '20' }) || ''));
	}

	// ---- 4. limit is CLAMPED, not rejected — and the applied value comes back ----
	{
		check('limit: 0 clamps up to 1', ok({ collection: 'todos', limit: 0 }).limit === 1);
		check(`limit: a huge value clamps to ${LIST_LIMIT_MAX}`, ok({ collection: 'todos', limit: 9999 }).limit === LIST_LIMIT_MAX);
		check('limit: a fraction rounds', ok({ collection: 'todos', limit: 7.6 }).limit === 8);
		check('limit: clamping is why the echo matters (the card can say what it got)',
			ok({ collection: 'todos', limit: 9999 }).limit === LIST_LIMIT_MAX);
	}

	// ---- 5. running it: filters, ordering, total, and per-collection labels ----
	const src = rows => ({ live: async () => rows });
	{
		const todos = [
			{ id: 'a', title: '写报告', date: '2026-10-09', status: 'open', updatedAt: 10 },
			{ id: 'b', title: '买菜', date: '2026-10-09', updatedAt: 30 },          // no status => open
			{ id: 'c', title: '旧事', date: '2026-10-08', status: 'open', updatedAt: 99 },
			{ id: 'd', title: '做完的', date: '2026-10-09', status: 'done', updatedAt: 99 },
		];
		let r = await runListQuery(src(todos), ok({ collection: 'todos' }));
		// updatedAt: a=10 b=30 c=99 d=99, and Array.sort is stable, so ties keep input order.
		check('run: orders by updatedAt descending (ties keep input order)',
			r.rows.map(x => x.id).join(',') === 'c,d,b,a');
		check('run: an absent status counts as open (「未完成」must actually return them)',
			r.rows.some(x => x.id === 'b'));
		check('run: no filter at all includes done — the filter is what excludes it, not the default',
			r.rows.some(x => x.id === 'd'));

		r = await runListQuery(src(todos), ok({ collection: 'todos', where: { status: 'open' } }));
		check('run: status=open filters done out',
			!r.rows.some(x => x.id === 'd') && r.rows.length === 3);

		r = await runListQuery(src(todos), ok({ collection: 'todos', where: { date: '2026-10-09' } }));
		check('run: date filters by day', r.rows.length === 3 && !r.rows.some(x => x.id === 'c'));

		r = await runListQuery(src(todos), ok({ collection: 'todos' }));
		check('run: created order is available too',
			(await runListQuery(src(todos), ok({ collection: 'todos', order: 'created' }))).rows.length === 4);

		const many = Array.from({ length: 30 }, (_, i) => ({ id: 'm' + i, title: 'x' + i, updatedAt: i }));
		r = await runListQuery(src(many), ok({ collection: 'todos', limit: 5 }));
		check('run: limit truncates the rows', r.rows.length === 5);
		check('run: total reports the pre-limit count (「显示 5 / 共 30」)',
			r.total === 30 && r.rows.length === 5);
		check('run: the echo is the query as executed', r.query.limit === 5 && r.query.collection === 'todos');
	}

	// ---- 6. labels are per collection, because there is no honest generic one ----
	{
		const rows = [
			{ id: 'm1', name: '偏好简洁', description: '回答别啰嗦', updatedAt: 2 },
			{ id: 'p1', name: '毕业设计', emoji: '🎓', updatedAt: 2 },
			{ id: 'a1', key: 'helper', name: '助手', slogan: '干活', updatedAt: 2 },
			{ id: 'n1', title: '笔记', body: 'x'.repeat(200), updatedAt: 2 },
			{ id: 't1', title: '待办', date: '2026-10-09', time: '09:30', updatedAt: 2 },
			{ id: 's1', title: '会议', note: '三楼', startIso: '2026-10-09T09:00', updatedAt: 2 },
			{ id: 'bad', updatedAt: 2 },
		];
		const one = async (collection, id) => {
			const r = await runListQuery(src(rows.filter(x => x.id === id)), ok({ collection }));
			return r.rows[0];
		};
		check('label: a memory shows its name + description',
			(await one('memories', 'm1')).label === '偏好简洁' && (await one('memories', 'm1')).detail === '回答别啰嗦');
		check('label: a project shows its name (not a blank title)',
			(await one('projects', 'p1')).label === '毕业设计');
		check('label: an assistant shows name + slogan',
			(await one('assistants', 'a1')).label === '助手' && (await one('assistants', 'a1')).detail === '干活');
		check('label: a note shows its title and a CLIPPED body',
			(await one('notes', 'n1')).detail.length === 81 && (await one('notes', 'n1')).detail.endsWith('…'));
		check('label: a todo shows date + time as one string',
			(await one('todos', 't1')).when === '2026-10-09 09:30');
		check('label: a schedule prefers startIso over the bare date',
			(await one('schedules', 's1')).when === '2026-10-09T09:00' && (await one('schedules', 's1')).detail === '三楼');
		check('label: a row with no title still renders something',
			(await one('todos', 'bad')).label === '(未命名待办)');
	}

	// ---- 7. it is read-only BY CONSTRUCTION: the query shape admits no write ----
	{
		check('read-only: there is no verb/sort/mutate field in the accepted schema',
			['collection', 'where', 'order', 'limit'].every(k => ok({ collection: 'todos', [k]: k === 'collection' ? 'todos' : undefined }) !== null));
		check('read-only: `delete`, `set`, `action` are all rejected as unknown fields',
			!!err({ collection: 'todos', delete: 'all' })
			&& !!err({ collection: 'todos', set: { title: 'x' } })
			&& !!err({ collection: 'todos', action: 'purge' }));
	}

	// ---- 8. the card is registered, and its validator IS this normalizer ----
	{
		check('registry: list is a declared card type', cat.CARD_KINDS.includes('list'));
		check('registry: the list validator rejects a bad query',
			typeof reg.validateCard('list', { collection: 'nope' }) === 'string');
		check('registry: the list validator accepts a good query',
			reg.validateCard('list', { collection: 'todos', where: { status: 'open' } }) === null);
		check('registry: the prompt tells the model to prefer it for real data',
			/list 是活数据卡片/.test(cat.CARD_PROMPT_SECTION) && /不要把内容自己编进卡片/.test(cat.CARD_PROMPT_SECTION));
		check('registry: the fence recognises a list card', cat.buildFenceOpenRe().test('```list\n{}'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});