/**
 * 内容容器实测宽 —— 对应手机 `core/WindowSize.kt` 的 `rememberMeasuredWindowWidthDp`
 * （读 `LocalWindowInfo.containerSize` = **当前窗口/容器**的真实尺寸）。
 *
 * 为什么不用 `window.innerWidth`：多显示器 / 最大化时它报显示器宽，会把
 * 「内容宽 − 详情宽 ≥ 380 才分栏」这类门槛在中等宽度上提前放行（手机 v64 同坑）。
 * ResizeObserver 在窗口 resize / 布局变化时都会回调，中途拖拽也不抖。
 *
 * 首帧口径：useLayoutEffect 在**绘制前**量一次——宽窄分支（如笔记页纸条 vs 三列）
 * 不会先闪一帧窄版再跳宽版；返回的 ref 是 setState 函数，可直接当 callback ref 用。
 */
import { useLayoutEffect, useState } from 'react';

export function useContainerWidth<T extends HTMLElement = HTMLDivElement>() {
	const [el, setEl] = useState<T | null>(null);
	const [width, setWidth] = useState(0);

	useLayoutEffect(() => {
		if (!el) { return; }
		const update = () => setWidth(Math.round(el.clientWidth));
		update();
		const ro = new ResizeObserver(update);
		ro.observe(el);
		return () => ro.disconnect();
	}, [el]);

	return [setEl, width] as const;
}
