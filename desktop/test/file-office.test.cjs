/* B2 批 1（文件/办公域）钉子：导出真的能打开，读真的分段。
 *
 * 这里的断言刻意不只看返回值字符串：docx/xlsx/pptx 都重新解析回来验证结构——
 * "导出成功" 与 "文件里真有内容" 是两件事，前者无法证明后者（三库任何一个参数写错
 * 都会生成一个能写出、打不开或空白的文件）。
 *
 * Run: npm run test:fileoffice
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-fileoffice.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { ToolRegistry } = require(path.join(ai, 'tools.js'));
const { Workspace } = require(path.join(ai, 'workspace.js'));
const fo = require(path.join(ai, 'file-office-tools.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-fo-'));
const ws = new Workspace(dir);
const reg = new ToolRegistry();
fo.registerFileOfficeTools(reg, () => ws);
const call = async (name, input) => reg.get(name).invoke(input);
const abs = rel => path.join(dir, rel);

(async () => {
	// ---- 文件名闸：分隔符与扩展名 ----
	{
		const bad = await call('file_export', { filename: '../evil.md', content: 'x' });
		check('export: path separator refused (story says "no separators", not "escape")', String(bad).startsWith('[FAIL]') && bad.includes('分隔符'));
		const badExt = await call('file_export', { filename: 'a.exe', content: 'x' });
		check('export: unsupported extension refused', String(badExt).startsWith('[FAIL]'));
	}

	// ---- file_export：纯文本 ----
	{
		const r = await call('file_export', { filename: '大纲.md', content: '# 标题\n正文' });
		check('export: writes into exports/', r.includes('exports/大纲.md'));
		check('export: file exists with the exact content', fs.readFileSync(abs('exports/大纲.md'), 'utf8') === '# 标题\n正文');
	}

	// ---- file_export_document：docx 重新解析 ----
	{
		const md = [
			'# 周报', '## 本周', '- 完成 A', '- 完成 B', '**重点**：上线',
			'| 项 | 数 |', '| --- | --- |', '| 待办 | 3 |', '---', '结尾段落',
		].join('\n');
		const r = await call('file_export_document', { filename: '周报.docx', content: md });
		check('docx: reports success with byte count', r.includes('docx') && /\d+ 字节/.test(r));
		const file = abs('exports/周报.docx');
		check('docx: file is a real zip container', fs.existsSync(file) && fs.readFileSync(file).slice(0, 2).toString() === 'PK');
		// Read the OOXML back with jszip (already a transitive dep) — no new dependency for a test,
		// and the assertions target the actual document XML rather than our own return string.
		const JSZip = require('jszip');
		const zipDoc = await JSZip.loadAsync(fs.readFileSync(file));
		const docXml = await zipDoc.file('word/document.xml').async('string');
		const text = docXml.replace(/<[^>]+>/g, ''); // 所有文本都在这一个 XML 里（w:t）
		check('docx: heading text survives', text.includes('周报') && text.includes('本周'));
		check('docx: bullets survive', text.includes('完成 A') && text.includes('完成 B'));
		check('docx: table cells survive', text.includes('待办') && text.includes('3'));
		check('docx: bold markers are gone (rendered, not literal)', !text.includes('**重点**') && text.includes('重点'));
		check('docx: trailing paragraph survives', text.includes('结尾段落'));
	}

	// ---- xlsx / csv ----
	{
		const rows = '渠道\t新增\t留存\n自然量\t12043\t45%\n投放\t3210\t31%';
		const rx = await call('file_export_document', { filename: '渠道.xlsx', content: rows, sheetName: '渠道明细' });
		check('xlsx: reports rows', rx.includes('3 行'));
		const ExcelJS = require('exceljs');
		const wb = new ExcelJS.Workbook();
		await wb.xlsx.readFile(abs('exports/渠道.xlsx'));
		const sheet = wb.getWorksheet('渠道明细');
		check('xlsx: sheet name applied', !!sheet);
		check('xlsx: header row bold', sheet.getRow(1).font && sheet.getRow(1).font.bold === true);
		check('xlsx: cell values round-trip', String(sheet.getRow(2).getCell(1).value) === '自然量' && String(sheet.getRow(2).getCell(2).value) === '12043');
		check('xlsx: column width set from content', sheet.getColumn(1).width >= 8);

		const rc = await call('file_export_document', { filename: '渠道.csv', content: rows });
		const csv = fs.readFileSync(abs('exports/渠道.csv'), 'utf8');
		check('csv: reports success', rc.includes('3 行'));
		check('csv: BOM present (Excel on zh-CN needs it)', csv.charCodeAt(0) === 0xFEFF);
		check('csv: cells separated by comma', csv.includes('自然量,12043,45%'));
		const quoted = await call('file_export_document', { filename: 'q.csv', content: 'a\t带,逗号\t带"引号"' });
		check('csv: quotes escaped', fs.readFileSync(abs('exports/q.csv'), 'utf8').includes('"带,逗号"') && fs.readFileSync(abs('exports/q.csv'), 'utf8').includes('""引号""'));
	}

	// ---- pptx ----
	{
		const slides = JSON.stringify([
			{ layout: 'title', title: '季度复盘', subtitle: '2026 Q3' },
			{ layout: 'bullets', title: '结论', blocks: [{ type: 'bullets', items: ['增长 12%', '成本降 4%'] }], notes: '口头补充风险项' },
			{ layout: 'table', title: '数据', blocks: [{ type: 'table', rows: [['项', '值'], ['DAU', '1200']] }] },
		]);
		const r = await call('file_build_deck', { filename: '复盘.pptx', slides });
		check('pptx: reports page count', r.includes('3 页'));
		const JSZip2 = require('jszip');
		const pptxZip = await JSZip2.loadAsync(fs.readFileSync(abs('exports/复盘.pptx')));
		const names = Object.keys(pptxZip.files);
		check('pptx: is an OOXML package with slides', names.filter(n => /^ppt\/slides\/slide\d+\.xml$/.test(n)).length === 3);
		check('pptx: notes slide present', names.some(n => /^ppt\/notesSlides\//.test(n)));
		const slide1 = await pptxZip.file('ppt/slides/slide1.xml').async('string');
		check('pptx: real title content, not placeholder', slide1.includes('季度复盘'));
	}

	// ---- pptx 参数闸 ----
	{
		const badJson = await call('file_build_deck', { filename: 'x.pptx', slides: '不是 JSON' });
		check('pptx: invalid JSON refused with the parse reason', String(badJson).startsWith('[FAIL]') && badJson.includes('JSON'));
		const empty = await call('file_build_deck', { filename: 'x.pptx', slides: '[]' });
		check('pptx: empty array refused', String(empty).startsWith('[FAIL]'));
		const tooMany = await call('file_build_deck', {
			filename: 'x.pptx', slides: JSON.stringify(Array.from({ length: 61 }, (_, i) => ({ title: `s${i}` }))),
		});
		check('pptx: page cap enforced (60)', String(tooMany).startsWith('[FAIL]') && tooMany.includes('60'));
		const badName = await call('file_build_deck', { filename: 'a/b.pptx', slides: '[{"title":"x"}]' });
		check('pptx: separator in filename refused', String(badName).startsWith('[FAIL]'));
	}

	// ---- file_read_text 分段 ----
	{
		const long = '甲'.repeat(12000);
		await call('file_export', { filename: 'long.md', content: long });
		const first = await call('file_read_text', { path: 'exports/long.md' });
		check('read_text: header states character range and total', first.includes('第 0–8000 字 / 共 12000 字'));
		check('read_text: header gives the next offset', first.includes('续读 offset=8000'));
		check('read_text: body is exactly the requested slice', first.split('\n\n')[1].length === 8000);
		const second = await call('file_read_text', { path: 'exports/long.md', offset: 8000 });
		check('read_text: continuation reads the tail', second.includes('第 8000–12000 字') && second.includes('已读完'));
		check('read_text: continuation body is 4000 chars', second.split('\n\n')[1].length === 4000);
		const past = await call('file_read_text', { path: 'exports/long.md', offset: 99999 });
		check('read_text: offset past EOF says so instead of empty', past.includes('已到文件末尾'));
		const capped = await call('file_read_text', { path: 'exports/long.md', limit: 999999 });
		check('read_text: limit clamps to 200000', capped.includes('第 0–12000 字'));
	}

	// ---- file_read_image：元数据 + 明确不给 OCR ----
	{
		// 1x1 PNG（最小合法）
		const png = Buffer.from(
			'89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6300010000050001' +
			'0d0a2db40000000049454e44ae426082', 'hex');
		fs.mkdirSync(abs('exports'), { recursive: true });
		fs.writeFileSync(abs('exports/p.png'), png);
		const r = await call('file_read_image', { path: 'exports/p.png' });
		check('read_image: identifies PNG and dimensions', r.includes('PNG') && r.includes('1×1'));
		check('read_image: states PC has no OCR instead of faking text', r.includes('没有 OCR'));
		const notImg = await call('file_read_image', { path: 'exports/大纲.md' });
		check('read_image: non-image refused', String(notImg).startsWith('[FAIL]'));
		const missing = await call('file_read_image', { path: 'exports/nope.png' });
		check('read_image: missing file reports it', String(missing).startsWith('[FAIL]'));
	}

	// ---- 域归属 ----
	{
		check('domain: file_export is office', reg.get('file_export').domain === 'office');
		check('domain: file_build_deck is office', reg.get('file_build_deck').domain === 'office');
		check('domain: file_read_text is file', reg.get('file_read_text').domain === 'file');
		check('domain: file_read_image is file', reg.get('file_read_image').domain === 'file');
		check('risk: exports ask before writing', reg.get('file_export').risk === 'confirm'
			&& reg.get('file_export_document').risk === 'confirm' && reg.get('file_build_deck').risk === 'confirm');
	}

	fs.rmSync(dir, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
