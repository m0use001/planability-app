/* M19-3 memory — the append-only event log behind the memories collection.
 *
 * The memories collection is a MATERIALISED VIEW of this log. That direction matters and is the
 * whole point: a store that only keeps the current row cannot answer "when did we decide this?"
 * or "why is this memory what it is?", and a user who cannot inspect the derivation of their
 * agent's beliefs cannot correct it. So writes go here, and `memories` is rebuilt from them.
 *
 * FOUR DECISIONS THAT ARE NOT OBVIOUS:
 *
 * 1. THE LOG IS APPEND-ONLY, THE VIEW IS NOT. Correcting a memory appends a correction event;
 *    it never rewrites history. That is what makes "可检视可擦除页" (the plan's phrase) possible:
 *    erase is a tombstone event, so the audit trail survives the erase.
 *
 * 2. EVENT IDS MUST BE MONOTONIC AND THE CLOCK MUST NOT BE TRUSTED. Wall-clock timestamps can go
 *    backwards (NTP correction, a user changing the system clock, a laptop resuming). Ordering
 *    is by a sequence number, and `at` is only for display. An out-of-order event corrupts the
 *    replay, which corrupts the view.
 *
 * 3. SCOPES ARE PART OF THE EVENT, NOT THE VIEW. user scope follows the person across projects;
 *    workspace scope belongs to one project root. Deciding scope at read time would silently
 *    move memories between projects when a workspaceRoot changes — the prokop cross-project
 *    requirement means the user scope must NOT follow workspaceRoot.
 *
 * 4. EXTRACTION IS A SEPARATE, FAILING-SOFT STAGE. The agent proposes candidate memories after a
 *    turn; extraction from that proposal can fail. A failure must not lose the turn, and it must
 *    not write a half-formed memory. So proposals are staged, then applied as a batch.
 */

/**
 * `MemoryScope`, `MemoryEventKind` and `MemoryEvent` live in shared/types.ts and are re-export
 * here — see the same move for `AutomationKind` and `TaskLogEntry`.
 *
 * They used to be defined only in this file, which is the exact shape of the failure the other
 * two had: the runtime value was always there, but the renderer had no name for it, so a page
 * built to inspect the log could not be typed at all. `MemoryView` deliberately stays local — it
 * is the folded projection, never something the renderer receives.
 */
export type { MemoryScope, MemoryEventKind, MemoryEvent } from '../../../shared/types';
import type { MemoryScope, MemoryEventKind, MemoryEvent, Memory } from '../../../shared/types';
import * as fs from 'node:fs';
import * as path from 'node:path';

export interface MemoryView {
	id: string;
	name: string;
	description: string;
	body: string;
	type: 'project' | 'feedback' | 'reference' | 'handoff';
	importance: number;
	scope: MemoryScope;
	/** Workspace root this row belongs to; '' for user scope. */
	workspace: string;
	accessCount: number;
	lastAccessAt: number;
	inIndex: boolean;
	links: string[];
	createdAt: number;
	updatedAt: number;
	/** Forgets are folded into the view, not deleted from the log. */
	forgotten: boolean;
	/** Sequence of the last event that produced this row — for "what changed and when". */
	seq: number;
}

export const LIMITS = {
	/** Longest body we store. A memory is a fact, not a document. */
	maxBodyChars: 4000,
	/** Longest name. */
	maxNameChars: 120,
	/** Refuse to grow the log past this; oldest events are compacted, not deleted silently. */
	maxEvents: 20_000,
	/**
	 * Largest inspection window one call may ask for, and the default when it does not name one
	 * (both read by `recent`). Deliberately separate from `maxEvents`: the log may legitimately
	 * hold 20,000 events, and none of that is a reason to put all 20,000 on the wire in response
	 * to a single render.
	 */
	maxEventsPerCall: 5_000,
	defaultEventsPerCall: 500,
	/** Importance is 0..1; anything else is clamped rather than rejected. */
	minImportance: 0,
	maxImportance: 1,
} as const;

/**
 * The append-only log plus the fold into a view.
 *
 * Kept as a class with an injected clock and id source so the whole thing is testable: the
 * interesting failures here are all about ordering and hostile input, neither of which can be
 * provoked reliably against a real clock.
 */
export class MemoryLog {
	private events: MemoryEvent[] = [];
	private seq = 0;
	/** Injected so tests can force clock skew; see decision 2. */
	constructor(private readonly now: () => number = () => Date.now()) { }

	/**
	 * Durability hook. Called after every append with the CURRENT FULL event list.
	 *
	 * It carries the whole list rather than just the new event because the log is bounded:
	 * `compactIfNeeded` drops events, and a sink that only ever appended lines would keep
	 * writing to a file that grows forever while memory shrinks. Handing over the array lets
	 * the sink append on the common path and rewrite when the set did not simply grow by one.
	 *
	 * A hook rather than a file path in the constructor: the log stays free of IO, so ordering,
	 * folding and hostile input stay testable without a temp directory, and the host decides
	 * where the log lives. The sink must consume the list synchronously and not retain it.
	 */
	private changed: ((events: MemoryEvent[]) => void) | null = null;
	onChanged(fn: ((events: MemoryEvent[]) => void) | null): void { this.changed = fn; }

	/** Append an event. Returns the stored event, or null if it was rejected. */
	append(e: Omit<MemoryEvent, 'seq' | 'at'> & { at?: number }): MemoryEvent | null {
		const name = String(e.name ?? '').trim().slice(0, LIMITS.maxNameChars);
		if (!name) { return null; }
		this.seq += 1;
		const ev: MemoryEvent = {
			seq: this.seq,
			kind: e.kind,
			key: e.key || name,
			name,
			body: String(e.body ?? '').slice(0, LIMITS.maxBodyChars),
			description: String(e.description ?? ''),
			type: e.type ?? 'project',
			// Clamp rather than reject: a model proposing importance=5 meant "very important",
			// and discarding the whole memory over it loses more than clamping does.
			importance: clamp(Number(e.importance), LIMITS.minImportance, LIMITS.maxImportance, 0.5),
			scope: e.scope === 'workspace' ? 'workspace' : 'user',
			workspace: e.scope === 'workspace' ? e.workspace : undefined,
			at: Number.isFinite(e.at) ? Number(e.at) : this.now(),
			source: e.source ?? 'tool',
			reason: e.reason,
		};
		this.events.push(ev);
		this.compactIfNeeded();
		// AFTER compaction, not before: what the sink sees must be what the next load folds.
		this.changed?.(this.events);
		return ev;
	}

	/**
	 * Bound the log. This DISCARDS events, so the question is never "may we drop this one" but
	 * "what does the view look like afterwards".
	 *
	 * The previous version of this function DID NOTHING, while its comment claimed it refused to
	 * grow past `maxEvents`. It computed `earliest` as
	 * `Math.min(...liveRows.map(r => r.seq), this.events[0].seq)` — and `events[0].seq` is by
	 * construction the smallest seq present, so the minimum was always `events[0].seq`,
	 * `keepFrom` was always 0, and not one event was ever dropped. The bound was documentation
	 * rather than behaviour, and now that the log is persisted that meant a file growing forever.
	 *
	 * Only a SUFFIX can be kept, and that is what makes the window safe rather than merely small:
	 * the fold replays in seq order, so if a key's `add` survives then every later event for that
	 * key survives too. In particular a `forget` can never be trimmed while its `add` is kept —
	 * which is the one cut that would matter, because the next projection would write that row
	 * back to the collection and resurrect the memory the user erased.
	 *
	 * The cost, stated rather than left to be discovered: `append` fills every event with the full
	 * field set, so a row rebuilt from a surviving non-`add` event still has the right name, body,
	 * description, type, importance and scope — and `memory_restore` appends the entire row, so a
	 * restore whose `add` has fallen out of the window still brings back the right content. What
	 * degrades is `createdAt`: a row whose `add` was trimmed dates from its oldest surviving
	 * event. `list()` sorts by `updatedAt`, so ordering is unaffected.
	 */
	private compactIfNeeded(): void {
		const over = this.events.length - LIMITS.maxEvents;
		if (over <= 0) { return; }
		this.events = this.events.slice(over);
	}

	/** Replay the log into the current view. Order is by `seq`, never by `at`. */
	fold(): MemoryView[] {
		const rows = new Map<string, MemoryView>();
		for (const ev of [...this.events].sort((a, b) => a.seq - b.seq)) {
			const prev = rows.get(ev.key);
			if (!prev || ev.kind === 'add') {
				rows.set(ev.key, {
					id: ev.key,
					name: ev.name,
					description: ev.description,
					body: ev.body,
					type: ev.type,
					importance: ev.importance,
					scope: ev.scope,
					workspace: ev.workspace ?? '',
					accessCount: 0,
					lastAccessAt: 0,
					inIndex: false,
					links: [],
					createdAt: ev.at,
					updatedAt: ev.at,
					// A `forget` for a key this log has never seen — an imported row erased
					// straight from the collection — has to fold to a HIDDEN row, not to a fresh
					// live one. This branch is the only place where the event's own meaning is
					// discarded instead of applied: hardcoding false here handed the projection a
					// live row and resurrected the exact memory the forget arrived to remove.
					forgotten: ev.kind === 'forget',
					seq: ev.seq,
				});
				continue;
			}
			prev.updatedAt = ev.at;
			prev.seq = ev.seq;
			switch (ev.kind) {
				case 'update':
					prev.body = ev.body;
					prev.description = ev.description || prev.description;
					break;
				case 'revise':
					prev.importance = ev.importance;
					prev.type = ev.type;
					break;
				case 'forget':
					// Soft: the row leaves the view but the event stays, so the erase is auditable.
					prev.forgotten = true;
					break;
				case 'restore':
					prev.forgotten = false;
					break;
			}
		}
		return [...rows.values()];
	}

	/** Live memories, newest first. Scope filter: user scope is visible everywhere. */
	list(opts: { scope?: MemoryScope; workspace?: string; includeForgotten?: boolean } = {}): MemoryView[] {
		const all = this.fold().filter(m => opts.includeForgotten || !m.forgotten);
		const scoped = all.filter(m => {
			// User scope follows the person across every project (the prokop cross-project case),
			// so it is always visible.
			if (m.scope === 'user') { return true; }
			// A workspace memory is only visible when the reader is in that SAME workspace.
			// The earlier version compared a value to itself and always returned true, which
			// leaked every project's workspace memories into every other project.
			return (m.workspace ?? '') === (opts.workspace ?? '');
		});
		return scoped.sort((a, b) => b.updatedAt - a.updatedAt);
	}

	get(key: string): MemoryView | null {
		return this.fold().find(m => m.id === key) ?? null;
	}

	/**
	 * The audit trail for one memory, oldest first. This is the "可检视" part.
	 *
	 * Matches on the KEY **or** the NAME, deliberately. The log acquired two ways of spelling a
	 * memory's identity: events written by the log's own writers carry the NAME as key, while a
	 * forget/restore for a row that only ever existed in the collection (imported, synced in, or
	 * created straight in the UI) carries the STORE ROW id — that row has no log history to
	 * inherit a key from. Both spellings then appear for the SAME memory, and a key-exact lookup
	 * split its trail in two; the memory page worked around it by querying twice and merging by
	 * seq. Every event carries `name` whichever key it was written under, so matching on it is
	 * the one lookup that finds the whole trail.
	 *
	 * The write side keeps the store id on purpose and this method does NOT try to change it: the
	 * fold derives a row's id from its key, so keying a store-only row by name would mint a SECOND
	 * row instead of erasing the one on screen. Caveat, stated rather than buried: two distinct
	 * rows that share a name would have their trails merged here — the truthful trade against
	 * losing half of a trail for one row, which is what the exact match did.
	 */
	history(key: string): MemoryEvent[] {
		return [...this.events].sort((a, b) => a.seq - b.seq)
			.filter(e => e.key === key || e.name === key);
	}

	/** Every event, for the inspection page and for export. */
	all(): MemoryEvent[] {
		return [...this.events].sort((a, b) => a.seq - b.seq);
	}

	/**
	 * The newest `n` events plus the TOTAL, for a paged inspection view.
	 *
	 * Deliberately not `all()` handed across IPC: the log is bounded at `maxEvents`, so dumping
	 * all of it is a multi-MB round trip that stalls the very page it exists to serve. Carrying
	 * `total` alongside the window keeps it honest — "显示 500 / 共 N" instead of a list that
	 * silently looks complete.
	 *
	 * `n` is sanitised, not trusted: `slice(-0)` is the WHOLE array, so an unvalidated 0 hands
	 * back exactly what this method exists to withhold.
	 */
	recent(n?: number): { events: MemoryEvent[]; total: number } {
		const raw = Number(n);
		const take = Number.isFinite(raw) && raw >= 1
			? Math.min(Math.floor(raw), LIMITS.maxEventsPerCall)
			: LIMITS.defaultEventsPerCall;
		const all = this.all();
		return { events: all.slice(-take), total: all.length };
	}

	get size(): number { return this.events.length; }
	get lastSeq(): number { return this.seq; }

	/** Seed from an existing collection (migration / dump import). */
	seed(rows: { name: string; body: string; description?: string; type?: MemoryView['type']; importance?: number; scope?: MemoryScope; workspace?: string; createdAt?: number; updatedAt?: number }[]): number {
		let n = 0;
		for (const r of rows ?? []) {
			// Seeded rows become 'add' events, so the log is the single source of truth from the
			// very first write. Importing them any other way would create a second history.
			//
			// `workspace` rides along for the same reason `scope` already did: re-seeding a
			// workspace-scoped memory without it brings it back as user scope on the next launch,
			// making it visible in every project. Scope is part of the fact being imported, not a
			// local annotation that can be dropped on the way in.
			const ev = this.append({
				kind: 'add', key: r.name, name: r.name, body: r.body,
				description: r.description ?? '', type: r.type ?? 'project',
				importance: r.importance ?? 0.5, scope: r.scope ?? 'user',
				workspace: r.workspace,
				at: r.createdAt ?? r.updatedAt, source: 'import',
			});
			if (ev) { n++; }
		}
		return n;
	}

	toJSON(): MemoryEvent[] { return this.all(); }

	static fromJSON(events: unknown[], now: () => number = () => Date.now()): MemoryLog {
		const log = new MemoryLog(now);
		for (const raw of events ?? []) {
			const e = raw as MemoryEvent;
			if (!e || typeof e !== 'object') { continue; }
			const seq = Number(e.seq);
			if (!Number.isFinite(seq) || seq <= 0) { continue; }
			const at = Number.isFinite(Number(e.at)) ? Number(e.at) : now();
			log.events.push({
				seq, at, kind: e.kind ?? 'add', key: String(e.key ?? e.name ?? ''),
				name: String(e.name ?? ''), body: String(e.body ?? ''),
				description: String(e.description ?? ''), type: e.type ?? 'project',
				importance: clamp(Number(e.importance), 0, 1, 0.5),
				scope: e.scope === 'workspace' ? 'workspace' : 'user',
				workspace: typeof e.workspace === 'string' ? e.workspace : undefined,
				source: e.source ?? 'tool', reason: typeof e.reason === 'string' ? e.reason : undefined,
			});
			log.seq = Math.max(log.seq, seq);
		}
		return log;
	}
}

/* ---------------------------------------------------------------------------
 * Durability: where the log lives between runs.
 *
 * pc-a's block, and it is not a nicety. The memory page promises 「轨迹仍在，可恢复」, and while
 * the log lived only in memory that sentence quietly became false at the next launch: the forget
 * event went with the process, so the trail behind an erase was gone precisely when someone came
 * back to inspect it. The store still holds the tombstone, so the memory stayed deleted — which
 * is what made the lie easy to miss. A promise that holds until the next restart is not a promise.
 *
 * Kept here rather than in `MemoryLog`'s constructor: a file path would drag a temp directory
 * into every test of the ordering and folding rules, which are the parts worth testing. `index.ts`
 * wires the two together in one place.
 *
 * FORMAT — JSON Lines, one event per line, append-only:
 *  - it matches the log's own semantics, and keeps the common path to a single `append` syscall
 *    instead of rewriting a file that may hold 20,000 events;
 *  - a crash mid-write can tear only the LAST line, and `readMemoryEvents` skips unparseable
 *    lines, so a torn tail costs one event. The alternative — one big JSON.parse over the whole
 *    file — turns the same crash into a total loss of the log.
 * ------------------------------------------------------------------------- */

/**
 * Read the log from disk. A missing file and an unreadable line are both "not there", never a
 * throw: this runs at startup, and refusing to boot because the memory history is damaged would
 * trade a cosmetic gap for a dead app. Feed the result to `MemoryLog.fromJSON`, which is what
 * validates and re-seats the sequence counter — this function only decodes lines.
 */
export function readMemoryEvents(file: string): MemoryEvent[] {
	let text: string;
	try {
		text = fs.readFileSync(file, 'utf8');
	} catch {
		return []; // first run, or the file is simply not there yet
	}
	const out: MemoryEvent[] = [];
	for (const line of text.split(/\r?\n/)) {
		if (!line.trim()) { continue; }
		try {
			const o: unknown = JSON.parse(line);
			if (o && typeof o === 'object') { out.push(o as MemoryEvent); }
		} catch {
			// A torn tail after a hard kill. Skipping it keeps the other N-1 events; the
			// alternative is throwing away an entire audit trail to salvage one line.
			continue;
		}
	}
	return out;
}

/**
 * Build a sink for `MemoryLog.onChanged`.
 *
 * `knownCount` is how many events were just read back, so the first write after boot appends
 * instead of rewriting a file that already holds exactly those events.
 *
 * Append when the list grew by exactly one — the shape of every ordinary write — and rewrite
 * otherwise. The rewrite branch is not decoration: a sink that only ever appended could not
 * follow a shrink, and a bounded log writing an unbounded file is the same bug in a new place.
 * Rewrites are rare by construction, so they stay cheap.
 *
 * Failures are LOGGED, not thrown. The caller is inside `append`, the event is already in
 * memory, and refusing to record a memory because the disk hiccuped would cost more than the
 * one durability miss it is complaining about.
 */
export function makeMemoryPersist(file: string, knownCount: number): (events: MemoryEvent[]) => void {
	let last = knownCount;
	return (events: MemoryEvent[]) => {
		const grew = events.length === last + 1 && events.length > 0;
		last = events.length;
		try {
			fs.mkdirSync(path.dirname(file), { recursive: true });
			if (grew) {
				fs.appendFileSync(file, JSON.stringify(events[events.length - 1]) + '\n', 'utf8');
			} else {
				const body = events.map(e => JSON.stringify(e)).join('\n');
				fs.writeFileSync(file, events.length ? body + '\n' : '', 'utf8');
			}
		} catch (e) {
			console.error('[memory] persist failed --', (e as Error)?.message ?? String(e));
		}
	};
}

/**
 * Open the log at `file`, or start an empty one, wired so it stays durable.
 *
 * The whole boot sequence in one place: read, re-seat the sequence counter via `fromJSON`,
 * attach the sink. `index.ts` calls this instead of assembling the three steps itself because
 * the wiring IS what pc-a's block was about. Hand-assembled, it has no test that would notice a
 * missing `onChanged` — and a log that hydrates without a sink reads perfectly and then quietly
 * stops being durable on the very next write, which is the same class of failure as the one being
 * fixed here: correct right up until you check.
 */
export function openMemoryLog(file: string): MemoryLog {
	const events = readMemoryEvents(file);
	const log = MemoryLog.fromJSON(events);
	log.onChanged(makeMemoryPersist(file, events.length));
	return log;
}

/**
 * Seed rows the collection holds but the log has never seen.
 *
 * This was `memLog.size === 0`, which was correct while the log was rebuilt from nothing on
 * every launch and became wrong the moment it was durable: a guard that fires only when the log
 * is EMPTY fires once ever, so rows that reach the collection WITHOUT going through the log —
 * a dump merged in from the phone — would never gain a history at all.
 *
 * Membership is by key against `all()` rather than by content, because two opposite requirements
 * are settled by the same test: an already-known key must be SKIPPED (otherwise every launch
 * appends duplicates), and a forgotten key must count as known too (otherwise the reseed writes a
 * fresh `add` and resurrects the row the user just erased — the exact bug this log exists for).
 */
export function seedMissing(log: MemoryLog, rows: Memory[]): number {
	const seen = new Set(log.all().map(e => e.key));
	const missing = rows.filter(r => !r.deletedAt && !seen.has(r.name));
	if (!missing.length) { return 0; }
	return log.seed(missing.map(r => ({
		name: r.name, body: r.body, description: r.description ?? '',
		type: r.type ?? 'project', importance: r.importance ?? 0.5,
		// Scope has to survive the round trip or (c) is a one-session fix: the projection now
		// WRITES these to the row, and re-seeding without them would fold every workspace memory
		// back as user scope on the next launch — visible in every project, which is the one thing
		// workspace scope exists to prevent.
		scope: r.scope,
		workspace: r.workspace,
		createdAt: r.createdAt || undefined, updatedAt: r.updatedAt || undefined,
	})));
}

function clamp(n: number, lo: number, hi: number, fallback: number): number {
	if (!Number.isFinite(n)) { return fallback; }
	return Math.min(Math.max(n, lo), hi);
}

/* ------------------------------------------------------------------ */
/* Auto-extraction (M19 item 3): staged proposals, applied as a batch */
/* ------------------------------------------------------------------ */

/** What the model proposes after a turn. Never written directly — see decision 4. */
export interface MemoryProposal {
	name: string;
	body: string;
	description?: string;
	type?: 'project' | 'feedback' | 'reference' | 'handoff';
	importance?: number;
	scope?: MemoryScope;
	reason: string;
}

/**
 * Stage a turn's proposals. Pure — nothing is written here.
 *
 * Kept separate from `apply` so the two failure modes stay distinguishable: a bad extraction
 * (nothing staged) and a bad write (nothing applied) are different bugs with different fixes.
 */
export function stageProposals(text: string): MemoryProposal[] {
	const out: MemoryProposal[] = [];
	for (const raw of String(text ?? '').split(/\n+/)) {
		const line = raw.trim();
		if (!line) { continue; }
		// The model proposes "name | type | importance | body"; anything else is ignored rather
		// than guessed at, because a memory written from a misparse is worse than none.
		const parts = line.split('|').map(s => s.trim());
		if (parts.length < 2 || !parts[0] || !parts[1]) { continue; }
		const type = (['project', 'feedback', 'reference', 'handoff'] as const)
			.find(t => t === parts[1]);
		if (!type) { continue; }
		const imp = Number(parts[2]);
		out.push({
			name: parts[0].slice(0, LIMITS.maxNameChars),
			body: (parts[3] ?? parts[parts.length - 1]).slice(0, LIMITS.maxBodyChars),
			description: parts[3] ?? '',
			type,
			importance: Number.isFinite(imp) ? imp : 0.5,
			reason: '轮次完成后自动沉淀',
		});
	}
	return out;
}

/** Why a proposal was not applied. Returned so the UI can show it, not just swallow it. */
export type ApplyRejection =
	| 'empty-name'
	| 'duplicate-identical'
	| 'no-change';

export interface ApplyResult {
	applied: { key: string; kind: MemoryEventKind; seq: number }[];
	rejected: { name: string; reason: ApplyRejection }[];
}

/**
 * Apply staged proposals.
 *
 * Deduplication is by CONTENT, not by name: re-proposing the same fact every turn is the normal
 * failure mode of auto-extraction, and without this the log fills with no-op events that make
 * the audit trail useless.
 */
export function applyProposals(
	log: MemoryLog,
	proposals: MemoryProposal[],
	opts: { workspace?: string; source?: 'auto' | 'tool' } = {},
): ApplyResult {
	const applied: ApplyResult['applied'] = [];
	const rejected: ApplyResult['rejected'] = [];
	for (const p of proposals ?? []) {
		const name = String(p.name ?? '').trim();
		if (!name) { rejected.push({ name, reason: 'empty-name' }); continue; }
		const existing = log.get(name);
		if (existing && !existing.forgotten
			&& existing.body === p.body && existing.description === (p.description ?? existing.description)) {
			rejected.push({ name, reason: existing.body === p.body ? 'duplicate-identical' : 'no-change' });
			continue;
		}
		// Scope resolution, in order: the proposal, then the existing row, then whatever the
		// CALLER is working in.
		//
		// The third term is the one that was missing. Defaulting to 'user' meant auto-extracted
		// facts became user scope — visible from every workspace forever — which quietly undid
		// the isolation this file exists for. A proposal made while a workspace is open
		// belongs to that workspace unless it says otherwise.
		const scope: MemoryScope = p.scope ?? existing?.scope
			?? (opts.workspace ? 'workspace' : 'user');
		const ev = log.append({
			kind: existing ? 'update' : 'add',
			key: name, name, body: p.body,
			description: p.description ?? existing?.description ?? '',
			type: p.type ?? existing?.type ?? 'project',
			importance: p.importance ?? existing?.importance ?? 0.5,
			scope,
			workspace: scope === 'workspace' ? (opts.workspace ?? existing?.workspace ?? '') : '',
			source: opts.source ?? 'auto',
			reason: p.reason,
		});
		if (ev) { applied.push({ key: ev.key, kind: ev.kind, seq: ev.seq }); }
		else { rejected.push({ name, reason: 'empty-name' }); }
	}
	return { applied, rejected };
}