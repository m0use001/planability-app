/* M20-1 tail: verifier auto-fix loop tests.
 *
 * The properties that matter: the loop cannot be gamed by the author, every attempt is
 * journalled, a crashing verifier is NOT a pass, and the bound is enforced.
 *
 * Run: npm run test:fixloop
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-fixloop-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const F = require(path.join(base, 'ai', 'fix-loop.js'));
const W = require(path.join(base, 'ai', 'workflow.js'));

const WF = W.demoWorkflow('wf', '研究 X 并写成笔记');
const CRIT = '每条结论都要有可核验的来源，且不许出现「大概」「可能」这类措辞。';

(async () => {
	// ---- 1. pre-flight refusals: refuse BEFORE spending anything
	{
		const noCrit = await F.runWithFixLoop({
			workflow: WF, criteria: '  ', verifier: async () => 'pass', fixerRole: '审查员',
		}, async () => 'x');
		check('refuse: empty criteria is refused', noCrit.ok === false && noCrit.reason === 'no-criteria');
		check('refuse: ...and nothing ran', noCrit.attempts === 0 && !noCrit.run);

		// Decision 1: the fixer must not be the author of the step it fixes.
		const same = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, verifier: async () => 'fail', fixerRole: WF.steps[WF.steps.length - 1].role,
		}, async () => 'x');
		check('refuse: the fixer may not be the author of the verified step',
			same.ok === false && same.reason === 'same-agent-verifies');
		check('refuse: ...and it says why', /自己检查自己/.test(same.detail));
		check('refuse: ...and nothing ran', !same.run);

		const bad = await F.runWithFixLoop({
			workflow: { id: 'x', name: 'n', task: 't', steps: [] }, criteria: CRIT,
			verifier: async () => 'fail', fixerRole: '审查员',
		}, async () => 'x');
		check('refuse: an invalid workflow is refused', bad.ok === false && bad.reason === 'invalid-workflow');

		const oor = await F.runWithFixLoop({
			workflow: WF, stepIndex: 99, criteria: CRIT, verifier: async () => 'fail', fixerRole: '审查员',
		}, async () => 'x');
		check('refuse: an out-of-range step index is refused', oor.ok === false && oor.reason === 'step-out-of-range');
	}

	// ---- 2. the happy path: first run passes, no fixes
	{
		let ran = 0;
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, verifier: async () => 'pass', fixerRole: '审查员',
			sleep: async () => { },
		}, async () => { ran++; return '产出'; });
		check('pass: it succeeded on the first run', r.ok === true && r.attempts === 0 && r.finalVerdict === 'pass');
		check('pass: the workflow ran its steps', ran === 3);
		check('pass: the fixer was never invoked', r.attempts === 0);
	}

	// ---- 3. a failure triggers a fix, and every attempt is JOURNALLED
	{
		const attempts = [];
		let fixCalls = 0;
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员',
			verifier: async ({ output }) => (output === 'GOOD' ? 'pass' : 'fail'),
			sleep: async () => { },
			onAttempt: a => attempts.push(a),
		}, async (role) => {
			if (role === '审查员') { fixCalls++; return fixCalls >= 2 ? 'GOOD' : 'STILL_BAD'; }
			return 'BAD';
		});
		check('fix: it eventually passed', r.ok === true && r.finalVerdict === 'pass');
		check('fix: it took two attempts', r.attempts === 2);
		check('fix: every attempt was reported', attempts.length === 3
			&& attempts.map(a => a.verdict).join() === 'fail,fail,pass');
		// The journal must show the correction history — that is how a weird final output is
		// explained later.
		const fixEntries = r.run.entries.filter(e => e.stepId.includes('-fix'));
		check('fix: each fix attempt is in the journal', fixEntries.length === 2);
		check('fix: the journal entries record what was asked', fixEntries.every(e => e.role === '审查员'));
		check('fix: the fixed output is in the journal', fixEntries.some(e => e.output === 'GOOD'));
	}

	// ---- 4. the bound is ENFORCED and reported
	{
		let calls = 0;
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 3,
			verifier: async () => 'fail', sleep: async () => { },
		}, async (role) => { if (role === '审查员') { calls++; } return 'BAD'; });
		check('bound: it gave up rather than looping forever', r.ok === false && r.reason === 'exhausted');
		check('bound: after exactly maxAttempts fixes', r.attempts === 3 && calls === 3);
		check('bound: it says how many it tried', /3 次/.test(r.detail));
		check('bound: the attempts are still journalled', r.run.entries.filter(e => e.stepId.includes('-fix')).length === 3);
		check('bound: maxAttempts=0 means NO fixes at all', (await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 0,
			verifier: async () => 'fail', sleep: async () => { },
		}, async () => 'BAD')).attempts === 0);
		check('bound: an absurd maxAttempts is clamped', (await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 999,
			verifier: async () => 'fail', sleep: async () => { },
		}, async () => 'BAD')).attempts <= 5);
	}

	// ---- 5. a CRASHING verifier is not a pass
	// Decision: treating "could not verify" as "verified" is the worst possible outcome.
	{
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员',
			verifier: async () => { throw new Error('verifier quota exceeded'); },
			sleep: async () => { },
		}, async () => 'BAD');
		check('crash: a crashing verifier does NOT report success', r.ok === false);
		check('crash: it says the verifier failed rather than the output', /验证器执行失败/.test(r.detail));
		check('crash: no fix was attempted on an unverifiable output', r.attempts === 0);

		// And a crash on the SECOND verification must not be mistaken for a pass either.
		let n = 0;
		const r2 = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 3,
			verifier: async () => { n++; if (n === 1) { return 'fail'; } throw new Error('gone'); },
			sleep: async () => { },
		}, async () => 'X');
		check('crash: a verifier that dies mid-loop is a refusal', r2.ok === false && /验证器执行失败/.test(r2.detail));
	}

	// ---- 6. a fixer that itself fails is reported, not swallowed
	{
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 3,
			verifier: async () => 'fail', sleep: async () => { },
		}, async (role) => {
			if (role === '审查员') { throw new Error('fixer quota exceeded'); }
			return 'BAD';
		});
		check('fixfail: it stops immediately rather than burning the remaining attempts',
			r.ok === false && r.attempts === 1);
		check('fixfail: the reason is reported', /quota/.test(r.detail));
		const failedEntry = r.run.entries.find(e => e.stepId.includes('-fix'));
		check('fixfail: the failed fix is journalled as failed', failedEntry && failedEntry.status === 'failed');
	}

	// ---- 7. Decision 4: the fixer gets the CRITERIA, not the original ask
	{
		let fixInput = '';
		await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 1,
			verifier: async ({ output }) => (output === 'GOOD' ? 'pass' : 'fail'),
			sleep: async () => { },
		}, async (role, input) => {
			if (role === '审查员') { fixInput = input; return 'GOOD'; }
			return 'BAD';
		});
		check('criteria: the fixer is told what "good" means', fixInput.includes(CRIT));
		check('criteria: and is given the failed output to correct', /BAD/.test(fixInput));
		check('criteria: it is NOT told to redo the original task', !/收集与以下任务相关的事实/.test(fixInput));
	}

	// ---- 8. the verified step defaults to the LAST one, and can be targeted
	{
		const seen = [];
		await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 1,
			verifier: async ({ output }) => { seen.push(output); return output === 'BAD2' ? 'fail' : 'pass'; },
			sleep: async () => { },
		}, async (role) => (role === '审查员' ? 'BAD2' : (role === 'researcher' ? 'BAD0' : 'BAD1')));
		// The demo's steps are researcher -> planner -> recorder, so the LAST step is `recorder`,
		// whose output is BAD1. BAD2 is the FIXER's (审查员) replacement, seen only after a fix.
		// This caught a real bug: indexing the workflow's steps instead of the journal's entries
		// verified a step that a halted run never produced.
		check('target: the LAST step is verified by default', seen[0] === 'BAD1' && seen.length === 1,
			);

		const seen2 = [];
		await F.runWithFixLoop({
			workflow: WF, stepIndex: 0, criteria: CRIT, fixerRole: '审查员', maxAttempts: 1,
			verifier: async ({ output }) => { seen2.push(output); return 'pass'; },
			sleep: async () => { },
		}, async () => 'X');
		check('target: an explicit stepIndex is verified instead', seen2.length === 1 && seen2[0] === 'X');
	}

	// ---- 9. hostile inputs
	{
		let threw = false;
		try {
			await F.runWithFixLoop(null, async () => 'x');
		} catch { threw = true; }
		check('hostile: a null options object does not throw out of the loop', threw === false);
		const r = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 1,
			verifier: async () => 'pass', sleep: async () => { },
		}, async () => null);
		check('hostile: a null step output still verifies cleanly', r.ok === true);
		const r2 = await F.runWithFixLoop({
			workflow: WF, criteria: CRIT, fixerRole: '审查员', maxAttempts: 1,
			verifier: async ({ output }) => (output === 'GOOD' ? 'pass' : 'fail'), sleep: async () => { },
		}, async (role) => (role === '审查员' ? 'GOOD' : 'z'.repeat(100_000)));
		check('hostile: a huge output is quoted back bounded, not in full',
			r2.ok === true && r2.run.entries.find(e => e.stepId.includes('-fix')).input.length < 20_000);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});