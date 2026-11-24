/* Automation tool tests — the last N-class gap (6 handlers, 0 tools).
 *
 * The assertions that matter:
 *
 *   - NO-TRIGGER REFUSAL. PC's `create()` does not check for a trigger, so an automation with
 *     neither an interval nor an hour is written, listed, looks enabled, and NEVER RUNS. Nothing
 *     in the UI says so, and it is exactly what a model produces from "remind me every day" with
 *     no time. Phone refuses it (`hasTrigger`); this tool closes that gap on PC.
 *   - BLANK STRING MEANS "NOT MENTIONED" on update, but "EMPTY" on create. Both answers to one
 *     question, and they are phone's answers — `takeIf { isNotBlank }` vs a required field. A test
 *     that only checked one direction would let the other drift.
 *   - "no such id" MUST NOT READ AS SUCCESS. An edit aimed at a stale id otherwise looks done, and
 *     the user only finds out when nothing happens.
 *
 * Run: npm run test:automation
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-automation.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { ToolRegistry } = require(path.join(ai, 'tools.js'));
const { registerAutomationTools, triggerText, hasTrigger } = require(path.join(ai, 'automation-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

function env(seed, history) {
	const rows = (seed || []).map((r, i) => Object.assign({
		id: 1000 + i, name: '任务' + i, prompt: '指令', enabled: true, lastRun: 0, createdAt: 1,
	}, r));
	const made = [];
	const reg = new ToolRegistry();
	registerAutomationTools(reg, {
		list: async () => rows.slice(),
		create: async (input) => {
			const rec = Object.assign({ id: 2000 + made.length, lastRun: 0, createdAt: 9, enabled: true }, input);
			made.push(rec);
			rows.push(rec);
			return rec;
		},
		update: async (id, patch) => {
			const i = rows.findIndex(r => r.id === id);
			if (i < 0) { return null; }
			// 镜像引擎/手机端：空白串忽略，其余按存在性应用
			for (const [k, v] of Object.entries(patch)) {
				if (typeof v === 'string' && !v.trim()) { continue; }
				rows[i][k] = v;
			}
			return rows[i];
		},
		remove: async (id) => {
			const i = rows.findIndex(r => r.id === id);
			if (i < 0) { return false; }
			// 真的移走，这样「删除后它还在吗」不是空话
			rows.splice(i, 1);
			return true;
		},
		setEnabled: async (id, on) => {
			const r = rows.find(x => x.id === id);
			if (!r) { return false; }
			r.enabled = on;
			return true;
		},
		recentLog: async () => 'tail line',
		taskHistory: async () => (history !== undefined ? history : [
			{ at: 1_700_000_000_000, ok: true, runId: 'r1' },
			{ at: 1_700_000_060_000, ok: false, runId: 'r2', error: 'workflow 不存在' },
		]),
	});
	return { reg, rows, made, call: (n, i) => reg.get(n).invoke(i) };
}

(async () => {
	// ================= 1. pure helpers =================
	{
		check('trigger: interval below an hour reads as minutes', triggerText({ intervalMinutes: 30 }) === '每 30 分钟');
		check('trigger: interval above an hour reads as hours', triggerText({ intervalMinutes: 120 }) === '每 2 小时');
		check('trigger: a day-plus interval reads as days', triggerText({ intervalMinutes: 2880 }) === '每 2 天');
		check('trigger: daily reads as a clock time', triggerText({ dailyHour: 9, dailyMinute: 5 }) === '每天 09:05');
		check('trigger: a daily minute of 0 is padded', triggerText({ dailyHour: 7 }) === '每天 07:00');
		check('trigger: neither says so plainly', triggerText({}).includes('没有触发条件'));

		check('hasTrigger: interval alone is enough', hasTrigger({ intervalMinutes: 1 }));
		check('hasTrigger: a daily hour alone is enough', hasTrigger({ dailyHour: 0 }));
		check('hasTrigger: an hour out of range is NOT a trigger', !hasTrigger({ dailyHour: 25 }));
		check('hasTrigger: an interval of 0 is NOT a trigger', !hasTrigger({ intervalMinutes: 0 }));
		check('hasTrigger: nothing is not a trigger', !hasTrigger({}));
	}

	// ================= 2. registration =================
	{
		const e = env();
		for (const n of ['automation_list', 'automation_create', 'automation_update',
			'automation_delete', 'automation_set_enabled', 'automation_log']) {
			check('registered: ' + n, typeof e.reg.get(n)?.invoke === 'function');
		}
		check('domain: all six in extend', ['automation_list', 'automation_create', 'automation_update',
			'automation_delete', 'automation_set_enabled', 'automation_log']
			.every(n => e.reg.get(n)?.domain === 'extend'), 'domain=' + e.reg.get('automation_delete')?.domain);
		check('risk: create/update/setEnabled ask first',
			['automation_create', 'automation_update', 'automation_set_enabled']
				.every(n => e.reg.get(n)?.risk === 'confirm'));
		check('risk: delete is danger', e.reg.get('automation_delete')?.risk === 'danger');
		check('risk: list and log are read-only',
			e.reg.get('automation_list')?.risk === 'auto' && e.reg.get('automation_log')?.risk === 'auto');
		// The semantics that differ per direction must be stated where the model will read them.
		check('description: update says a blank means "not mentioned"',
			/空字符串等于|没提这个字段/.test(String(e.reg.get('automation_update')?.description ?? '')),
			e.reg.get('automation_update')?.description);
	}

	// ================= 3. list =================
	{
		const e = env([
			{ name: '每日摘要', dailyHour: 8 },
			{ name: '已停用的', intervalMinutes: 60, enabled: false },
			{ name: '跑工作流', intervalMinutes: 60, kind: 'agent', workflowId: 'w1' },
			{ name: '带目标', intervalMinutes: 60, kind: 'agent', workflowId: 'w2', taskText: '目标' },
		]);
		const l = await e.call('automation_list', {});
		check('list: shows the trigger, not just a name', l.includes('每天 08:00') && l.includes('每 1 小时'), l);
		// 30 分钟走分钟分支，60 走小时分支 —— 阈值之上读成人话，这不是 bug。
		check('trigger: exactly one hour reads as hours, not as 60 minutes',
			triggerText({ intervalMinutes: 60 }) === '每 1 小时', triggerText({ intervalMinutes: 60 }));
		check('list: shows 启用/停用', l.includes('启用') && l.includes('停用'), l);
		check('list: distinguishes a single-shot from a workflow run',
			l.includes('单次提问') && l.includes('workflow=w1'), l);
		check('list: says when a workflow has a custom goal', l.includes('有自定义目标'), l);
		const empty = env();
		check('list: an empty catalogue says so',
			(await empty.call('automation_list', {})).includes('还没有自动化任务'));
	}

	// ================= 4. create =================
	{
		const e = env();
		const ok = await e.call('automation_create', {
			name: '每日', prompt: '总结今天的日程', dailyHour: 8, dailyMinute: 30,
		});
		check('create: made it', ok.includes('已创建自动化'), ok);
		check('create: names the trigger so the user sees WHEN',
			ok.includes('每天 08:30'), ok);
		check('create: and the id it got', ok.includes('编号'), ok);

		// THE refusal that matters — PC's engine would happily store a trigger-less automation.
		const noTrig = await e.call('automation_create', { name: 'x', prompt: 'y' });
		check('create: refuses a task with NO TRIGGER', noTrig.includes('[FAIL]'), noTrig);
		check('create: and explains why it never would have run',
			noTrig.includes('永远不会执行'), noTrig);
		check('create: and wrote nothing for the refused one', e.made.length === 1, 'created=' + e.made.length);

		const both = await e.call('automation_create', { name: 'x', prompt: 'y', intervalMinutes: 0, dailyHour: 25 });
		check('create: interval=0 and hour=25 together are still no trigger',
			both.includes('[FAIL]') && e.made.length === 1, both);

		check('create: refuses empty name', (await e.call('automation_create', { prompt: 'y', dailyHour: 1 })).includes('[FAIL]'));
		check('create: refuses empty prompt', (await e.call('automation_create', { name: 'x', dailyHour: 1 })).includes('[FAIL]'));
		check('create: refuses an out-of-range hour with a valid name/prompt',
			(await e.call('automation_create', { name: 'x', prompt: 'y', dailyHour: 99 })).includes('[FAIL]'));
		const agent = await e.call('automation_create', { name: 'x', prompt: 'y', intervalMinutes: 60, kind: 'agent' });
		check('create: kind=agent requires a workflowId (the engine would accept it and every run would fail)',
			agent.includes('[FAIL]') && agent.includes('workflowId'), agent);
		const agent2 = await e.call('automation_create', {
			name: 'x', prompt: 'y', intervalMinutes: 60, kind: 'agent', workflowId: 'w1',
		});
		check('create: kind=agent with a workflowId goes through', agent2.includes('跑工作流 w1'), agent2);
		check('create: exactly the two valid ones were created', e.made.length === 2, 'made=' + e.made.length);
	}

	// ================= 5. update: two answers to one question =================
	{
		const e = env([{ name: '旧名', prompt: '旧指令', dailyHour: 8 }]);
		const r = await e.call('automation_update', { id: 1000, name: '新名', dailyMinute: 15 });
		check('update: applied the fields that were given',
			r.includes('新名') && e.rows[0].name === '新名' && e.rows[0].dailyMinute === 15, r);
		check('update: left the prompt alone', e.rows[0].prompt === '旧指令');

		// Blank string is IGNORED — phone's takeIf { isNotBlank }. You cannot clear a prompt.
		const blank = await e.call('automation_update', { id: 1000, prompt: '   ' });
		check('update: a BLANK prompt is ignored, not applied (phone\'s rule)',
			e.rows[0].prompt === '旧指令', 'prompt=' + e.rows[0].prompt);
		check('update: and says so instead of letting it look like a clear',
			blank.includes('没有清空指令'), blank);

		// Numbers and booleans apply on PRESENCE, including 0 and false.
		await e.call('automation_update', { id: 1000, intervalMinutes: 0, enabled: false });
		check('update: a 0 is applied, not treated as "absent"', e.rows[0].intervalMinutes === 0);
		check('update: false is applied', e.rows[0].enabled === false);
		check('update: an out-of-range hour is applied too — presence is the signal',
			(await e.call('automation_update', { id: 1000, dailyHour: 99 }), e.rows[0].dailyHour === 99));

		check('update: an empty patch is refused',
			(await e.call('automation_update', { id: 1000 })).includes('[FAIL]'));
		check('update: a bad id is refused', (await e.call('automation_update', { id: 0, name: 'x' })).includes('[FAIL]'));
		// THE one that must not read as success.
		const gone = await e.call('automation_update', { id: 9999, name: 'x' });
		check('update: a non-existent id does NOT read as success',
			gone.includes('[FAIL]') && gone.includes('没有编号为 9999'), gone);
	}

	// ================= 6. delete / setEnabled / log =================
	{
		const e = env([{ name: '要删的', intervalMinutes: 60 }]);
		const d = await e.call('automation_delete', { id: 1000 });
		check('delete: removed', d.includes('已删除'), d);
		check('delete: it is really gone from the list',
			!(await e.call('automation_list', {})).includes('要删的'), '');
		const again = await e.call('automation_delete', { id: 1000 });
		// 只查 [FAIL] 太弱：「没有编号为 1000」和「删除 1000 失败」都含它，
		// 而这两件事对用户完全不同 —— 一个是从未存在，一个是真的删失败。
		check('delete: deleting twice says it is NOT THERE, not that the delete failed',
			again.includes('没有编号为 1000') && !again.includes('删除'), again);
		check('delete: a bad id is refused', (await e.call('automation_delete', { id: -1 })).includes('[FAIL]'));

		// 自己的 env：上一步 delete 已经把那个表清空了，共用一个会让「暂停一条还在的任务」
		// 变成操作一条已删的任务，读起来像代码坏了。
		const e2 = env([{ name: '要暂停的', intervalMinutes: 60 }]);
		const s1 = await e2.call('automation_set_enabled', { id: 1000, enabled: false });
		check('setEnabled: paused', s1.includes('已暂停'), s1);
		check('setEnabled: and the record reflects it', e2.rows[0].enabled === false);
		check('setEnabled: says the task still exists',
			s1.includes('还在列表里'), s1);
		const s2 = await e2.call('automation_set_enabled', { id: 1000, 'enabled': 'false' });
		check('setEnabled: a string "false" is not coerced — Boolean("false") would be true',
			s2.includes('[FAIL]'), s2);
		check('setEnabled: a bad id is refused',
			(await e2.call('automation_set_enabled', { id: 9999, enabled: true })).includes('[FAIL]'));

		const e3 = env([{ name: '跑过的', intervalMinutes: 60 }]);
		const lg = await e3.call('automation_log', { id: 1000 });
		check('log: shows the structured history with run ids',
			lg.includes('r1') && lg.includes('r2') && lg.includes('运行记录'), lg);
		check('log: marks successes and failures differently',
			lg.includes('成功') && lg.includes('失败'), lg);
		check('log: carries the error text', lg.includes('workflow 不存在'), lg);
		check('log: also shows the ask path\'s markdown tail (neither stands in for the other)',
			lg.includes('日志尾部'), lg);
		check('log: a bad id is refused', (await e3.call('automation_log', { id: 9999 })).includes('[FAIL]'));
		const noHist = env([{ name: '没跑过', intervalMinutes: 60 }], []);
		const nh = await noHist.call('automation_log', { id: 1000 });
		check('log: an automation that never ran says so plainly',
			nh.includes('还没有结构化运行记录'), nh);
	}

	// ================= 7. gating =================
	{
		const reg = new ToolRegistry();
		registerAutomationTools(reg, {
			list: async () => [], create: async () => null, update: async () => null,
			remove: async () => false, setEnabled: async () => false,
			taskHistory: async () => [],
		});
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': false }));
		check('gate: with extend off all six are gone',
			['automation_list', 'automation_create', 'automation_update', 'automation_delete',
				'automation_set_enabled', 'automation_log'].every(n => !reg.get(n)));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': true }));
		check('gate: and they come back', !!reg.get('automation_update') && !!reg.get('automation_create'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});