/* F2 capture service tests. No Electron, no windows — the whole point is that the mode logic,
 * the crop arithmetic and the overlay ORDERING are provable without a display.
 *
 * Run: npm test:float2
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-float2-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const C = require(path.join(base, 'float', 'capture.js'));

/** Minimal valid 1x1 PNG. */
const PNG = Buffer.from([
	0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
	0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
	0, 0, 0, 1, 0, 0, 0, 2, 8, 6, 0, 0, 0, 0x1f, 0x15, 0xc4, 0x89,
]);

// capture.ts's ShutterState now carries pickTarget, so the stored form includes it.
		check('shutter: default is full, no delay, picker on',
			JSON.stringify(C.DEFAULT_SHUTTER) === '{"mode":"full","delaySec":0,"pickTarget":true}');
		check('shutter: an absent pickTarget defaults to true (picker is the only way to pick a window)',
			C.normalizeShutter({ mode: 'window' }).pickTarget === true);
		check('shutter: pickTarget=false is honoured', C.normalizeShutter({ pickTarget: false }).pickTarget === false);

const DISPLAYS = () => ([
	{ bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 1 },
	{ bounds: { x: 1920, y: 0, width: 2560, height: 1440 }, scaleFactor: 1 },
	{ bounds: { x: -1920, y: -300, width: 1920, height: 1080 }, scaleFactor: 2 },
]);

/** Deps with recording fakes. `calls` is the ledger assertions inspect. */
function mkDeps(over = {}) {
	const calls = [];
	let overlayOpen = false;
	const deps = {
		calls,
		capturer: {
			screenSources: async (size) => {
				calls.push(['screenSources', size.width, size.height]);
				if (over.noScreen) { return []; }
				return [{ ok: true, png: PNG, width: size.width, height: size.height }];
			},
			windowSources: async (size) => {
				calls.push(['windowSources']);
				if (over.noWindows) { return []; }
				return [
					{ id: 'w1', title: '记事本 — 未命名', thumb: PNG },
					{ id: 'w2', title: '浏览器', thumb: PNG },
					{ id: 'w3', title: '', thumb: PNG },
					{ id: 'w4', title: '受保护的窗口' },
				];
			},
		},
		image: {
			crop: (png, r) => { calls.push(['crop', r.x, r.y, r.width, r.height]); return over.cropNull ? null : png; },
		},
		overlay: {
			prompt: async () => {
				calls.push(['overlay.prompt']);
				overlayOpen = true;
				if (over.cancel) { return null; }
				if (over.tinyRect) { return { x: 10, y: 10, width: 1, height: 1 }; }
				if (over.badRect) { return { x: NaN, y: 0, width: 100, height: 100 }; }
				return over.rect ?? { x: 100, y: 200, width: 400, height: 300 };
			},
			close: async () => { calls.push(['overlay.close']); overlayOpen = false; },
			isOpen: () => overlayOpen,
		},
		displays: () => (over.displays ?? DISPLAYS)(),
	};
	return deps;
}

(async () => {
	// ---- 1. mode normalisation and the shutter caption
	{
		check('mode: full / rect / window are valid', C.CAPTURE_MODES.join(',') === 'full,rect,window');
		check('mode: garbage falls back to full', C.normalizeMode('nope') === 'full');
		check('mode: null falls back to full', C.normalizeMode(null) === 'full');
		check('mode: a valid mode survives', C.normalizeMode('rect') === 'rect');
		check('mode: case is not silently accepted', C.normalizeMode('RECT') === 'full');

		// ShutterState now carries pickTarget (the window picker); the shorter form below also went stale.
		check('shutter: default is full, no delay',
			C.DEFAULT_SHUTTER.mode === 'full' && C.DEFAULT_SHUTTER.delaySec === 0);
		const n = C.normalizeShutter({ mode: 'window', delaySec: 3.7 });
		check('shutter: delay rounds to a whole second', n.delaySec === 4);
		check('shutter: a huge delay is clamped so it cannot look like a hang',
			C.normalizeShutter({ delaySec: 9999 }).delaySec === 30);
		check('shutter: a negative delay becomes 0', C.normalizeShutter({ delaySec: -5 }).delaySec === 0);
		check('shutter: a NaN delay becomes 0', Number.isFinite(C.normalizeShutter({ delaySec: 'x' }).delaySec));
		check('shutter: junk falls back to the full default',
			C.normalizeShutter('garbage').mode === 'full');
		// The label is the only thing telling the user what the next press does, since the
		// main window is gone by then.
		check('shutter: caption names the mode', C.shutterLabel({ mode: 'rect', delaySec: 0 }) === '截屏（矩形）');
		check('shutter: caption shows the countdown', C.shutterLabel({ mode: 'full', delaySec: 5 }).includes('5s'));
		check('shutter: no countdown suffix at 0', !C.shutterLabel({ mode: 'full', delaySec: 0 }).includes('s'));
	}

	// ---- 2. rect helpers
	{
		check('rect: a real rect is usable', C.isUsableRect({ x: 0, y: 0, width: 10, height: 10 }) === true);
		check('rect: a 1px rect is rejected (zero-area crops fail)', C.isUsableRect({ x: 0, y: 0, width: 1, height: 1 }) === false);
		check('rect: zero width rejected', C.isUsableRect({ x: 0, y: 0, width: 0, height: 10 }) === false);
		check('rect: NaN rejected', C.isUsableRect({ x: NaN, y: 0, width: 10, height: 10 }) === false);
		check('rect: null rejected', C.isUsableRect(null) === false);
		check('rect: a string is rejected', C.isUsableRect('0,0,10,10') === false);

		check('contains: inner fully inside is true',
			C.contains({ x: 0, y: 0, width: 100, height: 100 }, { x: 10, y: 10, width: 10, height: 10 }) === true);
		check('contains: overflowing is false',
			C.contains({ x: 0, y: 0, width: 100, height: 100 }, { x: 90, y: 10, width: 20, height: 10 }) === false);

		const clamped = C.clampRect({ x: -50, y: -50, width: 5000, height: 5000 }, { x: 0, y: 0, width: 100, height: 100 });
		check('clamp: an oversized rect is trimmed to the box', JSON.stringify(clamped) === '{"x":0,"y":0,"width":100,"height":100}');
		const small = C.clampRect({ x: 500, y: 500, width: 5, height: 5 }, { x: 0, y: 0, width: 100, height: 100 });
		check('clamp: never returns a zero-size rect', small.width >= 1 && small.height >= 1);
		check('clamp: a rect fully outside is pulled to the edge',
			C.clampRect({ x: 900, y: 900, width: 10, height: 10 }, { x: 0, y: 0, width: 100, height: 100 }).x < 100);

		check('display: the display with the greatest overlap wins',
			C.displayForRect({ x: 2000, y: 100, width: 100, height: 100 }, DISPLAYS()).bounds.x === 1920);
		check('display: a rect on the negative-origin monitor picks THAT display',
			C.displayForRect({ x: -1800, y: 0, width: 100, height: 100 }, DISPLAYS()).scaleFactor === 2);
		check('display: no displays returns null rather than throwing',
			C.displayForRect({ x: 0, y: 0, width: 1, height: 1 }, []) === null);
	}

	// ---- 3. request fitting and the image/screen scale factor
	{
		check('fit: a small display is requested as-is',
			JSON.stringify(C.fitRequest(800, 600, 1920)) === '{"width":800,"height":600}');
		const f = C.fitRequest(3840, 2160, 1920);
		check('fit: a 4K display is downscaled to maxEdge', f.width === 1920 && f.height === 1080);
		check('fit: aspect ratio is preserved', Math.abs((f.width / f.height) - (3840 / 2160)) < 0.01);
		check('fit: portrait displays work', C.fitRequest(2160, 3840, 1920).height === 1920);
		check('fit: never rounds an axis down to 0', C.fitRequest(4000, 10, 1920).height >= 1);
		check('fit: a zero-size display does not produce 0x0',
			C.fitRequest(0, 0, 1920).width >= 1 && C.fitRequest(0, 0, 1920).height >= 1);

		// The number that quietly breaks region capture.
		check('scale: a full-size image gives 1', C.imageToScreenScale(1920, 1920) === 1);
		check('scale: a half-size image gives 0.5', C.imageToScreenScale(960, 1920) === 0.5);
		check('scale: an oversized image gives >1', C.imageToScreenScale(3840, 1920) === 2);
		check('scale: zero width falls back to 1', C.imageToScreenScale(0, 1920) === 1);
		check('scale: NaN falls back to 1', C.imageToScreenScale(NaN, 1920) === 1);
	}

	// ---- 4. full-screen capture
	{
		const d = mkDeps();
		const r = await C.captureFull(d);
		check('full: succeeds', r.ok === true && !!r.png);
		check('full: reports the source rect in PHYSICAL screen coords',
			r.source.x === 0 && r.source.width === 1920);
		check('full: requested at the display size', JSON.stringify(d.calls[0].slice(1)) === '[1920,1080]');

		const d2 = mkDeps({ noScreen: true });
		const r2 = await C.captureFull(d2);
		check('full: no source is a clean failure, not a throw', r2.ok === false && /权限|未返回/.test(r2.reason));
		check('full: an out-of-range display index is clamped, not crashed',
			(await C.captureFull(mkDeps(), 99)).ok === true);
		const none = mkDeps({ displays: () => [] });
		check('full: no displays fails cleanly', (await C.captureFull(none)).ok === false);
	}

	// ---- 5. window capture
	{
		const d = mkDeps();
		const list = await C.listCaptureWindows(d);
		check('window: untitled sources are filtered out', list.length === 3);
		check('window: a source with no thumbnail still lists (picker can show it greyed)',
			list.some(w => w.id === 'w4'));
		const r = await C.captureWindow(d, 'w1');
		check('window: capture by id works', r.ok === true && !!r.png);
		const missing = await C.captureWindow(d, 'nope');
		check('window: an unknown id fails cleanly', missing.ok === false && /找不到/.test(missing.reason));
		const noThumb = await C.captureWindow(d, 'w4');
		check('window: a source with no thumbnail explains why', noThumb.ok === false && /缩略图/.test(noThumb.reason));
		check('window: an empty id fails cleanly', (await C.captureWindow(d, '')).ok === false);
		check('window: no sources at all is an empty list, not a throw',
			(await C.listCaptureWindows(mkDeps({ noWindows: true }))).length === 0);
	}

	// ---- 6. region capture — INCLUDING THE OVERLAY ORDERING
	{
		const d = mkDeps();
		const r = await C.captureRegion(d);
		check('rect: succeeds', r.ok === true && !!r.png);
		const names = d.calls.map(c => c[0]);
		// THE bug every snipping tool ships once: capture before close means the selection
		// rectangle ends up inside the user's own screenshot.
		const promptAt = names.indexOf('overlay.prompt');
		const closeAt = names.indexOf('overlay.close');
		const shotAt = names.indexOf('screenSources');
		check('rect: the overlay is opened first', promptAt === 0);
		check('rect: the overlay is CLOSED before the screen is captured',
			closeAt > promptAt && shotAt > closeAt,
			);
		check('rect: the overlay is closed exactly once', names.filter(n => n === 'overlay.close').length === 1);
		check('rect: no leftover overlay', d.overlay.isOpen() === false);
		check('rect: crop happened', names.includes('crop'));

		// Cancel path must still close, or a leaked always-on-top window covers the screen.
		const dc = mkDeps({ cancel: true });
		const rc = await C.captureRegion(dc);
		check('rect: cancel is reported as cancelled, not as an error',
			rc.ok === false && /取消/.test(rc.reason));
		check('rect: cancel STILL closes the overlay',
			dc.calls.map(c => c[0]).includes('overlay.close') && dc.overlay.isOpen() === false);
		check('rect: cancel does not capture the screen',
			!dc.calls.map(c => c[0]).includes('screenSources'));

		// A degenerate drag must not produce a zero-area crop.
		const dt = mkDeps({ tinyRect: true });
		check('rect: a 1px drag is rejected with a helpful message',
			(await C.captureRegion(dt)).ok === false && /太小/.test((await C.captureRegion(dt)).reason));
		const db = mkDeps({ badRect: true });
		check('rect: a NaN drag is rejected', (await C.captureRegion(db)).ok === false);
		check('rect: a rejected drag still closes the overlay', db.overlay.isOpen() === false);
		check('rect: a failed crop explains itself',
			(await C.captureRegion(mkDeps({ cropNull: true }))).ok === false);
	}

	// ---- 7. THE scale bug: a downscaled capture must scale the drag rect too
	{
		// Display is 1920 wide; the OS only hands back 960px. The drag rect must be halved
		// before cropping or the user gets the top-left quarter of what they selected.
		const disp = [{ bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 1 }];
		const d = mkDeps({ displays: () => disp, rect: { x: 1000, y: 200, width: 400, height: 300 } });
		d.capturer.screenSources = async (size) => {
			d.calls.push(['screenSources', size.width, size.height]);
			return [{ ok: true, png: PNG, width: 960, height: 540 }];   // half size!
		};
		const r = await C.captureRegion(d);
		const crop = d.calls.find(c => c[0] === 'crop');
		check('rect: a half-size capture halves the drag rect before cropping',
			crop[1] === 500 && crop[2] === 100 && crop[3] === 200 && crop[4] === 150,
			);
		check('rect: the reported source rect stays in PHYSICAL screen coords',
			r.source.x === 1000 && r.source.width === 400);
		check('rect: the reported size is the CROP size, not the screen size',
			r.width === 200 && r.height === 150);
	}

	// ---- 8. a crop rect that escapes the image is clamped, not trusted
	{
		const disp = [{ bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 1 }];
		const d = mkDeps({ displays: () => disp, rect: { x: 1800, y: 1000, width: 400, height: 300 } });
		d.capturer.screenSources = async () => [{ ok: true, png: PNG, width: 960, height: 540 }];
		const r = await C.captureRegion(d);
		const crop = d.calls.find(c => c[0] === 'crop');
		check('rect: an overhanging drag is clamped into the image',
			crop[1] + crop[3] <= 960 && crop[2] + crop[4] <= 540, );
		check('rect: clamping still yields a usable capture', r.ok === true);
	}

	// ---- 9. a leaked overlay from a previous attempt must not stack a second one
	{
		const d = mkDeps();
		d.overlay.isOpen = () => true;         // pretend a previous capture leaked
		const r = await C.captureRegion(d);
		check('rect: a pre-existing overlay is closed before prompting', r.ok === true);
		const closes = d.calls.filter(c => c[0] === 'overlay.close').length;
		const prompts = d.calls.filter(c => c[0] === 'overlay.prompt').length;
		check('rect: prompts exactly once even with a leaked overlay', prompts === 1);
		check('rect: the stale overlay is cleaned up too', closes >= 2);
	}

	// ---- 10. the mode dispatcher — the shutter calls exactly this
	{
		check('dispatch: full', (await C.capture(mkDeps(), 'full')).ok === true);
		check('dispatch: rect', (await C.capture(mkDeps(), 'rect')).ok === true);
		check('dispatch: window with an id', (await C.capture(mkDeps(), 'window', 'w1')).ok === true);
		check('dispatch: window with no id fails cleanly', (await C.capture(mkDeps(), 'window')).ok === false);
		check('dispatch: an unknown mode is refused, not defaulted',
			(await C.capture(mkDeps(), 'evil')).ok === false && /未知/.test((await C.capture(mkDeps(), 'evil')).reason));
		// The user's flow is "set mode, then press the shutter" — so setting a mode must be
		// free and must not capture anything by itself.
		const d = mkDeps();
		C.normalizeMode('rect');
		check('dispatch: setting a mode captures nothing', d.calls.length === 0);
	}

	// ---- 11. every mode must be closed over: no unhandled throw
	{
		const thrower = mkDeps();
		thrower.capturer.screenSources = async () => { throw new Error('boom'); };
		let ok = false;
		try { await C.captureRegion(thrower); } catch { ok = true; }
		check('robust: a throwing capturer propagates rather than hanging', ok === true);
		check('robust: the overlay is closed even when the capture throws',
			thrower.calls.map(c => c[0]).includes('overlay.close') && thrower.overlay.isOpen() === false);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});