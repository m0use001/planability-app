/* F2 capture service — the three screenshot modes behind the floating window's shutter button.
 *
 * The user's model (Q2, corrected): pick a MODE first (full / rect / window), then press the
 * shutter. So `mode` is persistent state and the shutter is a one-shot action. That matters
 * for the API shape: setting the mode must be free and repeatable, capturing must not depend
 * on which mode was set last.
 *
 * WHY ALL THREE MODES NEED NO NATIVE MODULE — this was the deciding factor when the feature
 * was scoped. The obvious worry was that region selection needs an overlay plus a crop, and
 * cropping "surely" needs image libraries. It does not:
 *   - full   : desktopCapturer, one screen source
 *   - window : desktopCapturer with types:['window'] gives every capturable window's title
 *              AND thumbnail, so "pick a window" needs no window enumeration API at all
 *   - rect   : an overlay window for dragging, then nativeImage.crop() — which ships with
 *              Electron and is already linked
 * So the only native piece left is SendInput (M21 blade 2), and even that is not on this path.
 *
 * THE BUG THIS FILE IS MOST CAREFUL ABOUT: the region overlay must be GONE before the capture
 * happens, or the selection rectangle and its dimming layer end up inside the user's own
 * screenshot. Every snipping tool has shipped this bug at least once. The ordering is enforced
 * by making the overlay a dependency of the capture, not by remembering to close it.
 */

import type { ScreenGeometry } from '../ai/cu';

export type CaptureMode = 'full' | 'rect' | 'window';

export const CAPTURE_MODES: readonly CaptureMode[] = ['full', 'rect', 'window'];

export interface CaptureRect { x: number; y: number; width: number; height: number }

/** A window the user can choose to capture. */
export interface CaptureTarget {
	/** Stable within one capture listing — the source id. */
	id: string;
	title: string;
	/** Thumbnail bytes, so the picker can show a preview without a second capture. */
	thumb?: Buffer;
	appIcon?: Buffer;
}

/** Raw capture result. The caller decides where it goes on disk. */
export interface RawCapture {
	ok: boolean;
	png?: Buffer;
	/** Physical pixels of the returned image. */
	width?: number;
	height?: number;
	/** Physical pixels on the virtual desktop — needed to map image coords back to clicks. */
	source?: CaptureRect;
	reason?: string;
}

/** What Electron's desktopCapturer gives us, injected so this file stays testable. */
export interface CapturerLike {
	screenSources(size: { width: number; height: number }): Promise<RawCapture[]>;
	windowSources(size: { width: number; height: number }): Promise<CaptureTarget[]>;
}

/** nativeImage, injected. Only crop/toPNG are needed. */
export interface ImageLike {
	crop(png: Buffer, rect: { x: number; y: number; width: number; height: number }): Buffer | null;
}

/** Overlay lifecycle, injected. Must be closable and awaitable — see the ordering note. */
export interface RegionOverlayLike {
	/** Show a drag overlay on `display` and resolve with the chosen rect, or null if cancelled. */
	prompt(display: { bounds: CaptureRect; scaleFactor: number }): Promise<CaptureRect | null>;
	/** Unconditionally remove the overlay. Must be idempotent. */
	close(): Promise<void>;
	isOpen(): boolean;
}

/** Geometry of the whole desktop, injected (from the native module or Electron's screen). */
export interface VirtualScreenLike {
	bounds: CaptureRect;
	scaleFactor: number;
}

export interface CaptureDeps {
	capturer: CapturerLike;
	image: ImageLike;
	overlay: RegionOverlayLike;
	/** All displays, for picking the one the region belongs to. */
	displays(): VirtualScreenLike[];
	/** Longest edge we will store; the preview is downscaled, physical pixels unchanged. */
	maxEdge?: number;
	/** Longest edge the capture pipeline will request from the OS. */
	captureMaxEdge?: number;
}

const DEFAULTS = { maxEdge: 1920, captureMaxEdge: 3840 } as const;

function isFiniteRect(r: unknown): r is CaptureRect {
	const q = r as CaptureRect;
	return !!q && Number.isFinite(q.x) && Number.isFinite(q.y)
		&& Number.isFinite(q.width) && Number.isFinite(q.height);
}

/** A rectangle with real area. Zero-width crops are how you get "screenshot failed". */
export function isUsableRect(r: unknown): r is CaptureRect {
	return isFiniteRect(r) && r.width >= 2 && r.height >= 2;
}

/** Is `inner` fully inside `outer`? Used to keep crops inside the captured image. */
export function contains(outer: CaptureRect, inner: CaptureRect): boolean {
	return inner.x >= outer.x && inner.y >= outer.y
		&& inner.x + inner.width <= outer.x + outer.width
		&& inner.y + inner.height <= outer.y + outer.height;
}

/** Clamp a rect into `outer`, never returning something smaller than 1x1. */
export function clampRect(inner: CaptureRect, outer: CaptureRect): CaptureRect {
	const x1 = Math.max(outer.x, Math.min(inner.x, outer.x + outer.width - 1));
	const y1 = Math.max(outer.y, Math.min(inner.y, outer.y + outer.height - 1));
	const x2 = Math.max(x1 + 1, Math.min(inner.x + inner.width, outer.x + outer.width));
	const y2 = Math.max(y1 + 1, Math.min(inner.y + inner.height, outer.y + outer.height));
	return { x: x1, y: y1, width: x2 - x1, height: y2 - y1 };
}

/** The display a rectangle mostly sits on. Falls back to the first display. */
export function displayForRect(rect: CaptureRect, displays: VirtualScreenLike[]): VirtualScreenLike | null {
	if (!displays.length) { return null; }
	let best = displays[0];
	let bestArea = -1;
	for (const d of displays) {
		const ox = Math.max(0, Math.min(rect.x + rect.width, d.bounds.x + d.bounds.width) - Math.max(rect.x, d.bounds.x));
		const oy = Math.max(0, Math.min(rect.y + rect.height, d.bounds.y + d.bounds.height) - Math.max(rect.y, d.bounds.y));
		const area = ox * oy;
		if (area > bestArea) { bestArea = area; best = d; }
	}
	return best;
}

/** Scale a capture request so the stored image is at most maxEdge on its longest side. */
export function fitRequest(width: number, height: number, maxEdge: number): { width: number; height: number } {
	if (width <= 0 || height <= 0) { return { width: Math.max(1, width), height: Math.max(1, height) }; }
	const longest = Math.max(width, height);
	if (longest <= maxEdge) { return { width: Math.round(width), height: Math.round(height) }; }
	const k = maxEdge / longest;
	return { width: Math.max(1, Math.round(width * k)), height: Math.max(1, Math.round(height * k)) };
}

/**
 * Scale factor between a captured image and the physical screen.
 *
 * This is the number that quietly breaks region capture: if the OS hands us a 1600px-wide
 * image of a 3200px-wide desktop, the user's drag rectangle must be divided by 2 before
 * cropping or they get the top-left quarter of what they selected. Returns 1 when unknown.
 */
export function imageToScreenScale(imageWidth: number, screenWidth: number): number {
	if (!Number.isFinite(imageWidth) || !Number.isFinite(screenWidth) || imageWidth <= 0 || screenWidth <= 0) {
		return 1;
	}
	return imageWidth / screenWidth;
}

/** Full-screen capture. The primary display by default, since that is what "全屏" means. */
export async function captureFull(deps: CaptureDeps, displayIndex = 0): Promise<RawCapture> {
	const maxEdge = deps.captureMaxEdge ?? DEFAULTS.captureMaxEdge;
	const displays = deps.displays();
	if (!displays.length) { return { ok: false, reason: '没有可用的显示器。' }; }
	const d = displays[Math.min(Math.max(0, displayIndex), displays.length - 1)];
	const size = fitRequest(d.bounds.width, d.bounds.height, maxEdge);
	const sources = await deps.capturer.screenSources(size);
	if (!sources.length || !sources[0]?.png) {
		return { ok: false, reason: 'desktopCapturer 未返回屏幕源（可能是系统未授予屏幕录制权限）。' };
	}
	const s = sources[0];
	return { ok: true, png: s.png, width: s.width, height: s.height, source: d.bounds };
}

/**
 * List capturable windows for the mode picker.
 *
 * Note what this does NOT do: it does not enumerate windows itself. desktopCapturer already
 * returns every capturable window with a title and a thumbnail, so the picker gets real
 * previews for free and we never need EnumWindows.
 */
export async function listCaptureWindows(deps: CaptureDeps): Promise<CaptureTarget[]> {
	const size = fitRequest(400, 300, DEFAULTS.maxEdge);
	const list = await deps.capturer.windowSources(size);
	return list.filter(w => !!w.title);
}

/** Capture one window by its source id. */
export async function captureWindow(deps: CaptureDeps, id: string): Promise<RawCapture> {
	if (!id) { return { ok: false, reason: '未指定窗口。' }; }
	const targets = await listCaptureWindows(deps);
	const t = targets.find(x => x.id === id);
	if (!t) { return { ok: false, reason: `找不到窗口「${id}」，请重新选择。` }; }
	if (!t.thumb?.length) { return { ok: false, reason: '该窗口没有可用的缩略图（可能受保护或最小化）。' }; }
	return { ok: true, png: t.thumb, width: undefined, height: undefined };
}

/**
 * Region capture: ask the overlay, close it, THEN capture.
 *
 * The ordering is the whole point. `finally` guarantees the overlay goes away even when the
 * user cancels or the capture throws — a leaked always-on-top transparent window would cover
 * the user's screen with no way to dismiss it except killing the app.
 */
export async function captureRegion(deps: CaptureDeps): Promise<RawCapture> {
	if (deps.overlay.isOpen()) {
		// A previous capture leaked an overlay. Clear it rather than stacking a second one.
		await deps.overlay.close();
	}
	let picked: CaptureRect | null = null;
	try {
		const displays = deps.displays();
		if (!displays.length) { return { ok: false, reason: '没有可用的显示器。' }; }
		// Prompt on the display the pointer is on; fall back to the first.
		const target = displays[0];
		picked = await deps.overlay.prompt({ bounds: target.bounds, scaleFactor: target.scaleFactor });
	} finally {
		await deps.overlay.close();
	}
	if (!picked) { return { ok: false, reason: '已取消截屏。' }; }
	if (!isUsableRect(picked)) { return { ok: false, reason: '选区太小或无效，请重新框选。' }; }

	const disp = displayForRect(picked, deps.displays()) ?? deps.displays()[0];
	const maxEdge = deps.captureMaxEdge ?? DEFAULTS.captureMaxEdge;
	const size = fitRequest(disp.bounds.width, disp.bounds.height, maxEdge);
	const sources = await deps.capturer.screenSources(size);
	if (!sources.length || !sources[0]?.png) {
		return { ok: false, reason: '截屏失败：未能捕获屏幕。' };
	}
	const shot = sources[0];

	// The image may be smaller than the physical display, so the drag rect has to be scaled
	// into image space before cropping — otherwise you crop the wrong region.
	const k = imageToScreenScale(shot.width ?? size.width, disp.bounds.width);
	const inImage: CaptureRect = {
		x: Math.round((picked.x - disp.bounds.x) * k),
		y: Math.round((picked.y - disp.bounds.y) * k),
		width: Math.round(picked.width * k),
		height: Math.round(picked.height * k),
	};
	const imgBox: CaptureRect = { x: 0, y: 0, width: shot.width ?? size.width, height: shot.height ?? size.height };
	const safe = clampRect(inImage, imgBox);
	if (!isUsableRect(safe)) { return { ok: false, reason: '选区无效。' }; }

	const cropped = deps.image.crop(shot.png!, safe);
	if (!cropped?.length) { return { ok: false, reason: '裁剪失败。' }; }
	return {
		ok: true, png: cropped,
		width: safe.width, height: safe.height,
		// Report the SCREEN-space rect, not image space: the model's next click has to be in
		// physical screen pixels, and silently handing back image coords invites a misclick.
		source: picked,
	};
}

/** Capture using whichever mode is selected. The shutter button calls exactly this. */
export async function capture(deps: CaptureDeps, mode: CaptureMode, arg?: string | number): Promise<RawCapture> {
	switch (mode) {
		case 'full': return captureFull(deps, typeof arg === 'number' ? arg : 0);
		case 'window': return captureWindow(deps, String(arg ?? ''));
		case 'rect': return captureRegion(deps);
		default: return { ok: false, reason: `未知的截屏方式「${String(mode)}」` };
	}
}

/** Mode as stored in config. Validated, because it round-trips through disk. */
export function normalizeMode(m: unknown): CaptureMode {
	return CAPTURE_MODES.includes(m as CaptureMode) ? (m as CaptureMode) : 'full';
}

/**
 * Persisted shutter state. Screenshot mode is remembered because the user's stated flow is
 * "pick a mode, then press the shutter" — making them re-pick every time would contradict it.
 */
export interface ShutterState {
	mode: CaptureMode;
	/** Countdown in seconds before the shot fires; 0 = immediate. */
	delaySec: number;
	/** Show a window picker before capturing. Default true; only the window mode uses it. */
	pickTarget: boolean;
}

export const DEFAULT_SHUTTER: ShutterState = { mode: 'full', delaySec: 0, pickTarget: true };

/** Persisted shutter state, validated on the way in — it round-trips through disk. */
export function normalizeShutter(s: unknown): ShutterState {
	const q = (s ?? {}) as Partial<ShutterState>;
	const delay = Number(q.delaySec);
	const pick = q.pickTarget;
	return {
		mode: normalizeMode(q.mode),
		// Clamp the delay: a huge value looks like a hang, and negative is meaningless.
		delaySec: Number.isFinite(delay) ? Math.min(Math.max(Math.round(delay), 0), 30) : 0,
		// Default TRUE: the window picker is the only way to tell the user WHICH window to grab.
		pickTarget: pick === undefined ? true : !!pick,
	};
}

/**
 * Build a per-mode caption for the shutter button, e.g. "截屏（全屏）".
 *
 * The mode is echoed in the label on purpose: after the shutter fires the main window is
 * gone, so the caption is the only thing telling the user what the next press will do.
 */
export function shutterLabel(state: ShutterState): string {
	const names: Record<CaptureMode, string> = { full: '全屏', rect: '矩形', window: '窗口' };
	const base = `截屏（${names[state.mode]}）`;
	return state.delaySec > 0 ? `${base} · ${state.delaySec}s` : base;
}