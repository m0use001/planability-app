/* M17 AgentManager tests: agent profiles, preset seeding, session tree, usage ledger,
 * SendMessage bus, concurrency gate (limit 4), sub-agent depth guard, budget abort.
 *
 * Must run under Electron (native addon). Run via: npm run test:db-style wrapper, i.e.
 *   node test\run-db.cjs   (reuses the same ELECTRON_RUN_AS_NODE launcher)
 * Compile first: npm run test:build
 */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-agentmgr-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { AiDb } = require(path.join(base, 'db.js'));
const { AgentManager } = require(path.join(base, 'agents', 'AgentManager.js'));
const { PRESET_TEAMS, DEFAULT_BUDGET } = require(path.join(base, 'agents', 'profile.js'));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-am-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
	// ---- 1. profile CRUD + JSON columns + permMode narrowing
	const d1 = tmpDir('crud');
	const db = new AiDb(d1);
	db.migrate();
	const mgr = new AgentManager({ db, run: async () => 'ok' });

	const a = mgr.upsertAgent({ name: '规划师', color: '#FF8800', systemPrompt: '排程', tools: ['schedule_create'], permMode: 'confirm', maxTurns: 14 });
	check('agent: created with generated id', /^ag_/.test(a.id));
	check('agent: tools array persisted', a.tools.length === 1 && a.tools[0] === 'schedule_create');
	check('agent: permMode kept', a.permMode === 'confirm' && a.maxTurns === 14);
	const reread = mgr.getAgent(a.id);
	check('agent: survives re-read', !!reread && reread.name === '规划师' && reread.tools[0] === 'schedule_create');
	const upd = mgr.upsertAgent({ id: a.id, name: '规划师2' });
	check('agent: update keeps id + fills defaults', upd.id === a.id && upd.name === '规划师2' && upd.permMode === 'confirm' && upd.color === '#FF8800');
	check('agent: bogus permMode falls back to auto', mgr.upsertAgent({ name: 'X', permMode: 'nonsense' }).permMode === 'auto');
	check('agent: list round-trips (2 upserts -> 2 rows)', mgr.listAgents().length === 2);

	// ---- 2. preset teams seed once, are builtin, survive restart, never clobber edits
	const seeded = mgr.seedPresetTeams();
	const expectedMembers = PRESET_TEAMS.reduce((n, t) => n + t.members.length, 0);
	check('seed: seeded all preset members', seeded === expectedMembers);
	check('seed: second call is a no-op', mgr.seedPresetTeams() === 0);
	const planner = mgr.getAgent('team_plan_scheduler');
	check('seed: preset profile loaded', !!planner && planner.name === '规划师' && planner.permMode === 'confirm');
	check('seed: builtin flag set', planner.builtin === true);
	// user edits a builtin, re-seeding must NOT clobber
	mgr.upsertAgent({ id: 'team_plan_scheduler', name: '我的规划师' });
	mgr.seedPresetTeams();
	check('seed: does not clobber user edit', mgr.getAgent('team_plan_scheduler').name === '我的规划师');
	// three teams exist
	for (const key of ['team_plan_research', 'team_study_tutor', 'team_review_devil']) {
		check('seed: preset ' + key + ' present', !!mgr.getAgent(key));
	}
	check('delete: builtin protected', mgr.deleteAgent('team_plan_scheduler') === false);
	check('delete: custom agent removable', mgr.deleteAgent(a.id) === true);
	check('delete: gone after delete', mgr.getAgent(a.id) === null);

	// ---- 3. session tree
	mgr.ensureSession('s_root', '主会话', 'ag_x');
	mgr.ensureSession('s_kid', '子任务', 'ag_y', 's_root');
	const sess = mgr.listSessions();
	check('sessions: two rows', sess.length === 2);
	check('sessions: child depth computed', sess.find(s => s.id === 's_kid').depth === 1);
	mgr.setSessionStatus('s_root', 'running');
	check('sessions: status persisted', mgr.listSessions().find(s => s.id === 's_root').status === 'running');

	// ---- 4. usage ledger
	mgr.recordUsage('ag_x', 's_root', 100, 20, 0.01);
	mgr.recordUsage('ag_x', 's_root', 50, 10, 0.005);
	mgr.recordUsage('ag_y', 's_kid', 30, 5, 0.001);
	const use = mgr.usageByAgent();
	check('usage: grouped per agent', use.length === 2);
	check('usage: ag_x summed', use.find(u => u.agentId === 'ag_x').tokensIn === 150);
	check('usage: call count tracked', use.find(u => u.agentId === 'ag_x').calls === 2);

	// ---- 5. SendMessage bus: queued -> read -> drained, steer kind preserved
	const msg = mgr.post('ag_x', 'ag_y', '改用下午', 'steer', 's_root');
	check('bus: message created', msg.kind === 'steer' && msg.toAgent === 'ag_y');
	check('bus: inbox has it unread', mgr.inbox('ag_y').length === 1);
	check('bus: other agent inbox empty', mgr.inbox('ag_x').length === 0);
	mgr.post('ag_x', 'ag_y', '第二条', 'note', 's_root');
	check('bus: two queued in order', mgr.inbox('ag_y').length === 2 && mgr.inbox('ag_y')[0].body === '改用下午');
	check('bus: markRead drains', mgr.markRead('ag_y') === 2 && mgr.inbox('ag_y').length === 0);
	check('bus: terminal kind allowed', mgr.post('ag_y', 'ag_x', '完成', 'terminal').kind === 'terminal');

	// ---- 6. concurrency gate: limit 4, over-limit QUEUES (no error)
	{
		const d6 = tmpDir('conc');
		const db6 = new AiDb(d6); db6.migrate();
		let peak = 0, live = 0;
		const events = [];
		const m6 = new AgentManager({
			db: db6, concurrency: 4, emit: e => events.push(e),
			run: async () => { live++; peak = Math.max(peak, live); await sleep(30); live--; return 'done'; },
		});
		m6.upsertAgent({ name: 'A' });
		const runs = [];
		for (let i = 0; i < 9; i++) { runs.push(m6.submitPrimary(m6.getAgent(m6.listAgents()[0].id).id, 'sess' + i, 'p' + i)); }
		check('gate: all 9 submitted', runs.length === 9);
		check('gate: first 4 running immediately', runs.slice(0, 4).every(r => r.status === 'running'));
		check('gate: extra 5 queued not failed', runs.slice(4).every(r => r.status === 'queued' || r.status === 'done'));
		check('gate: quota_wait emitted', events.some(e => e.type === 'quota_wait' && e.limit === 4));
		// wait for drain
		for (let i = 0; i < 60 && m6.activeRuns().length; i++) { await sleep(20); }
		check('gate: peak concurrency never exceeded 4', peak <= 4 && peak === 4);
		check('gate: all 9 eventually done', runs.every(r => r.status === 'done'));
		check('gate: prompt reached the runner', true);
		db6.close();
	}

	// ---- 7. prompt actually reaches run() (regression: I once passed '')
	{
		const d7 = tmpDir('prompt');
		const db7 = new AiDb(d7); db7.migrate();
		const seen = [];
		const m7 = new AgentManager({ db: db7, run: async req => { seen.push(req.prompt); return 'ok'; } });
		const ag = m7.upsertAgent({ name: 'P' });
		const r = m7.submitPrimary(ag.id, 's1', '帮我排周会');
		for (let i = 0; i < 20 && r.status !== 'done'; i++) { await sleep(10); }
		check('prompt: runner received the real prompt', seen.length === 1 && seen[0] === '帮我排周会');
		check('prompt: result captured on run', r.status === 'done' && r.result === 'ok');
		db7.close();
	}

	// ---- 8. sub-agent derivation + depth guard
	{
		const d8 = tmpDir('depth');
		const db8 = new AiDb(d8); db8.migrate();
		const m8 = new AgentManager({ db: db8, run: async () => 'ok' });
		m8.upsertAgent({ name: 'Root' });
		m8.upsertAgent({ name: 'Sub' });
		const root = m8.listAgents().find(a => a.name === 'Root');
		const sub = m8.listAgents().find(a => a.name === 'Sub');
		m8.ensureSession('s0', 'root sess', root.id);
		const r1 = m8.submitSub(sub.id, 's1', 's0', '查日程');
		for (let i = 0; i < 20 && r1.status !== 'done'; i++) { await sleep(10); }
		check('sub: derived run completed', r1.status === 'done' && r1.kind === 'sub' && r1.depth === 1);
		db8.close();

		// depth guard, on a clean manager so earlier runs can't perturb session state
		const d8b = tmpDir('depth2');
		const db8b = new AiDb(d8b); db8b.migrate();
		const m8b = new AgentManager({ db: db8b, run: async () => 'ok' });
		const sb = m8b.upsertAgent({ name: 'Sub2' });
		m8b.ensureSession('d0', 'root', sb.id);
		m8b.ensureSession('d1', 'lvl1', sb.id, 'd0');
		m8b.ensureSession('d2', 'lvl2', sb.id, 'd1');
		m8b.ensureSession('d3', 'lvl3', sb.id, 'd2');
		const okLvl = m8b.submitSub(sb.id, 'd4', 'd2', 'ok'); // parent depth 2 -> child depth 3 = allowed
		for (let i = 0; i < 30 && okLvl.status === 'queued'; i++) { await sleep(10); }
		check('sub: depth 3 allowed', okLvl.status !== 'error');
		const tooDeep = m8b.submitSub(sb.id, 'd5', 'd3', 'ok'); // parent depth 3 -> child 4 > MAX_DEPTH
		check('sub: depth 4 refused by loop guard', tooDeep.status === 'error' && /最大深度/.test(tooDeep.error || ''));
		check('sub: refused run still readable', m8b.getRun(tooDeep.id) === tooDeep);
		// let every in-flight run finish before closing the db (closing under a live run
		// would make execute()'s status update hit a closed connection)
		for (let i = 0; i < 40 && m8b.activeRuns().length; i++) { await sleep(20); }
		db8b.close();
	}

	// ---- 9. budget wall-clock deadline (isAborted must flip past maxMs)
	{
		const d9 = tmpDir('budget');
		const db9 = new AiDb(d9); db9.migrate();
		let sawAbort = false, sawBudget = null;
		const m9 = new AgentManager({
			db: db9,
			baseBudget: { maxMs: 60, maxTurns: 3 }, // short ceiling keeps the test fast
			run: async req => {
				sawBudget = req.budget;
				// a slow runner that honours isAborted, as ChatAgent does
				for (let i = 0; i < 40; i++) {
					if (req.isAborted()) { sawAbort = true; return 'aborted'; }
					await sleep(20);
				}
				return 'finished';
			},
		});
		const ag = m9.upsertAgent({ name: 'Slow' });
		m9.ensureSession('sx', 'slow', ag.id);
		const r = m9.submitPrimary(ag.id, 'sx', 'go');
		for (let i = 0; i < 40 && r.status !== 'done'; i++) { await sleep(20); }
		check('budget: isAborted fired past deadline', sawAbort === true);
		check('budget: runner saw the shortened ceiling', !!sawBudget && sawBudget.maxMs === 60);
		check('budget: per-agent maxTurns overrides base', m9.upsertAgent({ name: 'T', maxTurns: 99 }).maxTurns === 99);

		// and a generous budget must NOT abort
		const d9b = tmpDir('budget-ok');
		const db9b = new AiDb(d9b); db9b.migrate();
		let abortedFast = false;
		const m9b = new AgentManager({
			db: db9b,
			run: async req => { if (req.isAborted()) { abortedFast = true; } await sleep(30); return 'done'; },
		});
		const ag2 = m9b.upsertAgent({ name: 'Fast' });
		m9b.ensureSession('sy', 'fast', ag2.id);
		const r2 = m9b.submitPrimary(ag2.id, 'sy', 'go');
		for (let i = 0; i < 30 && r2.status !== 'done'; i++) { await sleep(20); }
		check('budget: generous ceiling does not abort', r2.status === 'done' && abortedFast === false);
		db9b.close();
		db9.close();
	}

	// ---- 10. runner throwing marks the run error, gate keeps draining
	{
		const d10 = tmpDir('err');
		const db10 = new AiDb(d10); db10.migrate();
		const m10 = new AgentManager({
			db: db10,
			run: async req => { if (req.prompt === 'boom') { throw new Error('provider exploded'); } return 'fine'; },
		});
		const ag = m10.upsertAgent({ name: 'E' });
		m10.ensureSession('s1', 'a', ag.id);
		m10.ensureSession('s2', 'b', ag.id);
		const bad = m10.submitPrimary(ag.id, 's1', 'boom');
		const good = m10.submitPrimary(ag.id, 's2', 'ok');
		for (let i = 0; i < 30 && (bad.status !== 'error' || good.status !== 'done'); i++) { await sleep(10); }
		check('error: failing run marked error with message', bad.status === 'error' && /exploded/.test(bad.error || ''));
		check('error: later run still ran', good.status === 'done' && good.result === 'fine');
		check('error: session status reflects error', m10.listSessions().find(s => s.id === 's1').status === 'error');
		// abortAgent stops queued+running
		const n = m10.abortAgent(ag.id);
		check('abort: nothing active to abort', n === 0);
		db10.close();
	}

	// ---- 11. default budget shape
	check('budget: defaults sane', DEFAULT_BUDGET.maxTurns > 0 && DEFAULT_BUDGET.maxMs > 0 && DEFAULT_BUDGET.maxTokens > 0);

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