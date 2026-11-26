/* M1 acceptance: store round-trip against a temp data dir.
 * Compile first:  node node_modules\typescript\bin\tsc src/main/store/store.ts shared/types.ts --outDir .test --rootDir . --module commonjs --target es2022 --esModuleInterop --skipLibCheck
 * Run:            node test\store.test.cjs  (expects .test\src\main\store\store.js)
 *                 (this suite is pure node; the ai-db suite must be run under Electron) */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const dir = path.join(os.tmpdir(), 'planability-store-test-' + Date.now());
const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));
// The source of truth for which collections exist. Requiring it here is the whole point below:
// a hardcoded count cannot tell "6 of 6 present" from "6 of 7 present", and `=== 6` sat happily
// while exportDump's handwritten six-entry list left `assistants` out of every backup.
const { COLLECTIONS } = require(path.join(__dirname, '..', '.test', 'shared', 'types.js'));

let failed = 0;
function check(name, cond) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}

(async () => {
	const store = new Store(dir);

	// 1. first run on empty dir: live() returns []
	check('empty dir -> live schedules = 0', (await store.live('schedules')).length === 0);

	// 2. upsert assigns id/createdAt/updatedAt
	const a = await store.upsert('schedules', { id: '', title: '团队周会', date: '2026-10-03' });
	check('upsert assigns id', /^e_[a-z0-9]+_[a-z0-9]+$/.test(a.id));
	check('upsert sets createdAt', a.createdAt > 0 && a.updatedAt > 0);

	// 3. upsert update keeps id, bumps updatedAt
	const b = await store.upsert('schedules', { ...a, title: '团队周会（改）' });
	check('update keeps id', b.id === a.id);
	check('update bumps updatedAt', b.updatedAt >= a.createdAt);

	// 4. softDelete -> live filters it, file keeps the record (deletedAt set)
	await store.upsert('todos', { id: '', title: '买雷电线', status: 'open' });
	const todo = (await store.live('todos'))[0];
	check('todo live = 1', (await store.live('todos')).length === 1);
	check('softDelete returns true', await store.softDelete('todos', todo.id));
	check('softDelete -> live = 0', (await store.live('todos')).length === 0);
	const rawTodos = JSON.parse(fs.readFileSync(path.join(dir, 'todos.json'), 'utf8'));
	check('deleted record kept on disk with deletedAt', rawTodos.some(t => t.id === todo.id && t.deletedAt));

	// 5. stats shape (all known collections)
	const [s, t, n, m, p] = await Promise.all([
		store.live('schedules'), store.live('todos'), store.live('notes'), store.live('memories'), store.live('projects')
	]);
	check('stats counts', s.length === 1 && t.length === 0 && n.length === 0 && m.length === 0 && p.length === 0);

	// 6. kv put/get round trip + persistence to disk
	await store.kvPut('auto.session:1', 'sess-abc');
	check('kv get', (await store.kvGet('auto.session:1')) === 'sess-abc');
	const kvDisk = JSON.parse(fs.readFileSync(path.join(dir, 'kv.json'), 'utf8'));
	check('kv on disk', kvDisk['auto.session:1'] === 'sess-abc');

	// 7. audit appends jsonl lines
	await store.audit('schedule_create', { title: 'x' }, true, 12);
	await store.audit('schedule_delete', { id: 'x' }, false, 3);
	const auditLines = fs.readFileSync(path.join(dir, 'tools_audit.jsonl'), 'utf8').trim().split('\n');
	check('audit appended 2 lines', auditLines.length === 2);
	check('audit line json parses', !!JSON.parse(auditLines[1]).tool);

	// 8. export -> merge newer-wins semantics
	const dump = await store.exportDump();
	// COLLECTIONS 已含 v2 三集合，PC 导出按内容升版标 2（2026-11-09 契约修正；schemaVersion=1
	// 会让按版本分派的消费方漏读三集合）。导入侧 1/2 都收，下面的 v1 合并用例仍走 schemaVersion:1。
	check('dump schemaVersion=2（COLLECTIONS 已含 v2 三集合）', dump.schemaVersion === 2);
	check('dump exports EVERY known collection (not just N of them)',
		COLLECTIONS.every(c => Object.prototype.hasOwnProperty.call(dump.entity, c)));
	check('dump exports exactly the known collections',
		Object.keys(dump.entity).length === COLLECTIONS.length);

	// simulate an incoming dump: newer record wins, unknown id is added
	const incoming = {
		schemaVersion: 1,
		exportedAt: Date.now(),
		entity: {
			schedules: [{ ...a, title: '来自手机的改名', updatedAt: Date.now() + 10_000 }],
			notes: [{ id: 'e_note_1', createdAt: 1, updatedAt: 1, title: '新笔记', body: 'x' }]
		},
		kv: { 'mobile.key': 'v' }
	};
	const merged = await store.mergeDump(incoming);
	check('merge count = 3', merged === 3);
	const afterMerge = await store.live('schedules');
	check('newer updatedAt wins', afterMerge.find(x => x.id === a.id).title === '来自手机的改名');
	check('unknown note added', (await store.live('notes')).length === 1);
	check('kv merged', (await store.kvGet('mobile.key')) === 'v');

	// 9. reload from disk (fresh Store instance = cold read)
	const store2 = new Store(dir);
	check('cold reload sees merged data', (await store2.live('notes')).length === 1);
	check('cold reload sees deleted still filtered', (await store2.live('todos')).length === 0);

	fs.rmSync(dir, { recursive: true, force: true });

	// =====================================================================
	// 10. merge rule v2 — id first, natural key fallback.
	//     Spec: 跨端契约文档「merge rule v2」节（已拍板）.
	//     The phone emits numeric ids ("1","2") while PC mints e_<base36>_<rand>,
	//     so v1 duplicated every phone record. Each case gets its own temp dir.
	// =====================================================================
	async function freshStore() {
		const d = path.join(os.tmpdir(), 'planability-merge-v2-' + Math.random().toString(36).slice(2));
		return { d, s: new Store(d) };
	}
	const tmpDirs = [];
	function track(d) { tmpDirs.push(d); return d; }

	// 10.1 id exact match still wins (natural key would point elsewhere)
	{
		const d = track(path.join(os.tmpdir(), 'pcb-v2-1-' + Math.random().toString(36).slice(2)));
		const s = new Store(d);
		const mine = await s.upsert('schedules', { id: '', title: 'A', date: '2026-10-05' });
		await s.upsert('schedules', { id: '', title: 'A', date: '2026-10-05' }); // duplicate title -> 2 locals
		const before = (await s.live('schedules')).length;
		// incoming shares mine's id but a DIFFERENT natural key
		await s.mergeDump({
			schemaVersion: 1, exportedAt: Date.now(),
			entity: { schedules: [{ ...mine, title: 'Z', date: '2099-01-01', updatedAt: Date.now() + 999 }] }
		});
		const list = await s.live('schedules');
		check('v2 id hit: count unchanged', list.length === before);
		check('v2 id hit: updated via id (title=Z)', list.some(x => x.id === mine.id && x.title === 'Z'));
		check('v2 id hit: other record untouched', list.some(x => x.id !== mine.id && x.title === 'A'));
		fs.rmSync(d, { recursive: true, force: true });
	}

	// 10.2 id miss -> natural key hit (the real cross-end dedup case)
	{
		const d = track(path.join(os.tmpdir(), 'pcb-v2-2-' + Math.random().toString(36)));
		const s = new Store(d);
		await s.upsert('schedules', { id: '', title: '周会', date: '2026-10-05', startIso: '2026-10-05T14:00:00' });
		const before = (await s.live('schedules')).length;
		const cnt = await s.mergeDump({
			schemaVersion: 1, exportedAt: Date.now(),
			entity: { schedules: [{ id: '1', createdAt: 1, updatedAt: Date.now() + 999, title: '周会', date: '2026-10-05', startIso: '2026-10-05T14:00:00' }] }
		});
		const list = await s.live('schedules');
		check('v2 natural hit: not duplicated', list.length === before);
		check('v2 natural hit: reported as merged', cnt === 1);
		check('v2 natural hit: keeps OUR id (not phone "1")', list[0].id !== '1' && /^e_/.test(list[0].id));
		fs.rmSync(d, { recursive: true, force: true });
	}

	// 10.3 ambiguous natural key (2+ locals) -> conservative insert, no wrong merge
	{
		const d = track(path.join(os.tmpdir(), 'pcb-v2-3-' + Math.random().toString(36)));
		const s = new Store(d);
		const x = await s.upsert('projects', { id: '', name: 'Planability' });
		const y = await s.upsert('projects', { id: '', name: 'Planability' });
		check('v2 ambiguous: two same-name projects exist', x.id !== y.id);
		const before = (await s.live('projects')).length;
		await s.mergeDump({
			schemaVersion: 1, exportedAt: Date.now(),
			entity: { projects: [{ id: '1', createdAt: 1, updatedAt: Date.now() + 999, name: 'Planability', emoji: 'X' }] }
		});
		const list = await s.live('projects');
		check('v2 ambiguous: inserted instead of paired', list.length === before + 1);
		check('v2 ambiguous: neither local was overwritten',
			list.filter(p => p.name === 'Planability' && p.emoji === 'X').length === 1);
		fs.rmSync(d, { recursive: true, force: true });
	}

	// 10.4 soft delete is respected (deleted record does not come back to life)
	{
		const d = track(path.join(os.tmpdir(), 'pcb-v2-4-' + Math.random().toString(36)));
		const s = new Store(d);
		const n = await s.upsert('notes', { id: '', title: '会议纪要', body: '正文内容' });
		await s.softDelete('notes', n.id);
		// phone dump: same natural key, but OLDER updatedAt than the local delete
		await s.mergeDump({
			schemaVersion: 1, exportedAt: Date.now(),
			entity: { notes: [{ id: '1', createdAt: 1, updatedAt: 1, title: '会议纪要', body: '正文内容' }] }
		});
		const list = await s.live('notes');
		check('v2 deletedAt respected: stays deleted', list.length === 0);
		const raw = JSON.parse(fs.readFileSync(path.join(d, 'notes.json'), 'utf8'));
		check('v2 deletedAt respected: still on disk with deletedAt',
			raw.some(r => r.id === n.id && r.deletedAt));
		// and a NEWER incoming version legitimately revives it ("newer wins" governs deletedAt)
		await s.mergeDump({
			schemaVersion: 1, exportedAt: Date.now(),
			entity: { notes: [{ id: '2', createdAt: 1, updatedAt: Date.now() + 9999, title: '会议纪要', body: '正文内容' }] }
		});
		const revived = await s.live('notes');
		check('v2 newer incoming revives deleted record', revived.length === 1);
		check('v2 revived record keeps OUR id', revived.length === 1 && revived[0].id === n.id);
		check('v2 revived record has no deletedAt left', revived.length === 1 && !revived[0].deletedAt);
		fs.rmSync(d, { recursive: true, force: true });
	}

	// 10.5 idempotence — importing the same dump twice must not grow the collection
	{
		const d = track(path.join(os.tmpdir(), 'pcb-v2-5-' + Math.random().toString(36)));
		const s = new Store(d);
		await s.upsert('schedules', { id: '', title: '晨会', date: '2026-10-06', startIso: '2026-10-06T09:00:00' });
		await s.upsert('todos', { id: '', title: '写周报', date: '2026-10-06', time: '18:00' });
		await s.upsert('notes', { id: '', title: '纪要', body: 'x'.repeat(300) });
		const phoneDump = {
			schemaVersion: 1, exportedAt: Date.now(),
			entity: {
				schedules: [{ id: '1', createdAt: 1, updatedAt: 5, title: '晨会', date: '2026-10-06', startIso: '2026-10-06T09:00:00' }],
				todos: [{ id: '1', createdAt: 1, updatedAt: 5, title: '写周报', date: '2026-10-06', time: '18:00' }],
				notes: [{ id: '1', createdAt: 1, updatedAt: 5, title: '纪要', body: 'x'.repeat(300) }]
			}
		};
		await s.mergeDump(phoneDump);
		const n1 = (await s.live('schedules')).length + (await s.live('todos')).length + (await s.live('notes')).length;
		const second = await s.mergeDump(phoneDump);
		const n2 = (await s.live('schedules')).length + (await s.live('todos')).length + (await s.live('notes')).length;
		check('v2 idempotent: first import deduped to 3 total', n1 === 3);
		check('v2 idempotent: second import adds nothing', n2 === 3);
		check('v2 idempotent: no phone ids leaked into local store', second === 3);
		const sched = await s.live('schedules');
		check('v2 idempotent: our id survived both rounds', /^e_/.test(sched[0].id));
		// and a cold reload agrees (chained === would be left-assoc; compare explicitly)
		const cold = new Store(d);
		const cs = (await cold.live('schedules')).length;
		const ct = (await cold.live('todos')).length;
		const cn = (await cold.live('notes')).length;
		check('v2 idempotent: cold reload still 1/1/1', cs === 1 && ct === 1 && cn === 1);
		fs.rmSync(d, { recursive: true, force: true });
	}

	// 10.6 naturalKey unit checks per collection (the cross-end spec table)
	{
		const k = Store.naturalKey;
		check('key schedules uses date|HH:mm',
			k('schedules', { id: '1', createdAt: 1, updatedAt: 1, title: ' 周会 ', date: '2026-10-05', startIso: '2026-10-05T14:30:00' })
			=== '周会|2026-10-05|14:30');
		check('key schedules falls back to startIso date',
			k('schedules', { id: '1', createdAt: 1, updatedAt: 1, title: 'X', startIso: '2026-10-07T08:05:00' })
			=== 'X|2026-10-07|08:05');
		check('key todos uses title|date|time',
			k('todos', { id: '1', createdAt: 1, updatedAt: 1, title: 'T', date: '2026-10-05', time: '18:00' })
			=== 'T|2026-10-05|18:00');
		check('key notes truncates body at 200',
			k('notes', { id: '1', createdAt: 1, updatedAt: 1, title: 'N', body: 'a'.repeat(500) })
			=== 'N|' + 'a'.repeat(200));
		check('key notes empty -> no key (never pair)', k('notes', { id: '1', createdAt: 1, updatedAt: 1, title: '', body: '' }) === '');
		check('key notebooks/projects/memories = name',
			k('notebooks', { id: '1', createdAt: 1, updatedAt: 1, name: 'NB' }) === 'NB' &&
			k('projects', { id: '1', createdAt: 1, updatedAt: 1, name: 'PR' }) === 'PR' &&
			k('memories', { id: '1', createdAt: 1, updatedAt: 1, name: 'ME' }) === 'ME');
		check('key assistants = key',
			k('assistants', { id: '1', createdAt: 1, updatedAt: 1, key: 'planner' }) === 'planner');
		check('key blank name -> no key (never pair)', k('projects', { id: '1', createdAt: 1, updatedAt: 1, name: '  ' }) === '');
	}

	for (const d of tmpDirs) { fs.rmSync(d, { recursive: true, force: true }); }
	console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => { console.error('THREW', e); process.exit(1); });
