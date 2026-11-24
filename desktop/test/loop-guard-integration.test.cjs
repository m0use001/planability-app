/* Integration check: does the loop guard actually stop a repeating ChatAgent round?
 * Drives ChatAgent against a fake provider (no network, no key) by stubbing the modules
 * ChatAgent imports, then asserts the breaker fires mid-loop instead of looping forever.
 *
 * Run: node test/loop-guard-integration.test.cjs  (needs .test build first)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-loopguard-int.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

// ---- stub the network layer before requiring agent.js -------------------------------
// The same trick ChatAgent's own deps allow: we replace provider/card stream modules in
// require.cache so no key or socket is needed.
const agentJs = path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'agent.js');
const providerJs = path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'provider.js');
const cardsJs = path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'cards.js');

let roundCounter = 0;
/** Each round asks for the SAME tool with the SAME args -> a real loop. */
function fakeStreamChat() {
	return (async function* () {
		const i = roundCounter++;
		if (i < 20) {
			yield { type: 'tool_calls', calls: [{ id: 'c' + i, name: 'schedule_query', arguments: '{"date":"2026-10-05"}' }] };
		} else {
			yield { type: 'text', value: '收工' };
		}
	})();
}

require.cache[providerJs] = {
	id: providerJs, filename: providerJs, loaded: true,
	exports: {
		chatCompletion: async () => ({ ok: true }),
		streamChat: fakeStreamChat,
		mainSlotConfig: () => ({ url: 'https://x', model: 'm', key: 'k', authStyle: 'bearer' }),
		assertPublicHttps: () => new URL('https://x'),
		fetchWithConnectRetry: async () => ({ ok: true }),
		callNonStream: async () => '',
	},
};
class FakeCardTransformer { feed(v) { return [v]; } flush() { return []; } }
require.cache[cardsJs] = {
	id: cardsJs, filename: cardsJs, loaded: true,
	exports: { CardStreamTransformer: FakeCardTransformer },
};

const { ChatAgent } = require(agentJs);

(async () => {
	const events = [];
	const registry = {
		listForApi: () => [],
		get: (n) => ({ name: n, description: '', risk: 'auto', invoke: async () => '结果', inputSchema: {} }),
	};
	const sessions = {
		active: () => 's1',
		loadHistory: async () => [],
		saveHistory: async () => {},
	};
	const agent = new ChatAgent({
		registry,
		store: { audit: async () => {} },
		sessions,
		undo: { push: async () => {} },
			sessionId: () => 's1',
		slot: () => ({ url: 'https://x', model: 'm', key: 'k', authStyle: 'bearer' }),
		permMode: () => 'full',
		assistant: () => null,
		plugins: () => ({ undo: false, cards: true }),
		memInject: () => false,
		emit: (e) => events.push(e),
		requestApproval: async () => ({ allow: true }),
		isAborted: () => false,
	});

	await agent.send('查询今天的日程');

	const done = events.find(e => e.type === 'done');
	const errs = events.filter(e => e.type === 'error');
	check('loop guard: turn ends with done (not error/throw)', !!done && errs.length === 0);

	// MAX_TOOL_ROUNDS is 10; a true loop would stop at the round cap. The guard must stop
	// it EARLIER, i.e. fewer provider rounds than the cap.
	const rounds = roundCounter;
	check('loop guard: stopped before the 10-round cap', rounds < 10);
	check('loop guard: stopped at a small round count', rounds >= 3 && rounds <= 6);

	const state = agent.guardState();
	check('loop guard: reports tripped', state.tripped === true);
	check('loop guard: reason is about the repeat', /死循环|空转|失控/.test(state.reason));

	// the model is told why it stopped, and the user sees it
	check('loop guard: user told why it stopped',
		events.some(e => e.type === 'text' && /中止工具调用/.test(e.value)));

	// the tool itself still ran (it is auto-risk), so this is not a silent no-op
	check('loop guard: did not error out silently', events.some(e => e.type === 'tool_start'));

	// checkpoints + token estimate surface
	check('cp: pushCheckpoint returns a seq', agent.pushCheckpoint('manual') === 0);
	check('cp: list reflects the checkpoint', agent.checkpointList().length === 1);
	check('tok: context estimate > 0', agent.estimateContextTokens() > 0);

	// rewind restores history (and persists)
	check('cp: rewind to checkpoint succeeds', await agent.rewindTo(0) === true);
	check('cp: rewind past the start returns false', await agent.rewindTo(-5) === false);

	// a fresh turn resets the guard: a NEW instruction gets a fresh budget, so the guard must
// re-trip for turn 2 rather than be stuck latched from turn 1.
	roundCounter = 0;
	await agent.send('再查一次别的');
	check('guard: fresh turn re-evaluates (not stuck latched)', agent.guardState().tripped === true);
	check('guard: turn 2 stopped just as early', roundCounter < 10);
	check('guard: turn 2 ran a full independent loop', roundCounter >= 3);

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});