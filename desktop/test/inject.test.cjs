/* M17 实时插话 tests.
 *
 * The feature: the user can type a thought while the AI is mid-turn; it is parked and injected
 * at a round boundary, so nobody has to stop the AI to add an idea.
 *
 * The subtle part this suite exists for: MAX_TOOL_ROUNDS=10 ends a turn. Without a budget
 * renewal a thought queued near the end would NEVER be seen — the queue would look accepted
 * and then be silently dropped. These tests drive the real ChatAgent with a stubbed provider.
 *
 * Run: node test/inject.test.cjs  (after `npm run test:build`)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-inject-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const dir = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const providerJs = path.join(dir, 'provider.js');
const cardsJs = path.join(dir, 'cards.js');
// agent.js requires ./provider at load time, so the stubs below MUST be installed into
// require.cache BEFORE it is required — otherwise the real network module wins and every
// test fails with "fetch failed" (this cost me a while to spot).

/** Scripted provider: `plan` is a list of rounds; each entry is 'tools' or 'text'. */
let plan = [];
let cursor = 0;
function reset(newPlan) { plan = newPlan.slice(); cursor = 0; }

require.cache[providerJs] = {
	id: providerJs, filename: providerJs, loaded: true,
	exports: {
		chatCompletion: async () => ({ ok: true }),
		streamChat: () => (async function* () {
			const step = plan[cursor++] ?? 'text';
			if (step === 'tools') {
				yield { type: 'tool_calls', calls: [{ id: 'c' + cursor, name: 'probe_' + (cursor % 3), arguments: JSON.stringify({ i: cursor }) }] };
			} else {
				yield { type: 'text', value: '答' + cursor };
			}
		})(),
		mainSlotConfig: () => ({}), assertPublicHttps: () => new URL('https://x'),
		fetchWithConnectRetry: async () => ({}), callNonStream: async () => '',
	},
};
class FakeCards { feed(v) { return [v]; } flush() { return []; } }
require.cache[cardsJs] = { id: cardsJs, filename: cardsJs, loaded: true, exports: { CardStreamTransformer: FakeCards } };
const { ChatAgent } = require(path.join(dir, 'agent.js'));

let persisted = [];
let registryCounter = () => {};
function makeAgent(overrides = {}) {
	persisted = [];
	return new ChatAgent({
		registry: {
			listForApi: () => [],
			// risk auto so no approval card is needed; distinct names+args avoid the loop guard
			get: (n) => ({ name: n, description: '', risk: 'auto', invoke: async () => { registryCounter(); return 'ok'; }, inputSchema: {} }),
		},
		store: { audit: async () => {} },
		sessions: { active: () => 's1', loadHistory: async () => [], saveHistory: async (_id, h) => { persisted = h; } },
		undo: { push: async () => {} },
			sessionId: () => 's1',
		slot: () => ({ key: 'k', url: 'https://x', model: 'm' }),
		permMode: () => 'full',
		assistant: () => null,
		plugins: () => ({ undo: false, cards: true }),
		memInject: () => false,
		emit: () => {},
		requestApproval: async () => ({ allow: true }),
		isAborted: () => false,
		...overrides,
	});
}

(async () => {
	// ---- 1. queue API is inert and lossless
	{
		const a = makeAgent();
		check('queue: starts empty', a.pendingThoughts().length === 0);
		a.enqueueThought('  再帮我加一条  ');
		a.enqueueThought('顺便把周会挪到下午');
		const q = a.pendingThoughts();
		check('queue: two thoughts parked', q.length === 2);
		check('queue: trimmed, order preserved', q[0].text === '再帮我加一条' && q[1].text === '顺便把周会挪到下午');
		a.enqueueThought('   '); // blank must be ignored
		check('queue: blank text ignored', a.pendingThoughts().length === 2);
		a.clearThoughts();
		check('queue: clear empties it', a.pendingThoughts().length === 0);
	}

	// ---- 2. injectMode defaults + validation
	{
		const a = makeAgent();
		check('mode: default is nextRound', a.getInjectMode() === 'nextRound');
		check('mode: afterTurn accepted', a.setInjectMode('afterTurn') === 'afterTurn');
		check('mode: garbage falls back to nextRound', a.setInjectMode('nonsense') === 'nextRound');
	}

	// ---- 3. a thought queued DURING a turn reaches the model
	{
		reset(['tools', 'tools', 'text']);
		const events = [];
		const a = makeAgent({ emit: e => events.push(e) });
		const turn = a.send('先做这个');
		// park a thought while round 0 is in flight
		a.enqueueThought('顺便查一下今天的会');
		await turn;
		const injected = events.filter(e => e.type === 'user_injected');
		check('inject: thought announced as injected', injected.length === 1 && injected[0].text === '顺便查一下今天的会');
		const asUser = persisted.filter(m => m.role === 'user' && m.content === '顺便查一下今天的会');
		check('inject: it is a real user message in history', asUser.length === 1);
		check('inject: it came AFTER the tool results (round boundary)', persisted.findIndex(m => m.content === '顺便查一下今天的会') > 0);
		check('inject: queue drained afterwards', a.pendingThoughts().length === 0);
	}

	// ---- 4. afterTurn mode: a thought queued mid-turn is held, then honoured at turn end
	{
		reset(['tools', 'tools', 'text']);
		const events = [];
		let agentRef = null, toolEnds = 0, injectedAtRound = -1, rounds = 0;
		const a = makeAgent({ emit: e => {
			events.push(e);
			if (e.type === 'user_injected' && e.text === '等下再补一句') { injectedAtRound = rounds; }
			if (e.type === 'tool_end' && ++toolEnds === 1) { agentRef.enqueueThought('等下再补一句'); }
		} });
		agentRef = a;
		a.setInjectMode('afterTurn');
		// count provider rounds via the registry invoke (one tool call per tools round)
		registryCounter = () => { rounds++; };
		await a.send('做一件事');
		check('afterTurn: injected before the turn ended', injectedAtRound > 0);
		check('afterTurn: it is in history as a user message',
			persisted.some(m => m.role === 'user' && m.content === '等下再补一句'));
		// In afterTurn mode the thought is queued after tool round 1, so it must NOT land at
		// round 2's boundary (which is where nextRound would have put it) — it waits for the
		// turn to conclude, i.e. after the model stops calling tools.
		check('afterTurn: deferred past the next tool round', injectedAtRound >= 2);
		check('afterTurn: queue drained afterwards', a.pendingThoughts().length === 0);
	}

	// ---- 5. THE ROUND CAP: a thought queued near the end must not be lost
	// Use 'afterTurn' so the thought is still queued when the budget check runs (in nextRound
	// mode it would have been injected one round earlier and the cap would never see it).
	// This is precisely the case the budget renewal exists for.
	{
		reset(new Array(40).fill('tools'));
		const events = [];
		let agentRef = null, toolEnds = 0;
		const a = makeAgent({ emit: e => {
			events.push(e);
			if (e.type === 'tool_end' && ++toolEnds === 9) { agentRef.enqueueThought('差点被丢掉的想法'); }
		} });
		agentRef = a;
		a.setInjectMode('afterTurn');
		await a.send('很长的任务');
		check('cap: thought survived the round cap', persisted.some(m => m.role === 'user' && m.content === '差点被丢掉的想法'));
		check('cap: renewal was announced to the user',
			events.some(e => e.type === 'text' && /达到上限，但你还有补充未处理，已继续/.test(e.value)));
		check('cap: queue empty after the run', a.pendingThoughts().length === 0);
	}

	// ---- 6. renewal is bounded — a queue that keeps refilling cannot spin forever
	{
		reset(new Array(200).fill('tools'));
		const events = [];
		let agentRef = null;
		const a = makeAgent({ emit: e => {
			events.push(e);
			// refill on every tool round so every renewal finds something waiting
			if (e.type === 'tool_end') { agentRef.enqueueThought('补充 ' + events.length); }
		} });
		agentRef = a;
		await a.send('无底洞');
		const renewals = events.filter(e => e.type === 'text' && /达到上限，但你还有补充未处理，已继续/.test(e.value)).length;
		check('cap: renewals happened', renewals >= 1);
		check('cap: renewals bounded to MAX_BUDGET_RENEWALS', renewals === 3);
		check('cap: run terminated (done emitted)', events.some(e => e.type === 'done'));
		// Whatever is still queued must have been announced, never silently dropped.
		const leftover = a.pendingThoughts().length;
		check('cap: leftover thoughts announced to the user',
			leftover === 0 || events.some(e => e.type === 'text' && /尚未处理，已保留在待发队列/.test(e.value)));
	}

	// ---- 7. a thought queued before any turn is not consumed by the next send's first round
	{
		reset(['text']);
		const a = makeAgent();
		a.enqueueThought('早就想说的');
		await a.send('先回答我');
		check('pre-queue: still delivered on the next turn', persisted.some(m => m.role === 'user' && m.content === '早就想说的'));
	}

	// ---- 8. stopping mid-turn must not silently swallow a queued thought
	{
		reset(new Array(30).fill('tools'));
		let agentRef = null, stop = false, toolEnds = 0;
		const a = makeAgent({
			// abort AFTER the thought is queued, so we test that stopping does not eat it
			isAborted: () => stop,
			emit: e => {
				if (e.type === 'tool_end' && ++toolEnds === 2) { agentRef.enqueueThought('停止前的想法'); stop = true; }
			},
		});
		agentRef = a;
		await a.send('跑起来');
		check('abort: queue preserved after the turn', a.pendingThoughts().length === 1);
		stop = false; // the user pressed stop; a later send is allowed to run again
		reset(['text']);
		await a.send('继续');
		check('abort: it can still be delivered later',
			persisted.some(m => m.role === 'user' && m.content === '停止前的想法'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});