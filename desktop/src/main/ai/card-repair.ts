/* One repair round for a malformed card.
 *
 * THE FAILURE THIS EXISTS TO STOP. A card the model writes badly used to end at
 * `catch { /* fall through to code block *\/ }` — the renderer showed a JSON code block, nothing
 * was logged, and the model was never told. So on the NEXT turn it wrote the same malformed card,
 * confidently, forever. The user sees a code block where a plan should be, and the only trace is
 * in a log nobody reads.
 *
 * The fix is one round: when a closed fence fails to parse or validate, hand the model its own
 * broken body plus the reason and let it try once. One, not several — a card is not a hard task,
 * and a retry loop that keeps failing is worse than a visible code block because it burns the
 * user's tokens while showing them nothing.
 *
 * This is deliberately NOT built on fix-loop.ts, which looks like the natural home for it. That
 * module is a workflow-step verifier: it takes criteria, a verdict function and a whole step to
 * re-run, and its guard rails (precheck, backoff, attempt budget) are all about long-running work.
 * A card body is a few hundred characters and the repair is a single extra completion, so wiring
 * it through that machinery would be more code than the repair itself and would import a step
 * concept into a place that has no steps.
 *
 * Everything here is pure: `findBrokenCards` over already-emitted text, and `buildRepairTurn`
 * returning messages to append. The caller owns when to spend the extra completion.
 */
import type { OpenAIMessage } from './agent';
import { CARD_FENCE_OPEN, CARD_FENCE_MENTION } from '../../../shared/cards';
import { validateCard } from './card-registry';

export interface BrokenCard {
	kind: string;
	/** The model's own body, verbatim. Quoted back so it can see what it actually wrote. */
	body: string;
	/** Why it was rejected — the parser's message or the validator's. */
	reason: string;
}

/** Hard cap on how much of a broken body is quoted back. A model that emitted a megabyte of
 *  garbage should not get a megabyte echoed at it as context. */
export const MAX_REPAIR_QUOTE = 800;

const clip = (s: string, n = MAX_REPAIR_QUOTE) => (s.length > n ? s.slice(0, n) + '…（已截断）' : s);

/**
 * Find the closed card fences in a block of text that failed to parse or validate.
 *
 * `text` is the ASSEMBLED output of a round, not a live stream — by the time we can judge a card
 * its fence has closed. Returns [] when everything is fine, which is the overwhelmingly common
 * case and the reason this is cheap to call on every turn.
 */
export function findBrokenCards(text: string): BrokenCard[] {
	const out: BrokenCard[] = [];
	// A closed fence: opening (kind + newline), any body, then the closing backticks.
	const re = new RegExp(`${CARD_FENCE_OPEN.source}([\\s\\S]*?)\`\`\``, 'g');
	let m: RegExpExecArray | null;
	while ((m = re.exec(text))) {
		const kind = m[1];
		const body = m[2].trim();
		let parsed: unknown;
		try {
			parsed = JSON.parse(body);
		} catch (e) {
			out.push({ kind, body: clip(body), reason: `正文不是合法 JSON：${(e as Error)?.message ?? e}` });
			continue;
		}
		const err = validateCard(kind, parsed);
		if (err) { out.push({ kind, body: clip(body), reason: err }); }
	}
	return out;
}

/** True when the text mentions a card fence but contains no closed one. That means the model was
 *  cut off mid-card — worth telling it, because the fence it was writing will never be seen by the
 *  renderer and the user gets a dangling ``` in their answer. */
export function hasUnclosedCard(text: string): boolean {
	if (!CARD_FENCE_MENTION.test(text)) { return false; }
	const opens = (text.match(new RegExp(CARD_FENCE_OPEN.source, 'g')) ?? []).length;
	const closes = (text.match(/```/g) ?? []).length;
	return closes < opens * 2;
}

/**
 * Build the extra turn that gives the model one chance to fix its card.
 *
 * Returns [] when nothing is broken, so the caller can treat "no repair" and "repair ran" the same
 * way at the call site.
 */
export function buildRepairTurn(broken: BrokenCard[]): OpenAIMessage[] {
	if (!broken.length) { return []; }
	const detail = broken
		.map((b, i) => `${i + 1}. 语言槽 \`${b.kind}\`：${b.reason}\n   你写的正文：${clip(b.body, 300)}`)
		.join('\n');
	return [
		{
			role: 'user',
			content:
				`你上一条回复里的组件卡片无法解析：\n${detail}\n\n`
				+ `请只重新输出 ${broken.map(b => b.kind).join('、')} 这些卡片本身（围栏 + 合法 JSON），不要解释、不要重复正文。`
				+ `如果这个卡片需要展示用户已有的数据，用 \`list\` 语言槽只写查询而不是把内容编进去。`,
		},
	];
}