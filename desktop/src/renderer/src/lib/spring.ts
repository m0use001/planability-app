/**
 * 弹簧物理 —— M3 Expressive `MotionScheme.expressive()` 的桌面等价（直译手机 `Motion.kt`）。
 *
 * 官方三档 spatial 弹簧（material3 1.5.0-beta02 起）：
 *  · fastSpatial    0.6 / 800   —— quick 档（起步猛、轻过冲）
 *  · defaultSpatial 0.8 / 380   —— standard 档（标准落位）
 *  · slowSpatial    0.8 / 200   —— emphasized 档（慢、沉、收尾轻过冲）
 *  · pageSlide      1.0 / 380   —— 临界阻尼（零过冲），页级横滑专用
 *  · wobble         0.5 / 380   —— 状态翻转一次性「抖一下」（过冲明显）
 *
 * 这里用半隐式 Euler 积分（mass=1）推进；与 Compose `Animatable` 同源按物理积分逐帧走，
 * 60Hz/120Hz 走同一条曲线、只是采样点更密。阻尼系数 = 2·dampingRatio·√stiffness。
 *
 * 纯函数（无 React）：单测可在固定 dt 下钉「临界阻尼不过冲 / 欠阻尼有过冲」。
 */

export interface SpringSpec {
	/** 阻尼比：1.0 = 临界（零过冲）；<1 欠阻尼（过冲）；>1 过阻尼。 */
	dampingRatio: number;
	/** 刚度（N/m 口径）。 */
	stiffness: number;
}

/** 参考沉降时长（ms）—— 弹簧按物理积分没有固定毫秒，这是 90% 沉降的参考值，
 *  供排障与「档位间距 ≥1.3×」断言用（120/180/300）。 */
export const MOTION_MS = {
	quick: 120,
	standard: 180,
	emphasized: 300,
	wobble: 200,
} as const;

/** 全 app 动画的弹簧档位唯一来源（手机 Motion.kt 档位逐字同源）。 */
export const SPRING = {
	quick: { dampingRatio: 0.6, stiffness: 800 } satisfies SpringSpec,
	standard: { dampingRatio: 0.8, stiffness: 380 } satisfies SpringSpec,
	emphasized: { dampingRatio: 0.8, stiffness: 200 } satisfies SpringSpec,
	/** 页级横滑：临界阻尼零过冲（手机 Motion.pageSlide 同款）。 */
	pageSlide: { dampingRatio: 1.0, stiffness: 380 } satisfies SpringSpec,
	wobble: { dampingRatio: 0.5, stiffness: 380 } satisfies SpringSpec,
} as const;

/** 由 dampingRatio + stiffness 算出阻尼系数（mass=1）。 */
export function dampingFor(spec: SpringSpec): number {
	return 2 * spec.dampingRatio * Math.sqrt(spec.stiffness);
}

export interface SpringState {
	/** 当前位置。 */
	x: number;
	/** 当前速度。 */
	v: number;
}

/**
 * 半隐式 Euler 一步：先更新速度（用当前力的旧速度），再用新速度更新位置。
 * 比显式 Euler 稳定得多，是弹簧动画的常规选法。`dt` 单位秒。
 *
 * 精度红线（2026-11-23 实测抓到）：半隐式 Euler 的阻尼项按 `v ← v·(1−dt·c)` 离散，
 * **dt·c 不够小时数值阻尼远超真值**——dt=1/60、standard（c≈31）时 dt·c=0.52，
 * 每步速度直接砍半，ζ=0.8 的轻过冲（解析值 1.5%）被整个杀成 0。所以外部一律走
 * `stepSpringSub`（子步积分把每子步 dt·c 压到 0.02 以下），不要直接拿本函数跑帧。
 */
export function stepSpring(
	state: SpringState,
	target: number,
	spec: SpringSpec,
	dt: number,
): SpringState {
	const d = dampingFor(spec);
	// 弹簧力 = -k·(x - target) - c·v（mass=1 → a = F）
	const force = -spec.stiffness * (state.x - target) - d * state.v;
	const v = state.v + force * dt;
	const x = state.x + v * dt;
	return { x, v };
}

/** 每子步允许的最大 dt·c（低于此值半隐式 Euler 的数值阻尼误差可忽略）。 */
const MAX_DC_PER_SUBSTEP = 0.02;

/**
 * 子步积分版：把 dt 均分成 n 段跑 stepSpring，n = ⌈dt·c / 0.02⌉。
 * 实证（2026-11-23）：standard 0.8/380 在 dt=1/60 下不子分时过冲=0（数值过阻尼），
 * 子分后过冲 ≈ 解析值 e^(−πζ/√(1−ζ²)) = 0.0152。动画帧循环用这个，不用裸 stepSpring。
 */
export function stepSpringSub(
	state: SpringState,
	target: number,
	spec: SpringSpec,
	dt: number,
): SpringState {
	const c = dampingFor(spec);
	const n = Math.max(1, Math.ceil((dt * c) / MAX_DC_PER_SUBSTEP));
	const h = dt / n;
	let s = state;
	for (let i = 0; i < n; i++) {
		s = stepSpring(s, target, spec, h);
	}
	return s;
}

/**
 * 把一个弹簧从 `from` 推到 `target`，跑到「足够接近」为止，返回总步数与是否过冲。
 * 用于单测：临界阻尼 `pageSlide` 不许过冲（max < target + ε）；欠阻尼 `wobble` 必须过冲。
 */
export function settleSpring(
	from: number,
	target: number,
	spec: SpringSpec,
	opts: { dt?: number; maxSteps?: number; eps?: number } = {},
): { steps: number; overshoot: number; final: number } {
	const dt = opts.dt ?? 1 / 60;
	const maxSteps = opts.maxSteps ?? 600;
	const eps = opts.eps ?? 0.0005;
	let s: SpringState = { x: from, v: 0 };
	let overshoot = 0;
	const dir = Math.sign(target - from) || 1;
	for (let i = 0; i < maxSteps; i++) {
		s = stepSpringSub(s, target, spec, dt);
		// 过冲 = 越过 target 的量（同向）
		if ((s.x - target) * dir > 0) {
			overshoot = Math.max(overshoot, Math.abs(s.x - target));
		}
		if (Math.abs(s.x - target) < eps && Math.abs(s.v) < 0.05) {
			return { steps: i + 1, overshoot, final: s.x };
		}
	}
	return { steps: maxSteps, overshoot, final: s.x };
}
