/* M17 待办表 (P2) tests: parking lots, the Q2 always/everyN policy, prompt rendering,
 * tool surface, and the schema upgrade 6 -> 8.
 *
 * Must run under Electron (native addon): npm run test:todos
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-todos-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { AiDb, MIGRATIONS } = require(path.join(base, 'db.js'));
const { TodoStore, DEFAULT_POLICY } = require(path.join(base, 'todos.js'));
const { registerTodoTools } = require(path.join(base, 'todo-tools.js'));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-todos-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

(async () => {
	// ---- 1. migrations 7/8/9 exist and apply (derive the count — do NOT hardcode it)
	const LAST_ID = MIGRATIONS[MIGRATIONS.length - 1].id;
	check('migrations: at least the P2/P3 migrations are defined', MIGRATIONS.length >= 9);
	const d1 = tmpDir('mig');
	const db = new AiDb(d1);
	db.migrate();
	check('migrations: fresh db reaches the last migration', db.schemaVersion() === LAST_ID);
	for (const t of ['agent_todos', 'peer_groups', 'peer_proposals']) {
		check('migrations: table ' + t + ' exists', db.tables().includes(t));
	}

	// ---- 2. upgrade path: an existing v6 database gains the new tables
	{
		const d2 = tmpDir('upgrade');
		const db2 = new AiDb(d2);
		db2.migrate();
		// simulate "previously at v6" by rolling the ledger back
		db2.raw.prepare('DELETE FROM schema_migrations WHERE id > 6').run();
		db2.raw.exec('DROP TABLE IF EXISTS agent_todos');
		db2.raw.exec('DROP TABLE IF EXISTS peer_groups');
		db2.raw.exec('DROP TABLE IF EXISTS peer_proposals');
		db2.raw.exec('DROP TABLE IF EXISTS peer_votes');
		db2.close();
		const db2b = new AiDb(d2);
		const rep = db2b.migrate();
		check('upgrade: re-applies exactly the missing ones', rep.applied.length === LAST_ID - 6 && db2b.schemaVersion() === LAST_ID);
		check('upgrade: agent_todos recreated', db2b.tables().includes('agent_todos'));
		check('upgrade: peer_votes recreated', db2b.tables().includes('peer_votes'));
		db2b.close();
		fs.rmSync(d2, { recursive: true, force: true });
	}

	// ---- 3. park / list / close
	const todos = new TodoStore(db);
	check('todo: starts empty', todos.open().length === 0);
	const t1 = todos.add('再帮我加一条日程');
	const t2 = todos.add('顺便查一下本周的会议', 'sess1');
	check('todo: two parked', todos.open().length === 2);
	check('todo: blank ignored', todos.add('   ') === null && todos.open().length === 2);
	check('todo: source defaults to user', t1.source === 'user');
	check('todo: sessionId stored', t2.sessionId === 'sess1');
	check('todo: ids are unique', t1.id !== t2.id);
	check('todo: close by id', todos.done(t1.id) === true);
	check('todo: closed item leaves the open list', todos.open().length === 1 && todos.open()[0].id === t2.id);
	check('todo: closing twice is a no-op', todos.done(t1.id) === false);
	check('todo: reopen works', todos.reopen(t1.id) === true && todos.open().length === 2);
	check('todo: remove works', todos.remove(t1.id) === true && todos.open().length === 1);
	check('todo: scoped by session', todos.open('sess1').length === 1 && todos.open('other').length === 0);
	check('todo: all() includes closed', todos.all().length >= 1);

	// ---- 4. Q2 policy: always vs everyN
	{
		const t = new TodoStore(db);
		t.add('提醒我');
		check('policy: default is always', t.getPolicy().mode === 'always');
		check('policy: defaults sane', DEFAULT_POLICY.everyN === 3 && DEFAULT_POLICY.maxShown === 8);
		// always → every round
		const alwaysHits = [1, 2, 3, 4, 5, 6].map(r => t.shouldCheck(r));
		check('policy: always checks every round', alwaysHits.every(Boolean));
		// everyN=3 → rounds 1,3,6,... (round 1 always included so turn 1 is never blind)
		t.setPolicy({ mode: 'everyN', everyN: 3 });
		check('policy: everyN accepted', t.getPolicy().mode === 'everyN' && t.getPolicy().everyN === 3);
		const seq = [1, 2, 3, 4, 5, 6, 7].map(r => t.shouldCheck(r));
		check('policy: everyN=3 hits 1,3,6', seq[0] && !seq[1] && seq[2] && !seq[3] && !seq[4] && seq[5] && !seq[6]);
		// garbage mode falls back to always
		t.setPolicy({ mode: 'weird' });
		check('policy: bogus mode falls back to always', t.getPolicy().mode === 'always');
		// everyN=0 must not divide by zero
		t.setPolicy({ mode: 'everyN', everyN: 0 });
		check('policy: everyN=0 clamped to 1', t.getPolicy().everyN === 1);
	}

	// ---- 5. prompt rendering (fresh store — case 3 left an open item behind)
	{
		const d5 = tmpDir('render');
		const db5 = new AiDb(d5);
		db5.migrate();
		const t = new TodoStore(db5);
		check('render: empty list renders nothing', t.render(1) === '');
		t.add('第一条想法');
		t.add('第二条想法');
		const r = t.render(1);
		check('render: includes both items', r.includes('第一条想法') && r.includes('第二条想法'));
		check('render: carries the ids so the model can close them', /\[[a-z0-9_]+\]/.test(r));
		check('render: mentions the check policy header', r.includes('待办表'));
		// cap: maxShown
		for (let i = 0; i < 12; i++) { t.add('批量' + i); }
		t.setPolicy({ mode: 'always', maxShown: 3 });
		const capped = t.render(1);
		check('render: honours maxShown', (capped.match(/^\d+\. \[/gm) || []).length === 3);
		check('render: says how many are hidden', /另有\s*\d+\s*条未显示/.test(capped));
		// policy gate applies to render too
		t.setPolicy({ mode: 'everyN', everyN: 4 });
		check('render: everyN round 2 returns nothing', t.render(2) === '');
		check('render: everyN round 4 returns the list', t.render(4).includes('第一条想法'));
		db5.close();
		fs.rmSync(d5, { recursive: true, force: true });
	}

	// ---- 6. tool surface (the three tools the model calls)
	{
		const reg = { defs: new Map(), register(d) { this.defs.set(d.name, d); }, get(n) { return this.defs.get(n); } };
		registerTodoTools(reg, todos);
		for (const n of ['agent_todo_add', 'agent_todo_list', 'agent_todo_done']) {
			check('tool: ' + n + ' registered', reg.defs.has(n));
		}
		const added = await reg.get('agent_todo_add').invoke({ text: '工具加的一条' });
		check('tool: add returns the id it stored', /\[td_[a-z0-9_]+\]/.test(added));
		const list = await reg.get('agent_todo_list').invoke({});
		check('tool: list shows the entry', list.includes('工具加的一条'));
		const id = (added.match(/\[([^\]]+)\]/) || [])[1];
		const closed = await reg.get('agent_todo_done').invoke({ id });
		check('tool: done closes it', closed.includes('已完成'));
		const missing = await reg.get('agent_todo_done').invoke({ id: 'nope' });
		check('tool: done on unknown id is graceful', missing.includes('未找到'));
		const empty = await reg.get('agent_todo_add').invoke({ text: '  ' });
		check('tool: blank add is reported, not silently dropped', empty.includes('未记录'));
	}

	// ---- 7. NOT part of the dump contract (must never reach the phone)
	{
		const dump = await (async () => {
			const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));
			const s = new Store(path.join(tmpDir('dump'), 'x'));
			return s.exportDump();
		})();
		check('dump: todo list never appears in the export',
			!Object.keys(dump.entity).includes('agent_todos') && !JSON.stringify(dump).includes('工具加的一条'));
	}

	db.close();
	fs.rmSync(d1, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});