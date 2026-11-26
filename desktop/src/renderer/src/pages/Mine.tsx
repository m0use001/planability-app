import { useEffect, useState } from 'react';
import {
	ChartColumn, Puzzle, Zap, Users, Cable, ChevronRight, Palette, Globe, Settings2, Lock,
	DatabaseBackup, ShieldCheck, BookOpen, Bot, Wrench, PieChart, Smartphone, Download,
	FolderOpen, Brain, Database, Trophy, FileText, Trash2, Info, type LucideIcon
} from 'lucide-react';
import { useNavigate } from '../router';
import SettingsPage, { SETTINGS_GROUPS, SettingsSection, useSettingsCtx } from './Settings';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock } from '../components/m3/paneblocks';
import { useCollection } from '../hooks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { WIDE } from '../lib/board';
import type { Note, Schedule, Todo } from '@shared/types';
import './mine.css';

/** 我的页（spec §5 我的行）：宽屏（内容宽 ≥820，页面级门槛 profileIsTwoPane 口径）
 *  = 左板设置分组列表 + 右板概览/选中组详情；窄屏 = 原样（chips + 设置整页）零回归。
 *  v79 对齐：左板=手机同款三节长列表（基础设置/Agent 能力/数据与统计），
 *  右板=概览（我卡 + 数据一览四格 + 近 7 天专注柱图）。 */

type MineRow = { label: string; icon: LucideIcon; onClick?: () => void };
type MineSection = { title: string; rows: MineRow[] };

export default function MinePage() {
	const nav = useNavigate();
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= WIDE.profileTwoPaneMinWidth;
	const [group, setGroup] = useState<string | null>(null);
	const ctx = useSettingsCtx();
	const todos = useCollection<Todo>('todos');
	const schedules = useCollection<Schedule>('schedules');
	const notes = useCollection<Note>('notes');
	const [focusMin, setFocusMin] = useState(0);
	const [weekData, setWeekData] = useState<{ day: string; label: string; minutes: number }[]>([]);
	useEffect(() => {
		window.planability.focusToday().then(s => setFocusMin(Math.floor(s / 60))).catch(() => undefined);
		window.planability.focusWeek().then(setWeekData).catch(() => undefined);
	}, []);

	const openDataDir = () => { void window.planability.openDataDir(); };
	// 手机 v79 左板三节；无 PC 实体的项保留行位（点了不跳——PC 暂未接入该能力）
	const sections: MineSection[] = [
		{
			title: '基础设置',
			rows: [
				{ label: '外观', icon: Palette, onClick: () => setGroup('appearance') },
				{ label: '时区', icon: Globe },
				{ label: '更多设置', icon: Settings2, onClick: () => setGroup('data') },
				{ label: '应用锁', icon: Lock },
				{ label: '备份与恢复', icon: DatabaseBackup, onClick: () => setGroup('data') },
				{ label: '系统权限', icon: ShieldCheck },
				{ label: '使用指引', icon: BookOpen },
			]
		},
		{
			title: 'Agent 能力',
			rows: [
				{ label: '模型与智能体', icon: Bot, onClick: () => nav('assistants') },
				{ label: '工具与扩展', icon: Wrench, onClick: () => nav('mcp') },
				{ label: '图表默认设置', icon: PieChart },
				{ label: '手机操控区域', icon: Smartphone },
				{ label: '自动化任务', icon: Zap, onClick: () => nav('automations') },
				{ label: '导入', icon: Download, onClick: () => nav('sync') },
				{ label: '文件库', icon: FolderOpen, onClick: () => nav('files') },
				{ label: '记忆库', icon: Brain, onClick: () => nav('memories') },
			]
		},
		{
			title: '数据与统计',
			rows: [
				{ label: '使用统计', icon: ChartColumn, onClick: () => nav('stats') },
				{ label: '数据中心', icon: Database, onClick: () => setGroup('data') },
				{ label: '成就墙', icon: Trophy },
				{ label: '周小结', icon: FileText },
				{ label: '回收站', icon: Trash2 },
			]
		},
		// 批 D（2026-11-24）：手机 v79 版本号在左板「关于」组（右板概览不放）
		{
			title: '关于',
			rows: [
				{ label: `版本 ${ctx.info?.appVersion ?? '…'}`, icon: Info },
			]
		},
	];

	if (wide) {
		const selected = SETTINGS_GROUPS.find(g => g.key === group) ?? null;
		const doneTodoList = todos.items.filter(t => t.status === 'done').length;
		const weekTotal = weekData.reduce((n, x) => n + x.minutes, 0);
		const weekMax = Math.max(10, ...weekData.map(x => x.minutes));
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="mine-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="我的" />
						<div className="mine-main-body">
							{sections.map(sec => (
								<div key={sec.title}>
									<BoardSectionTitle text={sec.title} />
									{sec.rows.map(r => (
										<button key={r.label} type="button" className="plans-flat-row mine-row"
											onClick={r.onClick} style={{ cursor: r.onClick ? 'pointer' : 'default' }}>
											<span className="mine-row-icon"><r.icon size={15} /></span>
											<span className="plans-flat-row__main">
												<span className="plans-flat-row__title">{r.label}</span>
											</span>
											{r.onClick && <ChevronRight size={14} style={{ color: 'var(--text-faint)' }} />}
										</button>
									))}
								</div>
							))}
						</div>
					</>}
					secondary={<>
						{selected ? (
							<>
								<BoardHeader
									title={selected.label}
									leading={
										<button type="button" className="board-back" onClick={() => setGroup(null)} aria-label="返回">
											<ChevronRight size={15} style={{ transform: 'rotate(180deg)' }} />
										</button>
									}
								/>
								<div className="mine-detail">
									<SettingsSection group={selected.key} ctx={ctx} />
								</div>
							</>
						) : (
							<>
								<BoardHeader title="概览" subtitle="账号与本机数据" />
								<div className="mine-detail">
									{/* 手机 v79：我卡（本地账号 · 纯离线）→ 打开本机数据目录 */}
									<button type="button" className="mine-me-card" onClick={openDataDir}>
										<span className="mine-avatar">我</span>
										<span className="plans-flat-row__main">
											<span className="plans-flat-row__title">我</span>
											<span className="plans-flat-row__meta">本地账号 · 纯离线</span>
										</span>
										<ChevronRight size={14} style={{ color: 'var(--text-faint)' }} />
									</button>

									<BoardSectionTitle text="数据一览" />
									<PaneStatRow
										items={[
											{ value: doneTodoList, label: '待办完成' },
											{ value: schedules.items.length, label: '日程' },
											{ value: notes.items.length, label: '笔记' },
											{ value: <>{focusMin}<span style={{ fontSize: 12 }}> 分钟</span></>, label: '专注' },
										]}
									/>

									<BoardSectionTitle text="近 7 天专注" />
									<PaneSectionBlock title="" flush>
										{weekTotal === 0
											? <div className="focus-week-empty">近 7 天还没有专注记录</div>
											: (
												<div className="focus-week-chart">
													{weekData.map(x => (
														<div key={x.label} className="focus-week-col">
															<div className="focus-week-barzone"><i style={{ height: `${Math.round((x.minutes / weekMax) * 100)}%` }} title={`${x.label} ${x.minutes} 分钟`} /></div>
															<span className="focus-week-label">{x.label}</span>
														</div>
													))}
												</div>
											)}
									</PaneSectionBlock>
								</div>
							</>
						)}
					</>}
				/>
			</div>
		);
	}

	return (
		<div ref={pageRef}>
			<div className="mine-shortcuts">
				<button className="mine-chip" onClick={() => nav('stats')}><ChartColumn size={14} /> 使用统计</button>
				<button className="mine-chip" onClick={() => nav('automations')}><Zap size={14} /> 自动化</button>
				<button className="mine-chip" onClick={() => nav('assistants')}><Users size={14} /> 子智能体</button>
				<button className="mine-chip" onClick={() => nav('mcp')}><Cable size={14} /> MCP</button>
				<button className="mine-chip" onClick={() => nav('plugins')}><Puzzle size={14} /> 插件</button>
			</div>
			<SettingsPage />
		</div>
	);
}
