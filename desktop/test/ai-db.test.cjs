/* M17 SQLite layer tests: migration idempotence, crash-safety, typed parts, usage ledger,
 * agent message bus, audit.
 *
 * Must run under the Electron binary (better-sqlite3 is a rebuilt native addon; plain node
 * segfaults on the pre-rebuild binary). Compile first, then run from desktop\:
 *   node node_modules\typescript\bin\tsc src\main\store\store.ts src\main\ai\db.ts shared\types.ts --outDir .test --rootDir . --module commonjs --target es2022 --esModuleInterop --skipLibCheck
 *   $env:ELECTRON_RUN_AS_NODE=1; .\node_modules\electron\dist\electron.exe test\ai-db.test.cjs
 *
 * ELECTRON_RUN_AS_NODE=1 matters: without it Electron treats the script as a main process
 * and never exits (the test hangs). Results are written to %TEMP%\pcb-ai-db-test.txt because
 * crashpad noise makes stdout unreliable. --rootDir matters too: without it tsc flattens the
 * output tree and the require path below does not resolve.
 */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-ai-db-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };

process.on('exit', flush);

const { AiDb, MIGRATIONS } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'db.js'));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-ai-db-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

try {
	// ---- 0. REGRESSION (pc-a reported boot crash): constructing AiDb into a directory that
	// does not exist yet must not throw. better-sqlite3 will not create intermediate dirs, and
	// the JSON Store creates dataDir lazily — so on a first run the dir is absent at boot.
	{
		const root = path.join(os.tmpdir(), 'pcb-aidb-missing-' + Date.now());
		const missing = path.join(root, 'deep', 'nested');
		check('boot: target dir really does not exist', !fs.existsSync(missing));
		let db0 = null, err = null;
		try { db0 = new AiDb(missing); } catch (e) { err = e; }
		check('boot: AiDb constructs into a missing dir (no throw)', err === null && db0 !== null);
		check('boot: nested dir was created', fs.existsSync(missing));
		if (db0) {
			db0.migrate();
			check('boot: migrations apply on a fresh first run', db0.schemaVersion() === MIGRATIONS.length);
			db0.close();
		}
		fs.rmSync(root, { recursive: true, force: true });
	}

	// ---- 1. fresh migrate creates every table
	const d1 = tmpDir('fresh');
	const db = new AiDb(d1);
	const r1 = db.migrate();
	check('fresh: applied every migration', r1.applied.length === MIGRATIONS.length);
	// derive from MIGRATIONS so adding a migration does not break this suite
	check('fresh: schemaVersion = last migration id', db.schemaVersion() === MIGRATIONS[MIGRATIONS.length - 1].id);
	const tables = db.tables();
	for (const t of ['agents', 'sessions', 'messages', 'tool_audit', 'usage', 'agent_messages', 'schema_migrations']) {
		check('fresh: table ' + t + ' exists', tables.includes(t));
	}

	// ---- 2. migrate is idempotent (second run is a no-op)
	const r2 = db.migrate();
	check('idempotent: second migrate applies nothing', r2.applied.length === 0);
	check('idempotent: schemaVersion unchanged', db.schemaVersion() === MIGRATIONS[MIGRATIONS.length - 1].id);
	// and a fresh handle on the same file agrees
	const db1b = new AiDb(d1);
	db1b.migrate();
	check('idempotent: reopening does not re-apply', db1b.schemaVersion() === MIGRATIONS[MIGRATIONS.length - 1].id);
	db1b.close();

	// ---- 3. agents: insert + read back JSON columns
	const now = Date.now();
	const insAgent = db.raw.prepare(
		'INSERT INTO agents (id,name,color,systemPrompt,tools,memoryScope,permMode,maxTurns,builtin,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)');
	insAgent.run('ag_1', '规划师', '#FF8800', '你负责排程', JSON.stringify(['schedule_create', 'todo_create']), JSON.stringify(['work']), 'auto', 12, 1, now, now);
	insAgent.run('ag_2', '记录员', '#00AA88', '你负责写笔记', '[]', '[]', 'ro', 8, 0, now, now);
	const a1 = db.raw.prepare('SELECT * FROM agents WHERE id=?').get('ag_1');
	check('agents: row persisted', !!a1 && a1.name === '规划师');
	check('agents: tools JSON round-trips', JSON.parse(a1.tools).length === 2 && JSON.parse(a1.tools)[0] === 'schedule_create');
	check('agents: permMode/maxTurns stored', a1.permMode === 'auto' && a1.maxTurns === 12);
	check('agents: builtin flag', a1.builtin === 1);
	check('agents: builtin index works', db.raw.prepare('SELECT COUNT(*) c FROM agents WHERE builtin=1').get().c === 1);

	// ---- 4. session tree: parent/child + depth + status badge
	const insSess = db.raw.prepare(
		'INSERT INTO sessions (id,title,agentId,parentId,depth,status,pinned,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?)');
	insSess.run('s_root', '主会话', 'ag_1', '', 0, 'running', 1, now, now);
	insSess.run('s_sub', '子任务', 'ag_2', 's_root', 1, 'idle', 0, now, now);
	const kids = db.raw.prepare('SELECT id FROM sessions WHERE parentId=?').all('s_root');
	check('sessions: parent index finds child', kids.length === 1 && kids[0].id === 's_sub');
	check('sessions: depth recorded', db.raw.prepare('SELECT depth FROM sessions WHERE id=?').get('s_sub').depth === 1);
	check('sessions: running status queryable', db.raw.prepare('SELECT id FROM sessions WHERE status=?').all('running').length === 1);

	// ---- 5. typed message parts
	const insMsg = db.raw.prepare('INSERT INTO messages (id,sessionId,seq,role,parts,createdAt) VALUES (?,?,?,?,?,?)');
	const parts = [
		{ type: 'text', text: '帮我排个周会' },
		{ type: 'reasoning', text: '先查日程' },
		{ type: 'tool_call', name: 'schedule_query', args: { date: '2026-10-05' } },
		{ type: 'tool_result', name: 'schedule_query', ok: true, summary: '3 条' },
	];
	insMsg.run('m_1', 's_root', 0, 'user', JSON.stringify(parts), now);
	const m1 = db.raw.prepare('SELECT * FROM messages WHERE sessionId=? ORDER BY seq').all('s_root');
	check('messages: row persisted', m1.length === 1);
	const parsed = JSON.parse(m1[0].parts);
	check('messages: 4 typed parts round-trip', parsed.length === 4 && parsed[2].type === 'tool_call' && parsed[2].name === 'schedule_query');
	check('messages: nested args survive JSON', parsed[2].args.date === '2026-10-05');
	check('messages: seq ordering supported', db.raw.prepare('SELECT COUNT(*) c FROM messages WHERE sessionId=? AND seq<=?').get('s_root', 0).c === 1);

	// ---- 6. tool audit incl. denied flag
	const insAudit = db.raw.prepare(
		'INSERT INTO tool_audit (sessionId,agentId,tool,input,ok,denied,ms,at) VALUES (?,?,?,?,?,?,?,?)');
	insAudit.run('s_root', 'ag_1', 'schedule_create', '{"title":"周会"}', 1, 0, 12, now);
	insAudit.run('s_root', 'ag_1', 'todo_delete', '{"id":"9"}', 0, 1, 0, now);
	const au = db.raw.prepare('SELECT * FROM tool_audit WHERE sessionId=? ORDER BY id').all('s_root');
	check('audit: 2 rows', au.length === 2);
	check('audit: denied recorded distinctly', au[0].denied === 0 && au[1].denied === 1 && au[1].ok === 0);
	check('audit: session index works', db.raw.prepare('SELECT COUNT(*) c FROM tool_audit WHERE sessionId=?').get('s_root').c === 2);

	// ---- 7. usage ledger + aggregate per agent
	const insUsage = db.raw.prepare('INSERT INTO usage (agentId,sessionId,tokensIn,tokensOut,cost,at) VALUES (?,?,?,?,?,?)');
	insUsage.run('ag_1', 's_root', 1000, 200, 0.01, now);
	insUsage.run('ag_1', 's_root', 500, 100, 0.005, now);
	insUsage.run('ag_2', 's_sub', 300, 50, 0.003, now);
	const perAgent = db.raw.prepare(
		'SELECT agentId, SUM(tokensIn) ti, SUM(tokensOut) to_, SUM(cost) c FROM usage GROUP BY agentId ORDER BY agentId').all();
	check('usage: aggregated per agent', perAgent.length === 2);
	check('usage: ag_1 tokens summed', perAgent[0].ti === 1500 && perAgent[0].to_ === 300);
	check('usage: ag_2 separate row', perAgent[1].agentId === 'ag_2' && perAgent[1].ti === 300);
	check('usage: cost summed', Math.abs(perAgent[0].c - 0.015) < 1e-9);

	// ---- 8. SendMessage bus: steer / queued / read
	const insAM = db.raw.prepare(
		'INSERT INTO agent_messages (id,fromAgent,toAgent,sessionId,kind,body,readAt,createdAt) VALUES (?,?,?,?,?,?,?,?)');
	insAM.run('am_1', 'ag_1', 'ag_2', 's_root', 'steer', '改用下午', null, now);
	insAM.run('am_2', 'ag_1', 'ag_2', 's_root', 'result', '已排程', null, now);
	const unread = db.raw.prepare('SELECT id FROM agent_messages WHERE toAgent=? AND readAt IS NULL ORDER BY createdAt').all('ag_2');
	check('bus: 2 unread queued', unread.length === 2);
	check('bus: steer kind preserved', db.raw.prepare('SELECT kind FROM agent_messages WHERE id=?').get('am_1').kind === 'steer');
	db.raw.prepare('UPDATE agent_messages SET readAt=? WHERE toAgent=? AND readAt IS NULL').run(now, 'ag_2');
	check('bus: marked read drains queue', db.raw.prepare('SELECT COUNT(*) c FROM agent_messages WHERE toAgent=? AND readAt IS NULL').get('ag_2').c === 0);

	// ---- 9. crash safety: a failing migration leaves version intact, no partial tables
	const d2 = tmpDir('crash');
	const bad = new AiDb(d2);
	bad.migrate();
	const beforeV = bad.schemaVersion();
	// simulate a later failing migration by appending one directly to the in-memory list
	MIGRATIONS.push({ id: 99, name: 'boom', up: 'CREATE TABLE half_a (x INT); CREATE TABLE half_b (y INT); SELECT * FROM no_such_table;' });
	let threw = false;
	try { bad.migrate(); } catch { threw = true; }
	MIGRATIONS.pop();
	check('crash: failing migration throws', threw);
	check('crash: version not advanced past failure', bad.schemaVersion() === beforeV);
	check('crash: half-created table rolled back', !bad.tables().includes('half_a'));
	// DB still usable afterwards
	bad.raw.prepare('INSERT INTO usage (agentId,tokensIn,tokensOut,cost,at) VALUES (?,?,?,?,?)').run('ag_x', 1, 1, 0, now);
	check('crash: DB still writable after rollback', bad.raw.prepare('SELECT COUNT(*) c FROM usage').get().c === 1);
	bad.close();

	db.close();
	fs.rmSync(d1, { recursive: true, force: true });
	fs.rmSync(d2, { recursive: true, force: true });

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
} catch (e) {
	out.push('THREW ' + (e && e.stack || e));
	failed++;
}

flush();
process.exit(failed === 0 ? 0 : 1);