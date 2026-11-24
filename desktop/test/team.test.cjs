/* team_assemble tool tests — the last of the four N-class gaps with an IPC handler and no tool.
 *
 * The assertions that matter:
 *
 *   - IT DOES NOT DISPATCH. `assembleTeam` is pure computation, and the IPC's own comment says the
 *     team is a PROPOSAL because "the user presses the button ... is the only sane default for
 *     spending quota on a guessed team". So the tool says out loud that nothing ran and nothing
 *     was spent. A model that reads "workflow ready" as "go" burns N agent turns on a keyword
 *     scorer's guess.
 *   - THE CEILING IS ENFORCED. `assembledWorkflows` is an unbounded Map; the IPC is user-driven
 *     (one click, one entry) but a model can loop. Past the ceiling it refuses rather than
 *     evicting — a workflow the user is about to run must not vanish.
 *   - THE WEAK LINK IS SURFACED, NOT AVERAGED AWAY. An 80% average hiding one 20% step is the
 *     number that gets a bad team approved.
 *
 * Run: npm run test:team
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-team.txt');
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
const { registerTeamTool, TEAM_TOOL_LIMITS } = require(path.join(ai, 'team-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

function proposal(over) {
	const base = {
		requirement: '调研并给个排期方案',
		template: { key: 'impl_review_test', name: '实现/审查/测试', description: '通用中大型项目默认组合' },
		steps: [
			{ intent: 'research', agentId: 'a1', agentName: '研究员', confidence: 0.9, because: '工具覆盖调研' },
			{ intent: 'plan', agentId: 'a2', agentName: '策划师', confidence: 0.2, because: '缺排期工具，勉强顶上' },
		],
		workflow: { id: 'assembled-1', name: '组队工作流', task: '调研并给个排期方案', steps: [{ id: 's1', role: 'researcher', prompt: 'x' }] },
		weakest: 0.2,
		warnings: ['策划师的工具集与「规划」意图不完全匹配'],
	};
	return Object.assign(base, over || {});
}

function env(over) {
	const state = { calls: [], registered: 0 };
	const reg = new ToolRegistry();
	registerTeamTool(reg, Object.assign({
		assemble: (req, prefer) => {
			state.calls.push({ req, prefer });
			return { ok: true, proposal: proposal() };
		},
		countRegistered: () => state.registered,
	}, over || {}));
	return { reg, state, call: (i) => reg.get('team_assemble').invoke(i) };
}

(async () => {
	// ================= 1. registration =================
	{
		const e = env();
		check('registered: team_assemble', typeof e.reg.get('team_assemble')?.invoke === 'function');
		check('domain: agent (it dispatches agent work, same as workflow/task)',
			e.reg.get('team_assemble')?.domain === 'agent', e.reg.get('team_assemble')?.domain);
		// auto, and that is a CONCLUSION not carelessness: assembleTeam makes no model calls.
		check('risk: auto — assembling costs nothing, running does',
			e.reg.get('team_assemble')?.risk === 'auto', e.reg.get('team_assemble')?.risk);
		check('description: says it does NOT dispatch',
			/不派发|不花额度/.test(String(e.reg.get('team_assemble')?.description ?? '')),
			e.reg.get('team_assemble')?.description);
	}

	// ================= 2. the happy path =================
	{
		const e = env();
		const r = await e.call({ requirement: '调研并给个排期方案' });
		check('assemble: the requirement reached the deps', e.state.calls[0]?.req === '调研并给个排期方案');
		check('reply: names the template', r.includes('实现/审查/测试'), r.slice(0, 100));
		check('reply: shows the roster with each step', r.includes('调研 → 研究员') && r.includes('规划 → 策划师'), r);
		check('reply: shows confidence as a percentage', r.includes('90%') && r.includes('20%'), r);
		check('reply: shows WHY each agent was picked', r.includes('工具覆盖调研'), r);
		// The weak link surfaced, not averaged away.
		check('reply: surfaces the WEAKEST link (an average would hide it)',
			r.includes('最弱一环') && r.includes('20%'), r);
		check('reply: carries the warnings', r.includes('不完全匹配'), r);
		check('reply: gives the workflow id so workflow_run can find it',
			r.includes('assembled-1'), r);
		// THE honest part.
		check('reply: says out loud that NOTHING was dispatched', r.includes('没有派发'), r);
		check('reply: and that nothing was spent', r.includes('没有消耗额度'), r);
		check('reply: and who decides (the user, then workflow_run)',
			r.includes('由用户决定') && r.includes('workflow_run'), r);
	}

	// ================= 3. the ceiling =================
	{
		const e = env();
		e.state.registered = TEAM_TOOL_LIMITS.registeredWarnAt - 1;
		const ok = await e.call({ requirement: '正好在下限之下' });
		check('ceiling: one below the ceiling still assembles', !ok.includes('[FAIL]'), ok.slice(0, 80));
		check('ceiling: and it did assemble', e.state.calls.length === 1);

		const full = env();
		full.state.registered = TEAM_TOOL_LIMITS.registeredWarnAt;
		const r = await full.call({ requirement: '已经满了' });
		check('ceiling: at the ceiling it REFUSES',
			r.includes('[FAIL]') && r.includes(String(TEAM_TOOL_LIMITS.registeredWarnAt)), r);
		check('ceiling: and did NOT assemble another', full.state.calls.length === 0);

		// It refuses rather than evicting: a workflow the user is about to run must not vanish.
		const over = env();
		over.state.registered = 99;
		const r2 = await over.call({ requirement: '超了' });
		check('ceiling: way past the ceiling still refuses (never silently evicts)',
			r2.includes('[FAIL]') && over.state.calls.length === 0, r2);
	}

	// ================= 4. refusals =================
	{
		const e = env();
		check('refuses: empty requirement',
			(await e.call({ requirement: '   ' })).includes('[FAIL]'));
		const long = 'x'.repeat(TEAM_TOOL_LIMITS.maxRequirementChars + 1);
		check('refuses: an over-long requirement', (await e.call({ requirement: long })).includes('[FAIL]'));
		check('refuses: and did not assemble', e.state.calls.length === 0);

		const bad = env({ assemble: () => ({ ok: false, reason: 'no_agents', detail: '没有可用的 agent' }) });
		const r = await bad.call({ requirement: '随便' });
		check('refuses: assemble failure surfaces the DETAIL, not just the reason',
			r.includes('[FAIL]') && r.includes('没有可用的 agent'), r);
	}
	{
		// preferTemplates: must reach the deps as an array of strings, and a non-array is not
		// forwarded as garbage.
		const e = env();
		await e.call({ requirement: 'x', preferTemplates: ['impl_review_test', 'options'] });
		check('pass-through: preferTemplates reaches the deps',
			JSON.stringify(e.state.calls[0]?.prefer) === JSON.stringify(['impl_review_test', 'options']),
			JSON.stringify(e.state.calls[0]?.prefer));
		await e.call({ requirement: 'x' });
		check('pass-through: an omitted preferTemplates reaches as undefined',
			e.state.calls[1]?.prefer === undefined, String(e.state.calls[1]?.prefer));
		await e.call({ requirement: 'x', preferTemplates: 'not-an-array' });
		check('pass-through: a non-array preferTemplates is dropped, not forwarded',
			e.state.calls[2]?.prefer === undefined, JSON.stringify(e.state.calls[2]?.prefer));
	}

	// ================= 5. gating =================
	{
		const reg = new ToolRegistry();
		registerTeamTool(reg, { assemble: () => ({ ok: true, proposal: proposal() }), countRegistered: () => 0 });
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': false }));
		check('gate: with agent off the tool is gone', !reg.get('team_assemble'));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': true }));
		check('gate: and it comes back', !!reg.get('team_assemble'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});