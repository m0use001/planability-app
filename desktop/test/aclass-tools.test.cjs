/* A-class tool tests — the eleven tools that expose to the model what the PC already did.
 *
 * The interesting assertions are the ones about SAFETY and SEMANTICS, not the happy path:
 *
 *   - `note_append` APPENDS. Overwriting would be the same data loss as a bad merge, and it is one
 *     `body: text` typo away.
 *   - `schedule_update` / `assistant_edit` patch ONLY the fields passed. "Change the title" must
 *     not clear the time — that is a REPLACE, and it reads as the tool working correctly.
 *   - `assistant_edit` cannot touch `key` or `builtin`. `key` is what config points at, and
 *     `builtin` decides whether a restart re-seeds the persona; letting the model set either is
 *     letting it quietly take over — or permanently wreck — a built-in helper.
 *   - every write goes through `onAssistantsChanged`, because index.ts owns the persona cache and
 *     a write the cache misses is a write the user does not see.
 *   - an unknown id answers "not found" rather than creating a duplicate by accident.
 *
 * Run: npm run test:aclass
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-aclass.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { registerPlanabilityTools } = require(path.join(ai, 'tools.js'));
const { Store } = require(path.join(__dirname, '..', '.test', 'src', 'main', 'store', 'store.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-aclass-'));
const store = new Store(dir);
const undo = { push: async () => { }, undoLast: async () => null };
const memLog = { append: async () => ({ seq: 1 }), project: async () => ({ applied: 0, rejected: 0 }) };

let refreshCalls = 0;
const extend = {
	onAssistantsChanged: async () => { refreshCalls++; },
	listSkills: async () => [
		{ key: 'sum', name: '总结', description: '总结长文', origin: 'builtin' },
		{ key: 'mine', name: '我的技能', description: '', origin: 'user' },
	],
	mcp: {
		list: async () => [
			{ id: 'm1', name: '搜索', url: 'https://a.example', enabled: true },
			{ id: 'm2', name: '遍历', url: 'https://b.example', enabled: false },
		],
		setEnabled: (id, on) => { mcpState[id] = on; },
	},
};
let mcpState = { m1: true, m2: false };

const reg = registerPlanabilityTools(store, undo, {
	memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
}, null, extend);

const call = async (name, input) => reg.get(name).invoke(input);
const NAMES = ['note_read', 'note_append', 'schedule_update', 'assistant_list', 'assistant_create',
	'assistant_edit', 'assistant_delete', 'skill_list', 'mcp_list', 'mcp_connect', 'mcp_disconnect'];

(async () => {
	// ---- 0. all eleven exist, and each sits in the domain it belongs to ----
	{
		for (const n of NAMES) {
			check('registered: ' + n + ' is registered', typeof reg.get(n)?.invoke === 'function');
		}
		const expected = {
			note_read: 'note', note_append: 'note', schedule_update: 'schedule',
			assistant_list: 'extend', assistant_create: 'extend', assistant_edit: 'extend',
			assistant_delete: 'extend', skill_list: 'extend', mcp_list: 'extend',
			mcp_connect: 'extend', mcp_disconnect: 'extend',
		};
		for (const n of NAMES) {
			check('domain: ' + n + ' belongs to ' + expected[n], reg.get(n)?.domain === expected[n]);
		}
		// A-class writes are confirm/danger, never auto — each of them changes user data.
		for (const n of ['note_append', 'schedule_update', 'assistant_create', 'assistant_edit']) {
			check('risk: ' + n + ' asks before writing', reg.get(n)?.risk === 'confirm');
		}
		check('risk: assistant_delete is danger (deleting a persona is destructive)',
			reg.get('assistant_delete')?.risk === 'danger');

		// `now` (phone parity): local date + weekday, zero params, never asks.
		check('now: registered as auto with zero parameters', reg.get('now')?.risk === 'auto'
			&& Object.keys(reg.get('now')?.inputSchema?.properties ?? {}).length === 0);
		const nowOut = await call('now', {});
		check('now: answers local ISO day + weekday', /^\d{4}-\d{2}-\d{2} 周[一二三四五六日]$/.test(nowOut));
		{
			const d = new Date();
			const expect = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
			check('now: matches the LOCAL calendar day (UTC would drift before 08:00)', nowOut.startsWith(expect));
		}
		// todo_complete is a write — same tier as todo_create (phone parity: confirm).
		check('risk: todo_complete asks before writing', reg.get('todo_complete')?.risk === 'confirm');
	}

	// ---- 1. note_read ----
	{
		const n = await store.upsert('notes', { id: '', createdAt: 0, updatedAt: 0, title: '会议', body: '第一行\n第二行' });
		const got = await call('note_read', { id: n.id });
		check('note_read: returns title AND body', got.includes('会议') && got.includes('第二行'));
		const missing = await call('note_read', { id: 'nope' });
		check('note_read: unknown id says so instead of inventing content',
			missing.includes('未找到') && !missing.includes('会议'));
	}

	// ---- 2. note_append APPENDS (the whole point) ----
	{
		const n = await store.upsert('notes', { id: '', createdAt: 0, updatedAt: 0, title: '清单', body: '原有正文' });
		await call('note_append', { id: n.id, text: '追加的一段' });
		const after = (await store.live('notes')).find(x => x.id === n.id);
		check('note_append: original text survives',
			after.body.includes('原有正文'));
		check('note_append: new text is there too', after.body.includes('追加的一段'));
		check('note_append: separated by a blank line, not concatenated',
			after.body === '原有正文\n\n追加的一段');

		const noop = await call('note_append', { id: n.id, text: '   ' });
		check('note_append: whitespace-only append is refused, not appended',
			noop.includes('不做任何事'));
		const unchanged = (await store.live('notes')).find(x => x.id === n.id);
		check('note_append: and the note is untouched', unchanged.body === '原有正文\n\n追加的一段');

		const beforeCount = (await store.live('notes')).length;
		const gone = await call('note_append', { id: 'nope', text: 'x' });
		check('note_append: unknown id does not create a new note',
			gone.includes('未找到') && (await store.live('notes')).length === beforeCount);
	}

	// ---- 3. schedule_update patches ONLY the passed fields ----
	{
		const s0 = await store.upsert('schedules', {
			id: '', createdAt: 0, updatedAt: 0,
			title: '旧标题', date: '2026-10-11', startIso: '2026-10-11T09:00', endIso: '2026-10-11T10:00',
		});
		const msg = await call('schedule_update', { id: s0.id, title: '新标题' });
		const s1 = (await store.live('schedules')).find(x => x.id === s0.id);
		check('schedule_update: the passed field changed', s1.title === '新标题');
		check('schedule_update: the time is NOT cleared by a title-only edit',
			s1.startIso === '2026-10-11T09:00' && s1.endIso === '2026-10-11T10:00' && s1.date === '2026-10-11');
		check('schedule_update: the reply names what changed',
			msg.includes('title') && !msg.includes('startIso'));

		const none = await call('schedule_update', { id: s0.id });
		check('schedule_update: an empty patch is refused rather than being a no-op write',
			none.includes('没有给出任何'));
		check('schedule_update: unknown id says so', (await call('schedule_update', { id: 'x', title: 'y' })).includes('未找到'));
	}

	// ---- 4. assistants ----
	{
		// 种子：一个内置 + 一个用户自建
		const builtin = await store.upsert('assistants', {
			id: '', createdAt: 0, updatedAt: 0, key: 'helper', name: '内置助手',
			color: '#fff', systemPrompt: '内置提示', builtin: true,
		});
		const mine = await store.upsert('assistants', {
			id: '', createdAt: 0, updatedAt: 0, key: 'mine', name: '我的',
			color: '#000', systemPrompt: '我的提示',
		});

		const list = await call('assistant_list', {});
		check('assistant_list: names both, and marks the builtin one',
			list.includes('内置助手') && list.includes('我的') && list.includes('内置'));

		refreshCalls = 0;
		const created = await call('assistant_create', {
			name: '新的', systemPrompt: '新的提示', key: '', temperature: 0.3,
		});
		const rows = await store.live('assistants');
		const made = rows.find(a => a.name === '新的');
		check('assistant_create: created with a generated key when none given',
			made && made.key.startsWith('asst_'));
		check('assistant_create: kept the temperature', made?.temperature === 0.3);
		check('assistant_create: is NOT builtin (a model-created persona must not survive re-seeding logic)',
			made?.builtin !== true);
		check('assistant_create: refreshed the persona cache', refreshCalls === 1);
		check('assistant_create: tells the user where to pick it',
			created.includes('设置里选它'));

		const bad = await call('assistant_create', { name: '', systemPrompt: 'x' });
		check('assistant_create: refuses an empty name', bad.includes('不能为空'));

		// 关键：key 与 builtin 不可改
		refreshCalls = 0;
		await call('assistant_edit', { id: builtin.id, systemPrompt: '改过的提示' });
		const afterB = (await store.live('assistants')).find(a => a.id === builtin.id);
		check('assistant_edit: systemPrompt changed', afterB.systemPrompt === '改过的提示');
		check('assistant_edit: key is unchanged (config points at it)',
			afterB.key === 'helper');
		check('assistant_edit: builtin is unchanged (it decides re-seeding on restart)',
			afterB.builtin === true);
		check('assistant_edit: refreshed the cache', refreshCalls === 1);

		// 即使模型试图传 key/builtin，也不该生效
		await call('assistant_edit', { id: mine.id, key: 'hijacked', builtin: true, systemPrompt: 'p2' });
		const afterM = (await store.live('assistants')).find(a => a.id === mine.id);
		check('assistant_edit: a passed-in key is IGNORED, not applied',
			afterM.key === 'mine');
		check('assistant_edit: a passed-in builtin is IGNORED too', afterM.builtin !== true);
		check('assistant_edit: but the legitimate field still went through',
			afterM.systemPrompt === 'p2');

		const empty = await call('assistant_edit', { id: mine.id });
		check('assistant_edit: an empty patch is refused', empty.includes('没有给出任何'));

		refreshCalls = 0;
		const del = await call('assistant_delete', { id: mine.id });
		check('assistant_delete: reports done', del.includes('已删除'));
		check('assistant_delete: is a soft delete (recoverable, like every other write)',
			(await store.live('assistants')).every(a => a.id !== mine.id));
		check('assistant_delete: refreshed the cache', refreshCalls === 1);
		check('assistant_delete: unknown id says so',
			(await call('assistant_delete', { id: 'nope' })).includes('未找到'));
	}

	// ---- 5. skills / mcp ----
	{
		const skills = await call('skill_list', {});
		check('skill_list: shows every skill with its origin',
			skills.includes('总结') && skills.includes('builtin') && skills.includes('user'));

		const servers = await call('mcp_list', {});
		check('mcp_list: shows each server with its enabled state',
			servers.includes('搜索') && servers.includes('已启用') && servers.includes('已停用'));

		await call('mcp_connect', { id: 'm2' });
		check('mcp_connect: enables it', mcpState.m2 === true);
		check('mcp_connect: unknown id says so rather than enabling nothing',
			(await call('mcp_connect', { id: 'zz' })).includes('未找到'));

		await call('mcp_disconnect', { id: 'm1' });
		check('mcp_disconnect: disables it', mcpState.m1 === false);
		check('mcp_disconnect: unknown id says so',
			(await call('mcp_disconnect', { id: 'zz' })).includes('未找到'));
	}

	// ---- 6. the domain switch covers them like everything else ----
	{
		const off = registerPlanabilityTools(store, undo, {
			memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
		}, cat.enabledDomains({ 'tool:extend': false, 'tool:note': false, 'tool:schedule': false }), extend);
		const gone = NAMES.filter(n => typeof off.get(n)?.invoke === 'function');
		check('switch: with extend/note/schedule off, all 11 are gone', gone.length === 0);
		const on = registerPlanabilityTools(store, undo, {
			memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
		}, null, extend);
		check('switch: with no config, all 11 are there',
			NAMES.every(n => typeof on.get(n)?.invoke === 'function'));
	}

	fs.rmSync(dir, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
