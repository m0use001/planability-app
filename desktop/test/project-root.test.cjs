/* P0-c（双端项目同步）：project-root 解析链的契约测试。
 *
 * 文件工具按会话项目切根的前提是「项目 → PC 本地目录」这一步不能指错地方：
 *   1) dump 里的 sync.rel 是不可信输入 —— 含 `..` / 绝对路径形态必须拒绝，落到下一档。
 *      放过去的话 confine 根会被同步来的数据指到共享夹外面。
 *   2) folderId 在本机 Syncthing 配置里找不到 → 必须回退，不能报错（Syncthing 没跑是常态）。
 *   3) 解析链顺序：sync 命中 > PC 本地绑定 > workspaceRoot/<项目名> > null。
 *   4) 项目名做缺省目录名时必须 sanitize（C++编程 不能变成半截路径）。
 *
 * Run: npm run test:projroot
 */
const path = require('node:path');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const PR = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'project-root.js'));

(async () => {
	// ---- 纯函数：sanitizeSegment / cleanRel ----
	check('sanitize：砍掉路径分隔符与非法字符', PR.sanitizeSegment('a/b\\c:d') === 'a_b_c_d', PR.sanitizeSegment('a/b\\c:d'));
	check('sanitize：空白串给占位名', PR.sanitizeSegment('   ') === '未命名项目');
	check('cleanRel：接受普通子路径', PR.cleanRel('编程/C++编程') === path.join('编程', 'C++编程'), String(PR.cleanRel('编程/C++编程')));
	check('cleanRel：拒绝 ..', PR.cleanRel('../escape') === null);
	check('cleanRel：拒绝绝对路径', PR.cleanRel('D:\\x') === null && PR.cleanRel('/x') === null);
	check('cleanRel：拒绝空串', PR.cleanRel('') === null && PR.cleanRel('  ') === null);

	// ---- 解析链 ----
	const mk = (over = {}) => ({
		loadProjects: async () => over.projects ?? [],
		getSyncFolders: async () => over.folders ?? [],
		workspaceRoot: () => over.ws ?? '',
		readBinding: async () => over.binding,
		...over.deps,
	});

	// 1. sync 命中：folderId 在本机 Syncthing 配置里能找到
	const r1 = await PR.resolveProjectRoot('p1', mk({
		projects: [{ id: 'p1', name: 'C++编程', sync: { folderId: 'plan-x', rel: '编程/C++编程' } }],
		folders: [{ id: 'plan-x', path: 'D:\\大学文件' }],
	}));
	check('sync 命中：folderId 映射本机路径 + rel 拼接',
		r1 && r1.source === 'sync' && r1.root === path.resolve('D:\\大学文件', '编程', 'C++编程'), JSON.stringify(r1));

	// 2. folderId 找不到 → 回退绑定
	const r2 = await PR.resolveProjectRoot('p1', mk({
		projects: [{ id: 'p1', name: 'x', sync: { folderId: 'missing', rel: 'a' } }],
		binding: 'E:\\手动绑定',
		ws: 'W:\\默认',
	}));
	check('folderId 不在本机配置 → 回退 PC 本地绑定',
		r2 && r2.source === 'binding' && r2.root === path.resolve('E:\\手动绑定'), JSON.stringify(r2));

	// 3. rel 带 .. → 拒绝该档，回退绑定（防同步数据把 confine 根指出去）
	const r3 = await PR.resolveProjectRoot('p1', mk({
		projects: [{ id: 'p1', name: 'x', sync: { folderId: 'plan-x', rel: '../escape' } }],
		folders: [{ id: 'plan-x', path: 'D:\\大学文件' }],
		binding: 'E:\\手动绑定',
	}));
	check('sync.rel 含 .. → 拒绝并回退（不得指到共享夹外）',
		r3 && r3.source === 'binding', JSON.stringify(r3));

	// 4. 无 sync 无绑定 → 缺省 workspaceRoot/<sanitize(项目名)>
	const r4 = await PR.resolveProjectRoot('p1', mk({
		projects: [{ id: 'p1', name: 'a/b:c' }],
		ws: 'W:\\默认',
	}));
	check('缺省档：workspaceRoot/<sanitize(项目名)>',
		r4 && r4.source === 'default' && r4.root === path.join(path.resolve('W:\\默认'), 'a_b_c'), JSON.stringify(r4));

	// 5. workspaceRoot 未配置且无 sync/绑定 → null
	const r5 = await PR.resolveProjectRoot('p1', mk({ projects: [{ id: 'p1', name: 'x' }] }));
	check('全落空 → null（调用方回退整根旧行为）', r5 === null, JSON.stringify(r5));

	// 6. 项目不存在 → null
	const r6 = await PR.resolveProjectRoot('nope', mk({ ws: 'W:\\默认' }));
	check('项目不存在 → null', r6 === null, JSON.stringify(r6));

	console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	process.exit(failed === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
