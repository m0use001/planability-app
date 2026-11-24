import { useEffect, useRef, useState } from 'react';
import {
	Plus, Pin, Trash2, Users, Gauge,
	ChartColumn, Zap, Brain, Cable, Puzzle, MessageSquare, ArrowDownToLine, LoaderCircle,
	Search, X, Pencil, Bot, Workflow, Wrench, FolderInput
} from 'lucide-react';
import type { ApprovalRequest, ChatEvent, ConvRow } from '@shared/chat';
import type { DesktopConfig, PermMode, SessionMeta } from '@shared/ipc';
import type { Assistant, Project } from '@shared/types';
import { Modal, PageHeader } from '../components/ui';
import { FilledTonalButtonM3, FabM3 } from '../components/m3/buttons';
import { SuggestionChipM3, FilterChipM3 } from '../components/m3/chips';
import { TextButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { ListRow, SideNav } from '../components/m3/chrome';
import './chat.css';
import { useNavigate } from '../router';
import { useCollection } from '../hooks';
import { ApprovalCardView, ChatComposer, ChatTranscript, useRowActions } from '../chat/ChatPieces';
import { ContextBudgetPanel } from '../chat/ContextPanel';

const MODEL_PRESETS = ['mimo-v2.6-flash', 'mimo-v2.5-flash'];

const PERM_LABEL: Record<PermMode, { label: string; hint: string }> = {
	ro: { label: '权限·计划模式', hint: '只读，写操作直接拒绝' },
	confirm: { label: '权限·变更前确认', hint: '写操作弹确认卡（默认）' },
	auto: { label: '权限·自动执行', hint: '写操作放行，危险操作仍确认' },
	full: { label: '权限·完全访问', hint: '写操作放行，危险操作仍确认' }
};

function greeting(): string {
	const h = new Date().getHours();
	if (h < 6) { return '夜深了'; }
	if (h < 12) { return '早上好'; }
	if (h < 14) { return '中午好'; }
	if (h < 18) { return '下午好'; }
	return '晚上好';
}

function relTime(ts: number): string {
	const diff = Date.now() - ts;
	if (diff < 60_000) { return '刚刚'; }
	if (diff < 3600_000) { return `${Math.floor(diff / 60_000)} 分钟前`; }
	if (diff < 86400_000) { return `${Math.floor(diff / 3600_000)} 小时前`; }
	return new Date(ts).toLocaleDateString('zh-CN');
}

export default function ChatPage() {
	const nav = useNavigate();
	const [rows, setRows] = useState<ConvRow[]>([]);
	const [input, setInput] = useState('');
	const [running, setRunning] = useState(false);
	const [pending, setPending] = useState<ApprovalRequest | null>(null);
	const [approvalErr, setApprovalErr] = useState('');
	/** 运行中的静默 return 统一出口：让「点了没反应」变成一句话（G 类 MEDIUM）。 */
	const [busyNote, setBusyNote] = useState('');
	const say = (m: string) => {
		setBusyNote(m);
		window.setTimeout(() => setBusyNote(''), 3000);
	};
	const [cfg, setCfg] = useState<DesktopConfig>({});
	const [openMenu, setOpenMenu] = useState<'model' | 'perm' | 'assistant' | 'context' | null>(null);
	const [customModel, setCustomModel] = useState('');
	const [sessionState, setSessionState] = useState<{ metas: SessionMeta[]; activeId: string }>({ metas: [], activeId: '' });
	const [assistants, setAssistants] = useState<Assistant[]>([]);
	const [sessQuery, setSessQuery] = useState('');
	const [renameId, setRenameId] = useState('');
	const [renameText, setRenameText] = useState('');
	// M17 实时插话 (P1): thoughts parked while a turn runs + injection gear (Q1).
	const [queue, setQueue] = useState<{ text: string; at: number; injected?: boolean }[]>([]);
	const [inject, setInject] = useState<'nextRound' | 'afterTurn'>('nextRound');
	const scrollRef = useRef<HTMLDivElement>(null);
	const inputRef = useRef<HTMLTextAreaElement>(null);
	const rowsLenRef = useRef(0);
	const [atBottom, setAtBottom] = useState(true);

	const modelLabel = cfg.mainModel || 'mimo-v2.6-flash';
	const permMode: PermMode = cfg.permMode ?? 'confirm';
	const thinkMode: 'adaptive' | 'on' | 'off' = cfg.thinkMode ?? 'adaptive';
	const THINK_LABEL = { adaptive: '思考·自适应', on: '思考·开', off: '思考·关' } as const;
	const cycleThink = () => {
		const order: ('adaptive' | 'on' | 'off')[] = ['adaptive', 'on', 'off'];
		const next = order[(order.indexOf(thinkMode) + 1) % order.length];
		void saveCfg({ thinkMode: next });
	};

	useEffect(() => {
		window.planability.configGet().then(setCfg).catch(() => undefined);
		window.planability.chat.getRows().then(setRows).catch(() => undefined);
		window.planability.chat.enqueueList().then(setQueue).catch(() => undefined);
		window.planability.chat.injectMode().then(setInject).catch(() => undefined);
		const offChat = window.planability.chat.onChat(handleEvent);
		const offApproval = window.planability.chat.onApproval(req => setPending(req));
		return () => { offChat(); offApproval(); };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	/** Pending queue is server-owned — re-sync after the toggle points. */
	const reconcileQueue = async () => {
		try { setQueue(await window.planability.chat.enqueueList()); } catch { /* non-fatal */ }
	};

	/** v2 projection: rows are main-owned; the renderer only folds events into a list. */
	function handleEvent(e: ChatEvent) {
		if (e.type === 'row_user_queued') {
			// P1: park the thought visibly; flip to 已注入 when it actually reached the model
			if (e.injected) {
				setQueue(prev => {
					const i = prev.findIndex(q => q.text === e.text && !q.injected);
					if (i < 0) { return [...prev, { text: e.text, at: e.at, injected: true }].slice(-20); }
					const next = [...prev];
					next[i] = { ...next[i], injected: true };
					return next;
				});
			} else {
				setQueue(prev => [...prev, { text: e.text, at: e.at }].slice(-20));
			}
			return;
		}
		if (e.type === 'row_upsert') {
			setRows(prev => {
				const i = prev.findIndex(r => r.rowId === e.row.rowId);
				if (i < 0) { return [...prev, e.row]; }
				const next = [...prev];
				next[i] = e.row;
				return next;
			});
		} else if (e.type === 'row_delta') {
			setRows(prev => prev.map(r => r.rowId === e.rowId
				? { ...r, [e.field]: (r[e.field] ?? '') + e.value }
				: r));
		} else if (e.type === 'rows_set') {
			setRows(e.rows);
		}
		// done/error: main already settled the row and pushed the upsert
	}

	const saveCfg = async (patch: DesktopConfig) => {
		setCfg(prev => ({ ...prev, ...patch }));
		try { await window.planability.configSet(patch); } catch { /* non-fatal */ }
	};

	const reloadSessions = async () => {
		try { setSessionState(await window.planability.sessionList()); } catch { /* non-fatal */ }
	};

	// 会话按项目分组（用户需求，对齐安卓 WorkbenchDrawer；数据面 = pc-b c551341 的 projectId）。
	// 分组与排序我定（他 ⑤ 明确留给我）：置顶浮在分组之上不吃掉（他点名的要求），
	// 未分类永远在场（它是没归档会话的家、也是移动的目标锚点），项目组全部展示（空组带 0 徽标，
	// 否则用户看不见「可以把会话移进去的地方」）；组内保持 updatedAt 倒序（useCollection/sessionList 既有序）。
	const { items: projects } = useCollection<Project>('projects');
	const [moveFor, setMoveFor] = useState<SessionMeta | null>(null);
	const moveSession = async (s: SessionMeta, projectId: string | null) => {
		await window.planability.sessionSetProject(s.id, projectId).catch(() => undefined);
		setMoveFor(null);
		await reloadSessions();
	};

	useEffect(() => {
		void reloadSessions();
		window.planability.list<Assistant>('assistants').then(setAssistants).catch(() => undefined);
	}, []);

	const newChat = async () => {
		if (running) { say('正在执行本回合——结束后再新建会话'); return; }
		try {
			await window.planability.sessionCreate();
			await reloadSessions();
			setRows([]);
			inputRef.current?.focus();
		} catch { /* non-fatal */ }
	};

	const switchSession = async (id: string) => {
		if (running) { say('正在执行本回合——结束后再切换会话'); setOpenMenu(null); return; }
		if (id === sessionState.activeId) { setOpenMenu(null); return; }
		await window.planability.sessionSwitch(id).catch(() => undefined);
		await reloadSessions();
		setRows(await window.planability.chat.getRows().catch(() => [] as ConvRow[]));
		setOpenMenu(null);
		inputRef.current?.focus();
	};

	const removeSession = async (id: string) => {
		if (!window.confirm('删除这个会话及其全部对话？')) { return; }
		await window.planability.sessionRemove(id).catch(() => undefined);
		await reloadSessions();
		setRows(await window.planability.chat.getRows().catch(() => [] as ConvRow[]));
	};

	const pinSession = async (id: string, pinned: boolean) => {
		await window.planability.sessionPin(id, pinned).catch(() => undefined);
		await reloadSessions();
	};

	// inline session rename (Enter commits — guarded against IME composition; Escape cancels)
	const commitRename = async () => {
		const id = renameId;
		const text = renameText.trim();
		setRenameId('');
		if (!id || !text) { return; }
		await window.planability.sessionRename(id, text).catch(() => undefined);
		await reloadSessions();
	};

	// ---- slash commands (undoWrite comes from useRowActions below) ----
	const SLASH = [
		{ cmd: '/new', hint: '新建会话', run: () => newChat() },
		{ cmd: '/undo', hint: '撤销最近一次写操作', run: () => undoWrite() },
		{ cmd: '/stats', hint: '统计数据概览', run: () => sendText('统计我现在有哪些数据，分别列出各类条数。') },
		{ cmd: '/clear', hint: '清空当前会话', run: async () => {
			// G 类 HIGH-3：同页删会话有 confirm，清空却零确认——同级不可恢复操作同级确认。
			if (!window.confirm('清空当前会话的全部消息？（不可恢复，与删除会话同级）')) { return; }
			await window.planability.chat.clear().catch(() => undefined);
		} }
	];

	// scroll anchoring: follow the tail only while the reader is at the bottom (or just
	// sent a message); scrolling up to read history is never yanked back (ZCode parity)
	useEffect(() => {
		const el = scrollRef.current;
		if (!el) { return; }
		const grew = rows.length > rowsLenRef.current;
		rowsLenRef.current = rows.length;
		const last = rows[rows.length - 1];
		if (!grew && !atBottom) { return; }
		if (last?.kind === 'user' || atBottom) {
			el.scrollTo({ top: el.scrollHeight, behavior: last?.kind === 'user' ? 'smooth' : 'auto' });
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [rows]);

	const handleScroll = () => {
		const el = scrollRef.current;
		if (!el) { return; }
		setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 96);
	};

	const jumpToBottom = () => {
		const el = scrollRef.current;
		if (!el) { return; }
		el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
		setAtBottom(true);
	};

	const sendText = async (text: string) => {
		if (!text) { return; }
		if (running) {
			// P1 实时插话: busy turn → the kernel queues it (row_user_queued renders the bubble)
			await window.planability.chat.send(text).catch(() => undefined);
			return;
		}
		setRunning(true);
		try {
			await window.planability.chat.send(text);
		} catch { /* main pushes an error row; nothing to fold in here */ } finally {
			setRunning(false);
			void reconcileQueue();
		}
	};

	// row actions (copy / regenerate / undo write) — shared with the float output pane
	const { renderRowActions, undoWrite } = useRowActions(rows, running, sendText);

	const cycleInject = async () => {
		const next = inject === 'nextRound' ? 'afterTurn' : 'nextRound';
		setInject(next);
		try { const m = await window.planability.chat.setInjectMode(next); setInject(m); } catch { /* non-fatal */ }
	};

	const clearQueue = async () => {
		await window.planability.chat.enqueueClear().catch(() => undefined);
		setQueue([]);
	};

	const send = async () => {
		const text = input.trim();
		if (!text) { return; }
		// no `running` gate here — P1: sendText decides between a real turn and queueing;
		// blocking on running silently swallowed every mid-turn thought (pc-a bug, found in acceptance)
		setInput('');
		await sendText(text);
	};

	const stop = async () => { await window.planability.chat.stop().catch(() => undefined); };

	const resolveApproval = async (allow: boolean, allowAlways = false, args?: Record<string, unknown>) => {
		if (!pending) { return; }
		const id = pending.id;
		// G 类 MEDIUM：先消失再发请求 = 失败被吞成「已处理」。等成功才收卡：
		// 通道拒绝 → 卡片还在 + 错误可见；main 返回 false（未来语义：id 已被 120s 超时
		// 自动拒绝消费掉）→ 同样报出来，而不是让「点了允许其实没生效」静默通过。
		try {
			const r = await window.planability.chat.resolveApproval(id, { allow, allowAlways, args }) as unknown;
			if (r === false) {
				setApprovalErr('该审批已被超时自动拒绝，这次点击没有生效');
				return;
			}
			setPending(null);
			setApprovalErr('');
		} catch (e) {
			setApprovalErr(`审批提交失败：${String((e as Error)?.message ?? e).slice(0, 120)}——卡片保留，可重试`);
		}
	};

	// 收起为悬浮窗：把当前会话的输入/输出搬出主窗（M21 悬浮窗）。主窗在此被隐藏而非销毁。
	const [floatNote, setFloatNote] = useState('');
	const enterFloat = async () => {
		try {
			// R7 的另一半：主窗里已打、还没发的字先入存储，浮窗输入窗 adopt 后才不丢。
			// 只推非空：空串覆盖会把浮窗侧未发送的草稿清掉。
			if (input.trim()) {
				const g = await window.planability.float.draftGet(sessionState.activeId);
				await window.planability.float.draftSet(sessionState.activeId, input, g.attachments ?? [], g.revision);
			}
			const r = await window.planability.float.enter(sessionState.activeId);
			if (!r.ok && r.reason) { setFloatNote(r.reason); setTimeout(() => setFloatNote(''), 5000); }
		} catch {
			setFloatNote('悬浮窗服务未就绪（主进程尚未接线）。');
			setTimeout(() => setFloatNote(''), 5000);
		}
	};

	// R7 无损切换的主窗侧收尾：草稿住主进程（契约硬要求①），浮窗收起时主窗 composer 必须
	// 从存储 adopt——否则文字在 draftGet 里活着、界面上却丢了（运行态实测踩过）。只认
	// 「浮→停」的跳变：其余模式事件不碰主窗输入框，避免盖掉用户正在打的字。
	const activeIdRef = useRef(sessionState.activeId);
	const floatPhaseRef = useRef<string>('docked');
	useEffect(() => { activeIdRef.current = sessionState.activeId; }, [sessionState.activeId]);
	useEffect(() => {
		const off = window.planability.float.onFloat(() => {
			void (async () => {
				try {
					const st = await window.planability.float.state();
					const prev = floatPhaseRef.current;
					floatPhaseRef.current = st.mode.phase;
					if (prev === 'docked' || st.mode.phase !== 'docked') { return; }
					const sid = activeIdRef.current;
					if (!sid) { return; }
					setInput((await window.planability.float.draftGet(sid)).text);
				} catch { /* non-fatal */ }
			})();
		});
		return off;
	}, []);

	const onKeyDown = (e: React.KeyboardEvent) => {
		if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send(); }
	};

	const toolCalls = rows.filter(r => r.kind === 'tool').length;

	const q = sessQuery.trim().toLowerCase();
	const matchSess = (s: SessionMeta) => !q || s.title.toLowerCase().includes(q);
	// 置顶浮在分组之上（pc-b ⑤：别被分组吃掉）；未分组收 projectId 缺失/空/落不进任何项目者
	// （删项目的级联清链接在 main，这里是防御性的最后一道——手改数据留下的悬空 id 也归这儿，
	// 不能渲染成一个没有标题的分组）。
	const projectIds = new Set(projects.map(p => p.id));
	const pinned = sessionState.metas.filter(s => s.pinned && matchSess(s));
	const ungrouped = sessionState.metas.filter(s => !s.pinned && matchSess(s) && (!s.projectId || !projectIds.has(s.projectId)));
	const byProject = projects
		.map(p => ({ project: p, list: sessionState.metas.filter(s => !s.pinned && matchSess(s) && s.projectId === p.id) }))
		.filter(g => !q || g.list.length > 0); // 搜索时只留有命中的组，避免一排空表头
	const filteredCount = pinned.length + ungrouped.length + byProject.reduce((n, g) => n + g.list.length, 0);
	const sessRow = (s: SessionMeta) => (
		<ListRow key={s.id} active={s.id === sessionState.activeId}
			leading={<MessageSquare size={13} />}
			title={renameId === s.id ? (
				<input
					className="sess-rename-input"
					value={renameText}
					autoFocus
					onClick={e => e.stopPropagation()}
					onCompositionStart={e => e.stopPropagation()}
					onChange={e => setRenameText(e.target.value)}
					onKeyDown={e => {
						e.stopPropagation();
						if (e.key === 'Enter' && !(e.nativeEvent as { isComposing?: boolean }).isComposing) { void commitRename(); }
						if (e.key === 'Escape') { setRenameId(''); }
					}}
					onBlur={() => void commitRename()}
					placeholder="会话名称"
					spellCheck={false}
				/>
			) : (
				<span className="sess-title" title={s.title}>{s.pinned ? '★ ' : ''}{s.title}</span>
			)}
			meta={<>
				{running && s.id === sessionState.activeId && <span className="tool-icon spin sess-run"><LoaderCircle size={12} /></span>}
				{s.messageCount} 条 · {relTime(s.updatedAt)}
			</>}
			onClick={() => void switchSession(s.id)}
			actions={[
				{ label: '重命名', icon: <Pencil size={13} />, onClick: () => { setRenameId(s.id); setRenameText(s.title); } },
				{ label: '移动到项目', icon: <FolderInput size={13} />, onClick: () => setMoveFor(s) },
				{ label: s.pinned ? '取消置顶' : '置顶', icon: <Pin size={13} />, onClick: () => pinSession(s.id, !s.pinned) },
				{ label: '删除', icon: <Trash2 size={13} />, danger: true, onClick: () => removeSession(s.id) },
			]}
		/>
	);

	return (
		<div className="chat-page wb">
			{/* workbench sidebar (ZCode WorkspaceSidebar parity): sessions + feature entries */}
			<aside className="wb-side">
				<div className="wb-side-head">
					<strong>工作台</strong>
					<FilledTonalButtonM3 disabled={running} title={running ? '正在执行本回合——结束后可用' : '新建会话'} onClick={() => void newChat()}>
						<IconM3 slot="icon"><Plus size={15} /></IconM3>新对话
					</FilledTonalButtonM3>
				</div>
				<div className="wb-side-scroll">
					{sessionState.metas.length > 0 && (
						<div className="wb-search">
							<Search size={13} />
							<input
								value={sessQuery}
								onChange={e => setSessQuery(e.target.value)}
								placeholder="搜索会话…"
								spellCheck={false}
							/>
							{sessQuery && (
								<button className="wb-search-clear" title="清除" onClick={() => setSessQuery('')}>
									<X size={11} />
								</button>
							)}
						</div>
					)}
					{sessionState.metas.length === 0 && (
						<div className="wb-empty">
							<MessageSquare size={18} />
							<span>还没有会话</span>
							<small>点右上「新对话」或直接在输入框发出第一条消息</small>
						</div>
					)}
					{sessionState.metas.length > 0 && filteredCount === 0 && (
						<div className="wb-nomatch">无匹配「{sessQuery.trim()}」</div>
					)}
					{pinned.length > 0 && <div className="wb-group">置顶</div>}
					{pinned.map(sessRow)}
					{/* 搜索无命中时藏表头（与项目组同规则），否则「未分类（0）」和「无匹配」并排出现 */}
					{(!q || ungrouped.length > 0) && <div className="wb-group">未分类（{ungrouped.length}）</div>}
					{ungrouped.map(sessRow)}
					{byProject.map(g => (
						<div key={g.project.id}>
							<div className="wb-group">
								{g.project.emoji ? `${g.project.emoji} ` : ''}{g.project.name}（{g.list.length}）
							</div>
							{g.list.map(sessRow)}
						</div>
					))}
				</div>
				<div className="wb-side-foot">
					{/* P3：footer 九入口 → SideNav chrome；导航后离开本页，active 恒空（无高亮） */}
					<SideNav ariaLabel="工作台功能入口"
						active={''}
						onSelect={key => nav(key as never)}
						items={[
							{ icon: ChartColumn, label: '使用统计', page: 'stats' },
							{ icon: Zap, label: '自动化', page: 'automations' },
							{ icon: Brain, label: '记忆库', page: 'memories' },
							{ icon: Bot, label: '多代理', page: 'agents' },
							{ icon: Workflow, label: '工作流编排', page: 'workflows' },
							{ icon: Wrench, label: '技能', page: 'skills' },
							{ icon: Users, label: '子智能体', page: 'assistants' },
							{ icon: Cable, label: 'MCP 服务器', page: 'mcp' },
							{ icon: Puzzle, label: '插件', page: 'plugins' }
						].map(item => ({
							key: item.page as string,
							label: item.label,
							icon: <item.icon size={15} />
						}))}
					/>
				</div>
			</aside>

			<div className="wb-main">
				<PageHeader
					title="AI 工作台"
					action={
						<div style={{ display: 'flex', gap: 8, alignItems: 'center', position: 'relative' }}>
							{/* P3 第三批：六枚 type-tag chip-btn → md-filter-chip；选中态=弹层开启 / 思考非 off */}
							<FilterChipM3 elevated title="切换模型（下一轮生效）"
								selected={openMenu === 'model'}
								onClick={() => setOpenMenu(openMenu === 'model' ? null : 'model')}>
								{modelLabel}（主槽）
							</FilterChipM3>
							<FilterChipM3 elevated title="切换子智能体（人格注入系统提示词）"
								selected={openMenu === 'assistant'}
								onClick={() => setOpenMenu(openMenu === 'assistant' ? null : 'assistant')}>
								<IconM3 slot="icon"><Users size={14} /></IconM3>
								{assistants.find(a => a.key === cfg.assistantKey)?.name ?? '通用助手'}
							</FilterChipM3>
							<FilterChipM3 elevated title={PERM_LABEL[permMode].hint}
								selected={openMenu === 'perm'}
								onClick={() => setOpenMenu(openMenu === 'perm' ? null : 'perm')}>
								{PERM_LABEL[permMode].label}
							</FilterChipM3>
							<FilterChipM3 elevated title="思考过程显示方式（自适应/开/关）"
								selected={thinkMode !== 'off'} onClick={cycleThink}>
								{THINK_LABEL[thinkMode]}
							</FilterChipM3>
							<FilterChipM3 elevated title="上下文与记忆注入"
								selected={openMenu === 'context'}
								onClick={() => setOpenMenu(openMenu === 'context' ? null : 'context')}>
								<IconM3 slot="icon"><Gauge size={14} /></IconM3>
								上下文
							</FilterChipM3>
							<FilterChipM3 elevated title="插话注入时机：下一轮工具边界 / 本回合结束后（点按切换）"
								onClick={() => void cycleInject()}>
								注入·{inject === 'nextRound' ? '下轮' : '回合末'}
							</FilterChipM3>

							{openMenu && (
								<>
									<div className="menu-backdrop" onClick={() => setOpenMenu(null)} />
									{openMenu === 'context' && (() => {
										const memOn = cfg.memInject !== false;
										return (
											<div className="pop-menu" style={{ minWidth: 320 }}>
												<div className="pop-title">上下文（当前会话）</div>
												<ContextBudgetPanel />
												<div className="ctx-row"><span>行数</span><strong>{rows.length}</strong></div>
												<div className="ctx-row"><span>工具调用</span><strong>{toolCalls} 次</strong></div>
												<div className="ctx-row"><span>子智能体</span><strong>{assistants.find(a => a.key === cfg.assistantKey)?.name ?? '通用助手'}</strong></div>
												<div className="pop-title" style={{ marginTop: 6 }}>记忆注入</div>
												<div className="ctx-row">
													<span>提示模型检索长期记忆</span>
													<label className="switch" style={{ marginLeft: 'auto' }}>
														<input type="checkbox" checked={memOn} onChange={() => {
															const next = !memOn;
															setCfg(prev => ({ ...prev, memInject: next }));
															void window.planability.configSet({ memInject: next });
														}} />
														<span className="switch-track"><span className="switch-thumb" /></span>
													</label>
												</div>
											</div>
										);
									})()}
									{openMenu === 'assistant' && (
										<div className="pop-menu">
											<div className="pop-title">子智能体（下一轮生效 · 管理在「助手」页）</div>
											<button className="pop-item" onClick={() => { saveCfg({ assistantKey: '' }); setOpenMenu(null); }}>
												{(cfg.assistantKey ?? '') === '' ? '● ' : '　'}<strong>通用助手</strong>
												<span className="pop-hint">无人格附加</span>
											</button>
											{assistants.filter(a => a.key !== 'general').map(a => (
												<button key={a.key} className="pop-item" onClick={() => { saveCfg({ assistantKey: a.key }); setOpenMenu(null); }}>
													{(cfg.assistantKey ?? '') === a.key ? '● ' : '　'}
													<span className="asst-dot" style={{ background: a.color }} />
													<strong>{a.name}</strong>
													<span className="pop-hint">{a.slogan}</span>
												</button>
											))}
										</div>
									)}
									{openMenu === 'model' && (
										<div className="pop-menu">
											<div className="pop-title">模型（下一轮生效）</div>
											{MODEL_PRESETS.map(m => (
												<button key={m} className="pop-item" onClick={() => { saveCfg({ mainModel: m }); setOpenMenu(null); }}>
													{m === modelLabel ? '● ' : '　'}{m}
												</button>
											))}
											<div className="pop-title" style={{ marginTop: 6 }}>自定义模型 ID</div>
											<div style={{ display: 'flex', gap: 6, padding: '0 10px 10px' }}>
												<input className="pop-input" value={customModel} placeholder="例如：mimo-v2.6-flash"
													onChange={e => setCustomModel(e.target.value)} />
												<TextButtonM3 onClick={() => {
													if (customModel.trim()) { saveCfg({ mainModel: customModel.trim() }); }
													setOpenMenu(null);
												}}>用</TextButtonM3>
											</div>
										</div>
									)}
									{openMenu === 'perm' && (
										<div className="pop-menu">
											<div className="pop-title">权限档位（手机端四档对齐）</div>
											{(Object.keys(PERM_LABEL) as PermMode[]).map(k => (
												<button key={k} className="pop-item" onClick={() => { saveCfg({ permMode: k }); setOpenMenu(null); }}>
													{permMode === k ? '● ' : '　'}<strong>{PERM_LABEL[k].label.replace('权限·', '')}</strong>
													<span className="pop-hint">{PERM_LABEL[k].hint}</span>
												</button>
											))}
										</div>
									)}
								</>
							)}
						</div>
					}
				/>

				<div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
					{/* 内容列限宽 880（spec §5 信息行：消息列表限宽不动；输入区已撑满面板） */}
					<div className="chat-col">
						{rows.length === 0 && (
							<div className="info-home">
								<div className="info-greet">{greeting()}，需要什么帮助？</div>
								<div className="info-suggest">
									{['统计我现在的全部数据', '帮我安排一下明天的日程', '把未完成的待办汇总给我', '写一个 Python 快排并解释'].map(t => (
										<SuggestionChipM3 key={t} onClick={() => setInput(t)}>{t}</SuggestionChipM3>
									))}
								</div>
							</div>
						)}
						<ChatTranscript rows={rows} thinkMode={thinkMode} cardsEnabled={cfg.plugins?.cards !== false}
							renderRowActions={renderRowActions} onSend={sendText} />
					</div>
				</div>

				{!atBottom && (
					<FabM3 className="jump-bottom" variant="surface" size="small" onClick={jumpToBottom} aria-label="回到底部">
						<IconM3><ArrowDownToLine size={17} /></IconM3>
					</FabM3>
				)}

				{pending && (
					<ApprovalCardView pending={pending} onResolve={resolveApproval} />
				)}
				{pending && approvalErr && (
					<div className="chat-error" style={{ marginBottom: 8 }}><span>{approvalErr}</span></div>
				)}

					{queue.length > 0 && (
						<div className="queue-panel">
							<span className="queue-title">插话队列 {queue.filter(q => !q.injected).length ? `· ${queue.filter(q => !q.injected).length} 待注入` : '· 已全部注入'}</span>
							{/* P3：队列条 → md-suggestion-chip（已注入变体用 dashed 描边区分） */}
							{queue.slice(-4).map((q, i) => (
								<SuggestionChipM3 key={`${q.at}-${i}`} className={q.injected ? 'queue-chip--injected' : ''}>
									{q.injected ? '✓ 已注入' : '⏳ 排队中'} {q.text.slice(0, 46)}{q.text.length > 46 ? '…' : ''}
								</SuggestionChipM3>
							))}
							<span style={{ flex: 1 }} />
							<TextButtonM3 title="同步队列状态" onClick={() => void reconcileQueue()}>刷新</TextButtonM3>
							<TextButtonM3 title="丢弃全部排队插话" onClick={() => void clearQueue()}>清空</TextButtonM3>
						</div>
					)}

				{floatNote && (
					<div className="chat-error" style={{ marginBottom: 8 }}><span>{floatNote}</span></div>
				)}
				{busyNote && (
					<div className="chat-error" style={{ marginBottom: 8 }}><span>{busyNote}</span></div>
				)}
				<ChatComposer
					value={input}
					onChange={setInput}
					onSubmit={send}
					running={running}
					onStop={stop}
					onShrink={() => void enterFloat()}
					slashItems={SLASH}
					inputRef={inputRef}
				/>
			</div>

			{moveFor && (
				<Modal title={`移动「${moveFor.title}」到项目`} onClose={() => setMoveFor(null)}>
					<div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
						<ListRow title={`未分组${!moveFor.projectId ? '（当前）' : ''}`} onClick={() => void moveSession(moveFor, null)} />
						{projects.map(p => (
							<ListRow key={p.id}
								title={<>{p.emoji ? `${p.emoji} ` : ''}{p.name}{moveFor.projectId === p.id ? '（当前）' : ''}</>}
								onClick={() => void moveSession(moveFor, p.id)} />
						))}
						{projects.length === 0 && (
							<div className="wb-nomatch">还没有项目——去「计划 → 项目」页签新建一个</div>
						)}
					</div>
				</Modal>
			)}
		</div>
	);
}

/** Reasoning fold lives in chat/ChatPieces.tsx (shared with the float output pane). */
