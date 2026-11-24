import { useEffect, useRef, type ReactNode } from 'react';

/**
 * PageHeader：全页面顶栏的**唯一出口**（P3 迁移第一步，方案 E′）。
 * 内部基于 chrome 层的 TopBar（m3-topbar）：底边线、token 取色、桌面密度（标题 26px→20px）。
 * API 不变 {title, action}——38 处调用零改动；旧 .page-header CSS 段随 P3 退役。
 */
export function PageHeader({ title, action }: { title: string; action?: ReactNode }) {
	return (
		<header className="m3-topbar m3-pageheader">
			<div className="m3-topbar__title"><h2>{title}</h2></div>
			{action ? <div className="m3-topbar__actions">{action}</div> : null}
		</header>
	);
}

/**
 * 丢弃守卫（G 类 HIGH-2 根修）：`dirty` 为真时，遮罩点击与 Esc 都先问再关。
 * 审查抓的正是「Notes 编辑器有 dirty 保护、Modal 里其它表单没有」的同一 Modal 两种待遇——
 * Skills 整篇技能文档、自动化表单、笔记本封面点到遮罩瞬间全丢。没传 dirty 的调用方
 * （只读回放、列表选择类）保持即时关闭：只有改过的才需要问。
 *
 * 焦点圈定（pc-a 10-08 挂账 a11y，本会话落地）：
 * - 打开时聚焦首个可聚焦元素，一个都没有就聚焦面板本身（键盘事件有落点）；
 * - Tab/Shift+Tab 逐键拦截、只在模态内循环——背景内容没有 inert，交给浏览器默认
 *   一定会漏到背景按钮上（aria-modal 只是提示，不拦截焦点）；
 * - 关闭时把焦点还给打开它的元素（元素已随状态消失则不还，isConnected 兜底）。
 * 本代码库没有 Modal 套 Modal 的用法（确认弹都是 window.confirm）；若将来嵌套，
 * 两个圈定都在 document 捕获层、后挂者后执行，焦点会落在最上层——够用，别轻易改。
 */
export function Modal({ title, onClose, dirty, children }: { title: string; onClose: () => void; dirty?: boolean; children: ReactNode }) {
	const panelRef = useRef<HTMLDivElement>(null);
	// 还原目标存 ref 而不是 effect 闭包变量：StrictMode 开发态会挂载→卸载→再挂载，
	// 闭包变量第二次捕获到的是模态内元素（还原等于没还原）；ref 只在为空时写入，首轮目标不丢。
	const restoreRef = useRef<HTMLElement | null>(null);
	const requestClose = () => {
		if (!dirty || window.confirm('有未保存的修改，关闭将丢弃，确定关闭？')) { onClose(); }
	};
	// 初始焦点 + 卸载还原：只在挂载时跑一次。若像 Esc 那样每次渲染重挂，
	// cleanup 会在每次输入后把焦点抢还原给 opener，表单根本没法连续打字。
	useEffect(() => {
		const panel = panelRef.current;
		if (!panel) { return; }
		const active = document.activeElement as HTMLElement | null;
		if (restoreRef.current === null && active && !panel.contains(active)) {
			restoreRef.current = active;
		}
		(focusables(panel)[0] ?? panel).focus();
		return () => {
			const restoreTo = restoreRef.current;
			restoreRef.current = null;
			if (restoreTo && restoreTo.isConnected) { restoreTo.focus(); }
		};
	}, []);
	// Esc + Tab：无依赖数组、每次渲染重挂——拿最新的 dirty/onClose（模态内容随输入变化）。
	useEffect(() => {
		const onKey = (e: KeyboardEvent) => {
			if (e.key === 'Escape') {
				e.stopPropagation();
				requestClose();
				return;
			}
			if (e.key !== 'Tab') { return; }
			const panel = panelRef.current;
			if (!panel) { return; }
			const list = focusables(panel);
			// 全拦截：只拦边缘（首尾元素）会漏——背景可聚焦元素与模态在 DOM 上交错，
			// 中段的默认 Tab 同样能跳出模态。
			e.preventDefault();
			if (list.length === 0) { panel.focus(); return; }
			const idx = list.indexOf(document.activeElement as HTMLElement);
			const next = idx === -1
				? (e.shiftKey ? list[list.length - 1] : list[0])
				: list[(idx + (e.shiftKey ? -1 : 1) + list.length) % list.length];
			next.focus();
		};
		document.addEventListener('keydown', onKey, true);
		return () => document.removeEventListener('keydown', onKey, true);
	});
	return (
		<div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) { requestClose(); } }}>
			<div className="modal" ref={panelRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title}>
				<div className="modal-title">{title}</div>
				{children}
			</div>
		</div>
	);
}

/** 模态内可聚焦元素（Tab 循环的范围）。disabled / tabindex=-1 不算数。 */
function focusables(root: HTMLElement): HTMLElement[] {
	return Array.from(root.querySelectorAll<HTMLElement>(
		'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
	));
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
	return (
		<label className="field">
			<span className="field-label">{label}</span>
			{children}
		</label>
	);
}

export function Empty({ text }: { text: string }) {
	return <div className="empty">{text}</div>;
}

export function DeleteButton({ onDelete }: { onDelete: () => void }) {
	return (
		<button className="btn-ghost btn-danger" onClick={() => { if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { onDelete(); } }}>
			删除
		</button>
	);
}
