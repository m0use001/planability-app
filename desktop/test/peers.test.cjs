/* M17 平级模式 (P3) tests: templates (Q4 form 3), peer fan-out with independent sessions,
 * and majority-vote convergence including the deliberate TIE case (Q3).
 *
 * Must run under Electron: npm run test:peers
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-peers-test.txt');
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
const { PeerStore } = require(path.join(base, 'peers.js'));
const { PeerOrchestrator } = require(path.join(base, 'peer-run.js'));
const { PEER_TEMPLATES, templateSlots, DEFAULT_PEER_TEMPLATE } = require(path.join(base, 'peer-templates.js'));

const sleep = ms => new Promise(r => setTimeout(r, ms));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-peers-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

(async () => {
	// ---- 1. templates: pre-filled AND editable (Q4 form 3)
	check('tpl: at least three templates', PEER_TEMPLATES.length >= 3);
	for (const t of PEER_TEMPLATES) {
		check('tpl: ' + t.key + ' has 3 slots', t.slots.length === 3);
		check('tpl: ' + t.key + ' slots all pre-filled', t.slots.every(s => s.prompt.trim().length > 10));
		check('tpl: ' + t.key + ' slots have distinct keys', new Set(t.slots.map(s => s.key)).size === 3);
	}
	const slots = templateSlots(DEFAULT_PEER_TEMPLATE);
	check('tpl: default key resolves', slots.length === 3);
	check('tpl: slots come back editable (agentId blank, own objects)', slots.every(s => s.agentId === ''));
	slots[0].prompt = '我改了这段提示词';
	slots[0].prompt = slots[0].prompt + '（用户改写）';
	check('tpl: editing a slot does not mutate the template',
		PEER_TEMPLATES.find(t => t.key === DEFAULT_PEER_TEMPLATE).slots[0].prompt !== slots[0].prompt);
	check('tpl: unknown key falls back to the first template', templateSlots('no-such').length === 3);

	// ---- 2. fan-out: each peer gets its OWN session, no parent (peers not sub-agents)
	{
		const d = tmpDir('fanout');
		const db = new AiDb(d); db.migrate();
		const started = [];
		const mgr = new AgentManager({ db, run: async req => { started.push(req.sessionId); return 'ok'; } });
		const peers = new PeerStore(db);
		const orch = new PeerOrchestrator(peers, mgr);

		const ag1 = mgr.upsertAgent({ name: '实现' });
		const ag2 = mgr.upsertAgent({ name: '审查' });
		const ag3 = mgr.upsertAgent({ name: '测试' });
		const s = templateSlots('impl_review_test');
		const r = orch.start('加个功能', 'M17 的第三种形态', {
			templateKey: 'impl_review_test', slots: s, agentIds: [ag1.id, ag2.id, ag3.id],
		});
		check('fanout: three runs started', r.runIds.length === 3);
		check('fanout: no slots skipped', r.skipped.length === 0);
		const sess = mgr.listSessions().filter(x => x.parentId === '');
		check('fanout: three root sessions created', sess.length === 3);
		check('fanout: every peer session is a ROOT (no parentId) — peers, not sub-agents',
			sess.every(s => s.parentId === '' && s.depth === 0));
		check('fanout: group persisted', !!peers.get(r.group.id));
		check('fanout: group starts open', r.group.status === 'open');
		// empty slot / missing agent must be skipped with a reason, not crash
		const r2 = orch.start('半空', 'x', {
			templateKey: 'custom',
			slots: [s[0], { ...s[1], prompt: '   ' }, { ...s[2], prompt: '有内容' }],
			agentIds: [ag1.id, ag2.id, ''],
		});
		check('fanout: blank slot skipped with a reason', r2.skipped.some(k => /空/.test(k.reason)));
		check('fanout: missing agent skipped with a reason', r2.skipped.some(k => /未指定 agent/.test(k.reason)));
		check('fanout: valid slot still ran', r2.runIds.length === 1);
		for (let i = 0; i < 40 && mgr.activeRuns().length; i++) { await sleep(15); }
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 3. convergence: majority decides (Q3)
	{
		const d = tmpDir('vote');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const g = peers.create('多数决', '任务');
		peers.propose(g.id, 'A', '方案A：最小改动');
		peers.propose(g.id, 'B', '方案B：重构');
		peers.propose(g.id, 'C', '方案C：换思路');
		// A gets net +2 (B and C back it), B gets +1 (A backs it), C gets nothing
		check('vote: votes accumulate per target', peers.vote(g.id, 'B', 'A', 1) === true);
		peers.vote(g.id, 'C', 'A', 1);
		peers.vote(g.id, 'A', 'B', 1);
		check('vote: three raw vote rows recorded', peers.votes(g.id).length === 3);
		check('vote: re-voting the same target replaces, not duplicates', peers.vote(g.id, 'B', 'A', 1) === true && peers.votes(g.id).length === 3);
		check('vote: voting for an unknown proposal is refused', peers.vote(g.id, 'A', 'nobody', 1) === false);
		const t = peers.tally(g.id);
		check('vote: majority winner is A', t.outcome === 'decided' && t.winnerId === 'A');
		check('vote: tally counts are right', t.tally.find(x => x.agentId === 'A').votes === 2);
		const s = peers.settle(g.id);
		check('vote: settle persists the winner', s.outcome === 'decided');
		check('vote: group marked settled', peers.get(g.id).status === 'settled' && peers.get(g.id).winnerId === 'A');
		check('vote: explanation mentions the winner', s.explain.includes('A'));
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 4. TIE must NOT be auto-resolved (the Q3 decision)
	{
		const d = tmpDir('tie');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const g = peers.create('平票', '任务');
		peers.propose(g.id, 'A', '甲方案');
		peers.propose(g.id, 'B', '乙方案');
		peers.vote(g.id, 'A', 'A', 1);
		peers.vote(g.id, 'B', 'B', 1);
		const t = peers.tally(g.id);
		check('tie: reported as tie, not decided', t.outcome === 'tie');
		check('tie: no winner picked', t.winnerId === '');
		check('tie: explanation shows both sides', t.explain.includes('A') && t.explain.includes('B'));
		const s = peers.settle(g.id);
		check('tie: group stays open for the user to decide', peers.get(g.id).status === 'open');
		check('tie: stored summary keeps the disagreement', peers.get(g.id).summary.includes('平票'));
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 5. degenerate vote states
	{
		const d = tmpDir('edge');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		// no proposals
		const g0 = peers.create('空组', 'x');
		check('edge: no proposals -> no-proposals', peers.tally(g0.id).outcome === 'no-proposals');
		// all abstain
		const g1 = peers.create('全弃权', 'x');
		peers.propose(g1.id, 'A', 'a'); peers.propose(g1.id, 'B', 'b');
		peers.vote(g1.id, 'A', 'A', 0); peers.vote(g1.id, 'B', 'B', 0);
		check('edge: all abstain -> all-abstain', peers.tally(g1.id).outcome === 'all-abstain');
		// only against votes
		const g2 = peers.create('全反对', 'x');
		peers.propose(g2.id, 'A', 'a'); peers.propose(g2.id, 'B', 'b');
		peers.vote(g2.id, 'B', 'A', -1); peers.vote(g2.id, 'A', 'B', -1);
		check('edge: no supporting votes -> all-abstain', peers.tally(g2.id).outcome === 'all-abstain');
		// blank proposal refused
		check('edge: blank proposal refused', peers.propose(g2.id, 'C', '   ') === null);
		// re-proposing replaces rather than duplicating
		peers.propose(g2.id, 'A', 'a2');
		check('edge: re-propose replaces', peers.proposals(g2.id).filter(p => p.agentId === 'A').length === 1);
		check('edge: unknown group reports no-proposals', peers.tally('nope').outcome === 'no-proposals');
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 6. orchestrator view for the renderer
	{
		const d = tmpDir('view');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const orch = new PeerOrchestrator(peers, mgr);
		const ag1 = mgr.upsertAgent({ name: 'A' });
		const ag2 = mgr.upsertAgent({ name: 'B' });
		const r = orch.start('组', '任务', {
			templateKey: 'options', slots: templateSlots('options'), agentIds: [ag1.id, ag2.id, ''],
		});
		check('view: unknown group -> null', orch.view('nope').group === null);
		orch.propose(r.group.id, ag1.id, '甲的方案');
		orch.propose(r.group.id, ag2.id, '乙的方案');
		const v = orch.view(r.group.id);
		check('view: group readable', v.group && v.group.id === r.group.id);
		check('view: proposals listed', v.proposals.length === 2);
		check('view: no result before settling', v.result === null);
		orch.vote(r.group.id, ag2.id, ag1.id, 1);
		const res = orch.settle(r.group.id);
		check('view: settle returns an outcome', res.outcome === 'decided' && res.winnerId === ag1.id);
		check('view: settled group exposes its result', orch.view(r.group.id).result !== null);
		check('view: list returns the group', orch.list().some(g => g.id === r.group.id));
		check('view: remove works', orch.remove(r.group.id) === true && orch.view(r.group.id).group === null);
		for (let i = 0; i < 40 && mgr.activeRuns().length; i++) { await sleep(15); }
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 7. settling with no explicit votes: self-support, which is a TIE by design
	{
		const d = tmpDir('selfvote');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const orch = new PeerOrchestrator(peers, mgr);
		const g = peers.create('自投票', '任务');
		peers.propose(g.id, 'A', '甲'); peers.propose(g.id, 'B', '乙'); peers.propose(g.id, 'C', '丙');
		const n = orch.selfVote(g.id);
		check('selfvote: one vote per proposal', n === 3);
		check('selfvote: three raw vote rows', peers.votes(g.id).length === 3);
		check('selfvote: each option has exactly one supporter',
			peers.proposals(g.id).every(p => p.vote === 1));
		const t = peers.tally(g.id);
		check('selfvote: 3 peers self-supporting -> TIE, not a fabricated majority',
			t.outcome === 'tie' && t.winnerId === '');
		check('selfvote: vote brief lists the other proposals',
			orch.voteBrief(g.id, 'A').includes('B') && !orch.voteBrief(g.id, 'A').includes('：甲'));
		check('selfvote: lone peer sees the other two', orch.voteBrief(g.id, 'A').includes('乙') && orch.voteBrief(g.id, 'A').includes('丙'));
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 8. peer session naming is what lets index.ts route an answer back to its group
	{
		const d = tmpDir('routing');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const peers = new PeerStore(db);
		const orch = new PeerOrchestrator(peers, mgr);
		const a1 = mgr.upsertAgent({ name: 'R1' });
		const r = orch.start('路由组', '任务', {
			templateKey: 'options', slots: templateSlots('options'), agentIds: [a1.id, '', ''],
		});
		const sid = mgr.listSessions().find(s => s.title.includes('路由组'))?.id ?? '';
		check('routing: peer session id carries the group id as a prefix', sid.startsWith(r.group.id));
		// the exact slice index.ts uses to recover the group id must work — LAST underscore,
		// and slot keys are sanitised so they cannot contain one
		const cut = sid.lastIndexOf('_');
		check('routing: group id recoverable by the same slice', cut > 0 && sid.slice(0, cut) === r.group.id);
		check('routing: slot key was sanitised (no underscore survives)',
			!sid.slice(cut + 1).includes('_'));
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