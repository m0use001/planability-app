/* M18 file domain tools — READ-ONLY batch.
 *
 * All three are risk=auto: they cannot mutate anything, so they need no approval card. That
 * is the whole point of doing read-only first — the confinement logic in Workspace can be
 * proven before any writing tool is allowed near the same code path.
 *
 * The workspace root is resolved lazily per call (the user may change it in settings between
 * turns), and an unconfigured root disables the domain rather than widening it.
 */

import * as fs from 'node:fs/promises';
import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';

/** Snapshot store for file_write, keyed by the undo stack the agent already owns. */
export interface FileUndo {
	/** push a restore point; the agent's UndoStack calls this before a mutating tool runs */
	pushFile(rel: string, previous: string | null): void;
}

/**
 * P0-c（双端项目同步）：getWorkspace 从「单一 workspaceRoot」升级为「按会话项目切根」。
 * index.ts 传入的工厂会用 ctx.sessionId → 会话的 projectId → project-root 解析链；
 * 解析不出项目目录时回退整个 workspaceRoot（旧行为），所以本签名对旧调用方零破坏。
 */
export type WorkspaceFactory = (sessionId?: string) => Promise<Workspace | null>;

/**
 * Bounded LIFO of file snapshots. `previous === null` means "the file did not exist", so undo
 * removes it rather than writing empty content.
 */
export class FileUndoStack implements FileUndo {
	private stack: { rel: string; previous: string | null; at: number }[] = [];
	constructor(private readonly limit = 20) { }

	pushFile(rel: string, previous: string | null): void {
		this.stack.push({ rel, previous, at: Date.now() });
		if (this.stack.length > this.limit) { this.stack.shift(); }
	}

	get size(): number { return this.stack.length; }
	list(): { rel: string; created: boolean; at: number }[] {
		return this.stack.map(e => ({ rel: e.rel, created: e.previous === null, at: e.at }));
	}

	/** Pop the newest snapshot. Returns null when there is nothing to undo. */
	pop(): { rel: string; previous: string | null } | null {
		const e = this.stack.pop();
		return e ? { rel: e.rel, previous: e.previous } : null;
	}

	clear(): void { this.stack = []; }
}

export function registerFileTools(reg: ToolRegistry, getWorkspace: WorkspaceFactory, undo?: FileUndo): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: ToolContext) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	const ws = (sessionId?: string): Promise<Workspace | null> => getWorkspace(sessionId);

	def('file_list', 'auto',
		'列出工作区内的文件与目录（只读）。path 相对工作区根，depth 省略时只列一层。返回相对路径、大小与修改时间。',
		{ type: 'object', properties: { path: { type: 'string', description: '相对工作区根的路径，缺省为根' }, depth: { type: 'number', description: '递归层数，1..12' } } },
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const r = await w.list(String(input?.path ?? ''), Number(input?.depth ?? 1) || 1);
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
			if (!r.entries.length) { return '（空目录）'; }
			return r.entries.map(e =>
				e.kind === 'dir' ? `${e.rel}/` : `${e.rel}  ${fmtSize(e.size)}  ${fmtTime(e.modified)}`,
			).join('\n');
		});

	def('file_read', 'auto',
		'读取工作区内一个文本文件的内容（只读）。path 相对工作区根。二进制与超限文件会被拒绝并说明原因。',
		{ type: 'object', properties: { path: { type: 'string', description: '相对工作区根的文件路径' } }, required: ['path'] },
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const r = await w.read(String(input?.path ?? ''));
			if (!r.ok) { return `[FAIL] ${r.rel}：${r.note ?? r.reason}`; }
			return r.content ?? '';
		});

	def('file_search', 'auto',
		'在工作区文本文件内搜索（只读）。query 为搜索词或正则（regex=true 时按正则）。glob 可限定如 **/*.ts。返回 相对路径:行号:内容。',
		{
			type: 'object',
			properties: {
				query: { type: 'string', description: '搜索词' },
				regex: { type: 'boolean', description: 'query 是否为正则' },
				glob: { type: 'string', description: '限定文件类型，如 **/*.ts' },
				limit: { type: 'number', description: '最多返回多少条' },
			},
			required: ['query'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const r = await w.search(String(input?.query ?? ''), {
				regex: !!input?.regex, glob: input?.glob ? String(input.glob) : undefined,
				limit: Number(input?.limit) || undefined,
			});
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
		if (!r.hits.length) { return `（扫描 ${r.scanned} 个文件，无匹配）`; }
		return r.hits.map(h => `${h.rel}:${h.line}: ${h.text}`).join('\n')
			+ (r.hits.length >= (Number(input?.limit) || 100) ? '\n（已达返回上限，可能还有更多）' : '');
	});

	// ---- P3 跨端续聊：读手机端最近一份会话镜像 ----
	// 会话镜像落 项目数据/当前会话/<标题>__<数字id>/对话.md（手机）或 __pc<id>（本端）。
	// 本工具只读「对方端」的镜像：目录名不含 __pc 段，按 对话.md 的 mtime 取最新。
	// P0-c 之后会话绑了项目时工作区根就是项目目录，所以 `项目数据/当前会话` 直接在根下。
	def('file_read_peer_session', 'auto',
		'读取手机端（对方端）最近一份会话镜像，用于跨端续聊：当前项目 项目数据/当前会话 下、目录名不含 __pc 的会话目录，按 对话.md 的修改时间取最新一份，输出其正文。返回头写明会话名、更新时间与「第 X–Y 字 / 共 N 字」，没读完用 file_read_text 按返回的 path 续读。没有对方端镜像时如实说明，不要伪造。',
		{
			type: 'object',
			properties: {
				offset: { type: 'number', description: '从第几个字符开始读（0 开始）；首次不传=0' },
				limit: { type: 'number', description: '本次最多读多少字符（默认 8000，上限 200000）' },
			},
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const base = await w.confine('项目数据/当前会话');
			if (!base.ok) { return `[FAIL] ${base.reason}`; }
			let dirents: import('node:fs').Dirent[];
			try { dirents = await fs.readdir(base.abs, { withFileTypes: true }); }
			catch { return '（项目数据/当前会话 不存在或为空，没有对方端会话镜像。）'; }
			// 选对方端（目录名不含 __pc）最近 mtime 的 对话.md；confine 逐条过闸，路径只来自白名单拼装
			const candidates: { name: string; rel: string; mtime: number }[] = [];
			for (const d of dirents) {
				if (!d.isDirectory() || d.name.includes('__pc')) { continue; }
				const rel = `项目数据/当前会话/${d.name}/对话.md`;
				const c = await w.confine(rel);
				if (!c.ok) { continue; }
				try {
					const st = await fs.stat(c.abs);
					if (st.isFile()) { candidates.push({ name: d.name, rel, mtime: st.mtimeMs }); }
				} catch { /* 该会话目录里没有 对话.md */ }
			}
			const newest = pickNewestPeerMirror(candidates);
			if (!newest) { return '（没有找到对方端的会话镜像。只有本端（__pc 后缀）的目录，或目录里没有 对话.md。）'; }
			const text = await w.read(newest.rel);
			if (!text.ok) { return `[FAIL] ${newest.rel}：${text.note ?? text.reason}`; }
			const full = text.content ?? '';
			const total = full.length;
			const offset = Math.max(0, Number(input?.offset) || 0);
			const cap = Math.min(200_000, Math.max(1, Number(input?.limit) || 8_000));
			const end = Math.min(total, offset + cap);
			const head = `[会话镜像] ${newest.name} · 更新 ${fmtTime(newest.mtime)}\n第 ${offset + 1}–${end} 字 / 共 ${total} 字${end < total ? `（没读完，用 file_read_text 续读：path=${newest.rel}, offset=${end}）` : ''}\n`;
			return head + full.slice(offset, end);
		});

	// ---- write path ----
	// risk=confirm: the approval card shows the diff via args.preview (see runTool, which
	// forwards decision.args into the tool input). Snapshot happens INSIDE the tool, after
	// approval — a snapshot taken before approval would litter the undo stack with entries
	// for writes the user never allowed.
	def('file_write', 'confirm',
		'写入工作区内一个文本文件（写操作，需用户确认，确认卡会显示差异）。path 相对工作区根，content 为完整新内容。',
		{
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的文件路径' },
				content: { type: 'string', description: '文件的完整新内容' },
			},
			required: ['path', 'content'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const rel = String(input?.path ?? '');
			const content = String(input?.content ?? '');
			const c = await w.confine(rel);
			if (!c.ok) { return `[FAIL] ${c.reason}`; }

			const snap = await w.snapshot(rel);
			if (!snap.ok) { return `[FAIL] ${snap.rel}：${snap.error}`; }
			const wres = await w.write(rel, content);
			if (!wres.ok) { return `[FAIL] ${wres.reason}`; }
			undo?.pushFile(wres.rel, snap.previous);
			const st = snap.previous === null
				? `新建文件（${wres.bytes} 字节）`
				: `已覆盖（${wres.bytes} 字节，原 ${snap.previous.length} 字节）`;
			return `已写入 ${wres.rel}：${st}。可用撤销恢复。`;
		});
}

/** P3：从候选里取 mtime 最新的对方端镜像（纯函数，测试钉住「最新」与空集语义）。 */
export function pickNewestPeerMirror(
	candidates: { name: string; rel: string; mtime: number }[],
): { name: string; rel: string; mtime: number } | null {
	if (!candidates.length) { return null; }
	return candidates.reduce((a, b) => (b.mtime > a.mtime ? b : a));
}

function fmtSize(n?: number): string {
	if (n == null) { return ''; }
	if (n < 1024) { return `${n}B`; }
	if (n < 1024 * 1024) { return `${(n / 1024).toFixed(1)}K`; }
	return `${(n / 1024 / 1024).toFixed(1)}M`;
}

function fmtTime(ms?: number): string {
	if (!ms) { return ''; }
	const d = new Date(ms);
	const p = (x: number) => String(x).padStart(2, '0');
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
