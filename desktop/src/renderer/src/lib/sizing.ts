/**
 * 窗口尺寸类 —— 直译手机 `core/WindowSize.kt`。
 *
 * 断点沿用 Material 3 的 WindowSizeClass：
 * - Compact：< 600dp —— 手机竖屏，走单列
 * - Medium：600–839dp —— 小平板竖屏 / 大手机横屏，走左侧导航栏 + 双栏
 * - Expanded：≥ 840dp —— 平板横屏 / 桌面窗口，走左侧导航栏 + 双栏 + 更宽正文
 *
 * PC 判据 = **内容容器实测宽**（ResizeObserver，见 `hooks/useContainerWidth`），
 * 不是 `window.innerWidth`——多显示器 / 最大化会把显示器宽当成内容宽骗到临界值。
 * 与手机 `rememberMeasuredWindowWidthDp`（读 `LocalWindowInfo.containerSize`）同源。
 */

export type SizeClass = 'compact' | 'medium' | 'expanded';

/** Compact → Medium 的断点（dp）。 */
export const MEDIUM_WIDTH_BREAKPOINT = 600;

/** Medium → Expanded 的断点（dp）。 */
export const EXPANDED_WIDTH_BREAKPOINT = 840;

/** 由窗口宽度（dp）算出尺寸类。纯函数，便于单测直接打边界值。 */
export function sizeClassOf(widthDp: number): SizeClass {
	if (widthDp < MEDIUM_WIDTH_BREAKPOINT) { return 'compact'; }
	if (widthDp < EXPANDED_WIDTH_BREAKPOINT) { return 'medium'; }
	return 'expanded';
}

/** 是否是「宽」形态（≥600dp）：决定双栏是否启用。 */
export function isWide(c: SizeClass): boolean {
	return c !== 'compact';
}

/** 是否是真平板横屏（≥840dp）：手机外壳形态（Rail + 两板）只在这个档启用。
 *  与手机 `rememberShellRailForm` 同口径：600–839dp 退回手机形态（单列 + 抽屉）。 */
export function isExpanded(c: SizeClass): boolean {
	return c === 'expanded';
}
