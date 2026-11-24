/* P5 read-only data exit: the app's planning collections are plain JSON files.
 *
 * WHY THIS LIVES OUTSIDE THE APP: an MCP client (ZCode, another agent) should be able to read
 * the user's memory without spawning Electron. The store's files are `<dataDir>/<collection>.json`,
 * and `jsonfile.ts` already has the read semantics we need (ENOENT = empty, unparseable =
 * quarantine, never silently empty). No better-sqlite3, no electron — the one thing the stdio
 * MCP server could never do is open the AI-session SQLite (Electron ABI), and it still cannot.
 *
 * WHY READ-ONLY: the caller is not necessarily this machine's user. The kernel's write gate is
 * `--allow-writes`; this module has no write path at all, so there is nothing to gate. The test
 * asserts the source contains no write calls — that is cheaper than remembering to add a gate.
 *
 * WHAT IT CANNOT SEE (honest limits):
 *   - Only what the phone dumps into the seven collections. v45's memory_evidence / scenes /
 *     persona are phone-side tables and are NOT in dump v1, so an external reader cannot answer
 *     "which messages support this memory". Adding them is a dump-contract bump, not a tool change.
 *   - Fields absent from dump v1 (priority/scene/status) simply do not exist here.
 */

import * as path from 'node:path';
import type { Memory } from '../../../shared/types';
import { readJsonFile } from '../jsonfile';

export interface DataToolDef {
	name: string;
	description: string;
	inputSchema: Record<string, unknown>;
}

export interface DataToolResult {
	ok: boolean;
	text: string;
}

/** The seven collections the cross-end dump contract carries (shared/types.ts CollectionName). */
const COUNTED = [
	'schedules', 'todos', 'notes', 'notebooks', 'memories', 'projects', 'assistants',
	// dump v2
	'memory_scenes', 'memory_persona', 'memory_evidence',
] as const;

/** ASCII words (≥2 chars) + CJK bigrams — the same token shape the phone's recall uses. */
function tokenize(query: string): string[] {
	const out: string[] = [];
	for (const w of query.toLowerCase().match(/[a-z0-9_]{2,}/g) ?? []) { out.push(w); }
	for (const run of query.match(/[\u2E80-\u9FFF\u3000-\u303F]+/g) ?? []) {
		if (run.length === 1) { out.push(run); }
		for (let i = 0; i + 1 < run.length; i++) { out.push(run.slice(i, i + 2)); }
	}
	return [...new Set(out)];
}

function excerpt(s: string, n: number): string {
	const t = s.trim().replace(/\s+/g, ' ');
	return t.length > n ? t.slice(0, n) + '…' : t;
}

export function createDataTools(storeDir: string): {
	tools: DataToolDef[];
	call: (name: string, input: Record<string, unknown>) => Promise<DataToolResult>;
} {
	const memoryFile = path.join(storeDir, 'memories.json');

	/** Read memories; a damaged file is a FAILURE, not an empty library. */
	async function loadMemories(): Promise<{ rows: Memory[]; error?: string }> {
		const { value, error, quarantinedTo } = await readJsonFile<Memory[]>(memoryFile, []);
		if (error) {
			return {
				rows: [],
				error: `记忆文件读取失败：${error}` + (quarantinedTo ? `（原件已移到 ${quarantinedTo}）` : ''),
			};
		}
		return { rows: value };
	}

	function score(m: Memory, toks: string[]): number {
		const name = (m.name ?? '').toLowerCase();
		const desc = (m.description ?? '').toLowerCase();
		const body = (m.body ?? '').toLowerCase();
		let s = 0;
		for (const t of toks) {
			if (name.includes(t)) { s += 3; }
			if (desc.includes(t)) { s += 2; }
			if (body.includes(t)) { s += 1; }
		}
		return s;
	}

	const tools: DataToolDef[] = [
		{
			name: 'planability_data_memory_search',
			description: '在 Planability 的长期记忆里做只读检索（名称/描述/正文，中文按二字组匹配）。返回条目摘要，正文需再取。',
			inputSchema: {
				type: 'object',
				properties: {
					query: { type: 'string', description: '检索词' },
					limit: { type: 'number', description: '返回条数，默认 10，上限 50' },
				},
				required: ['query'],
			},
		},
		{
			name: 'planability_data_memory_get',
			description: '按名称取一条长期记忆的完整内容（正文全文）。',
			inputSchema: {
				type: 'object',
				properties: { name: { type: 'string', description: '记忆名（唯一）' } },
				required: ['name'],
			},
		},
		{
			name: 'planability_data_memory_list',
			description: '按更新时间倒序列出长期记忆（摘要）。',
			inputSchema: {
				type: 'object',
				properties: {
					limit: { type: 'number', description: '返回条数，默认 50，上限 200' },
					type: { type: 'string', description: '可选：project / feedback / reference / handoff' },
				},
			},
		},
		{
			name: 'planability_data_counts',
			description: '各集合（日程/待办/笔记/记事本/记忆/项目/助手 + v2 场景/画像/证据）的条目数，用来判断有哪些数据可读。',
			inputSchema: { type: 'object', properties: {} },
		},
		{
			name: 'planability_data_scene_list',
			description: '列出 L2 场景（按热度倒序）：名字、摘要、热度。场景是记忆按「在做什么」聚合的结果。',
			inputSchema: {
				type: 'object',
				properties: { limit: { type: 'number', description: '返回条数，默认 15，上限 50' } },
			},
		},
		{
			name: 'planability_data_persona_get',
			description: '取 L3 长期画像全文（基础锚点/兴趣图谱/交互协议/认知内核四段）。',
			inputSchema: { type: 'object', properties: {} },
		},
		{
			name: 'planability_data_evidence_of',
			description: '查某条记忆的证据边（它由哪几轮对话支撑）。注意：消息正文不在 PC 同步范围内，只返回消息 id 与类型。',
			inputSchema: {
				type: 'object',
				properties: { memory: { type: 'string', description: '记忆名（唯一）' } },
				required: ['memory'],
			},
		},
	];

	async function call(name: string, input: Record<string, unknown>): Promise<DataToolResult> {
		if (name === 'planability_data_counts') {
			const counts: Record<string, number | string> = {};
			for (const c of COUNTED) {
				const { value, error } = await readJsonFile<unknown[]>(path.join(storeDir, `${c}.json`), []);
				counts[c] = error ? `读取失败：${error}` : value.length;
			}
			return { ok: true, text: JSON.stringify({ storeDir, counts }, null, 2) };
		}

		if (name === 'planability_data_scene_list') {
			const { value, error } = await readJsonFile<Array<{ name: string; summary?: string; heat?: number; project?: string }>>(
				path.join(storeDir, 'memory_scenes.json'), [],
			);
			if (error) { return { ok: false, text: `场景文件读取失败：${error}` }; }
			const sLimit = Math.max(1, Math.min(50, Number(input.limit ?? 15) || 15));
			const sItems = value
				.slice()
				.sort((a, b) => (b.heat ?? 0) - (a.heat ?? 0))
				.slice(0, sLimit)
				.map(s => ({
					name: s.name, heat: s.heat ?? 0,
					summary: excerpt(s.summary ?? '', 300), project: s.project ?? '',
				}));
			return { ok: true, text: JSON.stringify({ count: sItems.length, items: sItems }, null, 2) };
		}

		if (name === 'planability_data_persona_get') {
			const { value, error } = await readJsonFile<Array<{ body: string; version?: number; updatedAt?: number }>>(
				path.join(storeDir, 'memory_persona.json'), [],
			);
			if (error) { return { ok: false, text: `画像文件读取失败：${error}` }; }
			const p0 = value[0];
			if (!p0 || !p0.body) { return { ok: false, text: '还没有生成过画像' }; }
			return { ok: true, text: JSON.stringify(p0, null, 2) };
		}

		if (name === 'planability_data_evidence_of') {
			const memName = String(input.memory ?? '').trim();
			if (!memName) { return { ok: false, text: 'memory 不能为空' }; }
			const { value, error } = await readJsonFile<Array<{ memory: string; messageId: number; kind?: string }>>(
				path.join(storeDir, 'memory_evidence.json'), [],
			);
			if (error) { return { ok: false, text: `证据文件读取失败：${error}` }; }
			const rows = value.filter(e => e.memory === memName)
				.map(e => ({ messageId: e.messageId, kind: e.kind ?? 'support' }))
				.sort((a, b) => a.messageId - b.messageId);
			return {
				ok: true,
				text: JSON.stringify({
					memory: memName, count: rows.length, evidence: rows,
					note: '消息正文不在 PC 同步范围内，这里只表达「由哪几轮支撑」',
				}, null, 2),
			};
		}

		const { rows, error } = await loadMemories();
		if (error) { return { ok: false, text: error }; }

		if (name === 'planability_data_memory_search') {
			const q = String(input.query ?? '').trim();
			if (!q) { return { ok: false, text: 'query 不能为空' }; }
			const limit = Math.max(1, Math.min(50, Number(input.limit ?? 10) || 10));
			const toks = tokenize(q);
			const hits = rows
				.map(m => ({ m, s: score(m, toks) }))
				.filter(x => x.s > 0)
				.sort((a, b) => b.s - a.s || (b.m.updatedAt ?? 0) - (a.m.updatedAt ?? 0))
				.slice(0, limit)
				.map(({ m, s }) => ({
					name: m.name, type: m.type ?? '', description: m.description ?? '',
					body: excerpt(m.body ?? '', 300), updatedAt: m.updatedAt ?? 0, score: s,
				}));
			return { ok: true, text: JSON.stringify({ query: q, count: hits.length, hits }, null, 2) };
		}

		if (name === 'planability_data_memory_get') {
			const target = String(input.name ?? '').trim();
			const m = rows.find(r => r.name === target);
			if (!m) { return { ok: false, text: `没有名为「${target}」的记忆` }; }
			return { ok: true, text: JSON.stringify(m, null, 2) };
		}

		if (name === 'planability_data_memory_list') {
			const limit = Math.max(1, Math.min(200, Number(input.limit ?? 50) || 50));
			const type = String(input.type ?? '').trim();
			const list = rows
				.filter(m => !type || (m.type ?? '') === type)
				.sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
				.slice(0, limit)
				.map(m => ({
					name: m.name, type: m.type ?? '', description: m.description ?? '',
					body: excerpt(m.body ?? '', 300), updatedAt: m.updatedAt ?? 0,
				}));
			return { ok: true, text: JSON.stringify({ count: list.length, items: list }, null, 2) };
		}

		return { ok: false, text: `未知工具：${name}` };
	}

	return { tools, call };
}
