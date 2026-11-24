/* M17 loop-protection stack (plan §3 M17 item 4): signature ring, soft checkpoints,
 * circuit breaker and head+tail truncation.
 *
 * Guards against the three ways a tool loop goes wrong:
 *  1. It repeats itself      -> signature ring: the same (tool,args) seen N times trips.
 *  2. It wanders too far     -> soft checkpoints: history can be rolled back to a known point.
 *  3. It burns the context   -> head+tail truncation keeps the first turns and the recent
 *                               tail, and elides the noisy middle.
 *
 * Pure functions + small stateful guards; no IO, so it is directly unit-testable. */

/**
 * Minimal structural view of an OpenAI-style message. Declared locally rather than
 * imported from ../agent so this module stays dependency-free and directly testable —
 * ChatAgent's OpenAIMessage is structurally assignable to it.
 */
export interface GuardMessage {
	/**
	 * 'tool' included because the OpenAI chat format really does carry tool-result turns under
	 * that role, and both the loop guard and the context budget need to tell them apart from
	 * user instructions. Clipping a tool result is cheap; clipping an instruction is not.
	 */
	role: 'system' | 'user' | 'assistant' | 'tool';
	content: string;
	tool_calls?: { id: string; type: 'function'; function: { name: string; arguments: string } }[];
}

/** Circuit-breaker thresholds. */
export const LOOP_LIMITS = {
	/** how many times one signature may repeat before tripping */
	maxRepeat: 3,
	/** window size for the "everything is one tool" rule */
	ringSize: 8,
	/** a single round producing more calls than this is pathological */
	maxCallsPerRound: 12,
} as const;

/**
 * Stable signature for one tool call. Normalises whitespace and key order so cosmetic
 * jitter (`{"a":1,"b":2}` vs `{ "b":2 , "a":1 }`) does not read as progress.
 */
export function toolSignature(name: string, argsJson: string): string {
	let norm: string;
	try {
		const v = JSON.parse(argsJson || '{}') as Record<string, unknown>;
		norm = JSON.stringify(v, Object.keys(v).sort());
	} catch {
		norm = String(argsJson).replace(/\s+/g, ' ').trim();
	}
	return `${name}::${norm}`;
}

/**
 * Ring of recent signatures with a latching circuit breaker. Three independent rules:
 *  - the same signature repeats more than maxRepeat times
 *  - one round emits more than maxCallsPerRound calls
 *  - a full ring window spent on a single tool (spinning rather than progressing)
 */
export class LoopGuard {
	private ring: string[] = [];
	private readonly counts = new Map<string, number>();
	private roundCalls = 0;
	private trippedFlag = false;
	private reasonText = '';

	get isTripped(): boolean { return this.trippedFlag; }
	get tripReason(): string { return this.reasonText; }
	/** Recent signatures, oldest first — exposed for the audit trail and tests. */
	get signatures(): string[] { return [...this.ring]; }

	reset(): void {
		this.ring = [];
		this.counts.clear();
		this.roundCalls = 0;
		this.trippedFlag = false;
		this.reasonText = '';
	}

	/** Call at the start of each provider round so the per-round cap is per-round. */
	beginRound(): void { this.roundCalls = 0; }

	/** Record one tool call; returns true when this call trips the breaker. */
	observe(name: string, argsJson: string): boolean {
		if (this.trippedFlag) { return true; }
		const sig = toolSignature(name, argsJson);
		this.ring.push(sig);
		if (this.ring.length > LOOP_LIMITS.ringSize) { this.ring.shift(); }

		const n = (this.counts.get(sig) ?? 0) + 1;
		this.counts.set(sig, n);
		this.roundCalls++;

		if (n > LOOP_LIMITS.maxRepeat) {
			return this.trip(`工具 ${name} 以完全相同的参数调用 ${n} 次，判定为死循环`);
		}
		if (this.roundCalls > LOOP_LIMITS.maxCallsPerRound) {
			return this.trip(`单轮工具调用数超过 ${LOOP_LIMITS.maxCallsPerRound} 次，判定为失控循环`);
		}
		if (this.ring.length >= LOOP_LIMITS.ringSize
			&& this.ring.every(s => s.startsWith(name + '::'))) {
			return this.trip(`最近 ${LOOP_LIMITS.ringSize} 次调用全部落在同一工具 ${name} 上，疑似空转`);
		}
		return false;
	}

	private trip(reason: string): boolean {
		this.trippedFlag = true;
		this.reasonText = reason;
		return true;
	}
}

/** A restorable point in a conversation. */
export interface Checkpoint {
	seq: number;
	label: string;
	history: GuardMessage[];
	at: number;
}

/**
 * Soft checkpoints: drop one before a risky turn and rewind to it instead of discarding the
 * whole session. Bounded so a long session cannot grow without limit.
 */
export class CheckpointStack {
	private stack: Checkpoint[] = [];
	constructor(private readonly limit = 10) { }

	push(label: string, history: GuardMessage[]): Checkpoint {
		const cp: Checkpoint = { seq: this.stack.length, label, history: history.map(m => ({ ...m })), at: Date.now() };
		this.stack.push(cp);
		if (this.stack.length > this.limit) { this.stack.shift(); }
		return cp;
	}

	list(): { seq: number; label: string; at: number; turns: number }[] {
		return this.stack.map(c => ({ seq: c.seq, label: c.label, at: c.at, turns: c.history.length }));
	}

	/** Rewind to the newest checkpoint at or before seq; returns a copy of its history. */
	rewindTo(seq: number): GuardMessage[] | null {
		const cp = this.stack.filter(c => c.seq <= seq).pop();
		if (!cp) { return null; }
		this.stack = this.stack.filter(c => c.seq <= cp.seq);
		return cp.history.map(m => ({ ...m }));
	}

	get size(): number { return this.stack.length; }
	clear(): void { this.stack = []; }
}

/** Stands in for the elided middle turns. */
export const ELISION_MARKER = '（中间若干轮工具结果已折叠）';

export interface TruncateOptions {
	/** keep this many oldest turns (usually the original ask) */
	head?: number;
	/** keep this many newest turns (current state) */
	tail?: number;
	/** any single turn longer than this is clipped */
	maxCharsPerTurn?: number;
}

/**
 * Snap a head/middle/tail split so neither cut can land INSIDE an
 * `assistant(tool_calls) → tool*` run.
 *
 * Every producer of a subsequence has to answer this same question, and getting it wrong is not
 * cosmetic: an OpenAI-style server rejects the whole request when a tool turn is separated from
 * the assistant message that issued it — MiMo answers 400 "`tool_call_id` is not set" (pc-a's
 * 01:29 block) even when every id is present, because the half that would have given the id
 * meaning is gone. The turn then dies with nothing visible but that error, so it reads as "the
 * model went quiet" rather than as a malformed payload.
 *
 * Tool results are pushed immediately after their assistant message and run consecutively, so
 * only two things can split a round: a cut that stops one message BEFORE a tool response (the
 * assistant survives, its answers are dropped), and a cut that starts one message ON a tool
 * response (the answers survive, their assistant is dropped). Both are repaired by MOVING the
 * cut rather than by deleting anything — shifting a boundary can only keep more context, while
 * discarding the other half is precisely how the request breaks.
 *
 * The right cut is resolved first because it may need to walk back onto the issuing assistant,
 * which can carry it past the left cut. The left cut is then clamped to it, and that position
 * is guaranteed not to sit on a tool response (the left scan advances exactly until it does
 * not, or until the end), so moving the tail there cannot re-introduce the orphan the first
 * pass just removed.
 */
export function snapToolSafeCuts(
	messages: GuardMessage[],
	headCount: number,
	tailCount: number,
): { headEnd: number; tailStart: number } {
	const n = messages.length;
	const isTool = (i: number) => i >= 0 && i < n && messages[i].role === 'tool';

	// Tail: slide back to the issuing assistant so the most recent round stays whole — recent
	// tool results are exactly what a tail is kept for.
	let tailStart = Math.max(0, n - tailCount);
	while (tailStart > 0 && isTool(tailStart)) { tailStart--; }

	// Head: slide forward over the run instead of pulling a message out of the head, whose job
	// is to hold the original ask verbatim.
	let headEnd = Math.min(Math.max(headCount, 0), n);
	while (headEnd < n && isTool(headEnd)) { headEnd++; }

	// Short history: the two cuts can cross, and overlapping head/tail would send the same
	// message twice. Head wins, and its stop position is not a tool response by construction.
	if (headEnd > tailStart) { tailStart = headEnd; }
	return { headEnd, tailStart };
}

/**
 * Head+tail truncation: keep the opening and the recent tail, replace the noisy middle with
 * one marker. Tool-result turns are the bulk, so this is where context is saved.
 */
export function truncateHeadTail(
	messages: GuardMessage[],
	opts: TruncateOptions = {},
): { messages: GuardMessage[]; elided: number; savedChars: number } {
	const head = Math.max(0, opts.head ?? 2);
	const tail = Math.max(1, opts.tail ?? 6);
	const maxChars = opts.maxCharsPerTurn ?? 4000;

	let saved = 0;
	const clipped = messages.map(m => {
		if (typeof m.content !== 'string' || m.content.length <= maxChars) { return m; }
		saved += m.content.length - maxChars;
		return { ...m, content: m.content.slice(0, maxChars) + '…（已截断）' };
	});

	const { headEnd, tailStart } = snapToolSafeCuts(clipped, head, tail);
	if (tailStart <= headEnd) {
		return { messages: clipped, elided: 0, savedChars: saved };
	}
	const elided = tailStart - headEnd;
	for (const m of clipped.slice(headEnd, tailStart)) {
		saved += typeof m.content === 'string' ? m.content.length : 0;
	}
	const marker: GuardMessage = { role: 'user', content: ELISION_MARKER };
	return {
		messages: [...clipped.slice(0, headEnd), marker, ...clipped.slice(tailStart)],
		elided,
		savedChars: saved,
	};
}

/** Rough token estimate (~4 chars/token for mixed CJK + latin). */
export function estimateTokens(messages: GuardMessage[]): number {
	let chars = 0;
	for (const m of messages) {
		chars += typeof m.content === 'string' ? m.content.length : 0;
		if (m.tool_calls) { chars += JSON.stringify(m.tool_calls).length; }
	}
	return Math.ceil(chars / 4);
}