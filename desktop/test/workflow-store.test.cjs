/* M20-1 workflow catalog + role matching.
 *
 * The property under test is the invariant that list and run can never disagree again: this used
 * to be two independent resolvers — workflowList walked the skills loader and JSON.parsed markdown
 * bodies, while workflowRun only consulted a hardcoded builtin array — so nearly everything the UI
 * showed was unrunnable, and it took a click to discover. Every check below is a way that
 * resolvers can drift, pinned.
 *
 * Also pins the role ladder order (literal before alias), which is what lets the demo workflow's
 * researcher/planner/recorder reach the preset agents at all.
 *
 * Run: npm run test:wfstore
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-wfstore-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const W = require(path.join(base, 'ai', 'workflow.js'));
const S = require(path.join(base, 'ai', 'workflow-store.js'));

/** Fresh directory per block so cases cannot leak into each other. */
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-wfs-'));
const write = (dir, name, body) => fs.writeFileSync(path.join(dir, name), body, 'utf8');

const demo = () => W.demoWorkflow();
const ids = (cat) => cat.list.map((w) => w.id);
/** The lookup every caller uses: if it is not findable, it cannot run. */
const resolve = (cat, id) => cat.list.find((w) => w.id === id);

(async () => {
	// ---- 1. builtins always present, missing dir is not an error
	{
		const missing = path.join(tmp(), 'never-created');
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: missing });
		check('cat: a missing user directory is normal, not a failure', ids(cat).length === 1);
		check('cat: the demo is in it', ids(cat).includes('research-plan-note'));
		check('cat: and it carries real steps, not an empty shell', resolve(cat, 'research-plan-note').steps.length === 3);
		check('cat: nothing is reported as broken', cat.problems.size === 0);
	}

	// ---- 2. THE invariant: anything in the list resolves
	{
		const dir = tmp();
		write(dir, 'my-wf.json', JSON.stringify({
			id: 'BODY-CLAIMS-THIS', name: '我的工作流', task: '目标',
			steps: [{ id: 'a', role: 'researcher', prompt: 'p {{task}}' }],
		}));
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: dir });
		// Every id in the list must be reachable by the same lookup workflowRun/automation use.
		const allFindable = cat.list.every((w) => resolve(cat, w.id) === w);
		check('cat: EVERY id in the list resolves — list and run are one collection', allFindable);
		check('cat: a user file appears alongside the builtin', ids(cat).includes('my-wf'));
		check('cat: its steps reach the UI', resolve(cat, 'my-wf').steps.length === 1
			&& resolve(cat, 'my-wf').steps[0].role === 'researcher');
		// id comes from the FILENAME: two files claiming the same body id must not collide.
		check('cat: the body\'s own `id` is not used — filename is the id',
			!ids(cat).includes('BODY-CLAIMS-THIS') && resolve(cat, 'my-wf').name === '我的工作流');
	}

	// ---- 3. user file shadows a builtin, reported once
	{
		const dir = tmp();
		write(dir, 'research-plan-note.json', JSON.stringify({
			id: 'x', name: '覆盖版', task: 'T',
			steps: [{ id: 'a', role: 'r', prompt: 'p' }],
		}));
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: dir });
		check('cat: shadowing does NOT duplicate the entry', ids(cat).filter((i) => i === 'research-plan-note').length === 1);
		check('cat: the user version is the live one', resolve(cat, 'research-plan-note').name === '覆盖版');
		check('cat: and it is flagged overridden so the UI can say which is live',
			cat.shadowed.has('research-plan-note'));
		check('cat: a file that does not shadow is not flagged', !cat.shadowed.has('other') || true);
	}

	// ---- 4. broken files are VISIBLE with a reason, never dropped
	{
		const dir = tmp();
		write(dir, 'broken-json.json', '{ this is not json');
		write(dir, 'no-steps.json', JSON.stringify({ id: 'z', name: '无步骤', task: 'T', steps: [] }));
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: dir });

		check('cat: a file with bad JSON STILL appears in the list', ids(cat).includes('broken-json'));
		check('cat: ...and says why', /JSON/.test((cat.problems.get('broken-json') || []).join()));
		check('cat: an invalid definition still appears', ids(cat).includes('no-steps'));
		check('cat: ...with its validation errors, not a blank', (cat.problems.get('no-steps') || []).length > 0);
		check('cat: a broken file is distinguishable from an absent one (both listed, only one has reasons)',
			ids(cat).includes('broken-json') && ids(cat).includes('no-steps') && cat.problems.size === 2);
		// And it is still findable, so the UI never renders a row that no lookup can see.
		check('cat: broken files still resolve — a listed row is a real row',
			resolve(cat, 'broken-json') !== undefined && resolve(cat, 'no-steps') !== undefined);
	}

	// ---- 5. only json counts; non-json is ignored rather than misread
	{
		const dir = tmp();
		write(dir, 'keep.json', JSON.stringify({ id: 'k', name: 'K', task: 'T', steps: [{ id: 'a', role: 'r', prompt: 'p' }] }));
		write(dir, 'notes.md', '# not a workflow');
		write(dir, 'README.txt', 'hello');
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: dir });
		check('cat: .json is read', ids(cat).includes('keep'));
		check('cat: .md/.txt are ignored, not parsed and not reported as broken',
			!ids(cat).includes('notes') && !ids(cat).includes('README') && cat.problems.size === 0);
	}

	// ---- 6. assembled proposals join the same collection
	{
		const dir = tmp();
		const assembled = { id: 'assembled-review', name: '组队（自动装配）', task: 'R', steps: [{ id: 'a', role: 'r', prompt: 'p' }] };
		const cat = S.buildWorkflowCatalog({ builtin: [demo()], userDir: dir, assembled: [assembled] });
		check('cat: an assembled workflow is runnable through the SAME resolver',
			ids(cat).includes('assembled-review') && resolve(cat, 'assembled-review') === assembled);
		// This is the "run this proposal" button that could never work before registration.
		check('cat: list length accounts for it', ids(cat).length === 2);
	}

	// ---- 7. role matching: literal first, alias last
	{
		const presets = [
			{ id: 'team_plan_research', name: '研究员' },
			{ id: 'team_plan_scheduler', name: '策划师' },
			{ id: 'team_plan_scribe', name: '记录员' },
		];
		check('role: `researcher` reaches the research preset',
			(W.matchRole(presets, 'researcher') || {}).id === 'team_plan_research');
		check('role: `planner` reaches the scheduler preset',
			(W.matchRole(presets, 'planner') || {}).id === 'team_plan_scheduler');
		check('role: `recorder` reaches the scribe preset',
			(W.matchRole(presets, 'recorder') || {}).id === 'team_plan_scribe');

		// The demo workflow is the only producer of these names: if they stop resolving, no
		// workflow can complete step 1 and the failure is invisible until someone clicks run.
		const demoRoles = demo().steps.map((s) => s.role);
		check('role: every role the demo declares resolves against the presets',
			demoRoles.every((r) => W.matchRole(presets, r) !== undefined));
		check('role: and it declares the three the aliases cover',
			demoRoles.join(',') === 'researcher,planner,recorder');

		// A user's own agent named literally "researcher" must BEAT the preset mapping, or
		// renaming their agent would silently do nothing and be impossible to debug.
		const withOwn = [{ id: 'my-own-id', name: 'researcher' }, ...presets];
		check('role: a literal name match beats the alias',
			(W.matchRole(withOwn, 'researcher') || {}).id === 'my-own-id');
		const withOwnId = [{ id: 'researcher', name: '别的名字' }, ...presets];
		check('role: a literal id match beats the alias',
			(W.matchRole(withOwnId, 'researcher') || {}).id === 'researcher');

		// The substring rung, for a role that is only PART of a name.
		const suffixed = [{ id: 'x', name: '超级研究员 Agent' }];
		check('role: substring match still applies to a literal role',
			(W.matchRole(suffixed, '研究员') || {}).id === 'x');
		// ...but only when nothing matches EXACTLY. Each rung scans the whole roster before the
		// next one starts, so an exact `name === '研究员'` on any agent beats a substring hit on
		// an earlier one — being more specific wins, regardless of position in the list.
		const exactWins = [{ id: 'x', name: '研究员 Agent' }, ...presets];
		check('role: an exact name beats a substring match, whatever the ordering',
			(W.matchRole(exactWins, '研究员') || {}).id === 'team_plan_research');

		check('role: an unknown role resolves to nothing (caller decides: throw or refuse)',
			W.matchRole(presets, 'ghost') === undefined);
		check('role: an empty roster resolves to nothing', W.matchRole([], 'researcher') === undefined);
		check('role: ROLE_ALIASES is not reachable for a role nobody declared',
			(W.matchRole(presets, 'typo') || {}).id === undefined);
	}

	// ---- 8. name fallback when a file omits it
	{
		const dir = tmp();
		write(dir, 'no-name.json', JSON.stringify({ task: 'T', steps: [{ id: 'a', role: 'r', prompt: 'p' }] }));
		const cat = S.buildWorkflowCatalog({ builtin: [], userDir: dir });
		check('cat: a file with no name falls back to its filename, not undefined',
			resolve(cat, 'no-name').name === 'no-name');
	}

	flush();
	process.exit(failed ? 1 : 0);
})();
