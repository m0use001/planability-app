/* Planability data core: JSON entity store (Room schema subset, PC v1).
 * Ported from COD extension planability-core/src/store.ts — vscode API replaced by
 * node fs + path. Schema mirrors the phone Room tables so export/import dumps stay
 * interchangeable (schemaVersion=1, newer updatedAt wins).
 * Data root: %APPDATA%\planability-desktop\data (injected by main). */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type {
	CollectionName, Dump, Entity,
	Schedule, Todo, Note, Notebook, Memory, Project, Assistant,
	MemoryScene, MemoryPersona, MemoryEvidence,
} from '../../../shared/types';
// A value, not a type — `import type` above cannot carry it. Single source of truth for which
// collections exist, so exportDump cannot drift from CollectionName again.
import { COLLECTIONS } from '../../../shared/types';
import { readJsonFile, writeJsonFile } from '../jsonfile';

export type { CollectionName, Dump, Entity, Schedule, Todo, Note, Memory, Project } from '../../../shared/types';

export class Store {
	private readonly cache = new Map<CollectionName, Entity[]>();
	private readonly kvCache = new Map<string, string>();
	private readonly base: string;
	private dirReady = false;

	constructor(baseDir: string) {
		this.base = baseDir;
	}

	/** Data root (audit jsonl / dumps live here) — read-only for tools. */
	get dataDir(): string { return this.base; }

	static now(): number { return Date.now(); }
	static newId(): string { return `e_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`; }

	private file(name: string): string {
		return path.join(this.base, `${name}.json`);
	}

	private async ensureDir(): Promise<void> {
		if (this.dirReady) { return; }
		await fs.mkdir(this.base, { recursive: true });
		this.dirReady = true;
	}

	async load<T extends Entity>(name: CollectionName): Promise<T[]> {
		const hit = this.cache.get(name);
		if (hit) { return hit as T[]; }
		await this.ensureDir();
		// Not `try { JSON.parse } catch { [] }`. That collapsed "no file yet" and "file we could
		// not read" into one answer, and the next upsert then saved that answer over the original
		// — one parse error away from an empty collection the user never asked for. ENOENT falls
		// back inside readJsonFile; an unparseable file is moved aside first so it survives.
		const { value } = await readJsonFile<T[]>(this.file(name), []);
		this.cache.set(name, value);
		return value;
	}

	private async save(name: CollectionName): Promise<void> {
		await this.ensureDir();
		await writeJsonFile(this.file(name), this.cache.get(name) ?? [], 1);
	}

	async upsert<T extends Entity>(name: CollectionName, item: T): Promise<T> {
		const list = await this.load<T>(name);
		const now = Store.now();
		const existing = list.findIndex(e => e.id === item.id);
		const record = { ...item, id: item.id || Store.newId(), createdAt: item.createdAt || now, updatedAt: now } as T;
		if (existing >= 0) { list[existing] = record; } else { list.push(record); }
		await this.save(name);
		return record;
	}

	async softDelete(name: CollectionName, id: string): Promise<boolean> {
		const list = await this.load(name);
		const item = list.find(e => e.id === id);
		if (!item) { return false; }
		item.deletedAt = Store.now();
		item.updatedAt = Store.now();
		await this.save(name);
		return true;
	}

	async live<T extends Entity>(name: CollectionName): Promise<T[]> {
		return (await this.load<T>(name)).filter(e => !e.deletedAt);
	}

	/** Replace a whole collection (undo restore path). */
	async replace<T extends Entity>(name: CollectionName, items: T[]): Promise<void> {
		this.cache.set(name, items as Entity[]);
		await this.save(name);
	}

	// ---- KV (handoff memory, automation anchors) ----

	async kvGet(key: string): Promise<string | undefined> {
		await this.loadKv();
		return this.kvCache.get(key);
	}

	async kvPut(key: string, value: string): Promise<void> {
		await this.loadKv();
		this.kvCache.set(key, value);
		await this.ensureDir();
		await writeJsonFile(this.file('kv'), Object.fromEntries(this.kvCache), 1);
	}

	private async loadKv(): Promise<void> {
		if (this.kvCache.size) { return; }
		await this.ensureDir();
		// Same collapse as `load`: a corrupt kv.json read as "no keys" meant the next kvPut
		// rewrote the file with one key in it — sync switches, handoff and automation anchors,
		// all of them gone with no error.
		const { value } = await readJsonFile<Record<string, string>>(this.file('kv'), {});
		for (const [k, v] of Object.entries(value)) { this.kvCache.set(k, v); }
	}

	// ---- audit (append-only jsonl) ----

	async audit(tool: string, input: unknown, ok: boolean, ms: number): Promise<void> {
		await this.ensureDir();
		const line = JSON.stringify({ tool, input, ok, ms, at: Store.now() }) + '\n';
		// `appendFile`, not read-then-write. This file is append-only by definition, and the old
		// read-modify-write had two failure modes for no benefit: two concurrent tool calls could
		// interleave and drop each other's lines, and a single read error fell into a catch that
		// then wrote ONLY this line over the whole file — one transient error wiped the audit.
		await fs.appendFile(path.join(this.base, 'tools_audit.jsonl'), line, 'utf8');
	}

	// ---- sync (own dump format v1, newer updatedAt wins) ----

	async exportDump(): Promise<Dump> {
		// Derived from COLLECTIONS, never listed by hand: a handwritten list here dropped
		// `assistants` from every backup while the type still allowed it.
		const entity: Partial<Record<CollectionName, Entity[]>> = {};
		for (const n of COLLECTIONS) { entity[n] = await this.load(n); }
		await this.loadKv();
		// schemaVersion=2（2026-11-09）：COLLECTIONS 已含 v2 三集合，导出却标 1——消费方按版本
		// 分派会漏读三集合。手机端生产者（PhoneDump.kt:68）本就发 2，这里对齐；导入侧 1/2 都收。
		return { schemaVersion: 2, exportedAt: Store.now(), entity, kv: Object.fromEntries(this.kvCache) };
	}

	/** Merge another dump record-by-record: newer updatedAt wins. Returns merged count.
	 *
	 *  Merge rule v2 (pc-a 2026-10-05 decision, spec in
	 *  ◆/planability-cross-end-contract.md "✅ 已拍板"): id first, natural key as
	 *  fallback. v1 matched on id alone, but the phone emits self-incrementing numeric ids
	 *  ("1","2") while PC mints `e_<base36>_<rand>`, so the namespaces never intersect and
	 *  every phone record used to be inserted as a duplicate.
	 *
	 *  Conservative principle: if the natural key matches more than one local record the
	 *  match is ambiguous — we do NOT pair, and insert instead (duplicate beats wrong merge).
	 */
	async mergeDump(dump: Dump): Promise<number> {
		// v2 只是**追加**三个集合；旧手机（v1）与新手机（v2）都必须能合并——
		// 过渡期里两端版本不同步是常态，卡死任何一边都会让同步静默停摆。
		if (dump.schemaVersion !== 1 && dump.schemaVersion !== 2) {
			throw new Error(`未知备份版本 ${dump.schemaVersion}（当前支持 1/2）`);
		}
		let merged = 0;
		const names = Object.keys(dump.entity) as CollectionName[];
		for (const n of names) {
			if (!dump.entity[n]) { continue; }
			const mine = await this.load(n);
			// Index by natural key -> all local records sharing it. A key mapping to exactly
			// one record is a usable fallback pair; more than one is ambiguous (see above).
			const naturalIndex = new Map<string, Entity[]>();
			for (const e of mine) {
				const key = Store.naturalKey(n, e);
				if (!key) { continue; }
				const bucket = naturalIndex.get(key);
				if (bucket) { bucket.push(e); } else { naturalIndex.set(key, [e]); }
			}
			const byId = new Map(mine.map(e => [e.id, e]));
			for (const incoming of dump.entity[n]!) {
				// 1. exact id match always wins.
				let local = byId.get(incoming.id);
				// 2. fall back to a unique natural key match.
				if (!local) {
					const key = Store.naturalKey(n, incoming);
					if (key) {
						const bucket = naturalIndex.get(key);
						if (bucket?.length === 1) { local = bucket[0]; }
					}
				}
				if (local) {
					if (incoming.updatedAt > local.updatedAt) {
						// Winner keeps OUR id: a phone dump must not rewrite local identity,
						// and keeping it makes the next export/import round idempotent.
						const { id: _incomingId, ...fields } = incoming;
						Object.assign(local, fields);
						// Object.assign never removes keys, so a local tombstone would survive a
						// newer incoming record that is alive again -> undeletable forever.
						// "newer wins" applies to deletedAt too: the winner's delete state governs.
						if (incoming.deletedAt === undefined) { delete local.deletedAt; }
					}
					merged++;
				} else {
					mine.push(incoming);
					byId.set(incoming.id, incoming);
					const key = Store.naturalKey(n, incoming);
					if (key) {
						const bucket = naturalIndex.get(key);
						if (bucket) { bucket.push(incoming); } else { naturalIndex.set(key, [incoming]); }
					}
					merged++;
				}
			}
			this.cache.set(n, mine);
			await this.save(n);
		}
		if (dump.kv) {
			for (const [k, v] of Object.entries(dump.kv)) {
				await this.kvPut(k, v);
				merged++;
			}
		}
		return merged;
	}

	/**
	 * Natural key per collection — the v2 fallback identity for cross-end dedup.
	 * Spec: ◆/planability-cross-end-contract.md "✅ 已拍板" section. The phone
	 * mirror lives in data/PhoneDump.kt (phone-a); both sides must stay semantically identical.
	 * Returns '' when the record has no usable key (caller must then fall back to insert).
	 */
	static naturalKey(name: CollectionName, e: Entity): string {
		const trim = (v: unknown): string => typeof v === 'string' ? v.trim() : '';
		switch (name) {
			case 'schedules': {
				const r = e as Schedule;
				const day = trim(r.date) || trim(r.startIso).slice(0, 10);
				return `${trim(r.title)}|${day}|${trim(r.startIso).slice(11, 16)}`;
			}
			case 'todos': {
				const r = e as Todo;
				return `${trim(r.title)}|${trim(r.date)}|${trim(r.time)}`;
			}
			case 'notes': {
				const r = e as Note;
				const title = trim(r.title);
				const body = typeof r.body === 'string' ? r.body : '';
				if (!title && !body) { return ''; }
				// Same-title notes are common, so pair on a 200-char body fingerprint too.
				return `${title}|${body.slice(0, 200)}`;
			}
			case 'notebooks': {
				const name_ = trim((e as Notebook).name);
				return name_ ? name_ : '';
			}
			case 'projects': {
				const name_ = trim((e as Project).name);
				return name_ ? name_ : '';
			}
			case 'memories': {
				const name_ = trim((e as Memory).name);
				return name_ ? name_ : '';
			}
			case 'assistants': {
				const key = trim((e as Assistant).key);
				return key ? key : '';
			}
			case 'memory_scenes': {
				const name_ = trim((e as MemoryScene).name);
				return name_ ? name_ : '';
			}
			case 'memory_persona':
				// 单行资产：id 在两端可能不同（PC 首次导入会生成自己的 id），
				// 用常量自然键保证「永远只有一份画像」。
				return 'persona';
			case 'memory_evidence': {
				const r = e as MemoryEvidence;
				const mem = trim(r.memory);
				return mem ? `${mem}#${r.messageId}` : '';
			}
			default:
				return '';
		}
	}
}
