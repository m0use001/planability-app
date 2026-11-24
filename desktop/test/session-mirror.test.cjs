/* P0-d（双端项目同步）：PC 会话镜像器的契约测试。
 *
 * 镜像是「AI 会话正文跨端互通」的载体，错的最坏形态不是不写，而是写错地方：
 *   - 手机目录是 <标题>__<数字id>，PC 目录必须 __pc<id> 后缀——目录名规则错了就会互踩。
 *   - 标题改过名必须自愈（改名不删内容），否则旧产物文件丢。
 *   - dirName/sessionId 是运行时输入，含分隔符或 .. 必须拒绝（Mimosa 拦下过一版没校验的）。
 *   - 只镜像 user/assistant；空历史不产生内容。
 *
 * Run: npm run test:sessmirror
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const SM = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'session-mirror.js'));

(async () => {
	// ---- 纯函数 ----
	check('目录名：<标题>__pc<id>，非法字符转 _', SM.sessionDirName('a/b:c', 'pc_1') === 'a_b_c__pcpc_1', SM.sessionDirName('a/b:c', 'pc_1'));
	check('目录名：空标题退化「对话」', SM.sessionDirName('  ', 'pc_1') === '对话__pcpc_1');
	check('目录名：标题截 30 字', SM.sessionDirName('x'.repeat(40), 'pc_1').length === 'x'.repeat(30).length + '__pcpc_1'.length);
	check('md：# 标题 + ## 用户/AI，tool/system 不进镜像', (() => {
		const md = SM.mirrorMarkdown('测试', [
			{ role: 'system', content: 'sys' },
			{ role: 'user', content: '你好' },
			{ role: 'tool', content: '结果' },
			{ role: 'assistant', content: '回复' },
		]);
		return md.includes('# 测试') && md.includes('## 用户') && md.includes('你好')
			&& md.includes('## AI') && md.includes('回复') && !md.includes('sys') && !md.includes('结果');
	})());
	check('md：空内容给占位而不是空行', SM.mirrorMarkdown('t', [{ role: 'assistant', content: '' }]).includes('（无文本内容）'));

	// ---- mirrorTo 落盘 ----
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-mirror-'));
	const dn = SM.sessionDirName('高数讨论', 'pc_ab12');
	const r1 = await SM.mirrorTo(root, dn, '# 高数讨论', 'pc_ab12');
	check('落盘成功且路径在 项目数据/当前会话 下',
		r1.ok && r1.abs === path.join(root, '项目数据', '当前会话', dn, '对话.md'), JSON.stringify(r1));
	check('镜像文件内容写进去了', fs.readFileSync(r1.abs, 'utf8') === '# 高数讨论');

	// 标题自愈：改名后再镜像，旧目录整包搬过来（里面的产物文件跟过去）
	fs.writeFileSync(path.join(root, '项目数据', '当前会话', dn, '产物.docx'), 'x');
	const dn2 = SM.sessionDirName('改名后的标题', 'pc_ab12');
	const r2 = await SM.mirrorTo(root, dn2, '# 改名', 'pc_ab12');
	check('标题自愈：旧目录整体改到新名下', r2.ok && !fs.existsSync(path.join(root, '项目数据', '当前会话', dn))
		&& fs.existsSync(path.join(root, '项目数据', '当前会话', dn2, '产物.docx')), JSON.stringify(r2));
	check('自愈后镜像内容已更新', fs.readFileSync(r2.abs, 'utf8') === '# 改名');

	// ---- 拒绝路径：dirName 不可信 ----
	const bad = async (dirName, sid) => {
		const r = await SM.mirrorTo(root, dirName, 'md', sid);
		return !r.ok;
	};
	check('[拒绝] dirName 含 ..', await bad('..\\x__pcsid', 'sid'));
	check('[拒绝] dirName 含分隔符', await bad('a/b__pcsid', 'sid'));
	check('[拒绝] dirName 后缀与 sessionId 不符', await bad('x__pcother', 'sid'));
	check('[拒绝] dirName 只有后缀（空标题位）', await bad('__pcsid', 'sid'));

	fs.rmSync(root, { recursive: true, force: true });
	console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	process.exit(failed === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
