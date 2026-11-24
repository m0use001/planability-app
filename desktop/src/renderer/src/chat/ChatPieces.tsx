import { useEffect, useRef, useState, type ReactNode, type MutableRefObject } from 'react';
import { marked } from 'marked';
import {
	AtSign, Check, Copy, LoaderCircle, Paperclip, RefreshCw, RotateCcw, Slash, TriangleAlert,
	Minimize2
} from 'lucide-react';
import type { ApprovalRequest, ConvRow } from '@shared/chat';
import { FenceBlock, parseSegments } from '../pages/ChatCards';
import { ToolRowView } from '../pages/ToolRenderers';
import { FilledButtonM3, OutlinedButtonM3, OutlinedIconButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';

/* Shared chat building blocks, extracted from pages/Chat.tsx so the float output/input panes
 * render the SAME components instead of a forked second behaviour (M21 悬浮窗硬要求).
 * Chat.tsx owns session/side-bar state; these pieces are pure presentation + local state. */

marked.setOptions({ breaks: true, gfm: true });

export function renderMd(md: string): { __html: string } {
	try { return { __html: marked.parse(md) as string }; } catch { return { __html: md.replace(/</g, '&lt;') }; }
}

/** Reasoning fold with live elapsed-seconds while the segment streams (ZCode parity). */
export function ReasoningBlock({ row, defaultOpen }: { row: ConvRow; defaultOpen: boolean }) {
	const [sec, setSec] = useState(0);
	const startRef = useRef(Date.now());
	useEffect(() => {
		if (row.done) { return; }
		const t = setInterval(() => setSec(Math.floor((Date.now() - startRef.current) / 1000)), 1000);
		return () => clearInterval(t);
	}, [row.done]);
	return (
		<details className="reasoning-block" open={defaultOpen}>
			<summary>
				思考过程
				{!row.done && <span className="rb-time">· {sec > 0 ? `${sec}s` : '…'}</span>}
			</summary>
			<div className="reasoning-body">{row.reasoning}</div>
		</details>
	);
}

function formatJsonSafe(raw: string): string {
	try { return JSON.stringify(JSON.parse(raw), null, 1); } catch { return raw; }
}

/** The approval card's editable textarea assumes JSON args; anything else must not go in it. */
export function isJsonArgs(raw: string): boolean {
	try {
		const v = JSON.parse(raw);
		return !!v && typeof v === 'object' && !Array.isArray(v);
	} catch { return false; }
}

/** Read-only rendering of a non-JSON approval payload: M18's file_write sends
 * "将写入 <path>（…，+N / -M 行）：\n<unified diff>", clamped like the file views. */
function ApprovalPreview({ raw }: { raw: string }) {
	const [open, setOpen] = useState(false);
	const bodyRef = useRef<HTMLDivElement>(null);
	// fold only when the clamp actually clips: a size threshold would either hide a
	// short diff's toggle or offer one for content that already fits
	const [clipped, setClipped] = useState(false);
	useEffect(() => {
		const el = bodyRef.current;
		if (el) { setClipped(el.scrollHeight > el.clientHeight + 2); }
	}, [raw]);
	const nl = raw.indexOf('\n');
	const head = nl < 0 ? raw : raw.slice(0, nl);
	const body = nl < 0 ? '' : raw.slice(nl + 1);
	const lines = body ? body.split('\n') : [];
	const isDiff = head.startsWith('将写入');
	// split on the stat so "+3" / "-1" can be coloured without parsing the sentence around it
	const stat = isDiff ? head.split(/([+-]\d+) \/ (-\d+)/) : [];
	return (
		<div className="apv">
			<div className="apv-head">
				{stat.length === 4 ? (
					<>{stat[0]}<b className="apv-add">{stat[1]}</b> / <b className="apv-del">{stat[2]}</b>{stat[3]}</>
				) : head}
			</div>
			{lines.length > 0 && (
				<>
					<div className="fv-meta">
						<span className="fv-note">{isDiff ? `${lines.length} 行差异` : '参数原文'}</span>
						{clipped && (
							<button className="fv-toggle" onClick={() => setOpen(!open)}>{open ? '收起' : '展开'}</button>
						)}
					</div>
					<div ref={bodyRef} className={`apv-body${open ? ' open' : ''}`}>
						{lines.map((l, i) => (
							<div key={i} className={`apv-line ${l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : l.trimStart().startsWith('…') ? 'gap' : 'ctx'}`}>{l || ' '}</div>
						))}
					</div>
				</>
			)}
			<div className="apv-note">
				{isDiff ? '改动差异 · 只读预览（差异由主进程计算，参数不可在此编辑）' : '参数只读 · 原文已非可编辑 JSON，如需改动请在对话里说明'}
			</div>
		</div>
	);
}

/** The approval card, including the confirm-card editable args and the M18 diff preview.
 * Resolution is delegated: the host window owns the call (chat.resolveApproval) and receives
 * the possibly-edited args. */
export function ApprovalCardView({ pending, onResolve }: {
	pending: ApprovalRequest;
	onResolve(allow: boolean, allowAlways?: boolean, args?: Record<string, unknown>): void;
}) {
	const [argsEdit, setArgsEdit] = useState('');
	const [argsErr, setArgsErr] = useState('');
	useEffect(() => { setArgsEdit(pending?.argsJson ?? ''); setArgsErr(''); }, [pending]);
	const resolve = (allow: boolean, allowAlways = false) => {
		let args: Record<string, unknown> | undefined;
		// A non-JSON payload (M18 file_write sends a diff preview) has no editable args —
		// parsing it would always throw. Only a JSON payload can be edited before allowing.
		if (allow && pending.risk === 'confirm' && isJsonArgs(pending.argsJson)
			&& argsEdit.trim() && argsEdit.trim() !== pending.argsJson.trim()) {
			try {
				args = JSON.parse(argsEdit) as Record<string, unknown>;
			} catch {
				// inline, not window.alert: an alert here blocks the renderer for as long as it
				// is up, which reads as a frozen app (and the card would silently auto-deny)
				setArgsErr('参数 JSON 格式有误，无法放行。请修正后再允许。');
				return;
			}
		}
		onResolve(allow, allowAlways, args);
	};
	return (
		<div className={`approval-card ${pending.risk}`}>
			<div className="approval-head">
				<span className={`approval-badge ${pending.risk}`}>
					{pending.risk === 'danger' ? '危险操作' : '需要你确认'}
				</span>
				<strong>AI 想执行{pending.risk === 'danger' ? '危险' : '写'}操作</strong>
			</div>
			<div className="approval-tool"><code>{pending.toolName}</code></div>
			{!isJsonArgs(pending.argsJson) ? (
				<ApprovalPreview raw={pending.argsJson} />
			) : pending.risk === 'danger' ? (
				<pre className="approval-args">{formatJsonSafe(pending.argsJson)}</pre>
			) : (
				<textarea
					className="approval-args-edit"
					value={argsEdit}
					onChange={e => { setArgsEdit(e.target.value); setArgsErr(''); }}
					spellCheck={false}
					title="可编辑：修改参数后再允许（JSON）"
				/>
			)}
			{argsErr && <div className="approval-err">{argsErr}</div>}
			<div className="approval-actions">
				<button className="btn-ghost" onClick={() => resolve(false)}>拒绝</button>
				<div style={{ flex: 1 }} />
				{pending.risk === 'confirm' && (
					<button className="btn-ghost" onClick={() => resolve(true, true)}>总是允许</button>
				)}
				<button className="btn" onClick={() => resolve(true)}>允许</button>
			</div>
		</div>
	);
}

// ---- row actions (copy / regenerate / undo write) ----
const WRITE_OK = new Set(['schedule_create', 'schedule_delete', 'todo_create', 'note_create', 'memory_add', 'memory_forget']);

/** Row actions for the trailing completed assistant row. Shared by the main chat and the
 * float output pane so both offer the same actions off the same state shape. */
export function useRowActions(rows: ConvRow[], running: boolean, sendText: (t: string) => Promise<void>) {
	const [copiedRowId, setCopiedRowId] = useState<string | null>(null);

	const copyRow = async (rowId: string, text: string) => {
		try { await navigator.clipboard.writeText(text); } catch { /* clipboard denied */ }
		setCopiedRowId(rowId);
		setTimeout(() => setCopiedRowId(prev => (prev === rowId ? null : prev)), 1500);
	};

	const regen = async () => {
		if (running) { return; }
		let userIdx = -1;
		for (let i = rows.length - 1; i >= 0; i--) {
			const r = rows[i];
			if (r.kind === 'user') { userIdx = i; break; }
			if (r.kind === 'assistant' && !r.done) { return; }
		}
		if (userIdx < 0) { return; }
		const userText = rows[userIdx].text ?? '';
		// main trims history + rows and pushes rows_set; the resend re-adds the user row
		await window.planability.chat.rollbackUsers(1).catch(() => undefined);
		await sendText(userText);
	};

	const undoWrite = async () => { await sendText('请撤销我最近一次的写操作（调用 undo_last_write）。'); };

	/** Row actions render on the trailing completed assistant row only; that row keeps them
	 * faintly visible (no hover required) — hover/keyboard focus lifts them to full. */
	const renderRowActions = (i: number, row: ConvRow) => {
		const isLast = i === rows.length - 1;
		if (!isLast || !row.done || row.error || !row.text) { return null; }
		const canUndo = rows.some(r => r.kind === 'tool' && r.status === 'ok' && WRITE_OK.has(r.name ?? ''));
		return (
			<div className="msg-actions persist">
				<button className="msg-act" title="复制" onClick={() => void copyRow(row.rowId, row.text ?? '')}>
					{copiedRowId === row.rowId ? <Check size={13} /> : <Copy size={13} />}
				</button>
				{!running && (
					<button className="msg-act" title="重新生成" onClick={regen}>
						<RefreshCw size={13} /> 重新生成
					</button>
				)}
				{canUndo && !running && (
					<button className="msg-act danger" title="撤销本轮写操作" onClick={undoWrite}>
						<RotateCcw size={13} /> 撤销写操作
					</button>
				)}
			</div>
		);
	};

	return { renderRowActions, undoWrite };
}

/** The conversation rows themselves: user/system/error bubbles, tool rows, assistant rows
 * with reasoning + markdown + component fences + streaming cursor. Empty states and the
 * scroller belong to the host page. */
export function ChatTranscript({ rows, thinkMode, cardsEnabled, renderRowActions, onSend }: {
	rows: ConvRow[];
	thinkMode: 'adaptive' | 'on' | 'off';
	cardsEnabled: boolean;
	renderRowActions?(i: number, row: ConvRow): ReactNode;
	onSend(text: string): void;
}) {
	return (
		<>
			{rows.map((row, i) => {
				if (row.kind === 'user') {
					return <div className="bubble-user" key={row.rowId}>{row.text}</div>;
				}
				if (row.kind === 'system') {
					return <div className="bubble-system" key={row.rowId}>{row.text}</div>;
				}
				if (row.kind === 'error') {
					return (
						<div className="chat-error" key={row.rowId}>
							<TriangleAlert size={14} />
							<span>{row.text}</span>
						</div>
					);
				}
				if (row.kind === 'tool') {
					return <ToolRowView key={row.rowId} row={row} />;
				}
				// assistant row — skip fully empty settled rows (migration artifacts)
				if (row.done && !row.text && !row.reasoning && !row.error) { return null; }
				const hasOpenTool = rows.slice(i + 1).some(r => r.kind === 'tool' && r.status === 'running');
				return (
					<div className="msg-assistant" key={row.rowId}>
						{row.reasoning && thinkMode !== 'off' && (
							<ReasoningBlock key={row.rowId} row={row} defaultOpen={thinkMode === 'on' ? true : !row.done} />
						)}
						{row.text && (row.done && cardsEnabled
							? parseSegments(row.text).map((seg, k) => seg.type === 'md'
								? (seg.text.trim() && <div className="md" key={k} dangerouslySetInnerHTML={renderMd(seg.text)} />)
								: <FenceBlock key={k} seg={seg} onSend={onSend} />)
							: <div className="md" dangerouslySetInnerHTML={renderMd(row.text)} />
						)}
						{!row.done && !row.error && !row.text && !hasOpenTool && (
							<div className="tool-line"><span className="tool-icon spin"><LoaderCircle size={14} /></span><span className="tool-dim">思考中…</span></div>
						)}
						{!row.done && row.text && <span className="stream-cursor">▌</span>}
						{row.error && (
							<div className="chat-error">
								<TriangleAlert size={14} />
								<span>{row.error}</span>
							</div>
						)}
						{renderRowActions?.(i, row)}
					</div>
				);
			})}
		</>
	);
}

export interface SlashItem { cmd: string; hint: string; run(): void }

const AT_RE = /@(\S*)$/;
const AT_DOMAINS: { key: string; label: string; collection: 'schedules' | 'todos' | 'notes' | 'memories' | 'projects' }[] = [
	{ key: '日程', label: '日程', collection: 'schedules' },
	{ key: '待办', label: '待办', collection: 'todos' },
	{ key: '笔记', label: '笔记', collection: 'notes' },
	{ key: '记忆', label: '记忆', collection: 'memories' },
	{ key: '项目', label: '项目', collection: 'projects' }
];

/** The input bar: textarea + slash popover + @ linking + attach + send/stop (+ optional
 * shrink-to-float). Identical behaviour in the main chat and the float input pane. */
export function ChatComposer(props: {
	value: string;
	onChange(v: string): void;
	onSubmit(): void;
	running: boolean;
	onStop?(): void;
	/** Show the 「收起为悬浮窗」 entry (main window only). */
	onShrink?(): void;
	slashItems?: SlashItem[];
	inputRef?: MutableRefObject<HTMLTextAreaElement | null>;
	placeholderRunning?: string;
	placeholderIdle?: string;
}) {
	const { value, onChange, onSubmit, running } = props;
	const slashMode = !!props.slashItems && value.startsWith('/') && !value.includes('\n');
	const atMode = value.includes('@') && !value.includes('\n') && AT_RE.test(value);

	const runSlash = (item: SlashItem) => {
		onChange('');
		void item.run();
	};

	const attach = async () => {
		try {
			const f = await window.planability.filePick();
			if (!f) { return; }
			const block = `【附件：${f.name}${f.truncated ? '（已截断）' : ''}】\n${f.content}\n\n`;
			onChange((value ? value + '\n' : '') + block);
		} catch { /* cancelled */ }
	};

	const pickAt = async (d: typeof AT_DOMAINS[number]) => {
		try {
			const items = await window.planability.list<any>(d.collection);
			const lines = items.slice(0, 10).map(x =>
				`- ${(x.title ?? x.name ?? '').slice(0, 60)}${x.date ? `（${x.date}）` : ''}${x.status === 'done' ? ' ✓' : ''}`
			);
			const block = `[@${d.label}上下文，共 ${items.length} 条]\n${lines.join('\n') || '- （空）'}\n\n`;
			onChange(value.replace(AT_RE, block));
		} catch { /* non-fatal */ }
	};

	const onKeyDown = (e: React.KeyboardEvent) => {
		if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSubmit(); }
	};

	return (
		<div className="chat-input-bar">
			<div className="chat-input-wrap">
				{slashMode && props.slashItems && (
					<div className="pop-menu input-menu">
						<div className="pop-title"><Slash size={11} style={{ verticalAlign: -1.5 }} /> 快捷指令</div>
						{props.slashItems.filter(x => x.cmd.startsWith(value.split(/\s/)[0]) || value === '/').map(item => (
							<button key={item.cmd} className="pop-item" onClick={() => runSlash(item)}>
								<strong>{item.cmd}</strong><span className="pop-hint">{item.hint}</span>
							</button>
						))}
					</div>
				)}
				{atMode && (
					<div className="pop-menu input-menu">
						<div className="pop-title"><AtSign size={11} style={{ verticalAlign: -1.5 }} /> 勾连数据域（拉取前 10 条进上下文）</div>
						{AT_DOMAINS.filter(d => d.key.startsWith(value.split('@')[1] ?? '')).map(d => (
							<button key={d.key} className="pop-item" onClick={() => void pickAt(d)}>
								{d.label}
							</button>
						))}
					</div>
				)}
				<textarea
					ref={props.inputRef}
					value={value}
					onChange={e => onChange(e.target.value)}
					onKeyDown={onKeyDown}
					placeholder={running
						? (props.placeholderRunning ?? 'AI 正在执行——直接输入即排队插话（Enter 提交）')
						: (props.placeholderIdle ?? '向工作台提问…（Enter 发送 · Shift+Enter 换行 · / 指令 · @ 勾连）')}
					rows={Math.min(5, Math.max(1, value.split('\n').length))}
				/>
			</div>
			{running ? (
				<>
					<FilledButtonM3 onClick={onSubmit} disabled={!value.trim()}>插话</FilledButtonM3>
					{props.onStop && <OutlinedButtonM3 onClick={props.onStop}>停止</OutlinedButtonM3>}
				</>
			) : (
					<>
						{props.onShrink && (
							<OutlinedIconButtonM3 title="收起为悬浮窗（输入框独立到桌面）" onClick={props.onShrink}>
								<IconM3><Minimize2 size={17} /></IconM3>
							</OutlinedIconButtonM3>
						)}
						<OutlinedIconButtonM3 title="附加文件（文本）" onClick={() => void attach()}>
							<IconM3><Paperclip size={17} /></IconM3>
						</OutlinedIconButtonM3>
						<FilledButtonM3 onClick={onSubmit} disabled={!value.trim()}>发送</FilledButtonM3>
					</>
				)}
		</div>
	);
}
