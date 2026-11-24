/* dump v2 migration: the three v45 memory layers sync to the PC without breaking v1 peers.
 *
 * What this guards:
 *   - V1 DUMPS STILL MERGE. A phone that has not been updated keeps exporting schemaVersion 1;
 *     rejecting it would silently stop sync for every un-updated device.
 *   - EVIDENCE IDENTITY IS THE MEMORY NAME, NOT THE ID. mergeDump's rule is "the winner keeps
 *     OUR id", so phone ids are not stable here. Two dumps carrying the same (memory, messageId)
 *     under different ids must fold into ONE row — otherwise every sync duplicates the trail.
 *   - PERSONA IS A SINGLE ROW. Its natural key is the constant 'persona'; a second dump with a
 *     different id but newer body must replace, not append.
 *   - UNKNOWN VERSIONS ARE REFUSED (v3 must throw, not half-merge).
 *
 * Run: npm run test:dumpv2
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond ? '' : extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}

const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));

(async () => {
	const dir = path.join(os.tmpdir(), 'planability-dumpv2-' + process.pid + '-' + Date.now());
	const store = new Store(dir);

	// 0) PC 自己导出的 dump 必须标 v2——COLLECTIONS 已含三个 v2 集合，标 1 会让按版本分派的
	//    消费方（含手机端未来的导入器）漏读三集合（2026-11-09 契约修正）。
	const exported = await store.exportDump();
	check('PC 导出 dump 标 schemaVersion=2（COLLECTIONS 已含 v2 三集合）',
		exported.schemaVersion === 2, `实际 ${exported.schemaVersion}`);
	check('PC 导出 dump 含三个 v2 集合键',
		'memory_scenes' in exported.entity && 'memory_persona' in exported.entity && 'memory_evidence' in exported.entity);

	// 1) v1 仍然能合并（未升级手机）
	const v1 = {
		schemaVersion: 1, exportedAt: 1,
		entity: { memories: [{ id: 'm1', name: 'a', description: '', body: 'b', createdAt: 1, updatedAt: 1 }] },
	};
	await store.mergeDump(v1);
	check('v1 dump 仍被接受', (await store.load('memories')).length === 1);

	// 2) 未知版本必须抛错（不能半合并）
	let threw = false;
	try { await store.mergeDump({ schemaVersion: 3, exportedAt: 1, entity: {} }); } catch { threw = true; }
	check('未知版本被拒绝', threw);

	// 3) v2：三个集合落库；同一 (memory,messageId) 不同 id 只留一行
	await store.mergeDump({
		schemaVersion: 2, exportedAt: 2,
		entity: {
			memory_scenes: [{ id: 'scene_x', name: 'x', summary: 's1', heat: 2, createdAt: 1, updatedAt: 1 }],
			memory_persona: [{ id: 'persona', body: 'p1', version: 1, createdAt: 1, updatedAt: 1 }],
			memory_evidence: [
				{ id: 'a#1', memory: 'a', messageId: 1, kind: 'support', createdAt: 1, updatedAt: 1 },
				{ id: 'a#1-dup', memory: 'a', messageId: 1, kind: 'support', createdAt: 1, updatedAt: 2 },
			],
		},
	});
	check('场景落库', (await store.load('memory_scenes')).length === 1);
	check('画像落库', (await store.load('memory_persona')).length === 1);
	check('证据按 (记忆名,消息id) 折叠成一行', (await store.load('memory_evidence')).length === 1);

	// 4) 第二次同步：id 全变了（PC 保留本地 id），自然键必须仍然命中
	await store.mergeDump({
		schemaVersion: 2, exportedAt: 3,
		entity: {
			memory_scenes: [{ id: 'scene_x2', name: 'x', summary: 's2', heat: 5, createdAt: 1, updatedAt: 3 }],
			memory_persona: [{ id: 'persona2', body: 'p2', version: 2, createdAt: 1, updatedAt: 3 }],
			memory_evidence: [{ id: 'a#1-new', memory: 'a', messageId: 1, kind: 'support', createdAt: 1, updatedAt: 4 }],
		},
	});
	const scenes = await store.load('memory_scenes');
	const persona = await store.load('memory_persona');
	const evidence = await store.load('memory_evidence');
	check('场景不重复且内容更新', scenes.length === 1 && scenes[0].summary === 's2' && scenes[0].id === 'scene_x', JSON.stringify(scenes));
	check('画像始终只有一行且换成新正文', persona.length === 1 && persona[0].body === 'p2', JSON.stringify(persona));
	check('证据不重复，id 保留本地', evidence.length === 1 && evidence[0].id === 'a#1', JSON.stringify(evidence));

	console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	process.exit(failed === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(1); });
