/* M17: typed-parts persistence + the `task`/agent_list tools.
 * Must run under Electron (native addon): npm run test:typedparts
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-typedparts-test.txt');
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
const { TypedPartStore, toParts } = require(path.join(base, 'typed-parts.js'));
const { registerAgentTools } = require(path.join(base, 'agent-tools.js'));
const { AgentManager } = require(path.join(base, 'agents', 'AgentManager.js'));

function tmpDir(tag) {
	const d = path.join(os.tmpdir(), `pcb-tp-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
	fs.mkdirSync(d, { recursive: true });
	return d;
}

(async () => {
	// ---- 1. toParts splits content into typed parts
	{
		check('parts: plain text -> one text part', toParts({ role: 'user', content: '你好' })[0].type === 'text');
		const tc = toParts({
			role: 'assistant', content: '',
			tool_calls: [{ id: 'c1', type: 'function', function: { name: 'note_search', arguments: '{"q":"a"}' } }],
		});
		check('parts: tool_call lifted out', tc.length === 1 && tc[0].type === 'tool_call' && tc[0].name === 'note_search');
		const tr = toParts({ role: 'user', content: '[tool results]\n{"callId":"c1","value":"结果X"}' });
		check('parts: [tool results] envelope -> tool_result', tr.length === 1 && tr[0].type === 'tool_result' && tr[0].text === '结果X');
		check('parts: empty message -> no parts', toParts({ role: 'assistant', content: '  ' }).length === 0);
		const both = toParts({
			role: 'assistant', content: '先查一下',
			tool_calls: [{ id: 'c2', type: 'function', function: { name: 'x', arguments: '{}' } }],
		});
		check('parts: text + tool_call coexist', both.length === 2 && both[0].type === 'text' && both[1].type === 'tool_call');
	}

	// ---- 2. persist is incremental and idempotent
	{
		const d = tmpDir('persist');
		const db = new AiDb(d); db.migrate();
		const tp = new TypedPartStore(db);
		const hist = [
			{ role: 'user', content: '第一条' },
			{ role: 'assistant', content: '回答一', tool_calls: [{ id: 'c1', type: 'function', function: { name: 'a', arguments: '{}' } }] },
			{ role: 'user', content: '[tool results]\n{"callId":"c1","value":"ok"}' },
		];
		const n1 = tp.persist('s1', hist);
		check('persist: wrote every message first time', n1 === 3);
		const n2 = tp.persist('s1', hist);
		check('persist: second identical write adds nothing (idempotent)', n2 === 0);
		check('persist: row count stays 3', tp.storedCount('s1') === 3);
		const read = tp.read('s1');
		check('persist: seq ordering preserved', read.map(r => r.seq).join(',') === '0,1,2');
		check('persist: tool_call round-trips', read[1].parts.some(p => p.type === 'tool_call' && p.name === 'a'));
		check('persist: tool_result round-trips', read[2].parts.some(p => p.type === 'tool_result'));
		// append one message -> only the new one is written
		const n3 = tp.persist('s1', [...hist, { role: 'assistant', content: '回答二' }]);
		check('persist: only the new message is written', n3 === 1 && tp.storedCount('s1') === 4);
		// restart (cold cursor) must not duplicate
		const tp2 = new TypedPartStore(db);
		check('persist: cold cursor trusts DB count', tp2.persist('s1', hist) === 0);
		// a different session is independent
		check('persist: sessions isolated', tp.persist('s2', [{ role: 'user', content: '别的会话' }]) === 1);
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 3. task + agent_list tools (the model's path to sub-agents)
	{
		const d = tmpDir('task');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => '子代理回答' });
		const holder = { host: null };
		const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerAgentTools(reg, holder);

		check('tool: task registered', reg.defs.has('task'));
		check('tool: agent_list registered', reg.defs.has('agent_list'));
		check('tool: task is confirm-risk (never auto)', reg.defs.get('task').risk === 'confirm');
		check('tool: agent_list is auto', reg.defs.get('agent_list').risk === 'auto');

		// before the host is wired the tool must fail loudly, not silently do nothing
		const early = await reg.defs.get('task').invoke({ agent: 'x', prompt: 'y' });
		check('tool: task without host fails clearly', early.includes('内核未就绪'));

		// wire the host the way index.ts does
		holder.host = {
			spawn: (agentId, parentSessionId, prompt) => {
				const sid = 'ag_sub_test1';
				mgr.ensureSession(sid, '子任务', agentId, parentSessionId);
				const run = mgr.submitSub(agentId, sid, parentSessionId, prompt);
				return { id: run.id, status: run.status, sessionId: sid, error: run.error };
			},
			listAgents: () => mgr.listAgents().map(a => ({ id: a.id, name: a.name })),
		};

		const ag = mgr.upsertAgent({ name: '研究员' });
		const lst = await reg.defs.get('agent_list').invoke({});
		check('tool: agent_list shows the agent id + name', lst.includes(ag.id) && lst.includes('研究员'));

		mgr.ensureSession('parent1', '父会话', ag.id);
		// the parent id comes from the invocation context (the model cannot know it) — this is
		// exactly how ChatAgent.runTool calls it
		const ok = await reg.defs.get('task').invoke({ agent: ag.id, prompt: '查一下日程' }, { sessionId: 'parent1' });
		check('tool: task returns a run id + child session', ok.includes('run ') && ok.includes(ok.match(/会话 (\S+?)，/)?.[1] ?? 'x'));
		check('tool: parent id comes from context, not the model input',
			(await reg.defs.get('task').invoke({ agent: ag.id, prompt: 'x' }, { sessionId: 'parent1' })).includes('已派出'));
		const subSess = mgr.listSessions().find(s => s.parentId === 'parent1');
		check('tool: child session is a child of the parent', !!subSess && subSess.depth === 1);

		// missing args
				// the run is async — give it a tick to reach a terminal state before inspecting
		for (let i = 0; i < 40 && mgr.activeRuns().length; i++) { await new Promise(r => setTimeout(r, 10)); }
		const runCount = mgr.activeRuns().length;
		check('tool: sub run completed and drained', runCount === 0);

		check('tool: task without prompt fails', (await reg.defs.get('task').invoke({ agent: ag.id })).includes('两个参数'));
		db.close(); fs.rmSync(d, { recursive: true, force: true });
	}

	// ---- 4. task must respect the depth guard (pc-a's 验收④ depends on real derivation)
	{
		const d = tmpDir('depth');
		const db = new AiDb(d); db.migrate();
		const mgr = new AgentManager({ db, run: async () => 'ok' });
		const holder = { host: null };
		const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerAgentTools(reg, holder);
		holder.host = {
			spawn: (agentId, parentSessionId, prompt) => {
				const sid = 'ag_sub_' + Math.random().toString(36).slice(2, 7);
				mgr.ensureSession(sid, '子', agentId, parentSessionId);
				const run = mgr.submitSub(agentId, sid, parentSessionId, prompt);
				return { id: run.id, status: run.status, sessionId: sid, error: run.error };
			},
			listAgents: () => [],
		};
		const ag = mgr.upsertAgent({ name: 'A' });
		// build a chain down to the guard
		mgr.ensureSession('d0', 'r0', ag.id);
		mgr.ensureSession('d1', 'r1', ag.id, 'd0');
		mgr.ensureSession('d2', 'r2', ag.id, 'd1');
		mgr.ensureSession('d3', 'r3', ag.id, 'd2');
		const refused = await reg.defs.get('task').invoke({ agent: ag.id, prompt: 'x' }, { sessionId: 'd3' });
		check('task: depth guard refuses past MAX_DEPTH and says so', refused.includes('被拒'));
		// and a mid-tree parent still attaches properly (subtree visible, not detached)
		const okMid = await reg.defs.get('task').invoke({ agent: ag.id, prompt: 'y' }, { sessionId: 'd1' });
		check('task: mid-tree parent yields an attached child', okMid.includes('已派出'));
		const child = mgr.listSessions().find(s => s.parentId === 'd1');
		check('task: child row records parentId + depth', !!child && child.depth === 2);
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