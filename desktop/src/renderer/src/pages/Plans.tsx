import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from '../router';
import { CalendarDays, CircleCheck, FolderKanban, Plus, Trash2, Check, ArrowLeft } from 'lucide-react';
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
import SchedulesPage, { EMPTY_SCHEDULE, ScheduleForm, dayLabel, groupSchedules } from './Schedules';
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

/** 计划页（spec §5 计划行）。
 *  宽屏 = 两块板：左板分组扁平行（限宽 760），右板 = Overview（今日概览）/ Detail（选中项内联编辑器）。
 *  窄屏 = 原样（分段 + 各子页自管 Modal），零回归。 */
export default function PlansPage() {
	const [tab, setTab] = useState<Tab>('schedules');
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;

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
	const [openTodos, doneTodos] = splitTodos(todos.items);

	const titleOf = tab === 'schedules' ? '日程' : tab === 'todos' ? '待办' : '项目';

	return (
		<div ref={pageRef} className="plans-wide">
			<SplitBoards
				twoPane
				primary={<>
					<BoardHeader
						title={titleOf}
						subtitle={
							tab === 'schedules' ? `${schedules.items.length} 条`
								: tab === 'todos' ? `${openTodos.length} 项未完成`
									: `${projects.items.length} 个`
						}
						actions={
							<TextButtonM3 onClick={() => {
								if (tab === 'schedules') { setEditingSchedule({ ...EMPTY_SCHEDULE }); setSelScheduleId(null); }
								else if (tab === 'todos') { setEditingTodo({ ...EMPTY_TODO }); setSelTodoId(null); }
								else { setProjectDraft({ name: '', emoji: '📁' }); }
							}}><Plus size={14} /> 新建</TextButtonM3>
						}
					/>
					{/* 分段切换长在板面上（宽屏不嵌浮起栏） */}
					<div className="plans-board-seg">
						<Segmented<Tab> value={tab} onChange={setTab} ariaLabel="计划视图"
							options={TABS.map(t => ({ value: t.key, label: <><t.icon size={14} /> {t.label}</> }))} />
					</div>
					<div className="plans-main-body">
						<div className="plans-list-col">
							{tab === 'schedules' && <ScheduleList items={schedules.items} selId={selScheduleId} onSelect={id => { setSelScheduleId(id); setEditingSchedule(null); }} />}
							{tab === 'todos' && <TodoList items={todos.items} selId={selTodoId} onSelect={id => { setSelTodoId(id); setEditingTodo(null); }} onToggle={t => void todos.upsert({ ...t, status: t.status === 'done' ? 'open' : 'done' })} onDelete={id => todos.remove(id)} />}
							{tab === 'projects' && <ProjectList items={projects.items} onDelete={id => projects.remove(id)} />}
						</div>
					</div>
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
						) : <PlanTodayPane schedules={todaySchedules} openTodos={openTodos} />
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
						) : <PlanTodayPane schedules={todaySchedules} openTodos={openTodos} />
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

function ScheduleList({ items, selId, onSelect }: { items: Schedule[]; selId: string | null; onSelect: (id: string) => void }) {
	if (!items.length) { return <Empty text="还没有日程，点上方「新建」添加" />; }
	const groups = groupSchedules(items);
	return (
		<>
			{groups.map(([key, list]) => (
				<div key={key}>
					<BoardSectionTitle text={dayLabel(key)} />
					{list.map(s => (
						<button key={s.id} type="button" className={`plans-flat-row ${selId === s.id ? 'active' : ''}`} onClick={() => onSelect(s.id)}>
							<span className="plans-flat-row__main">
								<span className="plans-flat-row__title">{s.title}</span>
								<span className="plans-flat-row__meta">
									{[s.startIso ? `${s.startIso.slice(11, 16)}${s.endIso ? `–${s.endIso.slice(11, 16)}` : ''}` : null, s.note].filter(Boolean).join(' · ')}
								</span>
							</span>
							{s.priority != null && <span className={`prio ${s.priority === 1 ? 'p1' : s.priority === 2 ? 'p2' : 'p3'}`}>{s.priority === 1 ? '高' : s.priority === 2 ? '中' : '低'}</span>}
						</button>
					))}
				</div>
			))}
		</>
	);
}

function TodoList({ items, selId, onSelect, onToggle, onDelete }: {
	items: Todo[]; selId: string | null; onSelect: (id: string) => void;
	onToggle: (t: Todo) => void; onDelete: (id: string) => void;
}) {
	const [openTodos, doneTodos] = splitTodos(items);
	if (!items.length) { return <Empty text="还没有待办，点上方「新建」添加" />; }
	const row = (t: Todo) => (
		<div key={t.id} className={`plans-flat-row ${selId === t.id ? 'active' : ''}`}
			onClick={() => onSelect(t.id)} style={{ cursor: 'pointer' }}>
			<CheckboxM3 checked={t.status === 'done'} onClick={() => onToggle(t)} aria-label="完成待办" />
			<span className="plans-flat-row__main">
				<span className={`plans-flat-row__title ${t.status === 'done' ? 'done' : ''}`}>{t.title}</span>
				<span className="plans-flat-row__meta">{[t.date, t.time].filter(Boolean).join(' · ')}</span>
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

function PlanTodayPane({ schedules, openTodos }: { schedules: Schedule[]; openTodos: Todo[] }) {
	const doneToday = openTodos.filter(t => t.status === 'done').length;
	return (
		<>
			<BoardHeader title="今日" subtitle={localDayKey()} />
			<div className="plans-detail">
				<PaneStatRow
					items={[
						{ value: schedules.length, label: '今日日程' },
						{ value: openTodos.length, label: '未完成' },
						{ value: doneToday, label: '今日完成' },
					]}
				/>
				<PaneSectionBlock title="日程">
					{schedules.length === 0
						? <PaneEmptyInline text="今天没有日程" />
						: schedules.map(s => (
							<div key={s.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 0' }}>
								<span className="pane-chip">{s.startIso?.slice(11, 16) ?? '全天'}</span>
								<span className="plans-flat-row__title" style={{ flex: 1 }}>{s.title}</span>
							</div>
						))}
				</PaneSectionBlock>
				<PaneSectionBlock title="待办">
					{openTodos.length === 0
						? <PaneEmptyInline icon={<Check size={14} />} text="待办已清空" />
						: openTodos.slice(0, 6).map(t => (
							<div key={t.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 0' }}>
								<Check size={13} style={{ opacity: 0.5, flexShrink: 0 }} />
								<span className="plans-flat-row__title" style={{ flex: 1, fontWeight: 500 }}>{t.title}</span>
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
