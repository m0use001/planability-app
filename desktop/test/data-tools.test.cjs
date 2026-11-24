/* P5 read-only data exit: the MCP data tools must answer from the JSON store, never invent data.
 *
 * What this guards:
 *   - SEARCH IS BILINGUAL: CJK is matched by bigrams (FTS-style tokenization), ASCII by words —
 *     a caller searching "考试" or "examples" must both work.
 *   - A CORRUPT FILE IS A FAILURE, NOT AN EMPTY LIBRARY: the store's own rule. A tool that
 *     answered "no memories" here would send the caller away believing the library is empty.
 *   - THE MODULE HAS NO WRITE PATH: asserted at source level; that is what makes "read-only" a
 *     property of the code rather than a promise in a doc.
 *   - --store IS OPTIONAL: the stdio server only advertises these tools when pointed at a dir.
 *
 * Run: npm run test:datatools
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond ? '' : extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const { createDataTools } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'ai', 'data-tools.js'));

const dir = path.join(os.tmpdir(), 'planability-data-tools-' + process.pid + '-' + Date.now());
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, 'memories.json'), JSON.stringify([
	{ id: 'm1', name: '语言偏好', description: '回答语言', body: '以后回答都用中文，不要用英文和表格', type: 'feedback', updatedAt: 3000 },
	{ id: 'm2', name: '考试信息', description: 'C++ 期末', body: 'C++ 期末考试在 1 月 5 日，考场 F3a-309', type: 'reference', updatedAt: 2000 },
	{ id: 'm3', name: 'note-en', description: 'english note', body: 'prefers examples first', type: 'project', updatedAt: 1000 },
]));
fs.writeFileSync(path.join(dir, 'schedules.json'), JSON.stringify([{ id: 's1', title: '课' }]));
// dump v2 的三个集合
fs.writeFileSync(path.join(dir, 'memory_scenes.json'), JSON.stringify([
	{ id: 'scene_学习规划', name: '学习规划', summary: 'C++ 复习', heat: 3 },
	{ id: 'scene_作息', name: '作息', summary: '早起背单词', heat: 1 },
]));
fs.writeFileSync(path.join(dir, 'memory_persona.json'), JSON.stringify([
	{ id: 'persona', body: '## 基础锚点\n计算机大一\n## 交互协议\n只用中文', version: 2, updatedAt: 4000 },
]));
fs.writeFileSync(path.join(dir, 'memory_evidence.json'), JSON.stringify([
	{ id: '语言偏好#3', memory: '语言偏好', messageId: 3, kind: 'support' },
	{ id: '考试信息#5', memory: '考试信息', messageId: 5, kind: 'support' },
	{ id: '考试信息#7', memory: '考试信息', messageId: 7, kind: 'support' },
]));

(async () => {
	const tools = createDataTools(dir);

	check('七个只读工具都在', tools.tools.length === 7 &&
		tools.tools.map(t => t.name).join(',') === [
			'planability_data_memory_search', 'planability_data_memory_get',
			'planability_data_memory_list', 'planability_data_counts',
			'planability_data_scene_list', 'planability_data_persona_get',
			'planability_data_evidence_of',
		].join(','));

	const zh = await tools.call('planability_data_memory_search', { query: '中文' });
	check('中文检索命中「语言偏好」', zh.ok && JSON.parse(zh.text).hits[0].name === '语言偏好', zh.text);
	const zh2 = await tools.call('planability_data_memory_search', { query: '考场' });
	check('中文检索命中「考试信息」', zh2.ok && JSON.parse(zh2.text).hits[0].name === '考试信息', zh2.text);
	const en = await tools.call('planability_data_memory_search', { query: 'examples' });
	check('英文检索命中 note-en', en.ok && JSON.parse(en.text).hits[0].name === 'note-en', en.text);
	const empty = await tools.call('planability_data_memory_search', { query: '完全不存在的东西' });
	check('无命中返回空列表而不是报错', empty.ok && JSON.parse(empty.text).count === 0, empty.text);

	const got = await tools.call('planability_data_memory_get', { name: '语言偏好' });
	check('get 返回全文', got.ok && JSON.parse(got.text).body.includes('不要用英文和表格'), got.text);
	const miss = await tools.call('planability_data_memory_get', { name: '不存在' });
	check('get 未命中是失败（不是空对象）', !miss.ok, miss.text);

	const listed = await tools.call('planability_data_memory_list', { limit: 2 });
	const items = JSON.parse(listed.text).items;
	check('list 按 updatedAt 倒序且受 limit 约束',
		items.length === 2 && items[0].name === '语言偏好' && items[1].name === '考试信息', listed.text);
	const onlyRef = await tools.call('planability_data_memory_list', { type: 'reference' });
	check('list 类型过滤生效', onlyRef.ok && JSON.parse(onlyRef.text).items.every(i => i.type === 'reference'), onlyRef.text);

	const counts = JSON.parse((await tools.call('planability_data_counts', {})).text);
	check('counts 覆盖七集合且数字正确',
		counts.counts.memories === 3 && counts.counts.schedules === 1 && counts.counts.todos === 0, JSON.stringify(counts));
	check('counts 也覆盖 v2 三集合',
		counts.counts.memory_scenes === 2 && counts.counts.memory_persona === 1 && counts.counts.memory_evidence === 3,
		JSON.stringify(counts));

	// dump v2：场景 / 画像 / 证据
	const scenes = await tools.call('planability_data_scene_list', {});
	const sceneItems = JSON.parse(scenes.text).items;
	check('场景按 heat 倒序', scenes.ok && sceneItems[0].name === '学习规划' && sceneItems[1].name === '作息', scenes.text);
	const persona = await tools.call('planability_data_persona_get', {});
	check('画像返回全文四段', persona.ok && JSON.parse(persona.text).body.includes('交互协议'), persona.text);
	const ev = await tools.call('planability_data_evidence_of', { memory: '考试信息' });
	const evRows = JSON.parse(ev.text).evidence;
	check('证据按记忆名查、按消息 id 升序', ev.ok && evRows.length === 2 && evRows[0].messageId === 5 && evRows[1].messageId === 7, ev.text);
	const evNone = await tools.call('planability_data_evidence_of', { memory: '没有这条' });
	check('没有证据时返回空而不是报错', evNone.ok && JSON.parse(evNone.text).count === 0, evNone.text);

	// 损坏文件：必须是失败，且原件被隔离（不是被下一写覆盖）
	const badDir = path.join(os.tmpdir(), 'planability-data-tools-bad-' + Date.now());
	fs.mkdirSync(badDir, { recursive: true });
	fs.writeFileSync(path.join(badDir, 'memories.json'), '{not json');
	const bad = createDataTools(badDir);
	const r = await bad.call('planability_data_memory_search', { query: '中文' });
	check('损坏文件 → ok=false（不谎报空库）', !r.ok && r.text.includes('读取失败'), r.text);
	const leftovers = fs.readdirSync(badDir).filter(f => f.includes('.corrupt-'));
	check('损坏原件被隔离保留', leftovers.length === 1, fs.readdirSync(badDir).join(','));

	// 没有画像文件时，persona_get 必须是失败（诚实：还没生成过）
	const emptyDir = path.join(os.tmpdir(), 'planability-data-tools-empty-' + Date.now());
	fs.mkdirSync(emptyDir, { recursive: true });
	const emptyTools = createDataTools(emptyDir);
	const noPersona = await emptyTools.call('planability_data_persona_get', {});
	check('没有画像时 ok=false（不是空对象）', !noPersona.ok && noPersona.text.includes('还没有生成过'), noPersona.text);

	// 只读是代码属性：源码里不许有写库调用
	const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'ai', 'data-tools.ts'), 'utf8');
	const code = src.split('\n').filter(l => !l.trim().startsWith('*') && !l.trim().startsWith('//')).join('\n');
	check('数据工具模块没有写路径', !/writeJsonFile|\.upsert\(|fs\.write/.test(code));

	// stdio 入口：--store 是可选参数（不传就不注册数据工具）
	const serverSrc = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'ai', 'mcp-server.ts'), 'utf8');
	check('stdio 入口通过 --store 挂载数据工具', serverSrc.includes("arg('--store')") && serverSrc.includes('createDataTools'));
	check('stdio 入口按前缀路由到数据工具', serverSrc.includes("name.startsWith('planability_data_')"));

	console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	process.exit(failed === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
