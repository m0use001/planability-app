/* F0 keep-alive policy — should the app quit when its last window closes?
 *
 * Pure decision function, no Electron, because this is the one piece of "the app vanished and
 * I can't get it back" logic and it must be provable without launching the app.
 *
 * The user's Q1=A: when the main window closes in floating mode, the process must SURVIVE,
 * held by a tray icon. Without a tray the process is invisible in both the taskbar and the
 * system tray, and if the user then closes a floater there is no way back except killing and
 * relaunching — which reads as malware, not as a feature.
 *
 * So the rule is NOT "never quit". It is: quit when the user asked to quit, or when there is
 * nothing left to come back to. Everything else keeps the process alive with a tray.
 */

export type WindowRole = 'main' | 'float-input' | 'float-menu' | 'float-output' | 'capsule' | 'overlay';

/** Windows that should NOT keep the app alive on their own. */
const NON_PERSISTENT: readonly WindowRole[] = ['capsule', 'overlay'];

export interface AliveState {
	/** True when the floating group is engaged — the user collapsed the input box. */
	floatMode: boolean;
	/** Roles currently open. */
	open: WindowRole[];
	/** A tray icon exists and is visible. */
	trayVisible: boolean;
	/** The user explicitly chose Exit (from the tray or a menu). */
	quitRequested: boolean;
	/** The app is shutting down for another reason (update, will-quit). */
	shuttingDown: boolean;
}

/** Why we did or did not quit — surfaced in logs and in the tray tooltip. */
export type KeepAliveReason =
	| 'quit-requested'
	| 'shutting-down'
	| 'no-windows-left'
	| 'float-mode-without-tray'
	| 'float-mode-survives'
	| 'main-window-closed-but-alive';

export interface KeepAliveDecision {
	quit: boolean;
	reason: KeepAliveReason;
}

/**
 * Decide whether to quit. Called from `window-all-closed` and from tray Exit.
 *
 * Note what is deliberately NOT here: we never quit because "the last float window closed".
 * A user who collapses the input, then closes the floater by mistake, must still find the app
 * in the tray. The tray's Exit is the way out.
 */
export function decideKeepAlive(s: AliveState): KeepAliveDecision {
	if (s.shuttingDown) { return { quit: true, reason: 'shutting-down' }; }
	if (s.quitRequested) { return { quit: true, reason: 'quit-requested' }; }

	const persistent = s.open.filter(r => !NON_PERSISTENT.includes(r));
	if (!persistent.length) {
		// Nothing left that the user could come back to. Quitting is correct — unless they are
		// in floating mode, which by definition means the main window is closed and the group
		// is what they are using.
		return s.floatMode
			? { quit: false, reason: 'float-mode-without-tray' }
			: { quit: true, reason: 'no-windows-left' };
	}

	if (!s.open.includes('main')) {
		// The main window is gone but something else lives. In floating mode that is the
		// intended state (the user's R4: the body exits, the floaters stay).
		return { quit: false, reason: 'float-mode-survives' };
	}
	return { quit: false, reason: 'main-window-closed-but-alive' };
}

/**
 * Tray tooltip. It has to say what the app is doing *right now*, because with no window on
 * screen the tray tooltip is the entire UI.
 */
export function trayTooltip(s: AliveState): string {
	if (s.floatMode) {
		return 'Planability — 悬浮窗模式（托盘图标：点击恢复主窗口，退出请用右键菜单）';
	}
	return 'Planability';
}

/**
 * One-line status for the tray menu's disabled header item, so the menu is not just buttons.
 */
export function trayStatusLine(s: AliveState): string {
	if (!s.trayVisible) { return '托盘不可用'; }
	if (s.floatMode) {
		const n = s.open.filter(r => r.startsWith('float-')).length;
		return n ? `悬浮中（${n} 个浮窗）` : '悬浮中（浮窗已关闭，右键退出）';
	}
	return s.open.includes('main') ? '主窗口运行中' : '主窗口已关闭';
}

/**
 * Native image for the tray, generated rather than shipped.
 *
 * An .ico/.png in the repo is a binary nobody can review in a diff and nobody wants to
 * regenerate when the brand colour changes. A 16x16 solid rounded square built from raw
 * pixels is ~30 lines and reviewable. Electron's nativeImage.createFromBuffer accepts PNG,
 * so we emit a valid PNG by hand (IHDR + IDAT with zlib "stored" deflate + IEND).
 */
export function trayIconPng(size = 16): Buffer {
	// Raw RGBA scanlines: each row is 1 filter byte + size*4 bytes.
	const raw = Buffer.alloc(size * (1 + size * 4));
	for (let y = 0; y < size; y++) {
		const rowStart = y * (1 + size * 4);
		raw[rowStart] = 0; // filter: none
		for (let x = 0; x < size; x++) {
			const p = rowStart + 1 + x * 4;
			// A filled circle, so the tray icon reads as an app dot rather than a square blob.
			const cx = size / 2 - 0.5, cy = size / 2 - 0.5;
			const inside = (x - cx) ** 2 + (y - cy) ** 2 <= (size / 2 - 1.5) ** 2;
			raw[p] = inside ? 0x4c : 0;        // R
			raw[p + 1] = inside ? 0x9a : 0;    // G
			raw[p + 2] = inside ? 0xff : 0;    // B
			raw[p + 3] = inside ? 0xff : 0;    // A
		}
	}
	return pngFromRaw(raw, size, size);
}

/** Minimal PNG encoder using zlib "stored" blocks — no dependency, valid output. */
export function pngFromRaw(raw: Buffer, width: number, height: number): Buffer {
	const chunks: Buffer[] = [Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])];
	chunks.push(pngChunk('IHDR', ihdr(width, height)));
	// zlib header + stored deflate blocks + adler32.
	chunks.push(pngChunk('IDAT', zlibStored(raw)));
	chunks.push(pngChunk('IEND', Buffer.alloc(0)));
	return Buffer.concat(chunks);
}

function ihdr(width: number, height: number): Buffer {
	const b = Buffer.alloc(13);
	b.writeUInt32BE(width, 0);
	b.writeUInt32BE(height, 4);
	b[8] = 8;   // bit depth
	b[9] = 6;   // colour type: RGBA
	b[10] = 0;  // deflate
	b[11] = 0;  // filter method
	b[12] = 0;  // no interlace
	return b;
}

/** zlib stream using uncompressed deflate blocks (BTYPE=00). */
function zlibStored(data: Buffer): Buffer {
	const parts: Buffer[] = [Buffer.from([0x78, 0x01])]; // zlib, no compression, fastest
	const MAX = 65535;
	for (let off = 0; off < data.length; off += MAX) {
		const chunk = data.subarray(off, off + MAX);
		const last = off + MAX >= data.length ? 1 : 0;
		const head = Buffer.alloc(5);
		head[0] = last;
		head.writeUInt16LE(chunk.length, 1);
		head.writeUInt16LE(~chunk.length & 0xffff, 3);
		parts.push(head, chunk);
	}
	parts.push(Buffer.alloc(4));
	parts[parts.length - 1].writeUInt32BE(adler32(data), 0);
	return Buffer.concat(parts);
}

function adler32(data: Buffer): number {
	let a = 1, b = 0;
	for (const byte of data) {
		a = (a + byte) % 65521;
		b = (b + a) % 65521;
	}
	return ((b << 16) | a) >>> 0;
}

function crc32(buf: Buffer): number {
	let c = 0xffffffff;
	for (const byte of buf) {
		c ^= byte;
		for (let k = 0; k < 8; k++) {
			c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
		}
	}
	return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length, 0);
	const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(body), 0);
	return Buffer.concat([len, body, crc]);
}

/** Base64 of the tray icon, for `nativeImage.createFromDataURL`. */
export function trayIconDataUrl(size = 16): string {
	return `data:image/png;base64,${trayIconPng(size).toString('base64')}`;
}