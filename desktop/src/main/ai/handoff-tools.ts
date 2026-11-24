/**
 * Handoff tools — the four-section document, exposed to the model (an N-class gap closed).
 *
 * What a handoff is FOR: a DIFFERENT session — possibly on the phone, possibly a different person —
 * picks it up cold. That is why the format is fixed and the sections are mandatory, why an
 * unfilled section is an explicit 「（未填写）」 rather than an omission, and why the document is
 * WRITTEN TO A FILE in the synced folder instead of returned as a string. A handoff you cannot
 * find later is not a handoff.
 *
 * Domain is `note`. A handoff is not a notebook entry, but it is the same class of thing: a
 * document the model writes about your work for you to read later. Putting it under `agent` would
 * make the switch's stated cost ("cannot dispatch sub-agents") a lie about what it actually stops.
 *
 * ONE THING DELIBERATELY DIFFERENT FROM THE IPC PATH:
 *
 * The renderer gets preview-then-confirm in one screen. The tool splits that into two tools with
 * different risk levels: `handoff_preview` is read-only and `auto` — it costs nothing, and a model
 * that can see the exact document it is about to produce writes a better one — while
 * `handoff_write` is `confirm`. That is the same two-step guarantee, without making the model ask
 * permission to look at a string.
 *
 * `sanitizeHandoffName` is exported so index.ts's existing `handoffRead` handler can delegate to it
 * instead of keeping its own inline copy. The filename in `handoff_read` is chosen by a model that
 * did not necessarily create the file, so it must not be able to escape the folder — and having
 * that rule in two places is how the two places start disagreeing.
 */

import {
	writeHandoff, renderHandoff, parseHandoff, LIMITS,
	type Handoff, type WriteResult,
} from './handoff';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';

export type { Handoff, WriteResult };

/**
 * Reduce a caller-supplied filename to something that cannot leave the handoff folder.
 *
 * `path.basename` is what does the work: it drops every directory component, so `../../evil.md`
 * becomes `evil.md`. The `.md` requirement is checked AFTER basename so it applies to whatever
 * survived, and a name that is nothing but the extension is refused — it is not a handoff.
 */
export function sanitizeHandoffName(name: unknown): string | null {
	const base = path.basename(String(name ?? ''));
	if (!base.endsWith('.md')) { return null; }
	if (base.length <= '.md'.length) { return null; }
	return base;
}

export interface HandoffToolDeps {
	/** The synced handoff folder. Owned by index.ts because it is a synced location. */
	dir: () => string;
	/** Title of the current session, stamped into the document so the reader knows the source. */
	sessionTitle?: () => string | undefined | Promise<string | undefined>;
}

export const HANDOFF_TOOL_LIMITS = {
	maxList: 100,
	/** A handoff is a summary; LIMITS.maxSectionChars bounds each section, and the reply says so. */
	maxSectionChars: LIMITS.maxSectionChars,
} as const;

/** The editable fields, in document order. Anything the caller omits is rendered as 「（未填写）」. */
const FIELDS = ['title', 'goal', 'done', 'blockers', 'next'] as const;

/** Pull the handoff fields out of arbitrary tool input, with bounds applied. */
function readFields(input: any): Partial<Handoff> {
	const out: Partial<Handoff> = {};
	for (const k of FIELDS) {
		const v = input?.[k];
		if (v === undefined || v === null) { continue; }
		if (typeof v !== 'string') { continue; }
		if (v.length > LIMITS.maxSectionChars) { continue; } // refuse an over-long section outright
		out[k] = v;
	}
	return out;
}

export function registerHandoffTools(reg: any, deps: HandoffToolDeps): void {
	// Own the domain rather than trusting the caller: an unset domain lands in `system`, which is
	// alwaysOn, and the tool would be un-switchable with nothing saying a switch ignored it.
	reg.setDomain('note');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('handoff_preview', 'auto',
		'预览一份交接文档会长什么样（只读，不写盘）。四段固定：goal=目标、done=已完成、blockers=阻塞与风险、next=下一步。' +
		'没填的段会显式写成「（未填写）」——写之前先看一眼值得。',
		{
			type: 'object',
			properties: {
				title: { type: 'string', description: '标题' },
				goal: { type: 'string', description: '接手时要达成什么' },
				done: { type: 'string', description: '已完成' },
				blockers: { type: 'string', description: '阻塞与风险' },
				next: { type: 'string', description: '下一步与落点' },
			},
		},
		async (input) => {
			const h = readFields(input);
			if (!String(h.goal ?? '').trim()) {
				// Same refusal the write path gives, one step earlier and for free.
				return '[FAIL] 交接必须写明目标（goal），否则接手方无从下手。';
			}
			const title = String(h.title ?? '').trim() || '未命名交接';
			return renderHandoff({ ...h, title }, { sessionTitle: await deps.sessionTitle?.() });
		});

	def('handoff_write', 'confirm',
		'写一份交接文档到同步目录（写操作，需用户确认）。另一个会话/另一个人可以拿它冷启动。' +
		'goal 必填——没有目标的交接不成立。',
		{
			type: 'object',
			properties: {
				title: { type: 'string', description: '标题' },
				goal: { type: 'string', description: '接手时要达成什么（必填）' },
				done: { type: 'string', description: '已完成' },
				blockers: { type: 'string', description: '阻塞与风险' },
				next: { type: 'string', description: '下一步与落点' },
			},
			required: ['goal'],
		},
		async (input) => {
			const h = readFields(input);
			if (!String(h.goal ?? '').trim()) {
				return '[FAIL] 交接必须写明目标（goal），否则接手方无从下手。';
			}
			const dir = deps.dir();
			if (!dir) { return '[FAIL] 没有配置交接目录，无法写入。'; }
			const res = await writeHandoff(dir, h, { sessionTitle: await deps.sessionTitle?.() });
			if (!res.ok) { return `[FAIL] ${res.reason ?? '写入失败'}`; }
			return `已写入交接 ${res.path}。用 handoff_read 读回，或用 handoff_list 看全部。`;
		});

	def('handoff_list', 'auto',
		'列出手里的交接文档（只读）：文件名 + 大小，按时间倒序。',
		{ type: 'object', properties: {} },
		async () => {
			const dir = deps.dir();
			if (!dir) { return '[FAIL] 没有配置交接目录。'; }
			let names: string[];
			try { names = await fs.readdir(dir); }
			catch { return '还没有任何交接文档。'; }
			const md = names.filter(n => n.endsWith('.md')).sort().reverse();
			if (!md.length) { return '还没有任何交接文档。'; }
			const shown = md.slice(0, HANDOFF_TOOL_LIMITS.maxList);
			const rows: string[] = [];
			for (const n of shown) {
				try {
					const bytes = (await fs.stat(path.join(dir, n))).size;
					rows.push(`${n}  ${(bytes / 1024).toFixed(1)}K`);
				} catch { rows.push(`${n}`); } // vanished between readdir and stat
			}
			return rows.join('\n') + (md.length > shown.length
				? `\n\n（共 ${md.length} 份，只列前 ${shown.length} 份）` : '');
		});

	def('handoff_read', 'auto',
		'读一份交接文档的全文（只读）。需要文件名，来自 handoff_list。',
		{
			type: 'object',
			properties: { name: { type: 'string', description: '文件名（.md）' } },
			required: ['name'],
		},
		async (input) => {
			const dir = deps.dir();
			if (!dir) { return '[FAIL] 没有配置交接目录。'; }
			// The name is chosen by a model that may not have created the file, so it is reduced
			// to a bare filename first. Without this a call could read anything the process can.
			const safe = sanitizeHandoffName(input?.name);
			if (!safe) {
				return '[FAIL] 文件名无效：必须是交接目录里的一个 .md 文件名（来自 handoff_list）。';
			}
			let text: string;
			try { text = await fs.readFile(path.join(dir, safe), 'utf8'); }
			catch { return `[FAIL] 没有 ${safe} 这份交接（可能已删）。可用 handoff_list 看看有哪些。`; }
			const parsed = parseHandoff(text);
			const title = parsed.title ? `交接：${parsed.title}` : safe;
			const missing = ['goal', 'done', 'blockers', 'next'].filter(k => !String((parsed as any)[k] ?? '').trim());
			const tail = missing.length ? `\n\n（未填写的段：${missing.join('、')}）` : '';
			return `${title}\n${text}${tail}`;
		});
}