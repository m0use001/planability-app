/**
 * The file library (B3): `file_library_list` / `_read` / `_write` / `_delete`.
 *
 * SCOPE — and this is the line worth being explicit about, because it is the same question I
 * declined to answer for `work_plan_start` and the answer here is deliberately NARROWER:
 *
 * Phone stores the library as real per-project DIRECTORIES (`ProjectFileRepo.dirOf(projectId)`,
 * `GLOBAL_ID = 0`, `_global`). PC's `Project` is a flat `{ name, emoji? }` record with no folder,
 * and I am NOT creating one: "give every project a directory" is a cross-cutting decision that
 * the Files page, the export directory and the phone mirror all have to agree on.
 *
 * What this DOES create is narrower and purely additive: a `library/` subdirectory of the
 * workspace, whose SUBDIRECTORIES are named after projects. Those are library-internal, not a
 * general "project folder" concept. Nothing outside the library can see them, no existing
 * convention is overridden (`exports/` from calendar_export_ics is a sibling), and it all goes
 * through `Workspace` so the confinement rules are the ones file tools already use.
 *
 * So: `project: ''` = global, `project: '期末复习'` = `library/期末复习/`. Consequence to state
 * plainly in the gap document — the LAYOUT is PC-local and does not mirror phone's on-disk one,
 * so the two ends' libraries are not interchangeable by path. Everything above this line (tool
 * names, params, the global/project split, auto-creating an unknown project directory) matches.
 *
 * `library_search` is deliberately NOT implemented. On phone it calls `RagIndex.search`, and
 * `RagIndex.chunkNote` indexes NOTES — so it is a second name for what `note_search` already does.
 * Adding it here would put two names on one behaviour, which is the same duplication already
 * refused for plugin_toggle.
 */

import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';
import type { WorkspaceFactory } from './file-tools';

export interface FileUndo {
	pushFile(rel: string, previous: string | null): void;
}

export interface LibraryToolDeps {
	/** Null when no workspace root is configured — same contract as file_write. Session-aware since P0-c. */
	getWorkspace: WorkspaceFactory;
	undo?: FileUndo;
}

export const LIBRARY_LIMITS = {
	/** Where the library lives, relative to the workspace root. */
	root: 'library',
	/** Deletes go here rather than unlinking, so a mistaken delete is recoverable by hand. */
	trash: '.trash',
	/** One write of a 20 MB "note" is not a note. */
	maxFileChars: 200_000,
	/** A read of an unbounded file floods the context that asked the question. */
	maxReadChars: 20_000,
	/** Listing a whole library tree one file at a time is how a tool call times out. */
	maxList: 200,
	maxDepth: 4,
} as const;

/**
 * Validate a caller-supplied name and return it as a workspace-relative path.
 *
 * `Workspace.confine` would catch a traversal anyway, but it reports it as "路径越出工作区根目录",
 * which is the wrong story: the user did not ask for an escape, they passed a name with a slash in
 * it. Sub-paths inside the library are allowed (phone's library has sub-directories too), so this
 * normalises rather than forbids — but an absolute path or a `..` segment is refused outright.
 */
export interface LibraryRelOk { ok: true; rel: string; reason?: undefined }
export interface LibraryRelBad { ok: false; rel?: undefined; reason: string }
export type LibraryRel = LibraryRelOk | LibraryRelBad;

export function libraryRel(project: string | undefined, name: string): LibraryRel {
	const proj = String(project ?? '').trim();
	const raw = String(name ?? '').trim();
	if (!raw) { return { ok: false, reason: '文件名为空。' }; }
	if (raw.includes('\0')) { return { ok: false, reason: '文件名含非法字符。' }; }
	if (/^[a-zA-Z]:/.test(raw) || raw.startsWith('/') || raw.startsWith('\\')) {
		return { ok: false, reason: '文件名必须是库内的相对路径，不能是绝对路径。' };
	}
	const segs = raw.split(/[\\/]+/).filter(s => s && s !== '.');
	if (!segs.length) { return { ok: false, reason: '文件名无效。' }; }
	if (segs.some(s => s === '..')) {
		return { ok: false, reason: '文件名不能包含 .. —— 库只按项目分子目录，不接受向上跳。' };
	}
	// A project NAME becomes a directory name; a name with a slash in it would nest by accident.
	if (proj && (/[\\/]/.test(proj) || proj.includes('\0'))) {
		return { ok: false, reason: '项目名必须是单个名字，不能含路径分隔符。' };
	}
	// "." and ".." are single segments, so the rule above lets them through — and a project
	// named ".." would build library/../<file>, which leaves the library entirely.
	if (proj === '.' || proj === '..') {
		return { ok: false, reason: `项目名「${proj}」无效。` };
	}
	const under = proj ? [proj, ...segs] : segs;
	return { ok: true, rel: [LIBRARY_LIMITS.root, ...under].join('/') };
}

export function registerLibraryTools(reg: ToolRegistry, deps: LibraryToolDeps): void {
	reg.setDomain('file');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: ToolContext) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('file_library_list', 'auto',
		'列出文件库里的文件（只读）。project 省略=全局；给出项目名则只列该项目。返回相对库根的路径、大小与修改时间。',
		{
			type: 'object',
			properties: {
				project: { type: 'string', description: '项目名；省略或空 = 全局' },
				depth: { type: 'number', description: `递归层数，1..${LIBRARY_LIMITS.maxDepth}` },
			},
		},
		async (input, ctx) => {
			const w = await deps.getWorkspace(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件库不可用。请先在设置里指定一个目录。'; }
			const proj = String(input?.project ?? '').trim();
			if (proj && /[\\/]/.test(proj)) { return '[FAIL] 项目名必须是单个名字，不能含路径分隔符。'; }

			const base = proj ? `${LIBRARY_LIMITS.root}/${proj}` : LIBRARY_LIMITS.root;
			const depth = Math.min(Math.max(Number(input?.depth ?? 1) || 1, 1), LIBRARY_LIMITS.maxDepth);
			const r = await w.list(base, depth);
			if (!r.ok) {
				// A missing directory is not an error — an empty library is the normal first state.
				if (/不存在|无法访问/.test(String(r.reason))) {
					return `文件库是空的（${base} 还没有任何文件）。`;
				}
				return `[FAIL] ${r.reason}`;
			}
			// .trash holds what file_library_delete moved aside. Listing it as library content
			// would mean deleted files keep showing up as if they were still there.
			const inTrash = (rel: string) =>
				rel === `${LIBRARY_LIMITS.root}/${LIBRARY_LIMITS.trash}`
				|| rel.startsWith(`${LIBRARY_LIMITS.root}/${LIBRARY_LIMITS.trash}/`);
			const rows = r.entries.filter(e => e.kind === 'file' && !inTrash(e.rel));
			if (!rows.length) {
				const dirs = r.entries.filter(e => e.kind === 'dir').length;
				return `文件库是空的${dirs ? `（${base} 下有 ${dirs} 个子目录但没有文件）` : ''}。`;
			}
			const shown = rows.slice(0, LIBRARY_LIMITS.maxList);
			const body = shown.map(e => {
				const kb = e.size == null ? '' : ` ${(e.size / 1024).toFixed(1)}K`;
				const when = e.modified ? ` ${new Date(e.modified).toISOString().slice(0, 10)}` : '';
				return `${e.rel}${kb}${when}`;
			}).join('\n');
			return rows.length > shown.length
				? `${body}\n\n（共 ${rows.length} 个文件，只列出前 ${shown.length} 个）`
				: body;
		});

	def('file_library_read', 'auto',
		'读取文件库里一份文件的全文（只读）。project 省略=在全局与所有项目里按文件名自动匹配。',
		{
			type: 'object',
			properties: {
				name: { type: 'string', description: '文件名，可用 library/ 下的相对子路径' },
				project: { type: 'string', description: '项目名；省略 = 全局与所有项目内自动匹配' },
			},
			required: ['name'],
		},
		async (input, ctx) => {
			const w = await deps.getWorkspace(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件库不可用。'; }
			const explicit = String(input?.project ?? '').trim();

			if (explicit) {
				const r0 = libraryRel(explicit, String(input?.name ?? ''));
				if (!r0.ok) { return `[FAIL] ${r0.reason}`; }
				return await readOne(w, r0.rel, explicit);
			}

			// Auto-match across every project, exactly as phone does. Ambiguity is the dangerous
			// case here: two files of the same name in different projects means "pick one" would
			// be a coin flip, so it reports the collision instead.
			const listing = await w.list(LIBRARY_LIMITS.root, LIBRARY_LIMITS.maxDepth);
			if (!listing.ok) { return '文件库是空的（还没有任何文件）。'; }
			const leaf = String(input?.name ?? '').trim().split(/[\\/]+/).pop() || '';
			const trashRoot = `${LIBRARY_LIMITS.root}/${LIBRARY_LIMITS.trash}/`;
			// Same reason as the listing: a deleted file must not be auto-matched by name.
			const hits = listing.entries.filter(e =>
				e.kind === 'file' && !e.rel.startsWith(trashRoot)
				&& (e.name === leaf || e.rel.endsWith('/' + leaf)));
			if (!hits.length) {
				return `文件库里没有找到「${leaf}」。可用 file_library_list 看看有哪些（project 省略则列出全局与所有项目）。`;
			}
			if (hits.length > 1) {
				return `「${leaf}」在文件库里有 ${hits.length} 份，无法判断你要哪一份：\n` +
					hits.slice(0, 10).map(h => `  ${h.rel}`).join('\n') +
					'\n请用 project 参数指定项目名。';
			}
			return await readOne(w, hits[0].rel, hits[0].rel.split('/')[1] || '全局');
		});

	def('file_library_write', 'confirm',
		'新建或覆盖文件库里的一份文件（写操作，需用户确认，可撤销）。project 省略=全局；给不存在的项目名会自动建目录。',
		{
			type: 'object',
			properties: {
				name: { type: 'string', description: '文件名' },
				content: { type: 'string', description: '全文内容' },
				project: { type: 'string', description: '项目名；省略 = 全局' },
			},
			required: ['name', 'content'],
		},
		async (input, ctx) => {
			const w = await deps.getWorkspace(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件库不可用。'; }
			const r = libraryRel(input?.project, String(input?.name ?? ''));
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
			const content = String(input?.content ?? '');
			if (!content.trim()) { return '[FAIL] content 为空，没有要写入的内容。'; }
			if (content.length > LIBRARY_LIMITS.maxFileChars) {
				return `[FAIL] 内容过长（${content.length} 字，上限 ${LIBRARY_LIMITS.maxFileChars}）。`;
			}

			// Snapshot AFTER approval (the approval card shows the content) and BEFORE the write,
			// so 撤销 restores the previous version. A new file pushes `null` = "did not exist".
			const snap = await w.snapshot(r.rel);
			if (!snap.ok) { return `[FAIL] ${snap.rel}：${snap.error}`; }
			const res = await w.write(r.rel, content);
			if (!res.ok) { return `[FAIL] ${res.reason}`; }
			deps.undo?.pushFile(res.rel, snap.previous);

			const proj = String(input?.project ?? '').trim();
			return snap.previous === null
				? `已在文件库新建 ${res.rel}（${res.bytes} 字节${proj ? `，项目「${proj}」` : ''}）。可用撤销删除。`
				: `已覆盖文件库中的 ${res.rel}（${res.bytes} 字节，原 ${snap.previous.length} 字节）。可用撤销恢复原文。`;
		});

	def('file_library_delete', 'danger',
		'从文件库删除一份文件（danger，需用户确认）。不是真删：会移进 library/.trash，可用 file_list 找回。',
		{
			type: 'object',
			properties: {
				name: { type: 'string', description: '文件名' },
				project: { type: 'string', description: '项目名；省略 = 全局' },
			},
			required: ['name'],
		},
		async (input, ctx) => {
			const w = await deps.getWorkspace(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件库不可用。'; }
			const r = libraryRel(input?.project, String(input?.name ?? ''));
			if (!r.ok) { return `[FAIL] ${r.reason}`; }

			// Restored through the same undo stack the other file writes use, so "撤销" puts the
			// exact text back rather than re-deriving it from a trash directory.
			const snap = await w.snapshot(r.rel);
			if (!snap.ok) { return `[FAIL] ${snap.rel}：${snap.error}`; }
			if (snap.previous === null) {
				return `[FAIL] 文件库里没有 ${r.rel}，未做改动。`;
			}
			const gone = await w.restore(r.rel, null);
			if (!gone.ok) { return `[FAIL] 删除失败：${gone.reason}`; }

			const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+$/, '');
			const name = r.rel.split('/').pop() || 'file';
			const to = `${LIBRARY_LIMITS.root}/${LIBRARY_LIMITS.trash}/${stamp}-${name}`;
			const moved = await w.write(to, snap.previous);
			deps.undo?.pushFile(r.rel, snap.previous);

			return `已从文件库删除 ${r.rel}。原文保留在 ${moved.ok ? moved.rel : to}，` +
				'可用撤销恢复，也可用 file_read 从回收站取回。';
		});
}

async function readOne(
	w: Workspace, rel: string, where: string,
): Promise<string> {
	const snap = await w.snapshot(rel);
	if (!snap.ok) { return `[FAIL] ${snap.rel}：${snap.error}`; }
	if (snap.previous === null) { return `文件库里没有 ${rel}。可用 file_library_list 看看有哪些。`; }
	const body = snap.previous;
	if (body.length > LIBRARY_LIMITS.maxReadChars) {
		return `${rel}（${where}）共 ${body.length} 字，以下是前 ${LIBRARY_LIMITS.maxReadChars} 字：\n` +
			body.slice(0, LIBRARY_LIMITS.maxReadChars) + '\n\n…（已截断，需要其余部分请让用户提供范围）';
	}
	return `${rel}（${where}，${body.length} 字）：\n${body}`;
}