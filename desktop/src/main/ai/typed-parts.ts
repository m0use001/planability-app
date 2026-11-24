/* M17 typed-parts persistence (answers pc-a's M17 acceptance item ⑥: messages was always 0).
 *
 * The run loop persists to RowBridge → chat_sessions.json rows, which is the renderer's view.
 * This writes the SAME conversation into SQLite `messages` as typed parts, so reasoning and
 * tool calls can be replayed without re-parsing a flat transcript.
 *
 * Incremental by seq: a long conversation would otherwise be fully rewritten after every
 * single turn, which is both wasteful and a source of UI jank.
 */

import type { AiDb } from './db';

export interface TypedPart {
	type: 'text' | 'reasoning' | 'tool_call' | 'tool_result';
	text?: string;
	/** tool_call / tool_result */
	name?: string;
	arguments?: string;
	callId?: string;
	ok?: boolean;
}

/** Loose shape: the agent's OpenAIMessage plus any reasoning we can recover. */
interface RawMessage {
	role: 'system' | 'user' | 'assistant';
	content: string;
	tool_calls?: { id: string; type: string; function: { name: string; arguments: string } }[];
}

export function newMessageId(): string {
	return `msg_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
}

/** Split one message into typed parts. Returns '' when there is nothing worth storing. */
export function toParts(m: RawMessage): TypedPart[] {
	const parts: TypedPart[] = [];
	const text = typeof m.content === 'string' ? m.content : '';
	// `[tool results]` envelopes are transport, not conversation — lift them into parts.
	const resultsMatch = text.startsWith('[tool results]');
	if (resultsMatch) {
		for (const line of text.slice('[tool results]'.length).trim().split('\n')) {
			const t = line.trim();
			if (!t) { continue; }
			try {
				const parsed = JSON.parse(t) as { callId?: string; value?: unknown };
				parts.push({
					type: 'tool_result',
					callId: parsed.callId,
					text: typeof parsed.value === 'string' ? parsed.value : JSON.stringify(parsed.value ?? ''),
					ok: true,
				});
			} catch {
				parts.push({ type: 'tool_result', text: t });
			}
		}
	} else if (text.trim()) {
		parts.push({ type: 'text', text });
	}
	for (const tc of m.tool_calls ?? []) {
		parts.push({ type: 'tool_call', name: tc.function?.name, arguments: tc.function?.arguments, callId: tc.id });
	}
	return parts;
}

/**
 * Persist any messages beyond the high-water mark for this session.
 * Returns how many rows were written. Safe to call every turn.
 */
export class TypedPartStore {
	/** sessionId -> next seq to write */
	private readonly cursor = new Map<string, number>();

	constructor(private readonly db: AiDb) { }

	/** Forget a session (deleted / rolled back) so the next write starts clean. */
	reset(sessionId: string): void { this.cursor.delete(sessionId); }

	/** How many messages are already stored for a session (from the DB, not the cursor). */
	storedCount(sessionId: string): number {
		const r = this.db.raw.prepare('SELECT COUNT(*) c FROM messages WHERE sessionId=?').get(sessionId) as any;
		return r?.c ?? 0;
	}

	persist(sessionId: string, history: RawMessage[]): number {
		if (!sessionId) { return 0; }
		// Trust the DB when the cursor is cold (e.g. after a restart) so we never duplicate.
		let seq = this.cursor.get(sessionId);
		if (seq === undefined) { seq = this.storedCount(sessionId); }
		if (seq >= history.length) { this.cursor.set(sessionId, history.length); return 0; }

		const ins = this.db.raw.prepare(
			'INSERT INTO messages (id,sessionId,seq,role,parts,createdAt) VALUES (?,?,?,?,?,?)',
		);
		const now = Date.now();
		let written = 0;
		const tx = this.db.raw.transaction((rows: { seq: number; role: string; parts: string }[]) => {
			for (const r of rows) {
				ins.run(newMessageId(), sessionId, r.seq, r.role, r.parts, now + r.seq);
				written++;
			}
		});
		const batch: { seq: number; role: string; parts: string }[] = [];
		for (let i = seq; i < history.length; i++) {
			const m = history[i];
			const parts = toParts(m);
			if (!parts.length) { continue; }
			batch.push({ seq: i, role: m.role, parts: JSON.stringify(parts) });
		}
		if (batch.length) { tx(batch); }
		this.cursor.set(sessionId, history.length);
		return written;
	}

	/** Read back one session's messages as typed parts (diagnostics / future replay). */
	read(sessionId: string): { seq: number; role: string; parts: TypedPart[] }[] {
		return (this.db.raw.prepare('SELECT seq, role, parts FROM messages WHERE sessionId=? ORDER BY seq ASC').all(sessionId) as any[])
			.map(r => ({ seq: r.seq, role: r.role, parts: JSON.parse(r.parts) as TypedPart[] }));
	}
}
