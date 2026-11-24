/* M21 Computer Use — read-only first blade (plan §3 M21).
 *
 * Design decisions that are NOT obvious from the tool list:
 *
 * 1. The bitmap NEVER enters the model context. The plan says "不直接把位图塞进上下文
 *    （双槽模型 vision 支持待探测，先按不支持设计）". So cu_screenshot writes a PNG to disk
 *    and returns path + dimensions + scale as TEXT. This is also the "截图默认不出网"
 *    guarantee: nothing about the screen can leak through the model provider.
 * 2. Coordinates are always PHYSICAL screen pixels, and every tool result carries the
 *    scale factors. A screenshot is a downscaled image; a click needs physical pixels.
 *    Mixing the two is the classic way a CUA agent clicks the wrong thing.
 * 3. Injection is refused unless the target window is foreground. ZCode's frontmost-mismatch
 *    observation is the precedent: clicking blind lands on whatever happens to be under the
 *    cursor. Since we have no native module yet, the guard is expressed as a precondition
 *    check so the refusal path is real and testable TODAY.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';

export const CU_LIMITS = {
	/** longest edge of the stored screenshot; physical pixels are unchanged, this is the preview */
	maxEdge: 1920,
	/** keystrokes / clicks we will ever inject in one call */
	maxClicks: 1,
	maxScrollNotches: 10,
	maxTypeChars: 4000,
} as const;

export interface ScreenGeometry {
	/**
	 * PHYSICAL pixel bounds of the whole desktop, including the origin, which can be negative on
	 * a multi-monitor setup.
	 *
	 * Deliberately NOT Electron's `disp.bounds`, which is DIP. Publishing the DIP rect here made
	 * the type and the producer disagree: `cu_geometry` told the model its screen was 1600x1000
	 * physical while telling it to inject physical pixels at 2x, so a click at (1849,1538) — dead
	 * centre of a 3200x2000 desktop — was denied as out of range. Every consumer of `bounds`
	 * (inBounds, the click guard, the geometry report) speaks injection coordinates.
	 */
	bounds: { x: number; y: number; width: number; height: number };
	/** how many physical pixels per DIP — the ratio that trips up CUA coordinate maths */
	scaleFactor: number;
	/** display size in DIPs. Exactly `bounds` divided by `scaleFactor`. */
	dip: { width: number; height: number };
}

export interface ScreenshotResult {
	ok: boolean;
	/** absolute path on disk — the model gets this, not the bytes */
	path?: string;
	/** physical pixels of the stored image */
	size?: { width: number; height: number };
	geometry?: ScreenGeometry;
	bytes?: number;
	reason?: string;
}

/**
 * Convert DIPs -> physical pixels. All injected coordinates must go through this; a tool that
 * passes DIPs straight to SendInput clicks roughly half-width on a 200% display.
 */
export function dipToPhysical(x: number, y: number, scaleFactor: number): { x: number; y: number } {
	const s = Number.isFinite(scaleFactor) && scaleFactor > 0 ? scaleFactor : 1;
	return { x: Math.round(x * s), y: Math.round(y * s) };
}

/** Physical -> DIP (for reporting coordinates back in human terms). */
export function physicalToDip(x: number, y: number, scaleFactor: number): { x: number; y: number } {
	const s = Number.isFinite(scaleFactor) && scaleFactor > 0 ? scaleFactor : 1;
	return { x: Math.round(x / s), y: Math.round(y / s) };
}

/**
 * Is a PHYSICAL point inside the desktop? Guards clicks aimed at nothing.
 *
 * Both sides must be the same space: callers pass injection coordinates (SendInput is physical),
 * so `g.bounds` has to be physical too. While `bounds` carried DIP instead, this rejected every
 * click outside a DIP-sized rectangle — i.e. everything on a 200% display beyond its top-left
 * half — with a message that gave the model no way to see the mismatch.
 */
export function inBounds(x: number, y: number, g: ScreenGeometry): boolean {
	return x >= g.bounds.x && y >= g.bounds.y
		&& x < g.bounds.x + g.bounds.width && y < g.bounds.y + g.bounds.height;
}

export interface WindowInfo {
	title: string;
	bounds: { x: number; y: number; width: number; height: number };
	focused: boolean;
	/** our own app — clicking ourselves is almost never what the model meant */
	isSelf: boolean;
}

/**
 * M21 hard-deny list, enforced HERE rather than in the system prompt. A prompt is advisory and
 * an injected instruction can talk straight past it; this is the layer that does not bend.
 * Includes the M18 shell deny entries that make sense as key injection.
 */
export const CU_HARD_DENY: readonly { pattern: RegExp; why: string }[] = [
	{ pattern: /^win(\+|\^)(l|d|x|u|r|e|i|s)$/i, why: 'Win+L 会锁屏、Win+D 隐藏全部窗口、其余为系统级快捷键' },
	{ pattern: /^(ctrl|alt)(\+|\^)(ctrl|alt|shift|win)(\+|\^)(del|delete)$/i, why: 'Ctrl+Alt+Del 是安全注意序列，系统级' },
	{ pattern: /^(ctrl)(\+|\^)(alt)(\+|\^)(del|delete)$/i, why: 'Ctrl+Alt+Del 是安全注意序列，系统级' },
	// F4 opens the window "Close" menu; F5/F6/F7 are NOT security relevant (F5 is just reload)
	// so they are deliberately left allowed — over-broad denies train users to approve blindly.
	{ pattern: /^f4$/i, why: 'F4 会打开窗口系统菜单' },
	{ pattern: /(shutdown|logoff|format\s+[a-z]:|diskpart|cipher\s+\/w|reg\s+delete)/i, why: '关机/磁盘/注册表类命令' },
];

export type KeyVerdict = { ok: true } | { ok: false; reason: string };

/** Check one keystroke against the hard-deny list. */
export function checkKeyAllowed(keys: string): KeyVerdict {
	const k = String(keys ?? '').trim();
	if (!k) { return { ok: false, reason: '按键为空。' }; }
	for (const d of CU_HARD_DENY) {
		if (d.pattern.test(k)) { return { ok: false, reason: `命中硬禁表：${d.why}（该类按键无法通过审批解锁）` }; }
	}
	return { ok: true };
}

export interface ForegroundState {
	/** our own BrowserWindows — only ever OUR windows, never third-party ones */
	windows: WindowInfo[];
	/**
	 * Real frontmost title from Win32 GetForegroundWindow, when the native module is present.
	 * This is the authoritative answer and it covers other applications; the window list
	 * cannot, because BrowserWindow.getAllWindows() only enumerates our own.
	 */
	systemTitle?: string | null;
}

/**
 * Window titles are volatile ("编辑器 - 3.txt" vs "编辑器"), so an exact-only match would make
 * the guard refuse constantly and teach users to approve blindly. A prefix match still pins the
 * app, which is what the check is actually for — it stops "click at 400,300" from landing in
 * whatever window happens to be under the cursor.
 */
export function titleMatches(actual: string, target: string): boolean {
	const a = actual.trim(), t = target.trim();
	return a === t || a.startsWith(t);
}

/**
 * Foreground precondition for injection. ZCode's frontmost-mismatch lesson: a click aimed at
 * a window that is no longer frontmost lands on whatever is under the cursor instead.
 *
 * Prefers the system foreground title; falls back to our own focused window when the native
 * module is absent, which is weaker but still better than injecting blind.
 */
export function checkForeground(state: ForegroundState, targetTitle?: string): KeyVerdict {
	const fromSystem = typeof state.systemTitle === 'string' && state.systemTitle.trim().length > 0;
	const foreground = fromSystem
		? String(state.systemTitle)
		: state.windows.find(w => w.focused)?.title ?? '';
	if (!foreground) {
		return { ok: false, reason: '未检测到前台窗口——无法安全注入（请先让目标窗口获得焦点）。' };
	}
	if (targetTitle) {
		if (!titleMatches(foreground, targetTitle)) {
			return {
				ok: false,
				reason: `目标窗口「${targetTitle}」当前不在前台（前台是「${foreground}」），已拒绝注入以免点错窗口。`,
			};
		}
	}
	return { ok: true };
}

/** Screen text extraction result (M21 cu_screen_text). */
export interface ScreenTextResult {
	ok: boolean;
	/** window title the text came from */
	window?: string;
	lines: string[];
	reason?: string;
}

/**
 * M21 screenshot store. Images land in the app data dir under cu/ and are named by timestamp;
 * we never return bytes to the model.
 */
export class CaptureStore {
	constructor(private readonly dir: string) { }

	/** Names are internally constructed or readdir-derived; either way the final path must stay in dir. */
	private inDir(name: string): string {
		const root = path.resolve(this.dir);
		const abs = path.resolve(root, name);
		if (path.dirname(abs) !== root) { throw new Error(`文件名越出截图目录：${name}`); }
		return abs;
	}

	async save(png: Buffer, meta: ScreenGeometry): Promise<ScreenshotResult> {
		if (!png.length) { return { ok: false, reason: '截图为空。' }; }
		try {
			await fs.mkdir(this.dir, { recursive: true });
			const name = `cu-${Date.now()}.png`;
			const abs = this.inDir(name);
			await fs.writeFile(abs, png);
			// PNG header carries the dimensions; read them back rather than trusting the caller.
			const { width, height } = pngSize(png);
			return {
				ok: true, path: abs, size: { width, height },
				geometry: meta, bytes: png.length,
			};
		} catch (e) {
			return { ok: false, reason: `截图落盘失败：${String((e as Error)?.message ?? e)}` };
		}
	}

	async list(limit = 20): Promise<{ path: string; bytes: number; at: number }[]> {
		try {
			const names = (await fs.readdir(this.dir)).filter(n => /^cu-\d+\.png$/.test(n)).sort().reverse().slice(0, limit);
			const out: { path: string; bytes: number; at: number }[] = [];
			for (const n of names) {
				const abs = this.inDir(n);
				const st = await fs.stat(abs);
				out.push({ path: abs, bytes: st.size, at: st.mtimeMs });
			}
			return out;
		} catch {
			return [];
		}
	}

	/** Delete old captures so screenshots do not accumulate forever. */
	async prune(keep = 20): Promise<number> {
		try {
			const names = (await fs.readdir(this.dir)).filter(n => /^cu-\d+\.png$/.test(n)).sort();
			let removed = 0;
			for (const n of names.slice(0, Math.max(0, names.length - keep))) {
				await fs.rm(this.inDir(n), { force: true });
				removed++;
			}
			return removed;
		} catch {
			return 0;
		}
	}
}

/** Read width/height out of a PNG IHDR chunk. Returns 0x0 when the buffer is not a PNG. */
export function pngSize(buf: Buffer): { width: number; height: number } {
	// 8-byte signature, then 4-byte length, 'IHDR', then width/height big-endian
	if (buf.length < 24) { return { width: 0, height: 0 }; }
	const sig = [137, 80, 78, 71, 13, 10, 26, 10];
	for (let i = 0; i < 8; i++) { if (buf[i] !== sig[i]) { return { width: 0, height: 0 }; } }
	if (buf.toString('ascii', 12, 16) !== 'IHDR') { return { width: 0, height: 0 }; }
	return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}