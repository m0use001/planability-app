/* 「完整模式」按钮的契约测试。
 *
 * 这里断言的是**接线**，不是像素：按钮点了之后能不能看见东西，归 pc-a 的视觉验收。
 * 但这条线有三处在别处看不出来的“悄悄坏”：
 *
 *   1) 有人把开窗改成 shell.openExternal —— 代码更短、看起来一样，但原版 GUI 的每个 REST
 *      调用都要 X-API-Key，而它自己的页面里没有 key（实测页面搜不到 apiKey，且不带头的
 *      请求会被内核回 403 CSRF Error）。丢给系统浏览器 = 停在要 key 的提示上。
 *   2) 注入头的 filter 写宽了 —— 同一个 session 里任何页面都会带上这个 key。
 *   3) 内核没起时不开门 —— 窗口会打开一个连不上的地址，用户以为完整模式坏了。
 *
 * 所以断言盯的是这三件事，而不是“有个按钮”。
 *
 * Run: npm run test:opengui
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-opengui.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const root = path.join(__dirname, '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

const ipcTs = read(path.join('shared', 'ipc.ts'));
const preloadTs = read(path.join('src', 'preload', 'index.ts'));
const mainTs = read(path.join('src', 'main', 'index.ts'));
const syncTs = read(path.join('src', 'main', 'syncthing.ts'));
const pageTs = read(path.join('src', 'renderer', 'src', 'pages', 'Sync.tsx'));

// ---------- 1. 契约三处一致 ----------
check('shared/ipc.ts: IPC 表里有 syncOpenGui 通道',
	/syncOpenGui:\s*'sync:openGui'/.test(ipcTs),
	'缺通道常量');
check('shared/ipc.ts: Api 接口有 syncOpenGui(): Promise<string>',
	/syncOpenGui\(\):\s*Promise<string>/.test(ipcTs),
	'缺接口方法（preload 和渲染层靠它对类型）');
check('preload: syncOpenGui 走同一通道',
	/syncOpenGui:\s*\(\)\s*=>\s*ipcRenderer\.invoke\(IPC\.syncOpenGui\)/.test(preloadTs),
	'preload 没暴露或通道名不一致');
check('main: 有 IPC.syncOpenGui 的处理器',
	/ipcMain\.handle\(IPC\.syncOpenGui/.test(mainTs),
	'没有处理器 = 点了没反应');

// ---------- 2. 必须是主进程开窗 + 注入头，不是丢给系统浏览器 ----------
const handlerStart = mainTs.indexOf('ipcMain.handle(IPC.syncOpenGui');
const handler = handlerStart >= 0 ? mainTs.slice(handlerStart, handlerStart + 4000) : '';
check('main: 处理器用的是 BrowserWindow（不是 shell.openExternal）',
	/new BrowserWindow\(/.test(handler),
	'开窗方式变了');
check('main: 没有把完整模式交给系统浏览器',
	!/shell\.openExternal/.test(handler),
	'交给系统浏览器 = 原版 GUI 会停在要 API key 的提示上');
check('main: 用独立 session（不污染应用渲染层）',
	/session\.fromPartition\('persist:syncthing-gui'\)/.test(handler),
	'用默认 session 会让 key 注入作用到应用自己的页面');
check('main: 在发请求前注入 X-API-Key',
	/onBeforeSendHeaders/.test(handler) && /requestHeaders\['X-API-Key'\]/.test(handler),
	'不注入头 → 内核回 403 CSRF Error');
check('main: 注入范围只限内核地址',
	/urls:\s*\[GUI_GLOB\s*\+\s*'\*'\]/.test(handler) && /GUI_GLOB = 'http:\/\/' \+ GUI_HOST/.test(handler),
	'filter 写宽了，同 session 的其它页面也会带 key');
check('main: 窗口关闭时撤掉注入',
	/onBeforeSendHeaders\(filter,\s*null\)/.test(handler),
	'不撤掉的话，这个持久分区以后开的页面都会带 key');
check('main: 原版 GUI 窗口不挂 preload / 不开 node / 开沙箱',
	/sandbox:\s*true/.test(handler) && /nodeIntegration:\s*false/.test(handler) && !/preload:/.test(handler),
	'第三方页面不该拿到应用能力');

// ---------- 3. 内核没起时不开门 ----------
check('main: 内核没运行就直接报错，不开空窗',
	/if\s*\(!st\.running\)/.test(handler) && /throw new Error/.test(handler),
	'开一个连不上的窗口，用户会以为功能坏了');

// ---------- 4. key 从 engine 拿，且不经过 preload ----------
check('syncthing.ts: guiEndpoint() 返回 url + apiKey',
	/guiEndpoint\(\):\s*\{\s*url:\s*string;\s*apiKey:\s*string\s*\}/.test(syncTs),
	'拿不到 key，注入无从谈起');
check('key 没有下放给渲染层（preload 里不出现 apiKey/X-API-Key）',
	!/apiKey|X-API-Key/.test(preloadTs),
	'key 一旦进 preload 就等于进了渲染层，任何 XSS 都能读走它');

// ---------- 5. 界面：按钮在、点了发 IPC、内核没起时禁用 ----------
check('Sync.tsx: 有「完整模式」按钮',
	/完整模式/.test(pageTs),
	'用户要求的入口不存在');
check('Sync.tsx: 按钮调 window.planability.syncOpenGui()',
	/window\.planability\.syncOpenGui\(\)/.test(pageTs),
	'按钮没接到 IPC');
check('Sync.tsx: 按钮在内核未运行时禁用（disabled={!running}）',
	/disabled=\{!running\}/.test(pageTs),
	'内核没起时仍可点，只会得到一条错误提示');

out.push(failed === 0 ? '\nALL PASS' : '\n' + failed + ' FAILED');
flush();
process.exit(failed === 0 ? 0 : 1);
