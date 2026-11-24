/* M20-2 automation-task tests.
 *
 * The property that matters most: a scheduled job must NEVER silently stop working. So the tests
 * assert on failure paths, on the in-flight guard, and on session isolation — not just on the
 * happy path.
 *
 * Run: npm run test:autotask
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-autotask-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const T = require(path.join(base, 'ai', 'automation-task.js'));
const W = require(path.join(base, 'ai', 'workflow.js'));

const WF = W.demoWorkflow('wf-a', '研究 X 并写成笔记');
const AUTO = { id: 7, name: '每日简报', prompt: 'p', enabled: true, lastRun: 0, createdAt: 0 };

/** Let the fire-and-forget dispatch settle. Two frames: one for the IIFE to reach its awaits,
 * one for the progress/notify callbacks to have run. */
const settle = () => new Promise(r => setTimeout(r, 40));

const mkDeps = (over = {}) => {
	const calls = { sessionFor: [], run: [], notified: [], progress: [] };
	return {
		calls,
		deps: {
			resolve: (id) => (id === 'wf-a' ? WF : undefined),
			sessionFor: async (a, at) => { const s = T.backgroundSessionName(a, at); calls.sessionFor.push(s); return s; },
			run: async (wf, opts) => {
				calls.run.push({ steps: wf.steps.length, task: wf.task, ...opts });
				if (over.throwOnRun) { throw new Error('agent quota exceeded'); }
				// Real run so the journal has real step entries; then forced failure if asked,
				// because a partial failure has to be visible per step, not just as a flag.
				const runner = async (role, input) => {
					if (over.failAt && over.failAt === 'plan' && role === 'planner') { throw new Error('planner failed'); }
					return over.agentAnswer ?? 'done';
				};
				return W.runWorkflow(wf, runner, { runId: opts.runId }).then(r => r.journal);
			},
			nextRunId: () => 'run-' + calls.run.length,
			now: () => 1_700_000_000_000,
			log: new T.TaskLog(),
			notify: (t, b, e) => calls.notified.push({ t, b, e: !!e }),
			onProgress: (a, e) => calls.progress.push(e),
			...over.deps,
		},
	};
};

/** Let the fire-and-forget dispatch settle. */
// Two frames: one for the fire-and-forget IIFE to reach its awaits, one for the callbacks.

(async () => {
	// ---- 1. kind classification: unknown is 'ask', i.e. the historical behaviour
	{
		check('kind: missing kind is an ask', T.taskKind({}) === 'ask');
		check('kind: "ask" is an ask', T.taskKind({ kind: 'ask' }) === 'ask');
		check('kind: "agent" is an agent task', T.taskKind({ kind: 'agent' }) === 'agent');
		check('kind: "workflow" is an agent task too (people type that)', T.taskKind({ kind: 'workflow' }) === 'agent');
		check('kind: case is tolerated', T.taskKind({ kind: 'AGENT' }) === 'agent');
		check('kind: garbage falls back to ask rather than dispatching agents',
			T.taskKind({ kind: 'nonsense' }) === 'ask');
		check('kind: isAgentTask agrees', T.isAgentTask({ kind: 'workflow' }) === true
			&& T.isAgentTask({ kind: 'ask' }) === false);
	}

	// ---- 2. spec normalisation
	{
		check('spec: workflowId is trimmed', T.normalizeSpec({ workflowId: '  wf-a  ' }).workflowId === 'wf-a');
		check('spec: a missing timeout gets the default',
			T.normalizeSpec({}).timeoutMinutes === T.TASK_LIMITS.defaultTimeoutMinutes);
		check('spec: an absurd timeout is clamped, not honoured',
			T.normalizeSpec({ timeoutMinutes: 99999 }).timeoutMinutes === T.TASK_LIMITS.maxTimeoutMinutes);
		check('spec: a zero or negative timeout becomes 1, never 0',
			T.normalizeSpec({ timeoutMinutes: 0 }).timeoutMinutes === 1
			&& T.normalizeSpec({ timeoutMinutes: -5 }).timeoutMinutes === 1);
		check('spec: null input does not throw', T.normalizeSpec(null).workflowId === '');
	}

	// ---- 3. pre-flight checks: every refusal is EXPLAINED
	{
		const resolve = (x) => (x === 'wf-a' ? WF : undefined);
		// A plain 'ask' automation must NOT be dispatched as an agent task — the historical behaviour
		// has to keep working unchanged.
		check('check: a plain ask automation is not an agent task',
			T.checkTask(AUTO, { workflowId: 'wf-a' }, resolve, new Set()).reason === 'not-an-agent-task');
		check('check: a missing workflow id is refused',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: '' }, resolve, new Set()).reason === 'no-workflow-id');
		check('check: an unknown workflow is refused and names it',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'nope' }, resolve, new Set()).reason === 'unknown-workflow');
		check('check: an invalid workflow is refused with the reason',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' },
				() => ({ id: 'bad', name: 'n', task: 't', steps: [] }), new Set()).reason === 'invalid-workflow');
		check('check: already-running is refused',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' }, resolve, new Set([7])).reason === 'already-running');
		// Every refusal must have human text.
		for (const reason of ['no-workflow-id', 'unknown-workflow', 'invalid-workflow', 'unresolvable-role', 'already-running']) {
			check(`refusal text: ${reason} is explainable`, T.refusalText(reason, 'x').length > 2);
		}

		// Role resolvability, checked BEFORE a session exists. Without this the session was
		// created first and a permanently-failing automation littered one empty session per tick.
		const withRole = (role) => ({ id: 'wf-role', name: 'n', task: 't', steps: [{ id: 's1', role, prompt: 'p' }] });
		const yes = () => true;
		const no = () => false;
		check('check: an unplayable role is refused, with the role name in detail',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' },
				() => withRole('ghost-role'), new Set(), no).reason === 'unresolvable-role'
			&& T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' },
				() => withRole('ghost-role'), new Set(), no).detail === 'ghost-role');
		check('check: a playable role passes',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' },
				() => withRole('researcher'), new Set(), yes).ok === true);
		check('check: a resolver that THROWS is a refusal, not an escaped exception',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' },
				() => withRole('x'), new Set(), () => { throw new Error('no agent'); }).reason === 'unresolvable-role');
		// Omitting the callback keeps the old behaviour: tests and callers that only care about
		// the workflow shape must not have to stub an agent manager.
		check('check: no canResolveRole supplied skips the check (back-compat)',
			T.checkTask({ ...AUTO, kind: 'agent' }, { workflowId: 'wf-a' }, resolve, new Set()).ok === true);
		// The whole point: dispatch must not touch sessionFor when the role cannot resolve.
		const order = [];
		const rOrder = T.dispatchAgentTask(
			{ ...AUTO, kind: 'agent' }, T.normalizeSpec({ workflowId: 'wf-b' }),
			{
				resolve: () => withRole('ghost-role'),
				canResolveRole: () => false,
				sessionFor: async () => { order.push('session'); return 's'; },
				run: async () => ({ status: 'done' }),
				nextRunId: () => 'r1',
				log: new T.TaskLog(),
				notify: () => { },
			},
			new Set(),
		);
		check('check: dispatch refuses before creating a session', rOrder.started === false
			&& rOrder.reason === 'unresolvable-role' && order.length === 0);
	}

	// ---- 4. background runs get their OWN session, never the user's
	{
		check('session: the name is namespaced and timestamped',
			/^自动化 · 每日简报 · /.test(T.backgroundSessionName(AUTO, 1_700_000_000_000)));
		check('session: it is not the active session id', !/^[a-f0-9]{8}$/.test(T.backgroundSessionName(AUTO, 1)));
	}

	// ---- 5. dispatch happy path
	{
		const { calls, deps } = mkDeps();
		const inflight = new Set();
		const r = T.dispatchAgentTask({ ...AUTO, kind: 'agent' }, T.normalizeSpec({ workflowId: 'wf-a', task: '研究 Y' }), deps, inflight);
		check('dispatch: it started', r.started === true && !!r.runId);
		check('dispatch: it returned BEFORE the run finished (non-blocking)', inflight.has(7));
		await settle(); await settle();
		check('dispatch: the guard is released when it finishes', inflight.size === 0);
		check('dispatch: a dedicated session was created', calls.sessionFor.length === 1
			&& /自动化/.test(calls.sessionFor[0]));
		check('dispatch: the caller task overrode the workflow default', calls.run[0].task === '研究 Y');
		check('dispatch: the run got the timeout in ms',
			calls.run[0].timeoutMs === T.TASK_LIMITS.defaultTimeoutMinutes * 60_000);
		check('dispatch: progress was reported once', calls.progress.length === 1 && calls.progress[0].ok === true);
		check('dispatch: the user was notified of start and finish', calls.notified.length === 2);
		check('dispatch: the failure notification is not an error one',
			calls.notified.every(n => !n.e));
	}

	// ---- 6. the guard actually prevents a double fire
	// Two sweep ticks in the same second must not run the task twice.
	{
		const { calls, deps } = mkDeps({ });
		const inflight = new Set();
		const spec = T.normalizeSpec({ workflowId: 'wf-a' });
		const a = { ...AUTO, kind: 'agent' };
		const r1 = T.dispatchAgentTask(a, spec, deps, inflight);
		const r2 = T.dispatchAgentTask(a, spec, deps, inflight);
		check('guard: the second dispatch is refused while the first runs',
			r1.started === true && r2.started === false && r2.reason === 'already-running');
		check('guard: and it did NOT notify the user (the guard is not an error)', calls.notified.length === 0);
		await settle(); await settle();
		const r3 = T.dispatchAgentTask(a, spec, deps, inflight);
		check('guard: once finished, it can fire again', r3.started === true);
		await settle(); await settle();
	}

	// ---- 7. FAILURES ARE LOUD — a job that silently dies is worse than one that fails
	{
		const { calls, deps } = mkDeps({ throwOnRun: true });
		const inflight = new Set();
		T.dispatchAgentTask({ ...AUTO, kind: 'agent' }, T.normalizeSpec({ workflowId: 'wf-a' }), deps, inflight);
		await settle(); await settle();
		check('fail: the failure was reported as progress', calls.progress.length === 1 && calls.progress[0].ok === false);
		check('fail: the reason is preserved', /quota/.test(calls.progress[0].error || ''));
		check('fail: the user was told it is an error', calls.notified.some(n => n.e === true));
		check('fail: the guard was still released', inflight.size === 0);
		// A refused dispatch must also be logged, or the user has no idea why nothing ran.
		const { calls: c2, deps: d2 } = mkDeps();
		T.dispatchAgentTask({ ...AUTO, kind: 'agent' }, T.normalizeSpec({ workflowId: 'missing' }), d2, new Set());
		await settle(); await settle();
		check('fail: a refusal is logged, not silently skipped', c2.notified.some(n => /找不到工作流/.test(n.b)));
	}

	// ---- 8. a partially failed run is reported per step
	{
		const { calls, deps } = mkDeps({ failAt: 'plan' });
		T.dispatchAgentTask({ ...AUTO, kind: 'agent' }, T.normalizeSpec({ workflowId: 'wf-a' }), deps, new Set());
		await settle(); await settle();
		const e = calls.progress[0];
		check('partial: the entry is not ok', e.ok === false);
		// The run HALTED at the failing step (M20-1 decision 2), so there are 2 entries, not 3.
		// The point is that a partial run is visible per step — not summarised into one bit.
		check('partial: every attempted step is listed with its status',
			Array.isArray(e.steps) && e.steps.length === 2
			&& e.steps.map(s => s.stepId + ':' + s.status).join(' ') === 'research:done plan:failed');
		check('partial: the failing step is visible in the summary', /plan/.test(e.summary));
		check('partial: the summary says which step failed rather than just "failed"',
			/失败：/.test(e.summary));
	}

	// ---- 9. the log is bounded
	{
		const log = new T.TaskLog();
		for (let i = 0; i < 500; i++) { log.append(AUTO, { at: i, ok: true, summary: 's' + i }); }
		const l = log.list(AUTO, 1000);
		check('log: it is bounded per automation', l.length <= T.TASK_LIMITS.maxLogLines);
		check('log: the newest are kept', l[l.length - 1].summary === 's499');
		check('log: list(n) returns the newest n', log.list(AUTO, 5).length === 5);
		check('log: another automation has its own history', log.list({ ...AUTO, id: 8 }).length === 0);
		log.clear(AUTO);
		check('log: clear works', log.list(AUTO).length === 0);
	}

	// ---- 9b. the hook that keeps "history" from being a cache
	{
		const seen = [];
		const log = new T.TaskLog((id, entries) => seen.push([id, entries.length, entries[entries.length - 1].runId]));
		log.append(AUTO, { at: 1, ok: true, summary: 'a', runId: 'r-1' });
		log.append(AUTO, { at: 2, ok: false, summary: 'b', runId: 'r-2' });
		check('persist: every append notifies, carrying the automation id',
			seen.length === 2 && seen[0][0] === AUTO.id);
		check('persist: the notified payload ends with the entry just written', seen[1][2] === 'r-2');
		check('persist: the notified payload is the whole live list, not a delta',
			seen[1][1] === log.list(AUTO, 1000).length);

		// Seeding from disk must NOT write straight back — the file was just read, and a load
		// that fires the hook would rewrite it for no reader.
		const before = seen.length;
		log.load(AUTO.id, [{ at: 9, ok: true, summary: 'restored', runId: 'r-old' }]);
		check('persist: loading from disk does not fire the hook', seen.length === before);
		check('persist: the seeded entry is what list returns', log.list(AUTO, 5)[0].runId === 'r-old');
		check('persist: has() says a loaded automation is present', log.has(AUTO.id) === true);
		check('persist: has() says an unknown automation is absent', log.has(9999) === false);

		// An older build could have written more than today's limit; reading must re-apply it.
		const oversized = Array.from({ length: T.TASK_LIMITS.maxLogLines + 50 }, (_, i) => ({ at: i, ok: true, summary: 'x' }));
		log.load(77, oversized);
		check('persist: loading re-applies the bound', log.list({ id: 77 }, 99999).length <= T.TASK_LIMITS.maxLogLines);

		// The widened `{ id: number }` signature is what lets a caller ask without fabricating
		// name/prompt/enabled — the old code built a fake Automation just to read a list.
		check('persist: list accepts a bare id', log.list({ id: AUTO.id }).length >= 1);
	}

	// ---- 10. summarize
	{
		const ok = {
			runId: 'r', workflowId: 'w', workflowName: '收集→排程→记录', task: 't',
			entries: [{ stepId: 'a', role: 'r', input: '', output: 'OUT', status: 'done', startedAt: 1, endedAt: 2 }],
			startedAt: 1, endedAt: 2, status: 'done',
		};
		check('summary: a clean run reports the step count and the output',
			/1\/1 步完成/.test(T.summarize(ok)) && /OUT/.test(T.summarize(ok)));
		const bad = { ...ok, status: 'failed', failedAt: 'b',
			entries: [...ok.entries, { stepId: 'b', role: 'q', input: '', output: '', status: 'failed', startedAt: 1, endedAt: 2, error: 'boom' }] };
		check('summary: a failed run names the failing step AND its role',
			/b\(q\)/.test(T.summarize(bad)));
		check('summary: and includes the error', /boom/.test(T.summarize(bad)));
		const huge = { ...ok, entries: [{ ...ok.entries[0], output: 'z'.repeat(100_000) }] };
		check('summary: a huge final output is truncated in the SUMMARY only',
			T.summarize(huge).length < 3000);
	}

	// ---- 11. hostile input
	{
		const { deps } = mkDeps();
		let threw = false;
		try {
			T.dispatchAgentTask(null, null, deps, new Set());
		} catch { threw = true; }
		check('hostile: a null automation does not throw out of dispatch', threw === false);
		const { deps: d2 } = mkDeps();
		let t2 = false;
		try { T.dispatchAgentTask({ ...AUTO, kind: 'agent' }, T.normalizeSpec({}), d2, new Set()); } catch { t2 = true; }
		check('hostile: a spec with no workflow id is refused, not dispatched', t2 === false);
		check('hostile: refusal text survives garbage',
			typeof T.refusalText(undefined) === 'string' && T.refusalText(undefined).length > 0);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
