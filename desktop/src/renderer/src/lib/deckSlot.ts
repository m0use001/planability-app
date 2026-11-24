/**
 * 「水平纸条」板组里第 `index` 块板的矩形 —— 直译手机 `ui/common/BoardDeck.kt` 的 `deckSlot`。
 *
 * 一条横向胶片上排着任意多块板，相框（视口）压着**两块**：主框（弹性宽 mainW）+
 * 副框（定宽 secW）。`progress` 是「现在哪块板在主框」（可为小数，动画中）：
 *
 *  · progress == index：这块板在**主框**（x=0，宽 mainW = windowW − secW − gap）。
 *  · progress == index − 1：这块板滑进了**副框**（x = mainW + gap，宽从 mainW 连续
 *    收窄到 secW = 内容逐帧自动改排版）。
 *  · progress == index + 1：这块板从左缘**滑入主框**（x = −mainW → 0）。
 *  · progress == index − 2：这块板完全滑出**右缘**（x ≥ windowW）。
 *
 * 纯函数（无 React 依赖）：单测在整数点 / 中点 / 出屏 / 越界钉几何，与手机 `DeckGeometryTest`
 * 同口径（主框位 / 副框位 / 左缘外 / 右缘外）。
 */
export interface DeckSlot {
	/** 板左缘相对视口左缘的 x（可为负）。 */
	x: number;
	/** 板宽。 */
	w: number;
}

export function deckSlot(
	index: number,
	progress: number,
	windowW: number,
	secW: number,
	gap: number,
): DeckSlot {
	const mainW = Math.max(0, windowW - secW - gap);
	// delta = 0 → 主框；1 → 副框；-1 → 左缘外整板宽；2 → 右缘外
	const delta = progress - index;
	if (delta <= 0) {
		// 屏外左侧 / 从左缘滑入主框（与 drillTiles 的 detail 完全同式）
		return { x: delta * mainW, w: mainW };
	}
	if (delta <= 1) {
		// 主框 → 副框：x 走 mainW+gap，宽从 mainW 连续插值到 secW（「自动改排版」）
		return {
			x: delta * (mainW + gap),
			w: mainW + (secW - mainW) * delta,
		};
	}
	// 副框 → 滑出右缘（delta=2 时左缘贴视口右缘 = 完全出屏）
	return {
		x: (mainW + gap) + (delta - 1) * secW,
		w: secW,
	};
}

/** 主框宽（= 内容宽 − 副框宽 − 缝）。 */
export function mainWidthOf(windowW: number, secW: number, gap: number): number {
	return Math.max(0, windowW - secW - gap);
}
