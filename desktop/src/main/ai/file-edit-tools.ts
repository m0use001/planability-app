/* 文件/办公域批 2（B2 收尾：phone 的 file_edit_document / file_edit_sheet / file_edit_deck，
 * 2026-11-07）。
 *
 * 设计：三段管线「读回 → 结构化编辑 → 重渲染」，渲染端完全复用批 1 的
 * renderDocx / renderXlsx / renderPptx —— 编辑不是"在二进制上打洞"，而是把文件解析成
 * 与渲染侧同构的中间表示（markdown / rows / slides），改完整体重渲染。理由：
 * - docx/xlsx/pptx 都是 OOXML zip 容器，就地改 XML 需要引入解析库且极易写坏样式；
 * - 重渲染的代价只是"原文件里不是我们渲染出的格式细节会丢"，而这恰好是可解释的：
 *   我们只承诺 round-trip 自己生成的文件，对第三方文件在返回里显式声明降级。
 *
 * 三条硬约定（沿用批 1）：
 * 1. 路径一律走 Workspace.confine；文件名不许带分隔符。
 * 2. 编辑**必须**先读到原文件：读不到就报错，绝不"当空文件新建"——静默清空是最坏结果。
 * 3. 每个工具都返回「改了什么」的可核对摘要（第 N 行 / 第 N 页 / 单元格坐标），
 *    而不是一句"已保存"。模型据此判断是否要再改一次。
 */

import * as fsp from 'node:fs/promises';
import * as path from 'node:path';
import PptxGenJS from 'pptxgenjs';
import ExcelJS from 'exceljs';
import { Document as DocxDocument, Packer } from 'docx';
import type { ToolContext, ToolRegistry } from './tools';
import type { Workspace } from './workspace';
import type { WorkspaceFactory } from './file-tools';
import {
	renderDocx, renderXlsx, renderPptx, parseTableRows, parseSlides,
	FILE_LIMITS, EXPORT_DIR, exportRel, type DeckSlide,
} from './file-office-tools';

export type EditOp =
	| { op: 'replace'; find: string; with: string; all?: boolean }
	| { op: 'append'; text: string }
	| { op: 'prepend'; text: string }
	| { op: 'deleteLine'; match: string }
	| { op: 'insertAfter'; match: string; text: string }
	/** 手机端 edits:[{index,text}] 的归一形状：按 1 基段落/行号整段替换（text 空串=清空该段）。 */
	| { op: 'replaceParagraph'; index: number; text: string };

export type EditResult =
	| { ok: true; text: string; changes: string[]; reason?: undefined }
	| { ok: false; reason: string; text?: undefined; changes?: undefined };

/**
 * 手机端 file_edit_document 的参数形状 → PC 的 ops 管线（别名适配，不是第二套实现）。
 *
 * 手机：`edits:[{index:2,text:"新文字"}]`，index 是 file_read_document 读出的 [Pn]（1 基），
 *       text 空串 = 清空该段；没列出的段落不碰。
 * PC  ：`ops:[{op:'replace',find,with}]` 文本操作。
 *
 * 转换只能做"按段落定位"这一件事，因此这里把 index 解析成 markdown 的行索引，再翻成
 * replace/清空两种 op —— 语义与手机一致，且仍走 applyTextEdits（find 不到会报错，
 * 不会静默不改）。行号口径与 docxToMarkdown 的输出严格一致（那是同一条读回管线）。
 */
export function parsePhoneDocxEdits(raw: unknown): { ops?: EditOp[]; error?: string } {
	if (raw === undefined || raw === null || String(raw).trim() === '') { return {}; }
	let arr: unknown;
	try {
		arr = JSON.parse(String(raw));
	} catch (e) {
		return { error: `edits 不是合法 JSON：${(e as Error).message}` };
	}
	if (!Array.isArray(arr)) { return { error: 'edits 要是一个 JSON 数组。' }; }
	const ops: EditOp[] = [];
	for (const item of arr as Record<string, unknown>[]) {
		const idx = Math.floor(Number(item?.index) || 0);
		if (idx < 1) { return { error: 'edits 每项都要有 index（从 1 起，对应读取的 [Pn]）。' }; }
		const text = String(item?.text ?? '');
		// 用行号标记定位：applyTextEdits 之后由调用方按行号替换。
		ops.push({ op: 'replaceParagraph', index: idx, text } as unknown as EditOp);
	}
	return { ops };
}

/**
 * 手机端 file_edit_deck 的形状 → PC ops。手机 `slides:[{pageNo,title,subtitle,bullets}]`，
 * 语义是"只替换列出的页、其余页原样保留"，正好等于 PC 的 replaceSlide（整页替换但保留页位）。
 * 手机允许多次只给 title（改标题）；PC 的 setTitle 就是这一招，所以只带 title 时走 setTitle，
 * 其余走 replaceSlide —— 与手机 DocModel.slideFromJson 的"缺省字段沿用"不同：
 * PC 侧 replaceSlide 是整页对象，缺的字段会空。为免静默清空，这里对只给 title 的项走 setTitle。
 */
export function parsePhoneDeckSlides(raw: unknown): { ops?: { op: string; slide?: number; title?: string; slideData?: DeckSlide }[]; error?: string } {
	if (raw === undefined || raw === null || String(raw).trim() === '') { return {}; }
	let arr: unknown;
	try {
		arr = JSON.parse(String(raw));
	} catch (e) {
		return { error: `slides 不是合法 JSON：${(e as Error).message}` };
	}
	if (!Array.isArray(arr)) { return { error: 'slides 要是一个 JSON 数组。' }; }
	const ops: { op: string; slide?: number; title?: string; slideData?: DeckSlide }[] = [];
	for (const item of arr as Record<string, unknown>[]) {
		const page = Math.floor(Number(item?.pageNo) || 0);
		if (page < 1) { return { error: 'slides 每项都要有 pageNo（从 1 起，对应读取的 [第 N 页]）。' }; }
		const hasBody = item?.subtitle !== undefined || item?.bullets !== undefined || item?.blocks !== undefined;
		const title = typeof item?.title === 'string' ? item.title : undefined;
		if (!title && !hasBody) { return { error: `第 ${page} 页至少要给 title/subtitle/bullets 之一。` }; }
		if (!hasBody && title !== undefined) {
			// 只给标题：用 setTitle，别用整页替换（后者会把正文清空）
			ops.push({ op: 'setTitle', slide: page, title });
			continue;
		}
		ops.push({
			op: 'replaceSlide', slide: page,
			slideData: {
				title: title ?? '',
				subtitle: typeof item?.subtitle === 'string' ? item.subtitle : '',
				notes: typeof item?.notes === 'string' ? item.notes : undefined,
				blocks: (item?.bullets as unknown[] | undefined)?.length
					? [{ type: 'bullets', items: (item.bullets as unknown[]).map(String) }]
					: (item?.blocks as DeckSlide['blocks']),
			} as DeckSlide,
		});
	}
	return { ops };
}

/** 文本级编辑（docx 的 markdown 中间体、pptx 的 notes 等都用它）。
 *  `find` 找不到 MUST 报错而不是静默不改 —— 模型改错了目标却收到"已保存"是最坏体验。 */
export function applyTextEdits(src: string, ops: EditOp[]): EditResult {
	let text = String(src ?? '');
	const changes: string[] = [];
	for (const [i, raw] of (ops ?? []).entries()) {
		const op = raw as EditOp;
		const tag = `第 ${i + 1} 个操作`;
		if (!op || typeof op !== 'object') { return { ok: false, reason: `${tag}不是对象。` }; }
		if (op.op === 'replace') {
			const find = String(op.find ?? '');
			if (!find) { return { ok: false, reason: `${tag} replace 缺少 find。` }; }
			const withText = String(op.with ?? '');
			const n = text.split(find).length - 1;
			if (n === 0) { return { ok: false, reason: `${tag} replace 找不到「${find.slice(0, 40)}」，未改动任何内容。` }; }
			if (!op.all && n > 1) {
				return { ok: false, reason: `${tag} replace 的「${find.slice(0, 40)}」出现 ${n} 次；要么写更长的唯一片段，要么显式加 all=true。` };
			}
			text = op.all ? text.split(find).join(withText) : text.replace(find, withText);
			changes.push(`replace「${find.slice(0, 30)}」→「${withText.slice(0, 30)}」${op.all ? `（${n} 处）` : ''}`);
		} else if (op.op === 'append') {
			const add = String(op.text ?? '');
			if (!add) { return { ok: false, reason: `${tag} append 的 text 为空。` }; }
			text = text.replace(/\s*$/, '') + '\n\n' + add + '\n';
			changes.push(`末尾追加 ${add.length} 字`);
		} else if (op.op === 'prepend') {
			const add = String(op.text ?? '');
			if (!add) { return { ok: false, reason: `${tag} prepend 的 text 为空。` }; }
			text = add + '\n\n' + text;
			changes.push(`开头插入 ${add.length} 字`);
		} else if (op.op === 'deleteLine') {
			const match = String(op.match ?? '');
			if (!match) { return { ok: false, reason: `${tag} deleteLine 缺少 match。` }; }
			const lines = text.split('\n');
			const kept = lines.filter(l => !l.includes(match));
			const removed = lines.length - kept.length;
			if (removed === 0) { return { ok: false, reason: `${tag} deleteLine 找不到含「${match.slice(0, 40)}」的行。` }; }
			text = kept.join('\n');
			changes.push(`删除 ${removed} 行（含「${match.slice(0, 30)}」）`);
		} else if (op.op === 'insertAfter') {
			const match = String(op.match ?? '');
			const add = String(op.text ?? '');
			if (!match || !add) { return { ok: false, reason: `${tag} insertAfter 需要 match 与 text。` }; }
			const idx = text.indexOf(match);
			if (idx < 0) { return { ok: false, reason: `${tag} insertAfter 找不到「${match.slice(0, 40)}」。` }; }
			const at = idx + match.length;
			text = text.slice(0, at) + '\n' + add + text.slice(at);
			changes.push(`在「${match.slice(0, 30)}」后插入 ${add.length} 字`);
		} else if (op.op === 'replaceParagraph') {
			// 手机端 edits 的归一目标：按 1 基行号整段替换（空串=清空该段）。
			// 行号口径 = docxToMarkdown 的输出（读回管线同一条），所以和 [Pn] 编号严格对齐。
			const lines = text.split('\n');
			const i = op.index - 1;
			if (i < 0 || i >= lines.length) {
				return { ok: false, reason: `${tag} 没有第 ${op.index} 段（这份共 ${lines.length} 段），未改动任何内容。` };
			}
			const old = lines[i];
			const next = String(op.text ?? '');
			if (next === '') {
				lines.splice(i, 1);
				changes.push(`清空第 ${op.index} 段（原「${old.slice(0, 24)}」）`);
			} else {
				lines[i] = next;
				changes.push(`第 ${op.index} 段「${old.slice(0, 20)}」→「${next.slice(0, 20)}」`);
			}
			text = lines.join('\n');
		} else {
			return { ok: false, reason: `${tag} 的 op 未知：${String((op as { op?: string }).op)}。可用 replace/append/prepend/deleteLine/insertAfter。` };
		}
	}
	if (!changes.length) { return { ok: false, reason: '没有提供任何操作（ops 为空）。' }; }
	return { ok: true, text, changes };
}

// ---------- docx：读回 markdown 中间体 ----------

/** docx → markdown。只认我们自己渲染出去的形态（标题/段落/列表/表格/加粗），
 *  其余（图片、页眉页脚、批注、精确字体）一律降级并在返回里说明。 */
export async function docxToMarkdown(abs: string): Promise<{ ok: true; markdown: string; degradations: string[]; reason?: undefined } | { ok: false; reason: string; markdown?: undefined; degradations?: undefined }> {
	let buf: Buffer;
	try { buf = await fsp.readFile(abs); } catch { return { ok: false, reason: '读不到文件。' }; }
	try {
		// docx 是 zip：用 ExcelJS 自带的 jszip 依赖解 word/document.xml
		const JSZip = (await import('jszip')).default;
		const zip = await JSZip.loadAsync(buf);
		const entry = zip.file('word/document.xml');
		if (!entry) { return { ok: false, reason: '不是有效的 .docx（缺 word/document.xml）。' }; }
		const xml = await entry.async('string');
		const out: string[] = [];
		const degradations: string[] = [];
		if (/<w:drawing|<w:pict/.test(xml)) { degradations.push('图片/图形未保留（重渲染只写文本结构）'); }
		if (/<w:commentReference/.test(xml)) { degradations.push('批注未保留'); }

		// 段落级扫描：按 <w:p ...>…</w:p> 成对匹配取整段。
		// 不能用 `xml.split(/<w:p[ >]/)` —— 那会把 <w:pPr>（段落属性，每个段落都有）
		// 也当成段落起点切开，同一段被拆成两半、前一段的正文丢失：2026-11-08 由
		// doc-read 的"读到的编号喂给编辑"对账测试抓到（第二段消失、第三段重复）。
		const paras = xml.match(/<w:p\b[^>]*>[\s\S]*?<\/w:p>/g) ?? [];
		for (const p of paras) {
			const styleMatch = p.match(/<w:pStyle w:val="([^"]+)"/);
			const style = styleMatch?.[1] ?? '';
			// 文本片段：<w:t>…</w:t>，加粗由 <w:b/> 标记（同一 run 内）
			const runs = [...p.matchAll(/<w:r[ >][\s\S]*?<\/w:r>/g)].map(m => {
				const seg = m[0];
				const texts = [...seg.matchAll(/<w:t[^>]*>([\s\S]*?)<\/w:t>/g)].map(t => t[1]).join('');
				const bold = /<w:b\s*\/>/.test(seg);
				return { text: decodeXml(texts), bold };
			});
			const plain = runs.map(r => r.text).join('');
			if (!plain.trim()) {
				const isBullet = /<w:numPr/.test(p);
				if (!isBullet) { continue; }
			}
			const withBold = runs.map(r => (r.bold && r.text.trim() ? `**${r.text}**` : r.text)).join('');
			if (/^Heading1/.test(style)) { out.push(`# ${plain}`); }
			else if (/^Heading2/.test(style)) { out.push(`## ${plain}`); }
			else if (/^Heading3/.test(style)) { out.push(`### ${plain}`); }
			else if (/<w:numPr/.test(p)) { out.push(`- ${plain}`); }
			else if (plain.trim()) { out.push(withBold); }
		}
		if (/<w:tbl>/.test(xml)) { degradations.push('表格已尽量转为文本行，单元格合并会丢'); }
		return { ok: true, markdown: out.join('\n'), degradations };
	} catch (e) {
		return { ok: false, reason: `解析 .docx 失败：${(e as Error)?.message ?? String(e)}` };
	}
}

function decodeXml(s: string): string {
	return String(s)
		.replace(/&lt;/g, '<').replace(/&gt;/g, '>')
		.replace(/&quot;/g, '"').replace(/&apos;/g, "'")
		.replace(/&amp;/g, '&');
}

// ---------- xlsx：读回 rows ----------

export async function xlsxToRows(abs: string): Promise<{ ok: true; rows: string[][]; sheet: string; reason?: undefined } | { ok: false; reason: string; rows?: undefined; sheet?: undefined }> {
	let buf: Buffer;
	try { buf = await fsp.readFile(abs); } catch { return { ok: false, reason: '读不到文件。' }; }
	try {
		const wb = new ExcelJS.Workbook();
		// ExcelJS 的 d.ts 声明的是它自带的 Buffer 类型，与 Node 22 的 Buffer<ArrayBuffer> 不兼容；
		// 运行时接受同一个对象，用 unknown 桥一次（比关掉 strict 或复制整块字节更诚实）
		await wb.xlsx.load(buf as unknown as Parameters<typeof wb.xlsx.load>[0]);
		const ws = wb.worksheets[0];
		if (!ws) { return { ok: false, reason: '.xlsx 里没有工作表。' }; }
		const rows: string[][] = [];
		ws.eachRow({ includeEmpty: false }, row => {
			const vals: string[] = [];
			row.eachCell({ includeEmpty: true }, cell => {
				const v = cell.value;
				vals.push(v == null ? '' : typeof v === 'object' && 'text' in (v as object)
					? String((v as { text: unknown }).text)
					: String(v));
			});
			rows.push(vals);
		});
		return { ok: true, rows, sheet: ws.name };
	} catch (e) {
		return { ok: false, reason: `解析 .xlsx 失败：${(e as Error)?.message ?? String(e)}` };
	}
}

// ---------- 单元格级编辑（sheet） ----------

export type CellEdit =
	| { row: number; col: number; value: string }
	| { row: number; col?: never; values: string[] };

/** 行列都是 1 起（与 Excel 的 A1 习惯一致），越界即报错 —— 悄悄扩表比报错更难查。 */
export function applyCellEdits(rows: string[][], edits: CellEdit[]): { ok: true; rows: string[][]; changes: string[]; reason?: undefined } | { ok: false; reason: string; rows?: undefined; changes?: undefined } {
	const out = rows.map(r => [...r]);
	const changes: string[] = [];
	for (const [i, e] of (edits ?? []).entries()) {
		const tag = `第 ${i + 1} 处`;
		if (!e || typeof e !== 'object') { return { ok: false, reason: `${tag}不是对象。` }; }
		const r = Math.floor(Number(e.row));
		if (!Number.isFinite(r) || r < 1 || r > out.length) {
			return { ok: false, reason: `${tag} row=${e.row} 越界（当前 ${out.length} 行）。` };
		}
		const maybeValues = (e as { values?: unknown }).values;
		if (Array.isArray(maybeValues)) {
			out[r - 1] = maybeValues.map((v: unknown) => String(v ?? ''));
			changes.push(`第 ${r} 行整行替换为 ${out[r - 1].length} 列`);
			continue;
		}
		const c = Math.floor(Number((e as { col?: unknown }).col));
		const width = out[r - 1].length;
		if (!Number.isFinite(c) || c < 1) { return { ok: false, reason: `${tag} col 非法（1 起）。` }; }
		if (c > width) {
			// 只在"接在末尾一列"时允许扩列，跳列会留下空洞
			if (c !== width + 1) { return { ok: false, reason: `${tag} col=${c} 超出当前 ${width} 列且不是紧邻的下一列。` }; }
		}
		const row = out[r - 1];
		while (row.length < c) { row.push(''); }
		const before = row[c - 1] ?? '';
		const newVal = String((e as { value?: unknown }).value ?? '');
		row[c - 1] = newVal;
		changes.push(`R${r}C${c}：「${before.slice(0, 20)}」→「${newVal.slice(0, 20)}」`);
	}
	if (!changes.length) { return { ok: false, reason: '没有提供任何编辑。' }; }
	return { ok: true, rows: out, changes };
}

// ---------- pptx：读回 slides ----------

/** pptx → DeckSlide[]。只认我们自己渲染的版式；主题色/母版/动画降级并声明。 */
export async function pptxToSlides(abs: string): Promise<{ ok: true; slides: DeckSlide[]; degradations: string[]; reason?: undefined } | { ok: false; reason: string; slides?: undefined; degradations?: undefined }> {
	let buf: Buffer;
	try { buf = await fsp.readFile(abs); } catch { return { ok: false, reason: '读不到文件。' }; }
	try {
		const JSZip = (await import('jszip')).default;
		const zip = await JSZip.loadAsync(buf);
		const names = Object.keys(zip.files).filter(n => /^ppt\/slides\/slide\d+\.xml$/.test(n))
			.sort((a, b) => Number(a.match(/\d+/)![0]) - Number(b.match(/\d+/)![0]));
		if (!names.length) { return { ok: false, reason: '不是有效的 .pptx（找不到 slide）。' }; }
		const slides: DeckSlide[] = [];
		const degradations: string[] = ['主题色/母版/动画/图片位置未保留（重渲染用默认版式）'];
		for (const n of names) {
			const xml = await zip.file(n)!.async('string');
			// 从 a:t 取所有文本，按字号/位置粗分标题与要点
			const texts = [...xml.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)].map(m => decodeXml(m[1]));
			if (!texts.length) { slides.push({ layout: 'section', title: '（空页）' }); continue; }
			const title = texts[0];
			const rest = texts.slice(1);
			slides.push({
				layout: 'bullets',
				title,
				blocks: rest.length ? [{ type: 'bullets', items: rest }] : [],
			});
		}
		return { ok: true, slides, degradations };
	} catch (e) {
		return { ok: false, reason: `解析 .pptx 失败：${(e as Error)?.message ?? String(e)}` };
	}
}

// ---------- 工具注册 ----------

export function registerFileEditTools(reg: ToolRegistry, getWorkspace: WorkspaceFactory): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any, ctx?: ToolContext) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	reg.setDomain('file');

	const ws = (sessionId?: string): Promise<Workspace | null> => getWorkspace(sessionId);
	const need = async (sessionId?: string): Promise<Workspace> => {
		const w = await ws(sessionId);
		if (!w) { throw new Error('未配置工作区根目录，文件工具已停用。'); }
		return w;
	};

	const editFilePath = async (input: { path?: unknown }, w: Workspace): Promise<{ abs: string; rel: string } | string> => {
		const rel = String(input?.path ?? '').trim();
		if (!rel) { return '[FAIL] 缺少 path。'; }
		const c = await w.confine(rel);
		if (!c.ok) { return `[FAIL] ${c.reason}`; }
		return { abs: c.abs, rel };
	};

	// ---- docx ----
	def('file_edit_document', 'confirm',
		'编辑工作区里已有的 .docx：读回正文为 markdown 中间体 → 按 ops 改 → 重渲染回原路径。'
		+ '支持 replace/append/prepend/deleteLine/insertAfter。返回逐条改动摘要（改不到目标会明确报错，不会静默成功）',
		{
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的 .docx 路径（file_list 返回的）' },
				ops: {
					type: 'string',
					description: 'JSON 数组字符串，每项一个操作：'
						+ '{"op":"replace","find":"原文","with":"新文","all":false} | '
						+ '{"op":"append","text":"追加段落"} | {"op":"prepend","text":"开头段落"} | '
						+ '{"op":"deleteLine","match":"包含此串的行"} | {"op":"insertAfter","match":"锚点","text":"插入内容"}。'
						+ '与 edits 二选一（edits 是手机端形状的别名）',
				},
				edits: {
					type: 'string',
					description: '（手机端 file_edit_document 的形状，跨端提示词通用）JSON 数组：'
						+ '[{"index":2,"text":"新文字"}]，index 从 1 起，对应 file_read_document 读出的 [Pn]；'
						+ 'text 传空串=清空该段。没列出的段落不碰。与 ops 二选一',
				},
				saveAs: { type: 'string', description: '可选：另存为 exports/ 下的新文件名（不带路径）。不传=覆盖原文件' },
			},
			required: ['path'],
		},
		async (input, ctx) => {
			const w = await need(ctx?.sessionId);
			const f = await editFilePath(input, w);
			if (typeof f === 'string') { return f; }
			if (!/\.docx$/i.test(f.rel)) { return '[FAIL] file_edit_document 只处理 .docx。' }
			// 参数形状双入口（手机 edits:[{index,text}] / PC ops:[...]）。别名只是把入口
			// 归一成同一条管线（applyTextEdits），不是两套实现——两套实现就会两套行为。
			const aliasEdits = parsePhoneDocxEdits(input?.edits);
			if (aliasEdits.error) { return `[FAIL] ${aliasEdits.error}`; }
			let ops: EditOp[];
			if (aliasEdits.ops) {
				if (input?.ops !== undefined) {
					return '[FAIL] ops 与 edits 只能给一个（edits 是手机端形状的别名）。';
				}
				ops = aliasEdits.ops;
			} else {
				try {
					const parsed: unknown = JSON.parse(String(input?.ops ?? '[]'));
					if (!Array.isArray(parsed)) { return '[FAIL] ops 要是一个 JSON 数组。'; }
					ops = parsed as EditOp[];
				} catch (e) {
					return `[FAIL] ops 不是合法 JSON：${(e as Error).message}`;
				}
			}
			const back = await docxToMarkdown(f.abs);
			if (!back.ok) { return `[FAIL] ${back.reason}`; }
			const applied = applyTextEdits(back.markdown, ops);
			if (!applied.ok) { return `[FAIL] ${applied.reason}`; }
			if (applied.text.length > FILE_LIMITS.maxExportChars) {
				return `[FAIL] 编辑后正文 ${applied.text.length} 字，超过 ${FILE_LIMITS.maxExportChars} 字上限。`;
			}
			try {
				const buf = await renderDocx(path.basename(f.rel).replace(/\.docx$/i, ''), applied.text);
				const target = await writeBack(w, f, String(input?.saveAs ?? ''), '.docx');
				if (typeof target === 'string') { return target; }
				await fsp.mkdir(path.dirname(target.abs), { recursive: true });
				await fsp.writeFile(target.abs, buf);
				const notes = back.degradations.length ? `\n降级说明：${back.degradations.join('；')}` : '';
				return `已保存 ${target.rel}（${buf.length} 字节）\n改动：\n- ${applied.changes.join('\n- ')}${notes}`;
			} catch (e) {
				return `[FAIL] 重渲染失败：${(e as Error)?.message ?? String(e)}`;
			}
		});

	// ---- xlsx ----
	def('file_edit_sheet', 'confirm',
		'编辑工作区里已有的 .xlsx：读回全部行为二维数组 → 按 edits 改单元格/整行 → 重写回原路径。'
		+ '行列都是 1 起（同 Excel 习惯），越界会明确报错而不是悄悄扩表',
		{
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的 .xlsx 路径' },
				edits: {
					type: 'string',
					description: 'JSON 数组字符串：{"row":2,"col":3,"value":"新值"} 改单元格；'
						+ '{"row":2,"values":["整","行","内","容"]} 替换整行',
				},
				saveAs: { type: 'string', description: '可选：另存为 exports/ 下的新文件名（不带路径）' },
			},
			required: ['path', 'edits'],
		},
		async (input, ctx) => {
			const w = await need(ctx?.sessionId);
			const f = await editFilePath(input, w);
			if (typeof f === 'string') { return f; }
			if (!/\.xlsx$/i.test(f.rel)) { return '[FAIL] file_edit_sheet 只处理 .xlsx（.csv 请用 file_read_text + file_export）。'; }
			let edits: CellEdit[];
			try {
				const parsed: unknown = JSON.parse(String(input?.edits ?? '[]'));
				if (!Array.isArray(parsed)) { return '[FAIL] edits 要是一个 JSON 数组。'; }
				edits = parsed as CellEdit[];
			} catch (e) {
				return `[FAIL] edits 不是合法 JSON：${(e as Error).message}`;
			}
			const back = await xlsxToRows(f.abs);
			if (!back.ok) { return `[FAIL] ${back.reason}`; }
			const applied = applyCellEdits(back.rows, edits);
			if (!applied.ok) { return `[FAIL] ${applied.reason}`; }
			try {
				const buf = await renderXlsx(applied.rows, back.sheet);
				const target = await writeBack(w, f, String(input?.saveAs ?? ''), '.xlsx');
				if (typeof target === 'string') { return target; }
				await fsp.mkdir(path.dirname(target.abs), { recursive: true });
				await fsp.writeFile(target.abs, buf);
				return `已保存 ${target.rel}（${applied.rows.length} 行，${buf.length} 字节）\n改动：\n- ${applied.changes.join('\n- ')}`;
			} catch (e) {
				return `[FAIL] 重渲染失败：${(e as Error)?.message ?? String(e)}`;
			}
		});

	// ---- pptx ----
	def('file_edit_deck', 'confirm',
		'编辑工作区里已有的 .pptx：读回幻灯片 → 按 ops 改（整页替换/追加/删除/改标题）→ 重渲染回原路径。'
		+ '返回逐条改动摘要；主题色与图片位置会降级（用默认版式重排）',
		{
			type: 'object',
			properties: {
				path: { type: 'string', description: '相对工作区根的 .pptx 路径' },
				ops: {
					type: 'string',
					description: 'JSON 数组字符串，每项一个操作：'
						+ '{"op":"setTitle","slide":1,"title":"新标题"} | '
						+ '{"op":"replaceSlide","slide":2,"slide":{...DeckSlide 形状}} | '
						+ '{"op":"appendSlide","slide":{...}} | {"op":"deleteSlide","slide":3}。'
						+ '与 slides 二选一（slides 是手机端形状的别名）',
				},
				slides: {
					type: 'string',
					description: '（手机端 file_edit_deck 的形状，跨端提示词通用）JSON 数组：'
						+ '[{"pageNo":2,"title":"新标题","subtitle":"副标题","bullets":["要点1"]}]，'
						+ 'pageNo 从 1 起，对应 file_read_document 读出的 [第 N 页]；只替换列出的页，其余页不碰。'
						+ '与 ops 二选一',
				},
				saveAs: { type: 'string', description: '可选：另存为 exports/ 下的新文件名（不带路径）' },
			},
			required: ['path'],
		},
		async (input, ctx) => {
			const w = await need(ctx?.sessionId);
			const f = await editFilePath(input, w);
			if (typeof f === 'string') { return f; }
			if (!/\.pptx$/i.test(f.rel)) { return '[FAIL] file_edit_deck 只处理 .pptx。'; }
			let ops: { op: string; slide?: number; title?: string; slideData?: DeckSlide }[];
			// 参数形状双入口：手机 slides:[{pageNo,...}] → PC ops:[{op:'replaceSlide'|'setTitle',slide}]
			const aliasSlides = parsePhoneDeckSlides(input?.slides);
			if (aliasSlides.error) { return `[FAIL] ${aliasSlides.error}`; }
			if (aliasSlides.ops) {
				if (input?.ops !== undefined) {
					return '[FAIL] ops 与 slides 只能给一个（slides 是手机端形状的别名）。';
				}
				ops = aliasSlides.ops;
			} else {
			try {
				const parsed: unknown = JSON.parse(String(input?.ops ?? '[]'));
				if (!Array.isArray(parsed)) { return '[FAIL] ops 要是一个 JSON 数组。'; }
				// 允许字段名 slide 同时表示"页码"与"幻灯片对象"（模型常这么写）——按类型分流
				ops = (parsed as Record<string, unknown>[]).map(o => {
					const idx = typeof o.slide === 'number' ? o.slide : undefined;
					const data = o.slide && typeof o.slide === 'object' ? o.slide as DeckSlide : undefined;
					return { op: String(o.op ?? ''), slide: idx, title: typeof o.title === 'string' ? o.title : undefined, slideData: data };
				});
			} catch (e) {
				return `[FAIL] ops 不是合法 JSON：${(e as Error).message}`;
			}
			}
			const back = await pptxToSlides(f.abs);
			if (!back.ok) { return `[FAIL] ${back.reason}`; }
			const slides: DeckSlide[] = back.slides.map(s => ({ ...s }));
			const changes: string[] = [];
			for (const [i, o] of ops.entries()) {
				const tag = `第 ${i + 1} 个操作`;
				if (o.op === 'setTitle') {
					if (!o.slide || o.slide < 1 || o.slide > slides.length) { return `[FAIL] ${tag} slide=${o.slide} 越界（共 ${slides.length} 页）。`; }
					const before = slides[o.slide - 1].title ?? '';
					slides[o.slide - 1].title = o.title ?? '';
					changes.push(`第 ${o.slide} 页标题「${before.slice(0, 20)}」→「${(o.title ?? '').slice(0, 20)}」`);
				} else if (o.op === 'replaceSlide') {
					if (!o.slide || o.slide < 1 || o.slide > slides.length) { return `[FAIL] ${tag} slide=${o.slide} 越界（共 ${slides.length} 页）。`; }
					if (!o.slideData) { return `[FAIL] ${tag} replaceSlide 缺少 slide 对象。`; }
					slides[o.slide - 1] = o.slideData;
					changes.push(`第 ${o.slide} 页整页替换`);
				} else if (o.op === 'appendSlide') {
					if (!o.slideData) { return `[FAIL] ${tag} appendSlide 缺少 slide 对象。`; }
					if (slides.length >= FILE_LIMITS.maxSlides) { return `[FAIL] 已达 ${FILE_LIMITS.maxSlides} 页上限。`; }
					slides.push(o.slideData);
					changes.push(`追加第 ${slides.length} 页`);
				} else if (o.op === 'deleteSlide') {
					if (!o.slide || o.slide < 1 || o.slide > slides.length) { return `[FAIL] ${tag} slide=${o.slide} 越界（共 ${slides.length} 页）。`; }
					const removed = slides.splice(o.slide - 1, 1)[0];
					changes.push(`删除第 ${o.slide} 页「${String(removed.title ?? '').slice(0, 20)}」`);
				} else {
					return `[FAIL] ${tag} 的 op 未知：${o.op}。可用 setTitle/replaceSlide/appendSlide/deleteSlide。`;
				}
			}
			if (!changes.length) { return '[FAIL] 没有提供任何操作。'; }
			if (!slides.length) { return '[FAIL] 删完就没有页了，至少保留一页。'; }
			try {
				const buf = await renderPptx(path.basename(f.rel).replace(/\.pptx$/i, ''), slides);
				const target = await writeBack(w, f, String(input?.saveAs ?? ''), '.pptx');
				if (typeof target === 'string') { return target; }
				await fsp.mkdir(path.dirname(target.abs), { recursive: true });
				await fsp.writeFile(target.abs, buf);
				return `已保存 ${target.rel}（${slides.length} 页，${buf.length} 字节）\n改动：\n- ${changes.join('\n- ')}\n`
					+ `降级说明：${back.degradations.join('；')}`;
			} catch (e) {
				return `[FAIL] 重渲染失败：${(e as Error)?.message ?? String(e)}`;
			}
		});
}

/** 目标路径：默认覆盖原文件；给了 saveAs 则要求纯文件名、经 Workspace.confine 落到 exports/。
 *  绝不自己拼相对路径——越界防护必须和读写两侧走同一个 confine（批 1 的教训）。 */
async function writeBack(
	w: Workspace, f: { abs: string; rel: string }, saveAs: string, ext: string
): Promise<{ abs: string; rel: string } | string> {
	const raw = String(saveAs ?? '').trim();
	if (!raw) { return f; }
	// exportRel 已经返回 `exports/<name>`（批 1 的导出契约）。这里再拼一次 EXPORT_DIR
	// 会把文件写到 `exports/exports/<name>` —— 2026-11-08 由 doc-read 的对账测试抓到。
	const r = exportRel(raw, e => e === ext);
	if (!r.ok) { return `[FAIL] ${r.reason}`; }
	const rel = r.rel;
	const c = await w.confine(rel);
	if (!c.ok) { return `[FAIL] ${c.reason}`; }
	return { abs: c.abs, rel };
}
