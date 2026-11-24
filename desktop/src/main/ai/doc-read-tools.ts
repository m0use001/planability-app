/* file_read_document（B2 收尾，2026-11-08）。
 *
 * 解析 docx/xlsx/pptx/pdf → 带编号的文本：
 * - docx/pptx 是 OOXML zip 容器，jszip 已在依赖里（批 1 导出用的就是它）；
 * - xlsx 用 exceljs（批 1 同源），读出来按工作表分块、单元格制表符分隔；
 * - **PDF 抽文字层**（2026-11-08 用户拍板加的 PC 独有能力；手机端 PdfRenderer 无文字层，
 *   仍返回「[PDF 未抽取文字] + 替代办法」）。实现在 pdf-text.ts——单独成文件是因为
 *   pdfjs 的加载形态是本机安全门禁的敏感点，放一起会让整份文件反复被拦、定位困难。
 *
 * 编号是契约的一部分：docx 每段带 [P1]、pptx/pdf 每页带 [第 N 页]，file_edit_document /
 * file_edit_deck 按这套编号定位（它们按「行/页」重渲染，编号必须能对上）。xlsx 按工作表分块。
 *
 * 失败契约同手机 parseDocText：**永不返回空串**；文件不小而字很少时加质量闸提示。
 */

import * as fsp from 'node:fs/promises';
import * as path from 'node:path';
import ExcelJS from 'exceljs';
import JSZip from 'jszip';
import { extractPdfText } from './pdf-text';
import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';
import type { WorkspaceFactory } from './file-tools';
import { FILE_LIMITS } from './file-office-tools';

const xmlUnescape = (s: string): string => String(s)
	.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
	.replace(/&apos;/g, "'").replace(/&amp;/g, '&');

/** docx：按段落编号（1 基，与 file_edit_document 的 index 同一口径）。 */
export async function readDocx(buf: Uint8Array): Promise<string> {
	const zip = await JSZip.loadAsync(buf);
	const xml = await zip.file('word/document.xml')?.async('string');
	if (xml === null || xml === undefined) { return ''; }
	const paras = (xml.match(/<w:p\b[^>]*>[\s\S]*?<\/w:p>/g) ?? [])
		.map(m => xmlUnescape([...(m.match(/<w:t[^>]*>[^<]*<\/w:t>/g) ?? [])]
			.map(t => t.replace(/<[^>]+>/g, '')).join('')).trim())
		.filter(t => t !== '');
	return paras.map((t, i) => `[P${i + 1}] ${t}`).join('\n');
}

/** pptx：按页编号（1 基，与 file_edit_deck 的 pageNo 同一口径）。 */
export async function readPptx(buf: Uint8Array): Promise<string> {
	const zip = await JSZip.loadAsync(buf);
	// slide10 必须排在 slide2 之后：按文件名排序会把 10 放到 2 前面，页号就全错。
	const pages = Object.keys(zip.files)
		.filter(n => /^ppt[/]slides[/]slide\d+\.xml$/.test(n))
		.sort((a, b) => Number((a.match(/\d+/) ?? ['0'])[0]) - Number((b.match(/\d+/) ?? ['0'])[0]));
	const out: string[] = [];
	for (let i = 0; i < pages.length; i++) {
		const xml = (await zip.file(pages[i])?.async('string')) ?? '';
		const texts = (xml.match(/<a:t>[^<]*<\/a:t>/g) ?? [])
			.map(t => xmlUnescape(t.replace(/<[^>]+>/g, '')))
			.filter(t => t.trim() !== '');
		out.push(`[第 ${i + 1} 页]` + (texts.length ? '\n' + texts.join('\n') : '（无文字）'));
	}
	return out.join('\n');
}

/** xlsx：按工作表分块，行内单元格制表符分隔（与 file_export_document 的输入格式互逆）。 */
export async function readXlsx(buf: Uint8Array): Promise<string> {
	const wb = new ExcelJS.Workbook();
	await wb.xlsx.load(buf as unknown as ArrayBuffer);
	const out: string[] = [];
	wb.eachSheet(sheet => {
		out.push(`【工作表：${sheet.name}】`);
		sheet.eachRow(row => {
			const cells: string[] = [];
			row.eachCell({ includeEmpty: true }, cell => {
				const v = cell.value as unknown;
				if (v === null || v === undefined) { cells.push(''); return; }
				if (typeof v === 'object') {
					const o = v as { text?: unknown; result?: unknown; richText?: { text: string }[] };
					cells.push(String(o.text ?? o.result ?? (o.richText ?? []).map(r => r.text).join('') ?? ''));
					return;
				}
				cells.push(String(v));
			});
			out.push(cells.join('\t'));
		});
	});
	return out.join('\n');
}

export type DocExtract =
	| { ok: true; text: string; format: string }
	| { ok: false; text: string; reason: string };

/** 文档 → 带编号的文本。永不返回「成功但空」：空结果一律转成失败 + 原因。 */
export async function extractDocText(buf: Uint8Array, ext: string): Promise<DocExtract> {
	const lower = String(ext ?? '').toLowerCase();
	const fail = (text: string, reason: string): DocExtract => ({ ok: false, text, reason });
	if (lower === 'pdf') {
		// 2026-11-08 用户拍板：PC 端抽 PDF 文字层（手机端 PdfRenderer 无文字层，仍不做）。
		// 抽不到（扫描件）时 extractPdfText 会明说"整份都是图片"并给替代路径，不是空串。
		try {
			const text = await extractPdfText(buf);
			if (!text.trim()) { return fail('[PDF 未抽取到文字：文件里没有可读的文字层]', 'PDF 无文字层'); }
			if (/未抽取到文字/.test(text)) { return fail(text, 'PDF 整份无文字层'); }
			return { ok: true, text, format: 'pdf' };
		} catch (e) {
			return fail(`[抽取失败：${(e as Error)?.message ?? '未知原因'}]`, 'PDF 解析异常');
		}
	}
	let text = '';
	try {
		if (lower === 'docx') { text = await readDocx(buf); }
		else if (lower === 'pptx') { text = await readPptx(buf); }
		else if (lower === 'xlsx') { text = await readXlsx(buf); }
		else {
			return fail(`[抽取失败：不支持的文档格式 .${lower}（支持 docx/xlsx/pptx）]`, `不支持的格式 .${lower}`);
		}
	} catch (e) {
		return fail(`[抽取失败：${(e as Error)?.message ?? '未知原因'}]`, '解析异常');
	}
	if (!text.trim()) {
		return fail(
			`[抽取失败：没能从 .${lower} 里读到内容（文件 ${Math.round(buf.byteLength / 1024)} KB）——可能格式不受支持或文件损坏]`,
			'解析结果为空',
		);
	}
	// 抽取质量闸（phone parseDocText 同款）：文件不小、字却屈指可数 → 明确说「可能只读到一部分」。
	const visible = text.replace(/[\s[\]【】（）()：:0-9]/g, '').length;
	const kb = Math.round(buf.byteLength / 1024);
	if (kb >= 40 && visible < 80) {
		return {
			ok: true,
			format: lower,
			text: `[抽取质量异常：文件 ${kb} KB，只读到 ${visible} 个字——可能大部分是图片/公式图，或格式解析不全；涉及具体内容时请先向用户确认]\n${text}`,
		};
	}
	return { ok: true, text, format: lower };
}

export function registerFileReadDocument(reg: ToolRegistry, getWorkspace: WorkspaceFactory): void {
	reg.setDomain('file');
	reg.register({
		name: 'file_read_document',
		risk: 'auto',
		description: '解析工作区文档为文本（docx/xlsx/pptx）。docx 每段带 [P1] 编号、pptx 每页带 [第 N 页]，'
			+ 'file_edit_document / file_edit_deck 就按这套编号定位；支持分段读。PDF 两端都不抽文字层，读取会给出替代办法',
		inputSchema: {
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的文档路径（file_list 返回的）' },
				offset: { type: 'number', description: '从第几个字符开始读（0 开始），长文档按返回头的 offset 续读' },
				limit: { type: 'number', description: `本次最多读多少字符（默认 ${FILE_LIMITS.defaultReadChars}）` },
			},
			required: ['path'],
		},
		invoke: async (input: { path?: string; offset?: number; limit?: number }, ctx?: ToolContext) => {
			const w = await getWorkspace(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const rel = String(input?.path ?? '');
			const c = await w.confine(rel);
			if (!c.ok) { return `[FAIL] ${c.reason}`; }
			let buf: Uint8Array;
			try {
				buf = new Uint8Array(await fsp.readFile(c.abs));
			} catch {
				return `[FAIL] 文件不存在或无法访问：${rel}`;
			}
			const ext = path.extname(c.abs).toLowerCase().slice(1);
			const r = await extractDocText(buf, ext);
			if (!r.ok) { return `[FAIL] ${r.text}`; }
			const total = r.text.length;
			const offset = Math.max(0, Math.floor(Number(input?.offset ?? 0)) || 0);
			if (offset >= total) { return `（offset ${offset} 已到文档末尾，共 ${total} 字）`; }
			const limit = Math.max(1, Math.min(FILE_LIMITS.maxReadChars,
				Math.floor(Number(input?.limit ?? FILE_LIMITS.defaultReadChars)) || FILE_LIMITS.defaultReadChars));
			const end = Math.min(total, offset + limit);
			// 头格式与 file_read_text 完全一致：模型只认这一种续读指令，两种写法会让它算错位置。
			const head = `第 ${offset + 1}–${end} 字 / 共 ${total} 字`
				+ (end < total ? `（未完，续读 offset=${end}）` : '（已读完）');
			return `${head}\n\n${r.text.slice(offset, end)}`;
		},
	});
}
