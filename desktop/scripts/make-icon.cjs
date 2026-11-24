/* Regenerate desktop/resources/icon.png + icon.ico from scripts/icon-source.html.
 *
 * Run: npm run icon      (needs electron, i.e. a devDependency we already have)
 *
 * WHY A RENDERER INSTEAD OF A PNG: the app's mark lives in the Android repo as a vector
 * (app/src/main/res/drawable/ic_launcher_{fg,bg_day}.xml). Electron's nativeImage cannot decode
 * SVG or WebP — but a BrowserWindow can, so the icon is rasterized by Chromium and captured.
 * Keeping the source as HTML/SVG here means the PC icon can be re-derived when the phone's mark
 * changes, instead of being an opaque binary nobody knows how to rebuild.
 *
 * Usage: electron scripts/make-icon.cjs <source.html> <out.png> <out.ico>
 */
const fs = require('node:fs');
const { app, BrowserWindow, nativeImage } = require('electron');
const { pathToFileURL } = require('node:url');

const [INP, OUT_PNG, OUT_ICO] = process.argv.slice(2);
const SIZE = 256;

if (!INP || !OUT_PNG || !OUT_ICO) {
	console.error('用法: electron scripts/make-icon.cjs <source.html> <out.png> <out.ico>');
	app.exit(2);
}

app.whenReady().then(async () => {
	let win;
	try {
		win = new BrowserWindow({
			width: SIZE, height: SIZE, show: false, frame: false, transparent: true,
			useContentSize: true,
		});
		await win.loadURL(pathToFileURL(INP).href);
		// One settled frame: capturing before paint yields an empty bitmap.
		await new Promise(r => setTimeout(r, 250));
		const img = await win.webContents.capturePage({ x: 0, y: 0, width: SIZE, height: SIZE });
		const png = img.toPNG();
		if (!png.length) { throw new Error('capturePage 返回空'); }
		fs.writeFileSync(OUT_PNG, png);

		const master = nativeImage.createFromBuffer(png);
		if (master.isEmpty()) { throw new Error('PNG 读不回'); }
		const size = master.getSize();
		console.log('[icon] PNG -> ' + OUT_PNG + ' (' + png.length + ' B, ' + size.width + 'x' + size.height + ')');

		// Multi-size ICO: each entry embeds a PNG (allowed since Vista). Explorer wants 16 for the
		// list view and 256 for the tile; shipping one size makes one of them mush.
		const sizes = [16, 32, 48, 64, 128, 256];
		const pngs = sizes.map(s => master.resize({ width: s, height: s, quality: 'best' }).toPNG());
		const header = Buffer.alloc(6);
		header.writeUInt16LE(1, 2);              // type 1 = icon
		header.writeUInt16LE(sizes.length, 4);
		const entries = [];
		let offset = 6 + 16 * sizes.length;
		for (let i = 0; i < sizes.length; i++) {
			const s = sizes[i];
			const e = Buffer.alloc(16);
			e.writeUInt8(s >= 256 ? 0 : s, 0); // 0 means 256
			e.writeUInt8(s >= 256 ? 0 : s, 1);
			e.writeUInt16LE(1, 4);             // planes
			e.writeUInt16LE(32, 6);            // bpp
			e.writeUInt32LE(pngs[i].length, 8);
			e.writeUInt32LE(offset, 12);
			offset += pngs[i].length;
			entries.push(e);
		}
		fs.writeFileSync(OUT_ICO, Buffer.concat([header, ...entries, ...pngs]));

		// Read it back and parse the header — writing a file called .ico is not evidence that it
		// is one, and electron-builder only fails much later if it is not.
		const back = fs.readFileSync(OUT_ICO);
		const n = back.readUInt16LE(4);
		const firstOff = back.readUInt32LE(6 + 12);
		const isPng = back.slice(firstOff, firstOff + 4).toString('hex') === '89504e47';
		console.log('[icon] ICO -> ' + OUT_ICO + ' (' + fs.statSync(OUT_ICO).size + ' B, ' + sizes.join('/') + ')');
		console.log('[icon] 自检 entries=' + n + ' firstPng=' + isPng);
		if (n !== sizes.length || !isPng) { throw new Error('ICO 自检失败'); }
		app.exit(0);
	} catch (e) {
		console.error('[icon] 失败: ' + (e && e.stack || e));
		app.exit(1);
	} finally {
		if (win) { win.destroy(); }
	}
});
