/* PDF 文字层抽取（PC 独有能力，2026-11-08 用户拍板加）。
 *
 * 断言重点不是"能返回字符串"，而是三件事：
 *  ① 文字真的被抽出来（且按 [第 N 页] 分页，跟 docx 的 [Pn] 同一套编号习惯）；
 *  ② 扫描件/无文字层要说清"整份都是图片"并给替代路径，**不是**返回一堆空页或空串；
 *  ③ 坏文件报错而不是静默成功。
 *
 * 测试用的 PDF 在代码里现造（最小合法 PDF），不依赖外部样本文件。
 *
 * Run: npm run test:pdf
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-pdf.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');

/** 造一份最小合法 PDF：pages 个页面，每页 pageLines[i] 行文字。 */
function makePdf(pageLines) {
	const objs = [];
	const pageCount = pageLines.length;
	// 1=Catalog 2=Pages，之后每个页面两个对象（Page + Contents），最后是 Font
	objs.push('<< /Type /Catalog /Pages 2 0 R >>');
	const kids = [];
	const bodyObjs = [];
	for (let i = 0; i < pageCount; i++) {
		const pageObjNum = 3 + i * 2;
		const contentObjNum = pageObjNum + 1;
		kids.push(`${pageObjNum} 0 R`);
		const stream = pageLines[i].length
			? `BT /F1 18 Tf 60 760 Td ${pageLines[i].map((l, k) => `${k ? '0 -24 Td ' : ''}(${l}) Tj`).join(' ')} ET`
			: 'BT ET';
		bodyObjs.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${contentObjNum} 0 R /Resources << /Font << /F1 ${3 + pageCount * 2} 0 R >> >> >>`);
		bodyObjs.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
	}
	objs.push(`<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pageCount} >>`);
	objs.push(...bodyObjs);
	objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');

	let pdf = '%PDF-1.4\n';
	const offsets = [];
	objs.forEach((o, i) => { offsets.push(pdf.length); pdf += `${i + 1} 0 obj\n${o}\nendobj\n`; });
	const xref = pdf.length;
	pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
	offsets.forEach(o => { pdf += `${String(o).padStart(10, '0')} 00000 n \n`; });
	pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
	return Buffer.from(pdf, 'latin1');
}

(async () => {
	const { extractPdfText } = require(path.join(ai, 'pdf-text.js'));
	const { extractDocText } = require(path.join(ai, 'doc-read-tools.js'));

	// ---- ① 文字抽取 + 分页 ----
	{
		const buf = makePdf([['Quarterly Report'], ['Second page line one', 'line two']]);
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-pdf-'));
		const f = path.join(dir, 'r.pdf');
		fs.writeFileSync(f, buf);

		const text = await extractPdfText(new Uint8Array(buf));
		check('pdf: page markers are [第 N 页]', text.includes('[第 1 页]') && text.includes('[第 2 页]'));
		check('pdf: page 1 text extracted', text.includes('Quarterly Report'));
		check('pdf: page 2 text extracted', text.includes('Second page line one'));

		const viaTool = await extractDocText(new Uint8Array(buf), 'pdf');
		check('pdf: file_read_document path returns ok', viaTool.ok === true);
		check('pdf: tool output keeps page structure', viaTool.text.includes('[第 1 页]') && viaTool.text.includes('Quarterly Report'));
		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- ② 无文字层（扫描件）：说清楚 + 给替代办法 ----
	{
		const buf = makePdf([[], []]);
		const text = await extractPdfText(new Uint8Array(buf));
		check('pdf: no-text-layer says so explicitly', text.includes('未抽取到文字'));
		check('pdf: no-text-layer calls it images/scans', text.includes('图片') || text.includes('扫描'));
		check('pdf: no-text-layer offers alternatives', text.includes('替代'));
		check('pdf: no-text-layer reports page count', text.includes('共 2 页'));

		const viaTool = await extractDocText(new Uint8Array(buf), 'pdf');
		check('pdf: tool treats all-image pdf as a stated failure, not empty success', viaTool.ok === false
			&& viaTool.text.includes('替代'));
	}

	// ---- 单页有字、单页是图：仍然成功，但明确标出哪页没字 ----
	{
		const buf = makePdf([['has text'], []]);
		const text = await extractPdfText(new Uint8Array(buf));
		check('pdf: mixed doc still succeeds', text.includes('has text'));
		check('pdf: empty page is labelled, not silently blank', text.includes('无文字层'));
		const viaTool = await extractDocText(new Uint8Array(buf), 'pdf');
		check('pdf: mixed doc returns ok through the tool', viaTool.ok === true);
	}

	// ---- ③ 坏文件：报错，不静默 ----
	{
		const junk = Buffer.from('这不是 PDF，只是一段文本', 'utf8');
		let threw = false;
		let msg = '';
		try { await extractPdfText(new Uint8Array(junk)); } catch (e) { threw = true; msg = String(e && e.message); }
		check('pdf: corrupt input raises (caller turns it into a stated failure)', threw && msg.length > 0);
		const viaTool = await extractDocText(new Uint8Array(junk), 'pdf');
		check('pdf: corrupt file through the tool is a FAIL with reason', viaTool.ok === false && viaTool.text.includes('抽取失败'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
