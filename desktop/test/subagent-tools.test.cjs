/* Sub-agent management tools — agent_runs / agent_upsert / agent_delete / agent_message.
 *
 * These four close the last IPC-without-a-tool cluster the N-section counted (task could
 * DERIVE a sub-agent but the model could neither watch the run nor manage definitions).
 * The assertions are about the guards, not the happy path:
 *
 *   - agent_upsert's schema must NOT contain permMode. Permission gear is the user's call;
 *     and because the engine's own missing-permMode fallback is 'auto' (the most permissive),
 *     index.ts must PIN new agents to 'confirm'. Missing that pin = quiet escalation.
 *   - builtin agents are uneditable and undeletable, and the refusal names the agent.
 *   - maxTurns is clamped (a model asking for 9999 turns is asking to burn the quota).
 *   - agent_message only carries 'note': steer/terminal have a docstring but no consumer in
 *     the run loop, and accepting them would let the model believe it steered a live run.
 *   - agent_delete distinguishes missing / builtin / deleted — a bare boolean cannot.
 *
 * Run: npm run test:subagent  (needs `npm run test:build` first)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-subagent-tools.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const root = path.join(__dirname, '..');
const { registerAgentTools } = require(path.join(root, '.test', 'src', 'main', 'ai', 'agent-tools.js'));

// Fake registry: registerAgentTools only ever calls reg.register.
function makeRegistry() {
	const defs = new Map();
	return { defs, register: (d) => { defs.set(d.name, d); } };
}

// Fake host with call recording; every method individually overridable per case.
function makeHost(over = {}) {
	const calls = [];
	const h = {
		calls,
		spawn: over.spawn ?? (() => ({ id: 'run1', status: 'queued', sessionId: 's1' })),
		listAgents: over.listAgents ?? (() => [{ id: 'ag_a', name: '甲' }]),
		runs: over.runs ?? (() => null),
		agent: over.agent ?? (() => null),
		upsert: over.upsert ?? ((p) => { calls.push(['upsert', p]); return { ok: true, id: 'ag_new', name: p.name, permMode: 'confirm' }; }),
		delete: over.delete ?? (() => ({ ok: true })),
		post: over.post ?? ((...a) => { calls.push(['post', ...a]); return { ok: true, id: 'am_1' }; }),
	};
	return h;
}

const NEW_TOOLS = ['agent_runs', 'agent_upsert', 'agent_delete', 'agent_message'];

(async () => {
	// ---- 1. all four registered, with the risk gear that matches what they do ----
	{
		const reg = makeRegistry();
		registerAgentTools(reg, { host: makeHost() });
		for (const n of [...NEW_TOOLS, 'task', 'agent_list']) {
			check(`registered: ${n}`, reg.defs.has(n));
		}
		check('agent_runs is auto (read-only)', reg.defs.get('agent_runs')?.risk === 'auto');
		check('agent_upsert is confirm (writes definitions)', reg.defs.get('agent_upsert')?.risk === 'confirm');
		check('agent_message is confirm (writes to an inbox)', reg.defs.get('agent_message')?.risk === 'confirm');
		check('agent_delete is danger', reg.defs.get('agent_delete')?.risk === 'danger');
		check('danger description tells the user it needs 确认',
			/确认/.test(reg.defs.get('agent_delete')?.description ?? ''));
	}

	// ---- 2. kernel down: every tool says so instead of throwing ----
	{
		const reg = makeRegistry();
		registerAgentTools(reg, { host: null });
		for (const n of NEW_TOOLS) {
			const r = await reg.defs.get(n).invoke({ name: 'x', id: 'y', to: 'z', body: 'b' });
			check(`${n} with kernel down answers 未就绪 (got: ${String(r).slice(0, 24)})`,
				String(r).includes('未就绪'));
		}
	}

	// ---- 3. agent_runs: honest inventory ----
	{
		const reg = makeRegistry();
		const box = {
			runs: [
				{ id: 'run9', agentId: 'ag_a', sessionId: 's9', kind: 'sub', status: 'running', prompt: 'p'.repeat(80), startedAt: Date.now() - 120_000, error: undefined },
				{ id: 'run8', agentId: 'ag_b', sessionId: 's8', kind: 'primary', status: 'queued', prompt: '短指令', startedAt: Date.now(), error: 'boom' },
			],
			running: 1, limit: 4,
		};
		registerAgentTools(reg, { host: makeHost({ runs: () => box }) });
		const r = await reg.defs.get('agent_runs').invoke({});
		check('runs: head shows running/limit/total', r.includes('运行中 1 / 并发上限 4') && r.includes('共 2 条'));
		check('runs: sub run labeled and session named', r.includes('run9') && r.includes('[running·sub]') && r.includes('会话=s9'));
		check('runs: prompt truncated at 60 chars', r.includes('…') && !r.includes('p'.repeat(61)));
		check('runs: error surfaced', r.includes('错误=boom'));
		const empty = await (() => { const g = makeRegistry(); registerAgentTools(g, { host: makeHost({ runs: () => ({ runs: [], running: 0, limit: 4 }) }) }); return g.defs.get('agent_runs').invoke({}); })();
		check('runs: empty box says so explicitly', empty.includes('当前没有进行中的 run'));
	}

	// ---- 4. agent_upsert: no permMode in the schema, clamp, honest refusals ----
	{
		const reg = makeRegistry();
		const received = [];
		registerAgentTools(reg, { host: makeHost({ upsert: (p) => { received.push(p); return { ok: true, id: 'ag_1', name: p.name, permMode: 'confirm' }; } }) });
		const schema = reg.defs.get('agent_upsert').inputSchema;
		check('upsert schema has NO permMode property', !('permMode' in (schema.properties ?? {})));
		check('upsert description states permMode is user-only', /只能由用户在界面上改/.test(reg.defs.get('agent_upsert').description));
		await reg.defs.get('agent_upsert').invoke({ name: '助手', maxTurns: 999 });
		check('maxTurns 999 clamped to 50', received[0]?.maxTurns === 50);
		await reg.defs.get('agent_upsert').invoke({ name: '助手2' });
		check('maxTurns absent stays absent (engine default, not a tool invention)', !('maxTurns' in (received[1] ?? {})));
		const noName = await reg.defs.get('agent_upsert').invoke({ color: '#fff' });
		check('missing name refused before touching the host', String(noName).startsWith('[FAIL]') && received.length === 2);
		const reg2 = makeRegistry();
		registerAgentTools(reg2, { host: makeHost({ upsert: () => ({ ok: false, error: '「研究员」是内置 agent，不可修改' }) }) });
		const refused = await reg2.defs.get('agent_upsert').invoke({ id: 'team_plan_research', name: '研究员' });
		check('builtin refusal surfaced verbatim', refused.includes('内置 agent，不可修改'));
		const ok = await reg.defs.get('agent_upsert').invoke({ name: '新助手' });
		check('success names the agent, its id and the gear it got', ok.includes('新助手') && ok.includes('ag_1') && ok.includes('confirm'));
	}

	// ---- 5. agent_delete: three-way honesty ----
	{
		const reg = makeRegistry();
		let mode = 'ok';
		registerAgentTools(reg, { host: makeHost({ delete: () => (mode === 'ok' ? { ok: true } : mode === 'missing' ? { ok: false, error: '没有编号为 x 的 agent' } : { ok: false, error: '「导师」是内置 agent，不可删除' }) }) });
		check('delete ok reports deletion', (await reg.defs.get('agent_delete').invoke({ id: 'x' })).includes('已删除'));
		mode = 'missing';
		check('delete of unknown id says missing, not success', (await reg.defs.get('agent_delete').invoke({ id: 'x' })).includes('没有编号为'));
		mode = 'builtin';
		check('delete of builtin names it', (await reg.defs.get('agent_delete').invoke({ id: 'x' })).includes('内置 agent，不可删除'));
	}

	// ---- 6. agent_message: kind is structurally 'note' (no kind param exists) ----
	{
		const reg = makeRegistry();
		const host = makeHost();
		registerAgentTools(reg, { host });
		const schema = reg.defs.get('agent_message').inputSchema;
		check('message schema has no kind property (steer/terminal unrepresentable)', !('kind' in (schema.properties ?? {})));
		check('message description admits it is a note, not a live steer', /留言不是实时指令/.test(reg.defs.get('agent_message').description));
		await reg.defs.get('agent_message').invoke({ from: '主聊天', to: 'ag_a', body: '你好' });
		const post = host.calls.find(c => c[0] === 'post');
		check('post invoked with exactly (from,to,body)', !!post && post.length === 4 && post[1] === '主聊天' && post[2] === 'ag_a' && post[3] === '你好');
		const noBody = await reg.defs.get('agent_message').invoke({ to: 'ag_a' });
		check('empty body refused before touching the host', String(noBody).startsWith('[FAIL]'));
		const reg2 = makeRegistry();
		registerAgentTools(reg2, { host: makeHost({ post: () => ({ ok: false, error: '多代理内核未就绪（ai.db 不可用）' }) }) });
		check('post failure surfaced verbatim', (await reg2.defs.get('agent_message').invoke({ to: 'a', body: 'b' })).includes('多代理内核未就绪'));
	}

	// ---- 7. source-level: the guards that live in index.ts wiring, not in the tool ----
	{
		const idx = fs.readFileSync(path.join(root, 'src', 'main', 'index.ts'), 'utf8');
		const code = idx.replace(/\/\/[^\n]*/g, '');
		check('index.ts pins new agents to the confirm gear (never the engine auto fallback)',
			/permMode:\s*prev\s*\?\s*prev\.permMode\s*:\s*'confirm'/.test(code));
		check('index.ts refuses editing a builtin agent', /是内置 agent，不可修改/.test(idx));
		check('index.ts refuses deleting a builtin agent', /是内置 agent，不可删除/.test(idx));
		check('index.ts refuses upserting an unknown id (no accidental creation)', /不按不存在的 id 创建/.test(idx));
		check('agentRuns IPC reports the same concurrency the tool reports (no second hardcoded 4)',
			/IPC\.agentRuns[\s\S]{0,200}?limit:\s*am\(\)\.concurrency/.test(code));
		const toolsSrc = fs.readFileSync(path.join(root, 'src', 'main', 'ai', 'agent-tools.ts'), 'utf8');
		check('task no longer tells the model 「没有工具能查询 run 的运行态」', !toolsSrc.includes('目前没有工具能查询'));
		check('task points at agent_runs instead', /agent_runs 查看它是否还在跑/.test(toolsSrc));
		check('agent-tools module registers under the agent domain',
			/setDomain\('agent'\);\s*registerAgentTools\(/.test(code));
	}

	console.log(failed === 0
		? `subagent-tools: ALL PASS (${out.length})`
		: `subagent-tools: ${failed} FAIL of ${out.length}`);
	process.exit(failed === 0 ? 0 : 1);
})();
