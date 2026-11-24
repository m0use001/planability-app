/* M17 平级编排 (P3): fan a task out to 2-3 peers, then converge by majority vote.
 *
 * The peers are ordinary primary agents on the shared AgentManager — deliberately reusing the
 * existing concurrency gate instead of adding a second scheduler. What makes this "peer" and
 * not "tree" is everything here: each peer gets its OWN session, its OWN slot (no parentId,
 * no depth), and nothing is auto-rolled-up. Convergence is an explicit vote step.
 */

import type { AgentManager } from './agents/AgentManager';
import { PeerStore, type PeerGroup, type PeerVoteResult } from './peers';
import type { PeerSlot } from './peer-templates';

export interface PeerRunOptions {
	templateKey: string;
	/** exactly the slots to run — pre-filled from a template, user-editable (Q4 form 3) */
	slots: PeerSlot[];
	/** agent ids in the same order as slots; '' means "no agent assigned to that slot" */
	agentIds: string[];
	/** capped so a stray template cannot fan out more than the plan's budget */
	maxPeers?: number;
}

export interface PeerRunSummary {
	/** null when every slot was skipped — no group is created in that case (no zombie). */
	group: PeerGroup | null;
	slots: PeerSlot[];
	runIds: string[];
	skipped: { index: number; reason: string }[];
}

export class PeerOrchestrator {
	constructor(
		private readonly peers: PeerStore,
		private readonly agents: AgentManager,
	) { }

	/**
	 * Start a peer round. Each slot becomes a top-level primary run on its own session —
	 * there is no parent session, which is what distinguishes this from sub-agent derivation.
	 * Returns immediately; results land in the peer group (proposals must be submitted
	 * separately by the agent run, or by the caller).
	 */
	start(title: string, task: string, opts: PeerRunOptions): PeerRunSummary {
		const limit = Math.max(2, Math.min(opts.slots.length, opts.maxPeers ?? 3));
		const slots = opts.slots.slice(0, limit);

		// Decide what is runnable BEFORE creating the group: an all-skipped round used to
		// leave an empty 'open' group behind forever (a zombie the user cannot act on).
		const runnable: { index: number; agentId: string }[] = [];
		const skipped: { index: number; reason: string }[] = [];
		for (let i = 0; i < slots.length; i++) {
			const agentId = opts.agentIds[i] ?? '';
			if (!slots[i].prompt.trim()) { skipped.push({ index: i, reason: '该输入框为空，已跳过' }); continue; }
			if (!agentId) { skipped.push({ index: i, reason: '未指定 agent，已跳过' }); continue; }
			runnable.push({ index: i, agentId });
		}
		if (!runnable.length) {
			return { group: null as never, slots, runIds: [], skipped };
		}

		const group = this.peers.create(title, task, opts.templateKey);
		const runIds: string[] = [];
		for (const { index, agentId } of runnable) {
			const slot = slots[index];
			// One session per peer — siblings, not children.
			// The slot key is sanitised because main/index.ts recovers the group id from this
			// session id by splitting on the LAST underscore; keys like `opt_a` would otherwise
			// make the id ambiguous.
			const sid = `${group.id}_${slot.key.replace(/[^a-zA-Z0-9]/g, '')}`;
			this.agents.ensureSession(sid, `${group.title}·${slot.label}`, agentId);
			const run = this.agents.submitPrimary(agentId, sid, this.buildPrompt(group, slot));
			runIds.push(run.id);
		}
		return { group, slots, runIds, skipped };
	}

	/** Wrap the task with the peer role and the voting instruction. */
	private buildPrompt(group: PeerGroup, slot: PeerSlot): string {
		return [
			`# 协作任务：${group.title}`,
			group.task ? `\n任务：${group.task}` : '',
			`\n你的角色：${slot.label}（你是本次平级协作的 ${slot.label} 之一，彼此没有上下级，请独立作答）。`,
			`\n${slot.prompt}`,
			'\n完成后请给出你的结论，并明确指出你是否支持其他角色的方案（若你看到了他们的方案）。',
		].join('');
	}

	/** Record one peer's proposal (usually called from the agent run when it finishes). */
	propose(groupId: string, agentId: string, text: string): boolean {
		return this.peers.propose(groupId, agentId, text) !== null;
	}

	/** Cast a vote on a peer's proposal. vote: 1 / -1 / 0 */
	vote(groupId: string, voterId: string, targetAgentId: string, vote: number): boolean {
		return this.peers.vote(groupId, voterId, targetAgentId, vote);
	}

	/** All groups, newest first. */
	list(): PeerGroup[] { return this.peers.list(); }

	/** Drop a group and its proposals. */
	remove(groupId: string): boolean { return this.peers.remove(groupId); }

	/** Tally and settle. Ties deliberately stay unresolved for the user. */
	settle(groupId: string): PeerVoteResult {
		this.peers.setStatus(groupId, 'voting');
		return this.peers.settle(groupId);
	}

	/**
	 * Default vote when nobody spoke up: every peer backs its OWN proposal. With 3 peers
	 * that is 1-1-1 — a guaranteed tie, which the user then breaks. We deliberately do NOT
	 * invent cross-support: fabricating a majority would silently decide for the user.
	 * Cross-support only happens when peers actually read each other (peerVote).
	 */
	selfVote(groupId: string): number {
		let n = 0;
		for (const p of this.peers.proposals(groupId)) {
			if (this.peers.vote(groupId, p.agentId, p.agentId, 1)) { n++; }
		}
		return n;
	}

	/** Proposals rendered for a peer to read during the vote round. */
	voteBrief(groupId: string, forAgentId: string): string {
		const props = this.peers.proposals(groupId).filter(p => p.agentId !== forAgentId);
		if (!props.length) { return '（其他成员还没有提交方案）'; }
		return props.map((p, i) => `${i + 1}. ${p.agentId}：${p.text.slice(0, 300)}`).join('\n');
	}

	/** Everything the renderer needs to show the outcome. */
	view(groupId: string): {
		group: PeerGroup | null;
		proposals: { agentId: string; text: string; vote: number }[];
		result: PeerVoteResult | null;
	} {
		const group = this.peers.get(groupId);
		if (!group) { return { group: null, proposals: [], result: null }; }
		return {
			group,
			proposals: this.peers.proposals(groupId).map(p => ({ agentId: p.agentId, text: p.text, vote: p.vote })),
			// never settled twice
			result: group.status === 'settled' ? this.peers.tally(groupId) : null,
		};
	}
}