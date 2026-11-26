import { useEffect, useState } from 'react';
import type {
	TeamAssemblyResult, WorkflowEvent, WorkflowInfo, WorkflowRunInfo, HandoffFileInfo,
} from '@shared/chat';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import {
	Workflow, Users, FileText, Play, LoaderCircle, Eye, RefreshCw, Send, ClipboardList,
} from 'lucide-react';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';

/* M20 编排页（pc-a renderer 线）：工作流运行/进度/历史/回放 + 自动组队 + 交接文档。
 * 契约=shared/chat.ts + pc-b 的 UI_CONTRACT_HANDOFF.md。三条硬要求落在这页：
 * ①回放只读——绝不能有「重跑」按钮（回放不调 agent、不写任何东西，这正是它的用途）；
 * ②失败要显示 failedAt 与逐步状态——失败即 halt，历史里步骤数少于总步数是正常形态，不是数据错误；
 * ③组队是 PROPOSAL——拒绝时展示 detail、不虚构团队；weakest 单独展示不被平均值掩盖。
 * journal 只存内存且有上界，所以历史标注「本次会话」，重启后回放失败要如实报。 */

type Draft = { title: string; goal: string; done: string; blockers: string; next: string };

const EMPTY_DRAFT: Draft = { title: '', goal: '', done: '', blockers: '', next: '' };

const RUN_STATUS: Record<WorkflowRunInfo['status'], string> = {
	done: '完成', failed: '失败', running: '运行中',
};

const STEP_STATUS: Record<string, string> = {
	running: '运行中', done: '完成', failed: '失败',
};

function fmtTime(ts: number): string {
	return ts ? new Date(ts).toLocaleTimeString('zh-CN') : '—';
}

export default function WorkflowsPage() {
	// ---- M20-1 工作流 ----
	const [wfs, setWfs] = useState<WorkflowInfo[]>([]);
	const [runs, setRuns] = useState<WorkflowRunInfo[]>([]);
	const [taskDraft, setTaskDraft] = useState<Record<string, string>>({});
	const [busyRun, setBusyRun] = useState('');
	const [runErr, setRunErr] = useState('');
	// runId -> stepId -> 最新事件。onWorkflow 是推送，这里只做归并展示。
	const [live, setLive] = useState<Record<string, Record<string, WorkflowEvent>>>({});
	const [replay, setReplay] = useState<{ runId: string; text: string } | null>(null);
	const [replayBusy, setReplayBusy] = useState('');

	// ---- M20-3 自动组队 ----
	const [req, setReq] = useState('');
	const [teamBusy, setTeamBusy] = useState(false);
	const [team, setTeam] = useState<TeamAssemblyResult | null>(null);
	const [teamRunNote, setTeamRunNote] = useState('');

	// ---- M20-4 交接文档 ----
	const [ho, setHo] = useState<Draft>(EMPTY_DRAFT);
	const [hoPreview, setHoPreview] = useState('');
	const [hoWrite, setHoWrite] = useState('');
	const [hoFiles, setHoFiles] = useState<HandoffFileInfo[]>([]);
	const [hoOpen, setHoOpen] = useState<{ name: string; text: string } | null>(null);

	const refresh = async () => {
		try {
			const [list, history, files] = await Promise.all([
				window.planability.chat.workflowList(),
				window.planability.chat.workflowRuns(20),
				window.planability.chat.handoffList(),
			]);
			setWfs(list);
			setRuns(history);
			setHoFiles(files);
		} catch (e) {
			setRunErr(String(e).slice(0, 200));
		}
	};

	useEffect(() => {
		void refresh();
		const off = window.planability.chat.onWorkflow(e => {
			setLive(prev => ({ ...prev, [e.runId]: { ...(prev[e.runId] ?? {}), [e.stepId]: e } }));
		});
		// journal 在 runWorkflow resolve 后才入册，所以事件结束后仍需轮询把终态拉进历史。
		const t = setInterval(() => void refresh(), 2000);
		return () => { clearInterval(t); off(); };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	const runWorkflow = async (wf: WorkflowInfo) => {
		const task = (taskDraft[wf.id] ?? '').trim();
		if (!task || busyRun) { return; }
		setBusyRun(wf.id);
		setRunErr('');
		try {
			const r = await window.planability.chat.workflowRun(wf.id, task);
			if (!r.ok) {
				// 契约③：错误原样展示，不静默。当前 workflowRun 只认内置 demo，列表里的
				// 条目会回「未知工作流」——已 assign 给 pc-b（见 ◆），UI 不替他遮掩。
				setRunErr(`${wf.name}：${r.error ?? '启动失败'}`);
			} else if (r.runId) {
				setLive(prev => ({ ...prev, [r.runId as string]: {} }));
			}
		} catch (e) {
			setRunErr(`${wf.name}：${String(e).slice(0, 200)}`);
		} finally {
			setBusyRun('');
			await refresh();
		}
	};

	const showReplay = async (runId: string) => {
		setReplayBusy(runId);
		try {
			const r = await window.planability.chat.workflowReplay(runId);
			if (r.ok && r.text) { setReplay({ runId, text: r.text }); }
			else { setRunErr(`回放失败（${runId}）：journal 不在内存中——进程重启或已越界淘汰`); }
		} catch (e) {
			setRunErr(String(e).slice(0, 200));
		} finally { setReplayBusy(''); }
	};

	const assemble = async () => {
		if (!req.trim() || teamBusy) { return; }
		setTeamBusy(true);
		setTeam(null);
		setTeamRunNote('');
		try { setTeam(await window.planability.chat.teamAssemble(req.trim())); }
		catch (e) { setTeam({ ok: false, detail: String(e).slice(0, 200) }); }
		finally { setTeamBusy(false); }
	};

	const runTeam = async () => {
		if (!team?.ok || !team.workflow) { return; }
		const wf = team.workflow;
		const r = await window.planability.chat.workflowRun(wf.id, (wf.task || req).trim());
		if (r.ok && r.runId) {
			setTeamRunNote(`已启动（${r.runId}），逐步进度见上方「工作流」区`);
			setLive(prev => ({ ...prev, [r.runId as string]: {} }));
		} else {
			setTeamRunNote(`启动失败：${r.error ?? '未知错误'}`);
		}
		await refresh();
	};

	const previewHandoff = async () => {
		const r = await window.planability.chat.handoffPreview(ho);
		setHoPreview(r.ok ? (r.text ?? '') : `（被拒绝：${r.reason ?? '未说明原因'}）`);
		setHoWrite('');
	};

	const writeHandoff = async () => {
		const r = await window.planability.chat.handoffWrite(ho);
		setHoWrite(r.ok ? `已写入：${r.path}` : `（被拒绝：${r.reason ?? '未说明原因'}）`);
		if (r.ok) { await refresh(); }
	};

	const openHandoff = async (f: HandoffFileInfo) => {
		const r = await window.planability.chat.handoffRead(f.name);
		if (r.ok && r.text) { setHoOpen({ name: f.name, text: r.text }); }
		else { setRunErr(`读取 ${f.name} 失败`); }
	};

	const preStyle: React.CSSProperties = {
		background: 'var(--surface)', border: '1px solid var(--outline-dim)',
		borderRadius: 'var(--shape-sm)', padding: '10px 12px', fontSize: 12,
		maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', marginTop: 10,
	};

	return (
		<>
			<PageHeader
				title="工作流编排"
				action={<TextButtonM3 onClick={() => void refresh()}><RefreshCw size={13} /> 刷新</TextButtonM3>}
			/>
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				三步工作流逐步运行可回放 · 模糊需求自动组队（提议，不直接花额度）· 四段式交接写同步文件夹。
			</p>

			<section className="ag-section">
				<div className="ag-head"><Workflow size={15} /> 工作流（{wfs.length}）</div>
				{wfs.length === 0 && <Empty text="没有可运行的工作流" />}
				{wfs.map(wf => {
					const steps = wf.steps ?? [];
					const hasSteps = steps.length > 0;
					// 契约：解析/校验失败的文件也必须出现在列表里，并带着原因（problems）——
					// steps 为空只说明「没有步骤」，说不清「为什么」。
					const problems = wf.problems ?? [];
					const broken = problems.length > 0;
					const runnable = hasSteps && !broken;
					return (
						<div className="card" key={wf.id} style={{ display: 'block', marginBottom: 10 }}>
							<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
								<div className="card-main">
									<div className="card-title">
										{wf.name}
										{wf.overridden && <span className="type-tag" style={{ marginLeft: 8 }}>用户覆盖</span>}
										{broken && <span className="type-tag" style={{ marginLeft: 8, color: 'var(--danger)' }} title={problems.join('；')}>无法运行</span>}
										{!broken && !hasSteps && <span className="type-tag" style={{ marginLeft: 8, color: 'var(--danger)' }}>定义无效</span>}
									</div>
									<div className="card-meta">
										{broken
											? problems.join('；')
											: hasSteps
												? steps.map((s, i) => (
													<span key={s.id} title={s.prompt} style={{ marginRight: 8 }}>
														{i + 1}. {s.role}{s.optional ? '（可选）' : ''}
													</span>
												))
												: '此条目没有通过校验的步骤，无法运行'}
									</div>
								</div>
							</div>
							<div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
								<input
									value={taskDraft[wf.id] ?? ''}
									placeholder="这次要做什么？（任务描述，必填）"
									onChange={e => setTaskDraft(prev => ({ ...prev, [wf.id]: e.target.value }))}
									onKeyDown={e => { if (e.key === 'Enter') { void runWorkflow(wf); } }}
									spellCheck={false}
									style={{ flex: 1 }}
								/>
								<FilledButtonM3
									disabled={!runnable || !((taskDraft[wf.id] ?? '').trim()) || !!busyRun}
									title={runnable ? '启动运行（每一步都要选一个 agent 执行）' : broken ? '文件有问题，先修 workflows 目录里的这份定义' : '步骤缺失，无法运行'}
									onClick={() => void runWorkflow(wf)}
								>
									{busyRun === wf.id ? <LoaderCircle size={13} className="spin" /> : <Play size={13} />} 运行
								</FilledButtonM3>
							</div>
						</div>
					);
				})}
				{runErr && <div className="ag-dim" style={{ color: 'var(--danger)' }}>{runErr}</div>}

				{Object.keys(live).length > 0 && (
					<>
						<div className="ag-head" style={{ marginTop: 14 }}><Eye size={14} /> 实时进度</div>
						{Object.entries(live).slice(-3).reverse().map(([runId, steps]) => (
							<div key={runId} style={{ marginBottom: 8 }}>
								<span className="guard-chip" title={runId}>{runId}</span>
								{Object.values(steps).map(ev => (
									<span
										key={ev.stepId}
										className={`guard-chip ${ev.status === 'failed' ? 'trip' : ''}`}
										style={{ marginLeft: 6 }}
										title={ev.error ?? runId}
									>
										{ev.stepId} · {ev.role} · {STEP_STATUS[ev.status] ?? ev.status}
									</span>
								))}
								{Object.values(steps).some(e => e.status === 'failed') && (
									<div className="ag-dim" style={{ color: 'var(--danger)' }}>
										{Object.values(steps).filter(e => e.status === 'failed').map(e => `${e.stepId}：${e.error ?? '未知错误'}`).join('；')}
									</div>
								)}
							</div>
						))}
					</>
				)}

				<div className="ag-head" style={{ marginTop: 14 }}>
					<ClipboardList size={15} /> 运行历史（本次会话 · {runs.length}）
				</div>
				{runs.length === 0 && <div className="ag-dim">还没有运行记录</div>}
				{runs.length > 0 && (
					<table className="tr-table">
						<thead><tr><th>工作流</th><th>任务</th><th>状态</th><th>步骤</th><th>开始</th><th></th></tr></thead>
						<tbody>
							{runs.slice().reverse().map(r => (
								<tr key={r.runId}>
									<td>{r.workflowName || r.workflowId}</td>
									<td style={{ maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.task}>{r.task}</td>
									<td className={r.status === 'failed' ? 'ag-st-error' : r.status === 'done' ? 'ag-st-done' : 'ag-st-running'}>
										{RUN_STATUS[r.status]}
										{r.failedAt && `（中断于 ${r.failedAt}）`}
									</td>
									<td>
										{r.steps.map(s => (
											<span key={s.stepId} className={`guard-chip ${s.status === 'failed' ? 'trip' : ''}`} style={{ marginRight: 4 }} title={`${s.stepId} · ${s.role}`}>
												{s.stepId}:{STEP_STATUS[s.status] ?? s.status}
											</span>
										))}
									</td>
									<td>{fmtTime(r.startedAt)}</td>
									<td>
										<TextButtonM3 title="只读回放：不调用任何 agent、不写入任何数据" onClick={() => void showReplay(r.runId)}>
											{replayBusy === r.runId ? <LoaderCircle size={12} className="spin" /> : <Eye size={12} />} 回放
										</TextButtonM3>
									</td>
								</tr>
							))}
						</tbody>
					</table>
				)}
			</section>

			<section className="ag-section">
				<div className="ag-head"><Users size={15} /> 自动组队（模糊需求 → 提议团队）</div>
				<div style={{ display: 'flex', gap: 8 }}>
					<textarea
						value={req}
						placeholder="用人话描述需求，例如：调研三个竞品的定价并整理成对比表…"
						onChange={e => setReq(e.target.value)}
						style={{ flex: 1, minHeight: 56 }}
						spellCheck={false}
					/>
					<FilledButtonM3 disabled={teamBusy || !req.trim()} onClick={() => void assemble()}>
						{teamBusy ? <LoaderCircle size={13} className="spin" /> : <Send size={13} />} 组队
					</FilledButtonM3>
				</div>
				{team && !team.ok && (
					<div className="ag-dim" style={{ color: 'var(--danger)' }}>
						组队被拒：{team.detail ?? team.reason ?? '未说明原因'}
					</div>
				)}
				{team?.ok && team.steps && (
					<>
						<div className="ag-verdict" style={{ marginTop: 10 }}>
							<span className="guard-chip">模板：{team.template?.name ?? '—'}</span>
							<span className={`guard-chip ${team.weakest !== undefined && team.weakest < 0.6 ? 'trip' : ''}`}>
								最弱一步 {team.weakest !== undefined ? `${Math.round(team.weakest * 100)}%` : '—'}
							</span>
							{team.steps.map(s => (
								<span key={s.intent} className="guard-chip" title={s.because}>
									{s.intent} → {s.agentName} {Math.round(s.confidence * 100)}%
								</span>
							))}
						</div>
						{team.warnings?.map(w => (
							<div key={w} className="ag-dim" style={{ color: 'var(--warning)' }}>{w}</div>
						))}
						<div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}>
							<FilledButtonM3 disabled={!team.workflow} onClick={() => void runTeam()}>
								<Play size={13} /> 运行此提议
							</FilledButtonM3>
							{teamRunNote && <span className="ag-dim" style={{ margin: 0 }}>{teamRunNote}</span>}
						</div>
					</>
				)}
			</section>

			<section className="ag-section">
				<div className="ag-head"><FileText size={15} /> 交接文档（固定四段 · 写入同步文件夹）</div>
				<div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
					<Field label="标题">
						<input value={ho.title} onChange={e => setHo({ ...ho, title: e.target.value })} placeholder="例如：同步冲突提示" spellCheck={false} />
					</Field>
					<Field label="目标（必填，接手时要达成什么）">
						<input value={ho.goal} onChange={e => setHo({ ...ho, goal: e.target.value })} placeholder="写不清目标的交接不是交接" spellCheck={false} />
					</Field>
				</div>
				<Field label="已完成">
					<textarea value={ho.done} onChange={e => setHo({ ...ho, done: e.target.value })} style={{ minHeight: 64 }} spellCheck={false} />
				</Field>
				<Field label="阻塞与风险">
					<textarea value={ho.blockers} onChange={e => setHo({ ...ho, blockers: e.target.value })} style={{ minHeight: 64 }} spellCheck={false} />
				</Field>
				<Field label="下一步与落点">
					<textarea value={ho.next} onChange={e => setHo({ ...ho, next: e.target.value })} style={{ minHeight: 64 }} spellCheck={false} />
				</Field>
				<div style={{ display: 'flex', gap: 8 }}>
					<TextButtonM3 onClick={() => void previewHandoff()}><Eye size={13} /> 预览</TextButtonM3>
					<FilledButtonM3 onClick={() => void writeHandoff()}><FileText size={13} /> 写入</FilledButtonM3>
				</div>
				{hoPreview && <pre style={preStyle}>{hoPreview}</pre>}
				{hoWrite && <div className="ag-dim" style={{ color: hoWrite.startsWith('（被拒绝') ? 'var(--danger)' : 'var(--ok)' }}>{hoWrite}</div>}

				{hoFiles.length > 0 && (
					<table className="tr-table" style={{ marginTop: 10 }}>
						<thead><tr><th>已有交接</th><th>大小</th><th></th></tr></thead>
						<tbody>
							{hoFiles.map(f => (
								<tr key={f.name}>
									<td>{f.name}</td>
									<td>{f.bytes} B</td>
									<td><TextButtonM3 onClick={() => void openHandoff(f)}><Eye size={12} /> 查看</TextButtonM3></td>
								</tr>
							))}
						</tbody>
					</table>
				)}
			</section>

			{replay && (
				<Modal title={`回放 · ${replay.runId}（只读）`} onClose={() => setReplay(null)}>
					<pre style={{ ...preStyle, maxHeight: 420, marginTop: 0 }}>{replay.text}</pre>
					<div className="form-actions">
						{/* 契约①：这里刻意只有关闭——回放不调 agent、不写东西，加「重跑」等于复制用户数据 */}
						<FilledButtonM3 onClick={() => setReplay(null)}>关闭</FilledButtonM3>
					</div>
				</Modal>
			)}
			{hoOpen && (
				<Modal title={hoOpen.name} onClose={() => setHoOpen(null)}>
					<pre style={{ ...preStyle, maxHeight: 420, marginTop: 0 }}>{hoOpen.text}</pre>
					<div className="form-actions">
						<FilledButtonM3 onClick={() => setHoOpen(null)}>关闭</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}