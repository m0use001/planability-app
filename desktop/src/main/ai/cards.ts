/* Streaming plumbing for component cards (fence protocol, phone AI workbench parity).
 *
 * WHAT IS HERE AND WHY SO LITTLE. The card types themselves live in `card-registry.ts`, which is
 * the single place they are declared: the fence regexes below are derived from it, and so is the
 * SYSTEM_PROMPT paragraph that teaches the model to emit them. Nothing in this file may name a
 * card type by hand.
 *
 * WHAT WAS DELETED HERE, and why it is worth recording. This file used to also contain
 * `renderCards()` — a v1 renderer that turned card JSON into "interactive-looking markdown" on
 * the main-process side, with `renderPlan`/`renderQa`/`renderSchedule`/`renderChecklist` and four
 * private interfaces, ~70 lines. It had ZERO callers: desktop went v2, where the fence is
 * streamed through verbatim and the RENDERER parses it into native interactive cards
 * (`ChatCards.tsx`). Two renderers for one protocol is a drift machine — the v1 copy could not
 * know about a card field the renderer already supported. Deleted rather than kept "just in
 * case"; git keeps it.
 *
 * The streamer buffers until a fence closes, then emits the raw block. The renderer owns parsing
 * and interaction. Both bugs below were fixed in the 10-03 acceptance round: the closing-fence
 * search must start AFTER the opening fence's newline, and the opening fence must not be
 * mistaken for the closer.
 */
import { CARD_FENCE_OPEN, CARD_FENCE_MENTION } from '../../../shared/cards';
import { validateCard } from './card-registry';

export class CardStreamTransformer {
	private inFence = false;
	private buffer = '';

	/**
	 * Report a closed card that cannot be used. This does NOT alter what is emitted — the
	 * renderer still gets the raw block and decides what to do — but it is what stops the failure
	 * from being silent. Before this, a malformed card degraded to a code block and the model was
	 * never told, so it wrote the same malformed card again next turn, forever.
	 */
	private reportIfInvalid(kind: string, body: string): void {
		let parsed: unknown;
		try {
			parsed = JSON.parse(body.trim());
		} catch (e) {
			console.error(`[cards] ${kind} 卡片正文不是合法 JSON，已按原文透传：${(e as Error)?.message ?? e}`);
			return;
		}
		const err = validateCard(kind, parsed);
		if (err) { console.error(`[cards] ${kind} 卡片结构有问题（${err}），已按原文透传`); }
	}

	feed(chunk: string): string[] {
		const out: string[] = [];
		this.buffer += chunk;
		while (true) {
			if (!this.inFence) {
				const m = this.buffer.match(CARD_FENCE_OPEN);
				if (!m || m.index === undefined) {
					// keep a small tail in case the opening fence splits across chunks
					const keep = Math.min(this.buffer.length, 40);
					out.push(this.buffer.slice(0, this.buffer.length - keep));
					this.buffer = this.buffer.slice(this.buffer.length - keep);
					if (this.buffer.includes('\n') && !CARD_FENCE_MENTION.test(this.buffer)) {
						const idx = this.buffer.lastIndexOf('\n');
						out.push(this.buffer.slice(0, idx));
						this.buffer = this.buffer.slice(idx);
					}
					return out;
				}
				out.push(this.buffer.slice(0, m.index));
				this.buffer = this.buffer.slice(m.index);
				this.inFence = true;
				continue;
			}
			const lineBreak = this.buffer.indexOf('\n');
			const searchFrom = lineBreak < 0 ? -1 : lineBreak + 1;
			const end = searchFrom < 0 ? -1 : this.buffer.indexOf('```', searchFrom);
			if (end < 0) { return out; }
			// The buffer always starts at the opening fence here, so its kind is re-readable and
			// the body is everything between the opening newline and the closer.
			const open = CARD_FENCE_OPEN.exec(this.buffer);
			if (open) { this.reportIfInvalid(open[1], this.buffer.slice(open[0].length, end)); }
			// emit the complete raw fence — renderer owns parsing/interaction
			out.push(this.buffer.slice(0, end + 3));
			this.buffer = this.buffer.slice(end + 3);
			this.inFence = false;
		}
	}

	flush(): string[] {
		if (!this.buffer) { return []; }
		const out = [this.buffer];
		this.buffer = '';
		this.inFence = false;
		return out;
	}
}