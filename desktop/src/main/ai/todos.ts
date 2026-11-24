/* M17 待办表 (P2). A parking lot for thoughts the user does not want to interrupt the AI
 * with: they add items here, and the kernel re-injects the open ones into the prompt.
 *
 * Why not reuse the `todos` collection? Those are the user's *product* to-dos, exported to
 * the phone across the dump contract. This list is per-conversation scratch state for the
 * agent loop — it must never leak into pc-dump.json and never sync to the phone.
 *
 * Injection policy (Q2, user-selectable):
 *   'always'  — every round (model never forgets, costs a little context)
 *   'everyN'  — every N rounds (cheaper, the model may forget in between)
 * The list is capped: past `maxShown` open items we show the oldest ones and count the
 * rest, so a long list cannot crowd out the actual conversation.
 */

import type { AiDb } from './db';

export type TodoSource = 'user' | 'agent';
export type TodoStatus = 'open' | 'done';
/** Q2 selection. */
export type CheckMode = 'always' | 'everyN';

export interface AgentTodo {
	id: string;
	sessionId: string;
	text: string;
	source: TodoSource;
	status: TodoStatus;
	doneAt?: number;
	createdAt: number;
	updatedAt: number;
}

export interface CheckPolicy {
	mode: CheckMode;
	/** rounds between checks when mode = 'everyN' */
	everyN: number;
	/** most open items rendered into the prompt */
	maxShown: number;
}

export const DEFAULT_POLICY: CheckPolicy = { mode: 'always', everyN: 3, maxShown: 8 };

export class TodoStore {
	private policy: CheckPolicy = { ...DEFAULT_POLICY };

	constructor(private readonly db: AiDb) { }

	getPolicy(): CheckPolicy { return { ...this.policy }; }

	setPolicy(patch: Partial<CheckPolicy>): CheckPolicy {
		this.policy = {
			mode: patch.mode === 'everyN' ? 'everyN' : 'always',
			everyN: Math.max(1, Math.floor(patch.everyN ?? this.policy.everyN)),
			maxShown: Math.max(1, Math.floor(patch.maxShown ?? this.policy.maxShown)),
		};
		return this.getPolicy();
	}

	private row(r: any): AgentTodo {
		return {
			id: r.id,
			sessionId: r.sessionId ?? '',
			text: r.text,
			source: r.source === 'agent' ? 'agent' : 'user',
			status: r.status === 'done' ? 'done' : 'open',
			doneAt: r.doneAt ?? undefined,
			createdAt: r.createdAt,
			updatedAt: r.updatedAt,
		};
	}

	/** Park a thought. Returns '' for blank text (never creates an empty row). */
	add(text: string, sessionId = '', source: TodoSource = 'user'): AgentTodo | null {
		const t = String(text ?? '').trim();
		if (!t) { return null; }
		const now = Date.now();
		const id = `td_${now.toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
		this.db.raw.prepare(
			'INSERT INTO agent_todos (id,sessionId,text,source,status,doneAt,createdAt,updatedAt) VALUES (?,?,?,?,\'open\',NULL,?,?)',
		).run(id, sessionId, t, source, now, now);
		return { id, sessionId, text: t, source, status: 'open', createdAt: now, updatedAt: now };
	}

	open(sessionId?: string): AgentTodo[] {
		const rows = sessionId
			? this.db.raw.prepare('SELECT * FROM agent_todos WHERE status=\'open\' AND sessionId=? ORDER BY createdAt ASC').all(sessionId)
			: this.db.raw.prepare('SELECT * FROM agent_todos WHERE status=\'open\' ORDER BY createdAt ASC').all();
		return rows.map(r => this.row(r));
	}

	all(sessionId?: string): AgentTodo[] {
		const rows = sessionId
			? this.db.raw.prepare('SELECT * FROM agent_todos WHERE sessionId=? ORDER BY createdAt DESC').all(sessionId)
			: this.db.raw.prepare('SELECT * FROM agent_todos ORDER BY createdAt DESC').all();
		return rows.map(r => this.row(r));
	}

	done(id: string): boolean {
		const now = Date.now();
		return this.db.raw.prepare('UPDATE agent_todos SET status=\'done\', doneAt=?, updatedAt=? WHERE id=? AND status=\'open\'')
			.run(now, now, id).changes > 0;
	}

	reopen(id: string): boolean {
		const now = Date.now();
		return this.db.raw.prepare('UPDATE agent_todos SET status=\'open\', doneAt=NULL, updatedAt=? WHERE id=?')
			.run(now, id).changes > 0;
	}

	remove(id: string): boolean {
		return this.db.raw.prepare('DELETE FROM agent_todos WHERE id=?').run(id).changes > 0;
	}

	/**
	 * Render the open list for the system prompt. Returns '' when there is nothing to say, so
	 * callers can append unconditionally. `round` is 1-based.
	 */
	render(round = 1, sessionId?: string): string {
		if (!this.shouldCheck(round)) { return ''; }
		const open = this.open(sessionId);
		if (!open.length) { return ''; }
		const shown = open.slice(0, this.policy.maxShown);
		const lines = shown.map((t, i) => `${i + 1}. [${t.id}] ${t.text}`);
		let out = '\n\n# 待办表（用户或你此前记下的补充想法）\n' + lines.join('\n');
		if (open.length > shown.length) {
			out += `\n…另有 ${open.length - shown.length} 条未显示`;
		}
		out += '\n处理到哪条就用 agent_todo_done 标记完成；都不相关就别硬做。';
		return out;
	}

	/** Q2 gate: 'always' checks every round; 'everyN' checks on every Nth round. */
	shouldCheck(round: number): boolean {
		if (this.policy.mode === 'always') { return true; }
		const n = Math.max(1, this.policy.everyN);
		return round % n === 0 || round === 1;
	}
}