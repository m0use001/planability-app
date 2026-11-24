/* Minimal unified-ish line diff for the approval card.
 *
 * M18 plan §3 item 1 wants the confirmation card to carry a diff of what will change. A full
 * Myers implementation is overkill for "show me what the model is about to do", and pulling a
 * dependency for it would be worse. This is LCS-based, and — importantly — it DEGRADES
 * GRACEFULLY: on a large or pathological input it returns a whole-file replace marker rather
 * than burning CPU in the approval path (where a hang looks like a frozen app).
 */

export interface DiffLine {
	kind: 'ctx' | 'add' | 'del';
	text: string;
	/** 1-based line number in the old file (ctx/del) */
	oldNo?: number;
	/** 1-based line number in the new file (ctx/add) */
	newNo?: number;
}

const MAX_LINES = 2000;
/** Above this, skip the LCS table entirely (O(n*m) would be ~1e6 cells). */
const LCS_CELL_BUDGET = 4_000_000;

export function diffLines(before: string | null, after: string): DiffLine[] {
	const b = (before ?? '').split('\n');
	const a = after.split('\n');

	// New file: everything is an add. Deleted file: everything is a del.
	if (before === null) { return a.map((text, i) => ({ kind: 'add' as const, text, newNo: i + 1 })); }
	if (after === '') { return b.map((text, i) => ({ kind: 'del' as const, text, oldNo: i + 1 })); }

	if (b.length > MAX_LINES || a.length > MAX_LINES || b.length * a.length > LCS_CELL_BUDGET) {
		return [
			...b.map((text, i) => ({ kind: 'del' as const, text, oldNo: i + 1 })),
			...a.map((text, i) => ({ kind: 'add' as const, text, newNo: i + 1 })),
		];
	}

	// LCS table
	const n = b.length, m = a.length;
	const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
	for (let i = n - 1; i >= 0; i--) {
		for (let j = m - 1; j >= 0; j--) {
			dp[i][j] = b[i] === a[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
		}
	}

	const out: DiffLine[] = [];
	let i = 0, j = 0;
	while (i < n && j < m) {
		if (b[i] === a[j]) { out.push({ kind: 'ctx', text: b[i], oldNo: i + 1, newNo: j + 1 }); i++; j++; }
		else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ kind: 'del', text: b[i], oldNo: i + 1 }); i++; }
		else { out.push({ kind: 'add', text: a[j], newNo: j + 1 }); j++; }
	}
	while (i < n) { out.push({ kind: 'del', text: b[i], oldNo: i + 1 }); i++; }
	while (j < m) { out.push({ kind: 'add', text: a[j], newNo: j + 1 }); j++; }
	return out;
}

/** Compact `+`/`-`/` ` rendering, trimmed around the change with a context window. */
export function renderDiff(before: string | null, after: string, context = 3, maxOut = 60): string {
	const lines = diffLines(before, after);
	const changed = lines.map((l, idx) => (l.kind === 'ctx' ? -1 : idx)).filter(idx => idx >= 0);
	if (!changed.length) { return '（内容无变化）'; }

	const keep = new Set<number>();
	for (const idx of changed) {
		for (let k = idx - context; k <= idx + context; k++) { if (k >= 0 && k < lines.length) { keep.add(k); } }
	}
	const picked = [...keep].sort((x, y) => x - y);

	const out: string[] = [];
	let prev = -2;
	for (const idx of picked) {
		if (prev !== -1 && idx !== prev + 1) { out.push('  …'); }
		const l = lines[idx];
		const mark = l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' ';
		out.push(`${mark} ${l.text}`);
		prev = idx;
		if (out.length > maxOut) { out.push('  …（差异过长已截断）'); break; }
	}
	return out.join('\n');
}

/** Short one-line summary for a tool row / toast. */
export function diffStat(before: string | null, after: string): { added: number; removed: number } {
	let added = 0, removed = 0;
	for (const l of diffLines(before, after)) {
		if (l.kind === 'add') { added++; }
		else if (l.kind === 'del') { removed++; }
	}
	return { added, removed };
}