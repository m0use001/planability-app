/* 双端项目同步 P0-c：把「项目」解析成一个 PC 本地目录。
 *
 * 解析链（跨端项目同步设计 §2.2）：
 *   1. project.sync（dump 带来的 folderId+rel）→ 用本机 Syncthing 文件夹配置把 folderId
 *      映射成本地路径再拼 rel。两端跑同一个 Syncthing，folder id 一致，零手工配置。
 *   2. PC 本地手动绑定（kv `project.dirPath.<id>`；不进 dump——手机路径在 PC 无意义）。
 *   3. 缺省 workspaceRoot/<项目名>（兜底，能跑但不保证对）。
 * 全部落空 → null（调用方回退到整个 workspaceRoot，即旧行为，不加新限制）。
 *
 * rel 来自 dump（不可信输入）：任何含 `..` 段、绝对路径形态、空串一律拒绝，
 * 静默落到下一档——不能让同步来的数据把 confine 根指到共享夹外面去。
 */

import * as path from 'node:path';
import type { Project } from '../../../shared/types';

export interface SyncFolderLite {
	id: string;
	path: string;
}

export interface ProjectRootDeps {
	loadProjects(): Promise<Project[]>;
	getSyncFolders(): Promise<SyncFolderLite[]>;
	workspaceRoot(): string;
	readBinding(projectId: string): Promise<string | undefined>;
}

export interface ProjectRoot {
	root: string;
	source: 'sync' | 'binding' | 'default';
}

/** 项目名 → 单段目录名：砍掉路径分隔符与非法字符，空了给占位名。 */
export function sanitizeSegment(name: string): string {
	const s = String(name ?? '')
		.replace(/[/\\:*?"<>|]/g, '_')
		.replace(/\s+/g, ' ')
		.trim();
	return s || '未命名项目';
}

/** dump 里的 rel 只收「干净的相对子路径」。返回 null = 拒绝（含 `..`、绝对路径、空）。 */
export function cleanRel(rel: string): string | null {
	const s = String(rel ?? '').trim();
	if (!s) { return null; }
	// Windows 盘符绝对路径（D:\x）split 后没有空段，会在纯分段检查里溜过去——单独立判。
	if (/^[a-zA-Z]:/.test(s) || s.startsWith('/') || s.startsWith('\\')) { return null; }
	const parts = s.split(/[\\/]+/);
	if (parts.some(p => p === '..' || p === '.' || p === '')) { return null; }
	return parts.join(path.sep);
}

export async function resolveProjectRoot(
	projectId: string, deps: ProjectRootDeps,
): Promise<ProjectRoot | null> {
	let project: Project | undefined;
	try {
		project = (await deps.loadProjects()).find(p => p.id === projectId);
	} catch { /* store read failure — fall through to null */ }
	if (!project) { return null; }

	// 1. dump 带来的 sync 字段：folderId 必须能在本机 Syncthing 配置里找到（两端 id 一致）。
	const sync = project.sync;
	if (sync?.folderId && sync.rel) {
		const rel = cleanRel(sync.rel);
		if (rel) {
			try {
				const folder = (await deps.getSyncFolders()).find(f => f.id === sync.folderId && f.path);
				if (folder) {
					return { root: path.resolve(folder.path, rel), source: 'sync' };
				}
			} catch { /* Syncthing not running — fall through */ }
		}
	}

	// 2. PC 本地手动绑定。
	try {
		const bound = await deps.readBinding(projectId);
		if (bound && bound.trim()) {
			return { root: path.resolve(bound.trim()), source: 'binding' };
		}
	} catch { /* kv read failure — fall through */ }

	// 3. 缺省：workspaceRoot/<项目名>。workspaceRoot 未配置则整体落空。
	const ws = deps.workspaceRoot();
	if (!ws) { return null; }
	return { root: path.join(path.resolve(ws), sanitizeSegment(project.name)), source: 'default' };
}
