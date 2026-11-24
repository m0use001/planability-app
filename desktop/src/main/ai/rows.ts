/* Row bridge (M16): turns the agent's primitive events into conversation rows, streams
 * them to the renderer and persists them — the main process is the single writer
 * (v1 let the renderer push display history back, which duplicated state and raced).
 * One bridge per app; rows follow the active session via replaceAll() at turn start. */

import type { ChatEvent, ConvRow } from '../../../shared/chat';

/**
 * What RowBridge tells the card store (stage 2: cards are first-class, see ai/card-store.ts).
 *
 * Optional on the bridge — a bridge with no sink behaves exactly as before, which is why the
 * row-session-label tests and any embedder construct it with three arguments still. Both
 * methods may return a promise; the bridge NEVER awaits it and swallows rejections: rows are
 * the source of truth and must persist even if cards.json is locked. Ordering is preserved by
 * call sequence (the store serialises internally), so capture-before-reconcile stays true.
 */
export interface RowCardSink {
	/** A row's final text may contain closed card fences — store the ones that validate. */
	capture(sessionId: string, rowId: string, text: string): void | Promise<unknown>;
	/** The row set changed (load / rollback / clear): drop proposal cards whose row is gone. */
	reconcile(sessionId: string, liveRowIds: ReadonlySet<string>): void | Promise<unknown>;
}

/** v1 display message shape (chat_display.json) — only used for one-time migration. */
interface LegacyDisplayMsg {
	role: 'user' | 'assistant' | 'system';
	content: string;
	reasoning?: string;
	tools?: { name: string; argsJson: string; status: 'running' | 'ok' | 'fail' | 'denied'; summary?: string; ms?: number }[];
	error?: string;
	done: boolean;
}

/** Convert v1 display messages to rows. Tool rows precede the assistant text row so the
 * visual order (reasoning → tools → content) survives the migration. */
export function displayToRows(display: LegacyDisplayMsg[]): ConvRow[] {
	const rows: ConvRow[] = [];
	let seq = 0;
	const nid = () => `r_m${(seq++).toString(36)}`;
	for (const m of display ?? []) {
		if (m.role === 'user') {
			rows.push({ rowId: nid(), kind: 'user', text: m.content });
		} else if (m.role === 'system') {
			rows.push({ rowId: nid(), kind: 'system', text: m.content });
		} else {
			for (const t of m.tools ?? []) {
				rows.push({ rowId: nid(), kind: 'tool', name: t.name, argsJson: t.argsJson, status: t.status, summary: t.summary, ms: t.ms });
			}
			rows.push({
				rowId: nid(), kind: 'assistant', text: m.content ?? '', reasoning: m.reasoning,
				done: m.done !== false, error: m.error
			});
		}
	}
	return rows;
}

/** A crash mid-stream may persist half-open rows — close them on load (v1 normalize parity). */
export function normalizeRows(rows: ConvRow[]): ConvRow[] {
	return rows.map(r => {
		if (r.kind === 'tool' && r.status === 'running') {
			return { ...r, status: 'fail' as const, summary: r.summary ?? '中断' };
		}
		if (r.kind === 'assistant' && (!r.done || r.error)) {
			return { ...r, done: true, error: r.error ?? '（上次会话中断）' };
		}
		return r;
	});
}

export class RowBridge {
	private rows: ConvRow[] = [];
	private seq = 0;

	constructor(
		private readonly emit: (e: ChatEvent) => void,
		private readonly persist: (rows: ConvRow[]) => Promise<void>,
		/** Stamped on every row event so the renderer can tell whose transcript this is.
		 *  A provider, evaluated per emit, because the main bridge FOLLOWS the active
		 *  session while a run's bridge is fixed to its own session. */
		private readonly sessionId: () => string,
		/** Card store hook (stage 2). Same provider semantics as sessionId: evaluated per
		 *  call, so main-bridge captures label with the active session at that moment. */
		private readonly cards?: RowCardSink
	) { }

	/** Fire one card-sink call without letting it touch the row path: never awaited, never
	 *  thrown — a card store failure must not fail a turn whose rows persisted fine. */
	private cardOp(fn: (sink: RowCardSink) => void | Promise<unknown>): void {
		if (!this.cards) { return; }
		try {
			const r = fn(this.cards);
			if (r && typeof (r as Promise<unknown>).then === 'function') {
				void (r as Promise<unknown>).catch(() => { /* cards are best-effort; rows are not */ });
			}
		} catch { /* same: card problems stay on the card side */ }
	}

	/** Rescan EVERY closed assistant row for card fences. Idempotent by construction (ids are
	 *  positional), so calling it on each settle AND each load is free when nothing changed —
	 *  and it heals the crash window where rows were saved but their capture was not. */
	private captureCards(): void {
		const sid = this.sessionId();
		for (const r of this.rows) {
			if (r.kind !== 'assistant' || !r.done) { continue; }
			const text = r.text;
			if (!text) { continue; }
			this.cardOp(sink => sink.capture(sid, r.rowId, text));
		}
	}

	/** Current row ids, then reconcile: proposal cards whose row is no longer here go. */
	private reconcileCards(): void {
		const sid = this.sessionId();
		const live = new Set(this.rows.map(r => r.rowId));
		this.cardOp(sink => sink.reconcile(sid, live));
	}

	replaceAll(rows: ConvRow[], announce = false): void {
		this.rows = rows;
		this.seq = rows.length;
		if (announce) { this.emit({ type: 'rows_set', sessionId: this.sessionId(), rows }); }
		// Load / session switch / turn start: adopt cards this row set supports, drop proposal
		// cards whose rows are not in it. capture BEFORE reconcile so a card captured just now
		// (crash-heal above) is one reconcile sees as live — both run in call order in the store.
		this.captureCards();
		this.reconcileCards();
	}

	all(): ConvRow[] { return this.rows; }

	private nid(): string {
		// counter keeps order readable; time slice keeps ids unique across rollbacks
		return `r_${(this.seq++).toString(36)}${Date.now().toString(36).slice(-5)}`;
	}

	private upsert(row: ConvRow): void {
		const i = this.rows.findIndex(r => r.rowId === row.rowId);
		if (i >= 0) { this.rows[i] = row; } else { this.rows.push(row); }
		this.emit({ type: 'row_upsert', sessionId: this.sessionId(), row });
	}

	user(text: string, attachments?: readonly string[]): ConvRow {
		const row: ConvRow = { rowId: this.nid(), kind: 'user', text };
		// Spread-absent when empty (house style): a key that is always present but usually `[]`
		// is a second way to say "none" and would grow every persisted user row for nothing.
		if (attachments?.length) { row.attachments = [...attachments]; }
		this.upsert(row);
		return row;
	}

	/** The trailing assistant row, or a fresh open one (v1 "last assistant not done" parity). */
	private openAssistant(): ConvRow {
		const last = this.rows[this.rows.length - 1];
		if (last && last.kind === 'assistant' && !last.done) { return last; }
		const row: ConvRow = { rowId: this.nid(), kind: 'assistant', text: '', done: false };
		this.upsert(row);
		return row;
	}

	text(value: string): void {
		const row = this.openAssistant();
		row.text = (row.text ?? '') + value;
		this.emit({ type: 'row_delta', sessionId: this.sessionId(), rowId: row.rowId, field: 'text', value });
	}

	reasoning(value: string): void {
		const row = this.openAssistant();
		row.reasoning = (row.reasoning ?? '') + value;
		this.emit({ type: 'row_delta', sessionId: this.sessionId(), rowId: row.rowId, field: 'reasoning', value });
	}

	toolStart(name: string, argsJson: string): void {
		// close the open text segment: its content is complete once tools start, and the
		// next model round opens a fresh row — keeps rows chronological, cursor unambiguous
		const open = this.rows[this.rows.length - 1];
		if (open && open.kind === 'assistant' && !open.done) {
			this.upsert({ ...open, done: true });
		}
		this.upsert({ rowId: this.nid(), kind: 'tool', name, argsJson, status: 'running' });
	}

	toolEnd(name: string, ok: boolean, denied: boolean, summary: string, ms: number, result?: string): void {
		const open = [...this.rows].reverse().find(r => r.kind === 'tool' && r.name === name && r.status === 'running');
		if (!open) { return; }
		this.upsert({ ...open, status: ok ? 'ok' : denied ? 'denied' : 'fail', summary, ms, result });
	}

	/** Settle assistant rows: close every still-open segment (turn ended here). */
	settleAssistant(error?: string): void {
		const openRows = this.rows.filter(r => r.kind === 'assistant' && !r.done);
		if (!openRows.length) {
			// no open segment (e.g. empty final round) — create a closed marker row
			this.upsert({ rowId: this.nid(), kind: 'assistant', text: '', done: true, error });
			this.captureCards();
			return;
		}
		for (const row of openRows) {
			this.upsert({ ...row, done: true, error: error ?? row.error });
		}
		// THE capture point: text is final now (the repair loop's rewritten text lands here
		// too), so scan every closed assistant row for card fences and store what validates.
		this.captureCards();
	}

	turnError(message: string): void {
		this.upsert({ rowId: this.nid(), kind: 'error', text: message });
	}

	/** Mirror agent.rollbackUsers: drop rows from the k-th-last user row (inclusive). */
	rollbackUsers(k: number): void {
		let dropped = 0;
		let i = this.rows.length;
		while (i > 0 && dropped < k) {
			i--;
			if (this.rows[i].kind === 'user') { dropped++; }
		}
		this.rows = this.rows.slice(0, i);
		this.emit({ type: 'rows_set', sessionId: this.sessionId(), rows: this.rows });
		// Rows went, so their proposal cards go too — "died with its row" applied to rollback.
		this.reconcileCards();
	}

	clear(): void {
		this.rows = [];
		this.emit({ type: 'rows_set', sessionId: this.sessionId(), rows: [] });
		// live set is empty: every proposal card of this session is pruned, view cards exempt.
		this.reconcileCards();
	}

	async save(): Promise<void> {
		try { await this.persist(this.rows); } catch { /* non-fatal, next settle retries */ }
	}
}
