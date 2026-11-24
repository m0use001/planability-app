/* navStack 层级栈语义守卫 —— 直译手机 NavState/NavController（spec §4.1）。
 * 会红的门：popTo 幂等语义 / push 同 key 幂等 / 深链重置 popToRoot——
 * 改坏任何一条（如 popTo 往深跳、push 叠同 key 层）这里红。
 * Run: npm run test:navstack
 */
const path = require('node:path');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const { navReduce, navLevelOf } = require(path.join(__dirname, '..', '.test', 'src', 'renderer', 'src', 'lib', 'navStack.js'));

console.log('== push/pop 基础 ==');
{
	let s = [];
	s = navReduce(s, { type: 'push', pane: { key: 'a', title: 'A' } });
	s = navReduce(s, { type: 'push', pane: { key: 'b', title: 'B' } });
	check('两级入栈', s.length === 2 && s[1].key === 'b', JSON.stringify(s));
	check('level = 1 + 栈深', navLevelOf(s) === 3);
	check('空栈 level=1（根两板）', navLevelOf([]) === 1);
	s = navReduce(s, { type: 'pop' });
	check('pop 退一级', s.length === 1 && s[0].key === 'a');
	s = navReduce(s, { type: 'pop' });
	check('pop 空栈安全', s.length === 0);
}

console.log('== push 同 key 幂等（深链重复触发不叠层，手机 NavPane key 语义）==');
{
	let s = [];
	s = navReduce(s, { type: 'push', pane: { key: 'a', title: 'A' } });
	const s2 = navReduce(s, { type: 'push', pane: { key: 'a', title: 'A' } });
	check('同 key 顶层 push 不叠层', s2.length === 1, JSON.stringify(s2));
	const s3 = navReduce(s, { type: 'push', pane: { key: 'b', title: 'B' } });
	check('不同 key 正常叠层', s3.length === 2);
}

console.log('== popTo 跳层（瞬删语义；只往浅跳）==');
{
	let s = [];
	for (const k of ['a', 'b', 'c']) { s = navReduce(s, { type: 'push', pane: { key: k, title: k } }); }
	check('三级栈', s.length === 3);
	s = navReduce(s, { type: 'popTo', depth: 1 });
	check('popTo(1) 保留 1 层', s.length === 1 && s[0].key === 'a', JSON.stringify(s));
	const s2 = navReduce(s, { type: 'popTo', depth: 5 });
	check('popTo 超深不动（只往浅跳）', s2.length === 1);
	const s3 = navReduce(s, { type: 'popTo', depth: 0 });
	check('popTo(0) = 回根', s3.length === 0);
}

console.log('== popToRoot 清空（切 tab/深链重置，瞬删）==');
{
	let s = [];
	for (const k of ['a', 'b']) { s = navReduce(s, { type: 'push', pane: { key: k, title: k } }); }
	s = navReduce(s, { type: 'popToRoot' });
	check('popToRoot 清空', s.length === 0);
}

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
