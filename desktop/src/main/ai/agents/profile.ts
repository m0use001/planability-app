/* M17 AgentProfile — the persisted shape of a primary agent or sub-agent.
 *
 * The planning six collections stay in the JSON store; agent definitions live in SQLite
 * (main/ai/db.ts, table `agents`) so a team survives restarts. A profile extends the
 * phone-parity Assistant persona (name/color/systemPrompt) with the M17 knobs from
 * plan §3: tool allowlist, permission gear, turn ceiling and memory scope. */

import type { PermMode } from '../../../../shared/ipc';

/** DB row shape (agents table) — tools/memoryScope are JSON arrays in the column. */
export interface AgentRow {
	id: string;
	name: string;
	color: string;
	systemPrompt: string;
	tools: string;
	memoryScope: string;
	permMode: string;
	maxTurns: number;
	builtin: number;
	createdAt: number;
	updatedAt: number;
}

/** In-memory profile used by the agent runtime. */
export interface AgentProfile {
	id: string;
	name: string;
	color: string;
	systemPrompt: string;
	/** Allowed tool names; empty = inherit everything (no allowlist narrowing). */
	tools: string[];
	/** Memory domains this agent may read; empty = inherit all. */
	memoryScope: string[];
	permMode: PermMode;
	maxTurns: number;
	builtin: boolean;
	createdAt: number;
	updatedAt: number;
}

/** Budget a sub-agent run is allowed to burn (plan §3 M17 item 4). */
export interface AgentBudget {
	maxTurns: number;
	/** wall-clock ceiling in ms */
	maxMs: number;
	maxTokens: number;
}

export const DEFAULT_BUDGET: AgentBudget = { maxTurns: 12, maxMs: 120_000, maxTokens: 60_000 };

/** Narrow an unknown permMode string from the DB to the shared union. */
export function toPermMode(v: unknown): PermMode {
	return v === 'ro' || v === 'confirm' || v === 'auto' || v === 'full' ? v : 'auto';
}

function parseJsonArray(raw: unknown): string[] {
	if (Array.isArray(raw)) { return raw.filter((x): x is string => typeof x === 'string'); }
	if (typeof raw !== 'string' || !raw.trim()) { return []; }
	try {
		const v = JSON.parse(raw);
		return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
	} catch {
		return [];
	}
}

export function rowToProfile(r: AgentRow): AgentProfile {
	return {
		id: r.id,
		name: r.name,
		color: r.color,
		systemPrompt: r.systemPrompt ?? '',
		tools: parseJsonArray(r.tools),
		memoryScope: parseJsonArray(r.memoryScope),
		permMode: toPermMode(r.permMode),
		maxTurns: r.maxTurns > 0 ? r.maxTurns : DEFAULT_BUDGET.maxTurns,
		builtin: !!r.builtin,
		createdAt: r.createdAt,
		updatedAt: r.updatedAt,
	};
}

export function newAgentId(): string {
	return `ag_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Preset teams (plan §3 M17 item 2). builtin=1 rows are seeded on first run and refreshed
 * only when missing — a user who edits one keeps their edit.
 */
export const PRESET_TEAMS: ReadonlyArray<{ key: string; name: string; color: string; members: ReadonlyArray<Omit<AgentProfile, 'id' | 'createdAt' | 'updatedAt' | 'builtin'> & { key: string }> }> = [
	{
		key: 'team_plan',
		name: '规划团队',
		color: '#4C8DFF',
		members: [
			{
				key: 'team_plan_research',
				name: '研究员',
				color: '#4C8DFF',
				systemPrompt: '你负责收集与核实信息：查日程/待办/笔记/记忆，产出结构化事实清单。不要修改任何数据。',
				tools: [],
				memoryScope: [],
				permMode: 'ro',
				maxTurns: 10,
			},
			{
				key: 'team_plan_scheduler',
				name: '规划师',
				color: '#FF8800',
				systemPrompt: '你负责把事实转成可执行排程：日程与待办的具体日期、时间与优先级。写操作前先用一句话说明。',
				tools: [],
				memoryScope: [],
				permMode: 'confirm',
				maxTurns: 12,
			},
			{
				key: 'team_plan_scribe',
				name: '记录员',
				color: '#00AA88',
				systemPrompt: '你负责把结论写进笔记与笔记本，保持结构清晰、便于检索。',
				tools: [],
				memoryScope: [],
				permMode: 'confirm',
				maxTurns: 8,
			},
		],
	},
	{
		key: 'team_study',
		name: '学习团队',
		color: '#7C5CFF',
		members: [
			{
				key: 'team_study_tutor',
				name: '导师',
				color: '#7C5CFF',
				systemPrompt: '你负责讲解与出题：先讲清概念，再给练习与自测问题。回答用中文，结构化、结论先行。',
				tools: [],
				memoryScope: [],
				permMode: 'ro',
				maxTurns: 14,
			},
			{
				key: 'team_study_tracker',
				name: '进度跟踪',
				color: '#00AA88',
				systemPrompt: '你负责把学习计划落成待办与专注任务，并跟踪完成情况。',
				tools: [],
				memoryScope: [],
				permMode: 'confirm',
				maxTurns: 8,
			},
		],
	},
	{
		key: 'team_review',
		name: '三角色评审',
		color: '#FF5C7A',
		members: [
			{
				key: 'team_review_devil',
				name: '唱反调',
				color: '#FF5C7A',
				systemPrompt: '你负责找漏洞：假设方案要失败，列出风险、反例与被忽略的前提。不要给正面评价。',
				tools: [],
				memoryScope: [],
				permMode: 'ro',
				maxTurns: 10,
			},
			{
				key: 'team_review_arch',
				name: '架构师',
				color: '#4C8DFF',
				systemPrompt: '你负责给出可落地的结构方案与取舍理由，控制复杂度。',
				tools: [],
				memoryScope: [],
				permMode: 'ro',
				maxTurns: 10,
			},
			{
				key: 'team_review_editor',
				name: '决策者',
				color: '#FF8800',
				systemPrompt: '你负责综合前两方意见，给出最终结论与明确的取舍理由，只输出一个决定。',
				tools: [],
				memoryScope: [],
				permMode: 'ro',
				maxTurns: 8,
			},
		],
	},
];