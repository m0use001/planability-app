/* Loader for the M21 native injection module (native/planability-cu, napi-rs / Node-API).
 *
 * Three decisions worth stating, because they are the ones that cost time when wrong:
 *
 * 1. FAIL-SOFT, and say why. If the .node is missing (fresh clone, no Rust build yet), we
 *    return null and the tools report the reason. The alternative — throwing at require time —
 *    would take down the whole main process, i.e. one unbuilt optional feature would kill the
 *    app. A feature that cannot load must degrade, not detonate.
 *
 * 2. PATH SEARCH BY WALKING UP, not a fixed relative path. The same file is loaded from three
 *    different layouts: the electron-vite bundle (out/main/), the tsc test build (.test/), and a
 *    packaged app (resources/). A hardcoded "../../native" is correct in exactly one of them.
 *
 * 3. NO new npm dependency. napi-rs's value here is that Node-API is ABI-stable, so the module
 *    needs no electron-rebuild when Electron bumps its ABI. We keep that property by not taking
 *    on @napi-rs/cli and hand-writing this loader plus the .d.ts for seven functions.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';

export interface CuNative {
	/** "enter" / "ctrl+s" / "ctrl+shift+p". Throws on an unknown key or a UIPI block. */
	injectKey(keysSpec: string): void;
	/** Literal UTF-16 text injection (CJK/emoji safe). */
	injectTypeText(text: string): void;
	/** Move to a PHYSICAL pixel point and click, as one atomic SendInput batch. */
	injectClick(x: number, y: number, button: string): void;
	/** notches > 0 scrolls up. */
	injectScroll(x: number, y: number, notches: number): void;
	cursorPos(): number[];
	/** Title of the frontmost window — including windows that are not ours. */
	foregroundTitle(): string | null;
	/** Virtual desktop rect in physical pixels; x/y are often negative on multi-monitor. */
	virtualScreen(): { x: number; y: number; width: number; height: number };
	abiVersion(): number;
}

const REL = path.join('native', 'planability-cu', 'planability-cu.node');
/** How far up to walk before giving up. Covers out/main/ and .test/src/main/ai/ alike. */
const MAX_UP = 8;

/** Every place the module might live, in priority order. Exported for the smoke test. */
export function cuNativeCandidates(from: string, resourcesPath?: string): string[] {
	const out: string[] = [];
	if (resourcesPath) { out.push(path.join(resourcesPath, REL)); }
	let dir = from;
	for (let i = 0; i <= MAX_UP; i++) {
		out.push(path.join(dir, REL));
		const up = path.dirname(dir);
		if (up === dir) { break; }
		dir = up;
	}
	return out;
}

let cached: CuNative | null | undefined;
/** Set when loading failed, so the tool layer can explain itself instead of shrugging. */
let lastError: string | null = null;

/**
 * Load the native module once. Returns null when unavailable — never throws.
 * `resourcesPath` is passed in so this module stays free of an `electron` import and remains
 * testable under plain node.
 */
export function loadCuNative(resourcesPath?: string): CuNative | null {
	if (cached !== undefined) { return cached; }
	cached = null;
	lastError = null;
	const tried = cuNativeCandidates(__dirname, resourcesPath);
	for (const p of tried) {
		if (!fs.existsSync(p)) { continue; }
		try {
			// eslint-disable-next-line @typescript-eslint/no-var-requires
			const mod = require(p) as CuNative;
			// A stale .node from an older build would load but miss exports; check the shape.
			for (const fn of ['injectKey', 'injectClick', 'injectScroll', 'virtualScreen']) {
				if (typeof (mod as unknown as Record<string, unknown>)[fn] !== 'function') {
					throw new Error(`模块缺少导出 ${fn}（可能是旧版本残留，请重新构建）`);
				}
			}
			cached = mod;
			return cached;
		} catch (e) {
			lastError = `${p}: ${(e as Error)?.message ?? String(e)}`;
		}
	}
	if (!lastError) {
		lastError = `未找到 ${REL}（已尝试 ${tried.length} 个位置）——请先运行 npm run cu:build`;
	}
	return null;
}

/** Why the module is unavailable, or null if it loaded. For error messages. */
export function cuNativeError(): string | null {
	loadCuNative();
	return cached ? null : lastError;
}

/** Test seam: forget the cached result. */
export function resetCuNativeCache(): void {
	cached = undefined;
	lastError = null;
}