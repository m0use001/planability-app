import { useEffect, useRef, useState } from 'react';
import { Camera, Home, Link2, Link2Off, X } from 'lucide-react';
import type { ApprovalRequest, ConvRow } from '@shared/chat';
import type { CaptureMode, DraftAttachment, DraftState, FloatEvent, FloatStateInfo } from '@shared/float';
import type { DesktopConfig } from '@shared/ipc';
import { ApprovalCardView, ChatComposer, ChatTranscript, useRowActions, type SlashItem } from '../chat/ChatPieces';
import { usePaneDrag } from './usePaneDrag';

/* The floating group's React content (M21 悬浮窗, pc-a). One page serves all three panes;
 * main/float/host.ts loads the same float.html into three frameless windows WITHOUT a role
 * parameter, and the pane sizes are fixed and distinct (input 132 / menu 48 / output 320),
 * so the role comes from the window height. If the contract later grows a `?role=` query
 * this is a one-line swap. The draft is OWNED by the main process (shared/float.ts): this
 * page renders it and writes through with the revision rule — it never treats local state
 * as the source of truth, or collapsing the input would lose what the user typed. */

export type FloatRole = 'input' | 'menu' | 'output';

export function detectRole(): FloatRole {
	const h = window.innerHeight;
	return h <= 70 ? 'menu' : h <= 200 ? 'input' : 'output';
}

type FloatPayload = FloatEvent & { state?: FloatStateInfo };

export function FloatPane({ role }: { role: FloatRole }) {
	const [state, setState] = useState<FloatStateInfo | null>(null);

	useEffect(() => {
		window.planability.float.state().then(setState).catch(() => undefined);
		return window.planability.float.onFloat((e: FloatPayload) => { if (e.state) { setState(e.state); } });
	}, []);

	const sessionId = state && state.mode.phase !== 'docked' ? state.mode.sessionId : '';
	if (role === 'menu') { return <MenuPane state={state} sessionId={sessionId} />; }
	if (role === 'input') { return <InputPane sessionId={sessionId} state={state} />; }
	return <OutputPane state={state} />;
}

/** 拖拽把手（三窗各一）。竖条给菜单（row 布局绝对定位在左边），横条给输入/输出（column 顶部）。
 * 脱钩/锚点语义见 usePaneDrag 头注——把手指针逻辑都在那里，这里只负责摆位置。 */
function Grip({ role, state, vertical = false }: { role: FloatRole; state: FloatStateInfo | null; vertical?: boolean }) {
	const linked = !!state?.layout?.[role]?.linked;
	const isAnchor = state?.anchor === role;
	const drag = usePaneDrag(role, linked, isAnchor);
	const hint = isAnchor || !linked
		? '拖动移动（成组时其余跟随）'
		: '拖动移动（链接中：按下会先让本窗脱钩）';
	return (
		<div
			className={`float-grip ${vertical ? 'float-grip-v' : 'float-grip-h'}`}
			title={hint}
			onPointerDown={drag.onPointerDown}
			onPointerMove={drag.onPointerMove}
			onPointerUp={drag.onPointerUp}
			onPointerCancel={drag.onPointerCancel}
		/>
	);
}

// ---------------- menu pane: 3 mode radios + shutter + link toggle + back ----------------

const MODES: { k: CaptureMode; label: string; hint: string }[] = [
	{ k: 'full', label: '全屏', hint: '截取整个屏幕' },
	{ k: 'rect', label: '矩形', hint: '拖动框选一块区域' },
	{ k: 'window', label: '窗口', hint: '截取一个窗口' },
];

function MenuPane({ state, sessionId }: { state: FloatStateInfo | null; sessionId: string }) {
	const [busy, setBusy] = useState(false);
	const [note, setNote] = useState('');
	const [targets, setTargets] = useState<{ id: string; title: string; thumb?: Buffer }[] | null>(null);

	const flash = (msg: string) => { setNote(msg); setTimeout(() => setNote(''), 4000); };

	const shoot = async () => {
		if (!sessionId || busy) { return; }
		setBusy(true);
		try {
			const r = await window.planability.float.shutter(sessionId);
			if (r.needTarget) { setTargets(await window.planability.float.listTargets()); }
			else if (!r.ok && r.error) { flash(r.error); }
		} catch (e) { flash(String((e as Error)?.message ?? e)); } finally { setBusy(false); }
	};

	if (!sessionId) { return <div className="float-pane float-menu"><span className="float-note">未在悬浮模式</span></div>; }
	return (
		<div className="float-pane float-menu">
			<Grip role="menu" state={state} vertical />
			{MODES.map(m => (
				<button key={m.k} className={state?.captureMode === m.k ? 'on' : ''} title={m.hint}
					onClick={() => void window.planability.float.modeSet(m.k)}>{m.label}</button>
			))}
			<button className="float-shutter" title={state?.captureMode === 'window' ? '截取所选窗口' : '按当前方式截图'}
				disabled={busy || !state?.shotSupported} onClick={() => void shoot()}><Camera size={14} /></button>
			<button className={state?.allLinked ? 'on' : ''} title={state?.allLinked ? '拆开（三窗独立拖动）' : '链接成组（拖一个其余跟随）'}
				onClick={() => void window.planability.float.setLinked('all', !state?.allLinked)}>
				{state?.allLinked ? <Link2 size={14} /> : <Link2Off size={14} />}
			</button>
			<span style={{ flex: 1 }} />
			{note && <span className="float-note-inline">{note}</span>}
			<button title="回主窗口（浮窗收起，草稿保留）" onClick={() => void window.planability.float.exit()}><Home size={14} /></button>
			{targets && (
				<div className="float-targets">
					<div className="float-targets-head">
						<span>选择要截取的窗口</span>
						<button onClick={() => setTargets(null)}><X size={12} /></button>
					</div>
					{targets.map(t => (
						<button
							key={t.id}
							className="float-target"
							title={`截取：${t.title || '（无标题窗口）'}`}
							onClick={async () => {
								// 通道已在（pc-b 的 FLOAT.shutterWindow）：needTarget 的闭环——选择即捕获。
								// 成功后 host 广播 {type:'shot'}，输入窗草稿经 onFloat 自动带上附件 chip。
								if (!sessionId) { return; }
								const r = await window.planability.float.shutterWindow(sessionId, t.id);
								if (r.ok) { setTargets(null); }
								else { flash(r.error ?? '截取该窗口失败'); }
							}}
						>
							{t.thumb && <img alt="" src={thumbUrl(t.thumb)} />}
							<span>{t.title || '（无标题窗口）'}</span>
						</button>
					))}
				</div>
			)}
		</div>
	);
}

/** Attachments ride as paths (Q4=A): the thumbnail is rendered straight from the local file,
 * never sent anywhere — if the renderer cannot read it, the chip degrades to the name. */
function thumbUrl(bytes: Buffer): string {
	let bin = '';
	for (const b of bytes) { bin += String.fromCharCode(b); }
	return 'data:image/png;base64,' + btoa(bin);
}

// ---------------- input pane: draft-backed composer + attachment chips ----------------

function InputPane({ sessionId, state }: { sessionId: string; state: FloatStateInfo | null }) {
	const f = window.planability.float;
	const [draft, setDraft] = useState<DraftState>({ text: '', attachments: [], revision: 0, byWindow: 0, updatedAt: 0 });
	const draftRef = useRef(draft);
	const timer = useRef<number | null>(null);
	const [running, setRunning] = useState(false);

	useEffect(() => {
		if (!sessionId) { return; }
		let alive = true;
		const refresh = () => {
			// a pending local write owns the text until it lands; adopting the store copy now
			// would clobber the keystrokes that write carries
			if (timer.current) { return; }
			f.draftGet(sessionId).then(d => { if (alive) { draftRef.current = d; setDraft(d); } }).catch(() => undefined);
		};
		refresh();
		const off = f.onFloat((e: FloatPayload) => { if (e.type === 'draft' || e.type === 'shot') { refresh(); } });
		return () => { alive = false; off(); };
	}, [sessionId]);

	const adopt = (d: DraftState | undefined) => {
		if (d) { draftRef.current = d; setDraft(d); }
	};

	const writeDraft = (text: string) => {
		setDraft(prev => ({ ...prev, text })); // local echo first: typing must never wait on IPC
		if (timer.current) { clearTimeout(timer.current); }
			timer.current = window.setTimeout(async () => {
				timer.current = null;
				const cur = draftRef.current;
				try {
					const r = await f.draftSet(sessionId, text, cur.attachments, cur.revision);
					// 契约（pc-b 2f41f8d 起）：成功给 state、过期给 current、绝不同给
					if (r.ok) { adopt(r.state ?? await f.draftGet(sessionId)); }
					else if (r.current) { adopt(r.current); }
				} catch { /* window closing */ }
			}, 140);
	};

	const send = async () => {
		const text = draftRef.current.text.trim();
		if (!text || !sessionId || running) { return; }
		setRunning(true);
		try {
			await window.planability.chat.send(text);
			// 发送即消费草稿文本；附件保留——attachment→消息路径还没接线（见 agents_log）
			const r = await f.draftSet(sessionId, '', draftRef.current.attachments, draftRef.current.revision);
			if (r.ok) { adopt(r.state ?? await f.draftGet(sessionId)); }
			else if (r.current) { adopt(r.current); }
		} catch { /* main pushes an error row */ } finally { setRunning(false); }
	};

	const removeAttachment = async (p: string) => {
		try { adopt(await f.draftRemove(sessionId, p)); } catch { /* non-fatal */ }
	};

	const sendText = async (t: string) => { await window.planability.chat.send(t).catch(() => undefined); };

	if (!sessionId) { return <div className="float-pane float-input"><div className="float-note">未在悬浮模式</div></div>; }
	return (
		<div className="float-pane float-input">
			<Grip role="input" state={state} />
			{draft.attachments.length > 0 && (
				<div className="float-chips">
					{draft.attachments.map(a => <AttChip key={a.path} a={a} onRemove={() => void removeAttachment(a.path)} />)}
				</div>
			)}
			<ChatComposer
				value={draft.text}
				onChange={writeDraft}
				onSubmit={() => void send()}
				running={running}
				slashItems={SLASH_FLOAT(sendText)}
				placeholderIdle="发给工作台…（Enter 发送 · Shift+Enter 换行）"
				placeholderRunning="正在执行——这里输入即排队插话"
			/>
		</div>
	);
}

/** Floating 的 slash 子集：/new 会新建会话、与浮窗绑定的会话脱钩，刻意不提供。 */
function SLASH_FLOAT(sendText: (t: string) => Promise<void>): SlashItem[] {
	return [
		{ cmd: '/undo', hint: '撤销最近一次写操作', run: () => void sendText('请撤销我最近一次的写操作（调用 undo_last_write）。') },
		{ cmd: '/stats', hint: '统计数据概览', run: () => void sendText('统计我现在有哪些数据，分别列出各类条数。') },
		{ cmd: '/clear', hint: '清空当前会话', run: () => { void window.planability.chat.clear().catch(() => undefined); } }
	];
}

function AttChip({ a, onRemove }: { a: DraftAttachment; onRemove(): void }) {
	const [imgOk, setImgOk] = useState(true);
	const url = 'file:///' + a.path.replace(/\\/g, '/').replace(/^\/+/, '');
	return (
		<span className="float-chip" title={a.path}>
			{imgOk && <img alt="" src={url} onError={() => setImgOk(false)} />}
			<span className="float-chip-name">{a.path.split(/[\\/]/).pop()}</span>
			{a.width ? <span className="float-chip-dim">{a.width}×{a.height}</span> : null}
			<button title="移除附件" onClick={onRemove}><X size={11} /></button>
		</span>
	);
}

// ---------------- output pane: same transcript, polled until chat events are broadcast ----------------

function OutputPane({ state }: { state: FloatStateInfo | null }) {
	const [rows, setRows] = useState<ConvRow[]>([]);
	const [cfg, setCfg] = useState<DesktopConfig>({});
	const [pending, setPending] = useState<ApprovalRequest | null>(null);
	const [running, setRunning] = useState(false);
	const scrollRef = useRef<HTMLDivElement>(null);

	const sendText = async (t: string) => { await window.planability.chat.send(t).catch(() => undefined); };
	// 行操作与主窗共用同一组件；浮窗内 send 直接走同一条 chat 通道
	const { renderRowActions } = useRowActions(rows, running, sendText);

	useEffect(() => {
		window.planability.configGet().then(setCfg).catch(() => undefined);
		// 轮询而非订阅：chat 行事件目前只广播给主窗（index.ts 六处 send）——pc-b 广播后可换成 onChat
		const pull = () => {
			window.planability.chat.getRows().then(rs => {
				setRows(rs);
				const last = rs[rs.length - 1];
				setRunning(rs.some(r => r.kind === 'tool' && r.status === 'running') || (last?.kind === 'assistant' && !last?.done));
			}).catch(() => undefined);
		};
		pull();
		const t = setInterval(pull, 800);
		const offA = window.planability.chat.onApproval(req => setPending(req));
		return () => { clearInterval(t); offA(); };
	}, []);

	useEffect(() => {
		const el = scrollRef.current;
		if (el) { el.scrollTop = el.scrollHeight; }
	}, [rows.length, pending]);

	const resolveApproval = async (allow: boolean, allowAlways = false, args?: Record<string, unknown>) => {
		if (!pending) { return; }
		const id = pending.id;
		setPending(null);
		await window.planability.chat.resolveApproval(id, { allow, allowAlways, args }).catch(() => undefined);
	};

	return (
		<div className="float-pane float-output">
			<Grip role="output" state={state} />
			<div className="float-scroll" ref={scrollRef}>
				<ChatTranscript rows={rows} thinkMode={cfg.thinkMode ?? 'adaptive'}
					cardsEnabled={cfg.plugins?.cards !== false} renderRowActions={renderRowActions} onSend={sendText} />
			</div>
			{pending && <ApprovalCardView pending={pending} onResolve={resolveApproval} />}
		</div>
	);
}
