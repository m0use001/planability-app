/* M17 loop-protection stack tests: signature ring, circuit breaker, soft checkpoints,
 * head+tail truncation. Pure logic — runs under plain node, but is launched through the
 * Electron wrapper so `npm test` stays one command.
 *
 * Run: npm run test:guards
 */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-guards-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const G = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'guards.js'));

const msg = (role, content) => ({ role, content });

try {
	// ---- 1. signature normalisation: cosmetic jitter must NOT look like progress
	const s1 = G.toolSignature('todo_create', '{"title":"A","due":5}');
	const s2 = G.toolSignature('todo_create', '{ "due" : 5 , "title" : "A"  }');
	const s3 = G.toolSignature('todo_create', '{"title":"B","due":5}');
	check('sig: key order/whitespace normalised to same signature', s1 === s2);
	check('sig: genuinely different args differ', s1 !== s3);
	check('sig: different tool differs', G.toolSignature('note_add', '{"a":1}') !== G.toolSignature('todo_create', '{"a":1}'));
	check('sig: non-JSON args still stable', G.toolSignature('x', 'not json') === G.toolSignature('x', '  not   json '));

	// ---- 2. repeat trips the breaker, but only after the allowed count
	{
		const g = new G.LoopGuard();
		g.beginRound();
		check('ring: 1st repeat allowed', g.observe('todo_create', '{"a":1}') === false);
		check('ring: 2nd repeat allowed', g.observe('todo_create', '{"a":1}') === false);
		check('ring: 3rd repeat allowed (== maxRepeat)', g.observe('todo_create', '{"a":1}') === false);
		check('ring: 4th repeat trips', g.observe('todo_create', '{"a":1}') === true);
		check('ring: latched afterwards', g.isTripped === true && /死循环/.test(g.tripReason));
		check('ring: further observes stay tripped', g.observe('other', '{}') === true);
		g.reset();
		check('ring: reset clears trip', g.isTripped === false && g.tripReason === '');
	}

	// ---- 3. jitter does NOT trip (the whole point of normalisation)
	{
		const g = new G.LoopGuard();
		g.beginRound();
		let tripped = false;
		for (let i = 0; i < 6; i++) {
			tripped = g.observe('schedule_query', `{"date":"2026-10-0${i % 2}"}  `) || tripped;
		}
		// same tool 6 times but args differ -> should trip only via the ring rule, and the
		// ring is 8 long so 6 calls is under it: must NOT trip.
		check('ring: varying args on one tool do not trip under the ring threshold', tripped === false);
	}

	// ---- 4. all-calls-one-tool spin trips via the ring rule
	//         (one call per round, so this isolates the spin rule from the per-round cap)
	{
		const g = new G.LoopGuard();
		let tripped = false;
		for (let i = 0; i < 10; i++) { g.beginRound(); tripped = g.observe('memory_search', `{"q":"q${i}"}`) || tripped; }
		check('ring: sustained single-tool spinning trips', tripped === true && /空转|失控|死循环/.test(g.tripReason));
		check('ring: spin trip latches, ring stops recording', g.signatures.length === G.LOOP_LIMITS.ringSize);
	}

	// ---- 5. per-round call cap
	{
		const g = new G.LoopGuard();
		g.beginRound();
		let tripped = false;
		// 13 distinct tools in one round: unique args so the repeat rule can't fire
		for (let i = 0; i < 13; i++) { tripped = g.observe('t' + i, `{"i":${i}}`) || tripped; }
		check('ring: too many calls in one round trips', tripped === true);
		check('ring: reason mentions the call cap', /失控循环/.test(g.tripReason));
	}

	// ---- 6. round counter resets, so a long healthy session isn't capped globally
	{
		const g = new G.LoopGuard();
		let tripped = false;
		for (let round = 0; round < 6 && !tripped; round++) {
			g.beginRound();
			for (let i = 0; i < 5; i++) { tripped = g.observe('tool_' + round + '_' + i, `{"r":${round},"i":${i}}`) || tripped; }
		}
		check('ring: 30 distinct calls across 6 rounds do not trip', tripped === false);
	}

	// ---- 7. ring window is bounded (distinct tools, and beginRound per round so the
	//         per-round cap is not what we are measuring here)
	{
		const g = new G.LoopGuard();
		for (let i = 0; i < 30; i++) { g.beginRound(); g.observe('tool' + i, `{"i":${i}}`); }
		check('ring: window stays bounded at ringSize', g.signatures.length === G.LOOP_LIMITS.ringSize);
		check('ring: keeps the newest entries', g.signatures[g.signatures.length - 1].indexOf('tool29') === 0);
		check('ring: distinct tools across rounds never trip', g.isTripped === false);
	}

	// ---- 8. soft checkpoints: push, list, rewind
	{
		const cs = new G.CheckpointStack();
		let hist = [msg('user', 'q1'), msg('assistant', 'a1')];
		const c0 = cs.push('before risk', hist);
		hist = [...hist, msg('user', 'q2'), msg('assistant', 'a2')];
		const c1 = cs.push('after risk', hist);
		check('cp: two checkpoints', cs.size === 2);
		check('cp: list labels', cs.list().map(c => c.label).join(',') === 'before risk,after risk');
		check('cp: seq increments', c0.seq === 0 && c1.seq === 1);

		const back = cs.rewindTo(0);
		check('cp: rewind restores earlier history', !!back && back.length === 2 && back[1].content === 'a1');
		check('cp: rewind discards later checkpoints', cs.size === 1);
		check('cp: rewind returns a copy (mutating it is safe)', (() => {
			const h = cs.rewindTo(0); h.push(msg('user', 'x'));
			return cs.list()[0].turns === 2;
		})());
		check('cp: rewind past the beginning returns null', cs.rewindTo(-1) === null);
		cs.clear();
		check('cp: clear empties', cs.size === 0);
	}

	// ---- 9. checkpoint ring is capped
	{
		const cs = new G.CheckpointStack(3);
		for (let i = 0; i < 10; i++) { cs.push('c' + i, [msg('user', 'u' + i)]); }
		check('cp: stack capped at limit', cs.size === 3);
		check('cp: keeps newest labels', cs.list().map(c => c.label).join(',') === 'c7,c8,c9');
	}

	// ---- 10. head+tail truncation
	{
		const many = [];
		for (let i = 0; i < 30; i++) { many.push(msg(i % 2 ? 'assistant' : 'user', 'turn' + i)); }
		const r = G.truncateHeadTail(many, { head: 2, tail: 6 });
		check('trunc: middle elided count', r.elided === 30 - 2 - 6);
		check('trunc: length = head + marker + tail', r.messages.length === 2 + 1 + 6);
		check('trunc: keeps the head (the ask)', r.messages[0].content === 'turn0' && r.messages[1].content === 'turn1');
		check('trunc: keeps the newest tail', r.messages[r.messages.length - 1].content === 'turn29');
		check('trunc: marker inserted in the middle', r.messages[2].content === G.ELISION_MARKER);
		check('trunc: savedChars reported > 0', r.savedChars > 0);
	}
	{
		// short history must pass through untouched
		const few = [msg('user', 'a'), msg('assistant', 'b')];
		const r = G.truncateHeadTail(few, { head: 2, tail: 6 });
		check('trunc: short history untouched', r.elided === 0 && r.messages.length === 2);
	}
	{
		// oversized single turn is clipped even if the history is short
		const big = [msg('user', 'x'.repeat(10000))];
		const r = G.truncateHeadTail(big, { maxCharsPerTurn: 100 });
		check('trunc: oversized turn clipped', r.messages[0].content.length < 200 && /已截断/.test(r.messages[0].content));
		check('trunc: clipping counted as saved', r.savedChars > 9000);
	}

	// ---- 11. token estimate
	check('tok: scales with content', G.estimateTokens([msg('user', 'a'.repeat(400))]) === 100);
	check('tok: counts tool_calls payload', G.estimateTokens([{ role: 'assistant', content: '', tool_calls: [{ id: 'x'.repeat(40), type: 'function', function: { name: 'n', arguments: '{}' } }] }]) > 0);

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
} catch (e) {
	out.push('THREW ' + (e && e.stack || e));
	failed++;
}

flush();
process.exit(failed === 0 ? 0 : 1);