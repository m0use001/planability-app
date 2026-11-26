/**
 * 板材质组件 —— 直译手机 `ui/common/SplitBoard.kt`。
 *
 * 设计基调（与手机同源，守卫钉）：
 *  - 两块 24px 圆角板 + 12px 白缝（缝=底色透出，不画线）
 *  - 板头长在板面上（不嵌浮起 TopBar）
 *  - `twoPane=false` 时只渲染主板，且**不带板底/圆角**（手机 !twoPane 同款——单列直接铺在页面底色上）
 *
 * CSS：`styles/board.css`（常量在 `lib/board.ts`）。
 */
import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import { BOARD } from '../../lib/board';
import '../../styles/board.css';

// ---------- SplitBoards ----------
export interface SplitBoardsProps {
	twoPane: boolean;
	detailWidth?: number;
	/** 定宽那板放左边（默认在右）。笔记页反过来：左边是宽编辑器、右边是定宽列表。 */
	fixedFirst?: boolean;
	className?: string;
	primary: ReactNode;
	secondary?: ReactNode;
}

export function SplitBoards({
	twoPane, detailWidth = BOARD.detailWidth, fixedFirst = false, className = '', primary, secondary,
}: SplitBoardsProps) {
	const cls = `board-pair ${twoPane ? 'board-pair--two' : 'board-pair--single'} ${className}`.trim();
	if (twoPane && fixedFirst) {
		return (
			<div className={cls}>
				<div className="board board--fixed" style={{ width: detailWidth }}>{secondary}</div>
				<div className="board board--flex">{primary}</div>
			</div>
		);
	}
	return (
		<div className={cls}>
			<div className="board board--flex">{primary}</div>
			{twoPane ? <div className="board board--fixed" style={{ width: detailWidth }}>{secondary}</div> : null}
		</div>
	);
}

// ---------- BoardHeader ----------
export interface BoardHeaderProps {
	title: ReactNode;
	subtitle?: ReactNode;
	/** 富副标题插槽（会话页的胶囊行用）。与 subtitle 互斥。 */
	subtitleContent?: ReactNode;
	leading?: ReactNode;
	actions?: ReactNode;
	/** 整行点击（首页「点顶栏进日历」语义）。给了就整块板头可点。 */
	onClick?: () => void;
}

export function BoardHeader({ title, subtitle, subtitleContent, leading, actions, onClick }: BoardHeaderProps) {
	return (
		<div className={`board-header ${onClick ? 'board-header--clickable' : ''}`} onClick={onClick}>
			{leading}
			<div className="board-header__title">
				<div className="board-header__h">{title}</div>
				{subtitleContent ? (
					subtitleContent
				) : subtitle ? (
					<div className="board-header__sub">{subtitle}</div>
				) : null}
			</div>
			{actions}
		</div>
	);
}

// ---------- BoardSubHeader ----------
/** 瓦片/embedded 态的子页顶栏：透明瓦片里不画自带底色的 TopAppBar（手机 v70 同规律）。
 *  内联分支必须用显式 onBack，绝不能读空实现的关闭通道（手机笔记右栏曾踩「返回键哑」）。 */
export function BoardSubHeader({ title, onBack, actions }: { title: string; onBack: () => void; actions?: ReactNode }) {
	return (
		<BoardHeader
			title={title}
			leading={
				<button type="button" className="board-back" onClick={onBack} aria-label="返回">
					<ArrowLeft size={16} />
				</button>
			}
			actions={actions}
		/>
	);
}

// ---------- 板内分组 / 分隔 ----------
export function BoardSectionTitle({ text, trailing, className = '' }: {
	text: string;
	/** 节标题行尾槽（计数胶囊等——手机「日程 共2」口径）。 */
	trailing?: ReactNode;
	className?: string;
}) {
	return (
		<div className={`board-section-title ${className}`.trim()} style={{ display: 'flex', alignItems: 'center' }}>
			<span style={{ flex: 1 }}>{text}</span>
			{trailing}
		</div>
	);
}

export function BoardDivider() {
	return <hr className="board-divider" />;
}

export function BoardRowDivider() {
	return <hr className="board-row-divider" />;
}

// ---------- AdaptiveColumn ----------
/** 长内容列的无条件宽度上限（手机 adaptiveColumn 同源）。
 *  手机宽度永远 <840，widthIn(max) 在手机天然 no-op；桌面不限宽行长会崩，所以无条件限。 */
export function AdaptiveColumn({ maxWidth = 840, className = '', children }: {
	maxWidth?: number; className?: string; children: ReactNode;
}) {
	return <div className={`adaptive-col ${className}`.trim()} style={{ maxWidth }}>{children}</div>;
}
