/* The card catalogue — the ONE declaration both ends read.
 *
 * WHY THIS FILE EXISTS, IN ONE SENTENCE: the card type list was written out four times
 * (cards.ts three times, SYSTEM_PROMPT once, and once more in the renderer's ChatCards.tsx) and
 * `list` was added to three of them, so the UI could not render the card the model was told to emit.
 *
 * WHY IT LIVES IN shared/ AND NOT NEXT TO THE PARSER. tsconfig.web.json includes only
 * src/renderer/** and shared/**, so the renderer cannot import anything under src/main — the same
 * constraint float.ts hit. A catalogue the renderer must agree with cannot live on one side.
 *
 * WHAT IS HERE vs WHAT IS NOT. The declarative half (which cards exist, their schema version, and
 * the prompt fragment that teaches the model) is data, so it belongs here. The validating half is
 * behaviour that needs main-side code, so it stays in src/main/ai/card-registry.ts — keyed by kind,
 * and a test asserts every kind here has one. That split is what stops the two halves drifting: you
 * cannot add a card here without the renderer learning to parse it, and you cannot forget the
 * validator because the coverage check fails.
 */
export interface CardSpec {
	/** The fence language slot — what the model writes after the backticks. */
	kind: string;
	/** Bump when the shape changes incompatibly. A persisted card carries this with it. */
	version: number;
	/** The fragment that teaches the model this card's fields. Ends its prompt sentence. */
	prompt: string;
	/**
	 * PROPOSAL or VIEW — what the card IS, declared where the card is declared.
	 *
	 * A proposal (plan / qa / schedule / checklist) is the model's suggestion attached to ONE
	 * message: when that message's row goes (rollback / clear), the card goes with it — a
	 * proposal surviving its message would display something no turn owns any more, and nobody
	 * can trace it back. A view (`list`) is the dual: it names a query over live data and is
	 * precisely the thing the sidebar reopens, so it deliberately outlives its source row
	 * (card-store's reconcile exempts view cards — that exemption IS "persistent" in the plan).
	 *
	 * This lives HERE rather than as a `kind === 'list'` switch down in the store, because it is
	 * a declarative fact about the card type, next to its version and prompt: adding a card kind
	 * forces this choice at declaration time, where both ends and the tests see it, instead of
	 * somewhere downstream where forgetting it compiles fine.
	 */
	lifecycle: 'proposal' | 'view';
	/**
	 * Buttons that invoke a REGISTERED TOOL directly instead of round-tripping a user
	 * message through the model (stage 3 of the card plan).
	 *
	 * This array names tools — it never carries arguments. Argument building from card
	 * content is behaviour that needs validation, so it lives in main (ai/card-actions.ts);
	 * keeping it out of here is the same declarative/behavioural split this whole file is
	 * built on. The set is CLOSED: a card can only reach tools the registry already gates,
	 * and main resolves `tool` through the registry per call — a domain switch that hides
	 * the tool hides the button's effect too.
	 *
	 * `perItem` means the renderer invokes once per chosen item (with that item's index),
	 * not once for the whole card.
	 */
	actions?: readonly CardAction[];
}

export interface CardAction {
	/** Stable id the renderer sends back with the click. */
	id: string;
	/** Button label, worded for the user. */
	label: string;
	/** Registered tool name. Main resolves it in the registry — it cannot invent one. */
	tool: string;
	/** Invoke once per selected item rather than once for the card. */
	perItem?: boolean;
}

/**
 * A card as the STORE persists it (stage 2: cards are first-class, not just row-embedded).
 *
 * Captured by main (ai/card-store.ts) from a SETTLED assistant row: every closed fence in the
 * row's final text that parses and passes its validator becomes one entity. `id` is derived
 * from position — `c_<sessionId>_<rowId>_<fence index>` — so re-scanning the same row yields
 * the same ids and capture UPDATEs instead of duplicating; that determinism is what lets the
 * bridge rescan on every settle/replaceAll for free.
 *
 * `lifecycle` is deliberately NOT stored: it is derivable from `kind` via CARD_SPECS, and a
 * stored copy would be a second place to drift from the catalogue. `kind` IS stored — it is
 * the payload's own identity, needed before any lookup into the catalogue is possible.
 */
export interface CardEntity {
	/** `c_<sessionId>_<rowId>_<fence index>` — deterministic, so a re-scan upserts. */
	id: string;
	kind: string;
	/** The CardSpec.version the payload was captured under — an old entity can be recognized
	 *  as written by an older schema instead of being silently reinterpreted as today's. */
	schemaVersion: number;
	sessionId: string;
	/** The row it was captured from. Proposal cards die when this row does (reconcile). */
	rowId: string;
	/** The card JSON, parsed AND validated before it was ever stored. */
	payload: unknown;
	/** First capture. */
	at: number;
	/** Last content change (a repaired row re-captures). Pinning does NOT bump this. */
	updatedAt: number;
	/** Sidebar pin. Absent means unpinned — the persisted form keeps the key out entirely. */
	pinned?: boolean;
}

/**
 * The catalogue. Order is the order the prompt paragraph lists them in, so adding a card here
 * changes the prompt, the fence regexes and the renderer's parser together — by construction.
 */
export const CARD_SPECS: readonly CardSpec[] = [
	{
		kind: 'plan',
		version: 1,
		lifecycle: 'proposal',
		prompt: 'plan 的 steps 是 [{name,detail}] 对象数组（也接受纯字符串），title/goal/note 可选',
	},
	{
		kind: 'qa',
		version: 1,
		lifecycle: 'proposal',
		prompt: 'qa 的 questions 是 {q,options[]} 数组',
	},
	{
		kind: 'schedule',
		version: 1,
		lifecycle: 'proposal',
		prompt: 'schedule 的 items 是 {title,start,end,location} 数组',
		// One button per item → schedule_create. Main maps {start,end,location} onto the
		// tool's {startIso,endIso,note}; the card's own field names are NOT the tool's.
		actions: [{ id: 'create', label: '创建这个日程', tool: 'schedule_create', perItem: true }],
	},
	{
		kind: 'list',
		version: 1,
		lifecycle: 'view',
		prompt: 'list 是活数据卡片，只写查询不写内容：{collection, where?, order?, limit?}；'
			+ 'collection 取 schedules/todos/notes/notebooks/memories/projects/assistants；'
			+ 'where 里 todos 可用 status(open|done) 与 date(YYYY-MM-DD)，schedules 可用 date，'
			+ 'notes 可用 pinned，其余可用数字 project；order 取 updated(默认) 或 created；'
			+ '要展示用户已有的东西时一律用它，不要把内容自己编进卡片',
	},
	{
		kind: 'checklist',
		version: 1,
		lifecycle: 'proposal',
		prompt: 'checklist 的 items 是字符串数组',
		// String item → todo_create { title }. The cleanest 1:1 mapping in the catalogue,
		// which is why it ships first alongside schedule.
		actions: [{ id: 'create', label: '建成待办', tool: 'todo_create', perItem: true }],
	},
];

export const CARD_KINDS: readonly string[] = CARD_SPECS.map(s => s.kind);

export function cardSpec(kind: string): CardSpec | undefined {
	return CARD_SPECS.find(s => s.kind === kind);
}

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/**
 * Built from CARD_SPECS on every call.
 *
 * NOT from CARD_KINDS — that array is computed once at module load, so it is a snapshot, and a
 * snapshot is a second place that can disagree with the catalogue. That mistake was made twice in
 * one session: first the regex builder read the `CARD_KINDS` snapshot, and then, after fixing that,
 * the builder still read a `CARD_KINDS` whose own construction had snapshotted. Both times only a
 * test that adds a card at runtime and re-builds caught it.
 */
const alt = () => CARD_SPECS.map(s => esc(s.kind)).join('|');

/** ```kind + optional trailing spaces + newline — the opening fence. */
export function buildFenceOpenRe(): RegExp { return new RegExp('```(' + alt() + ')[ \\t]*\\r?\\n'); }

/** ```kind with no newline requirement — "is a card mentioned here at all". */
export function buildFenceMentionRe(): RegExp { return new RegExp('```(' + alt() + ')'); }

/** A whole closed card: opening fence, body, closing fence. This is the one the RENDERER needs —
 *  it owns parsing, so it is the renderer's job to know the language slots. */
export function buildFenceBlockRe(): RegExp {
	return new RegExp('```(' + alt() + ')[ \\t]*\\r?\\n([\\s\\S]*?)```', 'g');
}

// Prebuilt for hot paths; the builders are the source of truth.
export const CARD_FENCE_OPEN = buildFenceOpenRe();
export const CARD_FENCE_MENTION = buildFenceMentionRe();
export const CARD_FENCE_BLOCK = buildFenceBlockRe();

/** The SYSTEM_PROMPT paragraph that teaches the model to emit cards. Derived, never hand-written. */
export const CARD_PROMPT_SECTION =
	`- 输出计划/清单/日程提案/活数据视图/多选问答时用组件围栏卡片（正文为 JSON，语言槽为 ${CARD_KINDS.join('|')}）：`
	+ CARD_SPECS.map(s => s.prompt).join('；') + '。';