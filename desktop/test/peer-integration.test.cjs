/* M17 integration (answers pc-a's P3 block): the REAL path end to end.
 *
 * The unit tests inject a stub runner, which is exactly why they missed this: peer sessions
 * were created only in AgentManager's SQLite `sessions`, while rows/history live in the JSON
 * SessionStore. saveRows/loadRows silently no-op on an unknown id, so every peer answer came
 * back empty and the vote always reported「还没有任何 agent 提交方案」.
 *
 * This suite drives the real SessionStore + real AgentManager + real PeerOrchestrator and
 * asserts a proposal actually lands in peer_proposals.
 *
 * Run: node test/peer-integration.test.cjs  (after npm run test:build)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-peer-int.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const src = path.join(__dirname, '..', '.test', 'src', 'main');
const { AiDb } = require(path.join(src, 'ai', 'db.js'));
const { SessionStore } = require(path.join(src, 'ai', 'sessions.js'));
const { AgentManager } = require(path.join(src, 'ai', 'agents', 'AgentManager.js'));
const { PeerStore } = require(path.join(src, 'ai', 'peers.js'));
const { PeerOrchestrator } = require(path.join(src, 'ai', 'peer-run.js'));
const { templateSlots } = require(path.join(src, 'ai', 'peer-templates.js'));

const sleep = ms => new Promise(r => setTimeout(r, ms));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-pint-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

(async () => {
	// ---- 1. SessionStore.ensure creates the JSON row without stealing focus
	{
		const d = tmpDir('ensure');
		const ss = new SessionStore(d);
		const before = (await ss.list()).length;
		const s = await ss.ensure('pg_x_opta', 'peer 会话');
		check('ensure: created the missing session', (await ss.list()).length === before + 1);
		check('ensure: title kept', s.title === 'peer 会话');
		const again = await ss.ensure('pg_x_opta', '别的标题');
		check('ensure: idempotent (no duplicate)', (await ss.list()).length === before + 1 && again.title === 'peer 会话');
		// rows now actually stick (this is what used to silently no-op)
		await ss.saveRows('pg_x_opta', [{ rowId: 'r1', kind: 'assistant', text: 'hi', done: true }]);
		check('ensure: saveRows now persists (was a silent no-op)', (await ss.loadRows('pg_x_opta')).length === 1);
		check('ensure: loadRows returns the row', (await ss.loadRows('pg_x_opta'))[0].text === 'hi');
		fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 2. THE regression: peer answers must reach peer_proposals
	{
		const d = tmpDir('pipeline');
		const db = new AiDb(d); db.migrate();
		const sessions = new SessionStore(d);
		const peers = new PeerStore(db);

		// Real AgentManager. The run callback mirrors index.ts, including sessions.ensure —
		// if that line is missing this test fails, which is the point.
		let mgr;
		mgr = new AgentManager({
			db,
			run: async req => {
				const sid = req.sessionId;
				mgr.ensureSession(sid, 'peer', req.agent.id);
				await sessions.ensure(sid, 'peer');           // <-- the fix under test
				await sessions.saveRows(sid, [{ rowId: 'a1', kind: 'assistant', text: `${req.agent.name}的方案`, done: true }]);
				await sessions.saveHistory(sid, [{ role: 'assistant', content: `${req.agent.name}的方案` }]);
				// index.ts also routes the finished answer into the group:
				if (sid.startsWith('pg_')) {
					const cut = sid.lastIndexOf('_');
					if (cut > 0) { peers.propose(sid.slice(0, cut), req.agent.id, `${req.agent.name}的方案`); }
				}
				return `${req.agent.name}的方案`;
			},
		});

		const orch = new PeerOrchestrator(peers, mgr);
		const a1 = mgr.upsertAgent({ name: 'A' });
		const a2 = mgr.upsertAgent({ name: 'B' });
		const r = orch.start('集成组', '任务', {
			templateKey: 'options', slots: templateSlots('options'), agentIds: [a1.id, a2.id, ''],
		});
		check('pipeline: group created', !!r.group);
		check('pipeline: two peers fanned out', r.runIds.length === 2);

		for (let i = 0; i < 60 && mgr.activeRuns().length; i++) { await sleep(15); }

		const gid = r.group.id;
		const props = peers.proposals(gid);
		check('pipeline: proposals actually landed (was 0 before the fix)', props.length === 2);
		check('pipeline: proposal text is the peer answer', props.some(p => p.text.includes('的方案')));
		const jsonRows = (await sessions.list()).filter(s => s.id.startsWith(gid));
		check('pipeline: peer sessions exist in the JSON SessionStore', jsonRows.length === 2);
		check('pipeline: peer rows are viewable in the workbench',
			(await sessions.loadRows(jsonRows[0].id)).length === 1);
		check('pipeline: peer history is persisted', (await sessions.loadHistory(jsonRows[0].id)).length === 1);

		// settle must now reach a vote instead of 'no-proposals'
		orch.selfVote(gid);
		const res = orch.settle(gid);
		check('pipeline: settle no longer reports no-proposals', res.outcome !== 'no-proposals');
		check('pipeline: 2 self-votes -> tie (unresolved, by design)', res.outcome === 'tie' && res.winnerId === '');
		const stored = peers.get(gid);
		check('pipeline: group row readable after settle', !!stored);
		// the summary lists proposals by agent ID (that is what the renderer resolves via
		// agents.find), so assert on the two real ids rather than on 'A'/'B'
		check('pipeline: stored summary lists both peers',
			!!stored && stored.summary.includes(a1.id) && stored.summary.includes(a2.id));
		check('pipeline: tie leaves the group open for the user', stored?.status === 'open');
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 3. all slots skipped -> NO group left behind (pc-a's zombie-group report)
	{
		const d = tmpDir('zombie');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const orch = new PeerOrchestrator(peers, mgr);
		const slots = templateSlots('options');
		const before = peers.list().length;
		const r = orch.start('全跳过', '任务', {
			templateKey: 'options',
			slots: slots.map(s => ({ ...s, prompt: '  ' })),
			agentIds: ['', '', ''],
		});
		check('zombie: no group created when nothing is runnable', r.group === null);
		check('zombie: all three slots reported as skipped', r.skipped.length === 3);
		check('zombie: group list unchanged (no open zombie)', peers.list().length === before);
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 4. SESSION ISOLATION (pc-a 17:35 block): a child run must NOT read or write the
	// parent's transcript. ChatAgent used to hardcode sessions.active(), so every sub-agent and
	// peer loaded the parent's history and pushed its own turns back into it — three peers all
	// answered with the parent's opening line.
	{
		const d = tmpDir('isolation');
		const db = new AiDb(d); db.migrate();
		const sessions = new SessionStore(d);
		const providerJs = path.join(src, 'ai', 'provider.js');
		const cardsJs = path.join(src, 'ai', 'cards.js');
		// stubs MUST go in before requiring agent.js (it requires ./provider at load time)
		require.cache[providerJs] = { id: providerJs, filename: providerJs, loaded: true, exports: {
			chatCompletion: async () => ({ ok: true }),
			streamChat: () => (async function* () { yield { type: 'text', value: '子代理的独立回答' }; })(),
			mainSlotConfig: () => ({}), assertPublicHttps: () => new URL('https://x'),
			fetchWithConnectRetry: async () => ({}), callNonStream: async () => '',
		} };
		class FC { feed(v) { return [v]; } flush() { return []; } }
		require.cache[cardsJs] = { id: cardsJs, filename: cardsJs, loaded: true, exports: { CardStreamTransformer: FC } };
		const { ChatAgent } = require(path.join(src, 'ai', 'agent.js'));

		await sessions.create('父会话');
		const parentId = sessions.active();
		await sessions.saveHistory(parentId, [{ role: 'user', content: '这是只有父会话才有的历史' }]);

		const childId = 'ag_sub_iso';
		await sessions.ensure(childId, '子会话');
		// keep the parent ACTIVE — the trap this guards against
		await sessions.switch(parentId);

		const mkEvents = [];
		const mk = (sid) => new ChatAgent({
			registry: { listForApi: () => [], get: () => ({ name: 'x', description: '', risk: 'auto', invoke: async () => 'ok', inputSchema: {} }) },
			store: { audit: async () => {} },
			sessions,
			sessionId: () => sid,
			undo: { push: async () => {} },
			slot: () => ({ key: 'k', url: 'https://x', model: 'm' }),
			permMode: () => 'full', assistant: () => null,
			plugins: () => ({ undo: false, cards: true }), memInject: () => false,
			emit: (e) => { mkEvents.push(e.type + (e.message ? ':' + e.message : '')); },
			requestApproval: async () => ({ allow: true }), isAborted: () => false,
		});

		await mk(childId).send('子任务指令');
		check('isolation: child turn actually completed', mkEvents.includes('done') && !mkEvents.some(e => e.startsWith('error')));
		const parentHist = await sessions.loadHistory(parentId);
		const childHist = await sessions.loadHistory(childId);
		check('isolation: active session is still the parent', sessions.active() === parentId);
		check('isolation: child got its OWN turns', childHist.length >= 2 && childHist.some(m => m.content === '子任务指令'));
		check('isolation: child did NOT inherit the parent transcript',
			!childHist.some(m => m.content === '这是只有父会话才有的历史'));
		check('isolation: parent history was not overwritten by the child',
			parentHist.length === 1 && parentHist[0].content === '这是只有父会话才有的历史');
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});