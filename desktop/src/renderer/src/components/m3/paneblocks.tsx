/**
 * 副框图形词汇表 —— 直译手机 `ui/common/PaneBlocks.kt`（v72 / v72.1 / v72.2）。
 *
 * v72.2 口径（用户拍板「一行标题 + 一个圆角矩形」）：
 *  - 一行标题在矩形外 + 恰一个圆角矩形装全部内容；不把标题塞进块内、不一格一块。
 *  - 所有块统一同一个不透明色阶 `surface-container-high`（板底 surface-container-low 高一阶）。
 *    禁止混用 alpha / High / 板底同色——三种色在打架（v72.1 用户「颜色不同导致排版更乱」）。
 *  - 块内空态不要再嵌套 PaneEmptyBlock（同色=隐形），改块内居中一行；PaneEmptyBlock
 *    只用于无节块包裹的顶层空态。
 *  - N 格统计用 `PaneStatRow`：同一矩形内并排、细竖线分隔。
 *  - 进度色只走 primary 家族（轨 surface-variant）；进度条一律自绘（M3 默认在绿主题下发粉红）。
 *
 * 铁律：板内仍禁浮起 Card——块是同材质家族的扁平色调，无描边无阴影。
 */
import type { ReactNode } from 'react';
import '../../styles/board.css';

// ---------- PaneSectionBlock：节级色调块（标题在矩形外） ----------
export function PaneSectionBlock({ title, trailing, children, flush }: {
	title: ReactNode;
	/** 标题行尾槽（计数胶囊等）。 */
	trailing?: ReactNode;
	children: ReactNode;
	/** true = 不带 16px 外边距（已在带内缩的容器里时用，防双层缩进）。 */
	flush?: boolean;
}) {
	return (
		<>
			<div className="board-section-title" style={flush ? { paddingLeft: 0, paddingRight: 0 } : undefined}>
				{title}
			</div>
			<div className={`pane-block ${flush ? 'pane-block--flush' : ''}`}>{children}</div>
		</>
	);
}

// ---------- PaneContentBlock：无标题内容块 ----------
export function PaneContentBlock({ children, flush }: { children: ReactNode; flush?: boolean }) {
	return <div className={`pane-content-block ${flush ? 'pane-block--flush' : ''}`}>{children}</div>;
}

// ---------- PaneEmptyBlock：顶层空态（无节块包裹时用） ----------
export function PaneEmptyBlock({ icon, text, flush }: { icon?: ReactNode; text: ReactNode; flush?: boolean }) {
	return (
		<div className={`pane-empty-block ${flush ? 'pane-block--flush' : ''}`}>
			{icon}
			<span>{text}</span>
		</div>
	);
}

// ---------- PaneEmptyInline：块内空态（同色=不嵌套） ----------
export function PaneEmptyInline({ icon, text }: { icon?: ReactNode; text: ReactNode }) {
	return (
		<div className="pane-empty-inline">
			{icon}
			<span>{text}</span>
		</div>
	);
}

// ---------- PaneChip：非交互点缀胶囊 ----------
export function PaneChip({ children }: { children: ReactNode }) {
	return <span className="pane-chip">{children}</span>;
}

// ---------- PaneStatRow / PaneStatCell：N 格统计（同一矩形内并排、细竖线分隔） ----------
export interface PaneStatItem {
	value: ReactNode;
	label: ReactNode;
}

export function PaneStatRow({ items, flush }: { items: PaneStatItem[]; flush?: boolean }) {
	return (
		<div className={`pane-block ${flush ? 'pane-block--flush' : ''}`}>
			<div className="pane-stat-row">
				{items.map((it, i) => (
					<div className="pane-stat-cell" key={i}>
						<div className="pane-stat-cell__value">{it.value}</div>
						<div className="pane-stat-cell__label">{it.label}</div>
					</div>
				))}
			</div>
		</div>
	);
}

export function PaneStatCell({ value, label }: { value: ReactNode; label: ReactNode }) {
	return (
		<div className="pane-stat-cell">
			<div className="pane-stat-cell__value">{value}</div>
			<div className="pane-stat-cell__label">{label}</div>
		</div>
	);
}

// ---------- CompletionRing：环形占比（轨 surface-variant / 进度 primary / -90° 圆头，进度条一律自绘） ----------
export function CompletionRing({ progress, size = 56, stroke = 6 }: {
	progress: number; size?: number; stroke?: number;
}) {
	const r = (size - stroke) / 2;
	const c = 2 * Math.PI * r;
	const p = Math.max(0, Math.min(1, progress));
	const dash = c * p;
	return (
		<svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`完成率 ${Math.round(p * 100)}%`}>
			<circle cx={size / 2} cy={size / 2} r={r} fill="none"
				stroke="var(--md-sys-color-surface-variant)" strokeWidth={stroke} />
			{/* 0% = 空环（手机口径）：round 线帽在 dash=0 时仍会画一个点，p=0 直接不渲染进度弧 */}
			{p > 0 && (
				<circle cx={size / 2} cy={size / 2} r={r} fill="none"
					stroke="var(--md-sys-color-primary)" strokeWidth={stroke} strokeLinecap="round"
					strokeDasharray={`${dash} ${c}`}
					transform={`rotate(-90 ${size / 2} ${size / 2})`} />
			)}
		</svg>
	);
}

// ---------- PaneStatRing：完成率主卡（56px 环 + 大数字 + 副行 + 胶囊行） ----------
export function PaneStatRing({ progress, value, sub, chips, ringSize = 56 }: {
	progress: number;
	/** 主数值（如 "72%"）。 */
	value: ReactNode;
	sub?: ReactNode;
	chips?: ReactNode;
	ringSize?: number;
}) {
	return (
		<div className="pane-stat-ring">
			<CompletionRing progress={progress} size={ringSize} />
			<div style={{ flex: 1, minWidth: 0 }}>
				<div className="pane-stat-ring__value">{value}</div>
				{sub ? <div className="pane-stat-ring__sub">{sub}</div> : null}
				{chips ? <div className="pane-stat-ring__chips">{chips}</div> : null}
			</div>
		</div>
	);
}

// ---------- PaneActionBlock / PaneActionRow：可交互入口块 ----------
export function PaneActionBlock({ icon, title, meta, trailing, onClick, flush }: {
	icon?: ReactNode;
	title: ReactNode;
	meta?: ReactNode;
	trailing?: ReactNode;
	onClick?: () => void;
	flush?: boolean;
}) {
	return (
		<button type="button" className={`pane-action-block ${flush ? 'pane-block--flush' : ''}`} onClick={onClick}>
			{icon ? <span className="pane-action-block__icon">{icon}</span> : null}
			<span className="pane-action-block__text">
				<span className="pane-action-block__title">{title}</span>
				{meta ? <span className="pane-action-block__meta">{meta}</span> : null}
			</span>
			{trailing}
		</button>
	);
}

/** 多个入口并进一枚矩形（v72.2：多个入口并进一枚「快捷」矩形），行间细线。 */
export function PaneActionRow({ actions }: {
	actions: { key: string; icon?: ReactNode; title: ReactNode; meta?: ReactNode; trailing?: ReactNode; onClick?: () => void }[];
}) {
	return (
		<div className="pane-block">
			{actions.map((a, i) => (
				<div key={a.key}>
					{i > 0 && <hr className="board-row-divider" style={{ margin: '0 -14px' }} />}
					<button type="button" className="pane-action-block pane-action-block--flush" onClick={a.onClick}
						style={{ background: 'transparent', padding: '10px 2px' }}>
						{a.icon ? <span className="pane-action-block__icon">{a.icon}</span> : null}
						<span className="pane-action-block__text">
							<span className="pane-action-block__title">{a.title}</span>
							{a.meta ? <span className="pane-action-block__meta">{a.meta}</span> : null}
						</span>
						{a.trailing}
					</button>
				</div>
			))}
		</div>
	);
}
