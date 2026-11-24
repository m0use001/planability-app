/* P3 跨端续聊：pickNewestPeerMirror 的语义——按 mtime 取最新、空集给 null。
 * 这是「续聊读到哪份对话」的判据：选错一份 = AI 拿着过期上下文续聊，比没有更糟。
 *
 * Run: npm run test:peersession
 */
const path = require('node:path');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const FT = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'file-tools.js'));

check('空集 → null（工具层如实报「没有镜像」）', FT.pickNewestPeerMirror([]) === null);
const c = [
	{ name: '旧会话__1', rel: 'a', mtime: 100 },
	{ name: '新会话__2', rel: 'b', mtime: 300 },
	{ name: '中间__3', rel: 'c', mtime: 200 },
];
check('取 mtime 最新一份', FT.pickNewestPeerMirror(c)?.rel === 'b', JSON.stringify(FT.pickNewestPeerMirror(c)));
check('mtime 并列时保先出现者（reduce 不换语义，确定性）',
	FT.pickNewestPeerMirror([{ name: 'x', rel: 'x', mtime: 5 }, { name: 'y', rel: 'y', mtime: 5 }])?.rel === 'x');

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
