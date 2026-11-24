/* M20-2 sweep integration: an 'ask' automation must behave EXACTLY as before, and an 'agent'
 * automation must be dispatched without blocking the sweep or the other automations.
 *
 * Run: npm run test:autosweep
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-autosweep-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const A = require(path.join(base, 'ai', 'automation.js'));
const W = require(path.join(base, 'ai', 'workflow.js'));

/** A store stub: just enough for the engine's file + snapshot paths. */
const mkStore = (dir) => ({
	dataDir: dir,
	live: async (c) => (c === 'schedules' ? [{ id: 's1', title: '会议', date: '2026-10-05', startIso: '', endIso: '', note: '' }]
		: c === 'todos' ? [{ id: 't1', title: '写周报', date: '2026-10-05', priority: 1, status: 'open' }]
			: c === 'projects' ? [{ id: 'p1', name: 'Planability', description: '' }]
				: []),
});

const settle = () => new Promise(r => setTimeout(r, 40));

const seed = async (dir, list) => {
	fs.writeFileSync(path.join(dir, 'automations.json'), JSON.stringify(list), 'utf8');
};

(async () => {
	const tmp = path.join(os.tmpdir(), 'pcb-autosweep-' + Date.now());
	fs.mkdirSync(tmp, { recursive: true });

	// ---- 1. legacy automations keep working, byte for byte
	{
		const dir = path.join(tmp, 'legacy');
		fs.mkdirSync(dir, { recursive: true });
		const calls = [];
		const notes = [];
		const eng = new A.AutomationEngine(mkStore(dir),
			async (sys, user) => { calls.push({ sys, user }); return 'ANSWER'; },
			(t, b, e) => notes.push({ t, b, e: !!e }));
		// No task deps at all — the exact pre-M20-2 construction.
		await seed(dir, [{ id: 1, name: '每日简报', prompt: '总结今天', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		check('legacy: a task-less automation still runs', calls.length === 1);
		check('legacy: it still uses the single-shot call', /总结今天/.test(calls[0].user));
		check('legacy: the snapshot injection is unchanged', /日程|todo|待办|snapshot/i.test(calls[0].user) || calls[0].user.includes('---'));
		check('legacy: the notification is unchanged', notes.length === 1 && /执行完成/.test(notes[0].b));
		check('legacy: lastRun advanced', (await eng.list())[0].lastRun > 0);
	}

	// ---- 2. an agent task does NOT reach the single-shot call
	{
		const dir = path.join(tmp, 'agent');
		fs.mkdirSync(dir, { recursive: true });
		const calls = [];
		const notes = [];
		const ran = [];
		const eng = new A.AutomationEngine(mkStore(dir),
			async () => { calls.push(1); return 'ANSWER'; },
			(t, b, e) => notes.push({ t, b, e: !!e }),
			{
				resolve: (id) => (id === 'wf-a' ? W.demoWorkflow('wf-a', '研究 X') : undefined),
				sessionFor: async (a, at) => 'sess-bg',
				run: async (wf, opts) => {
					ran.push({ id: opts.runId, sessionId: opts.sessionId, task: wf.task, steps: wf.steps.length });
					return W.runWorkflow(wf, async () => 'done', { runId: opts.runId }).then(r => r.journal);
				},
				nextRunId: () => 'bg-run-1',
			});
		await seed(dir, [{ id: 2, name: '每日研究', prompt: 'x', kind: 'agent', workflowId: 'wf-a', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		check('agent: it did NOT use the single-shot call', calls.length === 0);
		check('agent: the workflow runner was used', ran.length === 1);
		check('agent: it got its OWN session, not the active one', ran[0].sessionId === 'sess-bg');
		await settle();
		await new Promise(r => setTimeout(r, 150)); // the write is fire-and-forget

		// The in-memory map alone would make 历史 a cache: the entries exist while this process
		// runs and vanish on relaunch, which is the opposite of what a history button promises.
		// So the file is the real record, and a brand-new engine — which has never seen this run
		// — has to be able to show it.
		const logFile = path.join(dir, 'automation-logs', '2.tasklog.json');
		check('history: the run was persisted to disk', fs.existsSync(logFile));
		const onDisk = JSON.parse(fs.readFileSync(logFile, 'utf8'));
		check('history: the persisted entry keeps its runId for replay',
			onDisk.length === 1 && onDisk[0].runId === 'bg-run-1' && onDisk[0].ok === true);

		const fresh = new A.AutomationEngine(mkStore(dir), async () => 'ANSWER', () => { });
		const hist = await fresh.taskHistory(2);
		check('history: a freshly constructed engine reads it back',
			hist.length === 1 && hist[0].runId === 'bg-run-1');
		check('history: asking twice gives the same answer (load is remembered)',
			(await fresh.taskHistory(2)).length === 1);
		check('history: an automation with no file reads as empty, not as an error',
			(await fresh.taskHistory(999)).length === 0);

		const logs = path.join(dir, 'automation-logs');
		fs.mkdirSync(logs, { recursive: true });
		fs.writeFileSync(path.join(logs, '55.tasklog.json'), '{ not json', 'utf8');
		check('history: a malformed file degrades to empty rather than throwing',
			(await fresh.taskHistory(55)).length === 0);

		// `slice(-0)` is the WHOLE array, so an unvalidated count of 0 hands back every entry
		// instead of a page. Seed more than one page so the two outcomes are distinguishable.
		const many = Array.from({ length: 60 }, (_, i) => ({ at: i, ok: true, summary: 'e' + i }));
		fs.writeFileSync(path.join(logs, '66.tasklog.json'), JSON.stringify(many), 'utf8');
		check('history: n=0 falls back to one page instead of returning everything',
			(await fresh.taskHistory(66, 0)).length === 50);
		check('history: a sane n is honoured', (await fresh.taskHistory(66, 5)).length === 5);
		check('history: an absurd n still cannot exceed what exists',
			(await fresh.taskHistory(66, 99999)).length === 60);
	}

	// ---- 3. a slow agent run does NOT block the sweep or a short ask
	// The whole reason the agent branch is fire-and-forget.
	{
		const dir = path.join(tmp, 'slow');
		fs.mkdirSync(dir, { recursive: true });
		const calls = [];
		let release;
		const gate = new Promise(r => { release = r; });
		const eng = new A.AutomationEngine(mkStore(dir),
			async () => { calls.push('ask'); return 'A'; },
			() => { },
			{
				resolve: () => W.demoWorkflow('wf-a', 't'),
				sessionFor: async () => 's',
				run: async (wf, opts) => { await gate; return W.runWorkflow(wf, async () => 'x', { runId: opts.runId }).then(r => r.journal); },
				nextRunId: () => 'r',
			});
		await seed(dir, [
			{ id: 1, name: 'agent job', prompt: 'x', kind: 'agent', workflowId: 'wf-a', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 },
			{ id: 2, name: 'quick ask', prompt: 'y', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 },
		]);
		await eng.sweep();
		check('non-blocking: the ask still ran while the agent was still going', calls.includes('ask'));
		release();
		await settle();
	}

	// ---- 4. an agent task with no runner configured says so instead of doing nothing
	{
		const dir = path.join(tmp, 'norunner');
		fs.mkdirSync(dir, { recursive: true });
		const notes = [];
		const calls = [];
		const eng = new A.AutomationEngine(mkStore(dir),
			async () => { calls.push(1); return 'A'; },
			(t, b, e) => notes.push({ t, b, e: !!e }));
		await seed(dir, [{ id: 1, name: 'agent job', prompt: 'x', kind: 'agent', workflowId: 'wf-a', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		check('no-runner: it did not silently fall back to a single call', calls.length === 0);
		check('no-runner: the user was told why', notes.some(n => /未就绪/.test(n.b) && n.e === true));
	}

	// ---- 5. lastRun advances even when the run is slow or the app would die
	// Otherwise a job killed mid-run re-fires on next launch and burns the quota twice.
	{
		const dir = path.join(tmp, 'advance');
		fs.mkdirSync(dir, { recursive: true });
		const eng = new A.AutomationEngine(mkStore(dir), async () => 'A', () => { }, {
			resolve: () => W.demoWorkflow('wf-a', 't'),
			sessionFor: async () => 's',
			run: async () => new Promise(() => { }),      // never resolves — simulates a kill
			nextRunId: () => 'r',
		});
		await seed(dir, [{ id: 1, name: 'hangs', prompt: 'x', kind: 'agent', workflowId: 'wf-a', intervalMinutes: 60, enabled: true, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		const after = (await eng.list())[0];
		check('advance: lastRun advanced BEFORE the run could finish', after.lastRun > 0);
		check('advance: so the persisted file already has it',
			JSON.parse(fs.readFileSync(path.join(dir, 'automations.json'), 'utf8'))[0].lastRun > 0);
	}

	// ---- 6. an unknown kind falls back to 'ask' rather than dispatching agents
	{
		const dir = path.join(tmp, 'unknown');
		fs.mkdirSync(dir, { recursive: true });
		const calls = [];
		const eng = new A.AutomationEngine(mkStore(dir),
			async () => { calls.push(1); return 'A'; }, () => { },
			{ resolve: () => W.demoWorkflow('wf-a', 't'), sessionFor: async () => 's', run: async () => ({ entries: [] }), nextRunId: () => 'r' });
		await seed(dir, [{ id: 1, name: 'weird', prompt: 'x', kind: 'nonsense', intervalMinutes: 1, enabled: true, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		check('unknown-kind: it ran as a plain ask, not as an agent task', calls.length === 1);
	}

	// ---- 7. lastRun persistence round-trip includes the new fields
	{
		const dir = path.join(tmp, 'persist');
		fs.mkdirSync(dir, { recursive: true });
		const eng = new A.AutomationEngine(mkStore(dir), async () => 'A', () => { });
		const created = await eng.create({
			name: '研究', prompt: 'x', kind: 'agent', workflowId: 'wf-a',
			taskText: '研究 X', timeoutMinutes: 30, intervalMinutes: 60,
		});
		const reread = (await eng.list()).find(a => a.id === created.id);
		check('persist: kind survives the round trip', reread.kind === 'agent');
		check('persist: workflowId survives', reread.workflowId === 'wf-a');
		check('persist: the task text survives', reread.taskText === '研究 X');
		check('persist: the timeout survives', reread.timeoutMinutes === 30);
		// A legacy file with no kind at all must still load.
		await seed(dir, [{ id: 9, name: 'old', prompt: 'x', enabled: true, lastRun: 0, createdAt: 1 }]);
		const legacy = (await eng.list()).find(a => a.id === 9);
		check('persist: a legacy record with no kind loads fine', legacy && legacy.kind === undefined);
	}

	// ---- 8. disabled automations still do not fire
	{
		const dir = path.join(tmp, 'off');
		fs.mkdirSync(dir, { recursive: true });
		const calls = [];
		const eng = new A.AutomationEngine(mkStore(dir),
			async () => { calls.push(1); return 'A'; }, () => { },
			{ resolve: () => W.demoWorkflow('wf-a', 't'), sessionFor: async () => 's', run: async () => ({ entries: [] }), nextRunId: () => 'r' });
		await seed(dir, [{ id: 1, name: 'off', prompt: 'x', kind: 'agent', workflowId: 'wf-a', intervalMinutes: 1, enabled: false, lastRun: 0, createdAt: 1 }]);
		await eng.sweep();
		check('disabled: a disabled agent task does not fire', calls.length === 0);
		check('disabled: and its lastRun did not advance', (await eng.list())[0].lastRun === 0);
	}

	fs.rmSync(tmp, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});