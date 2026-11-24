/* M18 file domain — READ-ONLY batch (plan §3 M18 item 1).
 *
 * Every path the model supplies is untrusted. The only invariant that matters here is:
 *
 *      resolve(p) must stay inside resolve(workspaceRoot)
 *
 * Enforced with path.resolve() on BOTH sides plus a separator-anchored prefix check, then
 * re-checked after realpath() so a symlink cannot walk out of the tree. Both halves matter:
 * resolve() alone is defeated by symlinks, realpath() alone cannot judge a path that does
 * not exist yet.
 *
 * Deliberately NOT implemented in this batch (per plan): file_write / file_delete, the undo
 * snapshot, and the diff approval card. Read-only first, so the confinement logic can be
 * proven on its own before any mutating tool is allowed near it.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';

export interface ConfineResult {
	ok: boolean;
	/** absolute, normalised path — only meaningful when ok */
	abs: string;
	/** user-facing Chinese reason when !ok */
	reason?: string;
}

export interface FileEntry {
	/** path relative to the workspace root, using forward slashes */
	rel: string;
	name: string;
	kind: 'file' | 'dir';
	size?: number;
	modified?: number;
}

export interface FileReadResult {
	ok: boolean;
	rel: string;
	content?: string;
	size?: number;
	truncated?: boolean;
	/** set for binary / oversized files instead of content */
	note?: string;
	reason?: string;
}

/** Directories never walked or listed — noise at best, secrets at worst. */
const SKIP_DIRS = new Set(['node_modules', '.git', 'dist', 'out', 'build', '.next', '.cache', 'coverage', '.venv', '__pycache__']);
/** Hard caps so a single tool call cannot blow up the context window. */
export const LIMITS = {
	maxReadBytes: 256 * 1024,
	maxListEntries: 500,
	maxSearchResults: 100,
	maxDepth: 12,
} as const;

export class Workspace {
	readonly root: string;

	constructor(root: string) {
		// NOTE: path.resolve('') returns process.cwd() — so an empty root would silently
		// become the working directory. That is exactly the "no root configured quietly
		// widens the blast radius" failure, so keep it empty and report not-configured.
		this.root = root ? path.resolve(root) : '';
	}

	get configured(): boolean { return this.root.length > 0; }

	/**
	 * Confine a model-supplied path. Rejects absolute paths outside the root, `..` escapes,
	 * and (via the symlink re-check) links pointing out of the tree.
	 */
	async confine(rel: string): Promise<ConfineResult> {
		if (!this.configured) {
			return { ok: false, abs: '', reason: '未配置工作区根目录，文件工具已停用。请先在设置里指定一个目录。' };
		}
		const cleaned = String(rel ?? '').trim().replace(/^[/\\]+/, '');
		if (cleaned.includes('\0')) {
			return { ok: false, abs: '', reason: '路径含非法字符。' };
		}
		const abs = path.resolve(this.root, cleaned);
		// separator-anchored: '/root-evil' must not pass a '/root' prefix test
		if (abs !== this.root && !abs.startsWith(this.root + path.sep)) {
			return { ok: false, abs, reason: '路径越出工作区根目录，已拒绝。' };
		}
		// symlink / junction escape: resolve the real location and re-check containment
		try {
			const real = await fs.realpath(abs);
			const realRoot = await fs.realpath(this.root);
			if (real !== realRoot && !real.startsWith(realRoot + path.sep)) {
				return { ok: false, abs, reason: '路径经符号链接指向工作区之外，已拒绝。' };
			}
		} catch {
			// does not exist yet — resolve() containment above already covers it
		}
		return { ok: true, abs };
	}

	private toRel(abs: string): string {
		const r = path.relative(this.root, abs);
		return r === '' ? '.' : r.split(path.sep).join('/');
	}

	/** List one directory (non-recursive by default; depth<=1 recurses). */
	async list(rel: string, depth = 1): Promise<{ ok: boolean; entries: FileEntry[]; reason?: string }> {
		const c = await this.confine(rel);
		if (!c.ok) { return { ok: false, entries: [], reason: c.reason }; }
		const capped = Math.max(0, Math.min(depth, LIMITS.maxDepth));
		const out: FileEntry[] = [];
		const walk = async (dirAbs: string, level: number): Promise<void> => {
			if (level > capped || out.length >= LIMITS.maxListEntries) { return; }
			let items: import('node:fs').Dirent[];
			try { items = await fs.readdir(dirAbs, { withFileTypes: true }); }
			catch { return; }
			for (const it of items) {
				if (out.length >= LIMITS.maxListEntries) { return; }
				if (it.name.startsWith('.git')) { continue; }
				if (it.isDirectory() && SKIP_DIRS.has(it.name)) { continue; }
				const childAbs = path.join(dirAbs, it.name);
				let size: number | undefined, modified: number | undefined;
				if (it.isFile()) {
					try { const st = await fs.stat(childAbs); size = st.size; modified = st.mtimeMs; } catch { /* skip */ }
				}
				out.push({
					rel: this.toRel(childAbs),
					name: it.name,
					kind: it.isDirectory() ? 'dir' : 'file',
					size, modified,
				});
				if (it.isDirectory()) { await walk(childAbs, level + 1); }
			}
		};
		await walk(c.abs, 0);
		out.sort((a, b) => (a.kind === b.kind ? a.rel.localeCompare(b.rel) : a.kind === 'dir' ? -1 : 1));
		return { ok: true, entries: out };
	}

	/** Read a text file. Binary and oversized files come back with a note, never garbage. */
	async read(rel: string): Promise<FileReadResult> {
		const c = await this.confine(rel);
		if (!c.ok) { return { ok: false, rel: String(rel), reason: c.reason }; }
		let st;
		try { st = await fs.stat(c.abs); }
		catch { return { ok: false, rel: this.toRel(c.abs), reason: '文件不存在或无法访问。' }; }
		if (st.isDirectory()) { return { ok: false, rel: this.toRel(c.abs), reason: '这是一个目录，请用 file_list。' }; }
		if (st.size > LIMITS.maxReadBytes) {
			return {
				ok: false, rel: this.toRel(c.abs), size: st.size,
				note: `文件 ${st.size} 字节，超过单次读取上限 ${LIMITS.maxReadBytes}，未读取。`,
			};
		}
		const buf = await fs.readFile(c.abs);
		// NUL byte in the first 8KB is the standard "not text" signal on Windows.
		const probe = buf.subarray(0, Math.min(buf.length, 8192));
		if (probe.includes(0)) {
			return { ok: false, rel: this.toRel(c.abs), size: st.size, note: '二进制文件，未读取。' };
		}
		const text = buf.toString('utf8');
		// valid UTF-8 round-trips; replacement chars mean we guessed wrong about the encoding
		const suspicious = (text.match(/\uFFFD/g) || []).length;
		if (suspicious > 0 && suspicious / Math.max(1, text.length) > 0.01) {
			return { ok: false, rel: this.toRel(c.abs), size: st.size, note: '非 UTF-8 文本，未读取。' };
		}
		return { ok: true, rel: this.toRel(c.abs), content: text, size: st.size, truncated: false };
	}

	/**
	 * Snapshot before a write. Returns the previous content (or null when the file is new) so
	 * the undo stack can restore it — M18 plan §3 item 1 "写入前快照".
	 */
	async snapshot(rel: string): Promise<{ ok: boolean; rel: string; previous: string | null; error?: string }> {
		const c = await this.confine(rel);
		if (!c.ok) { return { ok: false, rel: String(rel), previous: null, error: c.reason }; }
		try {
			const st = await fs.stat(c.abs);
			if (st.isDirectory()) { return { ok: false, rel: this.toRel(c.abs), previous: null, error: '目标是目录。' }; }
			if (st.size > LIMITS.maxReadBytes) {
				return { ok: false, rel: this.toRel(c.abs), previous: null, error: `文件过大（${st.size} 字节），不做快照。` };
			}
			const buf = await fs.readFile(c.abs);
			if (buf.subarray(0, Math.min(buf.length, 8192)).includes(0)) {
				return { ok: false, rel: this.toRel(c.abs), previous: null, error: '二进制文件，不做快照。' };
			}
			return { ok: true, rel: this.toRel(c.abs), previous: buf.toString('utf8') };
		} catch {
			return { ok: true, rel: this.toRel(c.abs), previous: null }; // new file
		}
	}

	/** Write a text file, creating parent dirs. Confines first; callers must have approval. */
	async write(rel: string, content: string): Promise<{ ok: boolean; rel: string; bytes: number; created: boolean; reason?: string }> {
		const c = await this.confine(rel);
		if (!c.ok) { return { ok: false, rel: String(rel), bytes: 0, created: false, reason: c.reason }; }
		const existed = await fs.stat(c.abs).then(() => true).catch(() => false);
		await fs.mkdir(path.dirname(c.abs), { recursive: true });
		await fs.writeFile(c.abs, content, 'utf8');
		return { ok: true, rel: this.toRel(c.abs), bytes: Buffer.byteLength(content, 'utf8'), created: !existed };
	}

	/** Restore a snapshot (undo). `previous === null` means the file did not exist -> remove. */
	async restore(rel: string, previous: string | null): Promise<{ ok: boolean; rel: string; reason?: string }> {
		const c = await this.confine(rel);
		if (!c.ok) { return { ok: false, rel: String(rel), reason: c.reason }; }
		if (previous === null) {
			await fs.rm(c.abs, { force: true });
			return { ok: true, rel: this.toRel(c.abs) };
		}
		await fs.mkdir(path.dirname(c.abs), { recursive: true });
		await fs.writeFile(c.abs, previous, 'utf8');
		return { ok: true, rel: this.toRel(c.abs) };
	}

	/**
	 * Substring / regex search over workspace text files. Returns rel + line + match, so the
	 * model gets coordinates it can feed straight back into file_read.
	 */
	async search(query: string, opts: { regex?: boolean; glob?: string; limit?: number } = {}): Promise<
		{ ok: boolean; hits: { rel: string; line: number; text: string }[]; scanned: number; reason?: string }
	> {
		if (!this.configured) { return { ok: false, hits: [], scanned: 0, reason: '未配置工作区根目录，文件工具已停用。' }; }
		const q = String(query ?? '');
		if (!q.trim()) { return { ok: false, hits: [], scanned: 0, reason: '搜索词为空。' }; }
		let re: RegExp;
		try {
			re = opts.regex ? new RegExp(q, 'i') : new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
		} catch (e) {
			return { ok: false, hits: [], scanned: 0, reason: `正则无效：${String((e as Error).message)}` };
		}
		const globRe = opts.glob ? new RegExp(
			opts.glob.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*\*/g, '.*').replace(/\*/g, '[^/]*').replace(/\?/g, '.'),
			'i',
		) : null;
		if (!this.configured) {
			return { ok: false, hits: [], scanned: 0, reason: '未配置工作区根目录，文件工具已停用。' };
		}
		const limit = Math.max(1, Math.min(opts.limit ?? LIMITS.maxSearchResults, LIMITS.maxSearchResults));

		const hits: { rel: string; line: number; text: string }[] = [];
		let scanned = 0;
		const walk = async (dirAbs: string, level: number): Promise<void> => {
			if (level > LIMITS.maxDepth || hits.length >= limit) { return; }
			let items: import('node:fs').Dirent[];
			try { items = await fs.readdir(dirAbs, { withFileTypes: true }); } catch { return; }
			for (const it of items) {
				if (hits.length >= limit) { return; }
				if (it.name.startsWith('.git')) { continue; }
				const childAbs = path.join(dirAbs, it.name);
				if (it.isDirectory()) {
					if (SKIP_DIRS.has(it.name)) { continue; }
					await walk(childAbs, level + 1);
					continue;
				}
				const rel = this.toRel(childAbs);
				if (globRe && !globRe.test(rel)) { continue; }
				let st;
				try { st = await fs.stat(childAbs); } catch { continue; }
				if (st.size > LIMITS.maxReadBytes) { continue; }
				let text: string;
				try { text = await fs.readFile(childAbs, 'utf8'); } catch { continue; }
				if (text.includes('\0')) { continue; }
				scanned++;
				const lines = text.split(/\r?\n/);
				for (let i = 0; i < lines.length && hits.length < limit; i++) {
					if (lines[i] && re.test(lines[i])) {
						hits.push({ rel, line: i + 1, text: lines[i].trim().slice(0, 200) });
					}
				}
			}
		};
		await walk(this.root, 0);
		return { ok: true, hits, scanned };
	}
}
