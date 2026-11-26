import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from '../router';
import { CalendarDays, CircleCheck, FolderKanban, Plus, Trash2, ArrowLeft, MoreVertical } from 'lucide-react';
import { Segmented } from '../components/m3/chrome';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock, PaneEmptyInline } from '../components/m3/paneblocks';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { FilterChipM3 } from '../components/m3/chips';
import { CheckboxM3 } from '../components/m3/controls';
import { useCollection } from '../hooks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import { localDayKey } from '../daykey';
import type { Project, Schedule, Todo } from '@shared/types';
import SchedulesPage, { EMPTY_SCHEDULE, ScheduleForm } from './Schedules';
import TodosPage, { EMPTY_TODO, TodoForm, splitTodos } from './Todos';
import ProjectsPage from './Projects';
import './plans.css';

type Tab = 'schedules' | 'todos' | 'projects';

const TABS: { key: Tab; label: string; icon: typeof CalendarDays }[] = [
	{ key: 'schedules', label: '日程', icon: CalendarDays },
	{ key: 'todos', label: '待办', icon: CircleCheck },
	{ key: 'projects', label: '项目', icon: FolderKanban }
];

const EMOJIS = ['📁', '💻', '🖥', '📚', '🎯', '🧪', '🏠', '✈️'];

// ---- 宽屏筛选（手机 v79 口径：时间段一行 + 状态一行；日程/待办状态项不同）----
type Period = 'all' | 'today' | '7d' | '30d';
const PERIODS: { key: Period; label: string }[] = [
	{ key: 'all', label: '全部' }, { key: 'today', label: '今天' },
	{ key: '7d', label: '近7天' }, { key: '30d', label: '近30天' }
];
const SCHED_STATUS = [
	{ key: 'open', label: '未开始' }, { key: 'past', label: '已过去' }, { key: 'all', label: '全部' }
];
const TODO_STATUS = [
	{ key: 'open', label: '进行中' }, { key: 'archived', label: '已归档' },
	{ key: 'expired', label: '过期' }, { key: 'all', label: '全部' }
];

/** YYYY-MM-DD + n 天（本地口径，与 localDayKey 同源）。 */
function addDaysKey(key: string, n: number): string {
	const d = new Date(Number(key.slice(0, 4)), Number(key.slice(5, 7)) - 1, Number(key.slice(8, 10)));
	d.setDate(d.getDate() + n);
	return localDayKeyOf(d);
}
function localDayKeyOf(d: Date): string {
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
/** 行内日期词（手机口径：今天/明天/M月D日，不带年）。 */
function dateWord(key?: string): string {
	if (!key) { return ''; }
	const today = localDayKey();
	if (key === today) { return '今天'; }
	if (key === addDaysKey(today, 1)) { return '明天'; }
	return `${Number(key.slice(5, 7))}月${Number(key.slice(8, 10))}日`;
}

/** 计划页（spec §5 计划行）。
 *  宽屏 = 两块板：左板分组扁平行（限宽 760），右板 = Overview（今日概览）/ Detail（选中项内联编辑器）。
 *  窄屏 = 原样（分段 + 各子页自管 Modal），零回归。 */
export default function PlansPage() {
	const [tab, setTab] = useState<Tab>('schedules');
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	// 宽屏筛选（手机 v79）：时间段 + 状态；切 tab 状态行换成对应那组并回到默认
	const [period, setPeriod] = useState<Period>('all');
	const [status, setStatus] = useState('open');
	const [moreOpen, setMoreOpen] = useState(false);

	// ---- 三集合在此一次取好：宽屏两板要跨 tab 用（左板列表 / 右板今日概览）----
	const schedules = useCollection<Schedule>('schedules');
	const todos = useCollection<Todo>('todos');
	const projects = useCollection<Project>('projects');

	// 选中态（宽屏右板 Detail）：日程/待办各一个，切 tab 不互相污染
	const [selScheduleId, setSelScheduleId] = useState<string | null>(null);
	const [selTodoId, setSelTodoId] = useState<string | null>(null);
	const [editingSchedule, setEditingSchedule] = useState<Schedule | null>(null); // 新建草稿
	const [editingTodo, setEditingTodo] = useState<Todo | null>(null);
	const [projectDraft, setProjectDraft] = useState<{ name: string; emoji: string } | null>(null);

	const selSchedule = useMemo(
		() => schedules.items.find(s => s.id === selScheduleId) ?? null,
		[schedules.items, selScheduleId]
	);
	const selTodo = useMemo(
		() => todos.items.find(t => t.id === selTodoId) ?? null,
		[todos.items, selTodoId]
	);

	// 滑出路上内容不蒸：右板编辑器保留最后一次非空对象
	const lastScheduleRef = useRef<Schedule | null>(null);
	if (selSchedule) { lastScheduleRef.current = selSchedule; }
	const lastTodoRef = useRef<Todo | null>(null);
	if (selTodo) { lastTodoRef.current = selTodo; }
	const schedEditing = editingSchedule ?? selSchedule;
	const todoEditing = editingTodo ?? selTodo;

	// Esc / 返回：退选中（保留正在编辑的改动）
	const saveSchedule = async () => {
		const cur = editingSchedule ?? selSchedule;
		if (!cur || !cur.title.trim()) { return; }
		await schedules.upsert(cur);
		setEditingSchedule(null);
	};
	const saveTodo = async () => {
		const cur = editingTodo ?? selTodo;
		if (!cur || !cur.title.trim()) { return; }
		await todos.upsert(cur);
		setEditingTodo(null);
	};

	useEffect(() => {
		if (!wide) { return; }
		const onKey = (e: KeyboardEvent) => {
			if (e.key !== 'Escape') { return; }
			if (editingSchedule || editingTodo || projectDraft) { return; } // 模态/草稿自己处理
			setMoreOpen(false);
			if (selScheduleId || selTodoId) { e.preventDefault(); setSelScheduleId(null); setSelTodoId(null); }
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, [wide, selScheduleId, selTodoId, editingSchedule, editingTodo, projectDraft]);

	if (!wide) {
		return (
			<div ref={pageRef}>
				{/* P3：seg-control → Segmented chrome（roving tabindex + 方向键循环） */}
				<Segmented<Tab> value={tab} onChange={setTab} ariaLabel="计划视图"
					options={TABS.map(t => ({ value: t.key, label: <><t.icon size={14} /> {t.label}</> }))} />
				{tab === 'schedules' && <SchedulesPage />}
				{tab === 'todos' && <TodosPage />}
				{tab === 'projects' && <ProjectsPage />}
			</div>
		);
	}

	// ---------------- 宽屏两板 ----------------
	const todayKey = localDayKey();
	const todaySchedules = schedules.items
		.filter(s => (s.date ?? s.startIso?.slice(0, 10)) === todayKey)
		.sort((a, b) => (a.startIso ?? '').localeCompare(b.startIso ?? ''));

	// 手机 v79 筛选：时间段（无日期只在「全部」出现）+ 状态
	const nowMin = new Date().getHours() * 60 + new Date().getMinutes();
	const inPeriod = (dateStr?: string) => {
		if (period === 'all') { return true; }
		if (!dateStr) { return false; }
		if (period === 'today') { return dateStr === todayKey; }
		const span = period === '7d' ? 7 : 30;
		return dateStr >= todayKey && dateStr <= addDaysKey(todayKey, span);
	};
	const schedPast = (s: Schedule) => {
		const d = s.date ?? s.startIso?.slice(0, 10) ?? '';
		if (d && d < todayKey) { return true; }
		if (d === todayKey && s.endIso) { return Number(s.endIso.slice(11, 13)) * 60 + Number(s.endIso.slice(14, 16)) < nowMin; }
		return false;
	};
	const todoState = (t: Todo): 'open' | 'archived' | 'expired' => {
		if (t.status === 'done') { return 'archived'; }
		if (t.date && t.date < todayKey) { return 'expired'; }
		return 'open';
	};
	// （不用 useMemo：位于窄屏 early-return 之后，hooks 数量会随宽窄分支变化 → React #310）
	const filteredSchedules = schedules.items.filter(s =>
		inPeriod(s.date ?? s.startIso?.slice(0, 10)) &&
		(status === 'all' || (status === 'past') === schedPast(s))
	);
	const filteredTodos = todos.items.filter(t =>
		inPeriod(t.date) && (status === 'all' || todoState(t) === status)
	);

	const statusOptions = tab === 'todos' ? TODO_STATUS : SCHED_STATUS;
	const emptyText = tab === 'todos' ? '没有符合条件的待办' : '这段时间还没有日程';
	const startNew = () => {
		if (tab === 'schedules') { setEditingSchedule({ ...EMPTY_SCHEDULE }); setSelScheduleId(null); }
		else if (tab === 'todos') { setEditingTodo({ ...EMPTY_TODO }); setSelTodoId(null); }
		else { setProjectDraft({ name: '', emoji: '📁' }); }
	};

	return (
		<div ref={pageRef} className="plans-wide">
			<SplitBoards
				twoPane
				primary={<>
					<BoardHeader
						title="计划"
						actions={
							<button type="button" className="board-back" aria-label="更多" onClick={() => setMoreOpen(v => !v)}>
								<MoreVertical size={16} />
							</button>
						}
					/>
					{/* 分段切换长在板面上（宽屏不嵌浮起栏）；手机 v79 分段只有 日程/待办，项目收进 ⋮ */}
					<div className="plans-board-seg">
						<Segmented<Tab> value={tab === 'projects' ? 'schedules' : tab}
							onChange={v => { setTab(v); setStatus('open'); }} ariaLabel="计划视图"
							options={[TABS[0], TABS[1]].map(t => ({ value: t.key, label: <><t.icon size={14} /> {t.label}</> }))} />
					</div>
					{/* 手机 v79 两行筛选：时间段 + 状态（状态项随 tab 换） */}
					<div className="plans-filters">
						<div className="plans-filter-row">
							{PERIODS.map(p => (
								<FilterChipM3 key={p.key} selected={period === p.key} onClick={() => setPeriod(p.key)}>{p.label}</FilterChipM3>
							))}
						</div>
						<div className="plans-filter-row">
							<span className="pane-chip">状态 ▾</span>
							{statusOptions.map(s => (
								<FilterChipM3 key={s.key} selected={status === s.key} onClick={() => setStatus(s.key)}>{s.label}</FilterChipM3>
							))}
						</div>
					</div>
					<div className="plans-main-body">
						<div className="plans-list-col">
							{tab === 'schedules' && <ScheduleList items={filteredSchedules} emptyText={emptyText} selId={selScheduleId} onSelect={id => { setSelScheduleId(id); setEditingSchedule(null); }} />}
							{tab === 'todos' && <TodoList items={filteredTodos} emptyText={emptyText} selId={selTodoId} onSelect={id => { setSelTodoId(id); setEditingTodo(null); }} onToggle={t => void todos.upsert({ ...t, status: t.status === 'done' ? 'open' : 'done' })} onDelete={id => todos.remove(id)} />}
							{tab === 'projects' && <ProjectList items={projects.items} onDelete={id => projects.remove(id)} />}
						</div>
					</div>
					{/* 手机 v79：新建入口 = 板右下角 FAB */}
					<button type="button" className="plans-fab" aria-label="新建" onClick={startNew}>
						<Plus size={20} />
					</button>
					{moreOpen && (
						<div className="plans-more-menu" role="menu">
							<button type="button" role="menuitem" onClick={() => { setTab('projects'); setMoreOpen(false); }}>
								<FolderKanban size={14} /> 项目
							</button>
						</div>
					)}
				</>}
				secondary={<>
					{tab === 'schedules' ? (
						schedEditing ? (
							<>
								<BoardHeader
									title={selSchedule ? (schedEditing.title || '未命名日程') : '新建日程'}
									leading={selSchedule ? (
										<button type="button" className="plans-back" onClick={() => { setSelScheduleId(null); setEditingSchedule(null); }} aria-label="返回">
											<ArrowLeft size={16} />
										</button>
									) : undefined}
								/>
								<div className="plans-detail">
									<ScheduleForm value={schedEditing} onChange={setEditingSchedule} />
									<div className="plans-detail-actions">
										{selSchedule && (
											<TextButtonM3 className="m3-text-danger" onClick={() => { void schedules.remove(selSchedule.id); setSelScheduleId(null); }}>删除</TextButtonM3>
										)}
										<FilledButtonM3 disabled={!schedEditing.title.trim()} onClick={() => void saveSchedule()}>保存</FilledButtonM3>
									</div>
								</div>
							</>
						) : <PlanTodayPane schedules={todaySchedules} todos={todos.items} />
					) : tab === 'todos' ? (
						todoEditing ? (
							<>
								<BoardHeader
									title={selTodo ? (todoEditing.title || '未命名待办') : '新建待办'}
									leading={selTodo ? (
										<button type="button" className="plans-back" onClick={() => { setSelTodoId(null); setEditingTodo(null); }} aria-label="返回">
											<ArrowLeft size={16} />
										</button>
									) : undefined}
								/>
								<div className="plans-detail">
									<TodoForm value={todoEditing} onChange={setEditingTodo} />
									<div className="plans-detail-actions">
										{selTodo && (
											<TextButtonM3 className="m3-text-danger" onClick={() => { void todos.remove(selTodo.id); setSelTodoId(null); }}>删除</TextButtonM3>
										)}
										<FilledButtonM3 disabled={!todoEditing.title.trim()} onClick={() => void saveTodo()}>保存</FilledButtonM3>
									</div>
								</div>
							</>
						) : <PlanTodayPane schedules={todaySchedules} todos={todos.items} />
					) : (
						<ProjectOverview projects={projects.items} />
					)}
				</>}
			/>

			{projectDraft && (
				<Modal title="新建项目" onClose={() => setProjectDraft(null)} dirty={projectDraft.name !== '' || projectDraft.emoji !== '📁'}>
					<Field label="名称">
						<input value={projectDraft.name} onChange={e => setProjectDraft({ ...projectDraft, name: e.target.value })} autoFocus placeholder="例如：线性代数课设" />
					</Field>
					<Field label="图标">
						<div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
							{EMOJIS.map(e => (
								<FilterChipM3 key={e} selected={projectDraft.emoji === e} onClick={() => setProjectDraft({ ...projectDraft, emoji: e })}>{e}</FilterChipM3>
							))}
						</div>
					</Field>
					<div className="form-actions">
						<TextButtonM3 onClick={() => setProjectDraft(null)}>取消</TextButtonM3>
						<FilledButtonM3 disabled={!projectDraft.name.trim()} onClick={() => {
							void projects.upsert({ id: '', createdAt: 0, updatedAt: 0, name: projectDraft.name.trim(), emoji: projectDraft.emoji });
							setProjectDraft(null);
						}}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</div>
	);
}

// ---------------- 左板：分组扁平行列表 ----------------

function ScheduleList({ items, selId, onSelect, emptyText = '还没有日程，点上方「新建」添加' }: { items: Schedule[]; selId: string | null; onSelect: (id: string) => void; emptyText?: string }) {
	if (!items.length) { return <Empty text={emptyText} />; }
	// 手机 v79：扁平行、日期进行内 meta（今天 09:00–10:00），不加分组头
	return (
		<>
			{items.map(s => (
				<button key={s.id} type="button" className={`plans-flat-row ${selId === s.id ? 'active' : ''}`} onClick={() => onSelect(s.id)}>
					<span className="plans-flat-row__main">
						<span className="plans-flat-row__title">{s.title}</span>
						<span className="plans-flat-row__meta">
							{[
								dateWord(s.date ?? s.startIso?.slice(0, 10)),
								s.startIso ? `${s.startIso.slice(11, 16)}${s.endIso ? `–${s.endIso.slice(11, 16)}` : ''}` : null
							].filter(Boolean).join(' ')}
						</span>
					</span>
					{s.priority != null && <span className={`prio ${s.priority === 1 ? 'p1' : s.priority === 2 ? 'p2' : 'p3'}`}>{s.priority === 1 ? '高' : s.priority === 2 ? '中' : '低'}</span>}
				</button>
			))}
		</>
	);
}

function TodoList({ items, selId, onSelect, onToggle, onDelete, emptyText = '还没有待办，点上方「新建」添加' }: {
	items: Todo[]; selId: string | null; onSelect: (id: string) => void;
	onToggle: (t: Todo) => void; onDelete: (id: string) => void; emptyText?: string;
}) {
	const [openTodos, doneTodos] = splitTodos(items);
	if (!items.length) { return <Empty text={emptyText} />; }
	const row = (t: Todo) => (
		<div key={t.id} className={`plans-flat-row ${selId === t.id ? 'active' : ''}`}
			onClick={() => onSelect(t.id)} style={{ cursor: 'pointer' }}>
			<CheckboxM3 checked={t.status === 'done'} onClick={() => onToggle(t)} aria-label="完成待办" />
				<span className="plans-flat-row__main">
					<span className={`plans-flat-row__title ${t.status === 'done' ? 'done' : ''}`}>{t.title}</span>
					<span className="plans-flat-row__meta">{t.date ? [dateWord(t.date), t.time].filter(Boolean).join(' ') : '未安排'}</span>
				</span>
			{t.priority != null && <span className={`prio ${t.priority === 1 ? 'p1' : t.priority === 2 ? 'p2' : 'p3'}`}>{t.priority === 1 ? '高' : t.priority === 2 ? '中' : '低'}</span>}
			<button type="button" className="m3-listrow__act m3-listrow__act--danger" title="删除" aria-label="删除"
				onClick={e => { e.stopPropagation(); if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { onDelete(t.id); } }}>
				<Trash2 size={13} />
			</button>
		</div>
	);
	return (
		<>
			{openTodos.length > 0 && <BoardSectionTitle text={`进行中（${openTodos.length}）`} />}
			{openTodos.map(row)}
			{doneTodos.length > 0 && <BoardSectionTitle text={`已完成（${doneTodos.length}）`} />}
			{doneTodos.map(row)}
		</>
	);
}

function ProjectList({ items, onDelete }: { items: Project[]; onDelete: (id: string) => void }) {
	if (!items.length) { return <Empty text="还没有项目。项目可作为日程/待办/笔记的归属维度" />; }
	return (
		<>
			{items.map(p => (
				<div key={p.id} className="plans-flat-row" style={{ cursor: 'default' }}>
					<span style={{ fontSize: 18 }}>{p.emoji ?? '📁'}</span>
					<span className="plans-flat-row__main">
						<span className="plans-flat-row__title">{p.name}</span>
						<span className="plans-flat-row__meta">创建于 {new Date(p.createdAt).toLocaleDateString('zh-CN')}</span>
					</span>
					<button type="button" className="m3-listrow__act m3-listrow__act--danger" title="删除" aria-label="删除"
						onClick={() => { if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { onDelete(p.id); } }}>
						<Trash2 size={13} />
					</button>
				</div>
			))}
		</>
	);
}

// ---------------- 右板：Overview（未选中时不许空着） ----------------

function PlanTodayPane({ schedules, todos }: { schedules: Schedule[]; todos: Todo[] }) {
	const todayKey = localDayKey();
	const todayTodos = todos.filter(t => t.date === todayKey);
	const doneToday = todayTodos.filter(t => t.status === 'done').length;
	// 手机口径：2026年11月23日 星期一（toLocaleDateString 的 zh-CN 不带空格）
	const d = new Date();
	const fullDate = `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${'日一二三四五六'[d.getDay()]}`;
	return (
		<>
			<BoardHeader title="今天" subtitle={fullDate} />
			<div className="plans-detail">
				{/* 手机 v79：一格三列统计（日程 | 待办 | 已完成），均为「今天」口径 */}
				<PaneStatRow
					items={[
						{ value: schedules.length, label: '日程' },
						{ value: todayTodos.filter(t => t.status !== 'done').length, label: '待办' },
						{ value: doneToday, label: '已完成' },
					]}
				/>
				<BoardSectionTitle text="今日日程" trailing={<span className="pane-chip">共 {schedules.length}</span>} />
				<PaneSectionBlock title="" flush>
					{schedules.length === 0
						? <PaneEmptyInline text="今天没有日程" />
						: schedules.map(s => (
							<div key={s.id} className="home-ov-row">
								<span className="home-ov-time">{s.startIso?.slice(11, 16) ?? '全天'}</span>
								<span className="plans-flat-row__title" style={{ flex: 1 }}>{s.title}</span>
							</div>
						))}
				</PaneSectionBlock>
				<BoardSectionTitle text="待办" trailing={<span className="pane-chip">{doneToday}/{todayTodos.length}</span>} />
				<PaneSectionBlock title="" flush>
					{todayTodos.length === 0
						? <PaneEmptyInline text="今天没有待办" />
						: todayTodos.map(t => (
							<div key={t.id} className="home-ov-row">
								<span className={`home-ov-dot ${t.status === 'done' ? 'done' : ''}`} />
								<span className={`plans-flat-row__title ${t.status === 'done' ? 'done' : ''}`} style={{ flex: 1 }}>{t.title}</span>
								<span className="home-ov-time" style={{ color: 'var(--text-faint)' }}>{t.time ?? ''}</span>
							</div>
						))}
				</PaneSectionBlock>
			</div>
		</>
	);
}

function ProjectOverview({ projects }: { projects: Project[] }) {
	return (
		<>
			<BoardHeader title="项目概览" subtitle={`${projects.length} 个`} />
			<div className="plans-detail">
				{projects.length === 0 ? <PaneEmptyInline text="还没有项目" /> : (
					<PaneSectionBlock title="全部项目">
						{projects.map(p => (
							<div key={p.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 0' }}>
								<span style={{ fontSize: 15 }}>{p.emoji ?? '📁'}</span>
								<span className="plans-flat-row__title" style={{ flex: 1 }}>{p.name}</span>
							</div>
						))}
					</PaneSectionBlock>
				)}
			</div>
		</>
	);
}
