import { useEffect, useRef, useState } from 'react';
import { useNavigate } from '../router';
import { Play, Pause, RotateCcw, Check, Pill, CalendarDays } from 'lucide-react';
import type { Todo } from '@shared/types';
import { PageHeader } from '../components/ui';
import { TextButtonM3, FilledButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { FilterChipM3 } from '../components/m3/chips';
import { BoardHeader, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock, PaneActionBlock, PaneEmptyInline } from '../components/m3/paneblocks';
import { useCollection } from '../hooks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
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
	const nav = useNavigate();
	const [openTodos] = splitTodos(todos.items);
	const nextUp = openTodos.slice(0, 3);

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
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="focus-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="专注" actions={capsuleChip} />
						<div className="focus-stage">{stage}</div>
					</>}
					secondary={<>
						<BoardHeader title="概览" />
						<div className="focus-overview">
							<PaneStatRow
								items={[
									{ value: <>{todayMin}<span style={{ fontSize: 12 }}> 分</span></>, label: '今日专注' },
									{ value: doneCount, label: '本次会话完成' },
								]}
							/>
							<PaneSectionBlock title="接下来要做" flush>
								{nextUp.length === 0
									? <PaneEmptyInline text="没有未完成待办" />
									: nextUp.map(t => (
										<button key={t.id} type="button" className="pane-action-block" style={{ background: 'transparent', padding: '8px 2px' }}
											onClick={() => nav('plans')}>
											<span className="pane-action-block__text">
												<span className="pane-action-block__title">{t.title}</span>
												<span className="pane-action-block__meta">{[t.date, t.time].filter(Boolean).join(' · ') || '去计划页安排'}</span>
											</span>
											<CalendarDays size={14} style={{ color: 'var(--text-faint)' }} />
										</button>
									))}
							</PaneSectionBlock>
							<PaneActionBlock
								icon={<Pill size={15} />}
								title="悬浮胶囊"
								meta={capsuleOn ? '显示中（单击暂停，长按结束）' : '已隐藏'}
								onClick={() => void toggleCapsule()}
							/>
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
