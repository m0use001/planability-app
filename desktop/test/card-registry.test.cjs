/* Card registry tests — the card type list must be declared in exactly one place.
 *
 * WHY SOURCE-LEVEL ASSERTIONS. The failure this file exists to prevent is not a runtime bug; it
 * is two copies of a list quietly disagreeing. `(plan|qa|schedule|checklist)` used to be typed out
 * three times in cards.ts plus once in the SYSTEM_PROMPT, and nothing would complain if someone
 * added a card to the parser and forgot the prompt — the model would then be told to emit a card
 * the renderer does not recognise, or never told about one it can already parse. No runtime
 * assertion can see that, because both copies parse fine on their own. So section 7 reads the
 * source of cards.ts and agent.ts and fails if a card list appears in either.
 *
 * Run: npm run test:cardregistry
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-cardreg.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ROOT = path.join(__dirname, '..');
const ai = path.join(ROOT, '.test', 'src', 'main', 'ai');
const shared = path.join(ROOT, '.test', 'shared');
const reg = require(path.join(ai, 'card-registry.js'));
const cat = require(path.join(shared, 'cards.js'));
const { CardStreamTransformer } = require(path.join(ai, 'cards.js'));

// ---- 1. the catalogue is well formed, and every kind has a validator ----
{
	// Identity, not equality: the main-side registry must RE-EXPORT the catalogue, not rebuild it.
	// A deep-equal assertion would pass against a copy, and a copy is the entire failure mode here.
	check('registry: the main registry re-exports the shared catalogue, not a copy',
		reg.CARD_SPECS === cat.CARD_SPECS && reg.cardSpec('plan') === cat.cardSpec('plan'));
	check('registry: the four original cards are all still declared',
		['plan', 'qa', 'schedule', 'checklist'].every(k => cat.CARD_KINDS.includes(k)));
	check('registry: no duplicate kinds', new Set(cat.CARD_KINDS).size === cat.CARD_KINDS.length);
	for (const s of reg.CARD_SPECS) {
		check(`registry: ${s.kind} has a prompt fragment`, typeof s.prompt === 'string' && s.prompt.length > 4);
		check(`registry: ${s.kind} has a positive integer version`, Number.isInteger(s.version) && s.version >= 1);
	}
	// THE assertion that makes the shared/main split safe: a kind added to the catalogue without a
	// validator here would be a card the model is told to emit and nothing checks, which is exactly
	// how `list` could have shipped broken.
	const uncovered = reg.assertEveryKindHasAValidator();
	check('registry: EVERY catalogue kind has a validator (this is what stops the halves drifting)',
		uncovered.length === 0);
	for (const k of uncovered) { out.push(`      !! no validator for ${k}`); }
	check('registry: cardSpec() resolves a known kind', reg.cardSpec('plan')?.kind === 'plan');
	check('registry: cardSpec() returns undefined for an unknown kind', reg.cardSpec('nope') === undefined);
}

// ---- 2. validators accept the shapes the old renderers assumed ----
{
	const good = [
		['plan', { title: 't', goal: 'g', steps: [{ name: 'a', detail: 'd' }, 'plain'], note: 'n' }],
		['plan', { steps: [] }],
		['qa', { questions: [{ q: 'q?', options: ['a', 'b'] }] }],
		['schedule', { items: [{ title: 'x', start: '09:00', end: '10:00', location: 'l' }] }],
		['checklist', { title: 'c', items: ['a', 'b'] }],
	];
	for (const [kind, body] of good) {
		check(`validate: ${kind} accepts ${JSON.stringify(body).slice(0, 40)}`, reg.validateCard(kind, body) === null);
	}
}

// ---- 3. validators reject wrong TYPES (today these render as garbage, silently) ----
{
	const bad = [
		['plan', { steps: 'not an array' }, 'steps must be an array'],
		['plan', { steps: [42] }, 'step item must be string or object'],
		['qa', { questions: ['plain string'] }, 'question must be object'],
		['qa', { questions: [{ q: 5 }] }, 'q must be string'],
		['qa', { questions: [{ q: 'x', options: 'a,b' }] }, 'options must be array'],
		['schedule', { items: ['plain'] }, 'item must be object'],
		['schedule', { items: [{ title: 7 }] }, 'title must be string'],
		['checklist', { items: [{ text: 'a' }] }, 'items must be strings'],
		['plan', 'a bare string', 'body must be an object'],
		['plan', ['an', 'array'], 'body must be an object'],
	];
	for (const [kind, body, why] of bad) {
		const err = reg.validateCard(kind, body);
		check(`validate: ${kind} rejects ${why}`, typeof err === 'string' && err.length > 0);
	}
}

// ---- 4. NO regression: an absent optional field still passes (it renders empty, as before) ----
{
	check('no-regression: plan without steps still accepted (empty card, as today)',
		reg.validateCard('plan', { title: 'just a title' }) === null);
	check('no-regression: checklist with no items still accepted',
		reg.validateCard('checklist', {}) === null);
	check('no-regression: qa with no questions still accepted',
		reg.validateCard('qa', {}) === null);
	check('validate: an unknown kind is not an error (the caller decides)',
		reg.validateCard('sometypenobodydefined', { anything: 1 }) === null);
}

// ---- 5. the fence regexes are derived from the list ----
{
	const open = cat.buildFenceOpenRe();
	for (const k of cat.CARD_KINDS) {
		check(`fence: recognises an opening ${k} fence`, open.test('```' + k + '\n{"a":1}'));
		check(`fence: recognises a ${k} fence with trailing spaces`,
			open.test('```' + k + '  \n{"a":1}'));
	}
	check('fence: does NOT recognise an unknown kind (no documented-but-unparseable card)',
		!open.test('```calculator\n{}'));
	check('fence: does not match a kind in the middle of a word',
		!open.test('```planning\n{}'));
	check('mention: recognises ```kind with no newline yet', cat.buildFenceMentionRe().test('```plan'));

	// The catalogue really is the source: a spec added at runtime must show up in a FRESHLY BUILT
	// regex AND must then be reported as missing a validator. Both halves follow, or the split lies.
//
// Freshly built, deliberately: the prebuilt CARD_FENCE_OPEN constants are snapshots taken at module
	// load, so they cannot follow a later mutation — and asserting against a snapshot would have
	// passed here while proving nothing. Which is the bug the `alt()` comment warns about, arriving
	// from the other direction.
	const before = cat.CARD_SPECS.length;
	cat.CARD_SPECS.push({ kind: 'zzprobe', version: 1, prompt: 'probe' });
	check('registry: adding a card makes it parseable without touching the parser',
		cat.buildFenceOpenRe().test('```zzprobe\n{}'));
	check('registry: and the renderer-facing closed-fence regex follows too',
		cat.buildFenceBlockRe().test('```zzprobe\n{}\n```'));
	check('registry: a card with no validator is now REPORTED as one (the drift alarm works)',
		reg.assertEveryKindHasAValidator().includes('zzprobe'));
	cat.CARD_SPECS.pop();
	check('registry: removing it takes it back out', !cat.buildFenceOpenRe().test('```zzprobe\n{}'));
	check('registry: probe cleanup left the list as it was', cat.CARD_SPECS.length === before);
}

// ---- 6. the prompt paragraph is derived from the same list ----
{
	for (const k of cat.CARD_KINDS) {
		check(`prompt: teaches the model about ${k}`, cat.CARD_PROMPT_SECTION.includes(k));
	}
	for (const s of cat.CARD_SPECS) {
		check(`prompt: includes ${s.kind}'s field spec verbatim`, cat.CARD_PROMPT_SECTION.includes(s.prompt));
	}
	check('prompt: names the language slots explicitly',
		cat.CARD_PROMPT_SECTION.includes(cat.CARD_KINDS.join('|')));
}

// ---- 7. SOURCE-LEVEL: no second copy of the list may exist ----
{
	const cardsSrc = fs.readFileSync(path.join(ROOT, 'src/main/ai/cards.ts'), 'utf8');
	const agentSrc = fs.readFileSync(path.join(ROOT, 'src/main/ai/agent.ts'), 'utf8');
	const catSrc = fs.readFileSync(path.join(ROOT, 'shared/cards.ts'), 'utf8');

	check('source: cards.ts imports the shared catalogue', cardsSrc.includes("shared/cards"));
	check('source: cards.ts contains NO hand-written card list', !/plan\|qa\|schedule\|checklist/.test(cardsSrc));
	check('source: cards.ts contains no card kind spelled out in a regex',
		!/```\(/.test(cardsSrc) && !/\((plan|qa)\|/.test(cardsSrc));
	check('source: agent.ts imports the prompt section', agentSrc.includes('CARD_PROMPT_SECTION'));
	check('source: agent.ts contains NO hand-written card list',
		!/plan\|qa\|schedule\|checklist/.test(agentSrc));
	check('source: agent.ts does not spell the slots out again', !/语言槽为/.test(agentSrc));
	// Asserts it derives from CARD_SPECS, and specifically NOT from CARD_KINDS: CARD_KINDS is a
	// load-time snapshot, and a builder reading a snapshot cannot follow the catalogue. That was
	// wrong twice, so the assertion names the right source rather than merely "some source".
	check('source: the catalogue derives its regexes from CARD_SPECS (not the CARD_KINDS snapshot)',
		/const alt = \(\) => CARD_SPECS\.map/.test(catSrc) && !/CARD_KINDS\.map\(esc\)/.test(catSrc));

	// Checking for the absence of a DEFINITION, not of the word: the header comment deliberately
	// names renderCards to explain why it was deleted, and that explanation is worth keeping.
	// "The name does not appear" is a different claim from "the thing does not exist".
	check('source: the deleted v1 renderer has no definition left',
		!/export function renderCards/.test(cardsSrc) && !/function render(Plan|Qa|Schedule|Checklist)/.test(cardsSrc));
	check('source: and none of its four private renderers either',
		!/render(Plan|Qa|Schedule|Checklist)\s*\(/.test(cardsSrc));

	// The RENDERER, which is where the fourth copy actually shipped: ChatCards.tsx carried its own
	// /```(plan|qa|schedule|checklist)/, so `list` was parseable by main and invisible in the UI.
	//
	// It is asserted as a WHITELISTED KNOWN GAP, the same mechanism used for unwired channels. The
	// alternative — leaving it unasserted — is how the copy survived this long; the alternative of
	// failing the build today would mean the main-side half of a contract cannot land until the
	// renderer half does, which is worse. So: the gap is named, it is asserted to still EXIST (so
	// that pc-a fixing it turns this red and has to remove the line, which is the point), and it
	// disappears only when the copy is gone.
	const rendererFile = path.join(ROOT, 'src/renderer/src/pages/ChatCards.tsx');
	const rSrc = fs.readFileSync(rendererFile, 'utf8');
	const rendererHasOwnCopy = /```\(plan\|qa\|schedule\|checklist\)/.test(rSrc);

	// ChatCards.tsx now imports CARD_FENCE_BLOCK and carries no copy of its own, so this list is
	// EMPTY. Keeping an empty but EXPLICIT list is the point: the day someone hand-writes another
	// one it lands here and the red above is unavoidable, instead of shipping silently the way
	// the fourth copy did.
	const RENDERER_KNOWN_GAPS = {};

	const gapKeys = Object.keys(RENDERER_KNOWN_GAPS);
	// Present = a copy exists somewhere. With the whitelist empty these two directions are the
	// whole guard: an unlisted copy fails below, and a listed-but-gone entry fails as stale, so
	// whoever removes the copy must also remove the line - which is what happened here.
	const gapPresent = rendererHasOwnCopy ? ['ChatCards.tsx'] : [];
	const gapStale = gapKeys.filter(f => !gapPresent.includes(f));
	const gapUnlisted = gapPresent.filter(f => !gapKeys.includes(f));
	check('source: renderer copies of the card list are all on the known-gap list '
		+ `(${gapPresent.length} present / ${gapKeys.length} listed)`,
		gapPresent.every(f => gapKeys.includes(f)));
	check('source: no STALE gap entry — pc-a removing the copy must remove this line too',
		gapStale.length === 0);
	check('source: no UNLISTED renderer copy (a new fourth copy fails here, not in production)',
		gapUnlisted.length === 0);
	for (const f of gapStale) {
		out.push(`      ?? gap listed but gone: ${f} — delete its RENDERER_KNOWN_GAPS entry`);
	}
	// The mirror of the "assume nothing" version: now that the whitelist is empty, the assertion
	// is that the copy is really GONE - not that the grep is merely not finding it. A grep that
	// silently stopped matching would otherwise look exactly like a fix.
	check('source: the renderer really imports the shared catalogue instead of writing its own',
		rSrc.includes('shared/cards') && rSrc.includes('CARD_FENCE_BLOCK'));
	check('source: and carries no copy of the card list at all',
		!rendererHasOwnCopy);
}

// ---- 8. the streamer still passes malformed cards through, but says so ----
{
	const noisy = [];
	const realError = console.error;
	console.error = (...a) => noisy.push(a.join(' '));
	let emitted;
	try {
		const t = new CardStreamTransformer();
		emitted = t.feed('前文\n```plan\n{"steps": "wrong"}\n```\n后文').join('');
	} finally {
		console.error = realError;
	}
	check('streamer: a malformed card is still emitted verbatim (renderer decides)',
		emitted.includes('```plan') && emitted.includes('{"steps": "wrong"}'));
	check('streamer: and the failure is reported instead of being silent',
		noisy.some(s => s.includes('plan') && s.includes('steps')));
}
{
	const noisy = [];
	const realError = console.error;
	console.error = (...a) => noisy.push(a.join(' '));
	let emitted;
	try {
		const t = new CardStreamTransformer();
		emitted = t.feed('```plan\nnot json at all\n```').join('');
	} finally {
		console.error = realError;
	}
	check('streamer: non-JSON body still passes through', emitted.includes('not json at all'));
	check('streamer: non-JSON is reported too', noisy.some(s => s.includes('JSON')));
}
{
	// A well-formed card must NOT produce noise — otherwise the log becomes useless.
	const noisy = [];
	const realError = console.error;
	console.error = (...a) => noisy.push(a.join(' '));
	try {
		const t = new CardStreamTransformer();
		t.feed('```checklist\n{"items":["a","b"]}\n```');
	} finally {
		console.error = realError;
	}
	check('streamer: a valid card logs nothing', noisy.length === 0);
}

out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
flush();
process.exit(failed === 0 ? 0 : 1);