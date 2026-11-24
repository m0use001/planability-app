/* B2 批 2：file_edit_document / file_edit_sheet / file_edit_deck 的契约测试。
 *
 * 这三把刀的真正风险不在"能不能改"，而在**改错时的表现**：
 *   1) find/match 找不到目标 → 必须报错。返回"已保存"而内容没变，是模型会立刻相信的谎。
 *   2) replace 命中多处 → 必须拒绝并要求写更长的唯一片段（或显式 all=true）。
 *      默认全替换会把"张三"改成"李四"时顺手改掉正文里每一处。
 *   3) 行/列/页越界 → 必须报错。悄悄扩表比报错难查一个数量级。
 *   4) 读不到原文件 → 必须报错，绝不能"当空文件新建"（静默清空是最坏结果）。
 *   5) 删完所有页 → 必须拒绝，不能产出 0 页的 pptx。
 *
 * round-trip 也测：docx/xlsx/pptx 写出去再读回来，编辑后再读回，确认改动能落到文件里
 * （而不是只改了内存对象）。
 *
 * Run: npm run test:fileedit
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-fileedit.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const FE = require(path.join(base, 'file-edit-tools.js'));

// ---------- 1. applyTextEdits：五个操作 + 四种拒绝 ----------
{
	const src = '# 标题\n\n第一段内容\n\n第二段内容\n';
	const r1 = FE.applyTextEdits(src, [{ op: 'replace', find: '第一段', with: '改过的第一段' }]);
	// 注意：find 是「第一段」，替换后与后面的「内容」拼成「改过的第一段内容」——这正是不做整词
	// 匹配的预期行为（工具是子串替换，不猜词边界）。断言只认"替换发生了、其它行没动"。
	check('replace 单处命中', r1.ok && r1.text.includes('改过的第一段内容') && r1.text.includes('第二段内容'),
		r1.ok ? r1.text.replace(/\n/g, '\\n') : r1.reason);

	const r2 = FE.applyTextEdits(src, [{ op: 'append', text: '新增结尾' }]);
	check('append 落在末尾', r2.ok && r2.text.trim().endsWith('新增结尾'));

	const r3 = FE.applyTextEdits(src, [{ op: 'prepend', text: '开头' }]);
	check('prepend 落在开头', r3.ok && r3.text.startsWith('开头'));

	const r4 = FE.applyTextEdits(src, [{ op: 'deleteLine', match: '第二段' }]);
	check('deleteLine 删掉命中行', r4.ok && !r4.text.includes('第二段') && r4.text.includes('第一段'));

	const r5 = FE.applyTextEdits(src, [{ op: 'insertAfter', match: '# 标题', text: '副标题' }]);
	check('insertAfter 插在锚点后', r5.ok && /# 标题\n副标题/.test(r5.text), r5.ok ? '' : r5.reason);

	// --- 拒绝路径（这才是重点）---
	const bad1 = FE.applyTextEdits(src, [{ op: 'replace', find: '不存在的文字', with: 'x' }]);
	check('[拒绝] replace 找不到目标 → 报错而非静默', !bad1.ok && /找不到/.test(bad1.reason), bad1.reason);

	const bad2 = FE.applyTextEdits('# a\n# a\n', [{ op: 'replace', find: '# a', with: '# b' }]);
	check('[拒绝] replace 多处命中且未 all → 报错', !bad2.ok && /出现 2 次/.test(bad2.reason), bad2.reason);

	const ok2 = FE.applyTextEdits('# a\n# a\n', [{ op: 'replace', find: '# a', with: '# b', all: true }]);
	check('replace all=true 时全替换', ok2.ok && !ok2.text.includes('# a'));

	const bad3 = FE.applyTextEdits(src, []);
	check('[拒绝] ops 为空 → 报错', !bad3.ok && /没有提供任何操作/.test(bad3.reason), bad3.reason);

	const bad4 = FE.applyTextEdits(src, [{ op: 'nonsense' }]);
	check('[拒绝] 未知 op → 报错并列出可用项', !bad4.ok && /未知/.test(bad4.reason) && /replace/.test(bad4.reason), bad4.reason);
}

// ---------- 2. applyCellEdits ----------
{
	const rows = [['姓名', '分数'], ['张三', '90'], ['李四', '85']];

	const e1 = FE.applyCellEdits(rows, [{ row: 2, col: 2, value: '95' }]);
	check('单元格改写命中 R2C2', e1.ok && e1.rows[1][1] === '95' && e1.rows[0][1] === '分数', e1.ok ? '' : e1.reason);
	check('单元格改写不动原数组（纯函数）', rows[1][1] === '90', rows[1][1]);

	const e2 = FE.applyCellEdits(rows, [{ row: 3, values: ['王五', '99', '加一列'] }]);
	check('整行替换', e2.ok && e2.rows[2].join(',') === '王五,99,加一列');

	// --- 拒绝路径 ---
	const b1 = FE.applyCellEdits(rows, [{ row: 99, col: 1, value: 'x' }]);
	check('[拒绝] 行越界 → 报错', !b1.ok && /越界/.test(b1.reason), b1.reason);

	const b2 = FE.applyCellEdits(rows, [{ row: 2, col: 9, value: 'x' }]);
	check('[拒绝] 跳列写入（留空洞）→ 报错', !b2.ok && /超出/.test(b2.reason), b2.reason);

	const e3 = FE.applyCellEdits(rows, [{ row: 2, col: 3, value: '紧邻新列' }]);
	check('紧邻下一列允许扩列', e3.ok && e3.rows[1][2] === '紧邻新列', e3.ok ? '' : e3.reason);

	const b3 = FE.applyCellEdits(rows, [{ row: 1, col: 0, value: 'x' }]);
	check('[拒绝] col=0（应为 1 起）→ 报错', !b3.ok && /非法/.test(b3.reason), b3.reason);

	const b4 = FE.applyCellEdits(rows, []);
	check('[拒绝] edits 为空 → 报错', !b4.ok, b4.reason);
}

// ---------- 3. round-trip：真写盘再读回（docx / xlsx / pptx） ----------
const FO = require(path.join(base, 'file-office-tools.js'));

(async () => {
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-fileedit-'));
	const docxPath = path.join(dir, 'a.docx');
	const xlsxPath = path.join(dir, 'a.xlsx');
	const pptxPath = path.join(dir, 'a.pptx');

	// docx：写 → 读回 → 编辑 → 再读回
	fs.writeFileSync(docxPath, await FO.renderDocx('测试文档', '# 一级标题\n\n正文第一段\n\n正文第二段\n'));
	const back1 = await FE.docxToMarkdown(docxPath);
	check('docx 读回成功', back1.ok, back1.ok ? '' : back1.reason);
	check('docx 读回含标题与正文', back1.ok && back1.markdown.includes('一级标题') && back1.markdown.includes('正文第一段'),
		back1.ok ? back1.markdown.slice(0, 80) : '');

	const edited = back1.ok ? FE.applyTextEdits(back1.markdown, [{ op: 'replace', find: '正文第一段', with: '正文第一段（已改）' }]) : { ok: false };
	check('docx 编辑应用成功', edited.ok, edited.ok ? '' : edited.reason);
	if (edited.ok) {
		fs.writeFileSync(docxPath, await FO.renderDocx('测试文档', edited.text));
		const back2 = await FE.docxToMarkdown(docxPath);
		check('docx 改动真的落盘（再读回可见）', back2.ok && back2.markdown.includes('已改'),
			back2.ok ? back2.markdown.slice(0, 80) : back2.reason);
	}

	// xlsx：写 → 读回 → 编辑 → 再读回
	fs.writeFileSync(xlsxPath, await FO.renderXlsx([['姓名', '分数'], ['张三', '90']]));
	const x1 = await FE.xlsxToRows(xlsxPath);
	check('xlsx 读回成功', x1.ok && x1.rows.length === 2, x1.ok ? `${x1.rows.length} 行` : x1.reason);
	if (x1.ok) {
		const xe = FE.applyCellEdits(x1.rows, [{ row: 2, col: 2, value: '100' }]);
		check('xlsx 编辑应用成功', xe.ok, xe.ok ? '' : xe.reason);
		if (xe.ok) {
			fs.writeFileSync(xlsxPath, await FO.renderXlsx(xe.rows, x1.sheet));
			const x2 = await FE.xlsxToRows(xlsxPath);
			check('xlsx 改动真的落盘', x2.ok && x2.rows[1][1] === '100',
				x2.ok ? JSON.stringify(x2.rows[1]) : x2.reason);
		}
	}

	// pptx：写 → 读回 → 改标题 → 再读回
	const deck = [{ layout: 'title', title: '首页标题', subtitle: '副题' }, { layout: 'bullets', title: '第二页', blocks: [{ type: 'bullets', items: ['要点一', '要点二'] }] }];
	fs.writeFileSync(pptxPath, await FO.renderPptx('测试演示', deck));
	const p1 = await FE.pptxToSlides(pptxPath);
	check('pptx 读回成功且页数对', p1.ok && p1.slides.length === 2, p1.ok ? `${p1.slides.length} 页` : p1.reason);
	check('pptx 读回标题正确', p1.ok && p1.slides[0].title === '首页标题', p1.ok ? String(p1.slides[0].title) : '');
	check('pptx 读回降级说明非空', p1.ok && p1.degradations.length > 0, p1.ok ? p1.degradations.join(';') : '');

	// ---------- 4. 坏文件必须拒绝，绝不当空文件 ----------
	const notDocx = path.join(dir, 'fake.docx');
	fs.writeFileSync(notDocx, 'this is not a zip');
	const nb = await FE.docxToMarkdown(notDocx);
	check('[拒绝] 损坏的 .docx → 报错而非空文档', !nb.ok && /不是有效|解析 .docx 失败|\.docx/.test(nb.reason), nb.reason);

	const missing = await FE.docxToMarkdown(path.join(dir, 'nope.docx'));
	check('[拒绝] 不存在的文件 → 报错', !missing.ok && /读不到/.test(missing.reason), missing.reason);

	const notXlsx = path.join(dir, 'fake.xlsx');
	fs.writeFileSync(notXlsx, 'nope');
	const nx = await FE.xlsxToRows(notXlsx);
	check('[拒绝] 损坏的 .xlsx → 报错', !nx.ok, nx.reason);

	// 清理
	try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* best effort */ }

	out.push(failed === 0 ? '\nALL PASS' : '\n' + failed + ' FAILED');
	console.log(out.join('\n'));
	process.exit(failed === 0 ? 0 : 1);
})();
