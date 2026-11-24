/* Card store (stage 2 of the card plan): cards as FIRST-CLASS persisted entities.
 *
 * WHERE CARDS USED TO LIVE: fenced inside an assistant row's text and nowhere else. That was
 * fine while a card was decoration on one message, and wrong the moment the plan made a `list`
 * view a thing the sidebar reopens: anything you cannot list, pin or delete is not a thing —
 * it is a rendering detail. So rows stay the SOURCE (the model still writes fences; nothing
 * new to teach it) and this store is the INDEX: capture turns a settled row's closed fences
 * into entities; the renderer reads them through chat:cardList instead of re-parsing
 * transcripts. The catalogue half stays in shared/cards.ts, the validator in card-registry.ts;
 * this file adds only persistence and the lifecycle rules.
 *
 * CAPTURE LIVES AT THE ROWBRIDGE, not here — RowBridge is the single writer of rows, so it is
 * the one place that has the final text (the repair loop's rewritten text lands through it
 * too), the sessionId (a provider, evaluated at call time: main bridge = active session, a
 * run's bridge = its own) and the rowId. The bridge calls capture/reconcile through an
 * optional sink; this store never imports the bridge.
 *
 * TWO LIFECYCLES, one rule each (declared on the spec, see CardSpec.lifecycle):
 *   - proposal — dies with its row: rollback / clear drop the row, reconcile drops the card.
 *     A proposal outliving its message would display an opinion no turn owns any more.
 *   - view     — survives its row ON PURPOSE; that exemption is the plan's "persistent"
 *     made literal. Deleting one TOMBSTONES it, because the next rescan of a live row would
 *     otherwise capture it straight back: a delete that undoes itself is worse than none.
 *
 * FILE SAFETY follows jsonfile.ts, not the tempting `try { parse } catch { [] }` first draft:
 * a corrupt cards.json is QUARANTINED (moved aside), never answered with "no cards" — that
 * answer is exactly how the next capture overwrites the original with an empty array. Writes
 * are chained (one at a time, serialised late, tmp + rename), so a crash or a peer never sees
 * a half file.
 *
 * Ids are positional — `c_<sessionId>_<rowId>_<fence index>` — so re-scanning the same row
 * yields the same ids and capture UPDATEs in place instead of duplicating. That determinism
 * is what makes rescanning on every settle / replaceAll (a cheap heal for crashes and edited
 * rows) safe to do as often as we do.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { cardSpec, CARD_FENCE_BLOCK, CARD_FENCE_MENTION } from '../../../shared/cards';
import type { CardEntity } from '../../../shared/cards';
import type { CardListQuery, CardOpResult } from '../../../shared/chat';
import { validateCard } from './card-registry';
import { readJsonFile, writeJsonFile } from '../jsonfile';

/** The on-disk shape. `removed` is keyed by session FIRST so dropSession can take its
 *  tombstones with it — a flat id→ts map cannot say which session a key belonged to
 *  without parsing ids, and ids are positional, not structured. */
interface StoredFile {
	cards: CardEntity[];
	removed: Record<string, Record<string, number>>;
}

const EMPTY: StoredFile = { cards: [], removed: {} };

/** Minimal shape gate on load. The file is written only by this store, so an entry missing
 *  these is not "an old version" — it is damage, and indexing it would crash later at a
 *  place far from the cause. Skipped loudly (console.error), never silently. */
function isEntity(c: unknown): c is CardEntity {
	if (typeof c !== 'object' || c === null) { return false; }
	const e = c as Record<string, unknown>;
	return typeof e.id === 'string' && e.id.length > 0
		&& typeof e.kind === 'string' && e.kind.length > 0
		&& typeof e.sessionId === 'string' && typeof e.rowId === 'string'
		&& typeof e.at === 'number';
}

const cardId = (sessionId: string, rowId: string, fenceIndex: number): string =>
	`c_${sessionId}_${rowId}_${fenceIndex}`;

/**
 * Derive lifecycle from the CATALOGUE, never from a stored copy (CardEntity deliberately
 * does not carry one). Unknown kind — the spec was removed since capture — degrades to
 * 'proposal': the conservative answer, because a card nobody can classify dying with its row
 * is recoverable (the row is gone anyway) while a ghost view card is not.
 */
const lifecycleOf = (c: CardEntity): 'proposal' | 'view' => cardSpec(c.kind)?.lifecycle ?? 'proposal';

export class CardStore {
	private cards: CardEntity[] = [];
	private byId = new Map<string, CardEntity>();
	/** sessionId → rowId → card ids, so a row-scoped rescan/prune never walks the whole array. */
	private byRow = new Map<string, Map<string, Set<string>>>();
	private removed: Record<string, Record<string, number>> = {};
	/** Single-flight load handle (sessions.ts ensureLoaded parity): two callers arriving
	 *  together must share ONE snapshot instead of each parsing the file their own way. */
	private loading: Promise<void> | null = null;
	/** Mutation chain — call order is application order. */
	private chain: Promise<void> = Promise.resolve();
	/** Write chain — SEPARATE from `chain` on purpose: a mutation awaits its own write, so a
	 *  shared chain would deadlock (the write would be queued behind the mutation awaiting it). */
	private writing: Promise<void> = Promise.resolve();

	constructor(private readonly dataDir: string) { }

	private get file(): string { return path.join(this.dataDir, 'cards.json'); }

	private ensureLoaded(): Promise<void> {
		if (!this.loading) {
			this.loading = this.loadOnce().catch(e => { this.loading = null; throw e; });
		}
		return this.loading;
	}

	private async loadOnce(): Promise<void> {
		await fs.mkdir(this.dataDir, { recursive: true });
		// readJsonFile quarantines unparseable bytes itself (ENOENT → empty is the only
		// silent fallback allowed). What it cannot know is a file that PARSES but is not our
		// shape: answering "empty" for that would be the same silent-loss move it exists to
		// stop, so that case throws and leaves the original untouched.
		const read = await readJsonFile<StoredFile>(this.file, EMPTY);
		const v = read.value;
		if (typeof v !== 'object' || v === null || Array.isArray(v)
			|| (v.cards !== undefined && !Array.isArray(v.cards))) {
			throw new Error(`cards.json 形状不对（cards 不是对象里的数组），拒绝当空库使用：${this.file}`);
		}
		this.cards = [];
		this.byId = new Map();
		this.byRow = new Map();
		let skipped = 0;
		for (const c of Array.isArray(v.cards) ? v.cards : []) {
			if (!isEntity(c)) { skipped++; continue; }
			this.indexAdd(c);
		}
		if (skipped) { console.error(`[cards] 丢弃 ${skipped} 个形状不对的条目（${this.file}）`); }
		this.removed = (v.removed && typeof v.removed === 'object' && !Array.isArray(v.removed))
			? v.removed : {};
	}

	private indexAdd(c: CardEntity): void {
		this.cards.push(c);
		this.byId.set(c.id, c);
		let rows = this.byRow.get(c.sessionId);
		if (!rows) { rows = new Map(); this.byRow.set(c.sessionId, rows); }
		let ids = rows.get(c.rowId);
		if (!ids) { ids = new Set(); rows.set(c.rowId, ids); }
		ids.add(c.id);
	}

	private indexDrop(c: CardEntity): void {
		this.byId.delete(c.id);
		const ids = this.byRow.get(c.sessionId)?.get(c.rowId);
		ids?.delete(c.id);
		this.cards = this.cards.filter(x => x.id !== c.id);
	}

	/** Queue one mutation. Order of calls = order of application; the caller gets the REAL
	 *  outcome (a failed save must surface), while the shared chain stays recoverable —
	 *  otherwise one EUSY on Windows rejects the handle and every later op fails behind it. */
	private run<T>(fn: () => Promise<T>): Promise<T> {
		const task = this.chain.then(fn);
		this.chain = task.then(() => { }, () => { /* see above */ });
		return task;
	}

	/** Write the file, one write at a time, serialised LATE: JSON.stringify happens when the
	 *  queued write executes, so the last writer serialises the store as it is THEN rather
	 *  than a snapshot taken before a sibling mutation landed (sessions.ts persist parity). */
	private persist(): Promise<void> {
		const write = this.writing.then(() => writeJsonFile(this.file, {
			cards: this.cards,
			removed: this.removed,
		}));
		this.writing = write.catch(() => { /* chain stays alive; caller still sees `write` */ });
		return write;
	}

	/**
	 * Capture one row's FINAL text: every closed fence that parses and passes its validator
	 * becomes / refreshes an entity, and this row's previously-stored cards that the text no
	 * longer supports are pruned. So an entity mirrors exactly what its row currently says —
	 * a repaired row re-captures, a fence deleted from the text takes its card with it.
	 *
	 * Tombstoned ids are skipped (they stay dead — see remove()). Returns how many entities
	 * changed; 0 means the persist was skipped too, which is what keeps rescans cheap.
	 */
	async capture(sessionId: string, rowId: string, text: string): Promise<number> {
		return this.run(async () => {
			await this.ensureLoaded();
			// Cheap gate: no card fence anywhere in the text AND this row owns no cards →
			// nothing to parse, nothing to prune, no write. (The mention regex is non-global,
			// so .test carries no lastIndex state.)
			const mayHaveFence = CARD_FENCE_MENTION.test(text);
			const owned = this.byRow.get(sessionId)?.get(rowId);
			if (!mayHaveFence && !owned?.size) { return 0; }

			const seen = new Set<string>();
			let changed = 0;
			if (mayHaveFence) {
				// A FRESH regex per scan: the shared CARD_FENCE_BLOCK is /g and carries
				// lastIndex; reusing it would make one scan continue another's.
				const re = new RegExp(CARD_FENCE_BLOCK.source, 'g');
				let fenceIndex = 0;
				for (const m of text.matchAll(re)) {
					const i = fenceIndex++;
					const kind = m[1];
					const body = m[2];
					const spec = cardSpec(kind);
					if (!spec) { continue; } // regex is built from the catalogue — trust nothing anyway
					const id = cardId(sessionId, rowId, i);
					if (this.removed[sessionId]?.[id]) { continue; } // tombstoned: stays dead
					let raw: unknown;
					try { raw = JSON.parse(body); } catch { continue; } // broken JSON: not a card
					if (validateCard(kind, raw) !== null) { continue; } // invalid never enters the store
					seen.add(id);
					const existing = this.byId.get(id);
					if (!existing) {
						const now = Date.now();
						this.indexAdd({
							id, kind, schemaVersion: spec.version, sessionId, rowId,
							payload: raw, at: now, updatedAt: now,
						});
						changed++;
					} else if (existing.kind !== kind
						|| existing.schemaVersion !== spec.version
						|| JSON.stringify(existing.payload) !== JSON.stringify(raw)) {
						// In place: `at` and `pinned` survive a content edit, updatedAt marks it.
						existing.kind = kind;
						existing.schemaVersion = spec.version;
						existing.payload = raw;
						existing.updatedAt = Date.now();
						changed++;
					}
				}
			}
			// Row-scoped prune: cards this row's CURRENT text no longer supports go — including
			// kinds that used to be valid here and now fail validation (they were captured under
			// the old text; the fence is gone or broken, so the card is too).
			for (const id of [...(owned ?? [])]) {
				if (seen.has(id)) { continue; }
				const c = this.byId.get(id);
				if (c) { this.indexDrop(c); changed++; }
			}
			if (changed) { await this.persist(); }
			return changed;
		});
	}

	/**
	 * Rows changed under us (rollback / clear / load): a PROPOSAL card whose row is gone goes
	 * with it, a VIEW card stays — the exemption is the plan's "persistent" made literal, and
	 * the reason reconcile exists at all: without it, rolling back a message leaves the
	 * sidebar showing a proposal for a question that is no longer asked.
	 */
	async reconcile(sessionId: string, liveRowIds: ReadonlySet<string>): Promise<number> {
		return this.run(async () => {
			await this.ensureLoaded();
			let dropped = 0;
			for (const c of [...this.cards]) {
				if (c.sessionId !== sessionId || lifecycleOf(c) === 'view') { continue; }
				if (liveRowIds.has(c.rowId)) { continue; }
				this.indexDrop(c);
				dropped++;
			}
			if (dropped) { await this.persist(); }
			return dropped;
		});
	}

	/** Filtered read. Awaits the mutation chain first, so a list() called right behind a
	 *  capture sees that capture — the renderer must never read a stale answer it can act on. */
	async list(q: CardListQuery = {}): Promise<CardEntity[]> {
		await this.chain;
		await this.ensureLoaded();
		return this.cards
			.filter(c => (!q.sessionId || c.sessionId === q.sessionId)
				&& (!q.rowId || c.rowId === q.rowId)
				&& (!q.kind || c.kind === q.kind))
			// Pinned first, then most recently changed. Shallow copies: the caller gets our
			// data, not our mutable objects (IPC serialises anyway, but tests and future
			// in-process callers should not be able to mutate the store by holding a reference).
			.map(c => ({ ...c }))
			.sort((a, b) => Number(b.pinned === true) - Number(a.pinned === true)
				|| b.updatedAt - a.updatedAt);
	}

	async pin(id: string, pinned: boolean): Promise<CardOpResult> {
		return this.run(async () => {
			await this.ensureLoaded();
			const c = this.byId.get(id);
			if (!c) { return { ok: false, reason: '卡片不存在' }; }
			// updatedAt deliberately NOT bumped (sessions.ts pin parity): pinning is not
			// activity, and bumping it would let a pin reorder the list by recency.
			const want = pinned ? true : undefined;
			if ((c.pinned === true) === pinned) { return { ok: true }; } // no-op, no write
			if (want === undefined) { delete c.pinned; } else { c.pinned = want; }
			await this.persist();
			return { ok: true };
		});
	}

	async remove(id: string): Promise<CardOpResult> {
		return this.run(async () => {
			await this.ensureLoaded();
			const c = this.byId.get(id);
			if (!c) { return { ok: false, reason: '卡片不存在' }; }
			if (lifecycleOf(c) === 'proposal') {
				// Refuse rather than delete-and-pretend: the fence is still in its row, so the
				// very next settle / replaceAll would capture the card straight back. Rollback
				// or clear removes it for real (their reconcile), and that is what the reason
				// below tells the user.
				return {
					ok: false,
					reason: '提案卡属于产生它的那条消息：只要消息还在，删除会在下一次扫描时被原样带回。'
						+ '要清掉它，请回滚或清空会话。',
				};
			}
			this.indexDrop(c);
			// Tombstone: without it, capture on the NEXT settle resurrects this id from the
			// fence that is still in the transcript. Nested by session so dropSession can take
			// it along (see StoredFile.removed).
			(this.removed[c.sessionId] ??= {})[id] = Date.now();
			await this.persist();
			return { ok: true };
		});
	}

	/** The session itself is gone — ALL its cards go, view cards included: persistence is
	 *  persistence FOR something, and there is no sidebar left once the session is deleted. */
	async dropSession(sessionId: string): Promise<number> {
		return this.run(async () => {
			await this.ensureLoaded();
			let dropped = 0;
			for (const c of [...this.cards]) {
				if (c.sessionId !== sessionId) { continue; }
				this.indexDrop(c);
				dropped++;
			}
			const hadTombstones = this.removed[sessionId] !== undefined;
			if (hadTombstones) { delete this.removed[sessionId]; }
			if (dropped || hadTombstones) { await this.persist(); }
			return dropped;
		});
	}
}
