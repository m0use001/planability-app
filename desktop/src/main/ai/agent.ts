/* Default agent: ten-round tool loop — ported from COD planability-ai handleChatRequest.
 * vscode.lm.sendRequest/invokeTool → provider.streamChat + ToolRegistry;
 * VS Code chat confirmation card → approval IPC round-trip (renderer shows the card).
 * Message shapes keep the COD-proven convention (tool results ride a user message
 * with a [tool results] JSON envelope — verified live against MiMo on 10-03). */

import { buildBudgetedRequest, ManifestLog, type Manifest, type ManifestToggles, type CompactionTier } from './context-budget';
import { CardStreamTransformer } from './cards';
import { CARD_PROMPT_SECTION } from '../../../shared/cards';
import { buildRepairTurn, findBrokenCards } from './card-repair';
import { chatCompletion, streamChat, callNonStream, type SlotConfig, type ToolCall } from './provider';
import type { ToolRegistry } from './tools';
import type { Store } from '../store/store';
import type { ApprovalDecision, ApprovalRequest } from '../../../shared/chat';
import type { PermMode } from '../../../shared/ipc';
import type { Assistant } from '../../../shared/types';
import type { SessionStore } from './sessions';
import { WRITE_COLLECTIONS, type UndoStack } from './undo';
import { CheckpointStack, LoopGuard, estimateTokens, type GuardMessage } from './guards';
import type { InjectMode } from '../../../shared/chat';

const MAX_TOOL_ROUNDS = 10;
/**
 * A turn normally stops after MAX_TOOL_ROUNDS tool rounds — but a thought the user queued
 * mid-turn would then never be seen. When the budget runs out with the queue still holding
 * something, grant a FRESH budget (bounded, so a busy queue cannot spin forever).
 */
const MAX_BUDGET_RENEWALS = 3;
/**
 * M17's fixed "24 turns then fold" trigger is GONE (M19-1 replaced it).
 *
 * Kept as a comment rather than deleted because it explains why the old rule was wrong: turn
 * count is not a proxy for context size. A session with a 200KB system prompt and 30 tools can
 * blow the token budget at 8 turns, while a 40-turn session of one-word replies is tiny. Folding
 * on turns wasted context on the second and failed to protect the first.
 */

/** Primitive agent events — consumed by the RowBridge (main/ai/rows.ts), which converts
 * them into the v2 row protocol streamed to the renderer. */
export type AgentEvent =
	| { type: 'text'; value: string }
	| { type: 'reasoning'; value: string }
	| { type: 'tool_start'; name: string; argsJson: string }
	| { type: 'tool_end'; name: string; ok: boolean; summary: string; ms: number; denied?: boolean; result?: string }
	/** M17 实时插话: a queued thought was just handed to the model (at a round boundary). */
	| { type: 'user_injected'; text: string; at: number }
	| { type: 'done' }
	| { type: 'error'; message: string };

export const SYSTEM_PROMPT = [
	'你是 Planability AI 工作台助手（Planability 电脑端）。',
	'职责：像在 IDE 里调用工具一样，帮助用户处理日程、待办、笔记、记忆与信息整理。',
	'规则：',
	'- 需要查/写数据时优先调用可用工具，不要空口猜测。',
	'- 写类操作先用一句话说明要做什么，再执行；结果用简洁中文汇报。',
	'- 涉及相对日期（今天/明天/下周等）或需要确认今天几号时，先调用 now；不要猜日期。',
	'- 创建带日期的日程/待办时必须写明确年份（如 2026-10-08，不要只写「明天」）。',
	'- 回答保持简洁、结构化，重要结论放前面。',
	// Which cards exist, what fields each takes, and how to teach the model to emit them all come
	// from card-registry.ts. Hand-writing that paragraph here is how the prompt's card list and the
	// parser's card list drift apart — and when they do, the model confidently produces cards the
	// renderer will not recognise, with nothing anywhere reporting it.
	CARD_PROMPT_SECTION,
].join('\n');

export type { ApprovalDecision, ApprovalRequest };

export interface OpenAIMessage {
	/**
	 * 'tool' is part of the real OpenAI chat format. Today the agent stores tool results under
	 * 'user' with a `[tool results]` prefix, which works but hides them from the context budget:
	 * M19-1 needs to tell a 4000-line file dump apart from a user instruction, because clipping
	 * the first is cheap and clipping the second corrupts the task. Widening the union lets the
	 * budget name them; nothing here changes what is sent.
	 */
	role: 'system' | 'user' | 'assistant' | 'tool';
	content: string;
	/**
	 * OpenAI-style APIs require this on `role:'tool'` messages, one id per message — MiMo
	 * hard-rejects the batched shape with 400 "tool_call_id is not set" (pc-a 01:29 block).
	 */
	tool_call_id?: string;
	tool_calls?: { id: string; type: 'function'; function: { name: string; arguments: string } }[];
}

interface AgentDeps {
	registry: ToolRegistry;
	store: Store;
	sessions: SessionStore;
	/**
	 * The session THIS instance owns. The main chat passes `() => sessions.active()`, but a
	 * sub-agent or peer run MUST pass its own sid: using the global active() made a child load
	 * the parent's transcript and write its own turns back into it (answers came out
	 * contaminated, and the parent's history was at risk of being overwritten).
	 */
	sessionId: () => string;
	undo: UndoStack;
	/** Effective slot config — config-first so UI model/key changes apply next turn. */
	slot: () => SlotConfig;
	/** Permission gear (phone ChatPermMenu parity). */
	permMode: () => PermMode;
	/** Active sub-agent persona (phone assistants parity) — null = plain general. */
	assistant: () => Pick<Assistant, 'name' | 'systemPrompt' | 'temperature'> | null;
	/** Capability plugin toggles (next-turn effective). */
	plugins: () => { undo: boolean; cards: boolean };
	/** Memory injection hint in the system prompt. */
	memInject: () => boolean;
	/**
	 * M17 待办表 (P2): reminder block for this round, '' when there is nothing to say.
	 * The TodoStore owns the Q2 always/everyN policy; the agent just appends what it returns.
	 */
	todoReminder?: (round: number) => string;
	emit: (e: AgentEvent) => void;
	/**
	 * Optional hook to enrich the approval card's argsJson for a specific tool (M18: file_write
	 * renders a unified diff there). Returning a string REPLACES what the card shows; the raw
	 * input is still what the tool receives.
	 */
	approvalArgs?: (toolName: string, input: Record<string, unknown>, argsJson: string) => string;
	/**
	 * 双端项目同步 P0-d：每轮 persist 后镜像会话到项目文件夹（index.ts 提供，best-effort，
	 * 绝不因镜像失败影响回合）。缺省不挂 = 不镜像。
	 */
	mirrorSession?: (sessionId: string) => void;
	requestApproval: (req: ApprovalRequest) => Promise<ApprovalDecision>;
	isAborted: () => boolean;

	// ---- M19-1 ContextBudget hooks (all optional; absent means "no summary, no toggles") ----
	/**
	 * Summariser for the middle of a long transcript, run on the WORKER slot so it never
	 * competes with the main answer. Returning '' declines to summarise, which is not an error —
	 * the budget then falls back to folding. Throwing is treated the same way, and reported.
	 */
	summarize?: (messages: GuardMessage[]) => Promise<string>;
	/** Per-item context switches. Absent = everything on (a new key must not disable itself). */
	manifestToggles?: () => ManifestToggles | undefined;

	// ---- M19-3 memory settling ----
	/**
	 * Settle the just-finished turn into durable memories.
	 *
	 * Runs on the WORKER slot, AFTER the turn has already been delivered to the user, and must
	 * fail SOFT: a failed settlement must not disturb a turn the user has already read. That is
	 * why it is a separate optional hook rather than inline work in the send loop.
	 */
	settleMemories?: (round: number) => Promise<{
		ok: boolean; reason?: string;
		applied: { key: string; kind: string; seq: number }[];
		rejected: { name: string; reason: string }[];
	}>;

	// ---- M19-4 skills ----
	/**
	 * Markdown to inject for THIS turn (loaded and selected outside the agent, because it needs
	 * the filesystem and the user's message). Return '' for none.
	 *
	 * The caller renders it as REFERENCE MATERIAL, not as instructions — a skill file is
	 * user-writable and this string goes into the system prompt, so the framing has to be part
	 * of what is produced rather than something a consumer might forget.
	 */
	skillBlock?: (userText: string) => Promise<string>;
}


/**
 * How to describe a compaction tier to the user. Saying nothing here is what makes a model
 * quietly contradict an instruction three turns later, so each tier names itself.
 */
function tierLabel(tier: CompactionTier): string {
	switch (tier) {
		case 'micro': return '压缩工具结果';
		case 'fold': return '折叠中段对话';
		case 'summary': return '摘要中段对话';
		default: return '处理';
	}
}

/** M17 实时插话: a thought the user typed while the AI was mid-turn. */
interface QueuedThought {
	text: string;
	at: number;
}

export class ChatAgent {
	private history: OpenAIMessage[] = [];
	/** How many repair rounds this agent has spent. Counted rather than capped because a cap would
	 *  be a policy about the user's tokens that belongs to them, not to this module — but it must be
	 *  visible so a turn that keeps paying for repairs is noticeable instead of invisible. */
	private cardRepairs = 0;
	private readonly alwaysAllow = new Set<string>();
	private approvalSeq = 0;
	/** M17 loop-protection: signature ring + circuit breaker, one per turn. */
	private readonly loopGuard = new LoopGuard();
	/** M17 soft checkpoints: pushed before risky turns, rewindable instead of losing a session. */
	private readonly checkpoints = new CheckpointStack();
	/**
	 * M17 实时插话队列: thoughts typed while a turn is running. They are NOT in `history`
	 * until injected at a round boundary, so the model never sees half-written text.
	 */
	private readonly queue: QueuedThought[] = [];
	/** 'nextRound' = inject before the next tool round; 'afterTurn' = only once this turn ends. */
	private injectMode: InjectMode = 'nextRound';

	// ---- M19-1 context budget state ----
	/** The switchable parts of this round's system prompt, so the budget can subtract them. */
	private systemParts: Partial<Record<'persona' | 'memory' | 'todo', string>> = {};
	/** Append-only per-request token ledger, surfaced to the context panel. */
	readonly manifestLog = new ManifestLog();
	/** The most recent request's manifest, for tests and the panel's initial render. */
	lastManifest: Manifest | null = null;

	constructor(private readonly deps: AgentDeps) {}

	get messageCount(): number { return this.history.length; }

	/** Load the active session's memory as the working copy (call at turn start). */
	async loadActive(): Promise<void> {
		this.history = await this.deps.sessions.loadHistory(this.sid);
	}

	private async persist(title?: string): Promise<void> {
		await this.deps.sessions.saveHistory(this.sid, this.history, title);
		// P0-d：镜像到项目文件夹。空历史（clear）不镜像；fire-and-forget，绝不拖回合。
		if (this.history.length && this.deps.mirrorSession) {
			try { this.deps.mirrorSession(this.sid); } catch { /* 镜像失败不进回合链路 */ }
		}
	}

	clear(): void {
		this.history = [];
		this.alwaysAllow.clear();
		void this.deps.sessions.saveHistory(this.sid, []);
	}

	/** Which session this instance owns — NEVER the global active() for a sub/peer run. */
	private get sid(): string { return this.deps.sessionId(); }

	/** Regenerate support: drop the last k real user turns (and everything after them). */
	async rollbackUsers(k: number): Promise<void> {
		await this.loadActive();
		let dropped = 0;
		let i = this.history.length;
		while (i > 0 && dropped < k) {
			i--;
			const m = this.history[i];
			if (m.role === 'user' && !m.content.startsWith('[tool results]')) { dropped++; }
		}
		this.history = this.history.slice(0, i);
		await this.persist();
	}

	// ---- M17 loop-protection surface (used by IPC / tests) ----

	/** Drop a checkpoint before a risky turn so the session can be rewound, not lost. */
	pushCheckpoint(label: string): number {
		return this.checkpoints.push(label, this.history).seq;
	}

	/** Rewind to a checkpoint. Returns false when no such checkpoint exists. */
	async rewindTo(seq: number): Promise<boolean> {
		const restored = this.checkpoints.rewindTo(seq);
		if (!restored) { return false; }
		this.history = restored;
		await this.persist();
		return true;
	}

	checkpointList(): { seq: number; label: string; at: number; turns: number }[] {
		return this.checkpoints.list();
	}

	/** Guard state for diagnostics. */
	guardState(): { tripped: boolean; reason: string; signatures: string[] } {
		return {
			tripped: this.loopGuard.isTripped,
			reason: this.loopGuard.tripReason,
			signatures: this.loopGuard.signatures,
		};
	}

	/** Rough context size of the live transcript (chars -> tokens estimate). */
	estimateContextTokens(): number { return estimateTokens(this.history); }

	/**
	 * The last request's token manifest, or null before the first turn.
	 *
	 * This is the ONLY number the context panel should show. Computing tokens in the renderer
	 * from the visible transcript produces a different figure than the one the provider was
	 * billed, because the request also carries the system prompt and the tool definitions.
	 */
	manifest(): Manifest | null { return this.lastManifest; }

	/** Recent per-request ledger, for the panel's history view. */
	manifestHistory(n = 20) { return this.manifestLog.recent(n); }

	/**
	 * The transcript as the memory extractor should see it.
	 *
	 * A COPY, and tool turns stripped: "read file X" is an action, not a fact, and feeding it to
	 * the extractor is how a memory library fills with operational noise. The extractor also
	 * clips internally, but doing it here means the transcript it receives cannot be mutated by
	 * whatever runs next.
	 */
	transcriptForMemory(): GuardMessage[] {
		return this.history
			.filter(m => m.role !== 'tool')
			.map(m => ({ role: m.role, content: typeof m.content === 'string' ? m.content : '' }));
	}

	/**
	 * Settle a finished turn into memories, tolerating every failure.
	 *
	 * Deliberately NOT awaited into the answer: the user is told the turn is done first, and
	 * settlement happens afterwards, so a failure cannot disturb a turn they have already read.
	 * Errors are still surfaced as a row — silently not remembering something is worse than
	 * saying so, because the user would assume it was stored.
	 */
	private async settle(round: number, emit: (e: AgentEvent) => void): Promise<void> {
		const hook = this.deps.settleMemories;
		if (!hook) { return; }
		try {
			const r = await hook(round);
			if (!r.ok) {
				emit({ type: 'text', value: `\n\n（本轮记忆沉淀未完成：${r.reason ?? '未知原因'}）` });
				return;
			}
			// Say nothing when nothing new was learned. A "沉淀了 0 条记忆" line on every turn
			// is noise that trains users to stop reading the ones that matter.
			if (r.applied.length) {
				emit({ type: 'text', value: `\n\n（已沉淀 ${r.applied.length} 条记忆，可用 memory_history 查看来由。）` });
			}
		} catch (e) {
			emit({ type: 'text', value: `\n\n（本轮记忆沉淀失败：${(e as Error)?.message ?? e}）` });
		}
	}

	// ---- M17 实时插话 ----

	/**
	 * Park a thought for the running turn. Safe to call at any time — it never aborts the
	 * stream and never throws, so the user does not have to stop the AI to add a thought.
	 */
	enqueueThought(text: string): QueuedThought[] {
		const t = String(text ?? '').trim();
		if (t) { this.queue.push({ text: t, at: Date.now() }); }
		return this.pendingThoughts();
	}

	pendingThoughts(): QueuedThought[] { return this.queue.map(q => ({ ...q })); }
	clearThoughts(): void { this.queue.length = 0; }
	getInjectMode(): InjectMode { return this.injectMode; }
	setInjectMode(m: InjectMode): InjectMode {
		this.injectMode = m === 'afterTurn' ? 'afterTurn' : 'nextRound';
		return this.injectMode;
	}

	/**
	 * Move queued thoughts into the transcript as real user messages, then announce them.
	 * Always drains, so callers can safely `continue` afterwards without looping forever.
	 */
	private async injectQueued(emit: (e: AgentEvent) => void): Promise<number> {
		if (!this.queue.length) { return 0; }
		const drained = this.drainQueueIntoHistory();
		await this.persist();
		for (const q of drained) { emit({ type: 'user_injected', text: q.text, at: q.at }); }
		return drained.length;
	}

	/** Move queued thoughts into the transcript as real user messages. */
	private drainQueueIntoHistory(): QueuedThought[] {
		if (!this.queue.length) { return []; }
		const drained = this.queue.splice(0, this.queue.length);
		for (const q of drained) {
			this.history.push({ role: 'user', content: q.text });
		}
		return drained;
	}

	async send(userText: string): Promise<void> {
		const { registry, store, emit, requestApproval, isAborted } = this.deps;
		await this.loadActive();
		// M19-4: load and select skills for THIS turn, once, before the round loop. Doing it
		// inside the loop would re-read the directory on every tool round and could pick up a
		// file the user edited mid-turn — the prompt would then change under the model.
		let skillBlock = '';
		if (this.deps.skillBlock) {
			try { skillBlock = await this.deps.skillBlock(userText); }
			catch { skillBlock = ''; }   // a broken skills dir must not break the turn
		}
		this.history.push({ role: 'user', content: userText });
		await this.persist(userText.slice(0, 24).replace(/\s+/g, ' '));
		// Loop guard is per-turn: a fresh user instruction deserves a fresh budget.
		this.loopGuard.reset();

		try {
			// Budget loop instead of a plain `for (round < MAX_TOOL_ROUNDS)`: when the tool-round
			// budget is exhausted but the user queued thoughts, grant a fresh budget (bounded by
			// MAX_BUDGET_RENEWALS) so queued input is never silently dropped.
			let round = 0;
			let renewals = 0;
			for (;;) {
				if (isAborted()) { emit({ type: 'error', message: '已停止' }); return; }

				// M17 实时插话: at a round boundary (never mid-stream) hand queued thoughts to
				// the model as real user messages, so it can pivot mid-task.
				// 'afterTurn' skips this — it injects at the turn-end branch instead, which is
				// the whole point of that mode.
				if (this.injectMode === 'nextRound') { await this.injectQueued(emit); }

				this.loopGuard.beginRound();
				round++;

				const cardStream = new CardStreamTransformer();
				const textAcc: string[] = [];
				const toolCalls: ToolCall[] = [];
				const persona = this.deps.assistant();
				let system = persona?.systemPrompt
					? `${SYSTEM_PROMPT}\n\n# 当前助手：${persona.name}\n${persona.systemPrompt}`
					: SYSTEM_PROMPT;
				// M19-1: remember which text belongs to which switchable item. Without these parts
				// the budget cannot subtract persona/memory/todo when the user turns one off — it
				// would have to either lie about the removal or leave the text in.
				const parts: Partial<Record<'persona' | 'memory' | 'todo', string>> = {};
				if (persona?.systemPrompt) { parts.persona = `# 当前助手：${persona.name}\n${persona.systemPrompt}`; }
				if (this.deps.memInject()) {
					const hint = '\n涉及长期记忆/历史经验时，先调用 memory_search 检索相关条目再回答。';
					system += hint;
					parts.memory = hint.trim();
				}
				// M17 待办表: re-inject open items so the user can park thoughts without
				// interrupting. Policy (always / everyN) lives in the TodoStore.
				if (this.deps.todoReminder) {
					const reminder = this.deps.todoReminder(round);
					if (reminder) {
						system += reminder;
						parts.todo = reminder.trim();
					}
				}
				// M19-4: skills are reference material, appended after the reminder so they sit
				// closest to the conversation — they are the most context-sensitive part.
				if (skillBlock) { system += skillBlock; }
				this.systemParts = parts;
				const abort = new AbortController();
				const onAbort = () => abort.abort();
				// caller's abort signal (managed by ChatService) — poll via isAborted hook + local listener
				const poll = setInterval(() => { if (isAborted()) { abort.abort(); } }, 400);
				try {
					// M19-1 ContextBudget replaces the old fixed "24 turns then fold" rule. That rule
					// was a blunt lever: with a large system prompt and 30 tools the transcript is a
					// rounding error, so folding the middle saved nothing. This budgets per ITEM and
					// applies the compaction ladder only when the request is actually near the limit.
					//
					// The old turn-count trigger is kept as a floor, not as the primary rule — a very
					// long single turn can blow the budget while the turn count is still small.
					const toolsForApi = registry.listForApi();
					const toolTokens = Math.ceil(JSON.stringify(toolsForApi ?? []).length / 4);
					const budgeted = await buildBudgetedRequest({
						history: this.history,
						system,
						systemParts: this.systemParts,
						toolTokens,
						summarize: this.deps.summarize,
					}, { triggerRatio: 0.95 }, this.deps.manifestToggles?.());
					// Record every request, so "where did my tokens go" is answerable after the fact.
					this.manifestLog.record(budgeted.manifest);
					this.lastManifest = budgeted.manifest;
					if (budgeted.manifest.compacted) {
						// Say what happened. Silently degrading the context is how a model starts
						// contradicting instructions three turns later with no visible cause.
						emit({
							type: 'text',
							value: `\n\n（上下文已${tierLabel(budgeted.manifest.tier)}，省 ${budgeted.manifest.savedTokens} tokens。）`,
						});
					}
					const outgoing = budgeted.messages;
					const reqBody: Record<string, unknown> = {
						stream: true,
						messages: [{ role: 'system', content: budgeted.system }, ...outgoing],
						tools: toolsForApi
					};
					if (persona?.temperature != null) { reqBody.temperature = persona.temperature; }
					const response = await chatCompletion(this.deps.slot(), reqBody, abort.signal);

					for await (const chunk of streamChat(response)) {
						if (isAborted()) { abort.abort(); break; }
						if (chunk.type === 'text') {
							textAcc.push(chunk.value);
							for (const out of cardStream.feed(chunk.value)) { emit({ type: 'text', value: out }); }
						} else if (chunk.type === 'reasoning') {
							emit({ type: 'reasoning', value: chunk.value });
						} else {
							toolCalls.push(...chunk.calls);
						}
					}
					for (const out of cardStream.flush()) { emit({ type: 'text', value: out }); }
				} finally {
					clearInterval(poll);
					void onAbort;
				}

				if (isAborted()) { emit({ type: 'error', message: '已停止' }); return; }

				const roundText = textAcc.join('');
				if (toolCalls.length === 0) {
					this.history.push({ role: 'assistant', content: roundText || ' ' });

					// A malformed card used to end here as a JSON code block with nothing logged
					// and the model never told, so the NEXT turn wrote the same broken card. Spend
					// ONE extra completion to fix it — the user's answer is already emitted above and
					// is NOT re-emitted, so this can only append a corrected card, never a second
					// copy of the prose.
					const repair = buildRepairTurn(findBrokenCards(roundText));
					if (repair.length && !isAborted()) {
						this.cardRepairs++;
						const repairTurn = repair[0];
						const repairMessages: OpenAIMessage[] = [
							{ role: 'system', content: SYSTEM_PROMPT },
							{ role: 'assistant', content: roundText },
							repairTurn,
						];
						try {
							const fixedText = await callNonStream(
								this.deps.slot(), SYSTEM_PROMPT, repairTurn.content, abort.signal);
							if (fixedText) {
								for (const out of new CardStreamTransformer().feed(fixedText)) {
									emit({ type: 'text', value: out });
								}
							}
						} catch (e) {
							// Best-effort by construction: the user's answer is already on screen, so a
							// failed repair must not turn into a failed turn.
							console.error('[cards] 修复轮失败，保留原卡片：', (e as Error)?.message ?? e);
						}
					}

					await this.persist();
					// Q1=C「等本轮自然结束」: the turn is concluding, but thoughts arrived while it
					// was answering — honour them now instead of stranding them. Draining (not
					// merely continuing) is what keeps this from looping forever.
					if (this.queue.length) { await this.injectQueued(emit); continue; }
					// M19-3: settle the turn into memories. Deliberately NOT awaited into the
					// answer — the user is told the turn is done first, and settlement happens
					// afterwards so a failure cannot corrupt a turn they have already read.
					emit({ type: 'done' });
					void this.settle(round, emit);
					return;
				}

				const assistantMsg: OpenAIMessage = { role: 'assistant', content: roundText || null as any };
				assistantMsg.tool_calls = toolCalls.map(t => ({
					id: t.id, type: 'function',
					function: { name: t.name, arguments: t.arguments || '{}' }
				}));
				this.history.push(assistantMsg);

				// M17 circuit breaker: inspect the whole round BEFORE running anything, so a
				// tripped guard cannot half-apply a batch of writes.
				for (const call of toolCalls) {
					if (this.loopGuard.observe(call.name, call.arguments || '{}')) { break; }
				}
				if (this.loopGuard.isTripped) {
					// Let the model see why it stopped so it can explain rather than retry.
					this.history.push({
						role: 'user',
						content: `[loop guard] ${this.loopGuard.tripReason}。停止调用工具，直接向用户说明你已完成的部分与遇到的问题。`,
					});
					await this.persist();
					emit({ type: 'text', value: `\n\n（${this.loopGuard.tripReason}，已中止工具调用。）` });
					emit({ type: 'done' });
					return;
				}

				const results: { id: string; body: string }[] = [];
				for (const call of toolCalls) {
					const result = await this.runTool(call, requestApproval);
					results.push({ id: call.id, body: JSON.stringify({ callId: call.id, value: result }) });
				}
				// M19-1: tool results go in as a 'tool' turn, not disguised as a user message. The context
				// budget must be able to tell a 4000-line file dump apart from something the
				// user typed — clipping the first is cheap, clipping the second corrupts the
				// task. The `[tool results]` prefix survives for anyone reading the transcript
				// in the UI, so nothing that parses this output breaks.
				// One tool message PER call, each carrying its own tool_call_id: batching them
				// into one message makes MiMo 400 ("tool_call_id is not set") and kills every
				// turn right after its first tool round (pc-a 01:29 block, fixed in place —
				// the block sat unanswered while the regression blocked all tool use).
				for (const r of results) {
					this.history.push({
						role: 'tool',
						tool_call_id: r.id,
						content: `[tool results]\n${r.body}`,
					});
				}
				await this.persist();

				// Tool-round budget spent. Renew only if the user still has thoughts waiting,
				// and only a bounded number of times, so a queue cannot spin the loop forever.
				if (round >= MAX_TOOL_ROUNDS) {
					if (this.queue.length && renewals < MAX_BUDGET_RENEWALS) {
						round = 0;
						renewals++;
						// Inject HERE: the turn is being revived purely because of these thoughts,
						// so they must enter the transcript now. In 'afterTurn' mode the turn-end
						// branch will not be reached (the model keeps calling tools), so renewing
						// without draining would loop the budget without ever delivering them.
						await this.injectQueued(emit);
						emit({ type: 'text', value: `\n\n（工具轮次达到上限，但你还有补充未处理，已继续。）` });
						continue;
					}
					emit({ type: 'text', value: '\n\n（工具轮次达到上限，先停在这里。）' });
					if (this.queue.length) {
						// Out of renewals and thoughts remain: do not drop them silently.
						emit({ type: 'text', value: `\n\n（已达到续跑上限，${this.queue.length} 条补充尚未处理，已保留在待发队列。）` });
					}
					emit({ type: 'done' });
					return;
				}
			}
		} catch (e) {
			emit({ type: 'error', message: String((e as Error)?.message ?? e) });
		}
	}

	private async runTool(call: ToolCall, requestApproval: AgentDeps['requestApproval']): Promise<string> {
		const { registry, store, emit } = this.deps;
		const def = registry.get(call.name);
		if (!def) { return `[FAIL] 未知工具 ${call.name}`; }

		let input: Record<string, unknown> = {};
		try { input = call.arguments ? JSON.parse(call.arguments) : {}; } catch { input = { _raw: call.arguments }; }
		let argsJson = JSON.stringify(input).slice(0, 400);

		// Open the tool row BEFORE the permission gate. RowBridge.toolEnd only settles a row
		// that is still 'running', so a denial (read-only gear or user reject) used to emit an
		// orphan tool_end that silently vanished from the transcript — the user saw no row at
		// all for a refused action. Starting first makes every path (ok / fail / denied)
		// settle the same row.
		const start = Date.now();
		emit({ type: 'tool_start', name: def.name, argsJson });

		if (def.risk !== 'auto') {
			const mode = this.deps.permMode();
				if (mode === 'ro') {
					// Read-only gear: every write/danger is denied outright (phone Visibility parity).
					emit({ type: 'tool_end', name: def.name, ok: false, denied: true, summary: '只读模式拒绝', ms: 0 });
					return '[DENIED] 当前为计划模式（只读），写操作被拒绝。提示用户切换权限档位。';
				}
				const autoRun = (mode === 'auto' || mode === 'full') && def.risk === 'confirm';
				if (!autoRun && !this.alwaysAllow.has(def.name)) {
					const decision = await requestApproval({
						id: `ap_${++this.approvalSeq}_${Date.now().toString(36)}`,
						toolName: def.name,
						risk: def.risk,
						// M18: let a tool enrich what the approval card shows (file_write renders a
						// diff here), instead of dumping raw content into the card.
						argsJson: this.deps.approvalArgs
							? this.deps.approvalArgs(def.name, input, argsJson)
							: argsJson
					});
					if (decision.allowAlways && def.risk === 'confirm') { this.alwaysAllow.add(def.name); }
					if (!decision.allow) {
						emit({ type: 'tool_end', name: def.name, ok: false, denied: true, summary: '用户拒绝', ms: 0 });
						return '[DENIED] 用户拒绝了此操作，不要重试，改用其他方式或直接说明。';
					}
				// Confirm-card parity: user may have edited the arguments before allowing.
				if (decision.args && def.risk === 'confirm') {
					input = decision.args;
					argsJson = JSON.stringify(input).slice(0, 400);
				}
			}
		}

		try {
			// Snapshot the target collection BEFORE a mutating tool runs (undo point).
			const target = WRITE_COLLECTIONS[def.name];
			if (target && this.deps.plugins().undo) { await this.deps.undo.push(target, def.name); }
			const out = await def.invoke(input, { sessionId: this.sid });
			const ms = Date.now() - start;
			await store.audit(def.name, input, true, ms);
			// raw output rides the row (truncated) so structured renderers can parse it
			emit({ type: 'tool_end', name: def.name, ok: true, summary: out.split('\n')[0].slice(0, 120), ms, result: out.slice(0, 1500) });
			return out;
		} catch (e) {
			const ms = Date.now() - start;
			await store.audit(def.name, input, false, ms);
			emit({ type: 'tool_end', name: def.name, ok: false, summary: String(e).slice(0, 120), ms });
			return `[FAIL] ${String(e)}`;
		}
	}
}
