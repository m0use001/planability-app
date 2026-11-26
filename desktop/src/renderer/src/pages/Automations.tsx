import { useEffect, useRef, useState } from 'react';
import type { AutomationDef, AutomationInput, TaskLogEntry } from '@shared/ipc';
import type { WorkflowInfo } from '@shared/chat';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { BoardHeader, BoardSectionTitle } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock, PaneEmptyInline } from '../components/m3/paneblocks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import './hub.css';
import './plans.css';

/* M20-2 kind 表单（pc-a renderer 线）：自动化到点后做什么，两种——
 * 'ask'（历史行为：单发指令给主槽模型）/ 'agent'（派真实多 agent 工作流）。
 * 契约硬要求（pc-b UI_CONTRACT §6）：①四字段全部可选、读取回落，老字段不动；
 * ②kind 未知一律当 ask 展示——绝不会因为读到一个没见过的值就把用户的每日简报
 * 显示成派 agent 的任务；③新建 agent 任务没有任务文案会被拒——表单在客户端就拦。
 * 引擎里 'workflow' 与 'agent' 同路（taskKind 归一），UI 只发 'agent'。 */

type Draft = AutomationInput & { mode: 'interval' | 'daily'; time: string };

const EMPTY_DRAFT: Draft = {
	name: '', prompt: '', mode: 'interval', intervalMinutes: 15, time: '09:00',
	kind: 'ask', workflowId: '', taskText: '',
};

/** 引擎语义（automation-task.ts taskKind）：'agent'/'workflow' 同路派工作流，未知回落 ask。
 * 参数故意收宽成 unknown：automations.json 是用户文件，运行时可能躺着一个类型上不存在的值。 */
function isAgentKind(k: unknown): boolean {
	return k === 'agent' || k === 'workflow';
}

function triggerLabel(a: AutomationDef): string {
	if (a.intervalMinutes && a.intervalMinutes > 0) { return `每 ${a.intervalMinutes} 分钟`; }
	if (a.dailyHour !== undefined && a.dailyHour >= 0) {
		return `每天 ${String(a.dailyHour).padStart(2, '0')}:${String(a.dailyMinute ?? 0).padStart(2, '0')}`;
	}
	return '未配置触发';
}

function relTime(ts: number): string {
	if (!ts) { return '未执行过'; }
	const diff = Date.now() - ts;
	if (diff < 60_000) { return '刚刚'; }
	if (diff < 3600_000) { return `${Math.floor(diff / 60_000)} 分钟前`; }
	if (diff < 86400_000) { return `${Math.floor(diff / 3600_000)} 小时前`; }
	return new Date(ts).toLocaleString('zh-CN');
}

export default function AutomationsPage() {
	const [items, setItems] = useState<AutomationDef[]>([]);
	const [workflows, setWorkflows] = useState<WorkflowInfo[]>([]);
	const [creating, setCreating] = useState(false);
	const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
	const [logOf, setLogOf] = useState<{ id: number; text: string } | null>(null);
	// agent 任务的运行台账（automationTaskLog）：条目带 runId，可就地回放 journal。
	const [taskLog, setTaskLog] = useState<{ id: number; entries: TaskLogEntry[] } | null>(null);
	const [replay, setReplay] = useState<{ runId: string; text: string } | null>(null);
	const [formErr, setFormErr] = useState('');

	const refresh = () => {
		window.planability.automationList().then(setItems).catch(console.error);
		// 工作流选择器的数据源；列表可能为空（例如 workflows 目录还没有合法文件），如实显示。
		window.planability.chat.workflowList().then(setWorkflows).catch(() => undefined);
	};
	useEffect(() => {
		refresh();
		// Sweep runs in the main process — poll so "last run" goes live without interaction.
		const t = setInterval(refresh, 30_000);
		return () => clearInterval(t);
	}, []);

	const draftAgent = isAgentKind(draft.kind);
	// 选择器只列「能跑的」：解析失败或没步骤的工作流配给定时任务，到点必失败。
	const runnableWorkflows = workflows.filter(w => !(w.problems ?? []).length && (w.steps ?? []).length > 0);

	const create = async () => {
		setFormErr('');
		if (!draft.name.trim()) { return; }
		const agentTask = isAgentKind(draft.kind);
		if (!agentTask && !draft.prompt.trim()) { return; }
		// 契约③：agent 任务没有任务文案会被主进程拒——表单先拦，别让用户撞墙。
		if (agentTask && !draft.workflowId) { setFormErr('工作流任务必须选一个工作流。'); return; }
		if (agentTask && !(draft.taskText ?? '').trim()) { setFormErr('工作流任务必须写明任务文案（到点要达成什么）。'); return; }
		const input: AutomationInput = {
			name: draft.name.trim(),
			// agent 任务由 taskText 驱动，prompt 只是 ask 路径的指令；空串即可，不造占位符。
			prompt: agentTask ? '' : draft.prompt.trim(),
			kind: agentTask ? 'agent' : 'ask',
		};
		if (agentTask) {
			input.workflowId = draft.workflowId;
			input.taskText = (draft.taskText ?? '').trim();
			const t = Number(draft.timeoutMinutes);
			if (Number.isFinite(t) && t > 0) { input.timeoutMinutes = Math.min(Math.round(t), 240); }
		}
		if (draft.mode === 'interval') {
			input.intervalMinutes = Math.max(1, Number(draft.intervalMinutes) || 15);
		} else {
			const [h, m] = draft.time.split(':').map(Number);
			input.dailyHour = h;
			input.dailyMinute = m;
		}
		await window.planability.automationCreate(input);
		setCreating(false);
		setDraft(EMPTY_DRAFT);
		await refresh();
	};

	const toggle = async (a: AutomationDef) => {
		await window.planability.automationSetEnabled(a.id, !a.enabled);
		await refresh();
	};

	const remove = async (a: AutomationDef) => {
		if (!window.confirm(`删除自动化「${a.name}」？`)) { return; }
		await window.planability.automationRemove(a.id);
		if (logOf?.id === a.id) { setLogOf(null); }
		await refresh();
	};

	const showLog = async (a: AutomationDef) => {
		const open = logOf?.id === a.id || taskLog?.id === a.id;
		if (open) { setLogOf(null); setTaskLog(null); setReplay(null); return; }
		setLogOf(null); setTaskLog(null); setReplay(null);
		// agent 任务走运行台账（逐条含 runId/steps/error）；ask 任务才是文本日志文件。
		if (isAgentKind(a.kind)) {
			const entries = await window.planability.automationTaskLog(a.id, 20).catch(() => [] as TaskLogEntry[]);
			setTaskLog({ id: a.id, entries });
		} else {
			const text = await window.planability.automationLog(a.id);
			setLogOf({ id: a.id, text: text || '（还没有执行日志）' });
		}
	};

	const showReplay = async (runId: string) => {
		const r = await window.planability.chat.workflowReplay(runId);
		setReplay({ runId, text: r.ok && r.text ? r.text : `回放失败（${runId}）：journal 不在内存中——进程重启或已越界淘汰` });
	};

	const agentMeta = (a: AutomationDef): string => {
		const bits: string[] = [`任务：${(a.taskText ?? '').trim() || '（无任务文案）'}`];
		bits.push(`工作流 ${a.workflowId ?? '（未指定）'}`);
		if (a.timeoutMinutes && a.timeoutMinutes > 0) { bits.push(`超时 ${a.timeoutMinutes} 分`); }
		return bits.join(' · ');
	};

	// ---- 批 B（2026-11-24，手机 v79 我的页进入=整页单列覆盖层，对账批 B 返工）：
	// 撤「左列表+右概览统计」两板，改整页单列——顶部大标题+工具行+边框任务卡列表；
	// 选中后整页进详情/日志（Esc 返回）。手机自动化没有「右板概览统计」。
	// 新建表单（四百行）与回放刻意仍走 Modal——手机拍板「自动化的表单不进右板」照抄。 ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	const [selId, setSelId] = useState<number | null>(null);
	const sel = items.find(a => a.id === selId) ?? null;
	const lastSelRef = useRef<AutomationDef | null>(null);
	if (sel) { lastSelRef.current = sel; }
	const deckSel = sel ?? lastSelRef.current;

	// 选中即把日志/运行台账装入详情页（窄屏仍走行内展开）
	useEffect(() => {
		if (!wide || !sel) { setLogOf(null); setTaskLog(null); setReplay(null); return; }
		let cancelled = false;
		void (async () => {
			if (isAgentKind(sel.kind)) {
				const entries = await window.planability.automationTaskLog(sel.id, 20).catch(() => [] as TaskLogEntry[]);
				if (!cancelled) { setLogOf(null); setTaskLog({ id: sel.id, entries }); }
			} else {
				const text = await window.planability.automationLog(sel.id).catch(() => '');
				if (!cancelled) { setTaskLog(null); setLogOf({ id: sel.id, text: text || '（还没有执行日志）' }); }
			}
		})();
		return () => { cancelled = true; };
	}, [wide, sel, selId]);

	// Esc 返回（宽屏）
	useEffect(() => {
		if (!wide) { return; }
		const onKey = (e: KeyboardEvent) => {
			if (e.key !== 'Escape') { return; }
			if (selId != null) { e.preventDefault(); setSelId(null); }
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, [wide, selId]);

	if (wide) {
		const enabledCount = items.filter(a => a.enabled).length;
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="hub-single">
				{deckSel ? (
					<>
						<BoardHeader title={deckSel.name} leading={sel ? (
							<button type="button" className="board-back" onClick={() => setSelId(null)} aria-label="返回">←</button>
						) : undefined}
							actions={<TextButtonM3 className="m3-text-danger" onClick={() => { void remove(deckSel); setSelId(null); }}>删除</TextButtonM3>}
						/>
						<div className="hub-detail">
							<div className="hub-detail-inner">
								<div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12 }}>
									<input className="check" type="checkbox" checked={deckSel.enabled} onChange={() => void toggle(deckSel)} aria-label="启用/停用" />
									<span style={{ fontSize: 13, fontWeight: 600 }}>{deckSel.enabled ? '启用中' : '已停用'}</span>
									<span className="type-tag">{isAgentKind(deckSel.kind) ? '工作流任务' : '指令任务'}</span>
								</div>
								<PaneStatRow
									items={[
										{ value: triggerLabel(deckSel), label: '触发' },
										{ value: relTime(deckSel.lastRun), label: '上次执行' },
									]}
								/>
								<PaneSectionBlock title={isAgentKind(deckSel.kind) ? '任务' : '指令'} flush>
									<div style={{ whiteSpace: 'pre-wrap', fontSize: 13.5, lineHeight: 1.7 }}>
										{isAgentKind(deckSel.kind) ? agentMeta(deckSel) : deckSel.prompt || '（无指令）'}
									</div>
								</PaneSectionBlock>
								<BoardSectionTitle text={isAgentKind(deckSel.kind) ? '运行台账（可回放）' : '执行日志'} />
								{logOf?.id === deckSel.id && (
									<pre style={{
										background: 'var(--md-sys-color-surface-container-high)', border: 'none',
										borderRadius: 16, padding: '12px 14px', fontSize: 12,
										maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', margin: '0 0 12px',
									}}>{logOf.text}</pre>
								)}
								{taskLog?.id === deckSel.id && (taskLog.entries.length === 0
									? <PaneEmptyInline text="还没有任务运行记录（到点触发后逐条落这里，可回放）" />
									: taskLog.entries.map((e, i) => (
										<div key={i} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', padding: '4px 0' }}>
											<span className={`guard-chip ${e.ok ? '' : 'trip'}`}>{e.ok ? '完成' : '失败'}</span>
											<span style={{ color: 'var(--text-faint)', fontSize: 12 }}>{new Date(e.at).toLocaleString('zh-CN')}</span>
											{e.runId && (
												<TextButtonM3 style={{ fontSize: 11.5 }} onClick={() => void showReplay(e.runId as string)}>
													回放 {e.runId}
												</TextButtonM3>
											)}
											{e.summary && <span className="plans-flat-row__meta" style={{ flexBasis: '100%' }}>{e.summary.slice(0, 120)}</span>}
										</div>
									)))}
							</div>
						</div>
					</>
				) : (
					<>
						<BoardHeader title="自动化" subtitle={`${items.length} 条 · ${enabledCount} 启用`}
							actions={<TextButtonM3 onClick={() => setCreating(true)}>＋ 新建</TextButtonM3>} />
						<div className="hub-list">
							<div className="hub-list-inner">
								{items.length === 0 && <Empty text="还没有自动化。例如：每 30 分钟「汇总我的未完成待办」" />}
								{items.map(a => (
									<button key={a.id} type="button" className="hub-row-card" style={{ border: '1px solid var(--md-sys-color-outline-variant)', background: 'transparent' }}
										onClick={() => setSelId(a.id)}>
										<input className="check" type="checkbox" checked={a.enabled}
											onClick={e => e.stopPropagation()} onChange={() => void toggle(a)} aria-label="启用/停用" />
										<span className="hub-row-card__main">
											<span className="hub-row-card__title">
												<span className="hub-row-card__title-text">{a.name}</span>
												{isAgentKind(a.kind) && <span className="type-tag">工作流</span>}
											</span>
											<span className="plans-flat-row__meta">{triggerLabel(a)} · 上次执行 {relTime(a.lastRun)}</span>
										</span>
									</button>
								))}
							</div>
						</div>
					</>
				)}
			</div>
		);
	}

	return (
		// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
		<div ref={pageRef}>
			<PageHeader title="自动化" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				每 60 秒检查一次。指令型：到点发给主槽模型（无工具），结果写日志并发通知；
				工作流型：到点派真实多 agent 跑一个工作流，逐步落日志、可回放。
			</p>
			{items.length === 0 && <Empty text="还没有自动化。例如：每 30 分钟「汇总我的未完成待办」" />}
			{items.map(a => {
				const agentTask = isAgentKind(a.kind);
				const logOpen = logOf?.id === a.id || taskLog?.id === a.id;
				return (
					<div className="card" key={a.id} style={{ display: 'block' }}>
						<div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
							<input className="check" type="checkbox" checked={a.enabled} onChange={() => toggle(a)} />
							<div className="card-main">
								<div className="card-title">
									{a.name}
									{agentTask && <span className="type-tag" style={{ marginLeft: 8 }} title={a.kind === 'workflow' ? 'kind=workflow（引擎按 agent 任务派发）' : 'agent 任务'}>工作流</span>}
								</div>
								<div className="card-meta">
									{triggerLabel(a)} · {agentTask ? agentMeta(a) : `指令：${a.prompt.slice(0, 40)}${a.prompt.length > 40 ? '…' : ''}`} · 上次执行 {relTime(a.lastRun)}
								</div>
							</div>
							<TextButtonM3 onClick={() => showLog(a)}>{logOpen ? '收起日志' : '日志'}</TextButtonM3>
							<TextButtonM3 className="m3-text-danger" onClick={() => remove(a)}>删除</TextButtonM3>
						</div>
						{logOf?.id === a.id && (
							<pre style={{
								background: 'var(--surface)', border: '1px solid var(--outline-dim)',
								borderRadius: 'var(--shape-sm)', padding: '10px 12px', fontSize: 12,
								maxHeight: 240, overflow: 'auto', whiteSpace: 'pre-wrap', marginTop: 10
							}}>{logOf.text}</pre>
						)}
						{taskLog?.id === a.id && (
							<div style={{ marginTop: 10 }}>
								{taskLog.entries.length === 0 && <div className="ag-dim" style={{ padding: 0 }}>还没有任务运行记录（到点触发后逐条落这里，可回放）</div>}
								{taskLog.entries.map((e, i) => (
									<div key={i} style={{
										background: 'var(--surface)', border: '1px solid var(--outline-dim)',
										borderRadius: 'var(--shape-sm)', padding: '8px 10px', fontSize: 12, marginBottom: 6,
									}}>
										<div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
											<span className={`guard-chip ${e.ok ? '' : 'trip'}`}>{e.ok ? '完成' : '失败'}</span>
											<span style={{ color: 'var(--text-faint)' }}>{new Date(e.at).toLocaleString('zh-CN')}</span>
											{e.runId && (
												<TextButtonM3 style={{ fontSize: 11.5 }} onClick={() => void showReplay(e.runId as string)}>
													回放 {e.runId}
												</TextButtonM3>
											)}
										</div>
										{e.summary && <div style={{ whiteSpace: 'pre-wrap', marginTop: 4, maxHeight: 140, overflow: 'auto' }}>{e.summary}</div>}
										{e.error && <div style={{ color: 'var(--danger)', whiteSpace: 'pre-wrap', marginTop: 4 }}>{e.error}</div>}
										{(e.steps ?? []).length > 0 && (
											<div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 4 }}>
												{(e.steps ?? []).map(s => (
													<span key={s.stepId} className={`guard-chip ${s.status === 'failed' ? 'trip' : ''}`} title={`${s.stepId} · ${s.role}`}>
														{s.stepId}:{s.status}
													</span>
												))}
											</div>
										)}
									</div>
								))}
							</div>
						)}
					</div>
				);
			})}

			{creating && (
				<Modal title="新建自动化" onClose={() => { setCreating(false); setFormErr(''); setDraft(EMPTY_DRAFT); }} dirty={JSON.stringify(draft) !== JSON.stringify(EMPTY_DRAFT)}>
					<Field label="名称">
						<input value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} autoFocus placeholder="例如：午间待办汇总" />
					</Field>
					<Field label="到点做什么">
						<select
							value={draft.kind ?? 'ask'}
							onChange={e => setDraft({ ...draft, kind: e.target.value as AutomationInput['kind'] })}
						>
							<option value="ask">发一条指令给模型（单发，无工具）</option>
							<option value="agent">跑一个多 agent 工作流（真实派活）</option>
						</select>
					</Field>
					{!draftAgent ? (
						<Field label="执行指令（到点后发给模型）">
							<textarea value={draft.prompt} onChange={e => setDraft({ ...draft, prompt: e.target.value })} style={{ minHeight: 100 }} placeholder="例如：汇总我未完成的待办，按优先级列出前 5 条" />
						</Field>
					) : (
						<>
							<Field label="用哪个工作流">
								<select value={draft.workflowId ?? ''} onChange={e => setDraft({ ...draft, workflowId: e.target.value })}>
									<option value="">{runnableWorkflows.length ? '选择工作流…' : '（没有可运行的工作流）'}</option>
									{runnableWorkflows.map(w => (
										<option key={w.id} value={w.id}>{w.name}{w.overridden ? '（用户覆盖）' : ''}</option>
									))}
								</select>
							</Field>
							<Field label="任务文案（到点要达成什么，必填）">
								<textarea
									value={draft.taskText ?? ''}
									onChange={e => setDraft({ ...draft, taskText: e.target.value })}
									style={{ minHeight: 80 }}
									placeholder="例如：汇总本周未完成待办，按优先级排进下周计划"
								/>
							</Field>
							<Field label="整体超时（分钟，留空 = 默认 20，上限 240）">
								<input
									type="number" min={1} max={240}
									value={draft.timeoutMinutes ?? ''}
									onChange={e => setDraft({ ...draft, timeoutMinutes: e.target.value === '' ? undefined : Number(e.target.value) })}
								/>
							</Field>
						</>
					)}
					<Field label="触发方式">
						<select value={draft.mode} onChange={e => setDraft({ ...draft, mode: e.target.value as Draft['mode'] })}>
							<option value="interval">按间隔重复</option>
							<option value="daily">每天固定时刻</option>
						</select>
					</Field>
					{draft.mode === 'interval' ? (
						<Field label="间隔（分钟）">
							<input type="number" min={1} value={draft.intervalMinutes ?? 15} onChange={e => setDraft({ ...draft, intervalMinutes: Number(e.target.value) })} />
						</Field>
					) : (
						<Field label="时刻">
							<input type="time" value={draft.time} onChange={e => setDraft({ ...draft, time: e.target.value })} />
						</Field>
					)}
					{formErr && <div style={{ color: 'var(--danger)', fontSize: 12.5, margin: '4px 0' }}>{formErr}</div>}
					<div className="form-actions">
						<TextButtonM3 onClick={() => { setCreating(false); setFormErr(''); setDraft(EMPTY_DRAFT); }}>取消</TextButtonM3>
						<FilledButtonM3 onClick={create}>创建</FilledButtonM3>
					</div>
				</Modal>
			)}

			{replay && (
				<Modal title={`任务回放 · ${replay.runId}（只读）`} onClose={() => setReplay(null)}>
					<pre style={{
						background: 'var(--surface)', border: '1px solid var(--outline-dim)',
						borderRadius: 'var(--shape-sm)', padding: '10px 12px', fontSize: 12,
						maxHeight: 420, overflow: 'auto', whiteSpace: 'pre-wrap', marginTop: 0,
					}}>{replay.text}</pre>
					<div className="form-actions">
						<FilledButtonM3 onClick={() => setReplay(null)}>关闭</FilledButtonM3>
				</div>
			</Modal>
		)}
		</div>
	);
}