import { useEffect, useRef, useState } from 'react';
import type {
	AgentBusMessage, AgentProfileInfo, AgentRunInfo, AgentUsageRow,
	AgentTodoInfo, TodoCheckPolicy, PeerSlotInfo, PeerGroupInfo,
} from '@shared/ipc';
import { PageHeader } from '../components/ui';
import { useNavigate } from '../router';
import {
	Bot, Send, OctagonAlert, Eye, Radio, Inbox, Gauge, ShieldCheck, LoaderCircle, Rocket,
	ListTodo, Check, Trash2, Users, Scale,
} from 'lucide-react';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import './agents.css';

/* M17 multi-agent driver (pc-a, renderer lane): the permanent surface for the agent
 * kernel B landed in 95a75ab — drive primary agents, watch runs/concurrency, use the
 * SendMessage bus, read the usage ledger. Each 下令 creates its own session, so the
 * workbench transcript doubles as the per-agent trajectory view (switch via 查看). */

type RunsInfo = { runs: AgentRunInfo[]; running: number; limit: number };

const KINDS: { k: AgentBusMessage['kind']; label: string }[] = [
	{ k: 'note', label: 'note' }, { k: 'steer', label: 'steer' },
	{ k: 'result', label: 'result' }, { k: 'terminal', label: 'terminal' }
];

export default function AgentsPage() {
	const nav = useNavigate();
	const [agents, setAgents] = useState<AgentProfileInfo[]>([]);
	const [runsInfo, setRunsInfo] = useState<RunsInfo>({ runs: [], running: 0, limit: 4 });
	const [usage, setUsage] = useState<AgentUsageRow[]>([]);
	const [guard, setGuard] = useState<{ tripped: boolean; reason: string; signatures: string[] }>({ tripped: false, reason: '', signatures: [] });
	const [cmd, setCmd] = useState<Record<string, string>>({});
	const [busy, setBusy] = useState('');
	const [busFrom, setBusFrom] = useState('');
	const [busTo, setBusTo] = useState('');
	const [busKind, setBusKind] = useState<AgentBusMessage['kind']>('result');
	const [busBody, setBusBody] = useState('');
	const [inboxFor, setInboxFor] = useState('');
	const [inbox, setInbox] = useState<AgentBusMessage[]>([]);
	// Silent monitoring (acceptance-grade): subscribe to the kernel event stream and
	// track extremes in-page — evidence accumulates without racing the AX tree.
	const [evtLog, setEvtLog] = useState<{ t: number; kind: string; detail: string }[]>([]);
	const maxActiveRef = useRef(0);
	const quotaRef = useRef(0);

	// ---- P2 待办表 ----
	const [todos, setTodos] = useState<AgentTodoInfo[]>([]);
	const [todoText, setTodoText] = useState('');
	const [todoPolicy, setTodoPolicy] = useState<TodoCheckPolicy>({ mode: 'always', everyN: 3, maxShown: 8 });

	// ---- P3 平级模式 ----
	const [tplKey, setTplKey] = useState('');
	const [tpls, setTpls] = useState<{ key: string; name: string; description: string }[]>([]);
	const [peerTask, setPeerTask] = useState('');
	const [peerSlots, setPeerSlots] = useState<PeerSlotInfo[]>([]);
	const [peerGroups, setPeerGroups] = useState<PeerGroupInfo[]>([]);
	const [peerOutcome, setPeerOutcome] = useState<{ groupId: string; outcome: string; winnerId: string; explain: string } | null>(null);
	const [peerBusy, setPeerBusy] = useState(false);

	// Poll cheaply: only push state (and re-render / renumber the AX tree) when the
	// snapshot actually changed — unconditional setState churned the DOM every 1.5s.
	const snapRef = useRef('');
	const refresh = async () => {
		try {
			let list = await window.planability.agentList();
			if (!list.length) { await window.planability.agentSeedTeams(); list = await window.planability.agentList(); }
			const runs = await window.planability.agentRuns();
			if (runs.runs.length > maxActiveRef.current) { maxActiveRef.current = runs.runs.length; }
			const usg = await window.planability.agentUsage();
			const gd = await window.planability.guardState();
			const ibx = inboxFor ? await window.planability.agentInbox(inboxFor) : null;
			const td = await window.planability.todoList();
			const pg = await window.planability.peerList().catch(() => [] as PeerGroupInfo[]);
			const snap = JSON.stringify([list, runs, usg, gd, ibx, td, pg]);
			if (snap === snapRef.current) { return; }
			snapRef.current = snap;
			setAgents(list);
			setRunsInfo(runs);
			setUsage(usg);
			setGuard(gd);
			if (ibx) { setInbox(ibx); }
			setTodos(td);
			setPeerGroups(pg);
		} catch { /* non-fatal */ }
	};

	useEffect(() => {
		void refresh();
		const t = setInterval(() => void refresh(), 1500);
		// silent event stream: run lifecycle / quota waits / bus — captured, never raced
		const off = window.planability.onAgentEvent(e => {
			const raw = e as { type?: string };
			const kind = raw?.type ?? 'unknown';
			if (/quota/i.test(kind)) { quotaRef.current += 1; }
			setEvtLog(prev => [...prev.slice(-60), { t: Date.now(), kind, detail: JSON.stringify(e).slice(0, 140) }]);
		});
		// mount probe: proves the subscription block executed in this page instance
		setEvtLog(prev => [...prev, { t: Date.now(), kind: 'subscribed', detail: 'agentEvent 订阅已挂载' }]);
		return () => { clearInterval(t); off(); };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [inboxFor]);

	// P2/P3 one-time bootstrap: policy + templates, and pre-fill the three editable slots.
	useEffect(() => {
		let dead = false;
		void (async () => {
			try {
				const pol = await window.planability.todoPolicyGet();
				const tl = await window.planability.peerTemplates();
				if (dead) { return; }
				setTodoPolicy(pol);
				setTpls(tl.map(t => ({ key: t.key, name: t.name, description: t.description })));
				if (tl.length) { setTplKey(tl[0].key); }
			} catch { /* non-fatal */ }
		})();
		return () => { dead = true; };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	// Load the selected template's slots. Slots are pre-filled but every one stays editable.
	const loadTemplate = async (key: string) => {
		setTplKey(key);
		try {
			const tl = await window.planability.peerTemplates();
			const t = tl.find(x => x.key === key);
			if (!t) { return; }
			// auto-assign the first N agents so the form is one click away from runnable
			setPeerSlots(t.slots.map((s, i) => ({ ...s, agentId: agents[i]?.id ?? '' })));
		} catch { /* non-fatal */ }
	};

	useEffect(() => { if (tplKey && peerSlots.length === 0) { void loadTemplate(tplKey); } }, [tplKey]); // eslint-disable-line react-hooks/exhaustive-deps

	const addTodo = async () => {
		const t = todoText.trim();
		if (!t) { return; }
		await window.planability.todoAdd(t);
		setTodoText('');
		await refresh();
	};

	const setPolicy = async (patch: Partial<TodoCheckPolicy>) => {
		const p = await window.planability.todoPolicySet(patch);
		setTodoPolicy(p);
	};

	const startPeers = async () => {
		if (peerBusy) { return; }
		setPeerBusy(true);
		try {
			const tpl = tpls.find(t => t.key === tplKey);
			const r = await window.planability.peerStart(tpl?.name ?? '平级协作', peerTask.trim(), tplKey, peerSlots, peerSlots.map(s => s.agentId));
			setPeerOutcome({ groupId: r.groupId, outcome: r.skipped.length ? `已跳过 ${r.skipped.length} 个槽位` : '已扇出', winnerId: '', explain: r.skipped.map(s => s.reason).join('；') });
			await refresh();
		} catch (e) {
			setPeerOutcome({ groupId: '', outcome: '失败', winnerId: '', explain: String(e).slice(0, 160) });
		} finally { setPeerBusy(false); }
	};

	const settlePeers = async (groupId: string) => {
		try {
			const r = await window.planability.peerSettle(groupId);
			setPeerOutcome({ groupId, outcome: r.outcome, winnerId: r.winnerId, explain: r.explain });
			await refresh();
		} catch (e) {
			setPeerOutcome({ groupId, outcome: '失败', winnerId: '', explain: String(e).slice(0, 160) });
		}
	};

	// Programmatic stress: fire 5 runs back-to-back (agentRun returns after enqueue, so
	// submission outruns execution → the concurrency gate should queue at least one).
	const [stressBusy, setStressBusy] = useState(false);
	const stress5 = async () => {
		if (stressBusy) { return; }
		setStressBusy(true);
		setEvtLog(prev => [...prev, { t: Date.now(), kind: 'stress_click', detail: '压测函数已触发' }]);
		try {
			const list = (await window.planability.agentList()).slice(0, 5);
			setEvtLog(prev => [...prev, { t: Date.now(), kind: 'stress_list', detail: `agents=${list.length}` }]);
			for (const a of list) {
				const s = await window.planability.sessionCreate(`压测·${a.name} ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`);
				await window.planability.agentRun(a.id, s.id, '按顺序调用五个工具各一次：schedule_query(range=today)、todo_query(status=all)、note_search(query=课)、memory_search(query=计划)、schedule_query(range=week)。最后只回一行"完成"。');
			}
			setEvtLog(prev => [...prev, { t: Date.now(), kind: 'stress_done', detail: '5 个任务已全部提交' }]);
		} catch (e) {
			setEvtLog(prev => [...prev, { t: Date.now(), kind: 'stress_err', detail: String(e).slice(0, 140) }]);
		} finally { setStressBusy(false); }
	};

	const dispatch = async (a: AgentProfileInfo) => {
		const prompt = (cmd[a.id] ?? '').trim();
		if (!prompt || busy) { return; }
		setBusy(a.id);
		try {
			// SessionStore.create() also makes it active — the workbench shows this run live.
			const s = await window.planability.sessionCreate(`${a.name} · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`);
			await window.planability.agentRun(a.id, s.id, prompt);
			setCmd(prev => ({ ...prev, [a.id]: '' }));
			await refresh();
		} finally { setBusy(''); }
	};

	const viewSession = async (sessionId: string) => {
		await window.planability.sessionSwitch(sessionId).catch(() => undefined);
		nav('info');
	};

	const postBus = async () => {
		if (!busFrom || !busTo || !busBody.trim()) { return; }
		await window.planability.agentPost(busFrom, busTo, busBody.trim(), busKind).catch(() => undefined);
		setBusBody('');
		setInboxFor(busTo);
		await refresh();
	};

	const STATUS: Record<AgentRunInfo['status'], string> = { queued: '排队', running: '运行', done: '完成', error: '出错' };

	return (
		<div className="agents-page">
			<PageHeader
				title="多代理"
				action={
					<span className={`guard-chip ${guard.tripped ? 'trip' : ''}`} title={guard.tripped ? guard.reason : `循环防护正常 · 签名 ${guard.signatures.length}`}>
						<ShieldCheck size={13} /> {guard.tripped ? '熔断' : '防护正常'}
					</span>
				}
			/>

			<section className="ag-section">
				<div className="ag-head"><Bot size={15} /> 代理（{agents.length}）</div>
				<div className="ag-grid">
					{agents.map(a => (
						<div className="ag-card" key={a.id} style={{ borderColor: a.color + '66' }}>
							<div className="ag-card-head">
								<span className="ag-dot" style={{ background: a.color }} />
								<strong>{a.name}</strong>
								{a.builtin && <span className="type-tag">内置</span>}
								<span className="type-tag">{a.permMode}</span>
								<span className="ag-depth-hint">max {a.maxTurns} 轮</span>
							</div>
							<div className="ag-prompt">{a.systemPrompt || '（无人格附加，继承主提示词）'}</div>
							<div className="ag-cmd">
								<input
									value={cmd[a.id] ?? ''}
									placeholder={`对 ${a.name} 下令…`}
									onChange={e => setCmd(prev => ({ ...prev, [a.id]: e.target.value }))}
									onKeyDown={e => { if (e.key === 'Enter') { void dispatch(a); } }}
									spellCheck={false}
								/>
								<FilledButtonM3 disabled={busy === a.id || !(cmd[a.id] ?? '').trim()} onClick={() => void dispatch(a)}>
									{busy === a.id ? <LoaderCircle size={13} className="spin" /> : <Send size={13} />} 下令
								</FilledButtonM3>
								<TextButtonM3 title="中止该 agent 的运行与排队" onClick={() => void window.planability.agentAbort(a.id).then(refresh)}>
									<OctagonAlert size={13} />
								</TextButtonM3>
							</div>
						</div>
					))}
					{agents.length === 0 && <div className="wb-empty">正在播种预置团队…</div>}
				</div>
			</section>

			<section className="ag-section">
				<div className="ag-head">
					<LoaderCircle size={15} className={runsInfo.running ? 'spin' : ''} /> 运行（{runsInfo.running} / 并发上限 {runsInfo.limit}）
					<span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
						{/* G 类 MEDIUM：调试按钮不进产物——DEV 门（build 后为 false）。验收要压测
						    就程序化连发（sessionCreate+agentRun），不必给正式界面留一个扣费开关。 */}
						{import.meta.env.DEV && (
							<FilledButtonM3 disabled={stressBusy} onClick={() => void stress5()} title="程序化连发 5 个任务，触发并发闸排队（静默监测）">
								{stressBusy ? <LoaderCircle size={13} className="spin" /> : <Rocket size={13} />} 压测×5
							</FilledButtonM3>
						)}
					</span>
				</div>
				{runsInfo.runs.length === 0 && <div className="ag-dim">暂无运行记录</div>}
				{runsInfo.runs.length > 0 && (
					<table className="tr-table">
						<thead><tr><th>agent</th><th>类型</th><th>状态</th><th>会话</th><th></th></tr></thead>
						<tbody>
							{runsInfo.runs.slice().reverse().map(r => {
								const a = agents.find(x => x.id === r.agentId);
								return (
									<tr key={r.id}>
										<td><span className="ag-dot" style={{ background: a?.color ?? 'var(--md-sys-color-outline)', marginRight: 6 }} />{a?.name ?? r.agentId}</td>
										<td>{r.kind === 'sub' ? `子代理 d${r.depth}` : '主代理'}</td>
										<td className={`ag-st-${r.status}`}>{STATUS[r.status]}{r.error ? `：${r.error.slice(0, 30)}` : ''}</td>
										<td><code>{r.sessionId.slice(0, 18)}…</code></td>
										<td><TextButtonM3 onClick={() => void viewSession(r.sessionId)}><Eye size={12} /> 查看</TextButtonM3></td>
									</tr>
							);})}
						</tbody>
					</table>
				)}
			</section>

			<section className="ag-section">
				<div className="ag-head"><Radio size={15} /> 静默监测（事件流 · 不依赖轮询抢读）</div>
				<div className="ag-verdict">
					<span className="guard-chip" title="运行中/排队的活动行数峰值（页内轮询自记）">最大并发活动 {maxActiveRef.current}</span>
					<span className={`guard-chip ${quotaRef.current ? '' : ''}`} title="内核发出的排队/配额等待事件数">排队事件 {quotaRef.current}</span>
					<span className="guard-chip" title="累计捕获的内核事件">事件 {evtLog.length}</span>
				</div>
				{evtLog.length === 0 && <div className="ag-dim">还没有内核事件——有排队/熔断发生时会落到这里</div>}
				{evtLog.slice(-8).reverse().map((e, i) => (
					<div className="ag-msg read" key={e.t + '-' + i}>
						<span className="ag-msg-kind">{e.kind}</span>
						<span className="ag-msg-body">{new Date(e.t).toLocaleTimeString('zh-CN')} · {e.detail}</span>
					</div>
				))}
			</section>

			<section className="ag-section">
				<div className="ag-head"><Inbox size={15} /> 消息总线（SendMessage）</div>
				<div className="ag-bus">
					<select value={busFrom} onChange={e => setBusFrom(e.target.value)}>
						<option value="">发自…</option>
						{agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
					</select>
					<span className="ag-arrow">→</span>
					<select value={busTo} onChange={e => setBusTo(e.target.value)}>
						<option value="">发给…</option>
						{agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
					</select>
					<select value={busKind} onChange={e => setBusKind(e.target.value as AgentBusMessage['kind'])}>
						{KINDS.map(k => <option key={k.k} value={k.k}>{k.label}</option>)}
					</select>
					<input value={busBody} placeholder="协作消息正文…" onChange={e => setBusBody(e.target.value)} spellCheck={false} />
					<FilledButtonM3 disabled={!busFrom || !busTo || !busBody.trim()} onClick={() => void postBus()}>投递</FilledButtonM3>
				</div>
				<div className="ag-inbox-head">
					<Inbox size={13} />
					<select value={inboxFor} onChange={e => setInboxFor(e.target.value)}>
						<option value="">查看收件箱…</option>
						{agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
					</select>
					{inboxFor && inbox.length > 0 && (
						<TextButtonM3 onClick={() => void window.planability.agentMarkRead(inboxFor).then(refresh)}>全部已读</TextButtonM3>
					)}
				</div>
				{inboxFor && inbox.length === 0 && <div className="ag-dim">收件箱为空</div>}
				{inbox.map(m => (
					<div key={m.id} className={`ag-msg ${m.readAt ? 'read' : 'unread'}`}>
						<span className="ag-msg-kind">{m.kind}</span>
						<strong>{agents.find(a => a.id === m.fromAgent)?.name ?? m.fromAgent}</strong>
						<span>→ {agents.find(a => a.id === m.toAgent)?.name ?? m.toAgent}</span>
						<span className="ag-msg-body">{m.body}</span>
						{!m.readAt && <span className="ag-unread-dot" />}
					</div>
				))}
			</section>

			<section className="ag-section">
				<div className="ag-head"><ListTodo size={15} /> 待办表（P2 · 想到就挂，AI 定期自查）</div>
				<div className="ag-bus">
					<input
						value={todoText}
						placeholder="挂一条想法，AI 会在后续轮次自动看到…"
						onChange={e => setTodoText(e.target.value)}
						onKeyDown={e => { if (e.key === 'Enter') { void addTodo(); } }}
						spellCheck={false}
					/>
					<FilledButtonM3 disabled={!todoText.trim()} onClick={() => void addTodo()}>挂上</FilledButtonM3>
					<select
						value={todoPolicy.mode}
						title="Q2：检查档位"
						onChange={e => void setPolicy({ mode: e.target.value as TodoCheckPolicy['mode'] })}
					>
						<option value="always">每轮都提醒</option>
						<option value="everyN">每 N 轮提醒</option>
					</select>
					{todoPolicy.mode === 'everyN' && (
						<input
							type="number" min={1} value={todoPolicy.everyN}
							style={{ width: 64 }}
							title="每隔多少轮提醒一次"
							onChange={e => void setPolicy({ everyN: Number(e.target.value) })}
						/>
					)}
					<span className="guard-chip" title="最多回灌多少条进提示词">上限 {todoPolicy.maxShown}</span>
				</div>
				{todos.filter(t => t.status === 'open').length === 0 && <div className="ag-dim">待办表是空的</div>}
				{todos.filter(t => t.status === 'open').map(t => (
					<div key={t.id} className="ag-msg">
						<span className="ag-msg-kind">{t.source === 'agent' ? 'AI' : '我'}</span>
						<span className="ag-msg-body">{t.text}</span>
						<TextButtonM3 title="标记完成" onClick={() => void window.planability.todoDone(t.id).then(refresh)}><Check size={12} /></TextButtonM3>
						<TextButtonM3 title="删除" onClick={() => { if (window.confirm(`删除待办「${t.text}」？`)) { void window.planability.todoRemove(t.id).then(refresh); } }}><Trash2 size={12} /></TextButtonM3>
					</div>
				))}
				{todos.some(t => t.status === 'done') && (
					<div className="ag-dim">已完成 {todos.filter(t => t.status === 'done').length} 条</div>
				)}
			</section>

			<section className="ag-section">
				<div className="ag-head">
					<Users size={15} /> 平级协作（P3 · 2-3 位平级 agent，多数决收敛）
					<span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
						<select value={tplKey} onChange={e => void loadTemplate(e.target.value)} title="选一套模板（每格都能改）">
							{tpls.map(t => <option key={t.key} value={t.key}>{t.name}</option>)}
						</select>
						<FilledButtonM3 disabled={peerBusy || peerSlots.length === 0} onClick={() => void startPeers()}>
							{peerBusy ? <LoaderCircle size={13} className="spin" /> : <Rocket size={13} />} 发起协作
						</FilledButtonM3>
					</span>
				</div>
				{tpls.find(t => t.key === tplKey) && (
					<div className="ag-dim">{tpls.find(t => t.key === tplKey)?.description}</div>
				)}
				<input
					value={peerTask}
					placeholder="这次要一起解决什么？（例如：给同步中心加冲突提示）"
					onChange={e => setPeerTask(e.target.value)}
					spellCheck={false}
					style={{ width: '100%', margin: '6px 0' }}
				/>
				{peerSlots.map((s, i) => (
					<div key={s.key} className="ag-cmd" style={{ marginBottom: 6 }}>
						<span className="ag-dot" style={{ background: s.color, marginRight: 6 }} />
						<input
							value={s.label}
							style={{ maxWidth: 110 }}
							title="角色名（可改）"
							onChange={e => setPeerSlots(prev => prev.map((x, j) => j === i ? { ...x, label: e.target.value } : x))}
						/>
						<select
							value={s.agentId}
							title="由哪个 agent 执行"
							onChange={e => setPeerSlots(prev => prev.map((x, j) => j === i ? { ...x, agentId: e.target.value } : x))}
						>
							<option value="">（未指定）</option>
							{agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
						</select>
						<input
							value={s.prompt}
							placeholder="这一格要怎么做事？（模板已预填，随便改）"
							onChange={e => setPeerSlots(prev => prev.map((x, j) => j === i ? { ...x, prompt: e.target.value } : x))}
							spellCheck={false}
						/>
					</div>
				))}
				{peerOutcome && (
					<div className="ag-verdict" style={{ marginTop: 6 }}>
						<span className={`guard-chip ${peerOutcome.outcome === 'tie' ? 'trip' : ''}`}>
							<Scale size={12} />
							{peerOutcome.outcome === 'decided' ? `收敛：${peerOutcome.winnerId}`
								: peerOutcome.outcome === 'tie' ? '平票 · 未替你决定'
								: peerOutcome.outcome}
						</span>
						<span className="ag-dim" style={{ whiteSpace: 'pre-wrap' }}>{peerOutcome.explain}</span>
					</div>
				)}
				{peerGroups.length > 0 && (
					<table className="tr-table">
						<thead><tr><th>协作组</th><th>状态</th><th>胜出</th><th></th></tr></thead>
						<tbody>
							{peerGroups.map(g => (
								<tr key={g.id}>
									<td>{g.title}<div className="ag-dim">{g.task || '（无任务描述）'}</div></td>
									<td>{g.status === 'settled' ? '已收敛' : g.status === 'voting' ? '投票中' : '进行中'}</td>
									<td>{g.winnerId ? (agents.find(a => a.id === g.winnerId)?.name ?? g.winnerId) : '—'}</td>
									<td>
										<TextButtonM3 title="多数决裁决" onClick={() => void settlePeers(g.id)}><Scale size={12} /> 裁决</TextButtonM3>
										<TextButtonM3 title="删除" onClick={() => { if (window.confirm(`删除协作组「${g.title}」？`)) { void window.planability.peerRemove(g.id).then(refresh); } }}><Trash2 size={12} /></TextButtonM3>
									</td>
								</tr>
							))}
						</tbody>
					</table>
				)}
			</section>

			<section className="ag-section">
				<div className="ag-head"><Gauge size={15} /> 用量台账（估算 tokens）</div>
				{usage.length === 0 && <div className="ag-dim">还没有用量</div>}
				{usage.length > 0 && (
					<table className="tr-table">
						<thead><tr><th>agent</th><th>输出 tokens</th><th>调用</th></tr></thead>
						<tbody>
							{usage.map(u => (
								<tr key={u.agentId}>
									<td>{agents.find(a => a.id === u.agentId)?.name ?? u.agentId}</td>
									<td>{u.tokensOut.toLocaleString()}</td>
									<td>{u.calls}</td>
								</tr>
							))}
						</tbody>
					</table>
				)}
			</section>
		</div>
	);
}