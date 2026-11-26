/* Chat streaming contract v2 (M16): a conversation is an ordered list of rows.
 * ZCode rows.ts parity: rows are self-contained; a structure change re-upserts the whole
 * row, growing text rides row_delta; rowId is monotonic per session and never reused.
 * The main process is the single writer — it persists rows and streams row events; the
 * renderer only projects (v1 renderer-persisted display history is gone; legacy
 * chat_display migrates into rows on first load, see main/ai/rows.ts). */
import type { CollectionName } from './types';
import type { CardEntity } from './cards';

export interface ConvRow {
	rowId: string;
	kind: 'user' | 'assistant' | 'tool' | 'error' | 'system';
	/** user text / assistant markdown / error message */
	text?: string;
	/** user rows only: LOCAL file paths attached to this message (float pane screenshots).
	 *  Bytes stay on this machine — these are references, see ai/chat-attachments.ts. Absent on
	 *  every row that has none, so rows without attachments persist byte-identically as before. */
	attachments?: string[];
	/** assistant-only reasoning stream */
	reasoning?: string;
	/** assistant turn settled — no more deltas will arrive for this row */
	done?: boolean;
	/** turn error carried on the assistant row */
	error?: string;
	/** tool rows only */
	name?: string;
	argsJson?: string;
	status?: 'running' | 'ok' | 'fail' | 'denied';
	summary?: string;
	ms?: number;
	/** raw tool output, truncated — structured renderers parse this */
	result?: string;
}

/**
 * A `list` card carries a QUERY, not content — this is the whole point of it.
 *
 * Every other card (plan/qa/schedule/checklist) holds JSON the MODEL wrote, so what you are
 * looking at is a proposal, not your data. A `list` card instead names a collection and a filter;
 * the main process runs it against the real store and the renderer draws the result. That makes
 * the card true at the moment it is drawn instead of true when the model imagined it.
 *
 * It also makes "re-render" nearly free: refreshing re-sends the same query, so a card can never
 * go stale in the way a snapshot can. That is why refresh needs no new mechanism.
 *
 * The schema is CLOSED and validated in main (ai/list-card.ts). An unknown key is an error, not
 * something to ignore — a silently-ignored filter shows the user the wrong rows with no signal
 * that anything was dropped.
 */
export interface ListCardQuery {
	collection: CollectionName;
	where?: {
		/** todos only. */
		status?: 'open' | 'done';
		/** YYYY-MM-DD. schedules and todos. */
		date?: string;
		/** notes only. */
		pinned?: boolean;
		/** numeric project id, on the project-scoped collections. */
		project?: number;
	};
	/** Defaults to 'updated'. */
	order?: 'updated' | 'created';
	/** Clamped by main; the echo in ListCardResult.query is the number actually applied. */
	limit?: number;
}

/** One displayed row. Deliberately display-shaped and collection-agnostic: the renderer draws
 *  label / detail / when and needs to know nothing about any particular entity type. */
export interface ListCardRow {
	id: string;
	label: string;
	detail?: string;
	/** Right-aligned fact, e.g. a time. */
	when?: string;
}

export interface ListCardResult {
	/** The query AS EXECUTED — normalized, defaults filled, limit clamped. The renderer re-sends
	 *  this exact object to refresh, so what is displayed and what is stored can never disagree. */
	query: ListCardQuery & { limit: number };
	rows: ListCardRow[];
	/** Matches before `limit`, so the card can say「显示 20 / 共 37」instead of implying 20 is all. */
	total: number;
}

/** What a card action invoked and what came back — see CardSpec.actions in shared/cards.ts. */
export interface CardActionRequest {
	/** Card kind (schedule / checklist / …). */
	kind: string;
	/** Action id from that spec's `actions`. */
	actionId: string;
	/** The card JSON as the renderer parsed it — main builds tool args from THIS via a
	 *  closed mapper in ai/card-actions.ts, never passes it through raw. */
	card: unknown;
	/** Required when the action is `perItem`: which item the button was on. */
	itemIndex?: number;
}

export interface CardActionRequestResult {
	ok: boolean;
	/** The tool that ran (echo, so the UI can name it honestly). */
	tool?: string;
	/** The tool's own output. */
	result?: string;
	/** Why it did not run: unknown action, tool hidden by a domain switch, bad card content… */
	error?: string;
	/** Set when the approval card was dismissed or timed out (120s auto-deny). Show it. */
	denied?: boolean;
}

/** Filters for ChatApi.cardList. All optional; an empty query is every stored card. */
export interface CardListQuery {
	/** One session's cards (a conversation's sidebar). */
	sessionId?: string;
	/** One row's cards — the proposal cards belonging to that single message. */
	rowId?: string;
	/** One kind (`list`, `schedule`, …). */
	kind?: string;
}

/** Result of a card-store mutation (pin / remove). `ok: false` ALWAYS carries `reason`. */
export interface CardOpResult {
	ok: boolean;
	/** Shown to the user verbatim — a refusal the UI can explain beats a silent no-op. */
	reason?: string;
}

export type ChatEvent =
	/**
	 * The three row events carry `sessionId`: WHICH SESSION THIS ROW BELONGS TO.
	 *
	 * Sub-agent runs, peers and background automations stream their rows on this same
	 * channel (a floating window watching a run needs them), so without a label a
	 * concurrent `task` run writes its transcript into whatever conversation the renderer
	 * is currently projecting. Consumer rule: an event whose sessionId is present and is
	 * NOT the session being displayed must be DROPPED; an event with no sessionId is
	 * treated as belonging to the displayed session (main-chat path, pre-label callers).
	 *
	 * Stamped by RowBridge, which takes a sessionId provider evaluated at emit time —
	 * the main bridge follows the active session, each run's bridge is fixed to its own.
	 * `done` / `error` / `row_user_queued` stay unlabeled on purpose: only the main-chat
	 * path broadcasts them, so they are main-chat signals by construction.
	 */
	| { type: 'row_upsert'; sessionId?: string; row: ConvRow }
	| { type: 'row_delta'; sessionId?: string; rowId: string; field: 'text' | 'reasoning'; value: string }
	/** full replacement (load/rollback/clear) — renderer rebuilds its projection */
	| { type: 'rows_set'; sessionId?: string; rows: ConvRow[] }
	/**
	 * A user message accepted while a turn is still running (M17 实时插话). It is NOT yet part
	 * of the model transcript — it is queued and injected at the next round boundary. The
	 * renderer shows it so the user can see their thought is parked and when it lands.
	 */
	| { type: 'row_user_queued'; text: string; at: number; /** set when it actually reached the model */ injected?: boolean }
	| { type: 'done' }
	| { type: 'error'; message: string };

export interface ApprovalRequest {
	id: string;
	toolName: string;
	risk: 'confirm' | 'danger';
	argsJson: string;
}

export interface ApprovalDecision {
	allow: boolean;
	allowAlways: boolean;
	/** Edited arguments (confirm-card parity: user may tweak params before allowing). */
	args?: Record<string, unknown>;
}

export const IPC = {
	chatSend: 'chat:send',
	chatStop: 'chat:stop',
	chatClear: 'chat:clear',
	approvalResolve: 'approval:resolve',
	chatGetRows: 'chat:getRows',
	chatRollbackUsers: 'chat:rollbackUsers',
	/** Queue a thought while a turn is running; injected at the next round boundary. */
	chatEnqueue: 'chat:enqueue',
	/** Drop queued thoughts without sending them (user changed their mind). */
	chatEnqueueClear: 'chat:enqueueClear',
	/** Read the pending queue (renderer badge). */
	chatEnqueueList: 'chat:enqueueList',
	chatInjectMode: 'chat:injectMode',
	chatSetInjectMode: 'chat:setInjectMode',
	// ---- M19-1 ContextBudget ----
	contextManifest: 'chat:contextManifest',
	contextTogglesGet: 'chat:contextTogglesGet',
	contextTogglesSet: 'chat:contextTogglesSet',
	contextHistory: 'chat:contextHistory',
	// ---- M20-1 workflows ----
	workflowList: 'workflow:list',
	workflowValidate: 'workflow:validate',
	workflowRun: 'workflow:run',
	workflowReplay: 'workflow:replay',
	workflowRuns: 'workflow:runs',
	// ---- M20-3/4 ----
	teamAssemble: 'workflow:teamAssemble',
	handoffPreview: 'handoff:preview',
	handoffWrite: 'handoff:write',
	handoffRead: 'handoff:read',
	handoffList: 'handoff:list',
	// ---- live-data cards (see ListCardQuery) ----
	/** Run a `list` card's query against the real store. Read-only. */
	listQuery: 'chat:listQuery',
	// ---- card actions (stage 3: a card button IS a gated tool call) ----
	cardAction: 'chat:cardAction',
	// ---- card entities (stage 2: cards are first-class persisted things) ----
	/** List stored cards (filter by session / row / kind) — the sidebar's read surface. */
	cardList: 'chat:cardList',
	/** Pin / unpin a stored card. */
	cardPin: 'chat:cardPin',
	/** Remove a stored card. View cards only; a proposal card refuses with a reason. */
	cardRemove: 'chat:cardRemove'
} as const;

/** How a thought queued mid-turn reaches the model (user picks; see ◆/pc-b.md Q1). */
export type InjectMode = 'nextRound' | 'afterTurn';

// ---- M19-1 ContextBudget: re-declared here rather than imported from main/ai ----
//
// The renderer's tsconfig (tsconfig.web.json) only includes src/renderer/** and shared/**, so a
// shared -> main import would break the web typecheck for every renderer file that touches
// ChatApi. src/main/ai/float/conformance.ts already uses exactly this pattern (declare on both
// sides, prove they agree with compile-time assertions), and it does the same for the float
// contract. Extend that file rather than adding an import here.
export type ContextKey = 'system' | 'tools' | 'memory' | 'todo' | 'persona' | 'history';

export interface ContextManifest {
	rows: { key: ContextKey; label: string; tokens: number; share: number; enabled: boolean; compacted: boolean }[];
	total: number;
	sum: number;
	limit: number;
	ratio: number;
	/** Which compaction tier ran: none | micro | fold | summary. */
	tier: 'none' | 'micro' | 'fold' | 'summary';
	/** True when summarisation failed and the budget fell back to folding. */
	degraded: boolean;
	compacted: boolean;
	savedTokens: number;
}

export const EVENTS = {
	chat: 'chat:event',
	approval: 'approval:request',
	/** M17 kernel: run started/finished, quota waits, bus messages. */
	agentEvent: 'agent:event',
	/** M20-1 workflow progress (a step started / finished / failed). */
	workflowEvent: 'workflow:event'
} as const;

export interface ChatApi {
	/** Fire a user message; row events stream back via onChat. Queues instead of throwing when a turn is running.
	 *  `attachments` are LOCAL file paths (the float pane's screenshots — path-only by design). They ride the
	 *  user row for the transcript, and are referenced in the message the model reads by name and path, never
	 *  by content, so an attachment is no longer dropped between the composer and the turn. */
	send(text: string, attachments?: string[]): Promise<void>;
	stop(): Promise<void>;
	clear(): Promise<void>;
	resolveApproval(id: string, decision: ApprovalDecision): Promise<void>;
	onChat(cb: (e: ChatEvent) => void): () => void;
	onApproval(cb: (req: ApprovalRequest) => void): () => void;
	/** All rows of the active session (crashed turns already normalized by main). */
	getRows(): Promise<ConvRow[]>;
	/** Drop the last k real user turns (and everything after) from the active session — regenerate support. */
	rollbackUsers(k: number): Promise<void>;
	/** Queue a thought mid-turn (M17 实时插话). Never blocks, never throws on a busy turn. */
	enqueue(text: string): Promise<{ queued: number }>;
	/** Pending queued thoughts, oldest first. */
	enqueueList(): Promise<{ text: string; at: number }[]>;
	/** Discard queued thoughts (user changed their mind). */
	enqueueClear(): Promise<void>;
	/** Current injection mode (nextRound = next tool round; afterTurn = when this turn concludes). */
	injectMode(): Promise<InjectMode>;
	setInjectMode(m: InjectMode): Promise<InjectMode>;
	// ---- M19-1 ContextBudget ----
	/**
	 * Per-item token breakdown of the LAST request, or null before the first turn.
	 *
	 * The renderer must NOT compute tokens itself: the request also carries the system prompt and
	 * the full tool definitions, so a panel that counts visible text reports a different number
	 * than the provider was billed. This is the only truthful source.
	 */
	contextManifest(): Promise<ContextManifest | null>;
	/** Per-item switches. Absent = on, so a newly added item is never silently off. */
	contextTogglesSet(t: Partial<Record<ContextKey, boolean>>): Promise<Partial<Record<ContextKey, boolean>>>;
	contextTogglesGet(): Promise<Partial<Record<ContextKey, boolean>>>;
	/** Recent per-request ledger, oldest first, for the panel's history view. */
	contextHistory(limit?: number): Promise<ContextManifest[]>;
	// ---- M20-1 declarative workflows ----
	workflowList(): Promise<WorkflowInfo[]>;
	/** Validate WITHOUT running, so an editor can show errors before anything is dispatched. */
	workflowValidate(w: unknown): Promise<{ ok: boolean; errors: string[] }>;
	/** Start a run. Returns the journal id; progress arrives via onWorkflow. */
	workflowRun(workflowId: string, task: string): Promise<{ ok: boolean; runId?: string; error?: string }>;
	/** Replay a journal. Calls no agent and writes nothing — that is the point of it. */
	workflowReplay(runId: string): Promise<{ ok: boolean; text?: string }>;
	workflowRuns(limit?: number): Promise<WorkflowRunInfo[]>;
	onWorkflow(cb: (e: WorkflowEvent) => void): () => void;
	// ---- M20-3 fuzzy requirement -> team proposal (a PROPOSAL; it dispatches nothing) ----
	teamAssemble(requirement: string, preferTemplates?: string[]): Promise<TeamAssemblyResult>;
	// ---- M20-4 fixed four-section handoff ----
	/** Render without writing, so the editor can show the exact document being produced. */
	handoffPreview(h: unknown): Promise<{ ok: boolean; text?: string; reason?: string }>;
	/** Write into the synced workspace folder. Refused when goal is empty. */
	handoffWrite(h: unknown): Promise<{ ok: boolean; path?: string; reason?: string }>;
	/** Read one back, for reopening a handoff in the editor. */
	handoffRead(name: string): Promise<{ ok: boolean; text?: string }>;
	handoffList(): Promise<HandoffFileInfo[]>;
	// ---- live-data cards (see ListCardQuery) ----
	/**
	 * Run a `list` card's query and return real rows. READ-ONLY by construction: the query can
	 * only name a collection and a closed set of filters, so there is no path from a card to a
	 * write. Re-sending `result.query` verbatim is how "refresh" works.
	 *
	 * Rejects when the query is malformed rather than returning an empty list — an empty list and
	 * "your filter matched nothing" look identical, and only one of them is true.
	 */
	listQuery(q: unknown): Promise<ListCardResult>;
	// ---- card actions (see CardActionRequest) ----
	/**
	 * Run a declared card action as a REAL tool call through the registry and its risk gate.
	 *
	 * confirm/danger tools pop the same approval card a model-initiated call does (120s
	 * auto-deny), and the user may edit the args on that card before allowing — that edit
	 * IS the parameter control. The tool cannot be one the card spec did not declare, and a
	 * domain switch that hides the tool refuses the action rather than routing around it.
	 */
	cardAction(req: CardActionRequest): Promise<CardActionRequestResult>;
	// ---- card entities (see CardEntity in shared/cards.ts) ----
	/**
	 * Stored cards. `list` view cards survive their source row (they are what the sidebar
	 * reopens); proposal cards live exactly as long as the message that proposed them — pass
	 * `rowId` to read one message's proposals, `sessionId` for a whole conversation.
	 */
	cardList(q?: CardListQuery): Promise<CardEntity[]>;
	/** Pin for the sidebar. Never bumps updatedAt — pinning is not activity (session-pin parity). */
	cardPin(id: string, pinned: boolean): Promise<CardOpResult>;
	/**
	 * Remove a VIEW card — tombstoned afterwards so the next row rescan cannot resurrect it
	 * from a fence that is still in the transcript. A PROPOSAL card REFUSES with a reason:
	 * while its message lives, deleting would be undone by the very next capture, so an honest
	 * no beats a delete that undoes itself. Rollback / clear are what really remove proposals.
	 */
	cardRemove(id: string): Promise<CardOpResult>;
}

export interface TeamStepAssignment {
	intent: 'research' | 'plan' | 'record' | 'review' | 'test' | 'implement';
	agentId: string;
	agentName: string;
	/** 0..1. The WEAKEST step's value is surfaced separately; do not average it away. */
	confidence: number;
	/** Why this agent, already worded for display. */
	because: string;
}
export interface TeamAssemblyResult {
	ok: boolean;
	reason?: 'no-agents' | 'no-template-fits' | 'requirement-too-short' | 'no-capable-agent';
	/** Human-readable explanation of a refusal. Show it; do not invent a team anyway. */
	detail?: string;
	template?: { key: string; name: string; description: string };
	steps?: TeamStepAssignment[];
	/** Lowest step confidence — the weak link. */
	weakest?: number;
	/** Set when we fell back or the team is imperfect; show it, do not hide it. */
	warnings?: string[];
	/** Ready to hand to workflowRun. Only meaningful when ok is true. */
	workflow?: { id: string; name: string; task: string; steps: { id: string; role: string; prompt: string }[] };
}
export interface HandoffFileInfo {
	name: string;
	/** Byte size; the UI can decide whether to offer 'open'. */
	bytes: number;
}

// ---- M20-1: declared here, not imported from main/ai/workflow.ts ----
// tsconfig.web.json only includes src/renderer/** and shared/**, so a shared->main import
// would break the web typecheck for every renderer file touching ChatApi. Same pattern as
// ContextManifest above; src/main/ai/float/conformance.ts is where the two are proven equal.
export interface WorkflowStepInfo {
	id: string;
	role: string;
	prompt: string;
	optional?: boolean;
}
export interface WorkflowInfo {
	id: string;
	name: string;
	task: string;
	steps: WorkflowStepInfo[];
	/** True when a user file shadows a built-in, so the UI can say which one is live. */
	overridden?: boolean;
	/**
	 * Why this workflow cannot run: bad JSON, or the validation errors that blocked it.
	 *
	 * Optional but load-bearing: a file that fails to parse must still APPEAR in the list, with a
	 * reason. Dropping it silently means the list and reality disagree, and the user is left
	 * watching a workflow they wrote simply not be there — with no indication it was ever seen.
	 * `steps` alone cannot say "broken", it only says "empty".
	 */
	problems?: string[];
}
export interface WorkflowEvent {
	runId: string;
	stepId: string;
	role: string;
	status: 'running' | 'done' | 'failed';
	error?: string;
}
export interface WorkflowRunInfo {
	runId: string;
	workflowId: string;
	workflowName: string;
	task: string;
	status: 'done' | 'failed' | 'running';
	steps: { stepId: string; role: string; status: string }[];
	failedAt?: string;
	startedAt: number;
	endedAt: number;
}
