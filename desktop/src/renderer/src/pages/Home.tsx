import { useEffect, useState } from 'react';
import { useNavigate } from '../router';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { ListRow } from '../components/m3/chrome';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRing, PaneSectionBlock, PaneActionRow, PaneEmptyInline } from '../components/m3/paneblocks';
import { CalendarDays, CircleCheck, StickyNote, Bot, Zap, ChevronRight, Plus } from 'lucide-react';
import type { DataStats, Schedule, Todo } from '@shared/types';
import type { AutomationDef } from '@shared/ipc';
import Timeline from '../components/Timeline';
import { localDayKey } from '../daykey';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import { WIDE } from '../lib/board';

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

	// ---- A2 宽屏（spec §5 首页行）：两板——主框=板头+时间轴(限宽 680)+未完成待办行；
	// 右板=Overview(今日完成环卡+今日日程/待办节块+入口块)。窄窗走原单列（零回归）。 ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	if (wide) {
		const todayKey = localDayKey();
		const todayTodos = todos.filter(t => t.date === todayKey);
		const doneToday = todayTodos.filter(t => t.status === 'done').length;
		const progress = todayTodos.length ? doneToday / todayTodos.length : 0;
		return (
			<div ref={pageRef} className="home-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="首页" subtitle={`${greeting()} · ${dateLabel}`} />
						<div className="home-main-body">
							<div className="home-row-head">
								<span><CalendarDays size={14} /> 今日时间轴</span>
								<button className="nc-link" onClick={() => nav('plans')}>全部 <ChevronRight size={12} /></button>
							</div>
							<div className="home-timeline-col">
								<Timeline
									schedules={schedules}
									todos={todos}
									automations={automations}
									onOpen={kind => nav(kind === 'automation' ? 'automations' : 'plans')}
								/>
							</div>
							<BoardSectionTitle text="未完成待办" />
							{openTodos.length === 0
								? <PaneEmptyInline text="待办已清空" />
								: openTodos.map(t => (
									<ListRow key={t.id}
										title={<span className="card-title">{t.title}</span>}
										meta={<span>{[t.date, t.time, t.priority ? `P${t.priority}` : null].filter(Boolean).join(' · ')}</span>}
										onClick={() => nav('plans')}
									/>
								))}
						</div>
					</>}
					secondary={<>
						<PaneStatRing
							progress={progress}
							value={`${Math.round(progress * 100)}%`}
							sub={`今日待办 ${doneToday}/${todayTodos.length} 完成`}
							chips={<>
								<span className="pane-chip">日程 {schedules.length}</span>
								<span className="pane-chip">未完成 {stats?.todosOpen ?? openTodos.length}</span>
							</>}
						/>
						<PaneSectionBlock title="今日日程">
							{schedules.length === 0
								? <PaneEmptyInline text="今天没有日程" />
								: schedules.map(s => (
									<ListRow key={s.id}
										title={<span className="card-title">{s.title}</span>}
										meta={<span>{[hhmm(s.startIso), hhmm(s.endIso)].filter(Boolean).join(' – ')}</span>}
										onClick={() => nav('plans')}
									/>
								))}
						</PaneSectionBlock>
						<PaneActionRow
							actions={[
								{ key: 'plans', icon: <CalendarDays size={15} />, title: '计划', meta: '日程与待办', onClick: () => nav('plans') },
								{ key: 'auto', icon: <Zap size={15} />, title: '自动化', meta: `${automations.length} 条`, onClick: () => nav('automations') },
							]}
						/>
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
