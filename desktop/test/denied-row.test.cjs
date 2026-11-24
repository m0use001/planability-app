/* Regression: a denied tool call must still produce a visible row.
 *
 * RowBridge.toolEnd only settles a row that is still 'running'. runTool used to emit the
 * denial's tool_end WITHOUT a preceding tool_start, so the settle silently no-opped and the
 * user saw no row at all for a refused action (read-only gear or explicit user reject).
 *
 * This test drives the real ChatAgent with a fake provider and asserts the event pairing
 * that RowBridge depends on: every tool_end has a matching, not-yet-ended tool_start.
 *
 * Run: node test/denied-row.test.cjs  (needs `npm run test:build` first)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-denied-row.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const dir = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const agentJs = path.join(dir, 'agent.js');
const providerJs = path.join(dir, 'provider.js');
const cardsJs = path.join(dir, 'cards.js');
const rowsJs = path.join(dir, 'rows.js');

// one call, then plain text — per-agent turn counter (reset for each case)
let served = false;
function resetProvider() { served = false; }
require.cache[providerJs] = {
	id: providerJs, filename: providerJs, loaded: true,
	exports: {
		chatCompletion: async () => ({ ok: true }),
		streamChat: () => (async function* () {
			if (!served) { served = true; yield { type: 'tool_calls', calls: [{ id: 'c1', name: 'schedule_create', arguments: '{"title":"周会"}' }] }; }
			else { yield { type: 'text', value: '好的' }; }
		})(),
		mainSlotConfig: () => ({}), assertPublicHttps: () => new URL('https://x'),
		fetchWithConnectRetry: async () => ({}), callNonStream: async () => '',
	},
};
class FakeCards { feed(v) { return [v]; } flush() { return []; } }
require.cache[cardsJs] = { id: cardsJs, filename: cardsJs, loaded: true, exports: { CardStreamTransformer: FakeCards } };

const { ChatAgent } = require(agentJs);
const { RowBridge } = require(rowsJs);

function makeAgent(permMode, approve) {
	return new ChatAgent({
		registry: {
			listForApi: () => [],
			get: () => ({ name: 'schedule_create', description: '', risk: 'confirm', invoke: async () => 'CREATED', inputSchema: {} }),
		},
		store: { audit: async () => {} },
		sessions: { active: () => 's1', loadHistory: async () => [], saveHistory: async () => {} },
		undo: { push: async () => {} },
			sessionId: () => 's1',
		slot: () => ({ key: 'k', url: 'https://x', model: 'm' }),
		permMode: () => permMode,
		assistant: () => null,
		plugins: () => ({ undo: false, cards: true }),
		memInject: () => false,
		emit: () => {},
		requestApproval: async () => (approve ? { allow: true } : { allow: false }),
		isAborted: () => false,
	});
}

(async () => {
	// ---- case 1: read-only gear denies the write
	{
		const events = [];
		const a = makeAgent('ro', false);
		const origEmit = a.deps.emit;
		// capture events through a fresh agent built with a recording emitter
		const a2 = new ChatAgent({ ...a.deps, emit: e => events.push(e) });
		void origEmit;
		await a2.send('建个日程');

		const starts = events.filter(e => e.type === 'tool_start');
		const ends = events.filter(e => e.type === 'tool_end');
		check('ro: a tool_start was emitted', starts.length === 1);
		check('ro: a tool_end was emitted', ends.length === 1);
		check('ro: the denial is reported as denied', ends[0].denied === true && ends[0].ok === false);
		check('ro: tool never ran (no CREATED result leaked)', events.every(e => !(e.result === 'CREATED')));

		// now feed the same event sequence through the real RowBridge
		const bridge = new RowBridge(() => {}, () => {}, () => 's_test');
		for (const e of events) {
			if (e.type === 'tool_start') { bridge.toolStart(e.name, e.argsJson); }
			else if (e.type === 'tool_end') { bridge.toolEnd(e.name, e.ok, !!e.denied, e.summary, e.ms, e.result); }
		}
		bridge.settleAssistant();
		const rows = bridge.all();
		const toolRows = (Array.isArray(rows) ? rows : []).filter(r => r.kind === 'tool');
		check('ro: RowBridge produced a tool row', toolRows.length === 1);
		check('ro: row settled as denied (not left running)', toolRows.length === 1 && toolRows[0].status === 'denied');
	}

	// ---- case 2: user explicitly rejects in the approval card
	{
		resetProvider();
		const events = [];
		const a = makeAgent('confirm', false);
		const a2 = new ChatAgent({ ...a.deps, emit: e => events.push(e) });
		await a2.send('建个日程');
		const starts = events.filter(e => e.type === 'tool_start');
		const ends = events.filter(e => e.type === 'tool_end');
		check('reject: a tool_start was emitted', starts.length === 1);
		check('reject: a tool_end was emitted', ends.length === 1);
		check('reject: reported as denied', ends[0].denied === true);

		const bridge = new RowBridge(() => {}, () => {}, () => 's_test');
		for (const e of events) {
			if (e.type === 'tool_start') { bridge.toolStart(e.name, e.argsJson); }
			else if (e.type === 'tool_end') { bridge.toolEnd(e.name, e.ok, !!e.denied, e.summary, e.ms, e.result); }
		}
		const rows = bridge.all();
		const toolRows = (Array.isArray(rows) ? rows : []).filter(r => r.kind === 'tool');
		check('reject: RowBridge produced a settled denied row', toolRows.length === 1 && toolRows[0].status === 'denied');
	}

	// ---- case 3: the happy path still works (start -> ok)
	{
		resetProvider();
		const events = [];
		const a = makeAgent('confirm', true);
		const a2 = new ChatAgent({ ...a.deps, emit: e => events.push(e) });
		await a2.send('建个日程');
		const starts = events.filter(e => e.type === 'tool_start');
		const ends = events.filter(e => e.type === 'tool_end');
		check('allow: start and end both emitted once', starts.length === 1 && ends.length === 1);
		check('allow: reported ok with result', ends[0].ok === true && ends[0].result === 'CREATED');

		const bridge = new RowBridge(() => {}, () => {}, () => 's_test');
		for (const e of events) {
			if (e.type === 'tool_start') { bridge.toolStart(e.name, e.argsJson); }
			else if (e.type === 'tool_end') { bridge.toolEnd(e.name, e.ok, !!e.denied, e.summary, e.ms, e.result); }
		}
		const rows = bridge.all();
		const toolRows = (Array.isArray(rows) ? rows : []).filter(r => r.kind === 'tool');
		check('allow: row settled as ok', toolRows.length === 1 && toolRows[0].status === 'ok');
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
