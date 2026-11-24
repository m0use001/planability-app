/**
 * ICS export — the one B-class gap with no cross-end coupling (phone's `calendar_export_ics`
 * reads a Room row, ours reads the store; nothing about the FILE is theirs to negotiate).
 *
 * RFC 5545 is the whole job here, and it is a job people get wrong in three specific ways that
 * all still "work" locally and fail on import:
 *
 *  1. FOLDING. A content line is at most 75 OCTETS, not characters (§3.1). A Chinese note is
 *     3 bytes per character, so a 30-character description is 90 bytes and breaks importers that
 *     take the limit seriously. Folding by character instead would split a UTF-8 sequence and
 *     produce a file that is not merely ugly but undecodable. We fold on byte boundaries, never
 *     mid-character.
 *  2. DTSTAMP. It is REQUIRED on every VEVENT (§3.6.1). Phone omits it; some importers shrug,
 *     some reject the whole file. We emit it.
 *  3. DTEND > DTSTART. An event whose end equals or precedes its start is invalid, and an
 *     all-day VEVENT additionally needs an EXCLUSIVE end date (§3.3.4/3.3.10): the event covers
 *     2026-10-11 and ends on 2026-10-12.
 *
 * Deliberate differences from the phone implementation, each one logged rather than smuggled:
 *   - `id` is a STRING here. Phone stores schedule ids as Room INTEGERs; ours are store ids.
 *     Same tool name, different param type — this is a D-class drift, not an oversight.
 *   - No LOCATION. `Schedule` has no `place` field on PC, so there is nothing honest to emit.
 *   - No PRIORITY. `Schedule.priority` is 1高/2中/3低 (a timeline colour), and mapping it to RFC
 *     PRIORITY 1/5/9 would invent a scale phone does not have. A slightly lossy file beats a
 *     divergent one; the two ends should agree before either adds it.
 *   - All-day events are representable. Phone's epoch-millisecond model cannot express
 *     VALUE=DATE at all, and a bare-date schedule is the common case in our data.
 */

import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';
import type { WorkspaceFactory } from './file-tools';

/** The subset of a schedule that ends up in a calendar file. */
export interface IcsEvent {
	/** Store id. Also the UID stem, so re-exporting an edited schedule UPDATES it. */
	id: string;
	title: string;
	/** YYYY-MM-DD. Present => all-day event (VALUE=DATE), and startIso/endIso are ignored. */
	date?: string;
	/** Wall clock or offset-bearing ISO. A bare "2026-10-11T09:00" is FLOATING (no Z). */
	startIso?: string;
	endIso?: string;
	note?: string;
	/** Not on PC's Schedule yet; wired so adding it later needs no format change. */
	place?: string;
}

export interface BuildIcsOptions {
	/** Injected so tests can assert exact bytes. Defaults to now. */
	now?: Date;
}

const PRODID = '-//Planability//Workbench//CN';

/**
 * RFC 5545 §3.3.11 TEXT escaping. Backslash goes FIRST: escaping it later would turn the
 * backslash this step inserts into an escaped backslash and leave the delimiter unescaped.
 */
export function icsText(s: string): string {
	return String(s ?? '')
		.replace(/\\/g, '\\\\')
		.replace(/;/g, '\\;')
		.replace(/,/g, '\\,')
		.replace(/\r\n|\r|\n/g, '\\n');
}

/**
 * Fold one content line to 75 octets per §3.1.
 *
 * Iterating a string with for..of yields whole code points, so a surrogate pair or a 4-byte
 * emoji is never cut in half; the cut happens BEFORE a character is appended, never inside one.
 * Continuation lines start with a single space, and that space counts toward the 75 — which is
 * why every chunk after the first gets 74.
 */
export function foldIcsLine(line: string): string {
	if (Buffer.byteLength(line, 'utf8') <= 75) { return line; }
	const out: string[] = [];
	let cur = '';
	for (const ch of line) {
		const limit = out.length === 0 ? 75 : 74;
		if (Buffer.byteLength(cur + ch, 'utf8') > limit) {
			out.push(cur);
			cur = ch;
		} else {
			cur += ch;
		}
	}
	out.push(cur);
	return out.join('\r\n ');
}

const pad2 = (n: number): string => String(n).padStart(2, '0');

/** `2026-10-11T09:00:00+08:00` / `...Z` => UTC `20261011T010000Z`. */
function utcStamp(d: Date): string {
	return `${d.getUTCFullYear()}${pad2(d.getUTCMonth() + 1)}${pad2(d.getUTCDate())}` +
		`T${pad2(d.getUTCHours())}${pad2(d.getUTCMinutes())}${pad2(d.getUTCSeconds())}Z`;
}

/** `2026-10-11T09:00` => `20261011T090000`. */
function floatingStamp(v: string): string {
	const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(v.trim());
	if (!m) { return ''; }
	return `${m[1]}${m[2]}${m[3]}T${m[4]}${m[5]}${m[6] ?? '00'}`;
}

/** An explicit offset (Z or +hh:mm) means the instant is known; emit UTC. Otherwise FLOAT. */
function hasOffset(v: string): boolean {
	return /(?:Z|[+-]\d{2}:?\d{2})$/i.test(v.trim());
}

/**
 * Strict YYYY-MM-DD parse.
 *
 * The regex alone is not enough: Date.UTC(2026, 12, 45) is a real Date — 2027-02-14 — so a
 * loose parse turns a typo into a confidently wrong event rather than an error. The
 * round-trip check is what makes "2026-13-45" a refusal instead of a different day.
 */
function parseYmd(date: string): { y: number; m: number; d: number } | null {
	const g = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date).trim());
	if (!g) { return null; }
	const y = Number(g[1]), m = Number(g[2]), d = Number(g[3]);
	if (m < 1 || m > 12 || d < 1 || d > 31) { return null; }
	const probe = new Date(Date.UTC(y, m - 1, d));
	if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== m - 1 || probe.getUTCDate() !== d) {
		return null; // e.g. 2026-02-30, which Date would happily roll into March.
	}
	return { y, m, d };
}

/** Add days to YYYY-MM-DD in UTC, so no local DST boundary can move the date. */
function addDays(parsed: { y: number; m: number; d: number }, n: number): string {
	const d = new Date(Date.UTC(parsed.y, parsed.m - 1, parsed.d + n));
	return d.toISOString().slice(0, 10);
}

/**
 * Build a complete VCALENDAR. Returns null when the event has no date at all — a VEVENT without
 * DTSTART is invalid, and emitting one would produce a file that imports as a nameless ghost.
 */
export function buildIcs(ev: IcsEvent, opts: BuildIcsOptions = {}): string | null {
	const title = String(ev.title ?? '').trim();
	if (!title) { return null; }
	const rawDay = String(ev.date ?? '').trim();
	if (!rawDay && !String(ev.startIso ?? '').trim()) { return null; }
	// An unparseable date must NOT fall back to the timed branch: `date` present but garbage
	// would otherwise silently export as a timed event using stale startIso.
	const day = rawDay ? parseYmd(rawDay) : null;
	if (rawDay && !day) { return null; }

	const now = opts.now ?? new Date();
	const lines: string[] = [
		'BEGIN:VCALENDAR',
		'VERSION:2.0',
		`PRODID:${PRODID}`,
		'CALSCALE:GREGORIAN',
		'BEGIN:VEVENT',
		// UID keyed on the store id alone: retitling or moving a schedule must UPDATE the same
		// calendar entry, not create a second one. Phone mixes the title hash into the UID, so a
		// rename there silently duplicates the event on import.
		`UID:planability-${icsText(ev.id)}@planability`,
		`DTSTAMP:${utcStamp(now)}`,
	];

	if (day) {
		// VALUE=DATE with an EXCLUSIVE end: an all-day event on the 11th ends on the 12th.
		lines.push(`DTSTART;VALUE=DATE:${rawDay.replace(/-/g, '')}`);
		lines.push(`DTEND;VALUE=DATE:${addDays(day, 1).replace(/-/g, '')}`);
	} else {
		const rawStart = String(ev.startIso ?? '').trim();
		const start = hasOffset(rawStart)
			? (() => { const d = new Date(rawStart); return Number.isNaN(d.getTime()) ? '' : utcStamp(d); })()
			: floatingStamp(rawStart);
		if (!start) { return null; }
		// Missing/short/negative end is common (a schedule created with only a date+time), so the
		// default is one hour rather than refusing to export. An invalid DTEND loses the whole
		// event on import; a slightly wrong one still shows up.
		const rawEnd = String(ev.endIso ?? '').trim();
		let end = '';
		if (rawEnd) {
			end = hasOffset(rawEnd)
				? (() => { const d = new Date(rawEnd); return Number.isNaN(d.getTime()) ? '' : utcStamp(d); })()
				: floatingStamp(rawEnd);
		}
		lines.push(`DTSTART:${start}`);
		lines.push(`DTEND:${end && end > start ? end : addOneHour(start)}`);
	}

	lines.push(`SUMMARY:${icsText(title)}`);
	if (String(ev.place ?? '').trim()) { lines.push(`LOCATION:${icsText(String(ev.place).trim())}`); }
	if (String(ev.note ?? '').trim()) { lines.push(`DESCRIPTION:${icsText(String(ev.note).trim())}`); }
	lines.push('END:VEVENT', 'END:VCALENDAR');

	// CRLF, and folded. A file joined with \n is technically malformed and some importers choke.
	return lines.map(foldIcsLine).join('\r\n') + '\r\n';
}

/**
 * `20261013T090000[Z]` -> one hour later, same shape.
 *
 * Offsets are read at FIXED positions (9/11/13 for HH/MM/SS) because the stamp is always
 * YYYYMMDD'T'HHMMSS, optionally followed by Z. The date is only rebuilt when the hour
 * rolls past 23, and that rebuild happens in UTC so no DST boundary can shift it.
 */
function addOneHour(stamp: string): string {
	const hh = Number(stamp.slice(9, 11));
	const mm = stamp.slice(11, 13);
	const ss = stamp.slice(13, 15);
	const z = stamp.endsWith('Z') ? 'Z' : '';
	if (hh + 1 < 24) { return stamp.slice(0, 9) + pad2(hh + 1) + mm + ss + z; }
	const next = new Date(Date.UTC(Number(stamp.slice(0, 4)), Number(stamp.slice(4, 6)) - 1,
		Number(stamp.slice(6, 8)) + 1));
	return next.toISOString().slice(0, 10).replace(/-/g, '') + 'T00' + mm + ss + z;
}

// --------------------------------------------------------------------------------------
// Tool
// --------------------------------------------------------------------------------------

export interface IcsToolDeps {
	/** Null when no workspace root is configured — same contract as file_write. Session-aware since P0-c. */
	getWorkspace: WorkspaceFactory;
	getSchedule: (id: string) => Promise<any>;
}

/** Where exports go, relative to the workspace root. Matches phone's `exports/` prefix. */
export const ICS_EXPORT_DIR = 'exports';

export function registerIcsTools(reg: ToolRegistry, deps: IcsToolDeps): void {
	// Set the domain HERE rather than trusting the caller to have done it. An unset domain
	// defaults to `system`, which is alwaysOn — so a caller who forgets leaves a tool the
	// user can never switch off, with nothing anywhere saying a switch ignored it. That is
	// the same "the switch lies" failure the whole domain mechanism exists to prevent.
	reg.setDomain('ics');

	// Same local helper file-tools.ts uses: ToolRegistry exposes register(), not def().
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: ToolContext) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def(
		'calendar_export_ics',
		'confirm',
		'把一条日程导出为 .ics 日历文件（写操作，需用户确认）。可导入系统日历或分享给别的日历应用。需要 id（来自 schedule_query）',
		{
			type: 'object',
			properties: { id: { type: 'string', description: '日程 id，来自 schedule_query' } },
			required: ['id'],
		},
		async (input, ctx) => {
			const id = String(input?.id ?? '').trim();
			if (!id) { return '[FAIL] 缺少日程 id。'; }

			const ws = await deps.getWorkspace(ctx?.sessionId);
			if (!ws) { return '[FAIL] 未配置工作区根目录，无法导出。请先在设置里指定一个目录。'; }

			const s = await deps.getSchedule(id);
			if (!s) { return `[FAIL] 未找到该日程（id=${id}），可能已删除。可用 schedule_query 查看现有日程。`; }

			const ics = buildIcs({
				id: s.id ?? id,
				title: s.title,
				date: s.date,
				startIso: s.startIso,
				endIso: s.endIso,
				note: s.note,
			});
			if (!ics) {
				// The two ways this happens: no title, or neither date nor startIso. Both are
				// "this schedule cannot become a calendar entry", and the model should say so
				// rather than retry with a different id.
				return `[FAIL] 日程「${String(s.title ?? '')}」没有日期也没有时间，无法导成日历事件。先给它一个 date 或 startIso。`;
			}

			const rel = `${ICS_EXPORT_DIR}/schedule_${id}.ics`;
			const w = await ws.write(rel, ics);
			if (!w.ok) { return `[FAIL] 写入 ${rel} 失败：${w.reason}`; }

			const when = s.date
				? `${s.date}（全天）`
				: `${String(s.startIso ?? '').replace('T', ' ').slice(0, 16)}`;
			return `已导出 ${w.rel}（${when} ${s.title}，${w.bytes} 字节）。` +
				`双击该文件或拖进系统日历即可导入；文件在工作区内，可以用 file_list 找到。`;
		},
	);
}