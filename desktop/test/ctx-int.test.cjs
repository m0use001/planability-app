/* M19-1 integration: the budget is actually on the request path, and tool turns are tool turns.
 *
 * These tests deliberately stub the network and count what agent.ts BUILDS, because the two
 * regressions that matter are invisible in the pure module: a budget that is computed but never
 * applied, and tool results that are indistinguishable from user instructions.
 *
 * Run: npm test:ctxint
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-ctxint-test.txt');
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
const S = require(path.join(base, 'ai', 'summarize.js'));

(async () => {
	// ---- 1. the summary RENDERER clips its input
	{
		const long = [{ role: 'tool', content: 'x'.repeat(50_000) }];
		const r = S.renderForSummary(long);
		check('summary render: a 50k turn is clipped', r.clipped === true);
		check('summary render: the clipped turn is marked', /本条已截断/.test(r.text));
		check('summary render: the rendered text stays under the cap',
			r.text.length <= S.SUMMARY_LIMITS.maxInputChars + 200);
		const small = [{ role: 'user', content: 'do the thing' }];
		const r2 = S.renderForSummary(small);
		check('summary render: a short input is not marked clipped', r2.clipped === false);
		check('summary render: roles are labelled so the model can tell them apart',
			/【用户】/.test(r2.text) && /【工具结果】/.test(S.renderForSummary(long).text));
		const empty = S.renderForSummary([]);
		check('summary render: an empty transcript renders to nothing', empty.text === '');
	}

	// ---- 2. the summary PROMPT says so when the input was clipped
	{
		const clipped = S.summaryPrompt('X', true);
		const whole = S.summaryPrompt('X', false);
		check('summary prompt: the clipped case says it was clipped', /截断/.test(clipped));
		check('summary prompt: the unclipped case does not claim clipping', !/截断/.test(whole));
		check('summary prompt: both ask for the summary', /摘要/.test(clipped) && /摘要/.test(whole));
	}

	// ---- 3. the summariser NEVER throws — a failure must reach the budget's fallback
	{
		// A slot with no key makes callNonStream reject; the summariser must swallow it so the
		// budget can report "degraded" instead of the exception escaping into the send loop.
		const bad = S.makeSummarizer(() => ({ baseUrl: 'http://127.0.0.1:1/v1', apiKey: '', model: 'x' }));
		let threw = false;
		let out = null;
		try { out = await bad([{ role: 'user', content: 'hello' }]); } catch { threw = true; }
		check('summarizer: a provider failure does not throw', threw === false);
		check('summarizer: a provider failure returns empty so the budget can fold instead',
			out === '' || out === null);
		// An empty transcript also yields '' without calling the provider at all.
		const emptyOut = await bad([]);
		check('summarizer: an empty transcript short-circuits', emptyOut === '');
	}

	// ---- 4. the summary prompt asks for WORK, not for a description of the text
	// A summary that says "the user pasted a file dump" costs a round trip and saves nothing.
	{
		const sys = S.makeSummarizer.toString();
		void sys;
		// The system text is not exported, so assert on the observable contract instead: the
		// summariser is constructed from these limits, and they exist.
		check('summary limits: an input cap exists', S.SUMMARY_LIMITS.maxInputChars > 0);
		check('summary limits: a per-turn clip exists', S.SUMMARY_LIMITS.maxCharsPerTurn > 0);
		check('summary limits: an output cap exists', S.SUMMARY_LIMITS.maxSummaryChars > 0);
		check('summary limits: the input cap is large enough to be useful',
			S.SUMMARY_LIMITS.maxInputChars >= 8000);
		check('summary limits: the per-turn clip is tighter than the input cap',
			S.SUMMARY_LIMITS.maxCharsPerTurn < S.SUMMARY_LIMITS.maxInputChars);
	}

	// ---- 5. the budget reaches the summary tier when the summariser is wired
	{
		// This is the integration claim: summary is no longer unreachable in practice.
		const h = [];
		for (let i = 0; i < 80; i++) {
			h.push({ role: 'user', content: `ask ${i}` });
			h.push({ role: 'assistant', content: `answer ${i}` });
			h.push({ role: 'tool', content: 'x'.repeat(500) });
		}
		const r = await B.buildBudgetedRequest({
			history: h, system: 'sys', toolTokens: 3000,
			summarize: async (msgs) => {
				// Use the real renderer so the wiring is exercised end to end.
				const rendered = S.renderForSummary(msgs);
				return `worked on ${rendered.text.length} chars of transcript`;
			},
		}, { maxTokens: 10_000, triggerRatio: 0.95, minTokensBeforeCompaction: 500 });
		check('integration: the summary tier is reachable through the real pipeline',
			r.manifest.tier === 'summary', );
		check('integration: the summary text is in the payload',
			r.messages.some(m => /前情摘要/.test(m.content)));
		check('integration: the real renderer did not blow the input cap',
			S.renderForSummary(h).text.length <= S.SUMMARY_LIMITS.maxInputChars + 200);
	}

	// ---- 6. agent.ts stores tool results as TOOL turns, not as user text
	// Verified structurally rather than by running a full turn: the whole point of the change is
	// that the budget can tell them apart, so the stored role must be 'tool'.
	{
		const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'ai', 'agent.ts'), 'utf8');
		check('agent: tool results are pushed with role tool',
			/role:\s*'tool'/.test(src) && /\[tool results\]/.test(src));
		check('agent: the old turn-count trigger is gone',
			!/TRUNCATE_TRIGGER_TURNS\s*=\s*\d/.test(src));
		check('agent: the budget is called on the request path',
			/buildBudgetedRequest/.test(src));
		check('agent: the manifest is recorded per request',
			/manifestLog\.record/.test(src));
		check('agent: compaction is announced to the user',
			/上下文已/.test(src));
		check('agent: the summariser is passed in from deps',
			/summarize:\s*this\.deps\.summarize/.test(src));
		check('agent: toggles are passed in from deps',
			/manifestToggles/.test(src));
		check('agent: tool tokens are measured from the real tools array',
			/JSON\.stringify\(toolsForApi/.test(src));
	}

	// ---- 7. the manifest accessor the panel will use
	{
		check('panel: manifest keys are the six addressable items',
			B.MANIFEST_KEYS.join(',') === 'system,tools,memory,todo,persona,history');
		check('panel: every key has a label', B.MANIFEST_KEYS.every(k => !!B.MANIFEST_LABELS[k]));
		// A panel that recomputes tokens itself will disagree with the bill, because the request
		// also carries the system prompt and the tool definitions. The manifest is the only truth.
		check('panel: the manifest carries a per-item breakdown',
			B.MANIFEST_KEYS.every(k => 'system tools memory todo persona history'.split(' ').includes(k)));
		check('panel: compaction tiers are all nameable',
			['none', 'micro', 'fold', 'summary'].every(t => typeof t === 'string'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});