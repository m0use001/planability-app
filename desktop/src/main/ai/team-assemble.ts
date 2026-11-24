/* M20-3 fuzzy requirement -> team assembly.
 *
 * The plan's phrasing is "模糊需求→选模板装配团队" (DevWit). The hard part is NOT the matching;
 * it is that an automated assembly can pick the WRONG team and then spend real quota producing
 * confident work in the wrong shape. So this module is deliberately conservative:
 *
 * 1. IT NEVER GUESSES SILENTLY. Below the confidence floor it refuses and says what it would
 *    need. A wrong 3-agent team is more expensive than no team.
 *
 * 2. IT MATCHES ON DECLARED CAPABILITIES, NOT ON WISHFUL ONES. An agent's `tools` allowlist is
 *    the only evidence that it can actually do the step; its name is marketing.
 *
 * 3. THE RESULT IS A PROPOSAL, NOT AN ACTION. Nothing here dispatches. The user sees the team
 *    and presses the button, which is the only sane default for spending money.
 *
 * 4. IT REUSES peer-templates (M17 P3). Two systems that both describe "a team" must not invent
 *    their own vocabulary, or every existing template has to be translated twice.
 */

import { PEER_TEMPLATES, templateSlots, type PeerTemplate } from './peer-templates';
import type { Workflow, WorkflowStep } from './workflow';
import { validateWorkflow } from './workflow';

export interface AgentCapability {
	id: string;
	name: string;
	/** Tool allowlist. Empty = inherits everything, which counts as "can do anything". */
	tools?: string[];
}

export type StepIntent = 'research' | 'plan' | 'record' | 'review' | 'test' | 'implement';

export const INTENT_TOOLS: Record<StepIntent, string[]> = {
	// A review step with no read tools cannot review; a test step that cannot run anything
	// cannot test. These lists are the gate that keeps a mismatched team from being assembled.
	research: ['web_fetch', 'web_search', 'file_read', 'file_search', 'memory_search'],
	plan: ['memory_search', 'file_read', 'todo_query'],
	record: ['note_create', 'file_write'],
	review: ['file_read', 'file_search'],
	test: ['file_read'],
	implement: ['file_write', 'file_read'],
};

/** Below this we refuse rather than guess. A wrong team costs more than no team. */
export const CONFIDENCE_FLOOR = 0.34;

export interface AssemblyRequest {
	/** The user's fuzzy one-liner. */
	requirement: string;
	/** Registered agents to choose from. */
	agents: AgentCapability[];
	/** Restrict to these template keys; empty = consider all. */
	preferTemplates?: string[];
}

export type RejectReason =
	| 'no-agents'
	| 'no-template-fits'
	| 'requirement-too-short'
	| 'no-capable-agent';

export interface StepAssignment {
	intent: StepIntent;
	/** The agent chosen. */
	agentId: string;
	agentName: string;
	/** 0..1 — how well this agent can do THIS step. */
	confidence: number;
	/** Why this agent, in words the UI can show. */
	because: string;
}

export interface AssemblyProposal {
	requirement: string;
	template: PeerTemplate;
	steps: StepAssignment[];
	/** The runnable workflow, ready for M20-1. */
	workflow: Workflow;
	/** Lowest step confidence — the weak link, surfaced rather than averaged away. */
	weakest: number;
	/** Set when we had to fall back, so the UI can say the team is imperfect. */
	warnings: string[];
}

export type Assembly =
	| { ok: true; proposal: AssemblyProposal }
	| { ok: false; reason: RejectReason; detail: string };

/** Detect what the requirement actually asks for. Intents, not topics. */
export function inferIntents(text: string): StepIntent[] {
	const t = String(text ?? '').toLowerCase();
	const hit = (words: string[]) => words.some(w => t.includes(w));
	const intents: StepIntent[] = [];
	if (hit(['调研', '研究', '收集', '查找', 'search', 'research', '看看'])) { intents.push('research'); }
	if (hit(['计划', '规划', '排期', '方案', 'plan', 'roadmap', '设计'])) { intents.push('plan'); }
	if (hit(['记录', '笔记', '归档', '总结', 'note', 'document', 'document'])) { intents.push('record'); }
	if (hit(['审查', '检查', '评审', 'review', '挑问题'])) { intents.push('review'); }
	if (hit(['测试', '验证', 'test', 'verify'])) { intents.push('test'); }
	if (hit(['实现', '开发', '写代码', '实现一下', 'implement', 'build'])) { intents.push('implement'); }
	return intents;
}

/**
 * Can this agent do this step? An empty tools list inherits everything, which counts as capable —
 * the conservative reading would refuse every agent on a default config.
 *
 * `need` mixes REQUIRED and NICE-TO-HAVE tools, and conflating them was the first version's bug
 * in both directions: requiring a majority of `research`'s five tools disqualified an agent that
 * has web fetch and search (it can research; it just cannot search local files), while requiring
 * only one qualified a read-only agent as an implementer.
 *
 * So the rule is: the FIRST entry — the capability the role is DEFINED by — must be present, plus
 * at least one more when the list has three or more entries. Coverage is still reported so the UI
 * can rank agents by how well suited they are.
 */
export function agentCanDo(a: AgentCapability, intent: StepIntent): { ok: boolean; coverage: number } {
	const need = INTENT_TOOLS[intent];
	if (!a.tools || !a.tools.length) { return { ok: true, coverage: 1 }; }
	const have = new Set(a.tools);
	const matched = need.filter(n => have.has(n)).length;
	const coverage = need.length ? matched / need.length : 0;
	// Without the defining tool the agent cannot do this ROLE at all, so coverage is 0 rather
	// than "40% of the tools". Reporting 0.4 for a read-only agent on a research role made it tie
	// with a web agent that can actually do the job, and the tie then decided the winner.
	if (!have.has(need[0])) { return { ok: false, coverage: 0 }; }
	const ok = need.length <= 2 ? matched >= 1 : matched >= 2;
	return { ok, coverage };
}

/**
 * How well does this template fit the requirement?
 *
 * Scored on intents AND on keywords rather than the template's own description, because the
 * description is written for a human choosing from a menu and is not a reliable machine signal.
 */
export function scoreTemplate(t: PeerTemplate, req: { requirement: string; intents: StepIntent[] }): number {
	const tplIntents = t.slots.map(s => slotIntent(s.key));
	const text = `${t.name} ${t.description} ${t.slots.map(s => s.prompt).join(' ')}`.toLowerCase();
	const r = String(req.requirement ?? '').toLowerCase();
	let intentHits = 0;
	for (const i of req.intents) { if (tplIntents.includes(i)) { intentHits++; } }
	const intentScore = req.intents.length ? intentHits / req.intents.length : 0.5;
	// Word overlap as a weak secondary signal.
	const words = r.split(/[\s,，。、；;]+/).filter(w => w.length > 1);
	const wordHits = words.filter(w => text.includes(w)).length;
	const wordScore = words.length ? wordHits / words.length : 0;
	return intentScore * 0.75 + wordScore * 0.25;
}

function slotIntent(key: string): StepIntent {
	const map: Record<string, StepIntent> = {
		impl: 'implement', review: 'review', test: 'test',
		research: 'research', frontend: 'implement', backend: 'implement', docs: 'record',
	};
	return map[key] ?? 'review';
}

/** Build the prompt for a step, threading the requirement and the previous output. */
export function stepPrompt(intent: StepIntent, requirement: string): string {
	const head: Record<StepIntent, string> = {
		research: '收集与以下任务相关的事实与依据，只给要点，不要复述原始资料：',
		plan: '根据这些事实排出可执行计划，标出依赖与风险：',
		record: '把结果整理成一份可读的笔记，保留关键结论与待办：',
		review: '挑出这份结果里的问题，按严重程度排序并说明理由：',
		test: '针对以上内容列出应当验证的点，以及怎么验证：',
		implement: '给出可落地的实现方案，具体到文件与步骤：',
	};
	return `${head[intent]}\n${requirement}`;
}

/**
 * Assemble a team. Returns a proposal the user can accept — never dispatches anything.
 */
export function assembleTeam(req: AssemblyRequest): Assembly {
	const requirement = String(req.requirement ?? '').trim();
	const agents = req.agents ?? [];
	if (agents.length === 0) { return { ok: false, reason: 'no-agents', detail: '没有可用的 agent。' }; }
	if (requirement.length < 6) {
		return { ok: false, reason: 'requirement-too-short', detail: '需求太短，装配不出团队——先写清楚要做什么。' };
	}

	const intents = inferIntents(requirement);
	// No recognisable intent is NOT an error: fall back to the default team shape and say so.
	const effective = intents.length ? intents : (['research', 'plan', 'record'] as StepIntent[]);

	const pool = (req.preferTemplates?.length ? PEER_TEMPLATES.filter(t => req.preferTemplates!.includes(t.key)) : PEER_TEMPLATES);
	const ranked = [...pool].map(t => ({ t, score: scoreTemplate(t, { requirement, intents: effective }) }))
		.sort((a, b) => b.score - a.score);
	if (!ranked.length) { return { ok: false, reason: 'no-template-fits', detail: '没有可用的团队模板。' }; }

	const warnings: string[] = [];
	if (!intents.length) {
		warnings.push('没能从需求里识别出明确的动作类型，按「收集→排程→记录」装配。');
	}
	const template = ranked[0].t;

	// One agent per slot, chosen by capability. Reuse is allowed (two review roles can share a
	// capable agent) but every slot gets a DIFFERENT agent where possible, so the team is
	// actually a team and not the same persona three times.
	const used = new Set<string>();
	const steps: StepAssignment[] = [];
	for (const slot of templateSlots(template.key)) {
		const intent = slotIntent(slot.key);
		let best: { a: AgentCapability; c: number } | null = null;
		for (const a of agents) {
			const { ok, coverage } = agentCanDo(a, intent);
			if (!ok) { continue; }
			// Prefer an unused agent, but do not reject a used one if nobody else fits.
			const score = coverage - (used.has(a.id) ? 0.05 : 0);
			if (!best || score > best.c) { best = { a, c: score }; }
		}
		if (!best) {
			warnings.push(`角色「${slot.label}」没有合适的 agent，保留模板位置但不会被填充。`);
			continue;
		}
		used.add(best.a.id);
		steps.push({
			intent, agentId: best.a.id, agentName: best.a.name,
			confidence: Math.max(0, Math.min(1, best.c)),
			because: best.c >= 1
				? '工具集覆盖该步骤所需的全部能力。'
				: `覆盖该步骤所需的 ${Math.round(best.c * 100)}% 能力。`,
		});
	}

	if (!steps.length) {
		return { ok: false, reason: 'no-capable-agent', detail: '现有 agent 的工具集都不足以完成该团队的任一角色。' };
	}

	const weak = steps.reduce((m, s) => Math.min(m, s.confidence), 1);
	if (weak < CONFIDENCE_FLOOR) {
		return {
			ok: false,
			reason: 'no-capable-agent',
			detail: `最弱的一步只有 ${Math.round(weak * 100)}% 把握（阈值 ${Math.round(CONFIDENCE_FLOOR * 100)}%）。与其花钱跑一个错的团队，不如先补工具。`,
		};
	}
	if (weak < 0.6) {
		warnings.push(`最弱的一步只有 ${Math.round(weak * 100)}% 把握，跑之前值得确认。`);
	}

	// Emit a runnable workflow: the assembly is only useful if it can actually be dispatched.
	const wfSteps: WorkflowStep[] = steps.map((s, i) => ({
		id: `${template.key}-${s.intent}-${i}`,
		role: s.agentName,
		prompt: stepPrompt(s.intent, requirement) + (i > 0 ? '\n\n上一步的产出：\n{{prev}}' : ''),
	}));
	const wf: Workflow = {
		id: `assembled-${template.key}`, name: `${template.name}（自动装配）`,
		task: requirement, steps: wfSteps,
	};
	const v = validateWorkflow(wf);
	if (v.ok === false) {
		// Our own construction failing validation means a bug here, not a user error.
		return { ok: false, reason: 'no-template-fits', detail: `装配结果无效：${v.errors.join('；')}` };
	}

	return {
		ok: true,
		proposal: { requirement, template, steps, workflow: wf, weakest: weak, warnings },
	};
}
