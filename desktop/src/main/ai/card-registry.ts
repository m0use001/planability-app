/* Card validation — the BEHAVIOURAL half of the catalogue.
 *
 * The catalogue itself (which cards exist, their version, the prompt that teaches the model) lives
 * in shared/cards.ts, because the RENDERER has to agree about the language slots and tsconfig.web
 * cannot reach into src/main. This file adds the part that needs main-side code: a structural
 * validator per kind.
 *
 * THE SPLIT IS THE POINT. You cannot add a card to the catalogue without the renderer's parser
 * picking it up, and you cannot add one here without giving it a validator, because
 * `assertEveryKindHasAValidator` fails on a kind that has no entry below. Two lists that can drift
 * is the problem being solved; a list plus a table keyed by that list is not two lists.
 */
import { CARD_SPECS, cardSpec } from '../../../shared/cards';
import { normalizeListQuery } from './list-card';

/** Returns an error string, or null when the card is usable. */
export type CardValidator = (raw: unknown) => string | null;

const isObj = (v: unknown): v is Record<string, unknown> =>
	typeof v === 'object' && v !== null && !Array.isArray(v);

/** `field` must be absent, or an array. */
function arr(v: unknown, field: string): string | null {
	if (!isObj(v)) { return '卡片必须是一个 JSON 对象'; }
	const a = v[field];
	if (a === undefined || a === null) { return null; }
	if (!Array.isArray(a)) { return `${field} 必须是数组`; }
	return null;
}

/**
 * Validates TYPES only, never presence. An absent field renders as it always has (an empty card);
 * tightening that would be a behaviour change disguised as a validator. What this does reject is a
 * field of the wrong shape — `items: "buy milk"` — which used to render as garbage markdown with no
 * sign that anything was wrong.
 */
const VALIDATORS: Record<string, CardValidator> = {
	plan(raw) {
		const bad = arr(raw, 'steps');
		if (bad) { return bad; }
		for (const s of ((raw as Record<string, unknown>).steps as unknown[]) ?? []) {
			if (typeof s !== 'string' && !(isObj(s) && (s.name === undefined || typeof s.name === 'string'))) {
				return 'plan.steps 的每一项必须是字符串或含 name 的对象';
			}
		}
		return null;
	},
	qa(raw) {
		const bad = arr(raw, 'questions');
		if (bad) { return bad; }
		for (const q of ((raw as Record<string, unknown>).questions as unknown[]) ?? []) {
			if (!isObj(q)) { return 'qa.questions 的每一项必须是对象'; }
			if (q.q !== undefined && typeof q.q !== 'string') { return 'qa.questions[].q 必须是字符串'; }
			if (q.options !== undefined && !Array.isArray(q.options)) { return 'qa.questions[].options 必须是数组'; }
		}
		return null;
	},
	schedule(raw) {
		const bad = arr(raw, 'items');
		if (bad) { return bad; }
		for (const it of ((raw as Record<string, unknown>).items as unknown[]) ?? []) {
			if (!isObj(it)) { return 'schedule.items 的每一项必须是对象'; }
			if (it.title !== undefined && typeof it.title !== 'string') { return 'schedule.items[].title 必须是字符串'; }
			if (it.start !== undefined && typeof it.start !== 'string') { return 'schedule.items[].start 必须是字符串'; }
		}
		return null;
	},
	// Delegates rather than duplicating: the query rules live with the code that runs them, so a new
	// filter cannot be taught to the model without also being honoured here.
	list(raw) {
		const r = normalizeListQuery(raw);
		return r.ok ? null : r.error;
	},
	checklist(raw) {
		const bad = arr(raw, 'items');
		if (bad) { return bad; }
		for (const it of ((raw as Record<string, unknown>).items as unknown[]) ?? []) {
			if (typeof it !== 'string') { return 'checklist.items 的每一项必须是字符串'; }
		}
		return null;
	},
};

/** Every catalogue kind has a validator. This is the assertion that makes the split safe. */
export function assertEveryKindHasAValidator(): string[] {
	return CARD_SPECS.map(s => s.kind).filter(k => typeof VALIDATORS[k] !== 'function');
}

export function validateCard(kind: string, raw: unknown): string | null {
	return VALIDATORS[kind]?.(raw) ?? null;
}

export { CARD_SPECS, cardSpec };