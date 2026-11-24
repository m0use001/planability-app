/* Live-data cards: a `list` card carries a QUERY, and this is where that query is checked and run.
 *
 * WHY A QUERY AND NOT CONTENT. Every other card holds JSON the model wrote, so the user is looking
 * at a proposal — a schedule the model invented, a checklist it composed. A `list` card names a
 * collection and a filter instead, and what you see is what is actually in your store. It also
 * makes refresh free: re-send the query, get the current answer, and a card cannot go stale the
 * way a snapshot does.
 *
 * WHY THE SCHEMA IS CLOSED AND STRICT. An unknown key is an ERROR, not something to ignore. The
 * tempting version ignores what it does not recognise and shows the closest rows it can — and then
 * the user is looking at the wrong data with nothing on screen saying a filter was dropped. For the
 * same reason `validateCard` reports the problem instead of degrading quietly: a card that silently
 * shows the wrong list is worse than a card that says it could not be understood.
 *
 * It is READ-ONLY by construction, not by convention: the query can only name a collection and a
 * closed set of filters, so there is no path from a card to a write. Anything that can change data
 * goes through the tool registry, with its risk level, its approval card and its undo entry.
 */
import type { ListCardQuery, ListCardResult, ListCardRow } from '../../../shared/chat';
import type { CollectionName } from '../../../shared/types';
import { COLLECTIONS } from '../../../shared/types';
import type { Entity } from '../store/store';

export const LIST_LIMIT_DEFAULT = 20;
export const LIST_LIMIT_MAX = 50;

/** The query as executed: `limit` made concrete, everything else filled with defaults. */
export type NormalizedListQuery = ListCardQuery & { limit: number };

type Where = NonNullable<ListCardQuery['where']>;

/**
 * Which filters each collection actually has. Deliberately short — a filter is only listed here if
 * the field exists on that entity, so the card cannot promise something the data cannot answer.
 * memories/projects/notebooks/assistants have no listed filter in v1; that is an honest "not yet",
 * not an oversight.
 */
const WHERE_ALLOWED: Record<CollectionName, readonly (keyof Where)[]> = {
	schedules: ['date', 'project'],
	todos: ['status', 'date', 'project'],
	notes: ['pinned', 'project'],
	memories: [],
	projects: [],
	notebooks: [],
	assistants: [],
	// dump v2：分层记忆资产没有 where 维度，列出来只为满足 Record 的穷尽性
	memory_scenes: [],
	memory_persona: [],
	memory_evidence: [],
};

const TOP_KEYS = ['collection', 'where', 'order', 'limit'] as const;
const isObj = (v: unknown): v is Record<string, unknown> =>
	typeof v === 'object' && v !== null && !Array.isArray(v);
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export type NormalizeResult =
	| { ok: true; query: NormalizedListQuery; error?: undefined }
	| { ok: false; query?: undefined; error: string };

/** Pure validation + normalization. No store, no I/O — so it is directly testable. */
export function normalizeListQuery(raw: unknown): NormalizeResult {
	if (!isObj(raw)) { return { ok: false, error: 'list 卡片必须是一个 JSON 对象' }; }

	for (const k of Object.keys(raw)) {
		if (!(TOP_KEYS as readonly string[]).includes(k)) {
			return { ok: false, error: `未知字段 ${k}（只允许 ${TOP_KEYS.join(' / ')}）` };
		}
	}

	const collection = raw.collection;
	if (typeof collection !== 'string' || !(COLLECTIONS as readonly string[]).includes(collection)) {
		return { ok: false, error: `collection 必须是 ${COLLECTIONS.join(' / ')} 之一` };
	}
	const coll = collection as CollectionName;

	const where: Record<string, unknown> = {};
	if (raw.where !== undefined && raw.where !== null) {
		if (!isObj(raw.where)) { return { ok: false, error: 'where 必须是一个对象' }; }
		const allowed = WHERE_ALLOWED[coll];
		for (const [k, v] of Object.entries(raw.where)) {
			if (!(allowed as readonly string[]).includes(k)) {
				return {
					ok: false,
					error: allowed.length
						? `${coll} 不支持筛选 ${k}（可用：${allowed.join(' / ')}）`
						: `${coll} 暂不支持任何筛选`,
				};
			}
			const bad = checkWhereValue(k, v);
			if (bad) { return { ok: false, error: `where.${k} ${bad}` }; }
			where[k] = v;
		}
	}

	let order: 'updated' | 'created' | undefined;
	if (raw.order !== undefined && raw.order !== null) {
		if (raw.order !== 'updated' && raw.order !== 'created') {
			return { ok: false, error: "order 只能是 'updated' 或 'created'" };
		}
		order = raw.order;
	}

	let limit = LIST_LIMIT_DEFAULT;
	if (raw.limit !== undefined && raw.limit !== null) {
		if (typeof raw.limit !== 'number' || !Number.isFinite(raw.limit)) {
			return { ok: false, error: 'limit 必须是数字' };
		}
		// Clamp rather than reject: a model asking for 5000 rows meant "as many as you can show",
		// and answering with an error helps nobody. The applied value comes back in the echo, so
		// the card can say what it actually got.
		limit = Math.min(LIST_LIMIT_MAX, Math.max(1, Math.round(raw.limit)));
	}

	return {
		ok: true,
		query: {
			collection: coll,
			...(Object.keys(where).length ? { where: where as Where } : {}),
			...(order ? { order } : {}),
			limit,
		},
	};
}

function checkWhereValue(key: string, v: unknown): string | null {
	switch (key) {
		case 'status':
			return v === 'open' || v === 'done' ? null : "必须是 'open' 或 'done'";
		case 'date':
			return typeof v === 'string' && DATE_RE.test(v) ? null : '必须是 YYYY-MM-DD';
		case 'pinned':
			return typeof v === 'boolean' ? null : '必须是 true 或 false';
		case 'project':
			return typeof v === 'number' && Number.isFinite(v) ? null : '必须是数字';
		default:
			return '不支持的筛选字段';
	}
}

/** Anything that can list a collection. Deliberately structural, so the ordering/label logic can
 *  be tested with a plain object and no Store (and no disk). */
export interface ListSource {
	live: (c: CollectionName) => Promise<Entity[]>;
}

/**
 * Run a normalized query. Split from `normalizeListQuery` so the ordering/label logic is testable
 * with plain arrays and no store at all.
 */
export async function runListQuery(src: ListSource, q: NormalizedListQuery): Promise<ListCardResult> {
	const all = await src.live(q.collection);
	const filtered = all.filter(e => matches(e, q.where));
	const key = q.order === 'created' ? 'createdAt' : 'updatedAt';
	const sorted = [...filtered].sort((a, b) => (b[key] ?? 0) - (a[key] ?? 0));
	return {
		query: q,
		rows: sorted.slice(0, q.limit).map(e => toRow(e, q.collection)),
		total: filtered.length,
	};
}

function matches(e: Entity, where?: Where): boolean {
	if (!where) { return true; }
	const r = e as unknown as Record<string, unknown>;
	if (where.date !== undefined && r.date !== where.date) { return false; }
	if (where.project !== undefined && r.project !== where.project) { return false; }
	if (where.pinned !== undefined && r.pinned !== where.pinned) { return false; }
	if (where.status !== undefined) {
		// An absent status means "not done" — that is how todos are stored, and treating a missing
		// field as "open" is what makes 「未完成」 return the right rows.
		const st = r.status ?? 'open';
		if (st !== where.status) { return false; }
	}
	return true;
}

/** Projection to the display shape. Per-collection, because there is no honest generic version:
 *  a memory's primary label is `name`, a note's is `title`, and pretending otherwise produces a
 *  column of blanks. The collection is passed in rather than sniffed off the entity: entities
 *  carry no collection marker, and an earlier draft switched on a field that does not exist,
 *  which silently sent every row through the fallback. */
function toRow(e: Entity, coll: CollectionName): ListCardRow {
	const r = e as unknown as Record<string, unknown>;
	const s = (v: unknown) => (typeof v === 'string' && v.trim() ? v : undefined);
	const id = String(r.id ?? '');
	switch (coll) {
		case 'schedules':
			return { id, label: s(r.title) ?? '(未命名日程)', detail: s(r.note), when: s(r.startIso) ?? s(r.date) };
		case 'todos':
			return { id, label: s(r.title) ?? '(未命名待办)', when: [s(r.date), s(r.time)].filter(Boolean).join(' ') || undefined };
		case 'notes':
			return { id, label: s(r.title) ?? '(无标题)', detail: clip(s(r.body), 80) };
		case 'memories':
			return { id, label: s(r.name) ?? '(未命名记忆)', detail: s(r.description) };
		case 'notebooks':
			return { id, label: s(r.name) ?? '(未命名笔记本)' };
		case 'assistants':
			return { id, label: s(r.name) ?? s(r.key) ?? id, detail: s(r.slogan) };
		default:
			return { id, label: s(r.name) ?? s(r.title) ?? id };
	}
}

const clip = (s: string | undefined, n: number) => (s && s.length > n ? s.slice(0, n) + '…' : s);