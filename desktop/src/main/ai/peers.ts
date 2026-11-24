/* M17 平级模式 (P3): 2-3 peer agents working the same task, converging by majority vote.
 *
 * Deliberately NOT a tree. There is no parent, no depth, and no automatic roll-up into a
 * single answer — peers each produce one independent proposal, they vote, and the group
 * settles on the option with the most votes. A tie (or an all-abstain group) is handed back
 * to the user with the disagreement intact, because "we couldn't agree" is real information
 * and silently picking one would hide it.
 *
 * Why majority and not debate relay: relay is serial (latency adds up) and it develops a
 * "last speaker wins" bias — the final reviser's version becomes the answer regardless of
 * merit. Voting costs the same tokens but keeps the viewpoints independent, which is what
 * makes it useful for weighing options on a real project.
 */

import type { AiDb } from './db';

export type PeerStatus = 'open' | 'voting' | 'settled' | 'error';

export interface PeerGroup {
	id: string;
	title: string;
	task: string;
	templateKey: string;
	status: PeerStatus;
	/** agent whose proposal carried the vote ('' on a tie) */
	winnerId: string;
	summary: string;
	createdAt: number;
	updatedAt: number;
}

export interface PeerProposal {
	id: string;
	groupId: string;
	agentId: string;
	text: string;
	/** 1 for / -1 against / 0 abstain */
	vote: number;
	createdAt: number;
}

export interface PeerVoteResult {
	groupId: string;
	status: PeerStatus;
	winnerId: string;
	/** tally per option, keyed by proposing agent */
	tally: { agentId: string; text: string; votes: number }[];
	/** 'most votes wins' | 'tie' | 'no-proposals' | 'all-abstain' | 'already-settled' */
	outcome: 'decided' | 'tie' | 'no-proposals' | 'all-abstain' | 'already-settled';
	/** plain-text explanation, safe to show the user verbatim */
	explain: string;
}

export function newGroupId(): string {
	return `pg_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
}

export class PeerStore {
	constructor(private readonly db: AiDb) { }

	// ---- groups ----

	create(title: string, task: string, templateKey = 'custom'): PeerGroup {
		const now = Date.now();
		const g: PeerGroup = {
			id: newGroupId(), title: title || '平级协作', task: task || '',
			templateKey, status: 'open', winnerId: '', summary: '', createdAt: now, updatedAt: now,
		};
		this.db.raw.prepare(
			'INSERT INTO peer_groups (id,title,task,templateKey,status,winnerId,summary,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?)',
		).run(g.id, g.title, g.task, g.templateKey, g.status, g.winnerId, g.summary, g.createdAt, g.updatedAt);
		return g;
	}

	get(id: string): PeerGroup | null {
		const r = this.db.raw.prepare('SELECT * FROM peer_groups WHERE id=?').get(id) as any;
		if (!r) { return null; }
		return { ...r, status: r.status as PeerStatus };
	}

	list(): PeerGroup[] {
		return (this.db.raw.prepare('SELECT * FROM peer_groups ORDER BY createdAt DESC').all() as any[])
			.map(r => ({ ...r, status: r.status as PeerStatus }));
	}

	setStatus(id: string, status: PeerStatus): void {
		this.db.raw.prepare('UPDATE peer_groups SET status=?, updatedAt=? WHERE id=?').run(status, Date.now(), id);
	}

	// ---- proposals + votes ----

	/** One peer submits its independent proposal. One per agent per group (re-submit wins). */
	propose(groupId: string, agentId: string, text: string): PeerProposal | null {
		const t = String(text ?? '').trim();
		if (!t) { return null; }
		const existing = this.db.raw.prepare('SELECT id FROM peer_proposals WHERE groupId=? AND agentId=?').get(groupId, agentId) as any;
		const id = existing?.id ?? `pp_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
		const now = Date.now();
		if (existing) {
			this.db.raw.prepare('UPDATE peer_proposals SET text=?, createdAt=? WHERE id=?').run(t, now, id);
		} else {
			this.db.raw.prepare('INSERT INTO peer_proposals (id,groupId,agentId,text,vote,createdAt) VALUES (?,?,?,?,0,?)')
				.run(id, groupId, agentId, t, now);
		}
		return { id, groupId, agentId, text: t, vote: 0, createdAt: now };
	}

	/**
	 * Cast (or change) one agent's vote on another agent's option.
	 * Votes live in `peer_votes` keyed by (group, voter, target) so support ACCUMULATES —
	 * three peers backing one option must be able to reach 2+ votes.
	 */
	vote(groupId: string, voterId: string, targetAgentId: string, vote: number): boolean {
		const target = this.db.raw.prepare('SELECT id FROM peer_proposals WHERE groupId=? AND agentId=?').get(groupId, targetAgentId) as any;
		if (!target) { return false; }
		this.db.raw.prepare(
			`INSERT INTO peer_votes (groupId,voterId,targetId,vote,createdAt) VALUES (?,?,?,?,?)
			 ON CONFLICT(groupId,voterId,targetId) DO UPDATE SET vote=excluded.vote, createdAt=excluded.createdAt`,
		).run(groupId, voterId, targetAgentId, Math.sign(vote) || 0, Date.now());
		this.refreshScores(groupId);
		return true;
	}

	/** Raw per-voter votes (the source of truth behind the tally). */
	votes(groupId: string): { voterId: string; targetId: string; vote: number }[] {
		return (this.db.raw.prepare('SELECT voterId, targetId, vote FROM peer_votes WHERE groupId=? ORDER BY createdAt ASC').all(groupId) as any[])
			.map(r => ({ voterId: r.voterId, targetId: r.targetId, vote: r.vote as number }));
	}

	/** Recompute the cached net score on each proposal from the votes table. */
	private refreshScores(groupId: string): void {
		const votes = this.votes(groupId);
		const net = new Map<string, number>();
		for (const v of votes) {
			net.set(v.targetId, (net.get(v.targetId) ?? 0) + v.vote);
		}
		const upd = this.db.raw.prepare('UPDATE peer_proposals SET vote=? WHERE groupId=? AND agentId=?');
		for (const p of this.proposals(groupId)) {
			upd.run(net.get(p.agentId) ?? 0, groupId, p.agentId);
		}
	}

	proposals(groupId: string): PeerProposal[] {
		return (this.db.raw.prepare('SELECT * FROM peer_proposals WHERE groupId=? ORDER BY createdAt ASC').all(groupId) as any[])
			.map(r => ({ ...r, vote: r.vote as number }));
	}

	// ---- convergence ----

	/**
	 * Majority vote over the group's proposals. Decided when exactly one option leads with the
	 * most support; a tie is NOT resolved here — it goes back to the user (Q3 decision).
	 */
	tally(groupId: string): PeerVoteResult {
		const g = this.get(groupId);
		if (!g) {
			return { groupId, status: 'error', winnerId: '', tally: [], outcome: 'no-proposals', explain: '协作组不存在' };
		}
		const props = this.proposals(groupId);
		const base: PeerVoteResult = { groupId, status: g.status, winnerId: '', tally: [], outcome: 'decided', explain: '' };

		if (!props.length) {
			return { ...base, outcome: 'no-proposals', explain: '还没有任何 agent 提交方案。' };
		}
		// Net support per option (#for minus #against).
		const scored = props.map(p => ({ agentId: p.agentId, text: p.text, votes: p.vote }));
		const positives = scored.filter(s => s.votes > 0);
		if (!positives.length) {
			const anyNegative = scored.some(s => s.votes < 0);
			return {
				...base, outcome: 'all-abstain', tally: scored,
				explain: anyNegative ? '没有任何方案获得支持票。' : '所有 agent 都弃权，无法收敛。',
			};
		}
		const max = Math.max(...positives.map(s => s.votes));
		const leaders = positives.filter(s => s.votes === max);
		const explainLines = scored.map(s => `· ${s.agentId}：${s.votes > 0 ? `净支持 ${s.votes} 票` : s.votes < 0 ? `反对（净 ${s.votes}）` : '无支持'}`);

		if (leaders.length > 1) {
			// Tie: keep every option on the table and tell the user, per the Q3 decision.
			return {
				...base, outcome: 'tie', tally: scored,
				explain: `平票（各净 ${max} 票），未替你决定：${leaders.map(l => ` ${l.agentId}`).join('、')}\n${explainLines.join('\n')}`,
			};
		}
		const winner = leaders[0];
		return {
			...base, status: 'settled', winnerId: winner.agentId, outcome: 'decided', tally: scored,
			explain: `${winner.agentId} 的方案以净 ${max} 票通过。\n${explainLines.join('\n')}`,
		};
	}

	/** Tally and persist the outcome (only a decided vote writes a winner). */
	settle(groupId: string): PeerVoteResult {
		const r = this.tally(groupId);
		const status: PeerStatus = r.outcome === 'decided' ? 'settled' : 'open';
		this.db.raw.prepare('UPDATE peer_groups SET status=?, winnerId=?, summary=?, updatedAt=? WHERE id=?')
			.run(status, r.winnerId, r.explain, Date.now(), groupId);
		return r;
	}

	remove(groupId: string): boolean {
		this.db.raw.prepare('DELETE FROM peer_proposals WHERE groupId=?').run(groupId);
		return this.db.raw.prepare('DELETE FROM peer_groups WHERE id=?').run(groupId).changes > 0;
	}
}