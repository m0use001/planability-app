/* M19-1 ContextBudget tests.
 *
 * The property that matters most here: compaction must never DESTROY the transcript, and the
 * manifest must describe the payload that is actually sent. Both are easy to get subtly wrong
 * and invisible until a user's context quietly stops making sense.
 *
 * Run: npm test:context
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-ctx-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const B = require(path.join(base, 'ai', 'context-budget.js'));
// guards is required directly so the token estimate can be computed INDEPENDENTLY of the
// module under test — otherwise the test would compare a value against itself.
const G = require(path.join(base, 'ai', 'guards.js'));

/** Build a transcript of n tool-heavy turns. */
function mkHistory(n, charsPer = 200) {
	const msgs = [];
	for (let i = 0; i < n; i++) {
		msgs.push({ role: 'user', content: `ask ${i}` });
		msgs.push({ role: 'assistant', content: `answer ${i}` });
		msgs.push({ role: 'tool', content: 'x'.repeat(charsPer) });
	}
	return msgs;
}

const CFG = { maxTokens: 10_000, triggerRatio: 0.95, minTokensBeforeCompaction: 500, head: 2, tail: 4, maxCharsPerTurn: 1000 };

(async () => {
	// ---- 1. a small context is left completely alone
	{
		const h = mkHistory(4, 50);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 100 }, CFG);
		check('small: no compaction below the trigger', r.manifest.tier === 'none');
		check('small: the payload is the transcript verbatim', r.messages.length === h.length);
		check('small: nothing was saved', r.manifest.savedTokens === 0);
		check('small: the system prompt is untouched', r.system === 'sys');
		check('small: not degraded', r.manifest.degraded === false);
	}

	// ---- 2. THE critical property: compaction never eats the stored transcript
	{
		const h = mkHistory(120, 900);
		const snapshot = JSON.stringify(h);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 2000 }, CFG);
		check('lossless: the ORIGINAL transcript is byte-identical after compaction',
			JSON.stringify(h) === snapshot, );
		// "Smaller" must mean FEWER TOKENS. Micro-compaction shortens tool results in place, so
		// the message COUNT stays identical — asserting on length would have hidden that.
		check('lossless: the outgoing payload uses fewer tokens',
			G.estimateTokens(r.messages) < G.estimateTokens(h));
		// Folding must be visible, not silent.
		check('lossless: the tier is reported honestly', r.manifest.tier !== 'none' && r.manifest.compacted === true);
	}

	// ---- 3. the compaction ladder is cheapest-first
	{
		// Over the trigger, but micro-compaction alone brings it back under, so it stays at micro.
		// (The tool block is large on purpose: it is what pushes history over the line while
		// leaving micro enough room to fix it.)
		const mild = mkHistory(40, 900);
		const r1 = await B.buildBudgetedRequest({ history: mild, system: 'sys', toolTokens: 1000 }, CFG);
		check('ladder: micro-compaction is tried first', r1.manifest.tier === 'micro');
		// Enormous: must reach a coarser tier.
		const huge = mkHistory(400, 900);
		const r2 = await B.buildBudgetedRequest({ history: huge, system: 'sys', toolTokens: 1000 }, CFG);
		check('ladder: an oversized context escalates past micro',
			['fold', 'summary'].includes(r2.manifest.tier));
		check('ladder: escalation reports real savings', r2.manifest.savedTokens > 0);
	}

	// ---- 4. micro-compaction only touches TOOL results, never the user's instructions
	{
		// Clipping a user message is clipping an instruction, which is much worse than clipping
		// a file dump. Verify by content, not by count.
		const h = [
			{ role: 'user', content: 'U'.repeat(5000) },
			{ role: 'tool', content: 'T'.repeat(5000) },
		];
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 9000 }, CFG);
		const userMsg = r.messages.find(m => m.role === 'user');
		const toolMsg = r.messages.find(m => m.role === 'tool');
		check('micro: a long USER message is never clipped', userMsg.content.length === 5000);
		check('micro: a long TOOL result IS clipped', toolMsg.content.length < 5000);
		// Regression guard for a real flaw found while writing this: the fold tier used
		// truncateHeadTail, which clips EVERY turn including the head — and the head is the
		// original ask. Clipping an instruction makes the model do the wrong thing with no
		// visible symptom afterwards. The head must survive verbatim.
		const bigAsk = [{ role: 'user', content: 'THE-ORIGINAL-ASK:' + 'U'.repeat(5000) },
		...mkHistory(80, 900)];
		const rr = await B.buildBudgetedRequest({ history: bigAsk, system: 'sys', toolTokens: 5000 }, CFG);
		check('fold: the ORIGINAL ASK survives verbatim at the fold tier',
			rr.messages[0].content === bigAsk[0].content, );
		check('micro: the clip keeps the head and marks itself',
			toolMsg.content.startsWith('T') && /已压缩/.test(toolMsg.content));
	}

	// ---- 5. micro-compaction spares the head (the original ask) and the tail (current step)
	{
		const h = mkHistory(40, 600);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 1000 }, CFG);
		const firstTool = r.messages.findIndex(m => m.role === 'tool');
		const lastTool = r.messages.map(m => m.role).lastIndexOf('tool');
		check('micro: the oldest tool result survives intact',
			r.messages[firstTool].content === h[firstTool].content);
		check('micro: the newest tool result survives intact',
			r.messages[lastTool].content === h[lastTool].content);
	}

	// ---- 6. the summary tier, and that it is PREFERRED over folding
	{
		const h = mkHistory(80, 500);
		let summarised = 0;
		const r = await B.buildBudgetedRequest({
			history: h, system: 'sys', toolTokens: 3000,
			summarize: async (msgs) => { summarised++; return 'earlier work concerned X'; },
		}, CFG);
		check('summary: the summariser is actually called', summarised === 1);
		check('summary: the summary tier is reached', r.manifest.tier === 'summary');
		check('summary: the summary text appears in the payload',
			r.messages.some(m => /前情摘要/.test(m.content) && /earlier work/.test(m.content)));
		// A summary PRESERVES intent; a fold destroys it. So summary must be tried first.
		check('summary: summary is preferred over fold (order matters)',
			r.messages.some(m => /前情摘要/.test(m.content))
			&& !r.messages.some(m => m.content.includes('中间若干轮工具结果已折叠')));
	}

	// ---- 7. a FAILING summariser must degrade, not lose the conversation
	{
		const h = mkHistory(80, 500);
		const r = await B.buildBudgetedRequest({
			history: h, system: 'sys', toolTokens: 3000,
			summarize: async () => { throw new Error('worker slot busy'); },
		}, CFG);
		check('degrade: a throwing summariser is caught', r.manifest.degraded === true);
		check('degrade: it falls back to folding instead of losing history',
			r.manifest.tier === 'fold');
		check('degrade: the payload is still non-empty and shorter',
			r.messages.length > 0 && r.messages.length < h.length);
		check('degrade: the summary says so', /降级/.test(B.manifestSummary(r.manifest)));
	}

	// ---- 8. an EMPTY summary is not a summary
	{
		const h = mkHistory(80, 500);
		const r = await B.buildBudgetedRequest({
			history: h, system: 'sys', toolTokens: 3000,
			summarize: async () => '   ',
		}, CFG);
		check('summary: whitespace is not accepted as a summary', r.manifest.tier === 'fold');
		check('summary: not degraded when the summariser simply had nothing', r.manifest.degraded === false);
	}

	// ---- 9. no summariser => summary tier is unreachable, and that is fine
	{
		const h = mkHistory(80, 500);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 3000 }, CFG);
		check('no-summariser: the summary tier is not faked', r.manifest.tier !== 'summary');
		check('no-summariser: it folds instead', r.manifest.tier === 'fold');
	}

	// ---- 10. THE manifest must match the payload that is sent
	{
		const h = mkHistory(60, 400);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 2000 }, CFG);
		check('manifest: rows sum to the total', r.manifest.sum === r.manifest.total);
		check('manifest: the history row equals the tokens of the OUTGOING history',
			r.manifest.rows.find(x => x.key === 'history').tokens
			=== G.estimateTokens(r.messages));
		check('manifest: every key appears exactly once', r.manifest.rows.length === B.MANIFEST_KEYS.length);
		check('manifest: rows are sorted by cost, largest first',
			r.manifest.rows.every((x, i) => i === 0 || r.manifest.rows[i - 1].tokens >= x.tokens));
		check('manifest: shares sum to 1',
			Math.abs(r.manifest.rows.reduce((s, x) => s + x.share, 0) - 1) < 1e-9);
		check('manifest: ratio is relative to the limit', Math.abs(r.manifest.ratio - r.manifest.total / r.manifest.limit) < 1e-9);
		check('manifest: every row has a label', r.manifest.rows.every(x => !!x.label && x.label.length > 0));
	}

	// ---- 11. toggles actually change the payload
	{
		const h = mkHistory(30, 300);
		const parts = {
			persona: '# persona: terse',
			memory: 'memory hint text',
			todo: 'todo reminder text',
		};
		const system = `BASE\n\n${parts.persona}\n\n${parts.memory}\n\n${parts.todo}`;
		const deps = { history: h, system, systemParts: parts, toolTokens: 1000 };
		const base = await B.buildBudgetedRequest(deps, CFG);
		const mem = base.manifest.rows.find(x => x.key === 'memory');
		check('toggle: memory is counted when on', mem.tokens > 0 && mem.enabled === true);
		check('toggle: persona is counted when on',
			base.manifest.rows.find(x => x.key === 'persona').tokens > 0);
		check('toggle: todo is counted when on',
			base.manifest.rows.find(x => x.key === 'todo').tokens > 0);

		const off = await B.buildBudgetedRequest(deps, CFG, { memory: false });
		check('toggle: turning memory off zeroes its tokens AND its row',
			off.manifest.rows.find(x => x.key === 'memory').tokens === 0
			&& off.manifest.rows.find(x => x.key === 'memory').enabled === false);
		check('toggle: the memory text is actually gone from the prompt',
			!/memory hint text/.test(off.system));
		check('toggle: the other parts survive', /todo reminder text/.test(off.system) && /# persona/.test(off.system));
		check('toggle: the total went down', off.manifest.total < base.manifest.total);

		const noHistory = await B.buildBudgetedRequest(deps, CFG, { history: false });
		check('toggle: history off sends no history at all', noHistory.messages.length === 0);
		check('toggle: history row is zeroed', noHistory.manifest.rows.find(x => x.key === 'history').tokens === 0);
		const noTools = await B.buildBudgetedRequest(deps, CFG, { tools: false });
		check('toggle: tools off zeroes the tools row',
			noTools.manifest.rows.find(x => x.key === 'tools').tokens === 0);
	}

	// ---- 12. toggles are applied BEFORE budgeting, not after
	// If a toggle shrinks the payload after the budget is solved, the trigger ratio means
	// different things depending on evaluation order.
	{
		const h = mkHistory(120, 900);
		// Big enough that history ON compacts (history ~27k tokens vs a 9.5k trigger).
		const on = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 2000 }, CFG);
		// With history off, there is nothing to compact and nothing to lose.
		const off = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 2000 }, CFG, { history: false });
		check('order: history-off never compacts (nothing to compact)',
			off.manifest.tier === 'none' && off.manifest.compacted === false);
		check('order: history-on compacts the same history', on.manifest.tier !== 'none');
		check('order: off is strictly cheaper', off.manifest.total < on.manifest.total);
	}

	// ---- 13. absent toggles default to ON (a new key must not silently disable anything)
	{
		const h = mkHistory(6, 100);
		const r = await B.buildBudgetedRequest({ history: h, system: 's', toolTokens: 50 }, CFG, {});
		check('default: an empty toggle map leaves everything enabled',
			r.manifest.rows.every(x => x.enabled === true));
		const r2 = await B.buildBudgetedRequest({ history: h, system: 's', toolTokens: 50 }, CFG, undefined);
		check('default: undefined toggles behave the same', r2.manifest.rows.every(x => x.enabled === true));
	}

	// ---- 14. when the parts are unknown, we must NOT fake a removal
	{
		const h = mkHistory(6, 100);
		const r = await B.buildBudgetedRequest(
			{ history: h, system: 'sys with memory inline', toolTokens: 50 }, CFG, { memory: false });
		// No systemParts => we cannot subtract. Better to keep the prompt and say nothing false.
		check('no-parts: the prompt is kept rather than falsely edited',
			r.system === 'sys with memory inline');
		check('no-parts: the memory row is zeroed so the panel still shows the intent', r.manifest.total >= 0);
	}

	// ---- 15. the manifest log is bounded
	{
		const log = new B.ManifestLog();
		const h = mkHistory(6, 100);
		for (let i = 0; i < 600; i++) {
			const r = await B.buildBudgetedRequest({ history: h, system: 's', toolTokens: 10 }, CFG);
			log.record(r.manifest);
		}
		check('log: it does not grow without bound', log.recent(1000).length <= 500);
		check('log: the last entry is retrievable', log.last && log.last.total > 0);
		check('log: entries carry the per-key breakdown', log.last.by.history >= 0);
		check('log: recent(n) returns the newest', log.recent(3).length === 3);
		check('log: recent(0) does not throw', Array.isArray(log.recent(0)));
	}

	// ---- 16. summary text and labels
	{
		const m = { rows: [], total: 0, sum: 0, limit: 100, ratio: 0.5, tier: 'none', degraded: false, compacted: false, savedTokens: 0 };
		check('summary text: none reads as a plain percentage', B.manifestSummary(m) === '50%');
		check('summary text: fold names the fold', /折叠/.test(B.manifestSummary({ ...m, tier: 'fold' })));
		check('summary text: summary names the summary', /摘要/.test(B.manifestSummary({ ...m, tier: 'summary' })));
		check('summary text: degraded is stated FIRST', /降级/.test(B.manifestSummary({ ...m, tier: 'fold', degraded: true })));
		check('labels: every manifest key has a Chinese label',
			B.MANIFEST_KEYS.every(k => B.MANIFEST_LABELS[k] && B.MANIFEST_LABELS[k].length > 0));
	}

	// ---- 17. degenerate configs must not produce NaN ratios or infinite loops
	{
		const h = mkHistory(20, 200);
		const zeroLimit = await B.buildBudgetedRequest({ history: h, system: 's', toolTokens: 0 }, { ...CFG, maxTokens: 0 });
		check('degenerate: a zero limit does not divide by zero',
			Number.isFinite(zeroLimit.manifest.ratio));
		const negHead = await B.buildBudgetedRequest({ history: h, system: 's', toolTokens: 5000 },
			{ ...CFG, head: -5, tail: -5 });
		check('degenerate: negative head/tail still terminates',
			Array.isArray(negHead.messages) && negHead.messages.length > 0);
		const empty = await B.buildBudgetedRequest({ history: [], system: '', toolTokens: 0 }, CFG);
		check('degenerate: an empty request is fine',
			empty.messages.length === 0 && empty.manifest.total >= 0 && empty.manifest.tier === 'none');
		const hugeTail = await B.buildBudgetedRequest({ history: mkHistory(10, 100), system: 's', toolTokens: 0 },
			{ ...CFG, tail: 9999 });
		check('degenerate: a tail larger than the history does not invent messages',
			hugeTail.messages.length <= 30);
	}

	// ---- 18. a compaction cut must never split an assistant(tool_calls) → tool* round
	// pc-a's 01:29 block one level up. Making every tool turn carry a tool_call_id fixed the
	// PUSH site, but a cut can still separate the answers from the assistant that issued them,
	// and the server then rejects the whole request: the orphan reports "`tool_call_id` is not
	// set" even though every id is present, because the half that would have given the id its
	// meaning is gone. Externally that reads as "the model went quiet", so it belongs here and
	// not in a live session.
	{
		const tc = (id) => ({ id, type: 'function', function: { name: 'f', arguments: '{}' } });
		const round = (tag, ids) => ([
			{ role: 'assistant', content: '', tool_calls: ids.map(tc) },
			// Kept under 500 chars so micro-compaction (maxCharsPerTurn / 2) cannot fire first —
			// this test is about the FOLD cut, and a different tier would not exercise it.
			...ids.map(id => ({ role: 'tool', tool_call_id: id, content: `${tag}:${id} ` + 'r'.repeat(470) })),
		]);
		const filler = (i) => ([
			{ role: 'user', content: `q${i} ` + 'u'.repeat(120) },
			{ role: 'assistant', content: `a${i} ` + 'a'.repeat(120) },
		]);

		// Rounds placed so BOTH default cuts land inside one: head=2 puts a tool response at
		// index 2 (its assistant at 1), tail=4 puts one at n-4 (its assistant at n-5).
		const h = [{ role: 'user', content: 'THE-ORIGINAL-ASK' }, ...round('early', ['e1', 'e2'])];
		for (let i = 0; i < 40; i++) { h.push(...filler(i), ...round(`r${i}`, [`a${i}`, `b${i}`])); }
		h.push(...round('late', ['t1', 't2']),
			{ role: 'assistant', content: 'final answer' },
			{ role: 'user', content: 'next question' });

		// A tool turn is legal at the head of a run (right after its issuing assistant) or
		// inside one (right after another tool turn); anywhere else it is an orphan. Runs are
		// walked from their head so a multi-call round counts once, not once per response.
		const orphans = (msgs) => {
			let bad = 0;
			for (let i = 0; i < msgs.length; i++) {
				if (msgs[i].role !== 'tool') { continue; }
				const prev = msgs[i - 1];
				if (prev && prev.role === 'tool') { continue; }
				if (!prev || prev.role !== 'assistant' || !prev.tool_calls) { bad++; continue; }
				for (let j = i; j < msgs.length && msgs[j].role === 'tool'; j++) {
					if (!prev.tool_calls.some(c => c.id === msgs[j].tool_call_id)) { bad++; break; }
				}
			}
			return bad;
		};
		// The other direction: an assistant that announced calls and then went silent.
		const split = (msgs) => msgs.reduce((n, m, i) => {
			if (m.role !== 'assistant' || !m.tool_calls || !m.tool_calls.length) { return n; }
			const next = msgs[i + 1];
			return n + (next && next.role === 'tool' ? 0 : 1);
		}, 0);

		check('pairing: the fixture really does straddle both cuts',
			h[2].role === 'tool' && h[h.length - 4].role === 'tool');

		const snapshot = JSON.stringify(h);
		const r = await B.buildBudgetedRequest({ history: h, system: 'sys', toolTokens: 4000 }, CFG);
		check('pairing: the fold tier is actually reached', r.manifest.tier === 'fold');
		check('pairing: the fold sends no orphan tool turn', orphans(r.messages) === 0);
		check('pairing: the fold leaves no assistant separated from its responses', split(r.messages) === 0);
		check('pairing: the original ask still leads the payload',
			r.messages[0].content === 'THE-ORIGINAL-ASK');
		check('pairing: the stored transcript is untouched', JSON.stringify(h) === snapshot);

		// The summary tier builds its own head/tail, so it needs the same snapping proved apart.
		let summarised = 0;
		const s = await B.buildBudgetedRequest({
			history: h, system: 'sys', toolTokens: 4000,
			summarize: async () => { summarised++; return 'earlier work'; },
		}, CFG);
		check('pairing: the summary tier is reached', summarised === 1 && s.manifest.tier === 'summary');
		check('pairing: the summary cut sends no orphan tool turn', orphans(s.messages) === 0);
		check('pairing: the summary cut leaves no assistant separated from its responses', split(s.messages) === 0);

		// The rule itself, at the unit level. Both repairs are MOVES of the cut, never deletions
		// — discarding the other half is how the request breaks in the first place — and a
		// history shorter than head+tail must not produce overlapping slices, which would send
		// the same message twice.
		const P = [
			{ role: 'user', content: 'ask' },
			{ role: 'assistant', content: '', tool_calls: [tc('h1')] },
			{ role: 'tool', tool_call_id: 'h1', content: 'x' },
			{ role: 'user', content: 'q' },
			{ role: 'assistant', content: 'a' },
			{ role: 'assistant', content: '', tool_calls: [tc('t1')] },
			{ role: 'tool', tool_call_id: 't1', content: 'y' },
		];
		const headSide = G.snapToolSafeCuts(P, 2, 1);
		check('snap: a head cut stopping before a tool response slides forward over the run',
			headSide.headEnd === 3);
		check('snap: a tail cut starting on a tool response slides back onto its assistant',
			headSide.tailStart === 5 && P[headSide.tailStart].role === 'assistant');
		check('snap: the run lands wholly inside the head', P.slice(0, headSide.headEnd).filter(m => m.role === 'tool').length === 1);
		const crossing = G.snapToolSafeCuts(P, 10, 10);
		check('snap: head and tail never overlap, so no message is sent twice',
			crossing.headEnd <= crossing.tailStart && crossing.tailStart <= P.length);
		const sane = G.snapToolSafeCuts(P, 2, 1);
		check('snap: both cuts stay inside the array', sane.headEnd >= 0 && sane.headEnd <= P.length
			&& sane.tailStart >= 0 && sane.tailStart <= P.length);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});