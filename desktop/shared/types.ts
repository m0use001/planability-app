/* Planability desktop shared entity types — schema is identical to the COD extension
 * store (and mirrors the phone Room tables as a subset), so export/import dumps stay
 * interchangeable across phone / COD / desktop. */

export interface Entity {
	id: string;
	createdAt: number;
	updatedAt: number;
	deletedAt?: number;
}

export interface Schedule extends Entity {
	title: string;
	date?: string; // YYYY-MM-DD (all-day)
	startIso?: string; // full timestamp
	endIso?: string;
	note?: string;
	project?: number;
	/** 1 高 / 2 中 / 3 低 — timeline block color (phone schedule.priority parity). */
	priority?: number;
	/**
	 * 跨端 repeat 契约（2026-10-31 assign，语法逐字一致）：`daily` / `weekly:1,3`（1=周一…7=周日）/
	 * `biweekly:1,3` / `monthly`（锚点日号）/ `monthlyW:2:3`（每月第 2 个周三）/ `yearly` /
	 * `interval:14`，可跟 `;until:<epochDay>`（0/缺省=永久）。解析与展开引擎在 ai/recurrence.ts，
	 * 与手机 data/Recurrence.kt 同语义。锚点 = 本条的 date。
	 */
	repeat?: string;
	/** 分类标签（自由名，PC 无分类管理 UI——与手机「tag 必须已存在」不同，见契约文档 D3 注记）。 */
	tag?: string;
	/** 自由标签串（手机 labels parity）。 */
	labels?: string;
}

export interface Todo extends Entity {
	title: string;
	date?: string;
	/** HH:MM — optional time of day; timed todos appear on the home timeline (phone due>0 parity). */
	time?: string;
	priority?: number;
	status?: 'open' | 'done';
	project?: number;
	note?: string;
	place?: string;
	/** 手机 due parity：todo_create/todo_edit 的 `due` 参数归一到这里的 HH:MM。 */
	/** repeat 契约同 Schedule.repeat（锚点 = 本条的 date）。 */
	repeat?: string;
	/** 分类标签（自由名，PC 无分类管理 UI——与手机「tag 必须已存在」不同，见契约文档注记）。 */
	tag?: string;
	/** 自由标签串（手机 labels parity）。 */
	labels?: string;
}

export interface Note extends Entity {
	title: string;
	body: string;
	pinned?: boolean;
	project?: number;
	/** Owning notebook (phone notebooks→notes tree parity). Notes without one fall back to the default notebook. */
	notebookId?: string;
}

/** Notebook with cover parameters (phone NotebookEntity parity, labels omitted). */
export interface Notebook extends Entity {
	name: string;
	/** Cover style 0..10 procedural / 90 custom text (phone NotebookCover parity). */
	coverStyle?: number;
	/** Cover hue (art color, does not follow the theme). */
	coverHue?: number;
	/** Custom cover text (style=90). */
	coverText?: string;
	/** Custom cover layout 0 grid / 1 stagger / 2 diagonal / 3 centered. */
	coverLayout?: number;
}

/**
 * Who a memory is for, and where it lives.
 *
 * `user` follows the person across every project; `workspace` is visible only from the
 * workspace that wrote it. Declared here rather than only in memory-log.ts because the ENTITY
 * carries it too — `Memory.scope` and `MemoryEvent.scope` are the same question, so they get
 * one spelling rather than a second copy that drifts a field at a time.
 */
export type MemoryScope = 'user' | 'workspace';

export interface Memory extends Entity {
	name: string;
	description: string;
	body: string;
	type?: 'project' | 'feedback' | 'reference' | 'handoff';
	importance?: number; // 0..1
	accessCount?: number;
	lastAccessAt?: number;
	inIndex?: boolean;
	links?: string[];
	/**
	 * Optional on purpose: this file is the dump interchange schema (phone / COD / desktop all
	 * read it), so an added field has to be one a foreign reader can ignore. Both default the way
	 * the log does — absent scope reads as 'user', which is how every row written before this
	 * existed behaved.
	 *
	 * They were missing while `MemoryView` and `MemoryEvent` already had them, so the projection
	 * upsert had nothing to write and the memory page could not show a row's scope at all.
	 */
	scope?: MemoryScope;
	/** Workspace root this row belongs to; ''/absent for user scope. */
	workspace?: string;
}

/**
 * dump v2：L2 场景（手机 memory_scenes 的投影）。
 *
 * 为什么 id 用 `scene_<name>`：名字在手机端唯一，PC 的 mergeDump 又「赢家保留本地 id」，
 * 所以第二条同步起 id 就对不上了——真正保证跨端同一条的是**自然键 name**（见 Store.naturalKey）。
 * id 只是首轮的助记，不承担身份。
 */
export interface MemoryScene extends Entity {
	name: string;
	summary: string;
	/** 场景热度：新建 1，每次合并 +1。 */
	heat?: number;
	/** 归属项目（手机项目 id 的字符串形式；'' = 全局）。 */
	project?: string;
}

/** dump v2：L3 画像（单行，id 恒为 `persona`，自然键也是它）。 */
export interface MemoryPersona extends Entity {
	body: string;
	version?: number;
}

/**
 * dump v2：记忆 → 原始消息的证据边。
 *
 * `memory` 是**记忆名**而不是记忆 id：PC 合并时会保留本地 id，用手机 id 关联会在第一次
 * 合并后就断。消息 id 本身在 PC 没有对应行（聊天不同步），所以这里它能表达的是
 * 「这条记忆由哪几轮支撑」——要还原原话仍需手机端数据。
 */
export interface MemoryEvidence extends Entity {
	memory: string;
	messageId: number;
	kind?: string;
}

/**
 * How a memory changed. The log is append-only, so a row is the fold of these rather than an
 * edit in place — which is what makes an erase auditable: `forget` hides the row from the view
 * and keeps the event forever.
 *
 * Declared here, not in memory-log.ts, because the renderer reads `history(key)`/`all()` and
 * needs a type for what comes back. The previous arrangement was the same failure as
 * `AutomationKind` and `TaskLogEntry`: the value existed at runtime while the type it needed
 * to be rendered lived somewhere the renderer could not name.
 */
export type MemoryEventKind =
	/** First version of a memory. */
	| 'add'
	/** Body/description changed. Appended, never applied in place. */
	| 'update'
	/** Importance or type changed. */
	| 'revise'
	/** Soft delete: the row disappears from the view, the event stays. */
	| 'forget'
	/** Restored after a forget. */
	| 'restore';

export interface MemoryEvent {
	/** Monotonic per store. Ordering key — never compare `at`. */
	seq: number;
	kind: MemoryEventKind;
	/** Stable identity across updates, so the log folds into a view. */
	key: string;
	name: string;
	body: string;
	description: string;
	type: 'project' | 'feedback' | 'reference' | 'handoff';
	importance: number;
	scope: MemoryScope;
	/** Workspace root at write time, for workspace scope. Ignored for user scope. */
	workspace?: string;
	/** Display timestamp only. Never use for ordering. */
	at: number;
	/** Where it came from: an explicit tool call, or post-turn auto-extraction. */
	source: 'tool' | 'auto' | 'import';
	/** Why the auto-extraction proposed this. Shown in the inspection UI. */
	reason?: string;
}

export interface Project extends Entity {
	name: string;
	emoji?: string;
	/**
	 * 项目根在 Syncthing 共享夹内的位置（跨端同步方案 P0-b，2026-11-09）。
	 * 手机端导出：folderId=包含项目根的那个共享夹 id（planability-data 或用户手动加的夹），
	 * rel=项目根相对该夹的路径（如 `编程/C++编程`）。项目根不在任何共享夹内则省略。
	 * PC 消费：用自己的 Syncthing 文件夹配置把 folderId 映射成本地路径再拼 rel——
	 * 两端跑同一 Syncthing，folder id 一致，PC 无需手工配置。
	 * dirPath（PC 本地绑定）不进 dump：手机路径在 PC 无意义，各端各存。
	 */
	sync?: { folderId: string; rel: string };
}

/** Sub-agent (phone AssistantsScreen parity): a persona wrapping extra system prompt. */
export interface Assistant extends Entity {
	key: string;
	name: string;
	color: string;
	slogan?: string;
	systemPrompt: string;
	temperature?: number;
	builtin?: boolean;
}

/**
 * The single source of truth for the collection set; `CollectionName` is DERIVED from it.
 *
 * This exists because `exportDump` carried its own hardcoded six-entry list and silently left
 * `assistants` out of every backup and every sync file — two independent reviewers found that
 * one independently, and neither would have found it if the set were declared in one place.
 * Deriving the type makes "a collection that exists but is never exported" impossible to write.
 */
export const COLLECTIONS = [
	'schedules', 'todos', 'notes', 'notebooks', 'memories', 'projects', 'assistants',
	// dump v2（2026-10-30）：手机端 v45 记忆分层。证据按**记忆名**关联，不按 id——
	// mergeDump 的规则是「赢家保留本地 id」，手机 id 在 PC 合并后不一定还在。
	'memory_scenes', 'memory_persona', 'memory_evidence',
] as const;

export type CollectionName = (typeof COLLECTIONS)[number];

/**
 * dump 版本：1 = 七集合；2 = 追加 memory_scenes / memory_persona / memory_evidence。
 * 两端都必须**同时接受** 1 和 2（旧手机 ↔ 新 PC 的过渡期里，只有一边升就断）。
 */
export type DumpSchemaVersion = 1 | 2;

export interface Dump {
	schemaVersion: DumpSchemaVersion;
	exportedAt: number;
	entity: Partial<Record<CollectionName, Entity[]>>;
	kv?: Record<string, string>;
}

export interface DataStats {
	schedules: number;
	todos: number;
	todosOpen: number;
	notes: number;
	notebooks?: number;
	memories: number;
	projects: number;
}
