/** 明暗主题（方案 E′）：偏好存 localStorage（renderer 本地，不走 IPC 契约），
 *  应用到 <html data-theme>——token 层（styles/m3-tokens.css）的 [data-theme='light'] 块
 *  与桥接变量全部跟着切，md-* 组件与 chrome 层自动同步。默认暗（现状）。
 *  URL 参数 ?theme=light 优先于存储：视觉验收（--shot）需要可复现的绝对指定，
 *  依赖 localStorage 会受上一个进程是否落盘影响。 */
export type ThemeMode = 'dark' | 'light';

export function getTheme(): ThemeMode {
	const q = new URLSearchParams(location.search).get('theme');
	if (q === 'light' || q === 'dark') { return q; }
	return localStorage.getItem('m3-theme') === 'light' ? 'light' : 'dark';
}

export function applyTheme(m: ThemeMode): void {
	document.documentElement.dataset.theme = m;
	localStorage.setItem('m3-theme', m);
}

// 模块加载即应用（main.tsx 首个 import 之一，先于首帧）
applyTheme(getTheme());
