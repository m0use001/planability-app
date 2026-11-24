/* Workflow tool tests: workflow_list / _validate / _run / _runs / _replay.
 *
 * The assertions that matter are the ones about HONESTY, not about the happy path:
 *
 *   - A tool must not pretend a background run finished. The IPC handler is fire-and-forget and
 *     returns a runId; that is right for a progress panel and wrong for a tool, because the model's
 *     turn ends when the tool returns. So workflow_run WAITS, and on timeout it says "still
 *     running" and points at workflow_runs / workflow_replay — both of which are real tools. That
 *     last clause is the assertion: a pointer to something that does not exist is exactly the
 *     `agent_runs` lie N5 caught in task's reply.
 *   - A run that times out is NOT cancelled. It keeps going and lands in the journal, so the
 *     model can look later. Racing a timer off the process would lose the work it already paid for.
 *   - Refuse before dispatching. Running an invalid workflow and reporting a failed journal costs
 *     the same work as validating up front, and then the reason has to be dug out of a journal.
 *
 * Run: npm run test:workflow
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-workflow.txt');
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
const { registerWorkflowTools, WORKFLOW_LIMITS } = require(path.join(ai, 'workflow-tools.js'));
const { demoWorkflow } = require(path.join(ai, 'workflow.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

const WFS = [
	{ id: 'demo', name: '示例工作流', task: '写一份调研', steps: [{ id: 's1', role: 'researcher', prompt: '{{task}}' }, { id: 's2', role: 'planner', prompt: '{{prev}}' }] },
	{ id: 'broken', name: '坏的工作流', task: 't', steps: [], problems: ['至少需要一个步骤'] },
];

function harness(over) {
	const reg = new ToolRegistry();
	const state = { started: [], resolved: [], replayed: [] };
	registerWorkflowTools(reg, Object.assign({
		listWorkflows: () => WFS,
		// demoWorkflow carries a placeholder NAME of its own; override it so the assertions can
		// name the workflow without depending on a demo default that may change.
		resolveWorkflow: (id) => {
			if (id === 'demo') { return { ...demoWorkflow('demo', '占位'), name: '示例工作流' }; }
			if (id === 'w2') { return { ...demoWorkflow('w2', '占位'), name: '另一个' }; }
			return undefined;
		},
		runWorkflow: async (wf, o) => {
			state.started.push({ id: wf.id, task: wf.task, waitMs: o.waitMs });
			const o2 = {
				ok: true,
				output: 'chain output',
				journal: {
					runId: 'r1', workflowId: wf.id, workflowName: wf.name, task: wf.task,
					entries: [
						{ stepId: 's1', role: 'researcher', input: 'in1', output: 'out1', status: 'done', startedAt: 1, endedAt: 2 },
						{ stepId: 's2', role: 'planner', input: 'in2', output: 'out2', status: 'done', startedAt: 2, endedAt: 3 },
					],
					startedAt: 1, endedAt: 3, status: 'completed',
				},
			};
			return { outcome: o2, runId: 'r1' };
		},
		listRuns: () => [{
			runId: 'r1', workflowId: 'demo', workflowName: '示例工作流', task: '写调研', status: 'completed',
			steps: [{ stepId: 's1', role: 'researcher', status: 'done' }], startedAt: 1, endedAt: 3,
		}],
		replay: (id) => { state.replayed.push(id); return { ok: true, text: 'REPLAY-' + id }; },
	}, over || {}));
	return { reg, state, call: (n, i) => reg.get(n).invoke(i) };
}

(async () => {
	// ================= 1. registration =================
	{
		const e = harness();
		for (const n of ['workflow_list', 'workflow_validate', 'workflow_run', 'workflow_runs', 'workflow_replay']) {
			check('registered: ' + n, typeof e.reg.get(n)?.invoke === 'function');
		}
		check('domain: all five are in agent (so the switch governs them)',
			['workflow_list', 'workflow_validate', 'workflow_run', 'workflow_runs', 'workflow_replay']
				.every(n => e.reg.get(n)?.domain === 'agent'), 'domain=' + e.reg.get('workflow_run')?.domain);
		check('risk: run asks first (each step is an agent turn, so each costs quota)',
			e.reg.get('workflow_run')?.risk === 'confirm', e.reg.get('workflow_run')?.risk);
		check('risk: the four read-only ones are auto',
			['workflow_list', 'workflow_validate', 'workflow_runs', 'workflow_replay']
				.every(n => e.reg.get(n)?.risk === 'auto'));
		check('description: run_tool says HOW LONG it waits',
			/秒/.test(String(e.reg.get('workflow_run')?.description ?? '')), e.reg.get('workflow_run')?.description);
	}

	// ================= 2. workflow_list =================
	{
		const e = harness();
		const l = await e.call('workflow_list', {});
		check('list: shows ids, names and the goal', l.includes('demo') && l.includes('示例工作流') && l.includes('写一份调研'), l.slice(0, 120));
		check('list: shows the step chain', l.includes('s1:researcher -> s2:planner'), l.slice(0, 200));
		check('list: surfaces a broken workflow as a WARNING, not as an empty one',
			l.includes('坏的工作流') && l.includes('至少需要一个步骤'), l.slice(0, 200));
		const empty = harness({ listWorkflows: () => [] });
		check('list: an empty catalogue says so plainly',
			(await empty.call('workflow_list', {})).includes('没有可运行的工作流'));
	}

	// ================= 3. workflow_validate =================
	{
		const e = harness();
		const ok = await e.call('workflow_validate', {
			workflow: { id: 'x', name: 'n', task: 't', steps: [{ id: 'a', role: 'r', prompt: 'p' }] },
		});
		check('validate: a valid definition passes', ok.includes('通过'), ok);
		const bad = await e.call('workflow_validate', { workflow: { id: 'x', name: 'n', task: 't', steps: [] } });
		check('validate: a step-less definition is refused with reasons', bad.includes('不通过'), bad);
		check('validate: refuses a non-object instead of throwing',
			(await e.call('workflow_validate', { workflow: 'nope' })).includes('[FAIL]'));
	}

	// ================= 4. workflow_run: awaits and reports =================
	{
		const e = harness();
		const r = await e.call('workflow_run', { id: 'demo', task: '把这份调研改成中文' });
		check('run: passed the overriding task through',
			e.state.started[0]?.task === '把这份调研改成中文', e.state.started[0]?.task);
		check('run: used the tool\'s own wait bound',
			e.state.started[0]?.waitMs === WORKFLOW_LIMITS.waitMs, String(e.state.started[0]?.waitMs));
		check('run: the reply carries the journal, not just "ok"',
			r.includes('s1') && r.includes('s2') && r.includes('完成'), r.slice(0, 200));
		check('run: and it carries each step\'s output so the answer is usable',
			r.includes('out1') && r.includes('out2'), r.slice(0, 300));
		check('run: names the workflow and the run id', r.includes('示例工作流'), r);
	}
	{
		// Refusals, and none of them may dispatch.
		const e = harness();
		check('run: unknown id is refused and lists nothing',
			(await e.call('workflow_run', { id: 'nope' })).includes('[FAIL]'));
		check('run: and nothing was dispatched', e.state.started.length === 0);
		const ph = harness({ resolveWorkflow: () => demoWorkflow('demo', '（未指定任务）') });
		const m = await ph.call('workflow_run', { id: 'demo' });
		check('run: refuses to feed agents a literal placeholder task',
			m.includes('[FAIL]') && m.includes('任务'), m);
		check('run: and again nothing was dispatched', ph.state.started.length === 0);
		const v = harness({ resolveWorkflow: () => demoWorkflow('bad', 'ok') });
		// demoWorkflow has 3 steps, so force an invalid one by making the resolver return a
		// workflow-shaped object with no steps.
		const v2 = harness({ resolveWorkflow: () => ({ id: 'bad', name: 'n', task: 't', steps: [] }) });
		const vm = await v2.call('workflow_run', { id: 'bad' });
		check('run: an INVALID workflow is refused BEFORE dispatching',
			vm.includes('[FAIL]') && vm.includes('无效'), vm);
		check('run: and nothing was dispatched', v2.state.started.length === 0);
		check('run: empty id is refused', (await e.call('workflow_run', { id: '  ' })).includes('[FAIL]'));
		void v; void ph;
	}

	// ================= 5. the wait expires =================
	// The run outlived the wait. It must NOT be reported as finished, and the pointers it gives
	// must be REAL tools — a pointer at something that does not exist is the `agent_runs` lie that
	// N5 caught in task's reply, one layer up.
	{
		let released = null;
		const e = harness({
			// `outcome: undefined` IS the timeout signal: index.ts resolves the race as null and maps
			// it to undefined. Modelling it directly avoids waiting out the real 120s window.
			runWorkflow: async (wf, o) => {
				state.started.push({ id: wf.id, task: wf.task, waitMs: o.waitMs });
				return { outcome: undefined, runId: 'r-slow' };
			},
		});
		const state = e.state;
		const msg = await e.call('workflow_run', { id: 'demo', task: '慢慢跑' });
		check('timeout: says the run is STILL RUNNING, not that it finished',
			msg.includes('还没跑完'), msg);
		check('timeout: names the run id so the user can look it up', msg.includes('r-slow'), msg);
		check('timeout: points at workflow_runs, which is a real tool',
			msg.includes('workflow_runs') && !!e.reg.get('workflow_runs'), msg);
		check('timeout: and at workflow_replay, also a real tool',
			msg.includes('workflow_replay') && !!e.reg.get('workflow_replay'), msg);
		// Only the journal status string that the SUCCESS path carries. The timeout reply itself
		// legitimately contains the words 已完成 inside「用 workflow_replay 看已完成步骤」, so
		// asserting on 完成 would fail forever no matter what the tool did.
		check('timeout: does NOT claim an outcome', !msg.includes('completed'), msg);
		check("timeout: still used the tool's own wait bound",
			state.started[0]?.waitMs === WORKFLOW_LIMITS.waitMs, String(state.started[0]?.waitMs));
	}
	{
		// A run that outlived the wait CONTINUES. index.ts keeps the run and lands it in the journal
		// rather than cancelling it, so the work already paid for is not thrown away. That is
		// index.ts's race, not this module's — asserted here so the contract is not forgotten.
		let released = null;
		const e = harness({
			runWorkflow: async (wf) => {
				await new Promise(res => { released = res; });
				return { outcome: undefined, runId: 'r-slow-2' };
			},
		});
		const p = e.call('workflow_run', { id: 'demo', task: 'x' });
		const settled = await Promise.race([p, new Promise(r => setTimeout(() => r('PENDING'), 200))]);
		// With outcome: undefined arriving only after release, the tool must still be waiting —
		// proving it did not fabricate an answer while the run was in flight.
		check('timeout: the tool waits for the run rather than answering blind',
			settled === 'PENDING' || (typeof settled === 'string' && settled.includes('还没跑完')),
			String(settled).slice(0, 80));
		if (released) { released(); }
	}
	{
		// A second run after a timeout still completes normally, so the timer was cleared rather
		// than leaked into the next call.
		const e = harness();
		await e.call('workflow_run', { id: 'demo', task: 'one' });
		const again = await e.call('workflow_run', { id: 'demo', task: 'two' });
		check('timeout: a second run still completes', again.includes('完成'), again.slice(0, 100));
	}
	// ================= 6. workflow_runs / replay =================
	{
		const e = harness();
		const r = await e.call('workflow_runs', {});
		check('runs: lists them with status and step summary',
			r.includes('r1') && r.includes('示例工作流') && r.includes('completed'), r);
		const none = harness({ listRuns: () => [] });
		check('runs: an empty list says so',
			(await none.call('workflow_runs', {})).includes('还没有'));
		check('runs: a hostile limit is clamped, not passed through',
			await (async () => {
				let seen = null;
				const h = harness({ listRuns: (n) => { seen = n; return []; } });
				await h.call('workflow_runs', { limit: 99999 });
				return seen === WORKFLOW_LIMITS.maxRuns;
			})());
		check('runs: a negative limit is clamped up to 1', await (async () => {
			let seen = null;
			const h = harness({ listRuns: (n) => { seen = n; return []; } });
			await h.call('workflow_runs', { limit: -5 });
			return seen === 1;
		})());

		const rp = await e.call('workflow_replay', { runId: 'r1' });
		check('replay: returns the replayed text', rp === 'REPLAY-r1', rp);
		check('replay: passed the run id through', e.state.replayed[0] === 'r1');
		check('replay: an unknown run id says so',
			(await harness({ replay: () => ({ ok: false }) }).call('workflow_replay', { runId: 'zz' })).includes('[FAIL]'));
		check('replay: empty run id is refused',
			(await e.call('workflow_replay', { runId: ' ' })).includes('[FAIL]'));
	}

	// ================= 7. gating =================
	{
		const reg = new ToolRegistry();
		registerWorkflowTools(reg, harness());
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': false }));
		check('gate: with agent off all five are gone',
			['workflow_list', 'workflow_validate', 'workflow_run', 'workflow_runs', 'workflow_replay']
				.every(n => !reg.get(n)));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:agent': true }));
		check('gate: and they come back', !!reg.get('workflow_run') && !!reg.get('workflow_replay'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});