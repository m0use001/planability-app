/**
 * `skill_read` / `skill_write` — the other half of `skill_list` (an N-class gap closed).
 *
 * WHY THIS EXISTS. `skill_list` names the skills; it does not show what they SAY. A model that is
 * told "there is a skill called X" and cannot read X has been given a list of doors with no way
 * through any of them. That is the same class of incompleteness the three-bucket assertion in
 * contract-drift.test.cjs was built to surface, and it is the one this file fixes.
 *
 * THE ONE THING WORTH COPYING FROM `skills.ts` RATHER THAN REINVENTING:
 *
 * `writeSkillText` REFUSES an over-long file instead of truncating it, and the reason is spelled
 * out in its own comment: the loader silently keeps only the first `maxBodyChars` of a body, so a
 * truncated write would produce a document that loads as a quietly shortened thing AND is re-read
 * in full on every turn. So `skill_write` reports whatever `problems` the outcome carries — what
 * the loader will make of the text just written — instead of answering "done". A skill that the
 * loader will mangle is not a skill that was saved.
 *
 * THE BLAST RADIUS IS NOT OBVIOUS AND IS STATED IN THE DESCRIPTION:
 *
 * A user skill file is injected into EVERY subsequent turn's prompt (that is what
 * `selectSkills`/`renderSkills` do). Writing one is not "create a note"; it is "put text in front
 * of every future conversation". That is why this is `confirm`, why the description says so, and
 * why a busybody path — a model writing its own skill to guarantee it is always selected — is a
 * real risk the approval card exists for.
 */

import {
	writeSkillText, sanitizeKey, toSkill, builtinSkillFiles,
	resolveUserSkillFile, listUserSkillFiles, loadSkills, LIMITS,
} from './skills';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';

export interface SkillToolDeps {
	/** The user skills directory. Owned by index.ts because it is a configured location. */
	dir: () => string;
}

export const SKILL_TOOL_LIMITS = {
	/** A skill body is a procedure. Clipping is fine HERE because the reply is a read, not a write. */
	maxReadChars: LIMITS.maxBodyChars,
} as const;

/** Which file a key resolves to, applying the loader's own precedence: user file beats built-in. */
export interface SkillFileOk {
	ok: true; key: string; file: string; origin: 'user' | 'builtin'; reason?: undefined;
}
export interface SkillFileBad {
	ok: false; key?: undefined; file?: undefined; origin?: undefined; reason: string;
}
export type SkillFileResult = SkillFileOk | SkillFileBad;

export async function resolveSkillFile(dir: string, rawKey: string): Promise<SkillFileResult> {
	const key = sanitizeKey(rawKey);
	if (!key) { return { ok: false, reason: '技能标识清洗后为空（只允许字母 / 数字 / 中文 / . _ -）。' }; }

	// 1. 直接按文件 key 匹配（文件名去扩展名）。
	const userFile = resolveUserSkillFile(await listUserSkillFiles(dir).catch(() => []), key);
	if (userFile) { return { ok: true, key, file: userFile, origin: 'user' }; }

	// 2. 再按 loader 的 key 匹配 —— 它来自 frontmatter 的 name，而 skill_list 显示的就是它。
	//    两套 key 都存在（文件 key 走文件名，loader key 走 name），模型从 skill_list 拿到的
	//    名字在第一步里找不到。落空的理由是"两套名字"，不是"文件不存在"，所以这一步不是
	//    图方便，是把用户本不该承受的一层自己吃掉。
	const byLoader = await loadSkills({ userDir: dir }).catch(() => ({ skills: [] }));
	const hit = byLoader.skills.find(x =>
		sanitizeKey(x.name) === key || sanitizeKey(x.key) === key);
	if (hit) {
		const back = resolveUserSkillFile(await listUserSkillFiles(dir).catch(() => []),
			sanitizeKey(path.basename(hit.source ?? '').replace(/\.mdx?$/i, '')));
		if (back) { return { ok: true, key, file: back, origin: 'user' }; }
	}

	// 3. 内置技能。
	const builtin = builtinSkillFiles();
	if (Object.prototype.hasOwnProperty.call(builtin, key)) {
		return { ok: true, key, file: `${dir}/(builtin)`, origin: 'builtin' };
	}
	const byBuiltin = await loadSkills({ userDir: dir }).catch(() => ({ skills: [] }));
	const b = byBuiltin.skills.find(x => sanitizeKey(x.name) === key);
	if (b && b.source && !b.source.startsWith(dir)) {
		return { ok: true, key, file: `${dir}/(builtin)`, origin: 'builtin' };
	}
	return { ok: false, reason: `没有名为「${key}」的技能。` };
}

export function registerSkillTools(reg: any, deps: SkillToolDeps): void {
	// Own the domain rather than trusting the caller: an unset domain lands in `system`, which is
	// alwaysOn, and the tool would be un-switchable with nothing saying a switch ignored it.
	reg.setDomain('extend');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('skill_read', 'auto',
		'读一份技能的完整正文（只读）。skill_list 只给名字和简介，这里给内容。需要 key。',
		{
			type: 'object',
			properties: { key: { type: 'string', description: '技能标识，来自 skill_list' } },
			required: ['key'],
		},
		async (input) => {
			const dir = deps.dir();
			if (!dir) { return '[FAIL] 没有配置技能目录。'; }
			const r = await resolveSkillFile(dir, String(input?.key ?? ''));
			if (!r.ok) {
				// Name what IS available, so a near-miss key does not just dead-end.
				const { skills } = await loadSkills({ userDir: dir }).catch(() => ({ skills: [] }));
				const names = skills.slice(0, 30).map(s => s.key).join('、');
				return `[FAIL] ${r.reason}${names ? `\n现有的技能：${names}` : ''}`;
			}
			let text: string;
			if (r.origin === 'builtin') {
				text = builtinSkillFiles()[r.key] ?? '';
			} else {
				text = await fs.readFile(r.file, 'utf8').catch(() => '');
				if (!text) { return `[FAIL] 读不到 ${r.file}（可能已被删）。可用 skill_list 看看有哪些。`; }
			}
			const s = toSkill(r.key, text, r.file, r.origin);
			const head = `技能 ${r.key}（${s.name || '（无名称）'}，${r.origin === 'builtin' ? '内置' : '用户'}，${s.body.length} 字）`;
			const clipped = text.length > SKILL_TOOL_LIMITS.maxReadChars;
			const body = clipped ? text.slice(0, SKILL_TOOL_LIMITS.maxReadChars) + '\n\n…（已截断）' : text;
			const tail = s.problem ? `\n\n⚠ 这份技能有问题：${s.problem}` : '';
			const enabled = s.enabled ? '' : '\n\n⚠ 它当前是停用状态（enabled=false），不会注入对话。';
			return `${head}\n${body}${tail}${enabled}`;
		});

	def('skill_write', 'confirm',
		'写一份技能（写操作，需用户确认）。技能会进入【之后每一轮对话】的系统提示 —— ' +
		'不是"存个笔记"，是"往以后每次对话前面放一段话"。text 是带 frontmatter 的完整 md。',
		{
			type: 'object',
			properties: {
				key: { type: 'string', description: '技能标识（字母/数字/中文/. _ -）' },
				text: { type: 'string', description: '完整 md：frontmatter（name/description/keywords 等）+ 正文' },
			},
			required: ['key', 'text'],
		},
		async (input) => {
			const dir = deps.dir();
			if (!dir) { return '[FAIL] 没有配置技能目录。'; }
			const key = sanitizeKey(input?.key);
			if (!key) { return '[FAIL] 技能标识清洗后为空（只允许字母 / 数字 / 中文 / . _ -）。'; }
			const text = String(input?.text ?? '');
			if (!text.trim()) { return '[FAIL] text 为空，没有要写入的内容。'; }

			const res = await writeSkillText(dir, key, text);
			if (!res.ok) {
				// writeSkillText already refuses rather than truncating, so an error here is a real
				// refusal with a reason — pass it through verbatim instead of paraphrasing it.
				return `[FAIL] ${res.error ?? '写入失败'}`;
			}
			// The honest part: what will the LOADER make of the text just written? A skill the
			// loader will mangle is not a skill that was saved, and "done" would be a lie about it.
			const probs = res.problems ?? [];
			let reply = res.replaced
				? `已覆盖技能 ${key}（${res.file}）。`
				: `已新建技能 ${key}（${res.file}）。`;
			if (probs.length) {
				reply += '\n\n⚠ 它会被这样加载，和你写的并不完全一样：' + probs.join('；');
			} else {
				reply += '\n\n它会被原样加载，没有问题。';
			}
			reply += '\n注意：它从下一轮对话起按关键词注入系统提示，可在设置里停用。';
			return reply;
		});
}