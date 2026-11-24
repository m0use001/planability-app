/* M20 backlog #5b: the agent kernel exposed as an MCP server (xcore6 pattern).
 *
 * The point is that the kernel stops being a private implementation detail of this Electron
 * app: another MCP client (an editor, another agent) can drive the same workflows, memory and
 * handoffs against the same files. Same code path, not a reimplementation.
 *
 * FOUR THINGS THAT ARE NOT OBVIOUS — and this is a SECURITY-SURFACED surface, so they matter:
 *
 * 1. THE APPROVAL MODEL DOES NOT SURVIVE THE BOUNDARY. Inside the app every write goes through
 *    an approval card; the MCP protocol has no such concept. So WRITES ARE OFF BY DEFAULT and
 *    must be switched on explicitly. Silently exposing a write tool to a remote caller would
 *    turn "the agent may write files after you approve" into "the agent may write files".
 *
 * 2. THE TOOL LIST IS FILTERED AT LIST TIME, NOT AT CALL TIME. Advertising a write tool and then
 *    refusing it teaches the caller to retry; not advertising it is unambiguous.
 *
 * 3. IT MUST NOT IMPORT `electron`. That is why this exposes the PURE modules only — no db.ts
 *    (better-sqlite3 is rebuilt against Electron's ABI and will not load under plain node), no
 *    index.ts, no provider. The same rule keeps it runnable as `node dist/mcp/server.js`.
 *
 * 4. PATHS ARE CONFINED. `workspace` is the root; every path-taking tool resolves and checks,
 *    because the caller is not necessarily this machine's user.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';

import { JournalStore, replayJournal, runWorkflow, validateWorkflow, type RunJournal } from './workflow';
import { applyProposals, MemoryLog, stageProposals, type MemoryProposal } from './memory-log';
import { assembleTeam, type AgentCapability } from './team-assemble';
import { parseHandoff, renderHandoff, writeHandoff } from './handoff';

export interface McpKernelDeps {
	/** Root every path-taking tool is confined to. */
	workspace: string;
	/** Registered agents, for team assembly. */
	agents?: AgentCapability[];
	/** Allow write tools (memory_add, handoff_write, workflow_run). Default false. */
	allowWrites?: boolean;
	/** Where journals and the memory log persist. Default `<workspace>/.planability`. */
	stateDir?: string;
	/** Actually run a step — supplied by the host, which owns the model. */
	runStep?: (role: string, input: string) => Promise<string>;
}

export type KernelToolName =
	| 'planability_workflow_list'
	| 'planability_workflow_validate'
	| 'planability_workflow_run'
	| 'planability_workflow_replay'
	| 'planability_memory_search'
	| 'planability_memory_history'
	| 'planability_memory_add'
	| 'planability_memory_forget'
	| 'planability_team_assemble'
	| 'planability_handoff_write'
	| 'planability_handoff_list'
	| 'planability_handoff_read';

export interface KernelToolSpec {
	name: KernelToolName;
	description: string;
	/** A write tool is not advertised unless allowWrites. */
	write: boolean;
	inputSchema: Record<string, unknown>;
}

/** Refuse a path that escapes the workspace. Symlink-free: we never follow into `..`. */
export function confine(workspace: string, candidate: string): string | null {
	const root = path.resolve(workspace);
	const abs = path.resolve(root, String(candidate ?? '.'));
	const rel = path.relative(root, abs);
	// `rel` starting with '..' (or being absolute, on Windows across drives) means escape.
	if (rel.startsWith('..') || path.isAbsolute(rel)) { return null; }
	return abs;
}

/** The full tool catalogue. Whether a tool is ADVERTISED depends on allowWrites. */
export function kernelTools(allowWrites: boolean): KernelToolSpec[] {
	const all: KernelToolSpec[] = [
		{
			name: 'planability_workflow_list',
			description: '列出内置的声明式工作流（步骤→角色→汇合）。',
			write: false,
			inputSchema: { type: 'object', properties: {} },
		},
		{
			name: 'planability_workflow_validate',
			description: '校验一个工作流定义，不执行它。返回全部错误而不是第一个。',
			write: false,
			inputSchema: {
				type: 'object',
				properties: { workflow: { type: 'object', description: '工作流定义' } },
				required: ['workflow'],
			},
		},
		{
			name: 'planability_workflow_run',
			description: '执行一个工作流并记录可回放的 journal。写操作。',
			write: true,
			inputSchema: {
				type: 'object',
				properties: {
					workflowId: { type: 'string' },
					task: { type: 'string', description: '任务目标；空则用工作流自带默认值' },
				},
				required: ['workflowId'],
			},
		},
		{
			name: 'planability_workflow_replay',
			description: '回放一次运行的 journal。不调任何 agent、不写任何东西——这正是用途。',
			write: false,
			inputSchema: { type: 'object', properties: { runId: { type: 'string' } }, required: ['runId'] },
		},
		{
			name: 'planability_memory_search',
			description: '在记忆库中检索（按名称/正文子串）。',
			write: false,
			inputSchema: {
				type: 'object',
				properties: { query: { type: 'string' }, limit: { type: 'number' } },
				required: ['query'],
			},
		},
		{
			name: 'planability_memory_history',
			description: '查看一条记忆的完整修改历史（append-only 审计日志）。',
			write: false,
			inputSchema: { type: 'object', properties: { key: { type: 'string' } }, required: ['key'] },
		},
		{
			name: 'planability_memory_add',
			description: '写入/更新一条记忆。写操作。',
			write: true,
			inputSchema: {
				type: 'object',
				properties: {
					name: { type: 'string' }, body: { type: 'string' },
					description: { type: 'string' },
					type: { type: 'string', enum: ['project', 'feedback', 'reference', 'handoff'] },
					importance: { type: 'number' }, scope: { type: 'string', enum: ['user', 'workspace'] },
				},
				required: ['name', 'body'],
			},
		},
		{
			name: 'planability_memory_forget',
			description: '删除一条记忆（软删：审计轨迹仍在，可用 restore 思路找回）。写操作。',
			write: true,
			inputSchema: { type: 'object', properties: { key: { type: 'string' } }, required: ['key'] },
		},
		{
			name: 'planability_team_assemble',
			description: '把一个模糊需求装配成团队提案。只出提案，不派发。',
			write: false,
			inputSchema: {
				type: 'object',
				properties: { requirement: { type: 'string' }, preferTemplates: { type: 'array', items: { type: 'string' } } },
				required: ['requirement'],
			},
		},
		{
			name: 'planability_handoff_write',
			description: '写一份固定四段的交接 markdown 到工作区。写操作。goal 为空会被拒绝。',
			write: true,
			inputSchema: {
				type: 'object',
				properties: {
					title: { type: 'string' }, goal: { type: 'string' },
					done: { type: 'string' }, blockers: { type: 'string' }, next: { type: 'string' },
				},
				required: ['goal'],
			},
		},
		{
			name: 'planability_handoff_list',
			description: '列出工作区里的交接文件。',
			write: false,
			inputSchema: { type: 'object', properties: {} },
		},
		{
			name: 'planability_handoff_read',
			description: '读取一份交接文件（路径限定在工作区内）。',
			write: false,
			inputSchema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
		},
	];
	return allowWrites ? all : all.filter(t => !t.write);
}

export interface Kernel {
	tools(): KernelToolSpec[];
	/** Invoke a tool. Unknown name and refused write both come back as errors, not throws. */
	call(name: string, input: Record<string, unknown>): Promise<{ ok: boolean; text: string }>;
}

const ok = (text: string) => ({ ok: true, text });
const bad = (text: string) => ({ ok: false, text });

/** Built-in workflows. Kept in sync with the app's by construction — same module. */
function builtinWorkflows(task = '（未指定任务）') {
	const { demoWorkflow } = require('./workflow') as typeof import('./workflow');
	return [demoWorkflow('research-plan-note', task)];
}

/**
 * Build the kernel. Everything is injected so this is testable without a model and without
 * Electron — and so a host can supply its OWN runStep (which is how the MCP server stays out of
 * the business of calling a provider).
 */
export function createKernel(deps: McpKernelDeps): Kernel {
	const workspace = path.resolve(deps.workspace);
	const stateDir = deps.stateDir ?? path.join(workspace, '.planability');
	const allowWrites = deps.allowWrites === true;
	const journals = new JournalStore();
	let memLog = new MemoryLog();

	const saveJournals = async (j: RunJournal) => {
		journals.add(j);
		await fs.mkdir(stateDir, { recursive: true });
		await fs.writeFile(path.join(stateDir, 'journals.json'), JSON.stringify(journals.list(500)), 'utf8');
	};
	const loadJournals = async () => {
		try {
			const raw = JSON.parse(await fs.readFile(path.join(stateDir, 'journals.json'), 'utf8'));
			if (Array.isArray(raw) && raw.length) { journals.add(raw[raw.length - 1] as RunJournal); }
		} catch { /* first run */ }
	};
	const saveMem = async () => {
		await fs.mkdir(stateDir, { recursive: true });
		await fs.writeFile(path.join(stateDir, 'memories.json'), JSON.stringify(memLog.toJSON()), 'utf8');
	};
	const loadMem = async () => {
		try {
			const raw = JSON.parse(await fs.readFile(path.join(stateDir, 'memories.json'), 'utf8'));
			if (Array.isArray(raw) && raw.length) { memLog = MemoryLog.fromJSON(raw); }
		} catch { /* first run */ }
	};
	// Kick off loading but do NOT make every call await it: the first call would otherwise pay
	// for disk, and the writes it guards would race it.
	const ready = Promise.all([loadJournals(), loadMem()]).catch(() => { });

	return {
		tools: () => kernelTools(allowWrites),

		call: async (name, rawInput) => {
			await ready;
			// A boundary is where junk arrives. Coerce instead of throwing: a caller sending
			// `null` arguments should get "no results", not a stack trace it has to interpret.
			const input: Record<string, unknown> =
				rawInput && typeof rawInput === 'object' && !Array.isArray(rawInput)
					? (rawInput as Record<string, unknown>)
					: {};
			// Defence in depth: the tool list already filters writes, so a write name here means
			// either allowWrites or a caller guessing names. Check anyway.
			const spec = kernelTools(true).find(t => t.name === name);
			if (!spec) { return bad(`未知工具 ${name}`); }
			if (spec.write && !allowWrites) {
				return bad(`工具 ${name} 是写操作，当前未开启（allowWrites=false）。` +
					'审批模型无法跨越 MCP 边界：应用内的每个写操作都要用户点确认，而 MCP 协议没有这个概念。');
			}
			try {
				switch (name) {
					case 'planability_workflow_list':
						return ok(builtinWorkflows().map(w =>
							`${w.id}｜${w.name}｜${w.steps.length} 步：${w.steps.map(s => `${s.id}(${s.role})`).join(' → ')}`
						).join('\n'));

					case 'planability_workflow_validate': {
						const v = validateWorkflow(input.workflow as any);
						return v.ok === true ? ok('校验通过。') : bad(`校验失败：${v.errors.join('；')}`);
					}

					case 'planability_workflow_run': {
						const wf = builtinWorkflows().find(w => w.id === String(input.workflowId));
						if (!wf) { return bad(`未知工作流 ${String(input.workflowId)}`); }
						const task = String(input.task ?? '').trim();
						if (!deps.runStep) {
							return bad('此宿主未提供 runStep，无法执行工作流（可改用 validate 与 replay）。');
						}
						const goal = task || wf.task;
						if (goal === '（未指定任务）') { return bad('需要提供任务描述。'); }
						const runId = journals.nextRunId();
						const r = await runWorkflow({ ...wf, task: goal }, deps.runStep, { runId });
						await saveJournals(r.journal);
						return ok(r.ok
							? `运行 ${runId} 完成（${r.journal.entries.length} 步）。可用 workflow_replay 回放。\n最终产出：\n${r.output.slice(0, 2000)}`
							: `运行 ${runId} 中断于 ${r.journal.failedAt}：${r.error}`);
					}

					case 'planability_workflow_replay': {
						const j = journals.get(String(input.runId));
						if (!j) { return bad(`找不到运行 ${String(input.runId)}`); }
						// No model call and no writes — that is the whole point of replay.
						return ok(replayJournal(j).lines);
					}

					case 'planability_memory_search': {
						const q = String(input.query ?? '').toLowerCase();
						const limit = Math.max(1, Math.min(Number(input.limit ?? 10) || 10, 50));
						const hits = memLog.list({ workspace }).filter(m =>
							!q || `${m.name} ${m.description} ${m.body}`.toLowerCase().includes(q));
						return ok(hits.length
							? hits.slice(0, limit).map(m => `${m.name} [${m.type}/${m.scope}] imp=${m.importance}\n  ${m.body.slice(0, 200)}`).join('\n')
							: '（无匹配记忆）');
					}

					case 'planability_memory_history': {
						const h = memLog.history(String(input.key ?? ''));
						return ok(h.length
							? h.map(e => `#${e.seq} ${e.kind} [${e.source}] ${e.scope}\n  ${e.body.slice(0, 200)}`).join('\n')
							: `未找到 ${String(input.key)} 的历史。`);
					}

					case 'planability_memory_add': {
						const r = applyProposals(memLog, [{
							name: String(input.name ?? ''), body: String(input.body ?? ''),
							description: String(input.description ?? ''),
							type: (input.type as any) ?? 'project',
							importance: input.importance as any,
							scope: (input.scope as any),
							reason: 'MCP memory_add',
						}], { workspace, source: 'tool' });
						await saveMem();
						return ok(r.applied.length
							? `已写入 ${r.applied.length} 条：${r.applied.map(a => `${a.key}(${a.kind})`).join('、')}`
							: `未写入：${r.rejected.map(x => `${x.name}:${x.reason}`).join('、')}`);
					}

					case 'planability_memory_forget': {
						const t = memLog.get(String(input.key ?? ''));
						if (!t) { return bad(`未找到记忆 ${String(input.key)}`); }
						if (t.forgotten) { return bad(`记忆 ${t.name} 已被删除。`); }
						memLog.append({
							kind: 'forget', key: t.id, name: t.name, body: t.body,
							description: t.description, type: t.type, importance: t.importance,
							scope: t.scope, workspace: t.workspace, source: 'tool', reason: 'MCP memory_forget',
						});
						await saveMem();
						return ok(`已删除记忆 ${t.name}。审计轨迹仍在历史里，可用 memory_history 查看。`);
					}

					case 'planability_team_assemble': {
						const r = assembleTeam({
							requirement: String(input.requirement ?? ''),
							agents: deps.agents ?? [],
							preferTemplates: Array.isArray(input.preferTemplates) ? input.preferTemplates.map(String) : undefined,
						});
						if (r.ok === false) { return bad(`${r.reason}：${r.detail}`); }
						const p = r.proposal;
						return ok([
							`模板：${p.template.name}（最弱一步 ${Math.round(p.weakest * 100)}%）`,
							...p.warnings.map(w => `注意：${w}`),
							'团队：',
							...p.steps.map(s => `  ${s.intent} ← ${s.agentName}（${Math.round(s.confidence * 100)}% · ${s.because}）`),
							'',
							'这是一个提案，未派发任何东西。确认后可交给 workflow_run。',
						].join('\n'));
					}

					case 'planability_handoff_write': {
						const dir = confine(workspace, 'handoff');
						if (!dir) { return bad('交接目录超出工作区。'); }
						const r = await writeHandoff(dir, input as any);
						return r.ok ? ok(`已写入 ${r.path}`) : bad(r.reason ?? '写入失败');
					}

					case 'planability_handoff_list': {
						const dir = confine(workspace, 'handoff');
						try {
							const names = (await fs.readdir(dir!)).filter(n => n.endsWith('.md')).sort().reverse();
							return ok(names.length ? names.join('\n') : '（工作区内没有交接文件）');
						} catch { return ok('（工作区内没有交接文件）'); }
					}

					case 'planability_handoff_read': {
						const abs = confine(workspace, path.join('handoff', path.basename(String(input.name ?? ''))));
						if (!abs || !abs.endsWith('.md')) { return bad('路径超出工作区，或不是 markdown 文件。'); }
						try { return ok(await fs.readFile(abs, 'utf8')); }
						catch { return bad(`读取失败：${String(input.name)}`); }
					}
				}
			} catch (e) {
				return bad(`执行失败：${(e as Error)?.message ?? e}`);
			}
			return bad(`工具 ${name} 未实现。`);
		},
	};
}

export { renderHandoff, parseHandoff, stageProposals, type MemoryProposal };
