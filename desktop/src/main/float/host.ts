/* IPC handlers for the floating window group.
 *
 * Kept separate from index.ts because this is the layer that owns the float group's windows;
 * index.ts already carries the app-wide wiring and adding more would make both worse.
 *
 * Every handler here is thin on purpose. The decisions live in float/state.ts, float/layout.ts
 * and float/keepalive.ts, which are pure and tested; these functions translate between IPC and
 * those modules, plus the few things that genuinely need Electron (creating windows, capturing,
 * broadcasting). Anything with a branch worth arguing about belongs in a tested module.
 */

import { BrowserWindow, desktopCapturer, ipcMain, nativeImage, screen } from 'electron';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';

import { FLOAT, type FloatEvent, type FloatStateInfo } from '../../../shared/float';
import {
	capture, listCaptureWindows, normalizeMode, type CaptureMode, type CaptureTarget,
	type RawCapture,
} from './capture';
import { loadCuNative } from '../ai/cu-native';
import {
	allLinked, clampToDisplay, defaultLayout, loadLayout, movePane, relayout,
	saveLayout, setAllLinked, setLinked, planBounds, PANE_ORDER,
	type LayoutSnapshot, type PaneRole,
} from './layout';
import { decideKeepAlive } from './keepalive';
import { normalizeShutter } from './capture';
import {
	addAttachment, DraftStore, enterFloat, exitFloat, floatSession,
	removeAttachment, type FloatModeState, type ShutterConfig, type ShutterMode,
} from './state';

const SIZES: Record<PaneRole, { width: number; height: number }> = {
	input: { width: 560, height: 132 },
	menu: { width: 560, height: 48 },
	output: { width: 560, height: 320 },
};

export interface FloatHostDeps {
	/** The main window factory, so "exit float" can restore it. */
	openMainWindow(): void;
	/** Hide the main window (the user's "软件本体退出"). Optional: absent = keep it visible. */
	hideMainWindow?(): void;
	/** Config file read/write, for persisting the geometry. */
	readConfig(): Record<string, unknown>;
	writeConfig(patch: Record<string, unknown>): void;
	/** Where to write screenshots. */
	shotsDir: string;
	/** Current main window, for the "is a turn streaming" check. */
	isStreaming(): boolean;
	/** Keep-alive inputs, shared with index.ts so both agree on whether we quit. */
	aliveState(): { floatMode: boolean; open: string[]; trayVisible: boolean; quitRequested: boolean; shuttingDown: boolean };
}

export class FloatHost {
	private readonly drafts = new DraftStore();
	private modeState: FloatModeState = { phase: 'docked' };
	private layout: LayoutSnapshot;
	/** The shutter config. Named `shutterCfg` because `shutter` is the public capture action. */
	private shutterCfg: ShutterConfig = { mode: 'full', delaySec: 0, pickTarget: true };
	private readonly wins: Partial<Record<PaneRole, BrowserWindow>> = {};
	private regionOverlay: BrowserWindow | null = null;
	private disposeTrayRefresh: (() => void) | null = null;

	constructor(private readonly deps: FloatHostDeps) {
		const cfg = deps.readConfig();
		this.shutterCfg = normalizeShutter(cfg.floatShutter);
		const saved = cfg.floatLayout;
		const wa = screen.getPrimaryDisplay().workArea;
		const anchor: LayoutSnapshot = defaultLayout(
			{ x: Math.round(wa.x + wa.width / 2 - SIZES.input.width / 2), y: Math.round(wa.y + 24), ...SIZES.input },
			SIZES.menu.height, SIZES.output.height,
		);
		this.layout = saved ? loadLayout(saved, anchor) : anchor;
	}

	/* ---------------- state broadcast ---------------- */

	private info(): FloatStateInfo {
		const layout = {} as FloatStateInfo['layout'];
		for (const role of PANE_ORDER) {
			const p = this.layout.panes[role];
			layout[role] = { rect: { ...p.rect }, linked: p.linked };
		}
		return {
			mode: this.modeState,
			captureMode: this.shutterCfg.mode as CaptureMode,
			open: this.openRoles(),
			layout,
			anchor: this.layout.anchor,
			allLinked: allLinked(this.layout),
			shotSupported: true,
		};
	}

	private openRoles(): PaneRole[] {
		return (['input', 'menu', 'output'] as PaneRole[]).filter(r => {
			const w = this.wins[r];
			return !!w && !w.isDestroyed();
		});
	}

	/** Push a state change to every float window AND the main window. */
	private broadcast(e: FloatEvent): void {
		const payload = { ...e, state: this.info() };
		for (const w of BrowserWindow.getAllWindows()) {
			if (w.isDestroyed()) { continue; }
			w.webContents.send(FLOAT.event, payload);
		}
	}

	/* ---------------- windows ---------------- */

	/**
	 * Create a pane window. Frameless, transparent, always-on-top, skipTaskbar — the same
	 * shape as the existing focus capsule, which is the only working precedent in this app.
	 *
	 * `resizable: false` is deliberate: resizing a pane in a snapped group means re-solving the
	 * stack, and a half-finished drag leaves panes overlapping. Size changes come from the layout.
	 */
	private ensure(role: PaneRole): BrowserWindow {
		const existing = this.wins[role];
		if (existing && !existing.isDestroyed()) { return existing; }
		const rect = this.layout.panes[role].rect;
		const win = new BrowserWindow({
			x: Math.round(rect.x), y: Math.round(rect.y),
			width: Math.round(rect.width), height: Math.round(rect.height),
			frame: false, transparent: true, resizable: false, movable: true,
			alwaysOnTop: true, skipTaskbar: true, hasShadow: true,
			webPreferences: {
				preload: path.join(__dirname, '../preload/index.js'),
				contextIsolation: true,
				nodeIntegration: false,
				sandbox: false,
			},
		});
		// The float renderer entry is a separate HTML page; until pc-a builds it, a missing
		// page must not take the app down, so we fall back to the main renderer.
		const url = process.env.ELECTRON_RENDERER_URL
			? `${process.env.ELECTRON_RENDERER_URL}/float.html`
			: path.join(__dirname, '../renderer/float.html');
		void win.loadURL(url).catch((e: Error) => {
			console.error('[float] renderer load failed —', e.message);
			if (!win.isDestroyed()) { void win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(FALLBACK_HTML)); }
		});
		win.on('closed', () => { this.wins[role] = undefined; });
		// Dragging a pane re-solves the group. Debounced because 'moved' fires per pixel.
		win.on('moved', () => {
			if (this.dragTimer) { clearTimeout(this.dragTimer); }
			this.dragTimer = setTimeout(() => {
				if (win.isDestroyed()) { return; }
				const b = win.getBounds();
				this.applyMove(role, { x: b.x, y: b.y, width: b.width, height: b.height });
			}, 60);
		});
		this.wins[role] = win;
		return win;
	}

	private dragTimer: NodeJS.Timeout | null = null;

	/** Re-solve the group from a pane's new position and push the result to the windows. */
	private applyMove(role: PaneRole, next: { x: number; y: number; width: number; height: number }): void {
		this.layout = movePane(this.layout, role, next, { gap: this.layoutGap() });
		// Clamp EVERY pane, then do not relayout: relayout re-derives the linked panes from the
		// anchor and would silently undo the clamp. See the ordering trap in float2 tests.
		const displays = screen.getAllDisplays().map(d => ({ id: d.id, bounds: d.bounds }));
		const panes = {} as LayoutSnapshot['panes'];
		for (const r of PANE_ORDER) {
			panes[r] = { ...this.layout.panes[r], rect: clampToDisplay(this.layout.panes[r].rect, displays) };
		}
		this.layout = { anchor: this.layout.anchor, panes };
		this.pushBounds();
		this.broadcast({ type: 'layout' });
	}

	/** Push the solved rectangles onto the real windows. */
	private pushBounds(): void {
		// The rule lives in `planBounds` so it can be tested without Electron — the version that
		// lived here skipped detached panes and nothing in the suite was able to notice. A
		// `linked: false` pane owns its rect, so it has to be pushed like any other; only a pane
		// with no window left, or a destroyed one, is skipped.
		const moves = planBounds(this.layout, (role) => {
			const w = this.wins[role];
			return w && !w.isDestroyed() ? w.getBounds() : null;
		});
		for (const m of moves) {
			const w = this.wins[m.role];
			if (w && !w.isDestroyed()) { w.setPosition(m.x, m.y); }
		}
	}

	private layoutGap(): number {
		const stored = this.deps.readConfig().floatLayout;
		const g = stored && typeof stored === 'object' ? (stored as { gap?: number }).gap : undefined;
		return Number.isFinite(g) ? Number(g) : 0;
	}

	private persist(): void {
		this.deps.writeConfig({
			floatLayout: saveLayout(this.layout, this.layoutGap()),
			floatShutter: this.shutterCfg,
		});
	}

	/* ---------------- mode transitions ---------------- */

	/**
	 * Enter floating mode. The draft is NOT copied anywhere: it already lives in this process,
	 * keyed by session, which is what makes the switch lossless in both directions.
	 */
	enter(sessionId: string): { ok: boolean; reason?: string; state: FloatStateInfo } {
		const streaming = this.deps.isStreaming();
		const next = enterFloat(this.modeState, sessionId, streaming);
		if (next === this.modeState) {
			// Refused: either already floating, or streaming. Distinguish for a useful message.
			if (streaming) {
				return { ok: false, reason: '正在输出中，等这一轮结束再切换。', state: this.info() };
			}
			return { ok: true, state: this.info() };
		}
		this.modeState = next;
		for (const role of ['input', 'menu', 'output'] as PaneRole[]) {
			const w = this.ensure(role);
			if (!w.isVisible()) { w.showInactive(); }
		}
		// The main window is the user's R4: the body exits. Hiding (not destroying) keeps
		// "switch back" instant — the tray, not the hidden window, is what holds the process.
		this.deps.hideMainWindow?.();
		this.pushBounds();
		this.broadcast({ type: 'mode' });
		return { ok: true, state: this.info() };
	}

	exit(): FloatStateInfo {
		const session = floatSession(this.modeState);
		if (session) {
			// Carry the draft across explicitly so the restore path has something to read even
			// if the store is later reset.
			this.drafts.forceSet(session, this.drafts.get(session));
		}
		this.modeState = exitFloat(this.modeState);
		for (const role of ['input', 'menu', 'output'] as PaneRole[]) {
			const w = this.wins[role];
			if (w && !w.isDestroyed()) { w.close(); }
		}
		this.deps.openMainWindow();
		this.broadcast({ type: 'mode' });
		this.persist();
		return this.info();
	}

	/* ---------------- draft ---------------- */

	draftGet(sessionId: string) { return this.drafts.get(sessionId); }

	draftSet(sessionId: string, text: string,
		attachments: { path: string; width?: number; height?: number; bytes?: number }[],
		baseRevision: number) {
		const v = this.drafts.set(sessionId, { text, attachments }, baseRevision, 0);
		if (!v.ok) { return { ok: false, reason: 'stale-revision', current: v.current }; }
		this.broadcast({ type: 'draft' });
		// `state` on success, `current` on a stale write. Never both: a caller checking
		// `ok && state` must not be able to read a state it did not just cause.
		return { ok: true, state: v.state };
	}

	draftAttach(sessionId: string, a: { path: string; width?: number; height?: number; bytes?: number }) {
		const next = addAttachment(this.drafts.get(sessionId), a);
		this.drafts.forceSet(sessionId, next);
		this.broadcast({ type: 'draft' });
		return next;
	}

	draftRemove(sessionId: string, p: string) {
		const next = removeAttachment(this.drafts.get(sessionId), p);
		this.drafts.forceSet(sessionId, next);
		this.broadcast({ type: 'draft' });
		return next;
	}

	/* ---------------- capture ---------------- */

	modeSet(m: CaptureMode): CaptureMode {
		this.shutterCfg = { ...this.shutterCfg, mode: normalizeShutter({ ...this.shutter, mode: m }).mode as ShutterMode };
		this.persist();
		return this.shutterCfg.mode as CaptureMode;
	}

	async listTargets(): Promise<CaptureTarget[]> {
		return listCaptureWindows({
			capturer: this.capturer(),
			image: { crop: () => null },
			overlay: this.overlayStub(),
			displays: () => this.virtualDisplays(),
		});
	}

	/**
	 * The shutter. Captures with the current mode and attaches the PNG to the draft.
	 *
	 * Per Q4=A the image stays on disk and the model is handed a path; nothing here sends bytes
	 * anywhere.
	 */
	async shutter(sessionId: string): Promise<{
		ok: boolean; attachment?: { path: string; width?: number; height?: number; bytes?: number };
		error?: string; needTarget?: boolean;
	}> {
		if (this.shutterCfg.mode === 'window' && this.shutterCfg.pickTarget) {
			const targets = await this.listTargets();
			if (targets.length > 1) { return { ok: false, needTarget: true }; }
		}
		const shot = await this.runCapture();
		if (!shot.ok || !shot.png) { this.broadcast({ type: 'shot', error: shot.reason }); return { ok: false, error: shot.reason }; }
		const a = await this.writeShot(shot);
		if (!a) { return { ok: false, error: '截图落盘失败。' }; }
		this.draftAttach(sessionId, a);
		this.broadcast({ type: 'shot', attachment: a });
		return { ok: true, attachment: a };
	}

	/** Capture by an explicit window id, for the window-mode picker. */
	async shutterWindow(sessionId: string, id: string) {
		const shot = await this.runCapture('window', id);
		if (!shot.ok || !shot.png) { this.broadcast({ type: 'shot', error: shot.reason }); return { ok: false, error: shot.reason }; }
		const a = await this.writeShot(shot);
		if (!a) { return { ok: false, error: '截图落盘失败。' }; }
		this.draftAttach(sessionId, a);
		this.broadcast({ type: 'shot', attachment: a });
		return { ok: true, attachment: a };
	}

	/** Run a capture in an explicit mode. `arg` is the window id for window mode. */
	private runCapture(mode?: CaptureMode, arg?: string): Promise<RawCapture> {
		return capture({
			capturer: this.capturer(),
			image: { crop: (png, r) => nativeImage.createFromBuffer(png).crop(r).toPNG() },
			overlay: this.overlay(),
			displays: () => this.virtualDisplays(),
		}, mode ?? (this.shutterCfg.mode as CaptureMode), arg);
	}

	/** Write the PNG under the app's shots dir and return a path-only attachment. */
	private async writeShot(shot: RawCapture): Promise<{ path: string; width?: number; height?: number; bytes?: number } | null> {
		try {
			await fs.mkdir(this.deps.shotsDir, { recursive: true });
			const p = path.join(this.deps.shotsDir, `shot-${Date.now()}.png`);
			await fs.writeFile(p, shot.png!);
			return { path: p, width: shot.width, height: shot.height, bytes: shot.png!.length };
		} catch (e) {
			console.error('[float] shot write failed —', (e as Error)?.message ?? e);
			return null;
		}
	}

	/* ---------------- the region overlay ---------------- */

	/**
	 * A frameless, transparent, always-on-top window covering one display, used to drag a
	 * selection. Its HTML is inline (the capsule precedent) so this needs no renderer work.
	 *
	 * The dimming layer is the important bit: without it the user cannot see what they are
	 * selecting on a bright window, and the selection rectangle itself would be visible in the
	 * resulting screenshot if the overlay outlived the capture — which captureRegion's `finally`
	 * prevents.
	 */
	private overlay() {
		const host = this;
		return {
			isOpen: () => !!this.regionOverlay && !this.regionOverlay.isDestroyed(),
			close: async () => {
				const o = this.regionOverlay;
				this.regionOverlay = null;
				if (o && !o.isDestroyed()) { o.destroy(); }
			},
			prompt: async (display: { bounds: { x: number; y: number; width: number; height: number }; scaleFactor: number }): Promise<{ x: number; y: number; width: number; height: number } | null> => {
				await this.overlay().close();
				const b = display.bounds;
				const win = new BrowserWindow({
					x: b.x, y: b.y, width: b.width, height: b.height,
					frame: false, transparent: true, resizable: false, movable: false,
					alwaysOnTop: true, skipTaskbar: true, hasShadow: false, enableLargerThanScreen: true,
					webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: false },
				});
				this.regionOverlay = win;
				// The overlay reports its own rect in CSS pixels; convert to physical here, since
				// the capture side always speaks physical pixels.
				return new Promise(resolve => {
					let settled = false;
					const finish = (v: { x: number; y: number; width: number; height: number } | null) => {
						if (settled) { return; }
						settled = true;
						host.regionOverlay = null;
						if (!win.isDestroyed()) { win.destroy(); }
						resolve(v);
					};
					ipcMain.once('float:overlayRect', (_e, r: { x: number; y: number; width: number; height: number } | null) => { finish(r); });
					win.on('closed', () => { finish(null); });
					win.once('ready-to-show', () => win.show());
					void win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(overlayHtml(b)));
				});
			},
		};
	}

	/** A no-op overlay for code paths that only list windows. */
	private overlayStub() {
		return { isOpen: () => false, close: async () => { }, prompt: async () => null };
	}

	private capturer() {
		return {
			screenSources: async (size: { width: number; height: number }) => {
				const srcs = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: size });
				return srcs.map(s => {
					const png = s.thumbnail.toPNG();
					return { ok: true, png, width: s.thumbnail.getSize().width, height: s.thumbnail.getSize().height };
				});
			},
			windowSources: async (size: { width: number; height: number }) => {
				const srcs = await desktopCapturer.getSources({ types: ['window'], thumbnailSize: size, fetchWindowIcons: true });
				return srcs.map(s => ({
					id: s.id,
					title: s.name,
					thumb: s.thumbnail.toPNG(),
					appIcon: s.appIcon?.toPNG(),
				}));
			},
		};
	}

	/** Physical-pixel geometry for every display, including a NEGATIVE virtual origin. */
	private virtualDisplays() {
		return screen.getAllDisplays().map(d => ({
			bounds: { x: d.bounds.x, y: d.bounds.y, width: d.bounds.width, height: d.bounds.height },
			scaleFactor: d.scaleFactor || 1,
		}));
	}

	/* ---------------- layout control ---------------- */

	movePaneTo(role: PaneRole, x: number, y: number): FloatStateInfo {
		const p = this.layout.panes[role];
		this.applyMove(role, { x, y, width: p.rect.width, height: p.rect.height });
		return this.info();
	}

	setLinkedPane(role: PaneRole | 'all', linked: boolean): FloatStateInfo {
		this.layout = role === 'all'
			? setAllLinked(this.layout, linked)
			: setLinked(this.layout, role, linked);
		this.pushBounds();
		this.persist();
		this.broadcast({ type: 'layout' });
		return this.info();
	}

	save(): void { this.persist(); }

	/** Re-solve from the anchor without moving anything — used after a restore. */
	relayoutNow(): void {
		this.layout = relayout(this.layout, { gap: this.layoutGap() });
		this.pushBounds();
	}

	/* ---------------- IPC registration ---------------- */

	register(): void {
		ipcMain.handle(FLOAT.state, () => this.info());
		ipcMain.handle(FLOAT.enter, (_e, sessionId: string) => this.enter(String(sessionId ?? '')));
		ipcMain.handle(FLOAT.exit, () => this.exit());
		ipcMain.handle(FLOAT.draftGet, (_e, sessionId: string) => this.draftGet(String(sessionId ?? '')));
		ipcMain.handle(FLOAT.draftSet, (_e, sessionId: string, text: string,
			attachments: { path: string }[], baseRevision: number) =>
			this.draftSet(String(sessionId ?? ''), String(text ?? ''), Array.isArray(attachments) ? attachments : [], Number(baseRevision)));
		ipcMain.handle(FLOAT.draftAttach, (_e, sessionId: string, a: { path: string }) =>
			this.draftAttach(String(sessionId ?? ''), a));
		ipcMain.handle(FLOAT.draftRemove, (_e, sessionId: string, p: string) =>
			this.draftRemove(String(sessionId ?? ''), String(p ?? '')));
		ipcMain.handle(FLOAT.modeSet, (_e, m: CaptureMode) => this.modeSet(m));
		ipcMain.handle(FLOAT.shutter, (_e, sessionId: string) => this.shutter(String(sessionId ?? '')));
		ipcMain.handle(FLOAT.shutterWindow, (_e, sessionId: string, id: string) =>
			this.shutterWindow(String(sessionId ?? ''), String(id ?? '')));
		ipcMain.handle(FLOAT.listTargets, () => this.listTargets());
		ipcMain.handle(FLOAT.movePane, (_e, role: PaneRole, x: number, y: number) =>
			this.movePaneTo(String(role) as PaneRole, Number(x), Number(y)));
		ipcMain.handle(FLOAT.setLinked, (_e, role: PaneRole | 'all', linked: boolean) =>
			this.setLinkedPane(role, linked));
		ipcMain.handle(FLOAT.saveLayout, () => { this.save(); });
	}

	/** Test/inspection helper. */
	get currentMode() { return this.modeState; }

	/**
	 * Which pane windows exist right now.
	 *
	 * index.ts reads this for the keep-alive decision, and that is not a convenience: it used to
	 * read a `floatWins` placeholder that nothing ever wrote, so `floatMode` was permanently false
	 * and `open` never contained a `float-*` role. With the main window destroyed that made
	 * decideKeepAlive report `no-windows-left` and quit the process — taking the floating group,
	 * and the user's session, with it. The owner of the windows has to be the one being asked
	 * whether they exist.
	 */
	get openPaneRoles(): PaneRole[] { return this.openRoles(); }

	/** Is the region-drag overlay up? Non-persistent, but it is a real window on screen. */
	get overlayOpen(): boolean { return !!this.regionOverlay && !this.regionOverlay.isDestroyed(); }
}

/** Shown if the float renderer page is missing; keeps the app usable. */
const FALLBACK_HTML = `<!doctype html><meta charset="utf-8"><body style="margin:0;
	background:#1b1b1f;color:#c9c9cf;font:13px system-ui;display:grid;place-items:center;height:100vh">
	悬浮窗界面尚未构建（等待 renderer 侧）。</body>`;

/**
 * The drag overlay's inline page. Reports a rect in CSS pixels via the float:overlayRect
 * channel; Escape cancels, which resolves null rather than a zero rect.
 */
function overlayHtml(b: { width: number; height: number }): string {
	return `<!doctype html><meta charset="utf-8"><style>
		html,body{margin:0;height:100%;background:transparent;overflow:hidden;cursor:crosshair;
			-webkit-user-select:none;user-select:none}
		#dim{position:fixed;inset:0;background:rgba(0,0,0,.28)}
		#sel{position:fixed;border:1px solid #4c9aff;background:rgba(76,154,255,.18);display:none}
		#hint{position:fixed;left:50%;top:24px;transform:translateX(-50%);color:#fff;font:13px system-ui;
			background:rgba(0,0,0,.6);padding:6px 12px;border-radius:6px}
	</style><div id="dim"></div><div id="sel"></div><div id="hint">拖动框选区域 · Esc 取消</div>
	<script>
		const sel=document.getElementById('sel');let a=null;
		addEventListener('mousedown',e=>{a={x:e.clientX,y:e.clientY};sel.style.display='block';});
		addEventListener('mousemove',e=>{if(!a)return;const x=Math.min(a.x,e.clientX),y=Math.min(a.y,e.clientY);
			Object.assign(sel.style,{left:x+'px',top:y+'px',width:Math.abs(e.clientX-a.x)+'px',height:Math.abs(e.clientY-a.y)+'px'});});
		addEventListener('mouseup',e=>{if(!a)return;const r={x:Math.min(a.x,e.clientX),y:Math.min(a.y,e.clientY),
			width:Math.abs(e.clientX-a.x),height:Math.abs(e.clientY-a.y)};
			require('electron').ipcRenderer.send('float:overlayRect',r.width>2&&r.height>2?r:null);});
		addEventListener('keydown',e=>{if(e.key==='Escape'){require('electron').ipcRenderer.send('float:overlayRect',null);}});
	</script>`;
}