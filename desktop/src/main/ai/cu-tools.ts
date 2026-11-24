/* M21 Computer Use tools.
 *
 * Two tiers, deliberately:
 *
 *  - READ (risk=auto): cu_screenshot, cu_list_windows, cu_screen_text. Pure observation.
 *  - INJECT (risk=confirm): cu_click, cu_scroll, cu_key, cu_type. Each one validates the
 *    hard-deny list and the foreground precondition BEFORE anything happens, so a refusal is
 *    provable today — the acceptance criterion "审批卡拒绝后光标/剪贴板无变化" does not depend
 *    on the native module existing.
 *
 * The actual SendInput backend is not shipped in this blade. Rather than ship a tool that
 * silently does nothing, the injection tools report `需要原生注入后端` as their failure. A
 * visible "not wired yet" beats an invisible no-op that looks like it worked.
 */

import type { ToolRegistry } from './tools';
import {
	CaptureStore, CU_HARD_DENY, CU_LIMITS, checkForeground, checkKeyAllowed,
	dipToPhysical, inBounds, physicalToDip,
	type ForegroundState, type ScreenGeometry, type WindowInfo,
} from './cu';

export interface CuDeps {
	/** Persist a screenshot; implemented over Electron desktopCapturer by index.ts. */
	capture: () => Promise<{ ok: boolean; png?: Buffer; geometry?: ScreenGeometry; reason?: string }>;
	/** Current windows (title/bounds/focused), from BrowserWindow.getAllWindows. */
	listWindows: () => WindowInfo[];
	/** Real frontmost window title from Win32. Null when the native module is absent. */
	systemForeground?: () => string | null;
	/** Screen text for a window (Windows OCR); returns '' when unavailable. */
	screenText?: (win: WindowInfo) => Promise<string>;
	store: CaptureStore;
	/** Injection backend. Absent when the native module is not built. */
	inject?: InjectBackend;
	/** Why inject is unavailable, so the tool explains itself instead of shrugging. */
	injectMissing?: string;
}

/** Bundle what the foreground guard needs, in one place. */
function fgState(deps: CuDeps): ForegroundState {
	return {
		windows: deps.listWindows(),
		systemTitle: deps.systemForeground ? deps.systemForeground() : null,
	};
}

/** What a future native module must provide. Kept narrow on purpose. */
export interface InjectBackend {
	click(physical: { x: number; y: number }, button: 'left' | 'right'): Promise<void>;
	scroll(physical: { x: number; y: number }, notches: number): Promise<void>;
	key(keys: string): Promise<void>;
	/** Put text on the clipboard and paste it (clipboard path, per plan). */
	pasteText(text: string): Promise<void>;
}

const NO_BACKEND_HINT = '需要原生注入后端（napi-rs SendInput）才能执行键鼠注入';

/**
 * Say WHY injection is unavailable. "Module missing, run npm run cu:build" is actionable;
 * a generic "not wired yet" makes the model retry the same call forever.
 */
function noBackend(deps: CuDeps): string {
	return deps.injectMissing ? `${NO_BACKEND_HINT}：${deps.injectMissing}` : NO_BACKEND_HINT;
}

export function registerCuTools(reg: ToolRegistry, deps: CuDeps): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	// ---------- read-only ----------

	def('cu_screenshot', 'auto',
		'截取整个屏幕并落盘，返回图片路径与元数据（物理像素尺寸/缩放比），不把图片内容交给模型。',
		{ type: 'object', properties: {} },
		async () => {
			const c = await deps.capture();
			if (!c.ok || !c.png || !c.geometry) { return `[FAIL] 截图失败：${c.reason ?? '未知原因'}`; }
			const r = await deps.store.save(c.png, c.geometry);
			if (!r.ok || !r.path || !r.size) { return `[FAIL] ${r.reason}`; }
			await deps.store.prune();
			const g = c.geometry;
			// Two different rectangles, and the old text called them both "物理像素". The image
			// may be scaled down to maxEdge while the screen is not — so the model saw
			// cu_geometry say one size, cu_screenshot say another, and neither matched what
			// dipToPhysical produced. Label each with what it measures, and only claim scaling
			// when it actually happened: a screen already under maxEdge is never shrunk.
			const scaled = r.size.width !== g.bounds.width || r.size.height !== g.bounds.height;
			return [
				`截图已保存：${r.path}`,
				`图片尺寸：${r.size.width}x${r.size.height}`
					+ (scaled ? `（已缩放，不是屏幕尺寸；上限 maxEdge=${CU_LIMITS.maxEdge}）` : '（与屏幕物理尺寸一致）'),
				`屏幕：物理 ${g.bounds.width}x${g.bounds.height} @ ${g.bounds.x},${g.bounds.y}`
					+ `（DIP ${g.dip.width}x${g.dip.height}，缩放比 ${g.scaleFactor}x）`,
				'注意：图片不会进入模型上下文，需要时用 file_read 或让用户查看该路径。',
				'后续注入坐标一律用物理像素，可用 dipToPhysical 换算。',
			].join('\n');
		});

	def('cu_list_windows', 'auto',
		'列出当前打开的窗口（标题、bounds、是否前台）。注入前用它确认目标窗口。',
		{ type: 'object', properties: {} },
		async () => {
			const sys = deps.systemForeground ? deps.systemForeground() : null;
			const lines: string[] = [];
			if (sys) {
				lines.push(`系统前台窗口（真实焦点，可能是其他程序）：${sys}`);
			} else {
				lines.push('系统前台窗口：未知（原生模块未构建，无法读取其他程序的窗口标题）');
			}
			const ws = deps.listWindows();
			if (!ws.length) { lines.push('（本应用没有可枚举的窗口）'); }
			else {
				lines.push('本应用窗口：');
				for (const [i, w] of ws.entries()) {
					lines.push(`  ${i + 1}. ${w.title}${w.focused ? '  [前台]' : ''}${w.isSelf ? '  [本应用]' : ''}  ` +
						`${w.bounds.width}x${w.bounds.height} @ ${w.bounds.x},${w.bounds.y}`);
				}
			}
			return lines.join('\n');
		});

	def('cu_screen_text', 'auto',
		'获取某个窗口的屏幕文本（OCR），用于定位控件。不指定窗口则取前台窗口。',
		{ type: 'object', properties: { window: { type: 'string', description: '窗口标题，来自 cu_list_windows' } } },
		async (input) => {
			const ws = deps.listWindows();
			const fg = ws.find(w => w.focused);
			const want = input?.window ? ws.find(w => w.title === String(input.window)) : fg;
			if (!want) { return '[FAIL] 未找到目标窗口（先用 cu_list_windows 查看）。'; }
			if (!deps.screenText) { return '[FAIL] 屏幕文本抽取未启用（本机缺少 Windows OCR 通道）。'; }
			try {
				const text = await deps.screenText(want);
				const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean).slice(0, 300);
				if (!lines.length) { return `（窗口「${want.title}」未取到文本）`; }
				return `窗口「${want.title}」的屏幕文本（${lines.length} 行）：\n${lines.join('\n')}`;
			} catch (e) {
				return `[FAIL] 取屏幕文本失败：${String((e as Error)?.message ?? e)}`;
			}
		});

	// ---------- injection (all confirm; all refuse safely when unproven) ----------

	def('cu_click', 'confirm',
		`在屏幕某点点击（物理像素坐标，逐次审批）。命中硬禁表或目标窗口不在前台时拒绝。最多单击一次，不支持双击。`,
		{
			type: 'object',
			properties: {
				x: { type: 'number', description: '物理像素 X' },
				y: { type: 'number', description: '物理像素 Y' },
				button: { type: 'string', description: 'left（默认）或 right' },
				window: { type: 'string', description: '期望的前台窗口标题，用于前台校验' },
			},
			required: ['x', 'y'],
		},
		async (input) => {
			const g = await geometryOrFail(deps);
			if ('reason' in g) { return g.reason; }
			const x = Number(input?.x), y = Number(input?.y);
			if (!Number.isFinite(x) || !Number.isFinite(y)) { return '[FAIL] x/y 必须是数字（物理像素）。'; }
			const fg = checkForeground(fgState(deps), input?.window ? String(input.window) : undefined);
			if (fg.ok === false) { return `[DENIED] ${fg.reason}`; }
			if (!inBounds(x, y, g.geom)) {
				// Name the bounds: "out of range" alone leaves the model with no way to notice
				// it was aiming in the wrong coordinate space, which is exactly what happened.
				const b = g.geom.bounds;
				return `[DENIED] 坐标 (${x},${y}) 超出屏幕范围（物理 bounds ${b.width}x${b.height} @ ${b.x},${b.y}，当前缩放 ${g.geom.scaleFactor}x）。`;
			}
			if (!deps.inject) { return `[FAIL] ${noBackend(deps)}（已通过硬禁表与前台校验：点 (${x},${y})）`; }
			try {
				await deps.inject.click({ x, y }, input?.button === 'right' ? 'right' : 'left');
				return `已点击 (${x},${y}) 物理像素（≈ DIP ${physicalToDip(x, y, g.geom.scaleFactor).x},${physicalToDip(x, y, g.geom.scaleFactor).y}）。`;
			} catch (e) {
				return `[FAIL] 注入失败：${String((e as Error)?.message ?? e)}`;
			}
		});

	def('cu_scroll', 'confirm',
		'在屏幕某点滚动滚轮（物理像素坐标，逐次审批）。notches 为正表示向上。',
		{
			type: 'object',
			properties: {
				x: { type: 'number' }, y: { type: 'number' },
				notches: { type: 'number', description: '格数，绝对值不超过 ' + CU_LIMITS.maxScrollNotches },
				window: { type: 'string' },
			},
			required: ['x', 'y'],
		},
		async (input) => {
			const g = await geometryOrFail(deps);
			if ('reason' in g) { return g.reason; }
			const fg = checkForeground(fgState(deps), input?.window ? String(input.window) : undefined);
			if (fg.ok === false) { return `[DENIED] ${fg.reason}`; }
			const n = Number(input?.notches ?? 0);
			if (!Number.isFinite(n) || n === 0) { return '[FAIL] notches 必须是非零数字。'; }
			if (Math.abs(n) > CU_LIMITS.maxScrollNotches) { return `[DENIED] |notches| 超过上限 ${CU_LIMITS.maxScrollNotches}。`; }
			// Same guard click uses. Without it `Number(undefined)` is NaN, the wheel event goes
			// nowhere, and we still reported "已滚动 n 格" — a success that did not happen, which
			// is worse than a refusal because it ends the investigation.
			const sx = Number(input.x), sy = Number(input.y);
			if (!Number.isFinite(sx) || !Number.isFinite(sy)) { return '[FAIL] x/y 必须是数字（物理像素）。'; }
			if (!inBounds(sx, sy, g.geom)) {
				const b = g.geom.bounds;
				return `[DENIED] 坐标 (${sx},${sy}) 超出屏幕范围（物理 bounds ${b.width}x${b.height} @ ${b.x},${b.y}）。`;
			}
			if (!deps.inject) { return `[FAIL] ${noBackend(deps)}`; }
			try {
				await deps.inject.scroll({ x: sx, y: sy }, n);
				return `已滚动 ${n} 格。`;
			} catch (e) {
				return `[FAIL] 注入失败：${String((e as Error)?.message ?? e)}`;
			}
		});

	def('cu_key', 'confirm',
		'发送一组按键（逐次审批）。Win+L/Win+D/Ctrl+Alt+Del 等系统级快捷键一律禁止，无法解锁。',
		{ type: 'object', properties: { keys: { type: 'string', description: '如 a / enter / ctrl+s' } }, required: ['keys'] },
		async (input) => {
			const keys = String(input?.keys ?? '');
			const v = checkKeyAllowed(keys);
			if (v.ok === false) { return `[DENIED] ${v.reason}`; }
			if (!deps.inject) { return `[FAIL] ${noBackend(deps)}`; }
			try { await deps.inject.key(keys); return `已发送按键 ${keys}。`; }
			catch (e) { return `[FAIL] 注入失败：${String((e as Error)?.message ?? e)}`; }
		});

	def('cu_type', 'confirm',
		'向当前前台窗口输入一段文字（逐次审批）。走剪贴板粘贴路径而非逐键模拟，减少键鼠注入面；先粘贴再恢复原剪贴板。',
		{ type: 'object', properties: { text: { type: 'string', description: '要输入的文字，上限 ' + CU_LIMITS.maxTypeChars + ' 字' } }, required: ['text'] },
		async (input) => {
			const text = String(input?.text ?? '');
			if (!text) { return '[FAIL] text 为空。'; }
			if (text.length > CU_LIMITS.maxTypeChars) { return `[DENIED] 文本长度 ${text.length} 超过上限 ${CU_LIMITS.maxTypeChars}。`; }
			const fg = checkForeground(fgState(deps));
			if (fg.ok === false) { return `[DENIED] ${fg.reason}`; }
			if (!deps.inject) { return `[FAIL] ${noBackend(deps)}`; }
			try { await deps.inject.pasteText(text); return `已向前台窗口粘贴 ${text.length} 字。`; }
			catch (e) { return `[FAIL] 注入失败：${String((e as Error)?.message ?? e)}`; }
		});

	def('cu_geometry', 'auto',
		'返回屏幕几何：物理像素 bounds、缩放比与 DIP 尺寸，并给出坐标换算规则。',
		{ type: 'object', properties: { dipX: { type: 'number' }, dipY: { type: 'number' } } },
		async (input) => {
			const g = await geometryOrFail(deps);
			if ('reason' in g) { return g.reason; }
			const geo = g.geom;
			let extra = '';
			if (Number.isFinite(Number(input?.dipX)) && Number.isFinite(Number(input?.dipY))) {
				const p = dipToPhysical(Number(input.dipX), Number(input.dipY), geo.scaleFactor);
				extra = `\nDIP(${input.dipX},${input.dipY}) → 物理像素(${p.x},${p.y})`;
			}
			return [
				`物理 bounds：${geo.bounds.width}x${geo.bounds.height} @ ${geo.bounds.x},${geo.bounds.y}`,
				`缩放比：${geo.scaleFactor}x`,
				`DIP 尺寸：${geo.dip.width}x${geo.dip.height}`,
				'注入一律用物理像素；DIP 换算见 dipToPhysical。',
			].join('\n') + extra;
		});
}

async function geometryOrFail(deps: CuDeps): Promise<{ geom: ScreenGeometry } | { reason: string }> {
	const c = await deps.capture();
	if (!c.ok || !c.geometry) { return { reason: `[FAIL] 读取屏幕几何失败：${c.reason ?? '未知'}` }; }
	return { geom: c.geometry };
}

/** Exported for tests: the deny list is part of the contract. */
export { CU_HARD_DENY };