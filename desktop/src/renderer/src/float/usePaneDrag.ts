import { useRef } from 'react';
import type { FloatRole } from './FloatPane';

/* 悬浮窗拖拽（pc-b 2026-10-07 assign——渲染层此前从未实现，通道 movePane 是通的）。
 *
 * 三个他给的硬约束 + 一个我定的 UX 决策：
 * ① movePane 收「新左上角」，坐标系 = DIP 屏幕坐标：window.screenX/screenY + 指针位移。
 *    绝不乘 devicePixelRatio——screenX 已经是 CSS 像素（DIP），setBounds 吃的就是这套。
 * ② 组跟随由主进程负责（host 的 moved 监听 60ms 防抖 → applyMove → pushBounds），
 *    渲染层只管把被拖窗的新位置报上去。
 * ③ 用 pointer 事件而不是 CSS -webkit-app-region：app-region 的拖拽由 OS 接管，
 *    DOM 收不到 pointerdown，自动脱钩与合成事件验收都无从谈起。
 *
 * UX 决策（pc-b 明说留给我定的岔路）：**按下即自动脱钩再拖**，而不是「只允许拖锚点」——
 * 用户刚报的原话就是「拖不动」，第一反应多半是去拖输入框（尤其它现在在最下面）；
 * 纯锚点方案会让原投诉原样复现。脱钩是用户拖动本身表达的显式意图、菜单条的链条开关
 * 会实时反映状态、且随时可点回来（Q3=A 的可逆原则）。拖锚点则天然拖整组。
 *
 * 脱钩必须发生在第一条 movePane 之前（await setLinked 再发位置），否则刚解算的
 * 「新位置」会被 linked 重排吸回去，表现为闪一下又回原地。
 * 用 pointer capture 保证移出窗体仍收到 move/up；合成事件（验收脚本）capture 会抛，
 * 全部 try/catch 包住——脚本会把事件直接派发到把手上，一样走这条逻辑。 */

type Drag = {
	/** 手势起点：指针屏幕坐标与窗口左上角，均 DIP。整段手势的位移都相对这两组基准算。 */
	sx: number; sy: number; wx: number; wy: number;
	started: boolean;
	/** 等 setLinked(false) 落地期间挂起 move，见头注。 */
	detaching: boolean;
	pending: { x: number; y: number } | null;
	timer: number | null;
};

const THRESHOLD = 4;
/** 33ms ≈ 一帧的量级：IPC 每次 movePane 都要解算+推窗，逐像素发会淹了主进程。 */
const THROTTLE_MS = 33;

export function usePaneDrag(role: FloatRole, linked: boolean, isAnchor: boolean) {
	const ref = useRef<Drag | null>(null);

	const send = (x: number, y: number) => { void window.planability.float.movePane(role, x, y); };

	const flush = (d: Drag) => {
		if (d.timer !== null) { window.clearTimeout(d.timer); d.timer = null; }
		if (d.pending) { const { x, y } = d.pending; d.pending = null; send(x, y); }
	};

	const schedule = (d: Drag, x: number, y: number) => {
		d.pending = { x, y };
		if (d.timer !== null) { return; }
		d.timer = window.setTimeout(() => { d.timer = null; flush(d); }, THROTTLE_MS);
	};

	const down = (e: React.PointerEvent<HTMLDivElement>) => {
		if (e.button !== 0) { return; }
		try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* synthetic events */ }
		ref.current = {
			sx: e.screenX, sy: e.screenY, wx: window.screenX, wy: window.screenY,
			started: false, detaching: false, pending: null, timer: null,
		};
	};

	const move = (e: React.PointerEvent<HTMLDivElement>) => {
		const d = ref.current;
		if (!d) { return; }
		const dx = e.screenX - d.sx;
		const dy = e.screenY - d.sy;
		if (!d.started) {
			if (Math.abs(dx) < THRESHOLD && Math.abs(dy) < THRESHOLD) { return; }
			d.started = true;
			document.body.classList.add('float-dragging');
			if (linked && !isAnchor && !d.detaching) {
				d.detaching = true;
				void window.planability.float.setLinked(role, false)
					.catch(() => undefined)
					.finally(() => {
						d.detaching = false;
						flush(d);
						void window.planability.float.saveLayout().catch(() => undefined);
					});
				return;
			}
		}
		const x = Math.round(d.wx + dx);
		const y = Math.round(d.wy + dy);
		if (d.detaching) { d.pending = { x, y }; return; }
		schedule(d, x, y);
	};

	const up = (e: React.PointerEvent<HTMLDivElement>) => {
		const d = ref.current;
		if (!d) { return; }
		try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* synthetic events */ }
		ref.current = null;
		document.body.classList.remove('float-dragging');
		if (!d.started) { return; }
		d.pending = { x: Math.round(d.wx + (e.screenX - d.sx)), y: Math.round(d.wy + (e.screenY - d.sy)) };
		// 脱钩还在路上：终态坐标留给 finally 的 flush 送（那之后 linked 才是 false）。
		if (d.detaching) { return; }
		flush(d);
		void window.planability.float.saveLayout().catch(() => undefined);
	};

	return {
		onPointerDown: down,
		onPointerMove: move,
		onPointerUp: up,
		onPointerCancel: up,
	};
}
