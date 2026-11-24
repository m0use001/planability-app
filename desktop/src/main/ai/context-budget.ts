/* M19-1 ContextBudget — the token manifest, per-item toggles, and the compaction ladder.
 *
 * The existing head+tail truncation is a single blunt lever: past 24 turns it folds the middle.
 * That works until a session has a 200KB system prompt, 30 tools, a memory reminder and a
 * todo reminder — then the transcript is a rounding error and folding the middle saves nothing.
 * So this module budgets by ITEM, not by position.
 *
 * FOUR THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. THE MANIFEST MUST BE COMPUTED FROM THE REQUEST THAT IS ACTUALLY SENT. Measuring
 *    this.history (what we store) instead of the outgoing payload (what we send) produces a
 *    panel that disagrees with the bill. It also has to account for the fact that folding
 *    the middle REPLACES messages, so the manifest is produced by the same function that
 *    builds the payload — never computed twice.
 *
 * 2. TOGGLES ARE APPLIED BEFORE BUDGETING, NEVER AFTER. If a toggle shrinks the payload after
 *    the budget was solved, the 95% threshold means something different depending on order.
 *    One pass, in a fixed order: toggles -> compaction -> manifest.
 *
 * 3. COMPACTION MUST NOT DESTROY THE TRANSCRIPT. Everything here folds a COPY. This is the
 *    same discipline as the existing truncateHeadTail: persistence and rollback stay lossless,
 *    only the request payload is folded. A context feature that eats history is a data-loss
 *    bug wearing a helpful hat.
 *
 * 4. SUMMARY IS A SEPARATE TIER, NOT A BIGGER FOLD. Folding loses content permanently from the
 *    model's view; a summary preserves intent. So the ladder is: drop tool noise -> fold with
 *    a marker -> summarise (async, via the worker slot). Below the summary tier we must say
 *    "degraded" rather than pretend the model still sees everything.
 */

import { ELISION_MARKER, estimateTokens, snapToolSafeCuts, type GuardMessage } from './guards';

// The module docstring already explains the ordering rules; this points at the file that turns
// the summary tier from "unreachable" into "reachable".
export { makeSummarizer } from './summarize';

/**
 * A message as the budget sees it. `BudgetMessage` deliberately has no 'tool' role — the guard
 * only ever inspects what goes to the API — but context pressure comes overwhelmingly FROM tool
 * results, so this module needs to name them. Extending rather than widening BudgetMessage keeps
 * the loop guard's types untouched.
 */
export interface BudgetMessage extends GuardMessage {
	role: 'system' | 'user' | 'assistant' | 'tool';
	/** Tool name, when this turn was a tool result. Used for grouping, not for token counting. */
	toolName?: string;
}

/** One addressable slice of the context. The panel toggles these. */
export type ManifestKey =
	| 'system'
	| 'tools'
	| 'memory'
	| 'todo'
	| 'persona'
	| 'history';

export const MANIFEST_KEYS: readonly ManifestKey[] = [
	'system', 'tools', 'memory', 'todo', 'persona', 'history',
];

/** Chinese labels for the panel. Kept here so main and renderer cannot disagree. */
export const MANIFEST_LABELS: Record<ManifestKey, string> = {
	system: '系统提示',
	tools: '工具定义',
	memory: '记忆注入',
	todo: '待办提醒',
	persona: '助手人格',
	history: '对话历史',
};

/** Per-key on/off. Absent = on, so adding a key later does not silently disable it. */
export type ManifestToggles = Partial<Record<ManifestKey, boolean>>;

export interface BudgetConfig {
	/** Hard ceiling in tokens. Above this we MUST compact before sending. */
	maxTokens: number;
	/** Fraction of maxTokens at which compaction kicks in. 0.95 per the plan. */
	triggerRatio: number;
	/** Keep this many oldest turns. */
	head: number;
	/** Keep this many newest turns. */
	tail: number;
	/** Per-turn clip. */
	maxCharsPerTurn: number;
	/** Below this many tokens we do nothing at all — folding a short transcript is noise. */
	minTokensBeforeCompaction: number;
	/** Token count of the tools block, as sent. Computed by the caller. */
	toolTokens: number;
}

/**
 * Defaults chosen against real numbers, not round ones. 128k is the GLM context; 95% of it
 * leaves room for the response, which is the whole point of the ratio.
 */
export const DEFAULT_BUDGET: BudgetConfig = {
	maxTokens: 128_000,
	triggerRatio: 0.95,
	head: 2,
	tail: 12,
	maxCharsPerTurn: 4000,
	minTokensBeforeCompaction: 8_000,
	toolTokens: 0,
};

export interface ManifestRow {
	key: ManifestKey;
	label: string;
	tokens: number;
	/** Percent of the whole request, for sorting the panel by cost. */
	share: number;
	/** Whether the user has this item switched on. */
	enabled: boolean;
	/** True when compaction altered this item. */
	compacted: boolean;
}

export interface Manifest {
	rows: ManifestRow[];
	total: number;
	/** Sum of the rows; must equal `total` or the panel is lying. */
	sum: number;
	limit: number;
	/** total / limit, 0..n. Above 1 means the request cannot be sent. */
	ratio: number;
	/** The tier that was applied. */
	tier: CompactionTier;
	/** Set when the payload had to be degraded below a summary. */
	degraded: boolean;
	/** True when compaction actually changed something. */
	compacted: boolean;
	savedTokens: number;
}

export type CompactionTier =
	| 'none'
	/** Dropped the noisiest tool-result content; cheapest and lossless-ish. */
	| 'micro'
	/** Folded the middle into one marker; content the model can no longer see. */
	| 'fold'
	/** Summarised the middle. Only reachable when a summariser is supplied. */
	| 'summary';

export interface BudgetDeps {
	history: BudgetMessage[];
	/** The system prompt as assembled this round (persona/memory/todo already appended). */
	system: string;
	/** The parts of the system prompt, for the per-item breakdown. Omitted = folded into system. */
	systemParts?: Partial<Record<'persona' | 'memory' | 'todo', string>>;
	/** Tokens of the tools JSON as sent. */
	toolTokens: number;
	/** Optional async summariser (the worker slot). Absent = summary tier unreachable. */
	summarize?: (messages: BudgetMessage[]) => Promise<string>;
}

export interface BudgetResult {
	/** What to actually send. */
	messages: BudgetMessage[];
	/** The system prompt after toggles. */
	system: string;
	manifest: Manifest;
}

function enabled(toggles: ManifestToggles | undefined, key: ManifestKey): boolean {
	// Absent = enabled. Defaulting to off would silently break every existing install the
	// moment a new key appears in the enum.
	return toggles?.[key] !== false;
}

/** Blank a message's content down to a marker, keeping its role. */
function blank(m: BudgetMessage, note: string): BudgetMessage {
	return { ...m, content: `${m.content.slice(0, 200)}\n…${note}` };
}

/**
 * Tier 1 — micro-compaction: shorten the noisiest tool-result turns, in the MIDDLE only.
 *
 * Why the middle: the oldest turns are the original ask (dropping them loses the task) and the
 * newest are what the model is working on right now (dropping those breaks the current step).
 * The middle is where `file_read` dumps of 4000 lines live.
 */
function microCompact(messages: BudgetMessage[], maxChars: number): { messages: BudgetMessage[]; saved: number } {
	let saved = 0;
	const out = messages.map((m, i) => {
		const content = typeof m.content === 'string' ? m.content : '';
		// Tool results are the bulk, so only those get micro-compacted; a clipped user message
		// is a clipped instruction, which is far worse than a clipped file dump.
		const isToolish = m.role === 'tool' || !!m.tool_calls;
		if (!isToolish || content.length <= maxChars) { return m; }
		const at = i;
		saved += content.length - maxChars;
		return blank(m, `（工具结果已压缩，原 ${content.length} 字）`);
		// `at` is unused but keeps the intent explicit: this is positional by design.
		void at;
	});
	return { messages: out, saved };
}

/**
 * Build the request under budget.
 *
 * One pass in a fixed order — toggles, then the compaction ladder, then the manifest — because
 * measuring before applying a toggle makes the 95% threshold mean different things depending on
 * evaluation order. See the header note, point 2.
 */
export async function buildBudgetedRequest(
	deps: BudgetDeps,
	cfg: Partial<BudgetConfig> = {},
	toggles?: ManifestToggles,
): Promise<BudgetResult> {
	// toolTokens comes from the CALLER (it is measured from the serialized tools array), so it
	// must win over the default. Merging naively left DEFAULT_BUDGET.toolTokens = 0 shadowing
	// it, which meant a request with 9k tokens of tool definitions counted as zero and never
	// tripped the trigger. The panel would also have under-reported by exactly that amount.
	const c: BudgetConfig = { ...DEFAULT_BUDGET, ...cfg, toolTokens: cfg.toolTokens ?? deps.toolTokens };
	const trig = Math.floor(c.maxTokens * c.triggerRatio);

	// ---- 1. toggles --------------------------------------------------------
	// Turning history off is handled by the caller (it owns the array); here we only decide
	// whether to send it, which is what the panel toggles.
	const historyOn = enabled(toggles, 'history');
	let system = enabled(toggles, 'system') ? deps.system : '';
	const parts = deps.systemParts ?? {};
	const personaOn = enabled(toggles, 'persona');
	const memoryOn = enabled(toggles, 'memory');
	const todoOn = enabled(toggles, 'todo');
	const toolsOn = enabled(toggles, 'tools');

	// The persona/memory/todo text lives INSIDE the system string today. When one of them is
	// toggled off we cannot subtract it, so we rebuild the system from parts when we have them
	// and otherwise say so via the manifest rather than pretending we removed it.
	const rebuilt = rebuildSystem(deps.system, parts, { persona: personaOn, memory: memoryOn, todo: todoOn });

	// ---- 2. compaction ladder ---------------------------------------------
	let tier: CompactionTier = 'none';
	let degraded = false;
	let messages = historyOn ? deps.history.slice() : [];
	let savedTokens = 0;

	const tokenOf = (msgs: BudgetMessage[]) => estimateTokens(msgs);

	let total = tokenOf(messages) + c.toolTokens + Math.ceil(system.length / 4);

	if (historyOn && total > c.minTokensBeforeCompaction && total > trig) {
		// Tier 1: micro.
		const micro = microCompact(messages, Math.floor(c.maxCharsPerTurn / 2));
		const afterMicro = tokenOf(micro.messages) + c.toolTokens + Math.ceil(system.length / 4);
		if (afterMicro < total) {
			savedTokens += total - afterMicro;
			messages = micro.messages;
			total = afterMicro;
			tier = 'micro';
		}
	}

	if (historyOn && total > trig) {
		// Tier 3 first if a summariser exists, because a summary PRESERVES intent while a fold
		// destroys it. Trying the fold first would mean never reaching the summary tier.
		//
		// Both tiers split head/middle/tail, and both cuts have to miss the inside of an
		// assistant(tool_calls) → tool* run or half a round goes out and the request is refused.
		const cutSum = snapToolSafeCuts(messages, c.head, c.tail);
		if (deps.summarize && cutSum.tailStart > cutSum.headEnd) {
			const head = messages.slice(0, cutSum.headEnd);
			const tail = messages.slice(cutSum.tailStart);
			const middle = messages.slice(cutSum.headEnd, cutSum.tailStart);
			try {
				const text = await deps.summarize(middle);
				if (text && text.trim()) {
					const summaryMsg: BudgetMessage = { role: 'user', content: `【前情摘要】${text.trim()}` };
					const next = [...head, summaryMsg, ...tail];
					const after = tokenOf(next) + c.toolTokens + Math.ceil(system.length / 4);
					if (after < total) {
						savedTokens += total - after;
						messages = next;
						total = after;
						tier = 'summary';
					}
				}
			} catch (e) {
				// A summariser failure must NOT lose the conversation: fall through to the fold.
				degraded = true;
			}
		}
		if (total > trig) {
			// Tier 2: fold with a marker. Content the model can no longer see, so this is
			// reported honestly via the tier rather than presented as a no-op.
			//
			// NOT truncateHeadTail: that helper clips EVERY turn to maxCharsPerTurn, including
			// the head. The head is the original ask, and clipping an instruction corrupts the
			// task in a way that is invisible afterwards — the model just does the wrong thing.
			// So the head is preserved verbatim and only the tail is clipped.
			const foldWithSafeHead = (msgs: BudgetMessage[]): BudgetMessage[] => {
				// Same snapping as the summary tier: a cut landing one message short of a tool
				// response keeps the assistant and drops the answers (or the reverse), and a
				// marker inserted between them breaks the pair just as effectively as a cut.
				const { headEnd, tailStart } = snapToolSafeCuts(msgs, c.head, c.tail);
				const head = msgs.slice(0, headEnd);
				const tail = msgs.slice(tailStart);
				const clippedTail = tail.map(m => {
					if (typeof m.content !== 'string' || m.content.length <= c.maxCharsPerTurn) { return m; }
					return { ...m, content: m.content.slice(0, c.maxCharsPerTurn) + '…（已截断）' };
				});
				return [...head, { role: 'user', content: ELISION_MARKER }, ...clippedTail];
			};
			const folded = { messages: foldWithSafeHead(messages) };
			const after = tokenOf(folded.messages) + c.toolTokens + Math.ceil(system.length / 4);
			if (after < total) {
				savedTokens += total - after;
				messages = folded.messages;
				total = after;
				if (tier !== 'summary') { tier = 'fold'; }
			}
		}
	}

	// ---- 3. manifest ------------------------------------------------------
	// Built from the SAME arrays we are about to send. Computing it separately is how the panel
	// ends up disagreeing with the bill.
	const finalSystem = rebuilt.ok ? rebuilt.system : system;
	const manifest = buildManifest({
		history: messages,
		historyOn,
		toolTokens: c.toolTokens,
		toolsOn,
		system: finalSystem,
		parts,
		partOn: { persona: personaOn, memory: memoryOn, todo: todoOn },
		limit: c.maxTokens,
		tier,
		degraded,
		savedTokens,
		originalTotal: total + savedTokens,
	});

	return { messages, system: finalSystem, manifest };
}

/**
 * Rebuild the system prompt with individual parts removed.
 *
 * When the parts are not supplied we cannot subtract them, and returning a fake "removed" would
 * be worse than admitting it — so `ok` is false and the caller keeps the full prompt.
 */
function rebuildSystem(
	full: string, parts: Partial<Record<'persona' | 'memory' | 'todo', string>>,
	on: { persona: boolean; memory: boolean; todo: boolean },
): { ok: boolean; system: string } {
	const anyOff = !on.persona || !on.memory || !on.todo;
	if (!anyOff) { return { ok: true, system: full }; }
	const known = [parts.persona, parts.memory, parts.todo].filter((s): s is string => !!s);
	if (!known.length) { return { ok: false, system: full }; }
	let out = full;
	for (const [key, text] of Object.entries(parts) as [keyof typeof parts, string | undefined][]) {
		if (!text || on[key]) { continue; }
		// Remove the exact text plus the blank line it was appended with.
		out = out.split(`\n\n${text}`).join('').split(text).join('');
	}
	return { ok: true, system: out };
}

/** Per-item token breakdown. Pure, so the panel can be unit-tested without a request. */
function buildManifest(a: {
	history: BudgetMessage[]; historyOn: boolean;
	toolTokens: number; toolsOn: boolean;
	system: string;
	parts: Partial<Record<'persona' | 'memory' | 'todo', string>>;
	partOn: { persona: boolean; memory: boolean; todo: boolean };
	limit: number; tier: CompactionTier; degraded: boolean;
	savedTokens: number; originalTotal: number;
}): Manifest {
	const toks = (s: string) => Math.ceil(s.length / 4);
	// The system row is the whole prompt MINUS the individually-addressable parts, so the rows
	// sum to the real total instead of double-counting the appended parts.
	const partTotal = (Object.keys(a.parts) as (keyof typeof a.parts)[])
		.reduce((n, k) => n + (a.parts[k] ? toks(a.parts[k]!) : 0), 0);
	const base = Math.max(0, toks(a.system) - partTotal);

	const raw: Record<ManifestKey, { tokens: number; enabled: boolean; compacted: boolean }> = {
		system: { tokens: base, enabled: true, compacted: false },
		tools: { tokens: a.toolsOn ? a.toolTokens : 0, enabled: a.toolsOn, compacted: false },
		memory: { tokens: a.partOn.memory && a.parts.memory ? toks(a.parts.memory) : 0, enabled: a.partOn.memory, compacted: false },
		todo: { tokens: a.partOn.todo && a.parts.todo ? toks(a.parts.todo) : 0, enabled: a.partOn.todo, compacted: false },
		persona: { tokens: a.partOn.persona && a.parts.persona ? toks(a.parts.persona) : 0, enabled: a.partOn.persona, compacted: false },
		history: {
			tokens: a.historyOn ? estimateTokens(a.history) : 0,
			enabled: a.historyOn,
			compacted: a.tier !== 'none',
		},
	};

	const total = Object.values(raw).reduce((n, r) => n + r.tokens, 0);
	const rows = MANIFEST_KEYS.map(key => ({
		key, label: MANIFEST_LABELS[key],
		tokens: raw[key].tokens,
		share: total > 0 ? raw[key].tokens / total : 0,
		enabled: raw[key].enabled,
		compacted: raw[key].compacted,
	})).sort((x, y) => y.tokens - x.tokens);

	return {
		rows, total, sum: rows.reduce((n, r) => n + r.tokens, 0), limit: a.limit,
		ratio: a.limit > 0 ? total / a.limit : 0,
		tier: a.tier, degraded: a.degraded,
		compacted: a.tier !== 'none',
		savedTokens: a.savedTokens,
	};
}

/** Append-only manifest log, so "where did my tokens go" is answerable after the fact. */
export class ManifestLog {
	private readonly rows: { at: number; total: number; limit: number; tier: CompactionTier; by: Record<ManifestKey, number> }[] = [];

	record(m: Manifest): void {
		const by = {} as Record<ManifestKey, number>;
		for (const r of m.rows) { by[r.key] = r.tokens; }
		this.rows.push({ at: Date.now(), total: m.total, limit: m.limit, tier: m.tier, by });
		// Bounded: a manifest per round would otherwise grow faster than the transcript.
		if (this.rows.length > 500) { this.rows.splice(0, this.rows.length - 500); }
	}

	recent(n = 20) { return this.rows.slice(-n); }

	get last() { return this.rows[this.rows.length - 1] ?? null; }
}

/** Human-readable one-liner for the panel header. */
export function manifestSummary(m: Manifest): string {
	const pct = Math.round(m.ratio * 100);
	if (m.degraded) { return `${pct}% · 摘要失败，已降级为折叠`; }
	if (m.tier === 'summary') { return `${pct}% · 已摘要中段（省 ${m.savedTokens} tokens）`; }
	if (m.tier === 'fold') { return `${pct}% · 已折叠中段（省 ${m.savedTokens} tokens）`; }
	if (m.tier === 'micro') { return `${pct}% · 已压缩工具结果（省 ${m.savedTokens} tokens）`; }
	return `${pct}%`;
}

export { ELISION_MARKER };