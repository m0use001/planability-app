import { useEffect, useRef, useState } from 'react';
import { useNavigate } from '../router';
import { Play, Pause, RotateCcw, Check, Pill, CalendarDays, ChevronDown, SlidersHorizontal, BarChart3 } from 'lucide-react';
import type { Schedule, Todo } from '@shared/types';
import { PageHeader } from '../components/ui';
import { TextButtonM3, FilledButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { FilterChipM3 } from '../components/m3/chips';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock, PaneEmptyInline } from '../components/m3/paneblocks';
import { useCollection } from '../hooks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import { localDayKey } from '../daykey';
import { splitTodos } from './Todos';
import './focus.css';

const PRESETS = [15, 25, 45, 60];

function fmt(total: number): string {
	const m = Math.floor(total / 60);
	const s = total % 60;
	return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export default function FocusPage() {
	const [minutes, setMinutes] = useState(25);
	const [left, setLeft] = useState(25 * 60);
	const [running, setRunning] = useState(false);
	const [todaySec, setTodaySec] = useState(0);
	const [doneCount, setDoneCount] = useState(0);
	const [capsuleOn, setCapsuleOn] = useState(false);
	const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
	const stateRef = useRef({ left, minutes, running, doneCount });
	stateRef.current = { left, minutes, running, doneCount };
	const abandonRef = useRef<() => void>(() => { /* bound below */ });

	const refreshToday = () => window.planability.focusToday().then(setTodaySec).catch(() => undefined);
	useEffect(() => { refreshToday(); }, []);

	useEffect(() => {
		if (!running) {
			if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
			return;
		}
		timerRef.current = setInterval(() => {
			setLeft(prev => {
				if (prev <= 1) {
					// completed
					setRunning(false);
					void window.planability.focusLog(minutes * 60).then(refreshToday);
					setDoneCount(c => c + 1);
					return minutes * 60;
				}
				return prev - 1;
			});
		}, 1000);
		return () => { if (timerRef.current) { clearInterval(timerRef.current); } };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [running, minutes]);

	// ---- floating capsule (phone FloatBall parity) ----
	useEffect(() => {
		if (!capsuleOn) { return; }
		void window.planability.capsuleUpdate({ left, total: minutes * 60, running, round: doneCount + 1 });
	}, [capsuleOn, left, minutes, running, doneCount]);

	useEffect(() => {
		return window.planability.onFocusAction(action => {
			const s = stateRef.current;
			if (action === 'toggle') {
				// idle: nothing to toggle (capsule is informational); running/paused: pause/resume
				if (!(s.left >= s.minutes * 60 && !s.running)) { setRunning(r => !r); }
			} else if (action === 'end') {
				abandonRef.current();
			}
		});
	}, []);

	const pick = (m: number) => {
		setMinutes(m);
		setLeft(m * 60);
		setRunning(false);
	};

	const abandon = () => {
		if (running) {
			const spent = minutes * 60 - left;
			if (spent >= 30) {
				if (window.confirm(`已专注 ${fmt(spent)}，记录这段专注吗？`)) {
					void window.planability.focusLog(spent).then(refreshToday);
				}
			}
		}
		setRunning(false);
		setLeft(minutes * 60);
	};
	abandonRef.current = abandon;

	const toggleCapsule = async () => {
		if (capsuleOn) {
			await window.planability.capsuleHide();
			setCapsuleOn(false);
		} else {
			await window.planability.capsuleShow({ left, total: minutes * 60, running, round: doneCount + 1 });
			setCapsuleOn(true);
		}
	};

	const progress = 1 - left / (minutes * 60);
	const todayMin = Math.floor(todaySec / 60);

	// ---- A2 宽屏（spec §5 专注行，v76 起两块板）：左=计时舞台（垂直居中），右=概览（定高不滚、一屏收纳） ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	const todos = useCollection<Todo>('todos');
	const schedules = useCollection<Schedule>('schedules');
	const nav = useNavigate();
	const [openTodos] = splitTodos(todos.items);
	// 手机 v79 右板四节：近 7 天柱图 / 本周四格（focus.json 逐日聚合，PC 真实流水不造假）
	const [presetOpen, setPresetOpen] = useState(false);
	const [weekData, setWeekData] = useState<{ day: string; label: string; minutes: number }[]>([]);
	useEffect(() => { window.planability.focusWeek().then(setWeekData).catch(() => undefined); }, []);
	const todayKeyF = localDayKey();
	const todaySchedList = schedules.items
		.filter(s => (s.date ?? s.startIso?.slice(0, 10)) === todayKeyF)
		.sort((a, b) => (a.startIso ?? '').localeCompare(b.startIso ?? ''));
	const todayTodoList = todos.items.filter(t => t.date === todayKeyF);

	// 计时舞台本体（窄屏 .focus-wrap 与宽屏 .focus-stage 共用同一份内容）
	const stage = (
		<>
			<div className="focus-ring" style={{ '--p': progress } as React.CSSProperties}>
				<div className="focus-time">{fmt(left)}</div>
				<div className="focus-mode">{minutes} 分钟模式</div>
			</div>

			<div className="focus-presets">
				{PRESETS.map(m => (
					<FilterChipM3 key={m} selected={minutes === m} onClick={() => pick(m)}>
						{m} 分钟
					</FilterChipM3>
				))}
			</div>

			<div className="focus-controls">
				<TextButtonM3 onClick={abandon} title="重置（进行中超过30秒可记录）">
					<IconM3 slot="icon"><RotateCcw size={15} /></IconM3>
				</TextButtonM3>
				<FilledButtonM3 className="focus-main" onClick={() => setRunning(!running)}>
					{running ? <><IconM3 slot="icon"><Pause size={17} /></IconM3> 暂停</> : <><IconM3 slot="icon"><Play size={17} /></IconM3> 开始专注</>}
				</FilledButtonM3>
			</div>

			<div className="focus-today">
				<div className="stat-grid" style={{ justifyContent: 'center' }}>
					<div className="stat-card">
						<div className="stat-num">{todayMin}<span style={{ fontSize: 14 }}> 分</span></div>
						<div className="stat-label">今日专注</div>
					</div>
					<div className="stat-card">
						<div className="stat-num">{doneCount}</div>
						<div className="stat-label">本次会话完成</div>
					</div>
				</div>
				{doneCount > 0 && (
					<div className="home-empty" style={{ paddingTop: 14 }}>
						<Check size={14} style={{ verticalAlign: -2, color: 'var(--ok)' }} /> 本轮已完成，休息一下吧
					</div>
				)}
			</div>
		</>
	);

	const capsuleChip = (
		<FilterChipM3 elevated selected={capsuleOn}
			title="悬浮胶囊（显示/隐藏；单击暂停，长按结束）"
			onClick={() => void toggleCapsule()}>
			<IconM3 slot="icon"><Pill size={15} /></IconM3>
			悬浮胶囊
		</FilterChipM3>
	);

	if (wide) {
		// ---- 手机 v79 专注页（UI 树实测）：左=专注(板头3钮+438dp环+轮次点+25分钟▾+大按钮)，
		// 右=概览四节（今日专注卡/近7天柱图/本周四格/接下来要做） ----
		const weekTotal = weekData.reduce((n, x) => n + x.minutes, 0);
		const weekMax = Math.max(10, ...weekData.map(x => x.minutes));
		const weekAvg = Math.round(weekTotal / 7);
		let streak = 0;
		for (let i = weekData.length - 1; i >= 0 && weekData[i].minutes > 0; i--) { streak++; }
		const target = 120;
		const todayPct = Math.min(100, Math.round((todayMin / target) * 100));
		const hasWeek = weekTotal > 0;
		const roundDone = doneCount % 4;
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="focus-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader
							title="专注"
							actions={
								<div className="focus-hdr-actions">
									<button type="button" className="board-back" aria-label="悬浮球" title="悬浮胶囊（显示/隐藏；单击暂停，长按结束）" onClick={() => void toggleCapsule()}>
										{/* 批 D：手机 v79 板头第一钮=播放三角（悬浮球开关），非回形针/Pill */}
										<Play size={16} />
									</button>
									<button type="button" className="board-back" aria-label="番茄设置" onClick={() => setPresetOpen(v => !v)}>
										<SlidersHorizontal size={16} />
									</button>
									<button type="button" className="board-back" aria-label="专注统计" onClick={() => nav('stats')}>
										<BarChart3 size={16} />
									</button>
								</div>
							}
						/>
						<div className="focus-stage">
							<div className="focus-ring" style={{ '--p': progress } as React.CSSProperties}
								title="双击重置（进行中超过30秒可记录）" onDoubleClick={() => abandon()}>
								<div className="focus-time">{fmt(left)}</div>
								<div className="focus-mode">{running ? `${minutes} 分钟模式` : '选择时长开始专注'}</div>
							</div>
							<div className="focus-round-row">
								<span className="focus-dots">
									{/* 批 D：手机 v79=当前轮大实心点、已完成轮小实心点、未到轮空心小点 */}
								{[0, 1, 2, 3].map(i => <i key={i} className={`${i < roundDone && doneCount > 0 ? 'on' : ''} ${i === roundDone ? 'cur' : ''}`} />)}
								</span>
								<span className="focus-round-label">第 {roundDone + 1} / 4 轮</span>
							</div>
							<div className="focus-duration">
								<button type="button" className="focus-duration-chip" onClick={() => setPresetOpen(v => !v)}>
									{minutes} 分钟 <ChevronDown size={13} />
								</button>
								{presetOpen && (
									<div className="focus-preset-menu" role="menu">
										{PRESETS.map(m => (
											<button key={m} type="button" role="menuitem" className={minutes === m ? 'sel' : ''}
												onClick={() => { pick(m); setPresetOpen(false); }}>{m} 分钟</button>
										))}
									</div>
								)}
							</div>
							<div className="focus-controls">
								{/* 手机 v79 宽屏只有一枚大按钮；重置收进「环上双击」（窄屏保留显式按钮） */}
								<FilledButtonM3 className="focus-main" onClick={() => setRunning(!running)}>
									{running ? <><IconM3 slot="icon"><Pause size={17} /></IconM3> 暂停</> : <><IconM3 slot="icon"><Play size={17} /></IconM3> 开始专注</>}
								</FilledButtonM3>
							</div>
						</div>
					</>}
					secondary={<>
						<BoardHeader title="概览" />
						<div className="focus-overview">
							<BoardSectionTitle text="今日专注" />
							<PaneSectionBlock title="" flush>
								<div className="focus-today-card">
									<div className="focus-today-done">完成 {doneCount} 个番茄</div>
									<div className="focus-today-min">{todayMin} 分钟</div>
									<div className="focus-today-bar"><i style={{ width: `${todayPct}%` }} /></div>
									<div className="focus-today-target">目标 {target} 分钟 · 已完成 {todayPct}%</div>
								</div>
							</PaneSectionBlock>

							<BoardSectionTitle text="近 7 天" />
							<PaneSectionBlock title="" flush>
								<div className="focus-week-chart">
									{weekData.map(x => (
										<div key={x.day} className="focus-week-col">
											<div className="focus-week-barzone"><i style={{ height: `${Math.round((x.minutes / weekMax) * 100)}%` }} title={`${x.day}日 ${x.minutes} 分钟`} /></div>
											<span className="focus-week-label">{x.day}</span>
										</div>
									))}
								</div>
								{!hasWeek && <div className="focus-week-empty">近 7 天还没有专注记录</div>}
							</PaneSectionBlock>

							<BoardSectionTitle text="本周概览" />
							<PaneStatRow
								items={[
									{ value: <>{weekTotal}<span style={{ fontSize: 12 }}> 分钟</span></>, label: '合计' },
									{ value: <>{weekAvg}<span style={{ fontSize: 12 }}> 分</span></>, label: '日均' },
									{ value: <>{doneCount}<span style={{ fontSize: 12 }}> 个</span></>, label: '番茄' },
									{ value: <>{streak}<span style={{ fontSize: 12 }}> 天</span></>, label: '连续' },
								]}
							/>

							<BoardSectionTitle text="接下来要做" />
							<PaneSectionBlock title="" flush>
								<div className="focus-next-today">今日</div>
								{todaySchedList.length === 0 && todayTodoList.length === 0
									? <PaneEmptyInline text="今天没有待办或日程" />
									: <>
										{todaySchedList.map(s => (
											<div key={s.id} className="home-ov-row">
												<span className="home-ov-time">{s.startIso?.slice(11, 16) ?? '全天'}</span>
												<span className="plans-flat-row__title" style={{ flex: 1 }}>{s.title}</span>
											</div>
										))}
										{todayTodoList.map(t => (
											<div key={t.id} className="home-ov-row">
												<span className={`home-ov-dot ${t.status === 'done' ? 'done' : ''}`} />
												<span className="plans-flat-row__title" style={{ flex: 1 }}>{t.title}</span>
												<span className="home-ov-time" style={{ color: 'var(--text-faint)' }}>{t.time ?? ''}</span>
											</div>
										))}
									</>}
								<div className="focus-next-go">
									<button type="button" onClick={() => nav('plans')}>去计划页 ›</button>
								</div>
							</PaneSectionBlock>
						</div>
					</>}
				/>
			</div>
		);
	}

	return (
		<div ref={pageRef}>
			<PageHeader title="专注" action={capsuleChip} />
			<div className="focus-wrap">{stage}</div>
		</div>
	);
}
