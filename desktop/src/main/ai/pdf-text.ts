/* PDF 文字层抽取（2026-11-08 用户拍板加；手机端仍不做，属 PC 独有能力）。
 *
 * 为什么单独一个文件：pdfjs 的加载形态是本机安全门禁的敏感点（它按"动态加载代码"归类），
 * 把这段放进 doc-read-tools 会让整份文件都被反复拦，定位困难。单独成文件后，
 * 门禁的反馈能精确对应到这一处，doc-read-tools 的其余逻辑不受影响。
 *
 * 依赖形态：pdfjs-dist@3.11.174 的主入口（build/pdf.js）在 Node 里可直接 require，
 * 不需要 legacy 路径。文字层抽取不需要 canvas（那些 DOMMatrix/Path2D 警告只影响渲染）。
 *
 * 失败契约与 doc-read 一致：永不返回空串。扫描件（无文字层）要说清是"图片页"而不是
 * "读不出来"，并给替代路径——这是手机端 parseDocText 的既定口径。
 */

import * as pdfjs from 'pdfjs-dist';

const pdfjsAny = pdfjs as unknown as {
	getDocument: (opts: { data: Uint8Array; useSystemFonts?: boolean; isEvalSupported?: boolean }) => {
		promise: Promise<{
			numPages: number;
			getPage: (n: number) => Promise<{ getTextContent: () => Promise<{ items: unknown[] }> }>;
		}>;
	};
};

/** 逐页抽取文字层：[第 N 页] 分段，页内按 pdfjs 的 EOL 标记换行。 */
export async function extractPdfText(buf: Uint8Array): Promise<string> {
	const doc = await pdfjsAny.getDocument({
		data: buf,
		useSystemFonts: true,
		// PDF 里的 JS 不该被执行：我们只读文字层，禁掉 eval 面（门禁也会盯这一点）。
		isEvalSupported: false,
	}).promise;
	const parts: string[] = [];
	let emptyPages = 0;
	for (let i = 1; i <= doc.numPages; i++) {
		const page = await doc.getPage(i);
		const content = await page.getTextContent();
		const lines: string[] = [];
		let line = '';
		for (const raw of content.items as { str?: string; hasEOL?: boolean }[]) {
			line += raw.str ?? '';
			if (raw.hasEOL) { lines.push(line); line = ''; }
		}
		if (line) { lines.push(line); }
		const text = lines.join('\n').trim();
		if (!text) { emptyPages++; }
		parts.push(`[第 ${i} 页]` + (text ? '\n' + text : '（无文字层：这一页多半是图片/扫描件）'));
	}
	// 全是图片页时，光给一堆"(无文字层)"对模型没有用——直接说清并给替代办法。
	if (emptyPages === doc.numPages) {
		return '[PDF 未抽取到文字：整份都是图片/扫描件，没有文字层]\n'
			+ `（共 ${doc.numPages} 页）替代：① 让用户把要用的那几页截图发来；`
			+ '② 用带 OCR 的工具先转成文本；③ 若有同内容的 docx/pptx/txt，优先读那份';
	}
	return parts.join('\n');
}
