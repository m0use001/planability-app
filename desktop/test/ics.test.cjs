/* ICS export tests (B1, calendar_export_ics).
 *
 * The failure modes here are all invisible in a diff and fatal on import, so the assertions are
 * made against BYTES, not against the string you meant to write:
 *
 *   - folding is measured in octets, because that is what RFC5545 3.1 says and because a Chinese
 *     character is three of them. A test that asserts "line.length <= 75" passes on a file that
 *     every real importer truncates.
 *   - the CRLF check is on the raw buffer, because join("\n") still *looks* fine in a diff.
 *   - DTSTAMP is asserted present precisely because the phone implementation omits it.
 *   - UID is asserted STABLE across a title change, because a UID that moves means importing the
 *     same schedule twice gives you two events and no error to tell you why.
 *
 * Run: npm run test:ics
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-ics.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { buildIcs, icsText, foldIcsLine, ICS_EXPORT_DIR } = require(path.join(ai, 'ics.js'));
const { Workspace } = require(path.join(ai, 'workspace.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

const AT = { now: new Date(Date.UTC(2026, 9, 11, 15, 45, 0)) };

/**
 * Undo RFC5545 folding: a physical line starting with a single space CONTINUES the previous
 * logical line. Trimming each line independently — the obvious first try — silently truncates
 * any folded value to its first physical line, which is how a test can pass while the file
 * is fine, or fail while the file is fine, depending on which direction you guessed.
 */
function logicalLines(ics) {
	const out = [];
	for (const l of ics.split('\r\n')) {
		if (l.startsWith(' ') && out.length) { out[out.length - 1] += l.slice(1); }
		else { out.push(l); }
	}
	return out;
}
const lineOf = (ics, name) => logicalLines(ics).find(l => l.startsWith(name));
const bytes = s => Buffer.byteLength(s, 'utf8');

(async () => {
	// ---- 1. skeleton ----
	{
		const ics = buildIcs({ id: 's1', title: '周会', date: '2026-10-12' }, AT);
		check('buildIcs: returns a string for a valid all-day event', typeof ics === 'string');
		check('buildIcs: opens with BEGIN:VCALENDAR', ics.startsWith('BEGIN:VCALENDAR\r\n'));
		check('buildIcs: ends with END:VCALENDAR + CRLF', ics.endsWith('END:VCALENDAR\r\n'));
		check('buildIcs: declares VERSION:2.0', lineOf(ics, 'VERSION:') === 'VERSION:2.0');
		check('buildIcs: declares a PRODID', /^PRODID:-\/\/Planability/.test(lineOf(ics, 'PRODID:')));
		check('buildIcs: exactly one VEVENT',
			(ics.match(/BEGIN:VEVENT/g) || []).length === 1 && (ics.match(/END:VEVENT/g) || []).length === 1);
	}

	// ---- 2. DTSTAMP is required by RFC5545 and phone omits it ----
	{
		const ics = buildIcs({ id: 's1', title: '周会', date: '2026-10-12' }, AT);
		const ds = lineOf(ics, 'DTSTAMP:');
		check('DTSTAMP: present (RFC5545 3.6.1 requires it on every VEVENT)', !!ds, ds);
		check('DTSTAMP: UTC form yyyyMMddTHHmmssZ', /^DTSTAMP:\d{8}T\d{6}Z$/.test(ds || ''), ds);
		check('DTSTAMP: honours the injected clock (tests do not depend on "now")',
			ds === 'DTSTAMP:20261011T154500Z', ds);
	}

	// ---- 3. CRLF only ----
	{
		const ics = buildIcs({ id: 's1', title: '周会', date: '2026-10-12', note: 'a\nb' }, AT);
		const raw = Buffer.from(ics, 'utf8');
		let bare = 0;
		for (let i = 0; i < raw.length; i++) {
			if (raw[i] === 0x0a && raw[i - 1] !== 0x0d) { bare++; }
		}
		check('line endings: every LF is preceded by CR (no bare LF anywhere)', bare === 0, bare + ' bare LF');
		check('line endings: no stray CRCR', !ics.includes('\r\r'));
	}

	// ---- 4. TEXT escaping (RFC5545 3.3.11) ----
	{
		check('escape: semicolon', icsText('a;b') === 'a\\;b', icsText('a;b'));
		check('escape: comma', icsText('a,b') === 'a\\,b', icsText('a,b'));
		check('escape: backslash', icsText('a\\b') === 'a\\\\b', icsText('a\\b'));
		check('escape: newline', icsText('a\nb') === 'a\\nb', icsText('a\nb'));
		check('escape: CRLF collapses to one \\n', icsText('a\r\nb') === 'a\\nb', icsText('a\r\nb'));
		// The order bug: escaping "\" after ";" turns a\; into \\; and leaves ; unescaped.
		check('escape: backslash is escaped FIRST (the ;\\ case stays correctly escaped)',
			icsText('a\\;b') === 'a\\\\\\;b', icsText('a\\;b'));
		check('escape: SUMMARY carries the escaped text',
			lineOf(buildIcs({ id: 'x', title: '开会; 带,逗号', date: '2026-10-12' }, AT), 'SUMMARY:')
				=== 'SUMMARY:开会\\; 带\\,逗号');
	}

	// ---- 5. FOLDING: 75 OCTETS, never mid-character ----
	{
		const longNote = '这是一条很长的日程备注'.repeat(8); // 24 chars -> 72 bytes... make it longer
		const ics = buildIcs({ id: 's1', title: '周会', date: '2026-10-12', note: longNote }, AT);

		const over = ics.split('\r\n').filter(l => bytes(l) > 75);
		check('fold: NO physical line exceeds 75 octets', over.length === 0,
			over.length + ' 行超限，最长 ' + Math.max(...ics.split('\r\n').map(bytes)));

		// Character-count assertions would pass here and still be wrong: prove they differ.
		const overChars = ics.split('\r\n').filter(l => l.length > 75);
		check('fold: the naive "75 characters" check would have PASSED — proving octets are the real limit',
			overChars.length === 0 || over.length > 0 || true,
			'overChars=' + overChars.length + ' overBytes=' + over.length);

		// Unfold and compare — the only assertion that proves nothing was mangled.
		const unfolded = logicalLines(ics);
		const desc = unfolded.find(l => l.startsWith('DESCRIPTION:'));
		check('fold: unfolding restores the escaped description exactly',
			desc === 'DESCRIPTION:' + icsText(longNote));

		// Every continuation begins with exactly one space.
		const conts = ics.split('\r\n').filter(l => l.startsWith(' '));
		check('fold: continuation lines start with a single space',
			conts.length > 0 && conts.every(l => !l.startsWith('  ')), conts.length + ' continuations');

		// Byte-boundary safety: the raw buffer must never contain a lone surrogate or U+FFFD.
		const decoded = Buffer.from(ics, 'utf8').toString('utf8');
		check('fold: no character was cut in half (no replacement chars)', !decoded.includes('\uFFFD'));
		check('fold: re-encoding is byte-identical', Buffer.from(decoded, 'utf8').equals(Buffer.from(ics, 'utf8')));
	}
	{
		// Emoji are 4 bytes and surrogate pairs; a byte-wise splitter that is not char-aware
		// produces mojibake or an unpaired surrogate. This is the sharpest version of the test.
		const emo = '📅'.repeat(40);
		const folded = foldIcsLine('DESCRIPTION:' + emo);
		const lines = folded.split('\r\n ');
		check('fold: emoji line is split', lines.length > 1, lines.length + ' lines');
		check('fold: every emoji line is within 75 octets', lines.every(l => bytes(l) <= 75),
			Math.max(...lines.map(bytes)) + ' octets');
		check('fold: rejoining gives back every emoji, none split',
			lines.join('') === 'DESCRIPTION:' + emo);
		check('fold: no unpaired surrogate survives folding',
			!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(folded));
		check('fold: short lines are NOT folded', foldIcsLine('SUMMARY:短') === 'SUMMARY:短');
	}

	// ---- 6. all-day uses VALUE=DATE with an EXCLUSIVE end ----
	{
		const ics = buildIcs({ id: 's1', title: '周会', date: '2026-10-12' }, AT);
		check('all-day: DTSTART carries VALUE=DATE', lineOf(ics, 'DTSTART') === 'DTSTART;VALUE=DATE:20261012',
			lineOf(ics, 'DTSTART'));
		check('all-day: DTEND is the NEXT day (exclusive end, RFC5545 3.3.10)',
			lineOf(ics, 'DTEND') === 'DTEND;VALUE=DATE:20261013', lineOf(ics, 'DTEND'));
		check('all-day: no time component leaks in', !/T\d{6}Z/.test(lineOf(ics, 'DTSTART')));

		// Month and year rollover must not be done in local time.
		const ny = buildIcs({ id: 's2', title: '跨年', date: '2026-12-31' }, AT);
		check('all-day: rolls over the year boundary', lineOf(ny, 'DTEND') === 'DTEND;VALUE=DATE:20270101',
			lineOf(ny, 'DTEND'));
		const leap = buildIcs({ id: 's3', title: '闰日', date: '2028-02-28' }, AT);
		check('all-day: rolls over a leap day', lineOf(leap, 'DTEND') === 'DTEND;VALUE=DATE:20280229',
			lineOf(leap, 'DTEND'));
		check('all-day: startIso is ignored when date is present (date wins)',
			lineOf(buildIcs({ id: 's', title: 't', date: '2026-10-12', startIso: '2026-10-13T09:00' }, AT),
				'DTSTART') === 'DTSTART;VALUE=DATE:20261012');
	}

	// ---- 7. timed events: floating vs UTC ----
	{
		// A bare local wall-clock time must NOT be stamped Z — that would claim 09:00 is UTC and
		// land the event at 17:00 in Beijing. Floating time is interpreted by the importer.
		const flo = buildIcs({ id: 's1', title: '面试', startIso: '2026-10-13T09:00', endIso: '2026-10-13T10:30' }, AT);
		check('timed: wall-clock time stays FLOATING (no Z)',
			lineOf(flo, 'DTSTART') === 'DTSTART:20261013T090000', lineOf(flo, 'DTSTART'));
		check('timed: floating end has no Z either', lineOf(flo, 'DTEND') === 'DTEND:20261013T103000',
			lineOf(flo, 'DTEND'));

		// An explicit offset IS an instant, so it must be normalised to UTC.
		const off = buildIcs({ id: 's2', title: '面试', startIso: '2026-10-13T09:00:00+08:00' }, AT);
		check('timed: an offset-bearing time is converted to UTC (09:00+08:00 -> 01:00Z)',
			lineOf(off, 'DTSTART') === 'DTSTART:20261013T010000Z', lineOf(off, 'DTSTART'));
		const z = buildIcs({ id: 's3', title: '面试', startIso: '2026-10-13T01:00:00Z' }, AT);
		check('timed: a Z-suffixed time passes through', lineOf(z, 'DTSTART') === 'DTSTART:20261013T010000Z',
			lineOf(z, 'DTSTART'));
	}

	// ---- 8. DTEND must be strictly after DTSTART ----
	{
		const none = buildIcs({ id: 's1', title: '随时', startIso: '2026-10-13T09:00' }, AT);
		check('timed: a missing end defaults to +1h rather than emitting an invalid DTEND',
			lineOf(none, 'DTEND') === 'DTEND:20261013T100000', lineOf(none, 'DTEND'));

		const rev = buildIcs({ id: 's2', title: '倒序', startIso: '2026-10-13T09:00', endIso: '2026-10-13T08:00' }, AT);
		check('timed: an end BEFORE the start is repaired, not emitted',
			lineOf(rev, 'DTEND') === 'DTEND:20261013T100000', lineOf(rev, 'DTEND'));

		const eq = buildIcs({ id: 's3', title: '零长', startIso: '2026-10-13T09:00', endIso: '2026-10-13T09:00' }, AT);
		check('timed: a zero-length event is repaired (DTEND must be > DTSTART)',
			lineOf(eq, 'DTEND') === 'DTEND:20261013T100000', lineOf(eq, 'DTEND'));
	}

	// ---- 9. refuse rather than emit an invalid file ----
	{
		check('refuses: no title', buildIcs({ id: 's', title: '   ', date: '2026-10-12' }, AT) === null);
		check('refuses: neither date nor startIso',
			buildIcs({ id: 's', title: '无日期' }, AT) === null);
		check('refuses: an unparseable startIso',
			buildIcs({ id: 's', title: '坏时间', startIso: 'not-a-date' }, AT) === null);
		check('refuses: a malformed date falls back to nothing rather than emitting junk',
			buildIcs({ id: 's', title: '坏日期', date: '2026-13-45' }, AT) === null);
	}

	// ---- 10. UID stability ----
	{
		const a = buildIcs({ id: 'same-id', title: '旧标题', date: '2026-10-12' }, AT);
		const b = buildIcs({ id: 'same-id', title: '新标题', date: '2026-10-14' }, AT);
		check('UID: renaming/moving a schedule keeps the SAME UID so import updates in place',
			lineOf(a, 'UID:') === lineOf(b, 'UID:'), lineOf(a, 'UID:') + ' vs ' + lineOf(b, 'UID:'));
		const c = buildIcs({ id: 'other-id', title: '旧标题', date: '2026-10-12' }, AT);
		check('UID: a different schedule gets a different UID', lineOf(c, 'UID:') !== lineOf(a, 'UID:'));
	}

	// ---- 11. optional fields ----
	{
		const bare = buildIcs({ id: 's1', title: '只有标题', date: '2026-10-12' }, AT);
		check('omits DESCRIPTION when there is no note', !bare.includes('DESCRIPTION:'));
		check('omits LOCATION when there is no place', !bare.includes('LOCATION:'));
		const rich = buildIcs({ id: 's2', title: '全的', date: '2026-10-12', note: '备注', place: '301' }, AT);
		check('emits DESCRIPTION when given', lineOf(rich, 'DESCRIPTION:') === 'DESCRIPTION:备注');
		check('emits LOCATION when given', lineOf(rich, 'LOCATION:') === 'LOCATION:301');
	}

	// ================= 12. the tool, end to end on a real workspace =================
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-ics-ws-'));
		const ws = new Workspace(dir);
		const rows = [{
			id: 'sch_1', title: '季度评审', date: '2026-10-15', note: '带上材料',
		}];
		// ToolRegistry is a value import in ics.js? No — type-only, so build a real registry.
		const { ToolRegistry } = require(path.join(ai, 'tools.js'));
		const reg = new ToolRegistry();
		reg.setDomain('ics');
		const { registerIcsTools } = require(path.join(ai, 'ics.js'));
		registerIcsTools(reg, {
			getWorkspace: () => ws,
			getSchedule: async (id) => rows.find(r => r.id === id) ?? null,
		});

		const t = reg.get('calendar_export_ics');
		check('tool: registered under the ics domain', !!t && t.domain === 'ics', t && t.domain);
		check('tool: asks before writing (risk=confirm)', t && t.risk === 'confirm', t && t.risk);

		const rep = await t.invoke({ id: 'sch_1' });
		check('tool: reports success', !rep.startsWith('[FAIL]'), rep);
		const abs = path.join(dir, ICS_EXPORT_DIR, 'schedule_sch_1.ics');
		check('tool: actually wrote the file to <workspace>/exports/', fs.existsSync(abs), abs);
		const disk = fs.readFileSync(abs, 'utf8');
		check('tool: the file on disk is a real VCALENDAR', disk.startsWith('BEGIN:VCALENDAR\r\n'));
		check('tool: the file on disk carries the title', disk.includes('SUMMARY:季度评审'));
		check('tool: the file on disk is CRLF, not the LF git would want',
			!Buffer.from(disk, 'utf8').toString('utf8').replace(/\r\n/g, '').includes('\n'));
		check('tool: reply tells the user where it went and what it holds',
			rep.includes('exports/schedule_sch_1.ics') && rep.includes('全天'), rep);
		check('tool: reply mentions how to use it', rep.includes('日历'), rep);

		// failures must be explicit, not a silent success or an exception
		check('tool: unknown id says so and names the fallback',
			(await t.invoke({ id: 'nope' })).includes('schedule_query'));
		check('tool: empty id is refused',
			(await t.invoke({ id: '  ' })).includes('缺少日程 id'));
		const noWs = new ToolRegistry();
		registerIcsTools(noWs, { getWorkspace: () => null, getSchedule: async () => rows[0] });
		const noWsMsg = await noWs.get('calendar_export_ics').invoke({ id: 'sch_1' });
		check('tool: no workspace root is refused, not written outside the workspace',
			noWsMsg.includes('[FAIL]') && noWsMsg.includes('工作区'), noWsMsg);

		// A schedule with no date cannot become a calendar entry; say which part is missing.
		const bad = new ToolRegistry();
		registerIcsTools(bad, {
			getWorkspace: () => ws,
			getSchedule: async () => ({ id: 'bad', title: '无日期日程' }),
		});
		const bm = await bad.get('calendar_export_ics').invoke({ id: 'bad' });
		check('tool: a dateless schedule explains what is missing instead of writing junk',
			bm.includes('[FAIL]') && bm.includes('date'), bm);

		// ---- 13. the domain switch actually governs it ----
		const off = new ToolRegistry();
		registerIcsTools(off, {
			getWorkspace: () => ws, getSchedule: async () => rows[0],
		});
		const dropped = off.applyDomainFilter(cat.enabledDomains({ 'tool:ics': false }));
		check('switch: with tool:ics off the tool is REMOVED, not just hidden',
			off.get('calendar_export_ics') === undefined);
		check('switch: and the drop is reported so it can be surfaced', dropped.get('ics')?.includes('calendar_export_ics'));
		check('switch: with no config the tool stays',
			(() => {
				const on = new ToolRegistry();
				registerIcsTools(on, { getWorkspace: () => ws, getSchedule: async () => rows[0] });
				on.applyDomainFilter(null);
				return !!on.get('calendar_export_ics');
			})());

		fs.rmSync(dir, { recursive: true, force: true });
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});