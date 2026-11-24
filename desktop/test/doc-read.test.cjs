/* file_read_document 钉子：编号口径必须与两个编辑工具对得上。
 *
 * 这个工具唯一的存在理由就是"给改稿工具提供定位"：file_edit_document 的 index 是 [Pn]，
 * file_edit_deck 的 pageNo 是 [第 N 页]。如果读取的编号与编辑的编号错开一位，
 * 用户说"改第 3 段"就会改到第 2 段或第 4 段——而且两边都报成功。
 * 所以这里不只断言"能读出文字"，而是**读出来的编号喂给编辑工具，改的确实是那一段/那一页**。
 *
 * Run: npm run test:docread
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-docread.txt');
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
const { registerFileOfficeTools } = require(path.join(ai, 'file-office-tools.js'));
const { registerFileEditTools } = require(path.join(ai, 'file-edit-tools.js'));
const { registerFileReadDocument, extractDocText } = require(path.join(ai, 'doc-read-tools.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-dr-'));
const ws = new Workspace(dir);
const reg = new ToolRegistry();
registerFileOfficeTools(reg, () => ws);
registerFileEditTools(reg, () => ws);
registerFileReadDocument(reg, () => ws);
const call = async (name, input) => reg.get(name).invoke(input);

(async () => {
	// ---- docx：编号 + 与 file_edit_document 对得上 ----
	{
		const md = ['# 季度总结', '第一段正文', '第二段正文', '第三段正文'].join('\n');
		await call('file_export_document', { filename: 's.docx', content: md });
		const read = await call('file_read_document', { path: 'exports/s.docx' });
		check('docx: paragraphs carry [P1] numbering', read.includes('[P1]') && read.includes('[P2]'));
		check('docx: numbering starts at 1 (not 0)', !read.includes('[P0]'));
		check('docx: heading text is present', read.includes('季度总结'));

		// 取 [P3] 那一段的原文，改成新文字，回读必须只有它变
		const p3Line = read.split('\n').find(l => l.includes('[P3]')) || '';
		// 去掉编号前缀（可能带前导空白）：find 必须是段落的纯正文，否则 replace 会命中错位置
		const p3 = p3Line.slice(p3Line.indexOf('[P3]') + 5).trim();
		check('docx: P3 line exists', p3.length > 0);
		// PC 批 2 的编辑契约是 ops（find/replace 文本操作，2026-11-07 交付），不是手机端的
		// edits:[{index}]——读出来的 [Pn] 段文本正可以直接当 find 用。跨端参数形状差异已记 gap。
		const edit = await call('file_edit_document', {
			path: 'exports/s.docx',
			ops: JSON.stringify([{ op: 'replace', find: p3, with: '第三段改写' }]),
			saveAs: 's2.docx',
		});
		check('docx: text read by [Pn] can drive an edit (ops.find)', edit.includes('s2.docx'));
		const after = await call('file_read_document', { path: 'exports/s2.docx' });
		check('docx: edited file reflects the change', after.includes('第三段改写'));
		// 「没列出的段落不碰」：除了被替换的那一段（第二段），其余三段必须都还在
		const untouched = ['季度总结', '第一段正文', '第三段正文'].filter(x => !after.includes(x));
		check('docx: other paragraphs untouched (' + (untouched.join(',') || 'none missing') + ')', untouched.length === 0);
		check('docx: heading still present after round-trip', after.includes('季度总结'));

		const noHit = await call('file_edit_document', {
			path: 'exports/s.docx', ops: JSON.stringify([{ op: 'replace', find: '这段不存在', with: 'x' }]),
		});
		check('docx: unmatched find refuses instead of silently doing nothing', String(noHit).startsWith('[FAIL]'));
	}

	// ---- pptx：页号 + 与 file_edit_deck 对得上 ----
	{
		const slides = JSON.stringify([
			{ layout: 'title', title: '封面标题', subtitle: '副标题' },
			{ layout: 'bullets', title: '第二页标题', blocks: [{ type: 'bullets', items: ['要点甲', '要点乙'] }] },
			{ layout: 'bullets', title: '第三页标题', blocks: [{ type: 'bullets', items: ['要点丙'] }] },
		]);
		await call('file_build_deck', { filename: 'd.pptx', slides });
		const read = await call('file_read_document', { path: 'exports/d.pptx' });
		check('pptx: pages carry [第 N 页]', read.includes('[第 1 页]') && read.includes('[第 3 页]'));
		check('pptx: page text is extracted', read.includes('第二页标题') && read.includes('要点甲'));

		// 同上：PC 用 ops.setTitle/slide 编号（与读取的 [第 N 页] 同一 1 基口径）
		const edit = await call('file_edit_deck', {
			path: 'exports/d.pptx',
			ops: JSON.stringify([{ op: 'setTitle', slide: 2, title: '第二页改写' }]),
			saveAs: 'd2.pptx',
		});
		check('pptx: page numbering from read drives an edit (ops.slide)', edit.includes('d2.pptx'));
		const after = await call('file_read_document', { path: 'exports/d2.pptx' });
		check('pptx: edited page reflects the change', after.includes('第二页改写'));
		check('pptx: untouched pages keep their text', after.includes('封面标题') && after.includes('第三页标题'));
	}

	// ---- xlsx：工作表分块 ----
	{
		const rows = '渠道\t新增\n自然量\t12043\n投放\t3210';
		await call('file_export_document', { filename: 't.xlsx', content: rows, sheetName: '明细' });
		const read = await call('file_read_document', { path: 'exports/t.xlsx' });
		check('xlsx: sheet header is printed', read.includes('【工作表：明细】'));
		check('xlsx: rows are tab separated', read.includes('自然量\t12043'));
	}

	// ---- 分页窗口：与 file_read_text 同一个头 ----
	{
		const long = Array.from({ length: 400 }, (_, i) => `第 ${i + 1} 段内容`).join('\n');
		await call('file_export_document', { filename: 'long.docx', content: long });
		const first = await call('file_read_document', { path: 'exports/long.docx', limit: 500 });
		check('window: header states character range and total', /第 1–\d+ 字 \/ 共 \d+ 字/.test(first));
		check('window: header gives next offset', first.includes('续读 offset='));
		const offset = Number((first.match(/续读 offset=(\d+)/) || [])[1] || 0);
		check('window: next offset is a real number', offset > 0);
		const second = await call('file_read_document', { path: 'exports/long.docx', offset });
		check('window: continuation starts where the header said', second.startsWith(`第 ${offset + 1}–`));
		const past = await call('file_read_document', { path: 'exports/long.docx', offset: 999999 });
		check('window: past-EOF says so instead of empty', past.includes('已到文档末尾'));
	}

	// ---- 失败契约：永不返回"成功但空" ----
	{
		const pdf = await call('file_read_document', { path: 'exports/none.pdf' });
		check('pdf: missing file refuses', String(pdf).startsWith('[FAIL]'));
		// PDF 自 2026-11-08 起是**支持**格式（PC 抽文字层）：假 PDF 应报「抽取失败」，
		// 而不是「不支持这个格式」——后者会让模型以为换个工具就能读。
		fs.writeFileSync(path.join(dir, 'exports/x.pdf'), '%PDF-1.4 fake');
		const pdf2 = await call('file_read_document', { path: 'exports/x.pdf' });
		check('pdf: corrupt file fails with a parse reason (format IS supported now)',
			String(pdf2).startsWith('[FAIL]') && pdf2.includes('抽取失败') && !pdf2.includes('不支持的文档格式'));
		fs.writeFileSync(path.join(dir, 'exports/bad.docx'), 'not a zip');
		const bad = await call('file_read_document', { path: 'exports/bad.docx' });
		check('docx: corrupt file refuses with a reason (no empty success)', String(bad).startsWith('[FAIL]') && bad.length > 20);
		fs.writeFileSync(path.join(dir, 'exports/x.txt'), 'hello');
		const unsupported = await call('file_read_document', { path: 'exports/x.txt' });
		check('txt: not a document format, refuses by name', String(unsupported).startsWith('[FAIL]') && unsupported.includes('.txt'));
	}

	// ---- 质量闸 ----
	{
		// 40KB+ 的 pptx 但几乎没有文字 → 必须带"可能只读到一部分"的警告
		const buf = Buffer.alloc(50 * 1024);
		const r = await extractDocText(new Uint8Array(buf), 'xlsx');
		check('quality gate: unreadable big file fails loudly', r.ok === false && r.text.includes('抽取失败'));
	}

	check('domain: file_read_document is file', reg.get('file_read_document').domain === 'file');
	check('risk: reading never asks', reg.get('file_read_document').risk === 'auto');

	fs.rmSync(dir, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
