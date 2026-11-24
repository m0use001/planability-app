/* B4 expert-tool tests: ask_expert + deep_plan, plus parseSubtasks.
 *
 * These assertions are about the properties that are EASY TO LOSE AND INVISIBLE IN A DIFF:
 *
 *   - the fan-out is CONCURRENT. Phone's deep_plan is explicitly 并行; PC's runWorkflow is
 *     explicitly sequential by design. If deep_plan quietly serialised, it would still look
 *     correct — same answers, same order — and just take 4x the wall clock. So the test uses
 *     deferred promises and asserts they were all in flight at once, which is the only way to
 *     distinguish parallel from sequential without a real network.
 *   - PARTIAL FAILURE DOES NOT DISCARD SUCCESS. all, not allSettled, would throw away three
 *     completed workers because the fourth timed out — and the user would see an error instead of
 *     the answer they paid for.
 *   - the CAP HOLDS even when the splitter ignores it. The planner is a model; it can return
 *     twelve items, and twelve workers is twelve times the spend the confirmation card promised.
 *
 * Run: npm run test:expert
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-expert.txt');
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
const { registerExpertTools, parseSubtasks, EXPERTS, EXPERT_LIMITS } = require(path.join(ai, 'expert-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

/** A fake model that records every call and answers from a script. */
function fakeModel(script) {
	const calls = [];
	let n = 0;
	const call = async (system, user) => {
		calls.push({ system, user });
		const i = n++;
		if (script.worker && calls.length > 1) { return script.worker(user, i, calls.length - 2); }
		return script.split ?? '';
	};
	return { call, calls };
}

/** A call that only settles when the test says so — the only honest way to see concurrency. */
function deferred() {
	let resolve, reject;
	const p = new Promise((res, rej) => { resolve = res; reject = rej; });
	return { p, resolve, reject };
}

function makeReg(deps) {
	const reg = new ToolRegistry();
	registerExpertTools(reg, deps);
	return reg;
}

(async () => {
	// ================= 1. parseSubtasks =================
	{
		check('parse: numbered "1." form', parseSubtasks('1. 甲\n2. 乙').length === 2);
		check('parse: "1、" form', parseSubtasks('1、甲\n2、乙').length === 2);
		check('parse: "1)" form', parseSubtasks('1) 甲\n2) 乙').length === 2);
		check('parse: bullet form', parseSubtasks('- 甲\n- 乙').length === 2);
		// SHORT items must survive AND lose their marker. These two are one property: if a length
		// gate drops them from the marked pass, the fallback returns the whole line and the
		// worker receives "1. 甲" — the count still looks right, so only this catches it.
		check('parse: short items are kept and stripped of their marker',
			parseSubtasks('1. 甲\n2. 乙').join('|') === '甲|乙',
			JSON.stringify(parseSubtasks('1. 甲\n2. 乙')));
		check('parse: no returned subtask carries a list marker',
			parseSubtasks('1. 步骤1\n2. 步骤2\n3. 步骤3').every(x => !/^[0-9]{1,2}\s*[.、)）]/.test(x)),
			JSON.stringify(parseSubtasks('1. 步骤1\n2. 步骤2\n3. 步骤3')));
		check('parse: strips the marker, not the content',
			parseSubtasks('1. 改配置默认值')[0] === '改配置默认值', JSON.stringify(parseSubtasks('1. 改配置默认值')));
		// Forgiving about FORMAT: a model that writes one item per line with no markers is still
		// a usable plan, and refusing it would turn a cosmetic difference into a failed tool.
		const bare = parseSubtasks('分析现状\n给出方案\n落地步骤');
		check('parse: tolerates unmarked one-per-line output', bare.length === 3, JSON.stringify(bare));

		// Strict about COUNT. This is what keeps one call to one bounded burst of spend.
		const many = parseSubtasks(Array.from({ length: 12 }, (_, i) => `${i + 1}. 步骤${i + 1}`).join('\n'));
		check('parse: CAPS at 4 even when the splitter returns 12',
			many.length === EXPERT_LIMITS.maxSubtasks, many.length + ' parsed');
		check('parse: and keeps the first four, in order',
			many[0] === '步骤1' && many[3] === '步骤4', JSON.stringify(many));

		check('parse: empty input yields nothing', parseSubtasks('').length === 0);
		check('parse: blank lines ignored', parseSubtasks('\n\n1. 甲\n\n\n2. 乙\n').length === 2);
		check('parse: prose with no items yields nothing (so deep_plan can refuse)',
			parseSubtasks('我觉得应该先看看需求。').length === 0, JSON.stringify(parseSubtasks('我觉得应该先看看需求。')));
	}

	// ================= 2. registration =================
	{
		const reg = makeReg(fakeModel({ split: '1. 甲' }));
		check('ask_expert: registered', typeof reg.get('ask_expert')?.invoke === 'function');
		check('deep_plan: registered', typeof reg.get('deep_plan')?.invoke === 'function');
		check('ask_expert: confirm (a model call per invocation)', reg.get('ask_expert')?.risk === 'confirm');
		check('deep_plan: confirm (up to SIX model calls per invocation)', reg.get('deep_plan')?.risk === 'confirm');
		check('ask_expert: lives in agent, so the switch governs it',
			reg.get('ask_expert')?.domain === 'agent', reg.get('ask_expert')?.domain);
		check('deep_plan: lives in agent', reg.get('deep_plan')?.domain === 'agent');
		check('the four expert keys match phone', EXPERTS.map(e => e.key).join(',') === 'planner,digest,tutor,writer',
			EXPERTS.map(e => e.key).join(','));
	}

	// ================= 3. ask_expert =================
	{
		const m = fakeModel({ split: '答案正文' });
		const reg = makeReg(m);
		const r = await reg.get('ask_expert').invoke({ agent: 'planner', question: '两周内上线，要怎么排' });
		check('ask_expert: returns the answer', r.includes('答案正文'), r);
		check('ask_expert: labels WHICH expert answered', r.includes('排期规划'), r);
		check('ask_expert: the role prompt actually went out',
			m.calls[0].system.includes('排期专家'), m.calls[0].system.slice(0, 60));
		check('ask_expert: the shared closing rules are on every expert prompt',
			m.calls[0].system.includes('不要写「好的，我来帮你」'));
		check('ask_expert: the question reaches the model intact',
			m.calls[0].user === '两周内上线，要怎么排', m.calls[0].user);

		const bad = await reg.get('ask_expert').invoke({ agent: 'nope', question: 'x' });
		check('ask_expert: an unknown expert lists the valid ones instead of guessing',
			bad.includes('[FAIL]') && bad.includes('planner'), bad);
		check('ask_expert: empty question is refused (the sub-agent cannot see this conversation)',
			(await reg.get('ask_expert').invoke({ agent: 'planner', question: '  ' })).includes('自包含'));
		check('ask_expert: an over-long question is refused, not truncated silently',
			(await reg.get('ask_expert').invoke({ agent: 'planner', question: 'x'.repeat(5000) })).includes('过长'));

		// A failed call must read as a failure, not as a refusal to help.
		const boom = makeReg({ call: async () => { throw new Error('网络断了'); } });
		const em = await boom.get('ask_expert').invoke({ agent: 'tutor', question: '讲讲闭包' });
		check('ask_expert: a provider failure says so and names the cause',
			em.includes('[FAIL]') && em.includes('网络断了'), em);
	}

	// ================= 4. deep_plan: the happy path =================
	{
		const m = fakeModel({
			split: '1. 分析现状\n2. 设计方案\n3. 落地验证',
			worker: () => '子任务产出',
		});
		const reg = makeReg(m);
		const r = await reg.get('deep_plan').invoke({ task: '把登录改成 SSO' });
		check('deep_plan: makes 1 split + 3 workers + 1 synthesis = 5 calls',
			m.calls.length === 5, m.calls.length + ' calls');
		check('deep_plan: the synthesis is what comes back', r.includes('子任务产出'), r.slice(0, 120));
		check('deep_plan: each worker got its own subtask AND the overall task',
			m.calls.some(c => c.user.includes('分析现状') && c.user.includes('把登录改成 SSO')));
		check('deep_plan: workers are told to stay in their lane',
			m.calls.some(c => c.system.includes('不要评价其他子任务')));
	}

	// ================= 5. deep_plan: CONCURRENCY =================
	{
		// The distinction between parallel and sequential is invisible in the output — same
		// answers, same order, 4x the wall clock. Deferred promises are the only way to see it.
		const gates = [];
		const seen = [];
		const call = async (system, user) => {
			if (system.includes('独立完成一个子任务')) {
				const g = deferred();
				gates.push(g);
				seen.push(gates.length);
				await g.p;
				return '产出' + gates.length;
			}
			if (system.includes('任务规划器')) { return '1. 甲\n2. 乙\n3. 丙'; }
			return '汇总结果';
		};
		const reg = makeReg({ call });
		const run = reg.get('deep_plan').invoke({ task: '并行验证' });

		// Let the split land and the fan-out start.
		await new Promise(r => setImmediate(r));
		await new Promise(r => setImmediate(r));
		check('parallel: all three workers are in flight at the SAME time', gates.length === 3,
			gates.length + ' in flight');
		check('parallel: they were started before any finished', seen.length === 3);

		gates.forEach(g => g.resolve());
		const res = await run;
		check('parallel: it completes once all three land', res.includes('汇总结果'), res.slice(0, 100));
	}

	// ================= 6. deep_plan: partial failure =================
	{
		const call = async (system, user) => {
			if (system.includes('任务规划器')) { return '1. 甲\n2. 乙\n3. 丙'; }
			if (user.includes('乙')) { throw new Error('超时'); }
			return '产出';
		};
		const reg = makeReg({ call });
		const r = await reg.get('deep_plan').invoke({ task: '部分失败' });
		check('partial: the two that worked are still delivered',
			r.includes('汇总结果') || r.includes('产出'), r.slice(0, 120));
		check('partial: AND it says which one failed', r.includes('乙'), r.slice(-160));
		check('partial: the failure did not abort the run', !r.startsWith('[FAIL]'), r.slice(0, 80));
	}
	{
		// All workers failing is a real, different outcome. An empty answer here would read as
		// "the task was too hard", which is a completely different problem.
		const call = async (system) => {
			if (system.includes('任务规划器')) { return '1. 甲\n2. 乙'; }
			throw new Error('服务不可用');
		};
		const reg = makeReg({ call });
		const r = await reg.get('deep_plan').invoke({ task: '全失败' });
		check('all-fail: says plainly that everything failed',
			r.includes('[FAIL]') && r.includes('都失败'), r.slice(0, 140));
		check('all-fail: and names the cause', r.includes('服务不可用'), r.slice(0, 200));
	}
	{
		// Losing the synthesis must not lose the work already paid for.
		const call = async (system) => {
			if (system.includes('任务规划器')) { return '1. 甲\n2. 乙'; }
			if (system.includes('汇总')) { throw new Error('汇总挂了'); }
			return '甲的成果';
		};
		const reg = makeReg({ call });
		const r = await reg.get('deep_plan').invoke({ task: '汇总失败' });
		check('synth-fail: returns the worker output rather than an error',
			r.includes('甲的成果') && !r.startsWith('[FAIL]'), r.slice(0, 120));
		check('synth-fail: and explains why there is no summary', r.includes('汇总挂了'), r.slice(0, 100));
	}

	// ================= 7. deep_plan: the cap and the refusals =================
	{
		// The planner is a model. It can return twelve items, and twelve workers is twelve times
		// the spend the confirmation card promised.
		let workerCalls = 0;
		let synthCalls = 0;
		const call = async (system) => {
			if (system.includes('任务规划器')) {
				return Array.from({ length: 12 }, (_, i) => `${i + 1}. 步骤${i + 1}`).join('\n');
			}
			// Count workers and synthesis SEPARATELY: the cap is on workers, and lumping the
			// synthesis in makes the assertion measure something else entirely.
			if (system.includes('独立完成一个子任务')) { workerCalls++; } else { synthCalls++; }
			return 'x';
		};
		const reg = makeReg({ call });
		await reg.get('deep_plan').invoke({ task: '无视上限' });
		check('cap: a splitter that ignores the cap still only runs 4 workers',
			workerCalls === EXPERT_LIMITS.maxSubtasks,
			workerCalls + ' workers, ' + synthCalls + ' synth');
		check('cap: and the synthesis still happened exactly once', synthCalls === 1, synthCalls + ' synth');
	}
	{
		// Prose instead of a list: say what came back. Silently running the prose as one giant
		// subtask would spend two more calls on it.
		const reg = makeReg(fakeModel({ split: '我觉得应该先看看需求再说。' }));
		const r = await reg.get('deep_plan').invoke({ task: '拆不出来' });
		check('prose: refuses rather than running the prose as a subtask',
			r.includes('[FAIL]') && r.includes('先看看需求'), r.slice(0, 140));
	}
	{
		const reg = makeReg(fakeModel({ split: '1. 甲' }));
		check('refuses: empty task', (await reg.get('deep_plan').invoke({ task: '  ' })).includes('[FAIL]'));
		check('refuses: over-long task',
			(await reg.get('deep_plan').invoke({ task: 'x'.repeat(5000) })).includes('过长'));
		const bf = makeReg({ call: async () => { throw new Error('拆分开局就挂了'); } });
		check('split-fail: says which stage failed',
			(await bf.get('deep_plan').invoke({ task: 'x' })).includes('拆分阶段'));
	}

	// ================= 8. one worker short-circuits the synthesis =================
	{
		// Synthesising over a single result is a round trip that buys nothing — but it must be
		// SKIPPED OUT LOUD, not spent silently.
		const m = fakeModel({ split: '1. 只有这一个', worker: () => '唯一产出' });
		const reg = makeReg(m);
		const r = await reg.get('deep_plan').invoke({ task: '一件事' });
		check('single: only 2 calls (split + 1 worker), no synthesis',
			m.calls.length === 2, m.calls.length + ' calls');
		check('single: the result still comes back', r.includes('唯一产出'), r);
		check('single: and it says the synthesis was skipped rather than quietly dropping it',
			r.includes('未做汇总'), r);
	}

	// ================= 9. gated by the agent switch =================
	{
		let cfg = { plugins: {} };
		const reg = new ToolRegistry();
		registerExpertTools(reg, fakeModel({ split: '1. 甲' }));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': false }));
		check('gate: with agent off both are gone',
			!reg.get('ask_expert') && !reg.get('deep_plan'));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': true }));
		check('gate: and they come back on the next pass', !!reg.get('ask_expert') && !!reg.get('deep_plan'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});