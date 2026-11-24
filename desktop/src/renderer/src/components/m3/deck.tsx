/**
 * 横向纸条钻入 —— 直译手机 `ui/common/DeckStrip.kt` + `BoardDeck.kt`。
 *
 * 「相框不动、一条横向纸条整体右抽」：主板滑进副框（宽连续改排版）、更早滑出右缘、
 * 更深一层的板从左缘滑进主框；返回集体左移。几何完全由 `lib/deckSlot.ts` 的 `deckSlot` 决定。
 *
 * 与手机的差异（桌面适配，不改视觉）：
 *  - 桌面没有预测返回手势 → 用 返回钮 / Esc / 面包屑 触发带动画 pop（动画照播）。
 *  - 透明瓦片 + 壳层相框：这里 DeckStrip 自带相框层（两块固定圆角板）+ 缝带盖片，
 *    页面只需把 `<DeckStrip>` 放进一个定位容器；纸条从相框后面穿过。
 *
 * v70e/g 的「墙带遮罩」在桌面不适用：桌面只有横向纸条、同高，container overflow:hidden
 * 已经裁住上下墙；缝带盖片裁住两框之间的缝带。逐像素与手机一致。
 */
import type { ReactNode } from 'react';
import { deckSlot, mainWidthOf } from '../../lib/deckSlot';
import { BOARD } from '../../lib/board';
import { SPRING, useAnimatedNumber } from '../../lib/motion';
import { useContainerWidth } from '../../hooks/useContainerWidth';
import '../../styles/board.css';

export interface DeckStripProps {
	/** 板序列（浅 → 深）。每块透明单板，宽度随 progress 在 mainW↔secW 间连续插值。 */
	panes: (() => ReactNode)[];
	/** 当前主框里那块板的序号（动画中为小数）。 */
	progress: number;
	/** 副框宽（默认 BOARD.detailWidth=380）。 */
	secWidth?: number;
	className?: string;
}

export function DeckStrip({ panes, progress, secWidth = BOARD.detailWidth, className = '' }: DeckStripProps) {
	const [ref, width] = useContainerWidth<HTMLDivElement>();
	const gap = BOARD.gap;
	const mainW = mainWidthOf(width, secWidth, gap);

	return (
		<div ref={ref} className={`deck-viewport ${className}`.trim()}>
			{width > 0 && (
				<>
					{/* 相框：壳层固定绘制（纸条从后面穿过） */}
					<div className="deck-frame deck-frame--main" style={{ width: mainW }} />
					<div
						className="deck-frame deck-frame--sec"
						style={{ left: mainW + gap, width: secWidth }}
					/>
					{/* 纸条瓦片：透明、只做圆角裁剪 */}
					{panes.map((Pane, i) => {
						const s = deckSlot(i, progress, width, secWidth, gap);
						return (
							<div
								key={i}
								className="deck-tile"
								style={{ width: s.w, transform: `translateX(${s.x}px)` }}
							>
								<Pane />
							</div>
						);
					})}
					{/* 缝带盖片：两框之间的 12px 缝盖回最上层 */}
					<div className="deck-seam" style={{ left: mainW, width: gap }} />
				</>
			)}
		</div>
	);
}

/**
 * 把层级 `level`（整数）按 `pageSlide` 弹簧（临界阻尼零过冲）动画到 progress。
 * 与手机 `BoardDeck` 的 `Animatable(level.toFloat()).animateTo(level, pageSlide())` 同源。
 *
 * 用法：
 *   const [level, setLevel] = useState(1);   // 1=根两板，2=钻入一级…
 *   const progress = useDeckProgress(level);
 *   <DeckStrip panes=[...] progress={progress} />
 */
export function useDeckProgress(level: number): number {
	return useAnimatedNumber(level, SPRING.pageSlide);
}

/**
 * 便利封装：给定 `level` 与 `panes`，内部做弹簧动画。
 * 钻入 = `setLevel(level+1)`（外部状态变化即触发抽动）；返回 = `setLevel(level-1)`。
 */
export function DeckBoard({
	level, panes, secWidth, className,
}: {
	level: number; panes: (() => ReactNode)[]; secWidth?: number; className?: string;
}) {
	const progress = useDeckProgress(level);
	return <DeckStrip panes={panes} progress={progress} secWidth={secWidth} className={className} />;
}
