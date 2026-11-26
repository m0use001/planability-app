/* Generic launcher: run a test file under the Electron binary with ELECTRON_RUN_AS_NODE=1,
 * then print the result file the test writes to %TEMP%.
 *
 * Why this exists (see 开发备忘/内部踩坑文档):
 *  1. better-sqlite3 is a rebuilt native addon — suites touching AiDb must run under
 *     Electron; plain node segfaults on the pre-rebuild binary.
 *  2. Without ELECTRON_RUN_AS_NODE, Electron treats the .cjs as a main process and never
 *     exits (the test hangs). `VAR=val cmd` is not valid in Windows cmd.exe, hence a JS
 *     launcher rather than a shell prefix / cross-env dependency.
 *  3. crashpad noise makes stdout unreliable, so tests write to a file and we print that.
 *
 * Usage: node test\run-electron.cjs <test-file-relative-to-desktop> <result-file-name>
 *   e.g. node test\run-electron.cjs test/ai-db.test.cjs pcb-ai-db-test.txt
 */

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const [, , testFile, logName] = process.argv;
if (!testFile) {
	console.error('用法: node test\\run-electron.cjs <test-file> [result-file-name]');
	process.exit(2);
}

const electron = path.join(__dirname, '..', 'node_modules', 'electron', 'dist', 'electron.exe');
if (!fs.existsSync(electron)) {
	console.error('找不到 electron.exe：' + electron);
	process.exit(2);
}

const log = path.join(os.tmpdir(), logName || 'pcb-electron-test.txt');
try { fs.unlinkSync(log); } catch { /* first run */ }

const r = spawnSync(electron, [path.join(__dirname, '..', testFile)], {
	env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
	stdio: ['ignore', 'inherit', 'ignore'], // crashpad writes to stderr; ignore it
	timeout: 180_000,
});

if (fs.existsSync(log)) {
	console.log(fs.readFileSync(log, 'utf8'));
} else {
	console.error('测试没有产出结果文件（可能在加载原生模块时就崩了）。');
	console.error('提示：better-sqlite3 需为 Electron rebuild（见 README 开发环境节）。');
}
process.exit(fs.existsSync(log) && r.status === 0 ? 0 : 1);