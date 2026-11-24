import { useState } from 'react';
import { Ban, CalendarDays, CircleX, FileText, FolderTree, Globe, LoaderCircle, Search, ShieldCheck, SquareCheckBig, Terminal, Wrench } from 'lucide-react';
import type { ConvRow } from '@shared/chat';

/* Per-tool renderer registry (ZCode ToolCallBlocks parity): a tool registers a component
 * for its result; unregistered tools fall back to the generic trace. The transcript never
 * changes when tools come and go — M17+ agent/MCP tools just add registry entries. */

export type ToolRow = ConvRow;

type ResultView = (props: { row: ConvRow }) => JSX.Element | null;

/* ---- query result views (parse the truncated raw output carried on the row) ---- */

/** "2026-10-08 09:00 标题（备注） [id]" lines from schedule_query. */
const ScheduleQueryView: ResultView = ({ row }) => {
	const items = (row.result ?? '').split('\n').map(l => l.trim()).filter(Boolean)
		.map(l => l.match(/^(\S+)\s+(?:(\d{2}:\d{2})\s+)?(.*?)\s*\[([^\]]*)\]$/))
		.filter((m): m is RegExpMatchArray => !!m);
	if (!items.length) { return <div className="tr-dim">{row.result}</div>; }
	return (
		<table className="tr-table">
			<thead><tr><th>日期</th><th>时间</th><th>事项</th></tr></thead>
			<tbody>
				{items.map((m, i) => (
					<tr key={i}><td>{m[1]}</td><td>{m[2] ?? '全天'}</td><td>{m[3]}</td></tr>
				))}
			</tbody>
		</table>
	);
};

/** "✓/· 日期 标题 [id]" lines from todo_query. */
const TodoQueryView: ResultView = ({ row }) => {
	const items = (row.result ?? '').split('\n').map(l => l.trim()).filter(Boolean)
		.map(l => l.match(/^([✓·])\s+(\S+)\s+(.*?)\s*\[([^\]]*)\]$/))
		.filter((m): m is RegExpMatchArray => !!m);
	if (!items.length) { return <div className="tr-dim">{row.result}</div>; }
	return (
		<div className="tr-list">
			{items.map((m, i) => (
				<div key={i} className={`tr-item ${m[1] === '✓' ? 'done' : ''}`}>
					<span className="tr-check">{m[1] === '✓' ? '✓' : '·'}</span>
					<span className="tr-date">{m[2]}</span>
					<span className="tr-title">{m[3]}</span>
				</div>
			))}
		</div>
	);
};

/** "# 标题 [id]\n正文" blocks from note_search. */
const NoteSearchView: ResultView = ({ row }) => {
	const blocks = (row.result ?? '').split('\n---\n').map(b => b.match(/^# (.+?) \[([^\]]*)\]\s*\n?([\s\S]*)$/))
		.filter((m): m is RegExpMatchArray => !!m);
	if (!blocks.length) { return <div className="tr-dim">{row.result}</div>; }
	return (
		<div className="tr-list">
			{blocks.map((m, i) => (
				<div key={i} className="tr-note">
					<div className="tr-note-title"><FileText size={12} /> {m[1]}</div>
					<div className="tr-note-body">{m[3].slice(0, 200)}</div>
				</div>
			))}
		</div>
	);
};

/* ---- M18 file domain views (parse the truncated raw output carried on the row) ---- */

/* Mirrors the tool_end slice in main/ai/agent.ts: a row never carries more than this —
 * the full output goes to the model, the row only holds a viewable slice. Used purely to
 * word the truncation note, so a cap change up-stream just makes the note quieter. */
const RAW_CAP = 1500;

/** file_read: the file text itself — clamp by default, expand on demand. */
const FileReadView: ResultView = ({ row }) => {
	const [open, setOpen] = useState(false);
	const raw = row.result ?? '';
	if (raw.startsWith('[FAIL]')) { return <div className="tr-dim">{raw}</div>; }
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			{/* meta sits above the block: once expanded, the toggle must not end up
			 * outside the trace-detail scroll viewport */}
			<div className="fv-meta">
				<span className="fv-note">
					{truncated ? `轨迹只保留前 ${RAW_CAP} 字符，完整内容已交给模型` : `${raw.length} 字符`}
				</span>
				{raw.length > 200 && (
					<button className="fv-toggle" onClick={() => setOpen(!open)}>{open ? '收起' : '展开'}</button>
				)}
			</div>
			<pre className={`fv-pre${open ? ' open' : ''}`}>{raw}</pre>
		</div>
	);
};

type FileLine = { kind: 'dir' | 'file' | 'text'; rel: string; size?: string; time?: string };

/** file_list: "rel/" for dirs, "rel  size  time" for files (two-space separated). */
const FileListView: ResultView = ({ row }) => {
	const raw = row.result ?? '';
	if (raw.startsWith('[FAIL]') || raw === '（空目录）') { return <div className="tr-dim">{raw}</div>; }
	const entries: FileLine[] = raw.split('\n').filter(Boolean).map(l => {
		if (l.endsWith('/')) { return { kind: 'dir' as const, rel: l.slice(0, -1) }; }
		const m = l.match(/^(.+?)  (\S+)  (\d{4}-\d{2}-\d{2} \d{2}:\d{2})$/);
		return m ? { kind: 'file' as const, rel: m[1], size: m[2], time: m[3] } : { kind: 'text' as const, rel: l };
	});
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			{truncated && <span className="fv-note">轨迹被截断（前 {RAW_CAP} 字符），目录里可能还有更多条目</span>}
			<div className="fv-list">
				{entries.map((e, i) => e.kind === 'text'
					? <div key={i} className="fv-text">{e.rel}</div>
					: (
						<div key={i} className="fv-row">
							<span className="fv-ico">{e.kind === 'dir' ? <FolderTree size={12} /> : <FileText size={12} />}</span>
							<span className="fv-rel">{e.rel}{e.kind === 'dir' ? '/' : ''}</span>
							{e.size && <span className="fv-sub">{e.size}{e.time ? ` · ${e.time}` : ''}</span>}
						</div>
					))}
			</div>
		</div>
	);
};

/** file_search: "rel:line: text" hits; anything else (cap notes) rides through as text. */
const FileSearchView: ResultView = ({ row }) => {
	const raw = row.result ?? '';
	if (raw.startsWith('[FAIL]') || raw.startsWith('（扫描')) { return <div className="tr-dim">{raw}</div>; }
	const hits: { rel: string; line: string; text: string }[] = [];
	const tail: string[] = [];
	for (const l of raw.split('\n')) {
		const m = l.match(/^(.+?):(\d+): (.*)$/);
		if (m) { hits.push({ rel: m[1], line: m[2], text: m[3] }); } else if (l) { tail.push(l); }
	}
	if (!hits.length) { return <div className="tr-dim">{raw}</div>; }
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			{truncated && <span className="fv-note">轨迹被截断（前 {RAW_CAP} 字符），可能还有更多命中</span>}
			<div className="fv-list">
				{hits.map((h, i) => (
					<div key={i} className="fv-hit">
						<span className="fv-rel">{h.rel}</span>
						<span className="fv-ln">:{h.line}</span>
						<code className="fv-txt">{h.text}</code>
					</div>
				))}
			</div>
			{tail.map((t, i) => <div key={i} className="fv-text">{t}</div>)}
		</div>
	);
};

/* ---- M18 shell / web domain views (pc-a) ---- */

/** The argument worth seeing before expanding: the command line, the URL, the query —
 * not its JSON. (argsJson is truncated at 400 chars upstream, so this can be a prefix.) */
function inlineArg(row: ConvRow): string | null {
	if (!row.argsJson) { return null; }
	try {
		const parsed = JSON.parse(row.argsJson) as Record<string, unknown>;
		const v = parsed.command ?? parsed.url ?? parsed.query;
		return typeof v === 'string' && v ? v : null;
	} catch { return null; }
}

/** shell_run result header: "[OK]" / "[EXIT 1]" / "[DENIED] …" / "[TIMEOUT] …" (agent.ts summary is this line). */
const SHELL_HEAD = /^\[(OK|EXIT \d+|DENIED|TIMEOUT|FAIL)[^\]]*\]/;

/** shell_run: the command (full, since the status line ellipsises it) + the captured output. */
const ShellRunView: ResultView = ({ row }) => {
	const [open, setOpen] = useState(false);
	const raw = row.result ?? '';
	const m = raw.match(SHELL_HEAD);
	const badge = m?.[0] ?? '';
	const body = badge ? raw.slice(badge.length).replace(/^\r?\n/, '') : raw;
	// [OK] is the only success head; DENIED/TIMEOUT/FAIL/EXIT all mean "it did not do the job"
	const tone = badge === '[OK]' ? 'good' : 'bad';
	const cmd = inlineArg(row);
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			{cmd && <div className="sh-cmd"><code>{cmd}</code></div>}
			<div className="fv-meta">
				{badge && <span className={`sh-badge ${tone}`}>{badge}</span>}
				<span className="fv-note">
					{body.length} 字符输出{truncated ? ` · 轨迹只保留前 ${RAW_CAP} 字符，完整输出已交给模型` : ''}
				</span>
				{body.length > 200 && (
					<button className="fv-toggle" onClick={() => setOpen(!open)}>{open ? '收起' : '展开'}</button>
				)}
			</div>
			<pre className={`fv-pre${open ? ' open' : ''}`}>{body}</pre>
		</div>
	);
};

/** shell_check: "层级=deny|allow|ask；reason" — a verdict, so render it as a chip. */
const ShellCheckView: ResultView = ({ row }) => {
	const raw = row.result ?? '';
	const m = raw.match(/^层级=(deny|allow|ask)(?:；([\s\S]*))?$/);
	if (!m) { return <div className="tr-dim">{raw}</div>; }
	const label = m[1] === 'deny' ? '禁止 · 硬禁表' : m[1] === 'allow' ? '免审批 · 只读白名单' : '需审批';
	return (
		<div className="fv">
			<div className="fv-meta">
				<span className={`sh-badge ${m[1] === 'allow' ? 'good' : m[1] === 'deny' ? 'bad' : ''}`}>{label}</span>
				{m[2] && <span className="fv-note">{m[2]}</span>}
			</div>
		</div>
	);
};

/** web_fetch: "标题：host" / "[JSON] …" head over the stripped page text. */
const WebFetchView: ResultView = ({ row }) => {
	const [open, setOpen] = useState(false);
	const raw = row.result ?? '';
	if (raw.startsWith('[FAIL]')) { return <div className="tr-dim">{raw}</div>; }
	let head: string;
	let body: string;
	if (raw.startsWith('[JSON]')) {
		head = 'JSON 响应';
		body = raw.slice(6).replace(/^\r?\n/, '');
	} else {
		const nl = raw.indexOf('\n');
		head = nl < 0 ? raw : raw.slice(0, nl);
		body = nl < 0 ? '' : raw.slice(nl + 1);
	}
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			<div className="fv-meta">
				<Globe size={12} className="fv-ico" />
				<span className="fv-note">{head}</span>
				<span className="fv-note">
					{body.length} 字符{truncated ? ` · 轨迹只保留前 ${RAW_CAP} 字符，完整正文已交给模型` : ''}
				</span>
				{body.length > 200 && (
					<button className="fv-toggle" onClick={() => setOpen(!open)}>{open ? '收起' : '展开'}</button>
				)}
			</div>
			<pre className={`fv-pre${open ? ' open' : ''}`}>{body}</pre>
		</div>
	);
};

/** web_search: "1. title" / "   url" / "   snippet" blocks. */
const WebSearchView: ResultView = ({ row }) => {
	const raw = row.result ?? '';
	if (raw.startsWith('[FAIL]') || raw.startsWith('（无结果）')) { return <div className="tr-dim">{raw}</div>; }
	const hits: { title: string; url: string; snippet?: string }[] = [];
	for (const line of raw.split('\n')) {
		const h = line.match(/^\d+\.\s+(.*)$/);
		if (h) { hits.push({ title: h[1], url: '' }); continue; }
		// continuation lines are indented; the first one is the URL, the rest fold into the snippet
		const cont = line.match(/^\s+(\S.*)$/);
		if (cont && hits.length) {
			const last = hits[hits.length - 1];
			if (!last.url) { last.url = cont[1]; }
			else { last.snippet = last.snippet ? `${last.snippet} ${cont[1]}` : cont[1]; }
		}
	}
	if (!hits.length) { return <div className="tr-dim">{raw}</div>; }
	const truncated = raw.length >= RAW_CAP;
	return (
		<div className="fv">
			<div className="fv-meta">
				<span className="fv-note">{hits.length} 条结果{truncated ? ` · 轨迹只保留前 ${RAW_CAP} 字符，可能还有更多` : ''}</span>
			</div>
			<div className="fv-list">
				{hits.map((h, i) => (
					<div key={i} className="web-hit">
						<div className="web-hit-title">{i + 1}. {h.title}</div>
						{h.url && <div className="web-hit-url">{h.url}</div>}
						{h.snippet && <div className="web-hit-snip">{h.snippet}</div>}
					</div>
				))}
			</div>
		</div>
	);
};

const RENDERERS: Record<string, ResultView> = {
	schedule_query: ScheduleQueryView,
	todo_query: TodoQueryView,
	note_search: NoteSearchView,
	file_list: FileListView,
	file_read: FileReadView,
	file_search: FileSearchView,
	shell_run: ShellRunView,
	shell_check: ShellCheckView,
	web_fetch: WebFetchView,
	web_search: WebSearchView
};

/** these views show the row's result themselves — echoing it as raw above would double it up */
const SELF_RAW = new Set(['file_list', 'file_read', 'file_search', 'shell_run', 'shell_check', 'web_fetch', 'web_search']);

const ICONS: Record<string, typeof CalendarDays> = {
	schedule_query: CalendarDays,
	todo_query: SquareCheckBig,
	note_search: FileText,
	file_list: FolderTree,
	file_read: FileText,
	file_search: Search,
	shell_run: Terminal,
	shell_check: ShieldCheck,
	web_fetch: Globe,
	web_search: Search
};

function formatJson(raw: string): string {
	try { return JSON.stringify(JSON.parse(raw), null, 1); } catch { return raw; }
}

/** One tool call row: status line (icon + name + summary) + expandable detail.
 * failed/denied rows open by default — "why did it fail" is the first question; once the
 * user toggles, their choice wins (manual overrides the derived default). */
export function ToolRowView({ row }: { row: ConvRow }) {
	const [manual, setManual] = useState<boolean | null>(null);
	if (row.kind !== 'tool') { return null; }
	const open = manual ?? (row.status === 'fail' || row.status === 'denied');
	const toggle = () => setManual(!open);
	const Icon = row.status === 'running' ? LoaderCircle
		: row.status === 'ok' ? (ICONS[row.name ?? ''] ?? Wrench)
		: row.status === 'denied' ? Ban
		: CircleX;
	const Custom = row.status === 'ok' ? RENDERERS[row.name ?? ''] : undefined;
	const hideRaw = row.status === 'ok' && SELF_RAW.has(row.name ?? '');
	const arg = inlineArg(row);
	return (
		<div className="tr-wrap">
			<div className={`tool-line ${row.status} trace-toggle`} onClick={toggle} title="点击查看调用详情">
				<span className={`tool-icon ${row.status === 'running' ? 'spin' : ''}`}><Icon size={14} /></span>
				<code>{row.name}</code>
				{arg && <code className="tool-arg" title={arg}>{arg}</code>}
				{row.status === 'running'
					? <span className="tool-dim">执行中…</span>
					: <span className="tool-dim">{row.summary}{row.ms != null ? ` · ${row.ms}ms` : ''}</span>}
				<span className="trace-caret">{open ? '▾' : '▸'}</span>
			</div>
			{open && (
				<div className="trace-detail">
					<div className="trace-row"><span className="trace-k">参数</span><code>{formatJson(row.argsJson ?? '{}')}</code></div>
					{row.result != null && !hideRaw && <div className="trace-row"><span className="trace-k">原始结果</span><span className="tr-raw">{row.result}</span></div>}
					{row.ms != null && <div className="trace-row"><span className="trace-k">耗时</span><span>{row.ms}ms · {row.status}</span></div>}
					{Custom && <div className="trace-result"><Custom row={row} /></div>}
				</div>
			)}
		</div>
	);
}
