/* 双端项目同步 P0-d：PC 会话镜像器（对标手机 data/SessionMirror.kt）。
 *
 * 会话绑了项目且项目目录解析成功（P0-c 解析链）→ 每轮把会话正文全量重写成
 *   <项目根>/项目数据/当前会话/<标题>__pc<会话id>/对话.md
 * Syncthing 把它同步到手机，跨端续聊（P3）按目录名+mtime 取对方最近一份。
 *
 * 与手机侧的约定（两边都必须守）：
 * - **只镜像 user/assistant**：工具行是过程痕迹，镜像面向「人翻开文件夹看」。
 * - **全量重写、幂等**：调用方状态变化后喊一声，不算增量。
 * - **目录名带 `__pc<id>` 后缀**：手机目录是 `<标题>__<数字id>`，PC id 是 `pc_...` 字符串且
 *   带 pc 前缀，两边永不撞；手机的 ProjectDataMigration 只搬能命中它自己 chat_sessions 的
 *   目录，不会动 PC 的目录。**两端都把对方的镜像当只读**——手机侧只写自己的目录，
 *   PC 只写 __pc 目录，冲突域不存在。
 * - **标题改过名要自愈**：旧目录改名（不是删），里面的文件跟过去——与手机 ensureSessionDir
 *   同语义。
 * - PC 侧会话没有归档位（手机才有 archived），所以恒落「当前会话」；手机归档位来了再扩展。
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';

/** 与手机 ProjectDirs.TITLE_MAX 一致；目录名含 `__pc<id>` 后缀仍远低于 255 字节。 */
const TITLE_MAX = 30;
export const MIRROR_NAME = '对话.md';
const DATA_DIR = '项目数据';
const CURRENT_DIR = '当前会话';
const ARCHIVED_DIR = '已归档会话';

export interface MirrorMessage {
	role: 'system' | 'user' | 'assistant' | 'tool';
	content: string;
}

/** `<标题>__pc<会话id>`；标题规则与手机一致（30 字、非法字符转 _、空退化「对话」）。 */
export function sessionDirName(title: string, sessionId: string): string {
	const safe = String(title ?? '').trim()
		.replace(/[\\/:*?"<>|]/g, '_')
		.slice(0, TITLE_MAX)
		|| '对话';
	// 会话 id 只收安全字符：id 是我们自产的（pc_ 前缀 + base36），但过一遍不花钱，
	// 且把「目录名绝不含分隔符」变成结构性保证而不是调用方约定。
	const id = String(sessionId ?? '').replace(/[^a-zA-Z0-9_-]/g, '');
	return `${safe}__pc${id}`;
}

/** 手机镜像同款 md：# 标题 + ## 用户/AI 小节，只收 user/assistant。 */
export function mirrorMarkdown(title: string, history: MirrorMessage[]): string {
	const md = [`# ${String(title ?? '').trim() || '会话'}`, ''];
	for (const m of history ?? []) {
		if (m.role !== 'user' && m.role !== 'assistant') { continue; }
		md.push(`## ${m.role === 'user' ? '用户' : 'AI'}`, '');
		md.push(m.content?.trim() ? m.content : '（无文本内容）', '');
	}
	return md.join('\n');
}

/** 目录名白名单：单段、无分隔符、无 ..、以本会话的 __pc<id> 后缀收尾。 */
function validDirName(dirName: string, suffix: string): boolean {
	return !!dirName && !/[\\/:*?"<>|]/.test(dirName) && !dirName.includes('..')
		&& dirName.endsWith(suffix) && dirName !== suffix;
}

/**
 * 落盘：写 <root>/项目数据/当前会话/<dirName>/对话.md，并把同 `__pc<id>` 后缀的旧目录
 * 改名过来（标题自愈，不删内容）。best-effort：镜像失败绝不抛给会话主链路。
 */
export async function mirrorTo(
	root: string, dirName: string, md: string, sessionId: string,
): Promise<{ ok: true; abs: string } | { ok: false; reason: string }> {
	try {
		// root 与 dirName 都先各自过闸，再拼路径；每个进 fs 的动态路径都带 startsWith 边界校验。
		const rootAbs = path.resolve(root);
		if (!rootAbs || rootAbs === path.parse(rootAbs).root) {
			return { ok: false, reason: '项目根未解析。' };
		}
		// 会话 id 只收安全字符；id 是运行时传入的，不能假定它干净。
		const suffix = `__pc${String(sessionId ?? '').replace(/[^a-zA-Z0-9_-]/g, '')}`;
		if (!suffix || suffix === '__pc' || !validDirName(dirName, suffix)) {
			return { ok: false, reason: '镜像目录名非法。' };
		}
		/** 任何路径只有解析后仍落在 rootAbs 内才允许进 fs。 */
		const inside = (p: string): boolean => {
			const r = path.resolve(p);
			return r === rootAbs || r.startsWith(rootAbs + path.sep);
		};
		const currentBase = path.join(rootAbs, DATA_DIR, CURRENT_DIR);
		const archivedBase = path.join(rootAbs, DATA_DIR, ARCHIVED_DIR);
		const dir = path.join(currentBase, dirName);
		if (!inside(currentBase) || !inside(archivedBase) || !inside(dir)) {
			return { ok: false, reason: '镜像目标越出项目根，已拒绝。' };
		}
		// 标题自愈：同后缀不同名的旧目录整包改名（含历史产物文件），没有则跳过。
		for (const parent of [currentBase, archivedBase]) {
			let entries: string[] = [];
			try { entries = await fs.readdir(parent); } catch { continue; }
			for (const name of entries) {
				// readdir 的条目不会带分隔符，这里照过一遍闸再进 fs——防的是未来改动引入的洞。
				if (name === dirName || !name.endsWith(suffix) || !validDirName(name, suffix)) { continue; }
				const src = path.join(parent, name);
				if (!inside(src)) { continue; }
				await fs.rename(src, dir).catch(() => { /* 占用时放弃自愈 */ });
			}
		}
		await fs.mkdir(dir, { recursive: true });
		const target = path.join(dir, MIRROR_NAME);
		if (!inside(target)) { return { ok: false, reason: '镜像文件路径越出项目根，已拒绝。' }; }
		await fs.writeFile(target, md, 'utf8');
		return { ok: true, abs: target };
	} catch (e) {
		return { ok: false, reason: (e as Error)?.message ?? String(e) };
	}
}
