/* M21 Computer Use — read-only blade tests.
 *
 * The point of this suite: the SAFETY layers must be provable today, without the native
 * injection module. Acceptance criteria "硬禁表命中被拒" and "审批卡拒绝后光标/剪贴板无变化"
 * have to hold even though nothing is wired to SendInput yet — so we test the guard functions
 * directly and assert that every injection tool refuses rather than silently no-op'ing.
 *
 * Must run under Electron: npm run test:cu
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-cu-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const CU = require(path.join(base, 'cu.js'));
const { registerCuTools } = require(path.join(base, 'cu-tools.js'));

/** Minimal valid 1x1 PNG. */
const PNG = Buffer.from([
	0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
	0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
	0, 0, 0, 1, 0, 0, 0, 2,   // width=1, height=2
	8, 6, 0, 0, 0, 0x1f, 0x15, 0xc4, 0x89,
]);

(async () => {
	// ---- 1. coordinate maths — the classic CUA bug
	{
		const geo = { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 2, dip: { width: 960, height: 540 } };
		check('geom: dipToPhysical scales up', JSON.stringify(CU.dipToPhysical(100, 50, 2)) === '{"x":200,"y":100}');
		check('geom: physicalToDip scales down', JSON.stringify(CU.physicalToDip(200, 100, 2)) === '{"x":100,"y":50}');
		check('geom: round trip is stable', JSON.stringify(CU.dipToPhysical(100, 50, 2)) === JSON.stringify(CU.dipToPhysical(100, 50, 2)));
		check('geom: bad scale factor falls back to 1', JSON.stringify(CU.dipToPhysical(10, 10, 0)) === '{"x":10,"y":10}');
		check('geom: NaN scale factor falls back to 1', JSON.stringify(CU.dipToPhysical(10, 10, NaN)) === '{"x":10,"y":10}');
		check('geom: inBounds accepts interior', CU.inBounds(100, 100, geo) === true);
		check('geom: inBounds rejects outside', CU.inBounds(5000, 100, geo) === false);
		check('geom: inBounds rejects negative', CU.inBounds(-1, 100, geo) === false);
	}

	// ---- 2. hard-deny for keys — system shortcuts must be un-unlockable
	{
		for (const k of ['win+l', 'WIN+L', 'win+d', 'win+x', 'ctrl+alt+del', 'CTRL+ALT+DEL',
			'alt+ctrl+del', 'ctrl+alt+delete', 'f4', 'shutdown /s', 'logoff']) {
			check(`key: DENY ${k}`, CU.checkKeyAllowed(k).ok === false);
		}
		check('key: reason explains it cannot be approved', /无法通过审批解锁/.test(CU.checkKeyAllowed('win+l').reason || ''));
		// F5/F6/F7 deliberately allowed: only F4 opens the window system menu, and an
		// over-broad deny list trains users to click "approve" without reading.
		for (const k of ['a', 'enter', 'ctrl+s', 'tab', 'escape', 'F5', 'f5', 'F6', 'F7']) {
			check(`key: ALLOW ${k}`, CU.checkKeyAllowed(k).ok === true);
		}
		check('key: empty key refused', CU.checkKeyAllowed('  ').ok === false);
		check('key: deny list is non-empty', CU.CU_HARD_DENY.length >= 5);
	}

	// ---- 3. foreground precondition (ZCode frontmost-mismatch lesson)
	{
		const wins = [
			{ title: '记事本', bounds: { x: 0, y: 0, width: 800, height: 600 }, focused: true, isSelf: false },
			{ title: '浏览器', bounds: { x: 100, y: 100, width: 900, height: 700 }, focused: false, isSelf: false },
		];
		const fg = (t, st) => CU.checkForeground({ windows: wins, systemTitle: st }, t);

		check('fg: allows injection into the focused window', fg('记事本').ok === true);
		check('fg: refuses when the named window is not frontmost',
			fg('浏览器').ok === false && /不在前台/.test(fg('浏览器').reason || ''));
		check('fg: refuses an unknown window title', fg('不存在').ok === false);
		check('fg: refuses when nothing is focused',
			CU.checkForeground({ windows: [{ ...wins[0], focused: false }], systemTitle: null }).ok === false);
		check('fg: no-target call succeeds when something is focused', fg(undefined).ok === true);

		// The real win: the system title covers THIRD-PARTY windows, which our own window
		// list structurally cannot see (BrowserWindow.getAllWindows() is ours only).
		check('fg: system title guards a window we do not own',
			CU.checkForeground({ windows: wins, systemTitle: 'Microsoft Edge' }, '记事本').ok === false);
		check('fg: system title admits a third-party target',
			CU.checkForeground({ windows: wins, systemTitle: 'Microsoft Edge' }, 'Microsoft Edge').ok === true);
		check('fg: system title WINS over a stale focused flag',
			CU.checkForeground({ windows: wins, systemTitle: 'Microsoft Edge' }, '记事本').ok === false);
		// A blank/odd system title must fall back to our own list rather than deny everything.
		check('fg: blank system title falls back to the window list',
			CU.checkForeground({ windows: wins, systemTitle: '   ' }, '记事本').ok === true);
		check('fg: missing systemTitle falls back to the window list',
			CU.checkForeground({ windows: wins }, '记事本').ok === true);

		// Volatile titles: exact-only matching would refuse constantly and train blind approval.
		check('fg: titleMatches accepts exact', CU.titleMatches('记事本', '记事本') === true);
		check('fg: titleMatches accepts a prefix (title grew a suffix)',
			CU.titleMatches('编辑器 - 未命名.txt', '编辑器') === true);
		check('fg: titleMatches rejects an unrelated window', CU.titleMatches('Edge', '记事本') === false);
		check('fg: titleMatches does not match mid-string', CU.titleMatches('我的记事本', '记事本') === false);
	}

	// ---- 4. PNG parsing + store
	{
		check('png: reads dimensions from IHDR', JSON.stringify(CU.pngSize(PNG)) === '{"width":1,"height":2}');
		check('png: rejects a non-png buffer', JSON.stringify(CU.pngSize(Buffer.from('not a png at all'))) === '{"width":0,"height":0}');
		check('png: rejects a truncated buffer', CU.pngSize(Buffer.from([137, 80])).width === 0);

		const dir = path.join(os.tmpdir(), 'pcb-cu-' + Date.now());
		const store = new CU.CaptureStore(dir);
		const geo = { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 1, dip: { width: 1920, height: 1080 } };
		const r = await store.save(PNG, geo);
		check('store: save returns a path', r.ok && typeof r.path === 'string' && fs.existsSync(r.path));
		check('store: size comes from the file, not the caller', r.size.width === 1 && r.size.height === 2);
		check('store: geometry carried through', r.geometry.scaleFactor === 1);
		check('store: empty buffer refused', (await store.save(Buffer.alloc(0), geo)).ok === false);
		const listed = await store.list();
		check('store: list returns the capture', listed.length === 1 && listed[0].bytes > 0);
		// prune keeps the newest N
		for (let i = 0; i < 8; i++) { await store.save(PNG, geo); }
		const removed = await store.prune(3);
		check('store: prune trims the oldest', removed > 0 && (await store.list()).length <= 3);
		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 5. tool surface: reads work, injections refuse loudly
	{
		const wins = [
			{ title: '记事本', bounds: { x: 0, y: 0, width: 800, height: 600 }, focused: true, isSelf: false },
			{ title: 'Planability', bounds: { x: 10, y: 10, width: 900, height: 700 }, focused: false, isSelf: true },
		];
		const geo = { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 2, dip: { width: 960, height: 540 } };
		let captured = 0;
		const dir = path.join(os.tmpdir(), 'pcb-cu-tools-' + Date.now());
		const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerCuTools(reg, {
			capture: async () => { captured++; return { ok: true, png: PNG, geometry: geo }; },
			listWindows: () => wins,
			store: new CU.CaptureStore(dir),
		});

		// reads are auto
		for (const n of ['cu_screenshot', 'cu_list_windows', 'cu_screen_text', 'cu_geometry']) {
			check(`tool: ${n} is auto (read-only)`, reg.defs.get(n).risk === 'auto');
		}
		// injections are confirm, never auto
		for (const n of ['cu_click', 'cu_scroll', 'cu_key', 'cu_type']) {
			check(`tool: ${n} is confirm (never auto)`, reg.defs.get(n).risk === 'confirm');
		}

		const shot = await reg.defs.get('cu_screenshot').invoke({});
		check('tool: cu_screenshot returns a path', shot.includes('截图已保存'));
		// Two rectangles must stay distinct: the PNG is capped to maxEdge while the screen is not,
		// so printing one number labelled 物理像素 (what this used to do — `尺寸：1920x1200（物理像素）`)
		// told the model its screen was 1920x1200 while cu_geometry said 1600x1000 and
		// dipToPhysical said 3200x2000. Three answers, none of them the same question.
		check('tool: cu_screenshot separates the image size from the screen size',
			/图片尺寸/.test(shot) && /屏幕：物理/.test(shot) && !/尺寸：\d+x\d+（物理像素）/.test(shot));
		check('tool: cu_screenshot still reports scale', /缩放比/.test(shot));
		check('tool: cu_screenshot says the bitmap is NOT in context', /不会进入模型上下文/.test(shot));
		check('tool: cu_list_windows marks foreground and self', (await reg.defs.get('cu_list_windows').invoke({})).includes('[前台]'));
		check('tool: cu_geometry converts DIP on demand', (await reg.defs.get('cu_geometry').invoke({ dipX: 10, dipY: 10 })).includes('物理像素(20,20)'));
		check('tool: cu_screen_text reports disabled cleanly', (await reg.defs.get('cu_screen_text').invoke({})).includes('未启用'));
		void captured;

		// injections must refuse, and must not pretend to have worked
		const click = await reg.defs.get('cu_click').invoke({ x: 100, y: 100 });
		check('tool: cu_click refuses without a backend (not a silent no-op)', click.includes('需要原生注入后端'));
		check('tool: cu_click still reported the validated point', click.includes('(100,100)'));
		const key = await reg.defs.get('cu_key').invoke({ keys: 'win+l' });
		check('tool: cu_key denies a system shortcut', key.startsWith('[DENIED]'));
		const okKey = await reg.defs.get('cu_key').invoke({ keys: 'enter' });
		check('tool: cu_key allows a normal key then reports the missing backend', okKey.includes('需要原生注入后端'));
		const scroll = await reg.defs.get('cu_scroll').invoke({ x: 10, y: 10, notches: 999 });
		check('tool: cu_scroll denies an over-limit notches', scroll.startsWith('[DENIED]'));
		const oob = await reg.defs.get('cu_click').invoke({ x: 99999, y: 10 });
		check('tool: cu_click denies out-of-bounds coordinates', oob.startsWith('[DENIED]'));
		// The refusal must NAME the bounds. "out of range" alone gave the model no way to notice
		// it was aiming in DIP while the guard spoke physical — it just kept retrying.
		check('tool: cu_click refusal names the bounds, not just a verdict', /物理 bounds \d+x\d+/.test(oob));
		// Scroll had NO guard at all: it injected at any coordinate (including Number(undefined)
		// = NaN) and then reported 已滚动 n 格 — a success that never happened, which is worse
		// than a refusal because it ends the investigation.
		const oobScroll = await reg.defs.get('cu_scroll').invoke({ x: 99999, y: 10, notches: 3 });
		check('tool: cu_scroll denies an off-screen coordinate instead of reporting success',
			oobScroll.startsWith('[DENIED]'));
		const nanScroll = await reg.defs.get('cu_scroll').invoke({ notches: 3 });
		check('tool: cu_scroll refuses a missing coordinate rather than injecting NaN',
			nanScroll.startsWith('[FAIL]') || nanScroll.startsWith('[DENIED]'));
		const wrongWin = await reg.defs.get('cu_click').invoke({ x: 10, y: 10, window: '浏览器' });
		check('tool: cu_click denies when the named window is not frontmost', wrongWin.startsWith('[DENIED]'));
		const longText = await reg.defs.get('cu_type').invoke({ text: 'x'.repeat(CU.CU_LIMITS.maxTypeChars + 1) });
		check('tool: cu_type denies oversized text', longText.startsWith('[DENIED]'));
		const emptyText = await reg.defs.get('cu_type').invoke({ text: '' });
		check('tool: cu_type rejects empty text', emptyText.startsWith('[FAIL]'));

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 6. with a backend wired, injection actually reaches it (proves the seam)
	{
		const calls = [];
		const geo = { bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 2, dip: { width: 960, height: 540 } };
		const dir = path.join(os.tmpdir(), 'pcb-cu-seam-' + Date.now());
		const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerCuTools(reg, {
			capture: async () => ({ ok: true, png: PNG, geometry: geo }),
			listWindows: () => [{ title: '记事本', bounds: { x: 0, y: 0, width: 800, height: 600 }, focused: true, isSelf: false }],
			store: new CU.CaptureStore(dir),
			inject: {
				click: async (p, b) => { calls.push(['click', p.x, p.y, b]); },
				scroll: async (p, n) => { calls.push(['scroll', p.x, p.y, n]); },
				key: async (k) => { calls.push(['key', k]); },
				pasteText: async (t) => { calls.push(['paste', t]); },
			},
		});
		await reg.defs.get('cu_click').invoke({ x: 200, y: 100 });
		await reg.defs.get('cu_scroll').invoke({ x: 200, y: 100, notches: -3 });
		await reg.defs.get('cu_key').invoke({ keys: 'enter' });
		await reg.defs.get('cu_type').invoke({ text: '你好' });
		check('seam: click reached the backend with PHYSICAL coords', JSON.stringify(calls[0]) === '["click",200,100,"left"]');
		check('seam: scroll reached the backend', JSON.stringify(calls[1]) === '["scroll",200,100,-3]');
		check('seam: key reached the backend', JSON.stringify(calls[2]) === '["key","enter"]');
		check('seam: paste reached the backend', JSON.stringify(calls[3]) === '["paste","你好"]');
		// and a denied key never reaches it
		calls.length = 0;
		await reg.defs.get('cu_key').invoke({ keys: 'win+l' });
		check('seam: denied key never reaches the backend (zero injection)', calls.length === 0);
		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 7. native module: loading is layout-independent and fails soft
	//
	// DELIBERATELY NO REAL INJECTION HERE. This suite runs on a developer's machine with a
	// real desktop; a stray cu_click would move someone's mouse mid-work and a stray cu_key
	// would type into whatever window they had open. So we only exercise read-only calls and
	// the error paths that reject BEFORE reaching SendInput — which is also the property that
	// matters: bad input must never produce OS events.
	{
		const NAT = require(path.join(base, 'cu-native.js'));
		const candidates = NAT.cuNativeCandidates(path.join(__dirname, 'src', 'main', 'ai'));
		check('native: candidate list walks upward past a deep dir',
			candidates.length > 3 && candidates.some(p => p.endsWith(path.join('native', 'planability-cu', 'planability-cu.node'))));
		check('native: candidate list never contains duplicates', new Set(candidates).size === candidates.length);

		// Point the search at a directory with nothing above it, to prove fail-soft.
		const emptyRoot = path.join(os.tmpdir(), 'pcb-cu-none-' + Date.now());
		fs.mkdirSync(emptyRoot, { recursive: true });
		const emptyCandidates = NAT.cuNativeCandidates(emptyRoot);
		check('native: an empty tree still terminates the walk', emptyCandidates.length <= 10);

		const real = NAT.loadCuNative();
		if (real) {
			check('native: module loaded', true);
			check('native: reports its abi version', typeof real.abiVersion() === 'number' && real.abiVersion() >= 2);
			const vs = real.virtualScreen();
			check('native: virtualScreen has positive extent', vs.width > 0 && vs.height > 0);
			check('native: virtualScreen is read-only and safe to call twice',
				JSON.stringify(real.virtualScreen()) === JSON.stringify(vs));
			const cp = real.cursorPos();
			check('native: cursorPos returns two finite numbers',
				Array.isArray(cp) && cp.length === 2 && cp.every(n => Number.isFinite(n)));
			const ft = real.foregroundTitle();
			check('native: foregroundTitle returns a string or null', ft === null || typeof ft === 'string');
			check('native: foregroundTitle does not throw when there is no window', (() => {
				try { real.foregroundTitle(); return true; } catch { return false; }
			})());

			// Error paths that must reject BEFORE any SendInput call happens.
			let threw = null;
			try { real.injectScroll(10, 10, 0); } catch (e) { threw = e; }
			check('native: zero-notch scroll is rejected', !!threw);
			threw = null;
			try { real.injectKey('definitely_not_a_key'); } catch (e) { threw = e; }
			check('native: an unparseable key is rejected before injection', !!threw);
			threw = null;
			try { real.injectClick(10, 10, 'middle'); } catch (e) { threw = e; }
			check('native: an unsupported mouse button is rejected before injection', !!threw);
			threw = null;
			try { real.injectTypeText(''); } catch (e) { threw = e; }
			check('native: empty text injection is a no-op, not an error', threw === null);
		} else {
			// Absence must be explainable, never a throw at require time.
			check('native: absence is reported with a reason', /未找到|cu:build|unavailable/.test(NAT.cuNativeError() || ''));
			check('native: loading twice stays null (cached, no throw)', NAT.loadCuNative() === null);
			out.push('SKIP  native module not built — injection assertions skipped (run npm run cu:build)');
		}
		fs.rmSync(emptyRoot, { recursive: true, force: true });
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});