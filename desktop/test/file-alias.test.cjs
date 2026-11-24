/* 别名入口（手机端参数形状 → PC 管线）的钉子。
 *
 * 2026-11-08 用户拍板选项①：PC 增生手机端的参数形状，让跨端提示词只写一套也能跑。
 * 关键不是"参数收下了"，而是**别名走的是同一条管线、结果与 ops 完全一致**——
 * 如果别名有第二套实现，两边就会慢慢分家（这正是 plugin_toggle / library_search
 * 当初被判"一个能力两个名字"时要避免的那种分家）。
 *
 * Run: npm run test:alias
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-alias.txt');
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
const { registerFileEditTools, parsePhoneDocxEdits, parsePhoneDeckSlides } = require(path.join(ai, 'file-edit-tools.js'));
const { registerFileReadDocument } = require(path.join(ai, 'doc-read-tools.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-alias-'));
const ws = new Workspace(dir);
const reg = new ToolRegistry();
registerFileOfficeTools(reg, () => ws);
registerFileEditTools(reg, () => ws);
registerFileReadDocument(reg, () => ws);
const call = (n, i) => reg.get(n).invoke(i);

(async () => {
	// ---- 归一函数的形状 ----
	{
		const a = parsePhoneDocxEdits(JSON.stringify([{ index: 3, text: '新文字' }]));
		check('docx alias: index+text becomes replaceParagraph', a.ops && a.ops[0].op === 'replaceParagraph' && a.ops[0].index === 3);
		check('docx alias: index 0 refused', !!parsePhoneDocxEdits(JSON.stringify([{ index: 0, text: 'x' }])).error);
		check('docx alias: non-JSON refused', !!parsePhoneDocxEdits('不是 JSON').error);
		check('docx alias: absent -> no ops (falls through to ops)', parsePhoneDocxEdits(undefined).ops === undefined);

		const d = parsePhoneDeckSlides(JSON.stringify([{ pageNo: 2, title: '标题' }]));
		check('deck alias: title-only becomes setTitle (does not wipe body)', d.ops && d.ops[0].op === 'setTitle');
		const d2 = parsePhoneDeckSlides(JSON.stringify([{ pageNo: 2, title: 't', bullets: ['a', 'b'] }]));
		check('deck alias: bullets become replaceSlide with bullet block', d2.ops && d2.ops[0].op === 'replaceSlide'
			&& d2.ops[0].slideData.blocks[0].type === 'bullets' && d2.ops[0].slideData.blocks[0].items.length === 2);
		check('deck alias: pageNo 0 refused', !!parsePhoneDeckSlides(JSON.stringify([{ pageNo: 0, title: 'x' }])).error);
		check('deck alias: empty item refused', !!parsePhoneDeckSlides(JSON.stringify([{ pageNo: 1 }])).error);
	}

	// ---- docx：手机形状真的改对那一段 ----
	{
		await call('file_export_document', { filename: 'a.docx', content: ['# 标题', '第一段', '第二段', '第三段'].join('\n') });
		const read = await call('file_read_document', { path: 'exports/a.docx' });
		check('docx: read gives [P2]', read.includes('[P2] 第一段'));

		// 手机端写法：按 [Pn] 定点改第 2 段
		const r1 = await call('file_edit_document', {
			path: 'exports/a.docx', edits: JSON.stringify([{ index: 2, text: '第一段（已改）' }]), saveAs: 'phone.docx',
		});
		check('docx alias: phone-shaped edits accepted', r1.includes('phone.docx'));
		const after = await call('file_read_document', { path: 'exports/phone.docx' });
		check('docx alias: the SECOND paragraph changed', after.includes('[P2] 第一段（已改）'));
		check('docx alias: other paragraphs untouched', after.includes('[P3] 第二段') && after.includes('[P4] 第三段'));

		// 清空该段（手机端 text="" 的语义）
		const r2 = await call('file_edit_document', {
			path: 'exports/a.docx', edits: JSON.stringify([{ index: 4, text: '' }]), saveAs: 'cleared.docx',
		});
		check('docx alias: empty text clears the paragraph', r2.includes('cleared.docx'));
		const cleared = await call('file_read_document', { path: 'exports/cleared.docx' });
		check('docx alias: cleared paragraph is gone', !cleared.includes('第三段'));

		// 两个形状同时给 → 明确拒绝（不猜哪个优先）
		const both = await call('file_edit_document', {
			path: 'exports/a.docx', ops: JSON.stringify([{ op: 'append', text: 'x' }]),
			edits: JSON.stringify([{ index: 1, text: 'y' }]),
		});
		check('docx: giving both ops and edits is refused', String(both).startsWith('[FAIL]') && both.includes('只能给一个'));

		// 越界段号
		const oob = await call('file_edit_document', {
			path: 'exports/a.docx', edits: JSON.stringify([{ index: 99, text: 'x' }]),
		});
		check('docx alias: out-of-range index refuses (no silent append)', String(oob).startsWith('[FAIL]') && oob.includes('没有第 99 段'));
	}

	// ---- deck：手机形状真的改对那一页 ----
	{
		await call('file_build_deck', {
			filename: 'b.pptx',
			slides: JSON.stringify([
				{ layout: 'title', title: '封面', subtitle: '副' },
				{ layout: 'bullets', title: '第二页', blocks: [{ type: 'bullets', items: ['原要点'] }] },
				{ layout: 'bullets', title: '第三页', blocks: [{ type: 'bullets', items: ['第三页要点'] }] },
			]),
		});
		const read = await call('file_read_document', { path: 'exports/b.pptx' });
		check('deck: read gives [第 2 页]', read.includes('[第 2 页]'));

		const r = await call('file_edit_deck', {
			path: 'exports/b.pptx', slides: JSON.stringify([{ pageNo: 2, title: '第二页改', bullets: ['新要点'] }]),
			saveAs: 'phone.pptx',
		});
		check('deck alias: phone-shaped slides accepted', r.includes('phone.pptx'));
		const after = await call('file_read_document', { path: 'exports/phone.pptx' });
		check('deck alias: page 2 replaced', after.includes('第二页改') && after.includes('新要点'));
		check('deck alias: page 3 untouched', after.includes('第三页') && after.includes('第三页要点'));

		const both = await call('file_edit_deck', {
			path: 'exports/b.pptx', ops: JSON.stringify([{ op: 'setTitle', slide: 1, title: 'x' }]),
			slides: JSON.stringify([{ pageNo: 1, title: 'y' }]),
		});
		check('deck: giving both ops and slides is refused', String(both).startsWith('[FAIL]'));
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
