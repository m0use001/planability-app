/* M17 平级模板 (P3 / Q4 第三种形态：模板预填 + 三个可编辑输入框).
 *
 * The user picks a starting set of roles, but every slot stays editable — the template is a
 * starting point, not a cage. "实现/审查/测试" and "前端/后端/文档" are both legitimate for a
 * mid-sized project, so we ship a few and let the user retype any of the three.
 */

export interface PeerSlot {
	/** role key inside the template */
	key: string;
	/** short label shown in the UI */
	label: string;
	/** pre-filled prompt for this slot */
	prompt: string;
	/** override for the agent's colour chip */
	color: string;
	/** which registered agent runs it ('' = first available) */
	agentId: string;
}

export interface PeerTemplate {
	key: string;
	name: string;
	description: string;
	slots: Omit<PeerSlot, 'agentId'>[];
}

function s(key: string, label: string, color: string, prompt: string) {
	return { key, label, color, prompt };
}

export const PEER_TEMPLATES: readonly PeerTemplate[] = [
	{
		key: 'impl_review_test',
		name: '实现 / 审查 / 测试',
		description: '通用中大型项目默认组合：出方案 → 挑问题 → 补测试，三方投票收敛。',
		slots: [
			s('impl', '实现', '#4C8DFF',
				'给出可落地的实现方案：改哪些文件、数据怎么迁移、边界情况怎么处理。要具体到函数与步骤，不要泛泛而谈。'),
			s('review', '审查', '#FF5C7A',
				'假设这个方案会失败。列出具体风险、反例和被忽略的前提，指出实现方案里站不住的部分。不要给正面评价。'),
			s('test', '测试', '#00AA88',
				'为这个方案设计验证方式：哪些用例必须覆盖、怎么构造失败场景、如何确认改动没破坏既有行为。'),
		],
	},
	{
		key: 'frontend_backend_docs',
		name: '前端 / 后端 / 文档',
		description: '按层拆分，适合有明确前后端边界的项目。',
		slots: [
			s('fe', '前端', '#4C8DFF',
				'从交互与状态角度给方案：用户看到什么、界面状态怎么流转、边界（空/加载/失败）怎么表现。'),
			s('be', '后端', '#FF8800',
				'从接口与数据角度给方案：接口形状、数据一致性、并发与失败处理。'),
			s('doc', '文档', '#00AA88',
				'写出这次改动需要补的说明：怎么用、有什么坑、后续维护要注意什么。'),
		],
	},
	{
		key: 'options',
		name: '三方案比选',
		description: '不预设分工：三个 agent 各自独立给一个方案，比选优劣。适合选型决策。',
		slots: [
			s('opt_a', '方案 A', '#4C8DFF', '独立提出一个可行方案，不需要顾虑别人提了什么，讲清代价与收益。'),
			s('opt_b', '方案 B', '#FF8800', '换一个思路独立提出方案，尽量与常见做法不同，讲清它在什么场景更合适。'),
			s('opt_c', '方案 C', '#7C5CFF', '再换一个思路，优先考虑改动最小、能最快落地的路径。'),
		],
	},
];

/** A template as three ready-to-edit slots (form 3: pre-filled, fully editable). */
export function templateSlots(key: string): PeerSlot[] {
	const t = PEER_TEMPLATES.find(x => x.key === key) ?? PEER_TEMPLATES[0];
	return t.slots.map(x => ({ ...x, agentId: '' }));
}

export const DEFAULT_PEER_TEMPLATE = PEER_TEMPLATES[0].key;