/* M17 `task` tool — sub-agent derivation exposed to the model (answers pc-a's M17
 * acceptance ④, which was blocked because submitSub had a method + unit test but no way for
 * the model to actually reach it).
 *
 * The tool is async-fire-and-report: it submits the run and returns immediately with the run
 * id, rather than blocking the parent's tool loop until the child finishes. That keeps the
 * concurrency gate meaningful (the child occupies a real slot) and avoids a parent turn
 * hanging on a child that itself wants to call tools.
 */

import type { ToolRegistry } from './tools';

export interface SubAgentHost {
	/** Create the child's own session and submit the run. Returns null if the host is not ready. */
	spawn(agentId: string, parentSessionId: string, prompt: string): { id: string; status: string; sessionId: string; error?: string } | null;
	listAgents(): { id: string; name: string }[];
	/** In-flight runs (queued + running) with the concurrency ceiling. Null = kernel down. */
	runs(): {
		runs: { id: string; agentId: string; sessionId: string; kind: string; status: string; prompt: string; startedAt: number; error?: string }[];
		running: number; limit: number;
	} | null;
	/** Profile facts the management tools need for precise refusals (existence, builtin). */
	agent(id: string): { id: string; name: string; builtin: boolean } | null;
	/**
	 * Create or update an agent definition. The HOST enforces what the tool schema cannot
	 * express: builtin targets are refused, an unknown update id refuses instead of creating,
	 * and a NEW agent is pinned to the 'confirm' gear — the tool deliberately cannot pass
	 * permMode at all, so the engine's own missing-value fallback ('auto', the most permissive)
	 * must never be what decides how much a model-created agent can do without approval.
	 */
	upsert(p: {
		id?: string; name: string; color?: string; systemPrompt?: string;
		tools?: string[]; memoryScope?: string[]; maxTurns?: number;
	}): { ok: boolean; id?: string; name?: string; permMode?: string; error?: string };
	/** Delete a definition. The HOST distinguishes missing / builtin / deleted — a bare
	 *  boolean from the engine cannot, and "deleted" vs "never existed" is the difference
	 *  between success and a lie. */
	delete(id: string): { ok: boolean; error?: string };
	/** Queue a NOTE into another agent's inbox. `from` is displayed as written. */
	post(from: string, to: string, body: string): { ok: boolean; id?: string; error?: string };
}

/**
 * Host is resolved lazily: the registry is built before AgentManager exists, so the wiring
 * closes over a holder that index.ts fills in once the kernel is constructed.
 */
export function registerAgentTools(reg: ToolRegistry, holder: { host: SubAgentHost | null }): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: import('./tools').ToolContext) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('task', 'confirm',
		'派生一个子代理去完成一件独立的事（它有自己的会话与工具循环，结果不会自动并入你的回答）。' +
		'agent 为子代理 agent id，prompt 是给它的完整指令。返回 run id，你可以在回复里引用它。',
		{
			type: 'object',
			properties: {
				agent: { type: 'string', description: '子代理 agent id（先调用 agent_list 查看可选）' },
				prompt: { type: 'string', description: '给子代理的完整指令' },
			},
			required: ['agent', 'prompt'],
		},
		async (input, ctx) => {
			const host = holder.host;
			if (!host) { return '[FAIL] 多代理内核未就绪，无法派生子代理。'; }
			const agentId = String(input?.agent ?? '');
			const prompt = String(input?.prompt ?? '').trim();
			if (!agentId || !prompt) { return '[FAIL] task 需要 agent 与 prompt 两个参数。'; }
			// The parent session comes from the INVOCATION context, not from the model: the
			// model cannot know which session it is in, so asking it for a parent id silently
			// produced detached sub-agents (parentId='' , depth=0) with no visible subtree.
			const parentSessionId = ctx?.sessionId ?? '';
			const run = host.spawn(agentId, parentSessionId, prompt);
			if (!run) { return '[FAIL] 多代理内核未就绪，无法派生子代理。'; }
			if (run.error) { return `[FAIL] 派生子代理被拒：${run.error}`; }
			return `已派出子代理 ${agentId}（run ${run.id}，会话 ${run.sessionId}，状态 ${run.status}）。` +
				'它在自己的会话里工作，结果不会自动合并；需要它汇报就在它的会话里直接下令，或用 agent_runs 查看它是否还在跑。';
		});

	def('agent_list', 'auto',
		'列出可用的 agent（id + 名称），用于 task 工具选择子代理',
		{ type: 'object', properties: {} },
		async () => {
			const host = holder.host;
			if (!host) { return '（多代理内核未就绪）'; }
			const list = host.listAgents();
			if (!list.length) { return '（没有可用的 agent）'; }
			return list.map(a => `${a.id}  ${a.name}`).join('\n');
		});

	// ---- M17 管理面：task 派出去之后，模型此前既查不到 run、也动不了 agent 定义 ----
	// These four close the last IPC-without-a-tool cluster the N-section counted. Each one is
	// a thin, honest window onto methods index.ts already exposes to the UI — no second
	// implementation of anything, and every refusal says WHY.

	def('agent_runs', 'auto',
		'查看当前排队与运行中的 agent run（子代理任务与主代理回合）：run id、agent、状态、所在会话、已运行时长。' +
		'已完成的 run 不在此列——结果留在它的会话里（会话 id 就在列表中），要看结论请读那个会话。',
		{ type: 'object', properties: {} },
		async () => {
			const host = holder.host;
			const box = host?.runs();
			if (!box) { return '（多代理内核未就绪）'; }
			const head = `运行中 ${box.running} / 并发上限 ${box.limit}，排队与运行中的 run 共 ${box.runs.length} 条`;
			if (!box.runs.length) { return `${head}。当前没有进行中的 run。`; }
			const lines = box.runs.map(r => {
				const mins = Math.max(0, Math.round((Date.now() - r.startedAt) / 60000));
				const prompt = r.prompt.length > 60 ? r.prompt.slice(0, 60) + '…' : r.prompt;
				return `${r.id}  [${r.status}${r.kind === 'sub' ? '·sub' : ''}]  agent=${r.agentId}  会话=${r.sessionId}  已跑 ${mins} 分钟` +
					(r.error ? `  错误=${r.error}` : '') + `\n    指令：${prompt}`;
			});
			return [head, ...lines].join('\n');
		});

	def('agent_upsert', 'confirm',
		'创建或修改一个 agent 定义（名称/颜色/系统提示/工具白名单/记忆域/回合上限），修改只更新给出的字段。' +
		'内置 agent 不可修改。权限档位（permMode）刻意不在本工具参数里——档位只能由用户在界面上改，模型不能自己放宽确认要求；' +
		'新建 agent 固定为 confirm 档（它的写操作仍需用户审批）。风险提示：系统提示会被该 agent 之后的每一轮完整读取。',
		{
			type: 'object',
			properties: {
				id: { type: 'string', description: '修改时必填（先用 agent_list 查）；创建时省略' },
				name: { type: 'string', description: 'agent 名称（创建时必填）' },
				color: { type: 'string', description: '标识色，如 #4C8DFF' },
				systemPrompt: { type: 'string', description: '该 agent 的系统提示' },
				tools: { type: 'array', items: { type: 'string' }, description: '工具白名单；空数组 = 不收窄（继承全部）' },
				memoryScope: { type: 'array', items: { type: 'string' }, description: '可读的记忆域；空数组 = 全部' },
				maxTurns: { type: 'number', description: '回合上限（1-50）' },
			},
			required: ['name'],
		},
		async (input) => {
			const host = holder.host;
			if (!host) { return '[FAIL] 多代理内核未就绪，无法管理 agent 定义。'; }
			const name = String(input?.name ?? '').trim();
			if (!name) { return '[FAIL] agent_upsert 需要 name。'; }
			const id = String(input?.id ?? '').trim();
			const rawTurns = Number(input?.maxTurns);
			const r = host.upsert({
				...(id ? { id } : {}),
				name,
				...(input?.color !== undefined ? { color: String(input.color) } : {}),
				...(input?.systemPrompt !== undefined ? { systemPrompt: String(input.systemPrompt) } : {}),
				...(Array.isArray(input?.tools) ? { tools: input.tools.map(String) } : {}),
				...(Array.isArray(input?.memoryScope) ? { memoryScope: input.memoryScope.map(String) } : {}),
				// Clamped: a model asking for 9999 turns is asking to burn the quota ceiling.
				...(Number.isFinite(rawTurns) && rawTurns > 0 ? { maxTurns: Math.min(50, Math.floor(rawTurns)) } : {}),
			});
			if (!r.ok) { return `[FAIL] ${r.error ?? 'agent 定义未保存'}`; }
			return `已保存 agent「${r.name}」（id ${r.id}，权限档位 ${r.permMode ?? 'confirm'}）。`;
		});

	def('agent_delete', 'danger',
		'删除一个 agent 定义（需要确认）。内置 agent 不可删除；删除不影响已存在的会话与历史记录。',
		{
			type: 'object',
			properties: { id: { type: 'string', description: '要删除的 agent id（先用 agent_list 查）' } },
			required: ['id'],
		},
		async (input) => {
			const host = holder.host;
			if (!host) { return '[FAIL] 多代理内核未就绪，无法删除 agent。'; }
			const id = String(input?.id ?? '').trim();
			if (!id) { return '[FAIL] agent_delete 需要 id。'; }
			const r = host.delete(id);
			return r.ok ? `已删除 agent ${id}。` : `[FAIL] 未能删除：${r.error ?? '未知原因'}`;
		});

	def('agent_message', 'confirm',
		'向另一个 agent 的收件箱留一条留言（需要确认；from 写你自己的标识，通常是「主聊天」或某个 agent id）。' +
		'消息会落库并出现在对方收件箱与界面消息流里。注意：它是留言不是实时指令——当前引擎不会把它注入正在运行的回合，也不能替对方读取。',
		{
			type: 'object',
			properties: {
				from: { type: 'string', description: '发送方标识（显示用）' },
				to: { type: 'string', description: '目标 agent id' },
				body: { type: 'string', description: '消息正文' },
			},
			required: ['to', 'body'],
		},
		async (input) => {
			const host = holder.host;
			if (!host) { return '[FAIL] 多代理内核未就绪，无法发消息。'; }
			const to = String(input?.to ?? '').trim();
			const body = String(input?.body ?? '').trim();
			if (!to || !body) { return '[FAIL] agent_message 需要 to 与 body。'; }
			const from = String(input?.from ?? '主聊天').trim() || '主聊天';
			const r = host.post(from, to, body);
			return r.ok ? `已留言给 ${to}（消息 ${r.id}），它会在收件箱看到。` : `[FAIL] 留言失败：${r.error ?? '未知原因'}`;
		});
}
