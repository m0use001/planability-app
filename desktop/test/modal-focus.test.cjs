/* Modal 焦点圈定的契约测试（源码断言，风格同 open-gui.test.cjs）。
 *
 * 断的是**接线**而不是像素：焦点循环的真实手感归视觉/键盘验收。但这条线有三处
 * 在别处看不出来的“悄悄坏”：
 *
 *   1) Tab 只在首尾元素拦边缘 —— 背景可聚焦元素与模态在 DOM 上交错（Modal 渲染在
 *      调用方所在的位置，不在什么 portal 里），中段默认 Tab 一样漏出去。必须全拦截。
 *   2) 初始焦点/焦点还原挂在无依赖数组的效果里 —— 每次渲染重挂会把 cleanup（还原焦点）
 *      在每次输入后跑一遍，表单打着打着字焦点飞回 opener。
 *   3) 还原目标用闭包变量 —— StrictMode 开发态挂载→卸载→再挂载后，第二轮捕获到的
 *      是模态内元素，还原等于没还原。
 *
 * Run: npm run test:modalfocus
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-modalfocus.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ui = fs.readFileSync(process.env.UI_TS_PATH || path.join(__dirname, '..', 'src', 'renderer', 'src', 'components', 'ui.tsx'), 'utf8');
const modal = ui.slice(ui.indexOf('export function Modal'));

check('面板本身可聚焦（tabIndex=-1 兜底，键盘事件有落点）',
	/tabIndex=\{-1\}/.test(modal) && /ref=\{panelRef\}/.test(modal),
	'没有 ref+tabIndex 的面板，无 focusable 的模态里 Tab 会落空');
check('打开时聚焦首个可聚焦元素，缺省聚焦面板',
	/\(focusables\(panel\)\[0\] \?\? panel\)\.focus\(\)/.test(modal),
	'初始焦点逻辑不在');
check('初始焦点/还原效果只挂载一次（空依赖数组）',
	/restoreTo\.isConnected[\s\S]{0,80}\}, \[\]\);/.test(modal),
	'焦点效果若每次渲染重挂，输入中途会把焦点抢回 opener');
check('Tab 全拦截（preventDefault 在 Tab 分支，先于任何循环判断）',
	/if \(e\.key !== 'Tab'\)[\s\S]{0,600}?e\.preventDefault\(\);/.test(modal),
	'只拦首尾边缘会漏：中段默认 Tab 会跳到背景元素');
check('循环取模回绕（首尾相接）',
	/% list\.length/.test(modal) && /e\.shiftKey \? -1 : 1/.test(modal),
	'没有取模回绕就不是圈');
check('焦点逃逸后夺回（idx === -1 分支）',
	/idx === -1[\s\S]{0,120}?e\.shiftKey \? list\[list\.length - 1\] : list\[0\]/.test(modal),
	'焦点已在模态外时 Tab 没有被拉回来');
check('还原目标存 ref，首轮 opener 不被 StrictMode 二次挂载顶掉',
	/restoreRef\.current === null/.test(modal) && /restoreRef\.current = active;/.test(modal),
	'闭包变量在挂载→卸载→再挂载后会捕获到模态内元素');
check('还原前判 isConnected（opener 可能已随状态消失）',
	/restoreTo\.isConnected/.test(modal),
	'还原到已卸载元素会静默失败甚至报错');
check('dirty 守卫与 Esc 原语义未动（requestClose + confirm）',
	/window\.confirm\('有未保存的修改/.test(modal) && /e\.key === 'Escape'/.test(modal),
	'dirty 守卫被焦点改动挤掉了');
check('a11y 属性仍在（role=dialog + aria-modal）',
	/role="dialog"/.test(modal) && /aria-modal="true"/.test(modal),
	'a11y 基础属性丢了');
check('focusables 排除 disabled 与 tabindex=-1',
	/:not\(\[disabled\]\)/.test(ui) && /\[tabindex\]:not\(\[tabindex="-1"\]\)/.test(ui),
	'选择器把不可聚焦元素算进圈里，循环会停在死元素上');

out.push(failed === 0 ? '\nALL PASS' : '\n' + failed + ' FAILED');
flush();
process.exit(failed === 0 ? 0 : 1);
