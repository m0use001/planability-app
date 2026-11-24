/** 本地日 key（YYYY-MM-DD）——全渲染层唯一算法。
 *
 * G 类 MEDIUM：`toISOString()` 是 UTC，北京时间 08:00 之前会把「今天」算成昨天，
 * 而 Home 已经特意改成本地日期并留了注释（两处同一 bug 两套写法）。抽成 helper 让
 * 「今天/明天」只有一个写法可错。 */

export function localDayKey(d: Date = new Date()): string {
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 明天的本地日 key（加一天后取 key，不手搓毫秒算术——DST 下 864e5 会错）。 */
export function nextDayKey(): string {
	const d = new Date();
	d.setDate(d.getDate() + 1);
	return localDayKey(d);
}
