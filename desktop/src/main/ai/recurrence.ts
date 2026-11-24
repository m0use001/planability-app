/* Cross-end repeat contract — PC port of phone data/Recurrence.kt (2026-10-31 assign).
 *
 * THE SYNTAX IS A CONTRACT, VERBATIM (phone-c 2026-10-31T20:12 assign: "repeat 语法是跨端契约，
 * 务请逐字一致"):
 *   daily / weekly:1,3（1=周一…7=周日，逗号分隔多选）/ biweekly:1,3（每两周）/
 *   monthly（每月按锚点日号）/ monthlyW:2:3（每月第 2 个周三）/ yearly（按锚点月日）/
 *   interval:14（每 14 天）；可跟 `;until:<epochDay>` 段（缺省=永久）。
 * 模型侧参数是 `repeat`（规则串）+ `repeat_until`（yyyy-MM-dd，落库时换算成 until 段）。
 *
 * 锚点 = 记录自身的 date。展开语义与手机 data/Recurrence.kt 的 occurrences() 逐分支同源：
 * 相位从锚点起算（INTERVAL 整周期、biweekly 周序、MONTHLY 钳月末、YEARLY 钳 2/29）。
 * 本文件是纯函数，无 IO —— 测试直接钉行为（test/plan-life-tools.test.cjs）。
 */

export const REPEAT_SYNTAX =
	'可用：daily / weekly:1,3（1=周一…7=周日）/ biweekly:1,3（每两周）/ monthly（每月按日号）/ ' +
	'monthlyW:2:3（每月第2个周三）/ yearly / interval:14（每14天）';

export interface RepeatRule {
	repeats: boolean;
	kind: 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'interval';
	/** WEEKLY：1=周一..7=周日；空 = 用锚点星期。 */
	weekdays: number[];
	biweekly: boolean;
	intervalDays: number;
	/** MONTHLY：false=按日号（nth=日号，0=锚点日）；true=按第 N 个星期。 */
	byWeekday: boolean;
	nth: number;
	monthWeekday: number;
	/** yyyy-MM-dd；undefined = 永久。 */
	until?: string;
}

// ---- 纯日历工具（UTC 日历算术，等价 LocalDate.toEpochDay 的无时区语义） ----

const pad2 = (n: number): string => String(n).padStart(2, '0');

export function epochDay(dateStr: string): number {
	const [y, m, d] = dateStr.split('-').map(Number);
	return Math.floor(Date.UTC(y, m - 1, d) / 864e5);
}

export function fromEpochDay(n: number): string {
	const d = new Date(n * 864e5);
	return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
}

/** 1=周一..7=周日（ISO；JS getDay 是 0=周日）。 */
export function isoWeekday(dateStr: string): number {
	const [y, m, d] = dateStr.split('-').map(Number);
	return ((new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7) + 1;
}

function daysInMonth(y: number, m: number): number {
	return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

const cmp = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

const WEEKDAY_CHARS = '一二三四五六日';

// ---- 解析（手机 parseScheduleRule 同语法） ----

export function parseRepeat(raw: string): RepeatRule | null {
	const parts = raw.split(';').map(s => s.trim()).filter(Boolean);
	const untilSeg = parts.find(p => p.startsWith('until:'));
	const untilNum = untilSeg ? Number(untilSeg.slice(6)) : 0;
	const until = untilNum > 0 ? fromEpochDay(untilNum) : undefined;
	const head = parts.find(p => !p.startsWith('until:')) ?? '';
	const seg = head.split(':');
	const ints = (s: string | undefined): number[] =>
		(s ?? '').split(',').map(x => Number(x.trim())).filter(n => Number.isInteger(n) && n >= 1 && n <= 7);
	const intOr = (s: string | undefined, dflt: number): number => {
		const n = Number(s);
		return Number.isFinite(n) ? n : dflt;
	};
	const base = { weekdays: [] as number[], biweekly: false, intervalDays: 1, byWeekday: false, nth: 0, monthWeekday: 1, until };
	switch (seg[0]) {
		case 'daily': return { ...base, repeats: true, kind: 'daily' };
		case 'weekly': return { ...base, repeats: true, kind: 'weekly', weekdays: ints(seg[1]) };
		case 'biweekly': return { ...base, repeats: true, kind: 'weekly', biweekly: true, weekdays: ints(seg[1]) };
		case 'monthly': return { ...base, repeats: true, kind: 'monthly' };
		case 'monthlyW': return { ...base, repeats: true, kind: 'monthly', byWeekday: true, nth: intOr(seg[1], 1), monthWeekday: intOr(seg[2], 1) };
		case 'yearly': return { ...base, repeats: true, kind: 'yearly' };
		case 'interval': return { ...base, repeats: true, kind: 'interval', intervalDays: Math.max(1, intOr(seg[1], 1)) };
		default: return null; // 看不懂 → 调用方报错（手机 repeatOf 同款）
	}
}

/** 模型参数 → 落库规则串。返回 { rule, clear, error } 三选一：
 *  - 没提 repeat → { }（调用方不改原值）
 *  - `none`/空串 → { clear: true }（明确取消重复）
 *  - 合法 → { rule: '<head>[;until:<epochDay>]' }
 *  - 看不懂 → { error }（附 REPEAT_SYNTAX） */
export function repeatParamOf(input: { repeat?: unknown; repeat_until?: unknown }):
	{ rule?: string; clear?: boolean; error?: string } {
	if (input.repeat === undefined) return {};
	const raw = String(input.repeat ?? '').trim().toLowerCase();
	if (!raw || raw === 'none' || raw === '不重复') return { clear: true };
	const rec = parseRepeat(raw);
	if (!rec || !rec.repeats) return { error: '看不懂的重复规则「' + raw + '」：' + REPEAT_SYNTAX };
	let rule = raw;
	const untilRaw = String(input.repeat_until ?? '').trim();
	if (untilRaw) {
		if (!/^\d{4}-\d{2}-\d{2}$/.test(untilRaw)) {
			return { error: '截止日期看不懂：「' + untilRaw + '」（要 yyyy-MM-dd）' };
		}
		rule += ';until:' + epochDay(untilRaw);
	}
	return { rule };
}

// ---- 展开（手机 occurrences() 同语义） ----

/** [from, to]（含端点，yyyy-MM-dd）内的出现日；跳过锚点之前；截至 until。 */
export function occurrences(rule: RepeatRule, anchor: string, from: string, to: string): string[] {
	if (!rule.repeats) return [];
	const start = cmp(from, anchor) < 0 ? anchor : from;
	if (cmp(start, to) > 0) return [];
	const out: string[] = [];
	const push = (d: string): boolean => {
		if (cmp(d, to) <= 0 && (!rule.until || cmp(d, rule.until) <= 0)) { out.push(d); return true; }
		return false;
	};
	const aEd = epochDay(anchor);
	const sEd = epochDay(start);
	const tEd = epochDay(to);
	if (rule.kind === 'daily' || rule.kind === 'interval') {
		const step = rule.kind === 'daily' ? 1 : Math.max(1, rule.intervalDays);
		const diff = sEd - aEd;
		let ed = aEd + (diff > 0 ? Math.ceil(diff / step) * step : 0);
		while (ed <= tEd) { if (!push(fromEpochDay(ed))) break; ed += step; }
	} else if (rule.kind === 'weekly') {
		const days = rule.weekdays.length ? rule.weekdays : [isoWeekday(anchor)];
		for (let ed = sEd; ed <= tEd; ed++) {
			const d = fromEpochDay(ed);
			if (!days.includes(isoWeekday(d))) continue;
			if (rule.biweekly && Math.floor((ed - aEd) / 7) % 2 !== 0) continue;
			if (!push(d)) break;
		}
	} else if (rule.kind === 'monthly') {
		let [y, m] = start.split('-').map(Number);
		const [ty, tm] = to.split('-').map(Number);
		while (y < ty || (y === ty && m <= tm)) {
			const len = daysInMonth(y, m);
			const day = rule.byWeekday
				? nthWeekdayOfMonth(y, m, Math.min(Math.max(rule.nth, 1), 5), Math.min(Math.max(rule.monthWeekday, 1), 7))
				: Math.min(rule.nth >= 1 ? rule.nth : Number(anchor.slice(8, 10)), len);
			if (day) {
				const d = `${y}-${pad2(m)}-${pad2(day)}`;
				if (cmp(d, anchor) >= 0 && cmp(d, start) >= 0) push(d);
			}
			m += 1;
			if (m > 12) { m = 1; y += 1; }
		}
	} else if (rule.kind === 'yearly') {
		const am = Number(anchor.slice(5, 7));
		const ad = Number(anchor.slice(8, 10));
		for (let y = Number(start.slice(0, 4)); y <= Number(to.slice(0, 4)); y++) {
			const d = `${y}-${pad2(am)}-${pad2(Math.min(ad, daysInMonth(y, am)))}`;
			if (cmp(d, anchor) >= 0 && cmp(d, start) >= 0) push(d);
		}
	}
	return out;
}

/** 该日期是否出现（= occurrences 命中）。 */
export function occursOn(rule: RepeatRule, anchor: string, date: string): boolean {
	return occurrences(rule, anchor, date, date).includes(date);
}

function nthWeekdayOfMonth(y: number, m: number, nth: number, weekday: number): number | null {
	const len = daysInMonth(y, m);
	const firstWd = isoWeekday(`${y}-${pad2(m)}-01`);
	const lastWd = isoWeekday(`${y}-${pad2(m)}-${pad2(len)}`);
	if (nth <= 4) {
		const day = 1 + ((weekday - firstWd + 7) % 7) + (nth - 1) * 7;
		return day <= len ? day : null;
	}
	return len - ((lastWd - weekday + 7) % 7); // nth=5 → 最后一个
}

/** 列表行用的紧凑描述（手机 describeCompact 同文案）。 */
export function describeCompact(rule: RepeatRule, anchor: string): string {
	const base = (() => {
		switch (rule.kind) {
			case 'none': return '不重复';
			case 'daily': return '每天';
			case 'weekly': {
				const days = (rule.weekdays.length ? rule.weekdays : [isoWeekday(anchor)]).slice().sort();
				const names = days.map(d => WEEKDAY_CHARS[d - 1]).join('、');
				return rule.biweekly ? `每两周 周${names}` : `每周${names}`;
			}
			case 'monthly':
				if (rule.byWeekday) {
					const nthText = rule.nth >= 1 && rule.nth <= 4 ? `第${rule.nth}个` : '最后一个';
					return `每月${nthText}周${WEEKDAY_CHARS[rule.monthWeekday - 1]}`;
				}
				return `每月 ${rule.nth >= 1 ? rule.nth : Number(anchor.slice(8, 10))} 日`;
			case 'yearly': return `每年 ${Number(anchor.slice(5, 7))}/${Number(anchor.slice(8, 10))}`;
			case 'interval': return `每 ${rule.intervalDays} 天`;
			default: return '不重复';
		}
	})();
	if (!rule.until) return base;
	const endY = Number(rule.until.slice(0, 4));
	const startText = `${Number(anchor.slice(5, 7))}/${Number(anchor.slice(8, 10))}`;
	const endText = endY === Number(anchor.slice(0, 4))
		? `${Number(rule.until.slice(5, 7))}/${Number(rule.until.slice(8, 10))}`
		: `${endY}/${Number(rule.until.slice(5, 7))}/${Number(rule.until.slice(8, 10))}`;
	return `${base} · ${startText}–${endText}`;
}

/** 行内重复短语（todoRepeatText 同款）：无重复 = 空串。 */
export function repeatText(ruleStr: string | undefined, anchor: string | undefined): string {
	if (!ruleStr || !anchor) return '';
	const rec = parseRepeat(ruleStr);
	if (!rec || !rec.repeats) return '';
	return ' [重复:' + describeCompact(rec, anchor) + ']';
}
