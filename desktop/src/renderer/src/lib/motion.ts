/**
 * 动效档位 —— 直译手机 `ui/common/Motion.kt` 的 M3E 弹簧体系。
 *
 * 全 app 动画都从这里取规格，任何调用点不许再手写毫秒/贝塞尔（守卫钉）。
 * 档位↔用途回手机原样：
 *  · quick      fastSpatial 0.6/800   —— 收起/取消/按压回弹：起步最猛、轻过冲
 *  · standard   defaultSpatial 0.8/380 —— 二级 tab/分段/展开：标准落位
 *  · emphasized slowSpatial 0.8/200   —— 弹层进入等大面积位移：慢、沉、收尾轻过冲
 *  · pageSlide  1.0/380（临界阻尼）    —— 页级横滑：**零过冲**（整屏位移过冲=「不稳」）
 *  · wobble     0.5/380                —— 状态翻转一次性「抖一下」（过冲明显，见手机红线）
 *
 * 位移可以过冲、透明度/颜色不许闪（官方口径）：非空间类用 CSS transition 即可，不走弹簧。
 */
import { useEffect, useRef, useState } from 'react';
import { stepSpringSub, SPRING, MOTION_MS, type SpringSpec } from './spring';

// 档位常量真源在 spring.ts（纯模块，node 测试可直取）；这里转出口保持「从 motion 取」的调用习惯。
export { SPRING, MOTION_MS };

/**
 * 把一个数值目标按弹簧动画到当前值，逐帧 rAF，返回当前动画值。
 * 用于 DeckStrip 的 progress、环卡进度等「物理积分」动画（与手机 Animatable 同源）。
 *
 * 连续性：目标变化时**不重置**内部状态（x/v 保留）——从当前值继续弹向新目标；
 * 落定后（差 <0.001 且速度 <0.01）停表并精确落位。丢帧大步钳到 50ms 防穿模。
 */
export function useAnimatedNumber(target: number, spec: SpringSpec = SPRING.standard): number {
	const [value, setValue] = useState(target);
	const stateRef = useRef({ x: target, v: 0 });
	const targetRef = useRef(target);
	targetRef.current = target;
	const specRef = useRef(spec);
	specRef.current = spec;

	useEffect(() => {
		let raf = 0;
		let last = performance.now();
		const tick = (now: number) => {
			let dt = (now - last) / 1000;
			last = now;
			if (dt > 0.05) { dt = 0.05; }
			if (dt <= 0) { dt = 1 / 120; }
			const next = stepSpringSub(stateRef.current, targetRef.current, specRef.current, dt);
			stateRef.current = next;
			setValue(next.x);
			const t = targetRef.current;
			if (Math.abs(next.x - t) > 0.001 || Math.abs(next.v) > 0.01) {
				raf = requestAnimationFrame(tick);
			} else {
				stateRef.current = { x: t, v: 0 };
				setValue(t);
			}
		};
		raf = requestAnimationFrame(tick);
		return () => cancelAnimationFrame(raf);
	}, [target, spec.dampingRatio, spec.stiffness]);

	return value;
}

/** 按压缩放反馈（M3E "squish"）的 CSS 版：按下缩到 scale、松手 quick 弹回。
 *  用 CSS class + transition 实现（手机走 graphicsLayer+弹簧，桌面等价物够用）。 */
export const PRESS_SCALE_CSS = 'm3-press-scale';
