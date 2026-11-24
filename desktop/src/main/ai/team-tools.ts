/**
 * `team_assemble` — the last of the four N-class gaps that had an IPC handler and no tool.
 *
 * WHAT IT IS AND IS NOT. `assembleTeam` is pure computation: it infers intents from a one-line
 * requirement, scores the registered agents against each intent, and returns a roster plus the
 * runnable workflow. No model calls, no quota, no dispatch.
 *
 * So this tool is `auto`. That is not carelessness — it is the same conclusion the IPC handler
 * documents in its own comment: "Returns a PROPOSAL and dispatches nothing — the user presses the
 * button, which is the only sane default for spending quota on a guessed team." A team picked by
 * a keyword scorer is a SUGGESTION, and running it spends N agent turns on that suggestion.
 *
 * For the model the equivalent of "the user presses the button" is: show the roster, say plainly
 * that nothing was dispatched, and let the user say go — at which point `workflow_run` runs it,
 * because the workflow is registered on the same call.
 *
 * THE ONE THING ADDED FOR THE TOOL THAT THE IPC PATH DOES NOT NEED:
 *
 * `assembledWorkflows` is an unbounded Map keyed by workflow id. The IPC is user-driven — one
 * click, one entry. A model that calls this in a loop would grow it without limit, and every
 * entry is visible in `workflow_list`. So the tool reports the count and warns past a small
 * ceiling; it does NOT silently evict, because a workflow the user is about to run must not
 * disappear to make room for one nobody asked for.
 */

import type { ToolRegistry } from './tools';
import type { Assembly, StepIntent } from './team-assemble';

export interface TeamToolDeps {
	/**
	 * Runs assembleTeam against the LIVE agent list and registers the proposal's workflow, so
	 * `workflow_run` resolves it immediately. index.ts owns that map.
	 */
	assemble: (requirement: string, prefer?: string[]) => Assembly;
	/** How many assembled workflows are live right now. Drives the ceiling warning. */
	countRegistered: () => number;
}

export const TEAM_TOOL_LIMITS = {
	maxRequirementChars: 400,
	/** Past this many live assembled workflows, warn instead of adding silently. */
	registeredWarnAt: 8,
} as const;

const INTENT_LABEL: Record<StepIntent, string> = {
	research: '调研', plan: '规划', record: '记录', review: '审查', test: '测试', implement: '实现',
};

const pct = (n: number): string => `${Math.round((Number(n) || 0) * 100)}%`;

export function registerTeamTool(reg: ToolRegistry, deps: TeamToolDeps): void {
	// Own the domain: an unset domain lands in `system` (alwaysOn) and the tool becomes
	// un-switchable with nothing saying a switch ignored it.
	reg.setDomain('agent');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('team_assemble', 'auto',
		'按一句需求组一个团队：推断要做哪几件事、给每步挑最合适的 agent，并给出可直接运行的工作流。'
		+ '只出方案，不派发、不花额度 —— 要不要跑由用户决定，跑用 workflow_run。',
		{
			type: 'object',
			properties: {
				requirement: { type: 'string', description: '一句需求描述，越具体挑得越准' },
				preferTemplates: {
					type: 'array',
					description: '限定只考虑这些模板 key（来自 workflow_list）；省略 = 全部',
					items: { type: 'string' },
				},
			},
			required: ['requirement'],
		},
		async (input) => {
			const requirement = String(input?.requirement ?? '').trim();
			if (!requirement) { return '[FAIL] requirement 不能为空 —— 这是一句需求，不是一个动作。'; }
			if (requirement.length > TEAM_TOOL_LIMITS.maxRequirementChars) {
				return `[FAIL] 需求描述过长（${requirement.length} 字，上限 ${TEAM_TOOL_LIMITS.maxRequirementChars}）。` +
					'它是一句话，不是一份规格说明书。';
			}
			const prefer = Array.isArray(input?.preferTemplates)
				? input.preferTemplates.map((x: unknown) => String(x)).filter(Boolean)
				: undefined;

			// BEFORE assembling. The assembled map is unbounded and the IPC is user-driven — one
			// click, one entry. A model calling this in a loop grows it without limit, and every
			// entry shows up in workflow_list. Warn rather than evict: a workflow the user is about
			// to run must not vanish to make room for one nobody asked for.
			const live = deps.countRegistered();
			if (live >= TEAM_TOOL_LIMITS.registeredWarnAt) {
				return `[FAIL] 当前已有 ${live} 个待运行的组队方案（上限 ${TEAM_TOOL_LIMITS.registeredWarnAt}）。` +
					'先用 workflow_run 跑掉一个，或让用户在界面上清一清，再组新的。';
			}

			const r = deps.assemble(requirement, prefer);
			if (!r.ok) {
				// Assembly is a union, but this repo builds with strictNullChecks off, so `!r.ok`
				// does not narrow it. A named local is clearer than repeating the cast twice.
				const bad = r as { detail?: string; reason?: string };
				return `[FAIL] 组不出团队：${bad.detail || bad.reason || '未知原因'}`;
			}
			const p = r.proposal;
			const lines: string[] = [];
			lines.push(`模板「${p.template.name}」：${p.template.description}`);
			lines.push('');
			lines.push('分工：');
			for (const s of p.steps) {
				const label = INTENT_LABEL[s.intent] ?? s.intent;
				lines.push(`  ${label} → ${s.agentName}（${pct(s.confidence)}，${s.because}）`);
			}
			lines.push('');
			// The weak link is surfaced rather than averaged away — an average of 80% that hides
			// one step at 20% is exactly the number that gets a bad team approved.
			lines.push(`最弱一环：${pct(p.weakest)}`);
			if (p.warnings.length) {
				lines.push('提醒：');
				for (const w of p.warnings) { lines.push('  ' + w); }
			}
			lines.push('');
			lines.push(`工作流 ${p.workflow.id}（${p.workflow.steps.length} 步），已注册，可以用 workflow_run 跑。`);
			lines.push('');
			// The honest part: nothing was spent, nothing was started.
			lines.push('注意：这只是方案，没有派发、没有消耗额度。要不要跑由用户决定 ——');
			lines.push('用户同意后再调 workflow_run（可以带上本次想完成的目标）。');
			return lines.join('\n');
		});
}