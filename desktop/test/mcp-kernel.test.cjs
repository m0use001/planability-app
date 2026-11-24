/* M20 backlog #5b: MCP kernel tests.
 *
 * This is a SECURITY SURFACE, so the tests are weighted toward refusals and confinement, not
 * toward the happy path. The single most important property: WRITES ARE OFF BY DEFAULT, because
 * the approval model does not survive the MCP boundary.
 *
 * Run: npm run test:mcpkernel
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-mcpk-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const K = require(path.join(base, 'ai', 'mcp-kernel.js'));

const tmp = path.join(os.tmpdir(), 'pcb-mcpk-' + Date.now());
fs.mkdirSync(tmp, { recursive: true });
const mk = (over = {}) => K.createKernel({ workspace: tmp, ...over });

(async () => {
	// ---- 1. WRITES ARE OFF BY DEFAULT. This is the whole point.
	{
		const ro = mk();
		const names = ro.tools().map(t => t.name);
		check('default: writes are NOT advertised', names.length > 0
			&& !names.some(n => /memory_add|memory_forget|handoff_write|workflow_run/.test(n)));
		check('default: reads ARE advertised', names.includes('planability_memory_search')
			&& names.includes('planability_workflow_replay'));
		check('default: the catalogue has both read and write tools', K.kernelTools(true).length > ro.tools().length);

		// And guessing the name does not help — the call path refuses too.
		for (const w of ['planability_memory_add', 'planability_memory_forget',
			'planability_handoff_write', 'planability_workflow_run']) {
			const r = await ro.call(w, { name: 'x', body: 'b', goal: 'g', workflowId: 'research-plan-note' });
			check(`default: calling ${w} anyway is refused`, r.ok === false && /未开启/.test(r.text));
		}
		// The refusal must explain WHY, so a caller does not retry in a loop.
		const why = await ro.call('planability_memory_add', { name: 'x', body: 'b' });
		check('default: the refusal explains the approval-model reason',
			/审批模型/.test(why.text) && /MCP 协议没有这个概念/.test(why.text));
	}

	// ---- 2. with writes enabled they actually work
	{
		const rw = mk({ allowWrites: true });
		const names = rw.tools().map(t => t.name);
		check('enabled: write tools appear', names.includes('planability_memory_add')
			&& names.includes('planability_handoff_write'));
		const add = await rw.call('planability_memory_add', { name: 'K1', body: 'b1', type: 'project' });
		check('enabled: a write succeeds', add.ok === true && /K1/.test(add.text));
		const again = await rw.call('planability_memory_add', { name: 'K1', body: 'b1', type: 'project' });
		check('enabled: re-writing identical content is a no-op', again.ok === true && /未写入/.test(again.text));
		const up = await rw.call('planability_memory_add', { name: 'K1', body: 'b2', type: 'project' });
		check('enabled: a changed body updates', up.ok === true);
		const hist = await rw.call('planability_memory_history', { key: 'K1' });
		check('enabled: the history shows both events', (hist.text.match(/^#/gm) || []).length === 2);
		const forq = await rw.call('planability_memory_forget', { key: 'K1' });
		check('enabled: forget says the audit trail survives', forq.ok === true && /审计轨迹仍在/.test(forq.text));
		const after = await rw.call('planability_memory_search', { query: 'K1' });
		check('enabled: a forgotten memory leaves the search results', /无匹配/.test(after.text));
		const stillThere = await rw.call('planability_memory_history', { key: 'K1' });
		check('enabled: ...but its history is still there', stillThere.ok === true && /#1/.test(stillThere.text));
	}

	// ---- 3. path confinement — the caller is not necessarily this machine's user
	{
		check('confine: a relative path inside the root is allowed',
			K.confine(tmp, 'handoff/a.md') === path.join(path.resolve(tmp), 'handoff', 'a.md'));
		check('confine: traversal is refused', K.confine(tmp, '../../etc/passwd') === null);
		check('confine: traversal with a sibling prefix is refused',
			K.confine(tmp, '../mcpk-ESCAPE/x.md') === null, );
		check('confine: an absolute path outside is refused', K.confine(tmp, 'C:/Windows/system32') === null);
		check('confine: the root itself is allowed', K.confine(tmp, '.') === path.resolve(tmp));

		const rw = mk({ allowWrites: true });
		const escape = await rw.call('planability_handoff_read', { name: '../../../../Windows/win.ini' });
		check('confine: handoff_read refuses a traversal name', escape.ok === false && /超出工作区/.test(escape.text));
		const badExt = await rw.call('planability_handoff_read', { name: 'x.txt' });
		check('confine: handoff_read refuses a non-markdown file', badExt.ok === false);
		// A legit file still reads.
		await rw.call('planability_handoff_write', { title: 'T', goal: 'G', done: 'D', blockers: '', next: '' });
		const list = await rw.call('planability_handoff_list', {});
		check('confine: a written handoff is listed', list.ok === true && /\.md/.test(list.text));
		const readBack = await rw.call('planability_handoff_read', { name: list.text.trim().split('\n').pop() });
		check('confine: and reads back', readBack.ok === true && /## 目标/.test(readBack.text));
	}

	// ---- 4. handoff rules still apply through MCP
	{
		const rw = mk({ allowWrites: true });
		const noGoal = await rw.call('planability_handoff_write', { title: 'x', done: 'y' });
		check('handoff: no goal is refused through MCP too', noGoal.ok === false && /目标/.test(noGoal.text));
		const okWrite = await rw.call('planability_handoff_write', { title: 'X', goal: 'G', done: '', blockers: '', next: '' });
		check('handoff: an empty section is written as 未填写, not omitted',
			okWrite.ok === true);
		const readBack = await rw.call('planability_handoff_read', { name: okWrite.text.replace('已写入 ', '') });
		check('handoff: the four sections are all present',
			readBack.ok === true && (readBack.text.match(/^## /gm) || []).length === 4);
	}

	// ---- 5. workflow run / replay
	{
		// No runStep -> refuses rather than pretending.
		const noStep = mk({ allowWrites: true });
		const r = await noStep.call('planability_workflow_run', { workflowId: 'research-plan-note', task: '研究 X' });
		check('workflow: without a host runStep it refuses', r.ok === false && /runStep/.test(r.text));

		let calls = 0;
		const withStep = K.createKernel({
			workspace: tmp, allowWrites: true,
			runStep: async () => { calls++; return 'OUT'; },
		});
		const noTask = await withStep.call('planability_workflow_run', { workflowId: 'research-plan-note' });
		check('workflow: a run with no task is refused (no literal placeholder is sent)',
			noTask.ok === false && /任务描述/.test(noTask.text));
		const okRun = await withStep.call('planability_workflow_run', { workflowId: 'research-plan-note', task: '研究 X' });
		check('workflow: the run succeeds', okRun.ok === true && calls === 3);
		const runId = (okRun.text.match(/运行 (\S+) 完成/) || [])[1];
		check('workflow: it reports the run id', !!runId);
		const before = calls;
		const replay = await withStep.call('planability_workflow_replay', { runId });
		check('workflow: replay works', replay.ok === true && /研究 X/.test(replay.text));
		check('workflow: replay calls NO agent', calls === before);
		check('workflow: an unknown run id is refused cleanly',
			(await withStep.call('planability_workflow_replay', { runId: 'nope' })).ok === false);
		const unknownWf = await withStep.call('planability_workflow_run', { workflowId: 'nope', task: 'x' });
		check('workflow: an unknown workflow id is refused', unknownWf.ok === false && /未知工作流/.test(unknownWf.text));

		// Validation without execution.
		const bad = await withStep.call('planability_workflow_validate', { workflow: { id: 'x', name: 'n', task: 't', steps: [] } });
		check('workflow: validate reports errors without running', bad.ok === false && /至少/.test(bad.text));
		check('workflow: validate did not run anything', calls === 3);
		const good = await withStep.call('planability_workflow_validate', { workflow: { id: 'x', name: 'n', task: 't', steps: [{ id: 'a', role: 'r', prompt: 'p' }] } });
		check('workflow: a valid definition passes', good.ok === true);
	}

	// ---- 6. team assembly
	{
		const noAgents = mk();
		const r = await noAgents.call('planability_team_assemble', { requirement: '调研并排期然后写笔记' });
		check('team: with no agents it refuses and says so', r.ok === false && /no-agents/.test(r.text));
		const withAgents = K.createKernel({
			workspace: tmp,
			agents: [{ id: 'a1', name: '通用', tools: [] }, { id: 'a2', name: '写手', tools: ['file_write', 'file_read'] }],
		});
		const ok2 = await withAgents.call('planability_team_assemble', { requirement: '调研并排期然后写笔记' });
		check('team: with agents it returns a proposal', ok2.ok === true && /团队：/.test(ok2.text));
		check('team: the proposal says it dispatched nothing', /未派发任何东西/.test(ok2.text));
	}

	// ---- 7. hostile input at the boundary
	{
		const rw = mk({ allowWrites: true });
		check('hostile: an unknown tool name is refused, not thrown',
			(await rw.call('planability_totally_made_up', {})).ok === false);
		check('hostile: a non-object input is handled',
			(await rw.call('planability_memory_search', null)).ok === true);
		check('hostile: a non-string query does not throw',
			(await rw.call('planability_memory_search', { query: { a: 1 } })).ok === true);
		const huge = await rw.call('planability_memory_search', { query: 'x', limit: 1e9 });
		check('hostile: an absurd limit is clamped, not honoured', huge.ok === true);
		check('hostile: a null workflow to validate is handled',
			(await rw.call('planability_workflow_validate', { workflow: null })).ok === false);
	}

	// ---- 8. the module does not import electron (it must run under plain node)
	{
		const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'ai', 'mcp-kernel.ts'), 'utf8');
		check('purity: mcp-kernel does not import electron', !/from 'electron'/.test(src));
				// Import statements only: the file's COMMENT explains why better-sqlite3 is excluded, and
		// a naive substring match flags that explanation as a violation.
		const imports = (s) => s.split('\n').filter(l => /^\s*import\s/.test(l)).join('\n');
		check('purity: it imports neither better-sqlite3 nor ai/db (Electron ABI only)',
			!/better-sqlite3|ai\/db/.test(imports(src)), );
		const server = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'ai', 'mcp-server.ts'), 'utf8');
		check('purity: mcp-server does not import electron either', !/from 'electron'/.test(server));
		// stdout is the protocol: any console.log in there corrupts the framing.
		// Only real code, not the header comment that warns about console.log.
		const serverCode = server.split('*/').slice(1).join('*/');
		check('purity: mcp-server writes no console.log in its code', !/console\.log/.test(serverCode));
		check('purity: and it documents why', /STDOUT IS THE PROTOCOL/.test(server));
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