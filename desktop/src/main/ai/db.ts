/* M17 SQLite layer: agents / session tree / typed message parts / audit / usage.
 * The planning six collections stay in the JSON Store (per plan §3 M17 item 3) and the
 * cross-end dump contract is untouched — this file only owns AI-session state.
 *
 * Migration framework follows the ZCode migration-runner shape: a numbered, append-only
 * list of migrations, each applied exactly once inside one transaction, tracked in
 * `schema_migrations`. Never edit a shipped migration — add a new one.
 *
 * Native module note (see ◆/planability-gotchas.md): this must be
 * better-sqlite3@11.x rebuilt for Electron. v13 segfaults on require here. */

import * as fs from 'node:fs';
import * as path from 'node:path';
import Database from 'better-sqlite3';
import type { Database as SqliteDb } from 'better-sqlite3';

export interface Migration {
	readonly id: number;
	readonly name: string;
	readonly up: string;
}

/** Append-only. To change the schema, append a new migration — never edit a shipped one. */
export const MIGRATIONS: readonly Migration[] = [
	{
		id: 1,
		name: 'agents',
		up: `
CREATE TABLE IF NOT EXISTS agents (
	id           TEXT PRIMARY KEY,
	name         TEXT NOT NULL,
	color        TEXT NOT NULL DEFAULT '#4C8DFF',
	systemPrompt TEXT NOT NULL DEFAULT '',
	-- JSON arrays: tools allowlist, memory scope (empty = inherit all)
	tools        TEXT NOT NULL DEFAULT '[]',
	memoryScope  TEXT NOT NULL DEFAULT '[]',
	permMode     TEXT NOT NULL DEFAULT 'auto',
	maxTurns     INTEGER NOT NULL DEFAULT 10,
	builtin      INTEGER NOT NULL DEFAULT 0,
	createdAt    INTEGER NOT NULL,
	updatedAt    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agents_builtin ON agents(builtin);
`,
	},
	{
		id: 2,
		name: 'sessions_tree',
		up: `
-- Session tree: root sessions have parent_id = '', sub-agent turns hang off a parent.
CREATE TABLE IF NOT EXISTS sessions (
	id         TEXT PRIMARY KEY,
	title      TEXT NOT NULL DEFAULT '新对话',
	agentId    TEXT NOT NULL DEFAULT '',
	parentId   TEXT NOT NULL DEFAULT '',
	depth      INTEGER NOT NULL DEFAULT 0,
	status     TEXT NOT NULL DEFAULT 'idle',   -- idle | running | done | error
	pinned     INTEGER NOT NULL DEFAULT 0,
	createdAt  INTEGER NOT NULL,
	updatedAt  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_parent ON sessions(parentId);
CREATE INDEX IF NOT EXISTS idx_sessions_agent  ON sessions(agentId);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
`,
	},
	{
		id: 3,
		name: 'messages_typed_parts',
		up: `
-- One row per message; content split into typed parts so tool calls and reasoning can be
-- replayed without re-parsing a flat transcript.
CREATE TABLE IF NOT EXISTS messages (
	id         TEXT PRIMARY KEY,
	sessionId  TEXT NOT NULL,
	seq        INTEGER NOT NULL,
	role       TEXT NOT NULL,             -- system | user | assistant
	parts      TEXT NOT NULL DEFAULT '[]',-- JSON [{type:text|reasoning|tool_call|tool_result, ...}]
	createdAt  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(sessionId, seq);
`,
	},
	{
		id: 4,
		name: 'tool_audit',
		up: `
CREATE TABLE IF NOT EXISTS tool_audit (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	sessionId  TEXT NOT NULL DEFAULT '',
	agentId    TEXT NOT NULL DEFAULT '',
	tool       TEXT NOT NULL,
	input      TEXT NOT NULL DEFAULT '{}',
	ok         INTEGER NOT NULL DEFAULT 1,
	denied     INTEGER NOT NULL DEFAULT 0,
	ms         INTEGER NOT NULL DEFAULT 0,
	at         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_session ON tool_audit(sessionId);
CREATE INDEX IF NOT EXISTS idx_audit_at      ON tool_audit(at);
`,
	},
	{
		id: 5,
		name: 'usage_ledger',
		up: `
-- Per agent/session token accounting (plan §3 M17 item 5). Cost is estimated by the
-- caller; tokens are what the provider actually reported.
CREATE TABLE IF NOT EXISTS usage (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	agentId    TEXT NOT NULL DEFAULT '',
	sessionId  TEXT NOT NULL DEFAULT '',
	tokensIn   INTEGER NOT NULL DEFAULT 0,
	tokensOut  INTEGER NOT NULL DEFAULT 0,
	cost       REAL    NOT NULL DEFAULT 0,
	at         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_agent   ON usage(agentId);
CREATE INDEX IF NOT EXISTS idx_usage_session ON usage(sessionId);
CREATE INDEX IF NOT EXISTS idx_usage_at      ON usage(at);
`,
	},
	{
		id: 6,
		name: 'agent_messages',
		up: `
-- SendMessage bus: main<->main and main<->sub messages with steer/queued semantics.
CREATE TABLE IF NOT EXISTS agent_messages (
	id         TEXT PRIMARY KEY,
	fromAgent  TEXT NOT NULL,
	toAgent    TEXT NOT NULL,
	sessionId  TEXT NOT NULL DEFAULT '',
	kind       TEXT NOT NULL DEFAULT 'note', -- note | steer | result | terminal
	body       TEXT NOT NULL DEFAULT '',
	readAt     INTEGER,
	createdAt  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agentmsg_to   ON agent_messages(toAgent, readAt);
CREATE INDEX IF NOT EXISTS idx_agentmsg_from ON agent_messages(fromAgent);
`,
	},
	{
		id: 7,
		name: 'agent_todos',
		up: `
-- M17 待办表 (P2). The user parks thoughts here without interrupting the AI; the kernel
-- re-injects open items into the system prompt each round (Q2: mode 'always' | 'everyN').
-- 'source' records who added it, so the transcript never lies about authorship.
CREATE TABLE IF NOT EXISTS agent_todos (
	id         TEXT PRIMARY KEY,
	sessionId  TEXT NOT NULL DEFAULT '',
	text       TEXT NOT NULL,
	source     TEXT NOT NULL DEFAULT 'user', -- user | agent
	status     TEXT NOT NULL DEFAULT 'open', -- open | done
	doneAt     INTEGER,
	createdAt  INTEGER NOT NULL,
	updatedAt  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agenttodos_open ON agent_todos(status, createdAt);
CREATE INDEX IF NOT EXISTS idx_agenttodos_sess ON agent_todos(sessionId, status);
`,
	},
	{
		id: 8,
		name: 'peer_groups',
		up: `
-- M17 平级模式 (P3): peer agents share a task list and vote; there is deliberately NO
-- parent/depth here (unlike sessions), because peers are not a tree.
CREATE TABLE IF NOT EXISTS peer_groups (
	id         TEXT PRIMARY KEY,
	title      TEXT NOT NULL DEFAULT '',
	task       TEXT NOT NULL DEFAULT '',
	templateKey TEXT NOT NULL DEFAULT 'custom',
	status     TEXT NOT NULL DEFAULT 'open',   -- open | voting | settled | error
	winnerId   TEXT NOT NULL DEFAULT '',      -- agent whose vote carried it
	summary    TEXT NOT NULL DEFAULT '',
	createdAt  INTEGER NOT NULL,
	updatedAt  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS peer_proposals (
	id         TEXT PRIMARY KEY,
	groupId    TEXT NOT NULL,
	agentId    TEXT NOT NULL,
	text       TEXT NOT NULL DEFAULT '',
	vote       INTEGER NOT NULL DEFAULT 0,     -- net score: (#for) - (#against) for THIS proposal
	createdAt  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_peerprop_group ON peer_proposals(groupId);
CREATE INDEX IF NOT EXISTS idx_peerprop_agent ON peer_proposals(agentId);
`,
	},
	{
		id: 9,
		name: 'peer_votes',
		up: `
-- One row per (voter, target) so support ACCUMULATES. An earlier revision kept only a net
-- score on peer_proposals, which meant three peers backing one option could never reach 2
-- votes and every round ended in a tie. Votes are the source of truth; proposals.vote is a
-- derived cache kept for cheap reads.
CREATE TABLE IF NOT EXISTS peer_votes (
	groupId    TEXT NOT NULL,
	voterId    TEXT NOT NULL,
	targetId   TEXT NOT NULL,   -- the proposing agent whose option is being backed
	vote       INTEGER NOT NULL, -- 1 for / -1 against / 0 abstain
	createdAt  INTEGER NOT NULL,
	PRIMARY KEY (groupId, voterId, targetId)
);
CREATE INDEX IF NOT EXISTS idx_peervotes_group ON peer_votes(groupId);
CREATE INDEX IF NOT EXISTS idx_peervotes_target ON peer_votes(groupId, targetId);
`,
	},
];

export interface MigrationReport {
	applied: number[];
	alreadyCurrent: number;
	/** Non-fatal notes (e.g. a migration that was already applied out of band). */
	notes: string[];
}

export class AiDb {
	private readonly db: SqliteDb;

	constructor(dataDir: string, fileName = 'ai.db') {
		// better-sqlite3 will NOT create intermediate directories: if dataDir is missing it
		// throws "Cannot open database because the directory does not exist" at boot. The JSON
		// Store creates dataDir lazily on first read/write, so on a first run (or a fresh
		// machine) it does not exist yet by the time this constructor runs. Create it here.
		fs.mkdirSync(dataDir, { recursive: true });
		this.db = new Database(path.join(dataDir, fileName));
		this.db.pragma('journal_mode = WAL');
		this.db.pragma('foreign_keys = ON');
	}

	/** Raw handle — main-process only; never crosses IPC. */
	get raw(): SqliteDb { return this.db; }

	/**
	 * Apply every pending migration. Each runs in its own transaction and is recorded, so a
	 * crash mid-migrate leaves the DB at the last fully applied migration, never half-way.
	 */
	migrate(): MigrationReport {
		this.db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
			id INTEGER PRIMARY KEY,
			name TEXT NOT NULL,
			appliedAt INTEGER NOT NULL
		)`);

		const done = new Set(
			(this.db.prepare('SELECT id FROM schema_migrations').all() as { id: number }[]).map(r => r.id),
		);
		const mark = this.db.prepare('INSERT INTO schema_migrations (id, name, appliedAt) VALUES (?, ?, ?)');
		const applied: number[] = [];
		const notes: string[] = [];

		for (const m of MIGRATIONS) {
			if (done.has(m.id)) { continue; }
			const run = this.db.transaction(() => {
				this.db.exec(m.up);
				mark.run(m.id, m.name, Date.now());
			});
			try {
				run();
				applied.push(m.id);
			} catch (e) {
				notes.push(`迁移 ${m.id}(${m.name}) 失败: ${String(e)}`);
				throw new Error(`迁移 ${m.id}(${m.name}) 失败，已回滚；数据库停留在上一个完整版本。原因：${String(e)}`);
			}
		}
		return { applied, alreadyCurrent: done.size, notes };
	}

	/** Tables that must exist once migrate() has run — cheap startup self-check. */
	tables(): string[] {
		return (this.db.prepare(
			"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
		).all() as { name: string }[]).map(r => r.name);
	}

	schemaVersion(): number {
		const r = this.db.prepare('SELECT MAX(id) v FROM schema_migrations').get() as { v: number | null };
		return r?.v ?? 0;
	}

	close(): void { this.db.close(); }
}