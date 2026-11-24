/**
 * 主从分栏决策 —— 直译手机 `ui/common/SplitBoard.kt` 的 `MasterDetail`。
 *
 * 三条硬约定（与手机同源，守卫钉住）：
 *  1. **分栏门槛是算出来的**：内容宽 − 详情宽 ≥ `BOARD.minListWidth` 才分栏。
 *     600~839dp 的「小平板硬上两栏」把左板压成 220dp 的老缺陷就是这么来的。
 *  2. **先算宽度够不够，再看选中态**：宽度不够时选中态没有任何分栏含义——直接 Single。
 *  3. **详情宽分两型**：表单/列表型固定 380；内容型（文件预览、笔记编辑器）取内容宽 40%，
 *     夹在 380~480dp。
 */
import { BOARD } from './board';

/** 一页在宽屏下的三态：单列 / 两栏且右栏是概览 / 两栏且右栏是选中项详情。 */
export type DetailPane = 'single' | 'overview' | 'detail';

/**
 * 详情板宽度：
 *  · 表单/列表型详情 → 固定 `BOARD.detailWidth`（380）；
 *  · 内容型详情（文件预览、笔记编辑器）→ 取内容宽的 40%，夹在 380~480dp。
 */
export function detailWidthFor(contentWidth: number, content: boolean): number {
	if (!content) { return BOARD.detailWidth; }
	return Math.min(
		BOARD.contentDetailWidth,
		Math.max(BOARD.detailWidth, Math.round(contentWidth * 0.40)),
	);
}

/**
 * 分栏判定：**先算宽度够不够，再看选中态**。
 * 宽度不够时选中态没有任何分栏含义——直接 `single`，页面继续走原来的单列路径。
 */
export function paneOf(
	contentWidth: number,
	detailWidth: number,
	hasSelection: boolean,
): DetailPane {
	if (contentWidth - detailWidth < BOARD.minListWidth) { return 'single'; }
	return hasSelection ? 'detail' : 'overview';
}
