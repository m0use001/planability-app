import { useState } from 'react';
import { ChartColumn, Puzzle, Zap, Users, Cable, ChevronRight } from 'lucide-react';
import { useNavigate } from '../router';
import SettingsPage, { SETTINGS_GROUPS, SettingsSection, useSettingsCtx } from './Settings';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneEmptyInline } from '../components/m3/paneblocks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { WIDE } from '../lib/board';
import './mine.css';

/** 我的页（spec §5 我的行）：宽屏（内容宽 ≥820，页面级门槛 profileIsTwoPane 口径）
 *  = 左板设置分组列表 + 右板概览/选中组详情；窄屏 = 原样（chips + 设置整页）零回归。 */
export default function MinePage() {
	const nav = useNavigate();
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= WIDE.profileTwoPaneMinWidth;
	const [group, setGroup] = useState<string | null>(null);
	const ctx = useSettingsCtx();

	if (wide) {
		const selected = SETTINGS_GROUPS.find(g => g.key === group) ?? null;
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="mine-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="我的" subtitle="快捷入口与设置" />
						<div className="mine-main-body">
							<div className="mine-chips-wide">
								<button className="mine-chip" onClick={() => nav('stats')}><ChartColumn size={14} /> 使用统计</button>
								<button className="mine-chip" onClick={() => nav('automations')}><Zap size={14} /> 自动化</button>
								<button className="mine-chip" onClick={() => nav('assistants')}><Users size={14} /> 子智能体</button>
								<button className="mine-chip" onClick={() => nav('mcp')}><Cable size={14} /> MCP</button>
								<button className="mine-chip" onClick={() => nav('plugins')}><Puzzle size={14} /> 插件</button>
							</div>
							<BoardSectionTitle text="设置" />
							{SETTINGS_GROUPS.map(g => (
								<button key={g.key} type="button" className={`plans-flat-row ${group === g.key ? 'active' : ''}`}
									onClick={() => setGroup(g.key)}>
									<span className="plans-flat-row__main">
										<span className="plans-flat-row__title">{g.label}</span>
										<span className="plans-flat-row__meta">{g.meta}</span>
									</span>
									<ChevronRight size={14} style={{ color: 'var(--text-faint)' }} />
								</button>
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
								<BoardHeader title="概览" subtitle={`版本 ${ctx.info?.appVersion ?? '…'}`} />
								<div className="mine-detail">
									<PaneStatRow
										items={[
											{ value: ctx.info?.appVersion ?? '…', label: '版本' },
											{ value: ctx.cfg.workspaceRoot?.trim() ? '开' : '关', label: '文件工具' },
											{ value: ctx.theme === 'light' ? '浅色' : '深色', label: '主题' },
										]}
									/>
									<PaneEmptyInline text="左侧选一组设置查看详情" />
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
