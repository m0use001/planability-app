/**
 * 自绘 chrome 层（方案 E′）：@material/web 缺的外壳件——Card/TopBar/SideNav/Snackbar/Segmented/Tooltip。
 * 设计基调（UI_FRAMEWORK_PLAN §2.1）：桌面密度（行更密、带 hover/焦点环）、token 单一真源、
 * 动效只用 --dur-* 与 --ease-*。行为边界：Segmented 自带 roving-tabindex+方向键；
 * Tooltip 刻意 CSS-only（纯提示、无交互内容；滚动容器内会被裁切——需要逃逸时再上 Radix）。
 */
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Check } from 'lucide-react';
import './chrome.css';

// ---------- Card ----------
export function Card({ variant = 'tonal', className = '', children }: {
	variant?: 'tonal' | 'outline' | 'elevated'; className?: string; children: ReactNode;
}) {
	return <div className={`m3-card m3-card--${variant} ${className}`}>{children}</div>;
}

// ---------- TopBar ----------
export function TopBar({ title, leading, actions }: { title: ReactNode; leading?: ReactNode; actions?: ReactNode }) {
	return (
		<header className="m3-topbar">
			{leading ? <div className="m3-topbar__leading">{leading}</div> : null}
			<div className="m3-topbar__title">{title}</div>
			{actions ? <div className="m3-topbar__actions">{actions}</div> : null}
		</header>
	);
}

// ---------- SideNav ----------
export interface SideNavItem<K extends string> { key: K; label: string; icon?: ReactNode; badge?: ReactNode }
export function SideNav<K extends string>({ items, active, onSelect, ariaLabel = '主导航' }: {
	items: SideNavItem<K>[]; active: K; onSelect: (key: K) => void; ariaLabel?: string;
}) {
	return (
		<nav className="m3-sidenav" aria-label={ariaLabel}>
			{items.map(it => (
				<button key={it.key} type="button"
					className={`m3-sidenav__item ${active === it.key ? 'm3-sidenav__item--active' : ''}`}
					aria-current={active === it.key ? 'page' : undefined}
					onClick={() => onSelect(it.key)}>
					{it.icon ? <span className="m3-sidenav__icon">{it.icon}</span> : null}
					<span className="m3-sidenav__label">{it.label}</span>
					{it.badge != null ? <span className="m3-sidenav__badge">{it.badge}</span> : null}
				</button>
			))}
		</nav>
	);
}

// ---------- Segmented（roving tabindex + 方向键循环，M3 语义） ----------
export function Segmented<K extends string>({ options, value, onChange, ariaLabel }: {
	options: { value: K; label: ReactNode }[]; value: K; onChange: (v: K) => void; ariaLabel?: string;
}) {
	const refs = useRef<(HTMLButtonElement | null)[]>([]);
	const onKey = (e: React.KeyboardEvent, i: number) => {
		const delta = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1
			: e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
		if (!delta) return;
		e.preventDefault();
		const next = (i + delta + options.length) % options.length;
		refs.current[next]?.focus();
		onChange(options[next].value);
	};
	return (
		<div className="m3-segmented" role="radiogroup" aria-label={ariaLabel}>
			{options.map((o, i) => (
				<button key={o.value} type="button" role="radio" aria-checked={value === o.value}
					ref={el => { refs.current[i] = el; }}
					tabIndex={value === o.value ? 0 : -1}
					className={`m3-segmented__opt ${value === o.value ? 'm3-segmented__opt--selected' : ''}`}
					onClick={() => onChange(o.value)}
					onKeyDown={e => onKey(e, i)}>
					{value === o.value ? <Check size={14} className="m3-segmented__check" aria-hidden="true" /> : null}
					{o.label}
				</button>
			))}
		</div>
	);
}

// ---------- ListRow（通用列表行；动作钮 hover/focus-within 显形） ----------
export interface ListRowAction { label: string; icon?: ReactNode; danger?: boolean; onClick: () => void }
export function ListRow({ active, muted, leading, title, meta, actions, onClick }: {
	active?: boolean; muted?: boolean; leading?: ReactNode; title: ReactNode; meta?: ReactNode;
	actions?: ListRowAction[]; onClick?: () => void;
}) {
	return (
		<div className={`m3-listrow ${active ? 'm3-listrow--active' : ''} ${muted ? 'm3-listrow--muted' : ''}`}>
			<button type="button" className="m3-listrow__main" onClick={onClick}>
				{leading ? <span className="m3-listrow__leading">{leading}</span> : null}
				<span className="m3-listrow__title">{title}</span>
				{meta != null ? <span className="m3-listrow__meta">{meta}</span> : null}
			</button>
			{(actions ?? []).map(a => (
				<button key={a.label} type="button" title={a.label} aria-label={a.label}
					className={`m3-listrow__act ${a.danger ? 'm3-listrow__act--danger' : ''}`}
					onClick={a.onClick}>
					{a.icon}
				</button>
			))}
		</div>
	);
}

// ---------- Snackbar（单条替换、4s 自动消失、可选动作） ----------
interface Snack { id: number; text: string; action?: { label: string; run: () => void } }
type ShowSnack = (text: string, action?: { label: string; run: () => void }) => void;
const SnackContext = createContext<ShowSnack>(() => { /* 未包 Provider 时不响（与直接 throw 相比更利于渐进接入） */ });
export function useSnackbar() { return useContext(SnackContext); }
export function SnackbarProvider({ children }: { children: ReactNode }) {
	const [snack, setSnack] = useState<Snack | null>(null);
	const timer = useRef(0);
	const show = useCallback<ShowSnack>((text, action) => {
		setSnack({ id: Date.now(), text, action });
		window.clearTimeout(timer.current);
		timer.current = window.setTimeout(() => setSnack(null), 4000);
	}, []);
	useEffect(() => () => window.clearTimeout(timer.current), []);
	return (
		<SnackContext.Provider value={show}>
			{children}
			{snack ? (
				<div className="m3-snackbar" role="status">
					<span className="m3-snackbar__text">{snack.text}</span>
					{snack.action ? (
						<button type="button" className="m3-snackbar__action"
							onClick={() => { snack.action?.run(); setSnack(null); window.clearTimeout(timer.current); }}>
							{snack.action.label}
						</button>
					) : null}
				</div>
			) : null}
		</SnackContext.Provider>
	);
}

// ---------- Tooltip（CSS-only；限制见文件头） ----------
export function Tooltip({ label, children }: { label: string; children: ReactNode }) {
	return <span className="m3-tooltip" data-tip={label}>{children}</span>;
}
