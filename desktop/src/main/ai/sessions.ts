/* Chat session store — multi-session parity with the phone workbench.
 * One file holds all sessions (meta + AI history + display rows); the active id
 * rides alongside. Compact for a single-machine desktop footprint.
 * M16: display truth moved to `rows` (main = single writer); the v1 renderer-owned
 * `display` array migrates into rows on first load and is then dropped. */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type { ConvRow } from '../../../shared/chat';
import { displayToRows } from './rows';
import type { OpenAIMessage } from './agent';

/** v1 display message shape — kept only so old files can migrate. */
interface LegacyDisplayMsg {
	role: 'user' | 'assistant' | 'system';
	content: string;
	reasoning?: string;
	tools?: { name: string; argsJson: string; status: 'running' | 'ok' | 'fail' | 'denied'; summary?: string; ms?: number }[];
	error?: string;
	done: boolean;
}

export interface ChatSession {
	id: string;
	title: string;
	createdAt: number;
	updatedAt: number;
	pinned: boolean;
	/**
	 * Optional link to a `projects` collection row, for grouping the sidebar.
	 *
	 * Optional so every existing `chat_sessions.json` loads untouched — no migration, and a
	 * session that was never grouped keeps working. Absent and empty both mean "no project";
	 * see `normProjectId`.
	 */
	projectId?: string;
	history: OpenAIMessage[];
	/** v2 display rows — persisted here, streamed to the renderer by the RowBridge. */
	rows: ConvRow[];
	/** @deprecated v1 renderer-owned display — dropped after one-time migration. */
	display?: LegacyDisplayMsg[];
}

/** Long enough for any id we mint, short enough that a pasted paragraph cannot bloat the file. */
const MAX_PROJECT_ID = 64;

/**
 * Normalise a project link: blank means "no project".
 *
 * Two reasons this is not a plain assignment. An empty string is what a text input produces when
 * the user clears it, and storing that would create a group whose title is blank — a group the UI
 * cannot label and did not ask for. And an id longer than any we mint can only be a paste error,
 * so it is dropped rather than stored: a stored value nothing will ever match is a dangling
 * reference, which is the thing this normalisation exists to prevent.
 */
function normProjectId(raw: unknown): string | undefined {
	const v = typeof raw === 'string' ? raw.trim() : '';
	if (!v || v.length > MAX_PROJECT_ID) { return undefined; }
	return v;
}

export function newSessionId(): string {
	return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

export class SessionStore {
	private sessions: ChatSession[] = [];
	private activeId = '';
	/** Single-flight load handle. See `ensureLoaded` for why a boolean was not enough. */
	private loading: Promise<void> | null = null;
	/** Write chain, so `persist` calls land in order. See `persist`. */
	private writing: Promise<void> = Promise.resolve();

	constructor(private readonly dataDir: string) { }

	private get file(): string { return path.join(this.dataDir, 'chat_sessions.json'); }
	private get activeFile(): string { return path.join(this.dataDir, 'chat_active.txt'); }

	/**
	 * Load the file exactly once, however many callers arrive together.
	 *
	 * The guard here used to be `if (this.loaded) return`, with `loaded = true` set below TWO
	 * awaits — so it protected nothing at all. Peer fan-out starts both runs at the same instant:
	 * both passed the guard, each parsed `chat_sessions.json` into its own snapshot of (at that
	 * point) an empty file, and the one that persisted second wrote its own array. The other
	 * session was simply never in the array that reached disk. That is why the JSON store
	 * intermittently held 1 of the 2 peer rows while the proposals — which go through SQLite —
	 * had both landed, and why `jsonRows[0]` still resolved (length 1, not 0).
	 *
	 * One shared promise closes the window structurally rather than by timing: a second caller
	 * gets the SAME in-flight load instead of starting a competing one, so there is only ever a
	 * single snapshot to install.
	 */
	private ensureLoaded(): Promise<void> {
		if (!this.loading) {
			// On failure the handle is cleared so the next caller retries, while the callers
			// already holding it still see the rejection they are owed.
			this.loading = this.loadOnce().catch(e => { this.loading = null; throw e; });
		}
		return this.loading;
	}

	private async loadOnce(): Promise<void> {
		await fs.mkdir(this.dataDir, { recursive: true });
		try { this.sessions = JSON.parse(await fs.readFile(this.file, 'utf8')); } catch { this.sessions = []; }
		try { this.activeId = (await fs.readFile(this.activeFile, 'utf8')).trim(); } catch { this.activeId = ''; }
		if (!this.sessions.find(s => s.id === this.activeId)) {
			this.activeId = this.sessions[0]?.id ?? '';
		}
		// one-time v1 → v2 migration: renderer-owned display → main-owned rows
		let migrated = false;
		for (const s of this.sessions) {
			if (!s.rows) {
				s.rows = s.display?.length ? displayToRows(s.display) : [];
				delete s.display;
				migrated = true;
			}
		}
		if (migrated) { await this.persist(); }
	}

	/**
	 * Write the file, one write at a time.
	 *
	 * Writes are CHAINED, so a slower `writeFile` cannot land after a newer one and replace it
	 * with stale contents. And `JSON.stringify` runs when the queued write actually executes, not
	 * when `persist()` was called — so the last writer serialises the array as it is THEN, rather
	 * than a snapshot taken before the other callers had pushed their rows. Both halves are
	 * needed: ordering without late serialisation still writes an old array, just on time.
	 *
	 * Without this, two concurrent saves each stringify their own view and the file keeps whichever
	 * finished last — a row the caller was already told had been saved, silently gone.
	 */
	private persist(): Promise<void> {
		const write = this.writing.then(async () => {
			await fs.mkdir(this.dataDir, { recursive: true });
			await fs.writeFile(this.file, JSON.stringify(this.sessions), 'utf8');
			await fs.writeFile(this.activeFile, this.activeId, 'utf8');
		});
		// The caller gets the REAL outcome — a failed save must surface, not vanish — but the
		// shared chain is kept recoverable: otherwise one EBUSY on Windows rejects the handle and
		// every save for the rest of the session fails behind it.
		this.writing = write.catch(() => { /* see above */ });
		return write;
	}

	/** Migrate a legacy single-conversation (chat_agent.json) into session #1. */
	async importLegacy(history: OpenAIMessage[], display: LegacyDisplayMsg[]): Promise<void> {
		await this.ensureLoaded();
		if (this.sessions.length || !history.length) { return; }
		const now = Date.now();
		this.sessions.push({
			id: newSessionId(), title: '之前的对话', createdAt: now, updatedAt: now,
			pinned: false, history, rows: displayToRows(display)
		});
		this.activeId = this.sessions[0].id;
		await this.persist();
	}

	async list(): Promise<ChatSession[]> {
		await this.ensureLoaded();
		return [...this.sessions]
			.sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
	}

	async getActive(): Promise<ChatSession | null> {
		await this.ensureLoaded();
		return this.sessions.find(s => s.id === this.activeId) ?? null;
	}

	/** 按 id 取单个会话（P0-c：文件工具按会话的 projectId 切根时用）。 */
	async get(id: string): Promise<ChatSession | null> {
		await this.ensureLoaded();
		return this.sessions.find(s => s.id === id) ?? null;
	}

	active(): string { return this.activeId; }

	async create(title = '新对话', projectId?: string): Promise<ChatSession> {
		await this.ensureLoaded();
		const now = Date.now();
		const s: ChatSession = { id: newSessionId(), title, createdAt: now, updatedAt: now, pinned: false, history: [], rows: [] };
		// Only set when there is something to set: an explicit `projectId: undefined` key would be
		// written to JSON as absent anyway, but keeping the object shape identical for the common
		// case means the persisted file does not grow a field nobody uses.
		const p = normProjectId(projectId);
		if (p) { s.projectId = p; }
		this.sessions.push(s);
		this.activeId = s.id;
		await this.persist();
		return s;
	}

	/**
	 * Create the JSON session if it is missing, WITHOUT making it active.
	 *
	 * M17 peer/sub-agent runs are created by AgentManager (SQLite `sessions`) but their rows
	 * and history live here. saveRows/loadRows silently no-op for an unknown id, so without
	 * this a peer's answer came back empty and the vote had nothing to count. Creating it in
	 * one place — right before a run starts — covers every caller (fan-out, task tool, IPC)
	 * instead of relying on each one remembering.
	 */
	async ensure(id: string, title = '新对话'): Promise<ChatSession> {
		await this.ensureLoaded();
		const existing = this.sessions.find(s => s.id === id);
		if (existing) { return existing; }
		const now = Date.now();
		const s: ChatSession = { id, title, createdAt: now, updatedAt: now, pinned: false, history: [], rows: [] };
		this.sessions.push(s);
		await this.persist();
		return s;
	}

	async switch(id: string): Promise<boolean> {
		await this.ensureLoaded();
		if (!this.sessions.find(s => s.id === id)) { return false; }
		this.activeId = id;
		await this.persist();
		return true;
	}

	async remove(id: string): Promise<boolean> {
		await this.ensureLoaded();
		const before = this.sessions.length;
		this.sessions = this.sessions.filter(s => s.id !== id);
		if (this.activeId === id) { this.activeId = this.sessions[0]?.id ?? ''; }
		await this.persist();
		return this.sessions.length !== before;
	}

	async rename(id: string, title: string): Promise<boolean> {
		await this.ensureLoaded();
		const s = this.sessions.find(x => x.id === id);
		if (!s) { return false; }
		s.title = title;
		s.updatedAt = Date.now();
		await this.persist();
		return true;
	}

	async pin(id: string, pinned: boolean): Promise<boolean> {
		await this.ensureLoaded();
		const s = this.sessions.find(x => x.id === id);
		if (!s) { return false; }
		// `updatedAt` deliberately NOT bumped: pinning is not activity, and bumping it would let a
		// pin reorder the list. Left exactly as it was.
		s.pinned = pinned;
		await this.persist();
		return true;
	}

	/**
	 * Move a session into a project, or out of all of them with `null` / an empty string.
	 *
	 * The project id is NOT checked against the `projects` collection. It cannot be: sessions live
	 * in this JSON file and projects live in the store, so `SessionStore` has no way to see them,
	 * and validating would also block the legitimate "group first, create the project later" flow.
	 * What keeps that honest is that a deleted project CLEARS the link — see `clearProjectFor`.
	 */
	async setProject(id: string, projectId: string | null): Promise<boolean> {
		await this.ensureLoaded();
		const s = this.sessions.find(x => x.id === id);
		if (!s) { return false; }
		const p = normProjectId(projectId);
		if (p) { s.projectId = p; } else { delete s.projectId; }
		s.updatedAt = Date.now();
		await this.persist();
		return true;
	}

	/**
	 * Drop the link to a project that no longer exists. Returns how many sessions were touched.
	 *
	 * Called when a project row is deleted. Without this, those sessions keep a `projectId` that
	 * matches nothing and the sidebar grows a group with no title — a dangling reference that
	 * looks like a rendering bug and is actually a data problem.
	 *
	 * One `persist` for the whole batch rather than one per session: deleting a project must not
	 * turn into N writes of the whole file.
	 */
	async clearProjectFor(projectId: string): Promise<number> {
		await this.ensureLoaded();
		const target = normProjectId(projectId);
		if (!target) { return 0; }
		let n = 0;
		for (const s of this.sessions) {
			if (s.projectId !== target) { continue; }
			delete s.projectId;
			s.updatedAt = Date.now();
			n++;
		}
		if (n) { await this.persist(); }
		return n;
	}

	async saveHistory(id: string, history: OpenAIMessage[], title?: string): Promise<void> {
		await this.ensureLoaded();
		const s = this.sessions.find(x => x.id === id);
		if (!s) { return; }
		s.history = history;
		s.updatedAt = Date.now();
		if (title && s.title === '新对话') { s.title = title; }
		await this.persist();
	}

	async saveRows(id: string, rows: ConvRow[]): Promise<void> {
		await this.ensureLoaded();
		const s = this.sessions.find(x => x.id === id);
		if (!s) { return; }
		s.rows = rows;
		s.updatedAt = Date.now();
		await this.persist();
	}

	async loadRows(id: string): Promise<ConvRow[]> {
		await this.ensureLoaded();
		return this.sessions.find(x => x.id === id)?.rows ?? [];
	}

	async loadHistory(id: string): Promise<OpenAIMessage[]> {
		await this.ensureLoaded();
		return this.sessions.find(x => x.id === id)?.history ?? [];
	}
}
