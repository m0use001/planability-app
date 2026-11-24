/* F0 keep-alive tests. No Electron — this decides whether the user's app disappears, so it
 * gets tested harder than a feature of its size otherwise would warrant.
 *
 * Run: npm test:float0
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const zlib = require('node:zlib');

const LOG = path.join(os.tmpdir(), 'pcb-float0-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const K = require(path.join(base, 'float', 'keepalive.js'));

const S = (over = {}) => ({
	floatMode: false, open: ['main'], trayVisible: true,
	quitRequested: false, shuttingDown: false, ...over,
});

(async () => {
	// ---- 1. the baseline: closing the main window of a normal session quits
	{
		check('quit: no windows at all and not in float mode -> quit',
			K.decideKeepAlive(S({ open: [] })).quit === true);
		check('quit: ...and the reason says why', K.decideKeepAlive(S({ open: [] })).reason === 'no-windows-left');
		check('quit: only non-persistent windows left -> quit (capsule/overlay never keep it alive)',
			K.decideKeepAlive(S({ open: ['capsule'] })).quit === true);
		check('quit: an overlay alone does not keep the app alive',
			K.decideKeepAlive(S({ open: ['overlay'] })).quit === true);
		check('quit: capsule + overlay together still quits',
			K.decideKeepAlive(S({ open: ['capsule', 'overlay'] })).quit === true);
	}

	// ---- 2. R4: floating mode survives the main window closing
	{
		const r = K.decideKeepAlive(S({ floatMode: true, open: ['float-input', 'float-menu', 'float-output'] }));
		check('float: the group keeps the process alive', r.quit === false);
		check('float: the reason names the mode', r.reason === 'float-mode-survives');

		// The case the whole design exists for: the user closes EVERY floater by mistake.
		// There is no window, yet quitting would leave them with no way back at all.
		const lost = K.decideKeepAlive(S({ floatMode: true, open: [] }));
		check('float: closing every floater does NOT quit (tray is the way out)',
			lost.quit === false && lost.reason === 'float-mode-without-tray');
		check('float: only a menu left still survives',
			K.decideKeepAlive(S({ floatMode: true, open: ['float-menu'] })).quit === false);
	}

	// ---- 3. explicit quit always wins, no matter what is open
	{
		check('quit: the tray Exit button quits even with floaters open',
			K.decideKeepAlive(S({ floatMode: true, open: ['float-input', 'float-output'], quitRequested: true })).quit === true);
		check('quit: ...with the right reason', K.decideKeepAlive(S({ quitRequested: true })).reason === 'quit-requested');
		check('quit: shutting-down wins over quitRequested',
			K.decideKeepAlive(S({ quitRequested: true, shuttingDown: true })).reason === 'shutting-down');
		check('quit: an update-triggered shutdown is not confused with the user quitting',
			K.decideKeepAlive(S({ floatMode: true, open: ['float-input'], shuttingDown: true })).quit === true);
	}

	// ---- 4. main closed but something else lives
	{
		const r = K.decideKeepAlive(S({ open: ['main', 'float-input'] }));
		check('mixed: any persistent window alive -> stay alive', r.quit === false);
	}

	// ---- 5. the tray is the entire UI when no window is on screen
	{
		const tip = K.trayTooltip(S({ floatMode: true, open: [] }));
		check('tooltip: floating mode says how to get back', /托盘/.test(tip) && /右键/.test(tip));
		check('tooltip: normal mode stays short', K.trayTooltip(S()) === 'Planability');
		check('status: counts the floaters', /3 个浮窗/.test(K.trayStatusLine(S({ floatMode: true, open: ['float-input', 'float-menu', 'float-output'] }))));
		check('status: says the floaters are gone but you can still exit',
			/浮窗已关闭/.test(K.trayStatusLine(S({ floatMode: true, open: [] }))));
		check('status: a missing tray is called out', K.trayStatusLine(S({ trayVisible: false })) === '托盘不可用');
		check('status: normal mode reads normally', K.trayStatusLine(S()) === '主窗口运行中');
		check('status: capsule/overlay do not inflate the float count',
			!/胶囊|capsule/.test(K.trayStatusLine(S({ floatMode: true, open: ['float-input', 'capsule'] }))));
	}

	// ---- 6. the generated PNG must actually BE a valid PNG
	// An icon that Electron silently fails to decode shows up as an invisible tray entry,
	// which is indistinguishable from "the tray feature is broken".
	{
		const png = K.trayIconPng(16);
		check('icon: PNG signature', Buffer.compare(png.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) === 0);
		check('icon: ends with IEND', png.subarray(png.length - 8, png.length - 4).toString('ascii') === 'IEND');
		check('icon: IHDR carries the requested size',
			png.readUInt32BE(16) === 16 && png.readUInt32BE(20) === 16);
		check('icon: colour type is RGBA', png[25] === 6);

		// The real test: zlib must be able to inflate it back to the exact raw scanlines.
		const chunks = [];
		let off = 8;
		while (off < png.length) {
			const len = png.readUInt32BE(off);
			const type = png.subarray(off + 4, off + 8).toString('ascii');
			if (type === 'IDAT') { chunks.push(png.subarray(off + 8, off + 8 + len)); }
			off += 12 + len;
		}
		check('icon: an IDAT chunk exists', chunks.length >= 1);
		let inflated = null;
		try { inflated = zlib.inflateSync(Buffer.concat(chunks)); } catch { }
		check('icon: zlib inflates the IDAT (valid deflate stream)', inflated !== null);
		check('icon: inflated size matches RGBA scanlines',
			inflated !== null && inflated.length === 16 * (1 + 16 * 4));
		if (inflated) {
			// Row 0 filter byte must be 0 (None) for every row.
			let filtersOk = true, anyOpaque = false;
			for (let y = 0; y < 16; y++) {
				const rs = y * (1 + 16 * 4);
				if (inflated[rs] !== 0) { filtersOk = false; }
				// Scan the whole row, not just pixel 0 — pixel 0 is the circle's corner and is
				// legitimately transparent, so reading only it would prove nothing.
				for (let x = 0; x < 16; x++) {
					if (inflated[rs + 1 + x * 4 + 3] > 0) { anyOpaque = true; }
				}
			}
			check('icon: every scanline uses filter None', filtersOk);
			check('icon: at least one pixel is opaque (not a blank icon)', anyOpaque);
			// Corner of a circle must be transparent, centre opaque.
			const alphaAt = (x, y) => inflated[y * (1 + 16 * 4) + 1 + x * 4 + 3];
			check('icon: the centre is opaque', alphaAt(8, 8) === 255);
			check('icon: the corner is transparent (round, not a square blob)', alphaAt(0, 0) === 0);
		}
		const url = K.trayIconDataUrl(16);
		check('icon: data URL is well formed', url.startsWith('data:image/png;base64,') && url.length > 200);
		check('icon: a 32px icon is also valid', K.trayIconPng(32).readUInt32BE(16) === 32);
		// Sizes that would produce a zero-byte row must not throw.
		check('icon: a 1px icon does not throw', K.trayIconPng(1).length > 50);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});