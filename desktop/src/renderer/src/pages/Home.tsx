import { useEffect, useState } from 'react';
import { useNavigate } from '../router';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRing, PaneSectionBlock, PaneActionRow, PaneEmptyInline } from '../components/m3/paneblocks';
import { CalendarDays, CircleCheck, StickyNote, Bot, Zap, ChevronRight, Plus, Timer, CalendarRange, Search } from 'lucide-react';
import type { DataStats, Schedule, Todo } from '@shared/types';
import type { AutomationDef } from '@shared/ipc';
import Timeline from '../components/Timeline';
import { localDayKey } from '../daykey';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';

function greeting(): string {
	const h = new Date().getHours();
	if (h < 6) { return '夜深了'; }
	if (h < 12) { return '早上好'; }
	if (h < 14) { return '中午好'; }
	if (h < 18) { return '下午好'; }
	return '晚上好';
}

/** ISO 时间戳 → HH:MM（Schedule 契约只有 startIso/endIso，没有 time 字段）。 */
function hhmm(iso?: string): string {
	return iso ? iso.slice(11, 16) : '';
}

export default function HomePage() {
	const nav = useNavigate();
	const [stats, setStats] = useState<DataStats | null>(null);
	const [schedules, setSchedules] = useState<Schedule[]>([]);
	const [todos, setTodos] = useState<Todo[]>([]);
	const [automations, setAutomations] = useState<AutomationDef[]>([]);

	useEffect(() => {
		window.planability.stats().then(setStats).catch(() => undefined);
		// local date (phone AppTime parity) — toISOString is UTC and drifts before 08:00.
		// G 类 MEDIUM 后收口：唯一算法在 daykey helper，Schedules 的「今天/明天」同源。
		const today = localDayKey();
		window.planability.list<Schedule>('schedules').then(list => {
			setSchedules(list.filter(s => (s.date ?? s.startIso?.slice(0, 10)) === today));
		}).catch(() => undefined);
		window.planability.list<Todo>('todos').then(setTodos).catch(() => undefined);
		window.planability.automationList().then(setAutomations).catch(() => undefined);
	}, []);

	const openTodos = todos.filter(t => t.status !== 'done').slice(0, 5);

	const dateLabel = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
	// 手机口径：2026年11月23日 星期一（zh-CN locale 不带空格）
	const dNow = new Date();
	const fullDateLabel = `${dNow.getFullYear()}年${dNow.getMonth() + 1}月${dNow.getDate()}日 星期${'日一二三四五六'[dNow.getDay()]}`;

	// ---- A2 宽屏（spec §5 首页行 + 手机实拍对齐）：主板=「今天」+完成胶囊+满幅时间轴；
	// 右板=「今日概览」四节（完成率/日程/待办/快捷/今日评分）。窄窗走原单列（零回归）。
	// 注：spec §5 写「Timeline 限宽 680」与手机实拍冲突——手机 HomeScreen 时间轴保留满幅
	// （记忆「HomeScreen 时间轴保留满幅」自证），遵实拍铺满主板。 ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;

	const todayKey = localDayKey();
	const todayTodos = todos.filter(t => t.date === todayKey);
	const doneToday = todayTodos.filter(t => t.status === 'done').length;
	const todoTotal = todayTodos.length;
	const donePct = todoTotal > 0 ? Math.round((doneToday / todoTotal) * 100) : 0;

	if (wide) {
		return (
			// ref 两个分支都要挂：宽窄判据来自这块容器的实测宽（只挂宽屏分支会死锁在窄版）
			<div ref={pageRef} className="home-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader
							leading={
								<button type="button" className="board-back" aria-label="搜索" onClick={() => nav('notes')}>
									<Search size={16} />
								</button>
							}
							title="今天"
							subtitle={fullDateLabel}
							actions={
								// 手机 v79：一枚合并胶囊「评分 0 ｜ 完成 0%」（细竖线分隔），不是两枚
								<span className="home-done-pill">
									评分 0
									<i className="home-pill-div" />
									完成 {donePct}%
								</span>
							}
						/>
						<div className="home-main-body">
							{/* 手机 v79 实测：时间轴是板内居中的窄列（网格线 x315→969 ≈ 654dp），不是满幅 */}
							<div style={{ maxWidth: 720, margin: '0 auto' }}>
								<Timeline
									schedules={schedules}
									todos={todos}
									automations={automations}
									onOpen={kind => nav(kind === 'automation' ? 'automations' : 'plans')}
								/>
							</div>
						</div>
					</>}
					secondary={<>
						<BoardHeader title="今日概览" subtitle={fullDateLabel} />
						<div className="home-overview">
							<BoardSectionTitle text="完成率" />
							<PaneStatRing
								progress={todoTotal > 0 ? doneToday / todoTotal : 0}
								value={todoTotal > 0 ? `${donePct}%` : '暂无任务'}
								sub={todoTotal > 0 ? `完成 ${doneToday} / ${todoTotal} 项` : ''}
								chips={<>
									<span className="pane-chip">日程 {schedules.length}</span>
									<span className="pane-chip">待办 {todoTotal}</span>
								</>}
							/>

							{/* 手机 v79：今天没有日程时整节隐藏（UI 树实测右板只有 完成率/待办/快捷/今日评分） */}
							{schedules.length > 0 && (
								<>
									<BoardSectionTitle text="日程" trailing={<span className="pane-chip">共 {schedules.length}</span>} />
									<PaneSectionBlock title="" flush>
										{schedules.map(s => (
											<div key={s.id} className="home-ov-row">
												<span className="home-ov-time">{hhmm(s.startIso) || '全天'}</span>
												<span className="home-ov-title" style={{ flex: 1 }}>{s.title}</span>
											</div>
										))}
									</PaneSectionBlock>
								</>
							)}

							<BoardSectionTitle text="待办" trailing={<span className="pane-chip">{doneToday}/{todoTotal}</span>} />
							<PaneSectionBlock title="" flush>
								{todayTodos.length === 0
									? <PaneEmptyInline text="这一天没有待办" />
									: todayTodos.map(t => (
										<div key={t.id} className="home-ov-row">
											<span className={`home-ov-dot ${t.status === 'done' ? 'done' : ''}`} />
											<span className={`home-ov-title ${t.status === 'done' ? 'done' : ''}`} style={{ flex: 1 }}>{t.title}</span>
											<span className="home-ov-time" style={{ color: 'var(--text-faint)' }}>{t.time ?? ''}</span>
										</div>
									))}
							</PaneSectionBlock>

							<BoardSectionTitle text="快捷" />
							<PaneActionRow
								actions={[
									{ key: 'focus', icon: <Timer size={15} />, title: '专注', meta: '番茄钟 / 正计时', trailing: <span className="home-ov-act">开始</span>, onClick: () => nav('focus') },
									{ key: 'cal', icon: <CalendarRange size={15} />, title: '日历', meta: '月视图 · 倒数日', trailing: <span className="home-ov-act">打开</span>, onClick: () => nav('plans') },
								]}
							/>

							<BoardSectionTitle text="今日评分" trailing={<span className="pane-chip">0 分</span>} />
							<PaneSectionBlock title="" flush>
								<div className="home-score-row">
									<span className="home-score-label">完成率</span>
									<span className="home-score-bar"><i style={{ width: `${donePct}%` }} /></span>
									<span className="home-score-val">{todoTotal > 0 ? `${doneToday}/${todoTotal} 项` : '暂无任务'}</span>
								</div>
								<div className="home-score-row">
									<span className="home-score-label">屏幕</span>
									<span className="home-score-bar"><i style={{ width: '0%' }} /></span>
									<span className="home-score-val">无数据</span>
								</div>
								<div className="home-score-row">
									<span className="home-score-label">睡眠</span>
									<span className="home-score-bar"><i style={{ width: '0%' }} /></span>
									<span className="home-score-val">未记录</span>
								</div>
							</PaneSectionBlock>
						</div>
					</>}
				/>
			</div>
		);
	}

	return (
		// ref 两个分支都要挂：宽窄判据来自这块容器的实测宽（只挂宽屏分支会死锁在窄版）
		<div ref={pageRef} className="home-page">
			<div className="home-hero">
				<h1>{greeting()}</h1>
				<p>{dateLabel} · Planability 已就绪</p>
			</div>

			<div className="stat-grid home-stats">
				<button className="stat-card clickable" onClick={() => nav('plans')}>
					<div className="stat-num">{stats?.schedules ?? 0}</div>
					<div className="stat-label"><CalendarDays size={12} /> 日程</div>
				</button>
				<button className="stat-card clickable" onClick={() => nav('plans')}>
					<div className="stat-num">{stats?.todosOpen ?? 0}</div>
					<div className="stat-label"><CircleCheck size={12} /> 未完成待办</div>
				</button>
				<button className="stat-card clickable" onClick={() => nav('notes')}>
					<div className="stat-num">{stats?.notes ?? 0}</div>
					<div className="stat-label"><StickyNote size={12} /> 笔记</div>
				</button>
				<button className="stat-card clickable" onClick={() => nav('info')}>
					<div className="stat-num"><Bot size={22} /></div>
					<div className="stat-label">AI 工作台</div>
				</button>
			</div>

			<div className="home-row-head">
				<span><CalendarDays size={14} /> 今日时间轴</span>
				<button className="nc-link" onClick={() => nav('plans')}>全部 <ChevronRight size={12} /></button>
			</div>
			<Timeline
				schedules={schedules}
				todos={todos}
				automations={automations}
				onOpen={kind => nav(kind === 'automation' ? 'automations' : 'plans')}
			/>

			<div className="home-row-head">
				<span><CircleCheck size={14} /> 未完成待办</span>
				<button className="nc-link" onClick={() => nav('plans')}>全部 <ChevronRight size={12} /></button>
			</div>
			{openTodos.length === 0
				? <div className="home-empty">待办已清空</div>
				: openTodos.map(t => (
					<div className="card" key={t.id}>
						<div className="card-main">
							<div className="card-title">{t.title}</div>
							{(t.date || t.time || t.priority) && (
								<div className="card-meta">{[t.date, t.time, t.priority ? `优先级 P${t.priority}` : null].filter(Boolean).join(' · ')}</div>
							)}
						</div>
					</div>
				))}

			<div className="home-quick">
				<FilledButtonM3 onClick={() => nav('info')}>
					<IconM3 slot="icon"><Bot size={14} /></IconM3> 问 AI
				</FilledButtonM3>
				<TextButtonM3 onClick={() => nav('plans')}>
					<IconM3 slot="icon"><Plus size={14} /></IconM3> 计划
				</TextButtonM3>
				<TextButtonM3 onClick={() => nav('automations')}>
					<IconM3 slot="icon"><Zap size={14} /></IconM3> 自动化
				</TextButtonM3>
			</div>
		</div>
	);
}
