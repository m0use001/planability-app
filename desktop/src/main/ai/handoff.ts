/* M20-4 handoff: a fixed four-section markdown document written to a synced folder.
 *
 * The value of a handoff is that a DIFFERENT session — possibly on the other end, possibly a
 * different person — can pick it up cold. So the format is fixed and the sections are mandatory,
 * because a free-form note is only readable by whoever wrote it.
 *
 * THREE THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. THE DOCUMENT IS WRITTEN TO A FILE, NOT RETURNED AS A STRING. A handoff you cannot find later
 *    is not a handoff. It goes next to the synced workspace folder so the phone can see it.
 *
 * 2. UNKNOWN SECTIONS ARE FILLED WITH AN EXPLICIT "未填写", never omitted. A reader that finds
 *    a missing section does not know whether it was empty or forgotten; an explicit marker is
 *    answerable and greppable.
 *
 * 3. NOTHING IS EXECUTED OR INTERPOLATED. Same rule as skills: this is a document that a model
 *    will read, so a section containing instructions must arrive as text.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';

export interface Handoff {
	title: string;
	/** What the next person is being asked to do. */
	goal: string;
	/** What was already done. */
	done: string;
	/** What is broken, blocked, or risky. */
	blockers: string;
	/** Where to look: files, commands, decisions. */
	next: string;
}

export const SECTIONS = [
	{ key: 'goal', label: '目标（接手时要达成什么）' },
	{ key: 'done', label: '已完成' },
	{ key: 'blockers', label: '阻塞与风险' },
	{ key: 'next', label: '下一步与落点' },
] as const;

export type SectionKey = typeof SECTIONS[number]['key'];

/** Longest a single section may be. A handoff is a summary; a novel is a file in the repo. */
export const LIMITS = {
	maxSectionChars: 4000,
	maxTitleChars: 120,
	/** Refuse to write a handoff with no goal at all — that is not a handoff. */
} as const;

const EMPTY_MARK = '（未填写）';

/**
 * Render the fixed four-section document.
 *
 * Every section is always present, so a reader never has to guess whether an absent heading
 * means "empty" or "forgotten".
 */
export function renderHandoff(h: Partial<Handoff>, meta?: { at?: number; sessionTitle?: string }): string {
	const title = String(h?.title ?? '').trim().slice(0, LIMITS.maxTitleChars) || '未命名交接';
	const at = meta?.at ?? Date.now();
	const lines: string[] = [`# 交接：${title}`, '', `> 生成时间：${new Date(at).toISOString()}`];
	// Pushed conditionally rather than pushing '' and cleaning up after: an earlier version used
	// a regex with `\s*` to strip the empty case, and `\s` matches newlines too, so it silently
	// ate the source-session line even when that line HAD content.
	if (meta?.sessionTitle) { lines.push(`> 来源会话：${meta.sessionTitle}`); }
	lines.push('');
	for (const s of SECTIONS) {
		lines.push(`## ${s.label}`);
		const raw = String((h as any)?.[s.key] ?? '').trim().slice(0, LIMITS.maxSectionChars);
		lines.push(raw || EMPTY_MARK, '');
	}
	return lines.join('\n');
}

/** A filesystem-safe filename that still sorts by date. */
export function handoffFileName(title: string, at: number): string {
	const stamp = new Date(at).toISOString().slice(0, 16).replace(/[:T]/g, '-');
	const slug = String(title ?? '').trim().slice(0, LIMITS.maxTitleChars)
		.replace(/[\\/:*?"<>|]/g, '-').replace(/\s+/g, '-') || 'handoff';
	return `${stamp}_${slug}.md`;
}

export interface WriteResult {
	ok: boolean;
	path?: string;
	/** Why it was refused, when ok is false. */
	reason?: string;
}

/**
 * Write the handoff into `dir`. Creates the directory.
 *
 * Refuses an empty goal on purpose: a handoff with no stated goal cannot be picked up cold,
 * which is the entire point of writing one.
 */
export async function writeHandoff(
	dir: string, h: Partial<Handoff>, meta?: { at?: number; sessionTitle?: string },
): Promise<WriteResult> {
	if (!String(h?.goal ?? '').trim()) {
		return { ok: false, reason: '交接必须写明目标（goal），否则接手方无从下手。' };
	}
	const at = meta?.at ?? Date.now();
	const file = path.join(dir, handoffFileName(h.title ?? '', at));
	const body = renderHandoff(h, { at, sessionTitle: meta?.sessionTitle });
	try {
		await fs.mkdir(dir, { recursive: true });
		// Never silently overwrite: a same-titled handoff from the same minute is rare, but
		// clobbering one is worse than a suffixed duplicate.
		let target = file;
		for (let i = 2; i < 20 && await exists(target); i++) {
			target = file.replace(/\.md$/, `-${i}.md`);
		}
		await fs.writeFile(target, body, 'utf8');
		return { ok: true, path: target };
	} catch (e) {
		return { ok: false, reason: `写入失败：${(e as Error)?.message ?? e}` };
	}
}

async function exists(p: string): Promise<boolean> {
	try { await fs.access(p); return true; } catch { return false; }
}

/**
 * Parse a handoff back into sections.
 *
 * Tolerant on read, strict on write — someone else's handoff may use slightly different spacing,
 * and refusing to read it makes the format worse than useless.
 */
export function parseHandoff(md: string): Partial<Handoff> & { title?: string } {
	const text = String(md ?? '');
	const out: Record<string, string> = {};
	const tm = /^#\s*交接[：:]\s*(.+)$/m.exec(text);
	if (tm) { out.title = tm[1].trim(); }
	for (const s of SECTIONS) {
		// Match the heading by its label, tolerating spacing and trailing punctuation.
		const re = new RegExp(`^##\\s*${s.label.split(/[（(]/)[0].trim()}[^\\n]*\\n([\\s\\S]*?)(?=\\n##\\s|$)`, 'm');
		const m = re.exec(text);
		const v = (m?.[1] ?? '').trim();
		out[s.key] = v && v !== EMPTY_MARK ? v : '';
	}
	return out as any;
}

/** Build a handoff skeleton from a session, so the UI starts pre-filled rather than blank. */
export function skeletonFrom(title: string, goalHint = ''): Handoff {
	return { title, goal: goalHint, done: '', blockers: '', next: '' };
}
