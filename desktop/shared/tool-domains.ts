/* Tool domains — the ONE declaration both ends read, and the only place a tool's domain is set.
 *
 * WHY THIS EXISTS. Every tool used to be registered unconditionally, so `DesktopConfig.plugins`
 * (which has existed since Phase 2 with a comment saying "phone PluginMarket v1 shape") was a
 * switch with nothing behind it: the Plugins page toggled it and `registerPlanabilityTools` never
 * read it. The user's "don't let the AI touch my files" did nothing.
 *
 * WHY IN shared/. The renderer needs the same list (to draw the Plugins page and to show what a
 * disabled domain costs), and tsconfig.web cannot reach into src/main — the exact constraint that
 * cost the card catalogue a fourth copy. See shared/cards.ts.
 *
 * WHY DOMAIN IS SET PER MODULE AND NOT PER TOOL. A domain marks a whole CAPABILITY AREA, and the
 * 38 tools live in 7 modules that already group that way. Threading a domain argument through 38
 * call sites across 7 differently-spelled `def()` helpers would be 45 edits to say something the
 * module structure already says — and every new tool would be one more chance to forget it.
 * `ToolRegistry.setDomain()` sets it once per group instead.
 *
 * CLOSED, like the card catalogue: a tool with an unrecognised domain is still registered and
 * LOGGED. Silently dropping it would be the same failure as a silently-dropped filter — the
 * model asks for a tool that exists, gets "no such tool", and nothing on screen says a switch
 * did it.
 */

export interface ToolDomainDef {
	/** Key used in config: `tool:<id>`. */
	id: string;
	/** Shown in the Plugins page. */
	label: string;
	/** One line on what turning it off costs — a switch with invisible consequences gets flipped. */
	offCost: string;
	/** Cannot be turned off. */
	alwaysOn?: boolean;
}

/**
 * The catalogue. `system` is first and pinned: everything else is optional, that one is not.
 *
 * `office` and `ics` are declared but hold no tools yet. That is deliberate and is NOT the
 * "declared but unwired" mistake: the plug is reserved here so the day they land they belong to a
 * domain that already has a switch, instead of arriving as ungated capability. The
 * contract-drift test asserts the gap explicitly rather than letting it pass quietly.
 */
export const TOOL_DOMAINS: readonly ToolDomainDef[] = [
	{ id: 'system', label: '基础', offCost: '', alwaysOn: true },
	{ id: 'schedule', label: '日程', offCost: 'AI 不能查/建/改日程' },
	{ id: 'todo', label: '待办', offCost: 'AI 不能查/建/完成待办' },
	{ id: 'note', label: '笔记', offCost: 'AI 不能搜/读/写笔记' },
	{ id: 'focus', label: '专注', offCost: 'AI 不能查/补记专注记录（手机 focus 域对齐，2026-10-31）' },
	{ id: 'memory', label: '记忆', offCost: 'AI 不能检索或沉淀长期记忆' },
	{ id: 'file', label: '文件', offCost: 'AI 不能读写你指定的工作区目录（M18）' },
	{ id: 'web', label: '联网', offCost: 'AI 不能抓网页或搜索（这两个工具是 danger 级）' },
	{ id: 'shell', label: '命令', offCost: 'AI 不能执行任何命令' },
	{ id: 'cu', label: '电脑操作', offCost: 'AI 不能截图、不能点击/输入你的桌面' },
	{ id: 'agent', label: '子代理', offCost: 'AI 不能分派子任务、不能看团队状态' },
	{ id: 'extend', label: '扩展', offCost: 'AI 不能列出技能/MCP 服务器/助手/自动化，也不能切换模型或改能力开关' },
	{ id: 'office', label: '办公文件', offCost: 'AI 不能导出 Word/Excel/PPT 或纯文本文件（批 1：导出与读取；编辑三件套未实现）' },
	{ id: 'ics', label: '日历导出', offCost: 'AI 不能把日程导出成 .ics 文件' },
];

export const TOOL_DOMAIN_IDS: readonly string[] = TOOL_DOMAINS.map(d => d.id);

const BY_ID = new Map(TOOL_DOMAINS.map(d => [d.id, d]));
export function toolDomain(id: string): ToolDomainDef | undefined { return BY_ID.get(id); }

/** The config key for a domain switch. `tool:` distinguishes it from the feature toggles that
 *  already live in `plugins` (cards, undo, …), so a domain and a feature can never collide. */
export const toolDomainKey = (id: string): string => `tool:${id}`;

/**
 * Resolve which domains are enabled, or null for "all of them".
 *
 * Null, not empty, is the right default and it is load-bearing: a config written before this
 * feature existed has no `plugins` at all, and reading that as "nothing enabled" would silently
 * disarm every tool for every existing install — the single worst thing this file could do.
 */
export function enabledDomains(plugins: Record<string, boolean> | undefined): Set<string> | null {
	const off = TOOL_DOMAIN_IDS.filter(id =>
		!BY_ID.get(id)!.alwaysOn && plugins?.[toolDomainKey(id)] === false);
	// One answer for "nothing is switched off", whichever way it was written: undefined, {}, or an
	// object holding only feature toggles. Callers then have exactly one "no filtering" case to
	// handle instead of three that all mean the same thing.
	if (!off.length) { return null; }
	return new Set(TOOL_DOMAIN_IDS.filter(id => !off.includes(id)));
}

/** Domains currently disabled, for the Plugins page to explain what it is costing. */
export function disabledDomains(plugins: Record<string, boolean> | undefined): ToolDomainDef[] {
	const enabled = enabledDomains(plugins);
	if (!enabled) { return []; }
	return TOOL_DOMAINS.filter(d => !d.alwaysOn && !enabled.has(d.id));
}
