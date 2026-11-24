/* 文件/办公域批 1（phone ToolDefs 的 file_export / file_export_document / file_build_deck /
 * file_read_text / file_read_image 对齐，2026-11-05）。
 *
 * WHY THESE LIVE IN THE MAIN PROCESS RATHER THAN GOING THROUGH office-bridge:
 * the bridge (desktop/scripts/office-bridge.mjs) is a separate Node process that renders to a
 * LOCAL HTTP endpoint for the PHONE. On PC the model runs in the main process already, and the
 * three rendering libraries (docx / exceljs / pptxgenjs) are plain dependencies — spawning a
 * bridge process and POSTing to a port would add a failure mode (port taken, process dead, no
 * auth) to earn nothing. The bridge's DocModel shape is still the contract we render to, so a
 * deck from either end looks the same.
 *
 * DEFAULTS THAT ARE DELIBERATE:
 * - Everything writes under `exports/` inside the workspace and goes through Workspace.confine.
 *   The model never gets to name an absolute path; a filename with a separator is refused
 *   (that is a different story from "you asked to escape" — see libraryRel's reasoning).
 * - `file_read_text` is CHARACTER-indexed, not byte-indexed, and its header always states
 *   "第 X–Y 字 / 共 N 字" so a long document is read in pieces instead of being flooded or cut
 *   silently. Bytes would be the wrong unit: one CJK char is 3 bytes and the model counts chars.
 * - `file_read_image` returns METADATA ONLY. PC has no OCR (phone uses ML Kit); pretending to
 *   read text out of an image would be the worst possible answer.
 */

import * as fsp from 'node:fs/promises';
import * as path from 'node:path';
import { Document as DocxDocument, Packer, Paragraph, TextRun, HeadingLevel, Table, TableRow, TableCell } from 'docx';
import ExcelJS from 'exceljs';
import PptxGenJS from 'pptxgenjs';
import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';
import type { WorkspaceFactory } from './file-tools';

export const FILE_LIMITS = {
	/** One export of a 20 MB "document" is not an export. */
	maxExportChars: 2_000_000,
	/** 8000 chars is roughly a printed page and a half — the phone default. */
	defaultReadChars: 8000,
	maxReadChars: 200_000,
	maxSlides: 60,
} as const;

/** Exports land here (workspace-relative), same as the phone's `exports` directory. */
export const EXPORT_DIR = 'exports';

const TEXT_EXT = new Set([
	'.md', '.markdown', '.txt', '.htm', '.html', '.json', '.xml', '.yaml', '.yml',
	'.csv', '.tsv', '.ics', '.svg', '.css', '.js', '.ts', '.sql', '.log', '.ini', '.toml',
]);

export type ExportRel = { ok: true; rel: string; ext: string; reason?: undefined }
	| { ok: false; rel?: undefined; ext?: undefined; reason: string };

/** Filename validation: no separators, no traversal, and a real extension. Reported as a
 *  user error (not a confinement error) because the user asked for a document, not an escape. */
export function exportRel(filename: string, allowed: (ext: string) => boolean): ExportRel {
	const raw = String(filename ?? '').trim();
	if (!raw) { return { ok: false, reason: '文件名为空。' }; }
	if (/[/\\]/.test(raw) || raw.includes('..')) {
		return { ok: false, reason: '文件名不能带路径分隔符或 ..（导出会写到 exports/ 目录）。' };
	}
	const ext = path.extname(raw).toLowerCase();
	if (!allowed(ext)) {
		return { ok: false, reason: `不支持的文件扩展名 ${ext || '（无）'}。` };
	}
	return { ok: true, rel: `${EXPORT_DIR}/${raw}`, ext };
}

// ---- docx（把 phone 的轻量 Markdown 约定实现到 docx 库上） ----

interface DocxPara { text: string; kind: 'h1' | 'h2' | 'h3' | 'p' | 'bullet' | 'number' | 'quote' | 'hr' | 'table'; rows?: string[][] }

const stripBold = (s: string): string => s.replace(/\*\*(.+?)\*\*/g, '$1');

/** Parse the light-Markdown subset the phone contract promises:
 *  `# / ## / ###` headings, `**bold**`, `- ` bullets, `1. ` numbered, `> ` quote,
 *  `| a | b |` + `| --- | --- |` tables, `---` rule, everything else = paragraph. */
export function parseLightMarkdown(src: string): DocxPara[] {
	const lines = String(src ?? '').replace(/\r\n/g, '\n').split('\n');
	const out: DocxPara[] = [];
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i];
		const t = line.trim();
		if (!t) { continue; }
		if (/^-{3,}$/.test(t)) { out.push({ text: '', kind: 'hr' }); continue; }
		if (/^\|.*\|$/.test(t) && /^\|[\s:|-]+\|$/.test(String(lines[i + 1] ?? '').trim())) {
			const rows: string[][] = [];
			const cells = (l: string): string[] => l.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
			rows.push(cells(t));
			i += 2; // skip the separator row
			while (i < lines.length && /^\|.*\|$/.test(String(lines[i]).trim())) {
				rows.push(cells(String(lines[i]).trim()));
				i++;
			}
			i--; // the outer loop advances past the last table row
			out.push({ text: '', kind: 'table', rows });
			continue;
		}
		const h = /^(#{1,3})\s+(.*)$/.exec(t);
		if (h) {
			const lvl = h[1].length;
			out.push({ text: stripBold(h[2]), kind: lvl === 1 ? 'h1' : lvl === 2 ? 'h2' : 'h3' });
			continue;
		}
		if (/^[-*+]\s+/.test(t)) { out.push({ text: stripBold(t.replace(/^[-*+]\s+/, '')), kind: 'bullet' }); continue; }
		if (/^\d+[.)]\s+/.test(t)) { out.push({ text: stripBold(t.replace(/^\d+[.)]\s+/, '')), kind: 'number' }); continue; }
		if (/^>\s?/.test(t)) { out.push({ text: stripBold(t.replace(/^>\s?/, '')), kind: 'quote' }); continue; }
		out.push({ text: t, kind: 'p' }); // keep **bold** markers; renderDocx handles emphasis
	}
	return out;
}

/** `**bold**` inside a paragraph becomes real bold runs rather than literal asterisks. */
function boldRuns(text: string): TextRun[] {
	const parts = String(text).split(/(\*\*[^*]+\*\*)/).filter(Boolean);
	return parts.map(p => (p.startsWith('**') && p.endsWith('**'))
		? new TextRun({ text: p.slice(2, -2), bold: true })
		: new TextRun({ text: p }));
}

export async function renderDocx(title: string, markdown: string): Promise<Buffer> {
	const paras: (Paragraph | Table)[] = [];
	for (const p of parseLightMarkdown(markdown)) {
		switch (p.kind) {
			case 'h1': paras.push(new Paragraph({ heading: HeadingLevel.HEADING_1, children: boldRuns(p.text) })); break;
			case 'h2': paras.push(new Paragraph({ heading: HeadingLevel.HEADING_2, children: boldRuns(p.text) })); break;
			case 'h3': paras.push(new Paragraph({ heading: HeadingLevel.HEADING_3, children: boldRuns(p.text) })); break;
			case 'bullet': paras.push(new Paragraph({ bullet: { level: 0 }, children: boldRuns(p.text) })); break;
			case 'number': paras.push(new Paragraph({ children: boldRuns(`• ${p.text}`) })); break;
			case 'quote': paras.push(new Paragraph({ children: boldRuns(`「${p.text}」`), indent: { left: 480 } })); break;
			case 'hr': paras.push(new Paragraph({ text: '', border: { bottom: { style: 'single', size: 6, color: 'CCCCCC' } } })); break;
			case 'table':
				paras.push(new Table({
					rows: (p.rows ?? []).map(r => new TableRow({
						children: r.map(c => new TableCell({ children: [new Paragraph({ children: boldRuns(c) })] })),
					})),
				}));
				break;
			default: paras.push(new Paragraph({ children: boldRuns(p.text) }));
		}
	}
	const doc = new DocxDocument({ title, sections: [{ children: paras }] });
	return Packer.toBuffer(doc);
}

// ---- xlsx / csv ----

/** Rows are tab-separated cells, one record per line (the phone contract). */
export function parseTableRows(src: string): string[][] {
	return String(src ?? '').replace(/\r\n/g, '\n').split('\n')
		.filter(l => l.trim() !== '')
		.map(l => l.split('\t').map(c => c.trim()));
}

export async function renderXlsx(rows: string[][], sheetName = 'Sheet1'): Promise<Buffer> {
	const wb = new ExcelJS.Workbook();
	const ws = wb.addWorksheet((sheetName || 'Sheet1').slice(0, 31));
	rows.forEach((r, i) => {
		const row = ws.addRow(r);
		if (i === 0) { row.font = { bold: true }; }
	});
	// Column widths from content, clamped: "width = max len" makes one long cell push every
	// other column off screen.
	const width: number[] = [];
	rows.forEach(r => r.forEach((c, i) => { width[i] = Math.max(width[i] ?? 8, Math.min(48, String(c).length + 2)); }));
	width.forEach((w, i) => { ws.getColumn(i + 1).width = w; });
	const buf = await wb.xlsx.writeBuffer();
	return Buffer.from(buf as ArrayBuffer);
}

export function renderCsv(rows: string[][]): string {
	const esc = (c: string): string => (/[",\n]/.test(c) ? `"${c.replace(/"/g, '""')}"` : c);
	// BOM: Excel on a Chinese Windows opens UTF-8 CSV as GBK without it, and the first column
	// of every file turns into mojibake — the most common "your export is broken" report.
	return '\ufeff' + rows.map(r => r.map(esc).join(',')).join('\r\n') + '\r\n';
}

// ---- pptx（结构化 slides JSON） ----

export interface DeckSlide {
	layout?: string;
	title?: string;
	subtitle?: string;
	notes?: string;
	blocks?: { type: string; items?: string[]; text?: string; bold?: boolean; path?: string; caption?: string; rows?: string[][] }[];
}

export function parseSlides(json: string): { ok: true; slides: DeckSlide[]; reason?: undefined } | { ok: false; slides?: undefined; reason: string } {
	let parsed: unknown;
	try { parsed = JSON.parse(String(json ?? '')); } catch (e) {
		return { ok: false, reason: `slides 不是合法 JSON：${(e as Error).message}` };
	}
	if (!Array.isArray(parsed) || !parsed.length) { return { ok: false, reason: 'slides 要是一个非空 JSON 数组。' }; }
	if (parsed.length > FILE_LIMITS.maxSlides) { return { ok: false, reason: `一次最多 ${FILE_LIMITS.maxSlides} 页。` }; }
	return { ok: true, slides: parsed as DeckSlide[] };
}

export async function renderPptx(title: string, slides: DeckSlide[]): Promise<Buffer> {
	const pptx = new PptxGenJS();
	pptx.layout = 'LAYOUT_16x9';
	pptx.title = title;
	for (const s of slides) {
		const slide = pptx.addSlide();
		const isTitle = String(s.layout ?? '') === 'title' || (!s.layout && !!s.title && !!s.subtitle);
		if (s.title) {
			slide.addText(s.title, {
				x: isTitle ? 0.8 : 0.5, y: isTitle ? 2.0 : 0.35,
				w: isTitle ? 8.4 : 9, h: isTitle ? 1.2 : 0.8,
				fontSize: isTitle ? 40 : 28, bold: true,
				align: isTitle ? 'center' : 'left',
			});
		}
		if (s.subtitle) {
			slide.addText(s.subtitle, {
				x: 0.8, y: isTitle ? 3.3 : 1.15, w: 8.4, h: 0.6,
				fontSize: isTitle ? 20 : 16, color: '666666',
				align: isTitle ? 'center' : 'left',
			});
		}
		let y = isTitle ? 4.4 : 1.7;
		for (const b of s.blocks ?? []) {
			if (b.type === 'bullets' && b.items?.length) {
				slide.addText(b.items.map(t => ({ text: String(t), options: { bullet: true } })), { x: 0.7, y, w: 8.6, h: 3.6, fontSize: 16 });
				y += 3.7;
			} else if (b.type === 'para' && b.text) {
				slide.addText(b.text, { x: 0.7, y, w: 8.6, h: 1.0, fontSize: 15, bold: !!b.bold });
				y += 1.1;
			} else if (b.type === 'table' && b.rows?.length) {
				slide.addTable(b.rows.map(r => r.map(c => ({ text: String(c) }))), { x: 0.7, y, w: 8.6, fontSize: 12 });
				y += 0.4 + b.rows.length * 0.35;
			} else if (b.type === 'image' && b.path) {
				const p = String(b.path);
				// http(s)/data URIs pass through; a local path must be absolute. A missing file
				// skips the image instead of failing the whole deck.
				if (/^(https?:|data:)/.test(p) || path.isAbsolute(p)) {
					try {
						slide.addImage({ path: p, x: 0.9, y, w: 7.6, h: 3.2 });
						y += 3.3;
						if (b.caption) { slide.addText(String(b.caption), { x: 0.9, y, w: 7.6, h: 0.4, fontSize: 11, color: '777777' }); y += 0.45; }
					} catch { /* skip the image, keep the deck */ }
				}
			}
		}
		if (s.notes) { slide.addNotes(String(s.notes)); }
	}
	const out = await pptx.write({ outputType: 'nodebuffer' });
	return Buffer.from(out as ArrayBuffer);
}

// ---- 工具注册 ----

export function registerFileOfficeTools(reg: ToolRegistry, getWorkspace: WorkspaceFactory): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: ToolContext) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	const ws = (sessionId?: string): Promise<Workspace | null> => getWorkspace(sessionId);

	/** Write into exports/, through the same containment as every other file write. */
	const writeExport = async (w: Workspace, rel: string, data: Buffer | string): Promise<{ ok: boolean; rel: string; bytes: number; reason?: string }> => {
		const c = await w.confine(rel);
		if (!c.ok) { return { ok: false, rel, bytes: 0, reason: c.reason }; }
		await fsp.mkdir(path.dirname(c.abs), { recursive: true });
		await fsp.writeFile(c.abs, data);
		const bytes = typeof data === 'string' ? Buffer.byteLength(data, 'utf8') : data.length;
		return { ok: true, rel, bytes };
	};
	const WS_DOWN = '[FAIL] 未配置工作区根目录，文件工具已停用。';

	reg.setDomain('office');

	def('file_export', 'confirm', '把纯文本内容导出为工作区 exports/ 下的文本文件（.md/.txt/.json/.csv/.html/.svg/代码等）。办公格式请用 file_export_document',
		{
			type: 'object',
			properties: {
				filename: { type: 'string', description: '文件名（含扩展名，如 大纲.md、数据.csv；不要路径分隔符）' },
				content: { type: 'string', description: '要写入的完整文本（原样写入，不做转义）' },
			},
			required: ['filename', 'content'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return WS_DOWN; }
			const content = String(input?.content ?? '');
			if (!content) { return '[FAIL] content 为空。'; }
			if (content.length > FILE_LIMITS.maxExportChars) {
				return `[FAIL] 内容 ${content.length} 字，超过单次导出上限 ${FILE_LIMITS.maxExportChars}。`;
			}
			const r = exportRel(String(input?.filename ?? ''), ext => TEXT_EXT.has(ext));
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
			const we = await writeExport(w, r.rel, content);
			if (!we.ok) { return `[FAIL] ${we.reason}`; }
			return `已导出 ${we.rel}（${we.bytes} 字节）`;
		});

	def('file_export_document', 'confirm',
		'导出办公文档到 exports/：.docx（Word，支持轻量 Markdown 排版）/ .xlsx（每行一条记录、单元格用制表符分隔）/ .csv',
		{
			type: 'object',
			properties: {
				filename: { type: 'string', description: '文件名（.docx/.xlsx/.csv 结尾；不要路径分隔符）' },
				content: {
					type: 'string',
					description: 'docx 支持轻量 Markdown：# 标题 / ## 小节 / **粗体** / - 要点 / > 引用 / | 表头 |（下一行 | --- |）数据行 / --- 分隔线；其他行=正文。xlsx/csv=每行一条记录、单元格用制表符分隔',
				},
				sheetName: { type: 'string', description: 'xlsx 工作表名（可选）' },
			},
			required: ['filename', 'content'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return WS_DOWN; }
			const content = String(input?.content ?? '');
			if (!content) { return '[FAIL] content 为空。'; }
			const r = exportRel(String(input?.filename ?? ''), ext => ext === '.docx' || ext === '.xlsx' || ext === '.csv');
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
			try {
				if (r.ext === '.docx') {
					const buf = await renderDocx(String(input?.filename ?? '').replace(/\.docx$/i, ''), content);
					const we = await writeExport(w, r.rel, buf);
					return we.ok ? `已导出 ${we.rel}（${we.bytes} 字节，docx）` : `[FAIL] ${we.reason}`;
				}
				const rows = parseTableRows(content);
				if (r.ext === '.csv') {
					const we = await writeExport(w, r.rel, renderCsv(rows));
					return we.ok ? `已导出 ${we.rel}（${we.bytes} 字节，${rows.length} 行）` : `[FAIL] ${we.reason}`;
				}
				const buf = await renderXlsx(rows, String(input?.sheetName ?? 'Sheet1'));
				const we = await writeExport(w, r.rel, buf);
				return we.ok ? `已导出 ${we.rel}（${we.bytes} 字节，${rows.length} 行）` : `[FAIL] ${we.reason}`;
			} catch (e) {
				return `[FAIL] 渲染失败：${(e as Error)?.message ?? String(e)}`;
			}
		});

	def('file_build_deck', 'confirm',
		'生成演示文稿（.pptx）到 exports/：按结构化大纲生成带版式/备注的幻灯片',
		{
			type: 'object',
			properties: {
				filename: { type: 'string', description: '文件名，以 .pptx 结尾，不要带路径' },
				slides: {
					type: 'string',
					description: 'JSON 数组字符串。每项：{"layout":"title|section|bullets|two_column|image|table|quote",'
						+ '"title":"","subtitle":"","notes":"演讲者备注","blocks":[{"type":"bullets","items":["要点"]},'
						+ '{"type":"para","text":"段落","bold":false},{"type":"image","path":"本地绝对路径或http(s)地址","caption":"图注"},'
						+ '{"type":"table","rows":[["表头"],["单元格"]]}]}。'
						+ '标题与要点必须写真实内容，不要写占位话。',
				},
			},
			required: ['filename', 'slides'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return WS_DOWN; }
			const r = exportRel(String(input?.filename ?? ''), ext => ext === '.pptx');
			if (!r.ok) { return `[FAIL] ${r.reason}`; }
			const parsed = parseSlides(String(input?.slides ?? ''));
			if (!parsed.ok) { return `[FAIL] ${parsed.reason}`; }
			try {
				const buf = await renderPptx(String(input?.filename ?? '').replace(/\.pptx$/i, ''), parsed.slides);
				const we = await writeExport(w, r.rel, buf);
				return we.ok ? `已导出 ${we.rel}（${parsed.slides.length} 页，${we.bytes} 字节）` : `[FAIL] ${we.reason}`;
			} catch (e) {
				return `[FAIL] 渲染失败：${(e as Error)?.message ?? String(e)}`;
			}
		});

	// ---- 读侧：文本分段读 + 图片元数据（file 域） ----
	reg.setDomain('file');

	def('file_read_text', 'auto',
		'读取工作区文本文件内容，支持从指定字符位置分段读。返回头写明「第 X–Y 字 / 共 N 字」，没读完按头里的 offset 续读',
		{
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的文件路径（file_list 返回的）' },
				offset: { type: 'number', description: '从第几个字符开始读（0 开始）；首次不传=0' },
				limit: { type: 'number', description: `本次最多读多少字符（默认 ${FILE_LIMITS.defaultReadChars}，上限 ${FILE_LIMITS.maxReadChars}）` },
			},
			required: ['path'],
		},
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const r = await w.read(String(input?.path ?? ''));
			if (!r.ok) { return `[FAIL] ${r.rel}：${r.note ?? r.reason}`; }
			const text = r.content ?? '';
			const total = text.length;
			const offset = Math.max(0, Math.floor(Number(input?.offset ?? 0)) || 0);
			if (offset >= total) { return `（offset ${offset} 已到文件末尾，共 ${total} 字）`; }
			const limit = Math.max(1, Math.min(FILE_LIMITS.maxReadChars,
				Math.floor(Number(input?.limit ?? FILE_LIMITS.defaultReadChars)) || FILE_LIMITS.defaultReadChars));
			// Slice on CHARACTERS, not bytes: one CJK char is 3 bytes and the model counts chars.
			const end = Math.min(total, offset + limit);
			const head = `第 ${offset}–${end} 字 / 共 ${total} 字` + (end < total ? `（未完，续读 offset=${end}）` : '（已读完）');
			return `${head}\n\n${text.slice(offset, end)}`;
		});

	def('file_read_image', 'auto',
		'读取工作区图片的基本信息（格式/尺寸/大小）。PC 端没有 OCR，不返回图片里的文字',
		{ type: 'object', properties: { path: { type: 'string', description: '相对工作区根的图片路径' } }, required: ['path'] },
		async (input, ctx) => {
			const w = await ws(ctx?.sessionId);
			if (!w) { return '[FAIL] 未配置工作区根目录，文件工具已停用。'; }
			const rel = String(input?.path ?? '');
			const c = await w.confine(rel);
			if (!c.ok) { return `[FAIL] ${c.reason}`; }
			let st;
			try { st = await fsp.stat(c.abs); } catch { return `[FAIL] 文件不存在或无法访问：${rel}`; }
			if (st.isDirectory()) { return '[FAIL] 这是一个目录，请用 file_list。'; }
			const buf = await fsp.readFile(c.abs);

			// Dimensions come from the file header — no image library, and never a full decode of
			// a file that may be 50 MB.
			let dim: { w: number; h: number; fmt: string } | null = null;
			if (buf.length > 24 && buf.readUInt32BE(0) === 0x89504e47) {
				dim = { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20), fmt: 'PNG' };
			} else if (buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8) {
				let i = 2;
				while (i + 9 < buf.length) {
					if (buf[i] !== 0xff) { i++; continue; }
					const marker = buf[i + 1];
					const len = buf.readUInt16BE(i + 2);
					if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
						dim = { w: buf.readUInt16BE(i + 7), h: buf.readUInt16BE(i + 5), fmt: 'JPEG' };
						break;
					}
					i += 2 + len;
				}
				if (!dim) { dim = { w: 0, h: 0, fmt: 'JPEG' }; }
			} else if (buf.length > 10 && buf.toString('ascii', 0, 3) === 'GIF') {
				dim = { w: buf.readUInt16LE(6), h: buf.readUInt16LE(8), fmt: 'GIF' };
			} else if (buf.length > 30 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') {
				dim = { w: 0, h: 0, fmt: 'WEBP' };
			}
			if (!dim) { return `[FAIL] 不是可识别的图片格式（PNG/JPEG/GIF/WEBP）：${rel}`; }
			const kb = (st.size / 1024).toFixed(1);
			const sizeText = dim.w && dim.h ? `${dim.w}×${dim.h}` : '尺寸未解析';
			return `${rel}\n格式：${dim.fmt}，尺寸：${sizeText}，大小：${kb} KB\n`
				+ '（PC 端没有 OCR，不提取图片文字；需要图里的文字请用视觉模型或在手机上用 file_read_image）';
		});
}
