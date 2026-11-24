/* Card repair tests — a malformed card gets ONE chance to be fixed, and the model is told why.
 *
 * THE FAILURE THIS EXISTS TO STOP. `catch { /* fall through to code block *\/ }` meant a card the
 * model wrote badly rendered as a JSON code block, logged nothing, and told the model nothing — so
 * the next turn wrote the same broken card, confidently, forever. The user sees a code block where
 * a plan should be.
 *
 * The properties pinned here are the ones that make the repair safe rather than merely eager:
 *   - a GOOD card costs nothing (findBrokenCards returns [] — so this is safe to call every turn);
 *   - the model is shown ITS OWN body plus the reason, because "your card is invalid" without the
 *     body is not actionable;
 *   - the reply is ONE user turn asking for the card only, so the user's prose is not re-emitted;
 *   - a repair that comes back broken is NOT repaired again (one round, not a loop);
 *   - an unclosed fence is reported separately, because it is a different failure (truncation) with
 *     a different cause.
 *
 * Run: npm run test:cardrepair
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-cardrepair.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { findBrokenCards, buildRepairTurn, hasUnclosedCard, MAX_REPAIR_QUOTE } = require(path.join(base, 'card-repair.js'));
const reg = require(path.join(base, 'card-registry.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'cards.js'));

// ---- 1. a good turn costs nothing ----
{
	check('good: a valid plan card is not reported', findBrokenCards('好的\n```plan\n{"steps":["a"]}\n```').length === 0);
	check('good: plain prose with no fence is not reported', findBrokenCards('今天天气不错。').length === 0);
	check('good: an inline code span is not a card', findBrokenCards('用 `npm test` 跑测试').length === 0);
	check('good: a fenced block that is NOT a card language is not reported',
		findBrokenCards('```js\nconst a = 1;\n```').length === 0);
	check('good: several valid cards in one turn are all fine',
		findBrokenCards('```plan\n{"steps":["a"]}\n```\n中间文字\n```checklist\n{"items":["x"]}\n```').length === 0);
	check('good: buildRepairTurn([]) returns nothing at all', buildRepairTurn([]).length === 0);
	check('good: the new list card is valid as written and therefore free',
		findBrokenCards('```list\n{"collection":"todos","where":{"status":"open"}}\n```').length === 0);
}

// ---- 2. malformed cards ARE reported, with the reason and the body ----
{
	const badJson = findBrokenCards('```plan\n{"steps": [oops}\n```');
	check('bad: non-JSON body is reported', badJson.length === 1);
	check('bad: it names the language slot', badJson[0].kind === 'plan');
	check('bad: it quotes the model back its own body', badJson[0].body.includes('oops'));
	check('bad: the reason mentions JSON', /JSON/.test(badJson[0].reason));

	const badShape = findBrokenCards('```list\n{"collection":"tasks"}\n```');
	check('bad: a list card with an unknown collection is reported', badShape.length === 1);
	check('bad: the reason is the validator\'s, not a generic message',
		/collection/.test(badShape[0].reason));

	const badType = findBrokenCards('```checklist\n{"items":[{"text":"a"}]}\n```');
	check('bad: wrong item type is reported', badType.length === 1);

	const both = findBrokenCards('```plan\n{oops}\n```\n文字\n```list\n{"collection":"nope"}\n```');
	check('bad: two broken cards are BOTH reported', both.length === 2);
	check('bad: and each carries its own kind',
		both.map(b => b.kind).sort().join(',') === 'list,plan');
}

// ---- 3. a truncated body is clipped, so a huge dump cannot become context ----
{
	const huge = 'x'.repeat(MAX_REPAIR_QUOTE * 3);
	const r = findBrokenCards('```plan\n' + huge + '\n```');
	check('clip: a huge broken body is truncated', r[0].body.length <= MAX_REPAIR_QUOTE + 12);
	check('clip: and the truncation is marked, not silent', /截断/.test(r[0].body));
}

// ---- 4. the repair turn says what to fix, shows the body, and asks for the card only ----
{
	const r = findBrokenCards('```list\n{"collection":"tasks"}\n```');
	const msgs = buildRepairTurn(r);
	check('repair: exactly one user turn', msgs.length === 1 && msgs[0].role === 'user');
	const c = msgs[0].content;
	check('repair: it names the offending slot', c.includes('`list`'));
	check('repair: it includes the reason', /collection/.test(c));
	check('repair: it quotes the body back', c.includes('tasks'));
	check('repair: it asks for the card and nothing else', /不要解释/.test(c) && /卡片本身/.test(c));
	check('repair: it points at `list` for real data (the failure this usually is)',
		/只写查询而不是把内容编进去/.test(c));
}

// ---- 5. one round, not a loop ----
{
	// Feeding a STILL-broken reply back must not schedule another round. There is no second round
	// in the API at all, so the property is that nothing here can produce one.
	check('one round: the API only ever returns one message', buildRepairTurn([{ kind: 'plan', body: '{}', reason: 'x' }]).length === 1);
	// Scanned from CODE, not comments. Both words appear in the file's header explaining why there
	// is no retry loop — reading the prose as if it were the implementation is exactly the mistake
	// this assertion exists to prevent, so the doc comment is stripped first.
	{
		const code = require('node:fs').readFileSync(
			path.join(__dirname, '..', 'src', 'main', 'ai', 'card-repair.ts'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
		check('one round: no retry budget or attempt counter exists in the code',
			!/maxAttempts|maxRounds|retry|attempt/i.test(code));
		const fn = code.split('export function buildRepairTurn')[1] || '';
		check('one round: buildRepairTurn contains no loop at all',
			!/\b(for|while|do)\s*\(/.test(fn));
	}
}

// ---- 6. an unclosed fence is a DIFFERENT failure and is reported separately ----
{
	check('unclosed: a dangling opening fence is detected', hasUnclosedCard('```plan\n{"steps":["a"]}') === true);
	check('unclosed: a complete fence is not', hasUnclosedCard('```plan\n{"steps":["a"]}\n```') === false);
	check('unclosed: text with no fence at all is not', hasUnclosedCard('普通回答') === false);
	check('unclosed: an inline backtick span is not a dangling card', hasUnclosedCard('用 `npm` 跑') === false);
	// Truncation produces NO closed fence, so findBrokenCards finds nothing — which is exactly why
	// the unclosed case needs its own signal rather than relying on the repair path.
	check('unclosed: and findBrokenCards cannot see it (hence the separate check)',
		findBrokenCards('```plan\n{"steps":["a"]}').length === 0);
}

// ---- 7. the repair path is consistent with the registry it validates against ----
{
	check('registry: every declared kind can appear in a repair report',
		cat.CARD_KINDS.every(k => findBrokenCards('```' + k + '\nnot json\n```')[0]?.kind === k));
	check('registry: repair and streamer use the same validator',
		reg.validateCard('plan', { steps: 5 }) === findBrokenCards('```plan\n{"steps":5}\n```')[0].reason);
}

out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
flush();
process.exit(failed === 0 ? 0 : 1);