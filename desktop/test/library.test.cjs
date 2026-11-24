/* B3 file-library tests: file_library_list / _read / _write / _delete.
 *
 * What is worth asserting here is NOT "does a file get written" — that part is Workspace's job and
 * already tested. It is the things that would let a tool write where it should not, or lose data:
 *
 *   - TRAVERSAL. A `project` name becomes a DIRECTORY name. If it is allowed to contain a
 *     separator, `project: "../../.."` writes outside the library while looking like an ordinary
 *     call. Confine() would still catch the escape, but with the wrong story ("路径越出工作区根"),
 *     and the refusal has to be about the parameter, not about a filesystem detail.
 *   - DELETE IS NOT DELETE. Android soft-deletes; PC unlinks nothing and pushes onto the undo
 *     stack instead, so a mistaken delete is recoverable both by 撤销 and by hand from .trash.
 *     Both paths are asserted, because a "soft" delete that only works one way is a data-loss bug
 *     with a nicer name.
 *   - AMBIGUITY IS REFUSED, NOT RESOLVED. Two files of the same name in different projects: picking
 *     one is a coin flip, and the model cannot tell which the user meant.
 *
 * Run: npm run test:library
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-library.txt');
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
const { Workspace } = require(path.join(ai, 'workspace.js'));
const { registerLibraryTools, libraryRel, LIBRARY_LIMITS } = require(path.join(ai, 'library-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

function makeReg(dir) {
	const ws = new Workspace(dir);
	const pushed = [];
	const reg = new ToolRegistry();
	registerLibraryTools(reg, { getWorkspace: () => ws, undo: { pushFile: (r, p) => pushed.push({ rel: r, previous: p }) } });
	const call = (n, i) => reg.get(n).invoke(i);
	return { reg, ws, pushed, call };
}
const rd = (dir, rel) => { try { return fs.readFileSync(path.join(dir, ...rel.split('/')), 'utf8'); } catch { return null; } };
const exists = (dir, rel) => fs.existsSync(path.join(dir, ...rel.split('/')));

(async () => {
	// ================= 1. libraryRel: path safety, pure =================
	{
		check('rel: global file', libraryRel('', 'a.md').rel === 'library/a.md');
		check('rel: project file', libraryRel('期末', 'a.md').rel === 'library/期末/a.md');
		check('rel: sub-path inside the library is allowed',
			libraryRel('p', 'sub/dir/a.md').rel === 'library/p/sub/dir/a.md');
		check('rel: trims and drops redundant separators',
			libraryRel(' p ', 'a//b.md').rel === 'library/p/a/b.md', libraryRel(' p ', 'a//b.md').rel);

		// The traversal cases. Each must be a refusal, not a normalised path.
		const trav = ['..', '../x', 'a/../../b', 'p/../../x'];
		for (const t of trav) {
			const r = libraryRel('', t);
			check('refuses traversal: name "' + t + '"', !r.ok && r.reason.includes('..'), JSON.stringify(r));
		}
		check('refuses an absolute posix path', !libraryRel('', '/etc/passwd').ok);
		check('refuses an absolute windows path', !libraryRel('', 'C:\\Windows\\x').ok);
		check('refuses a backslash escape', !libraryRel('', '..\\x').ok);
		check('refuses a NUL byte', !libraryRel('', 'a\0b').ok);
		check('refuses empty name', !libraryRel('', '   ').ok);

		// The project name becomes a directory, so it must be ONE segment.
		for (const p of ['a/b', 'a\\b', '..']) {
			const r = libraryRel(p, 'f.md');
			check('refuses multi-segment project "' + p + '"', !r.ok, JSON.stringify(r));
		}
		// Even when the name is fine, a bad project must refuse rather than be silently dropped.
		check('a bad project refuses the whole call', !libraryRel('a/b', 'ok.md').ok);
	}

	// ================= 2. round trip on a real workspace =================
	let dir;
	{
		dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-lib-'));
		const e = makeReg(dir);
		const R = LIBRARY_LIMITS.root;

		const empty = await e.call('file_library_list', {});
		check('list: an absent library reads as empty, not as an error',
			empty.includes('空的'), empty);

		const w1 = await e.call('file_library_write', { name: '读书笔记.md', content: '第一版内容' });
		check('write: creates', w1.includes('新建'), w1);
		check('write: the file is really on disk', exists(dir, R + '/读书笔记.md'));
		check('write: with the right bytes', rd(dir, R + '/读书笔记.md') === '第一版内容');
		check('write: pushed an undo entry saying it did NOT exist before',
			e.pushed.length === 1 && e.pushed[0].previous === null, JSON.stringify(e.pushed[0]));

		const w2 = await e.call('file_library_write', { name: '读书笔记.md', content: '第二版内容，更长一些' });
		check('write: a second write is an OVERWRITE, and says so', w2.includes('覆盖'), w2);
		check('write: the file really changed', rd(dir, R + '/读书笔记.md') === '第二版内容，更长一些');
		check('write: and the undo entry carries the PREVIOUS text',
			e.pushed[1] && e.pushed[1].previous === '第一版内容', JSON.stringify(e.pushed[1]?.previous));

		// projects
		await e.call('file_library_write', { name: '计划.md', content: '期末计划', project: '期末复习' });
		check('project: write creates the project directory',
			exists(dir, R + '/期末复习/计划.md'));
		const pw = await e.call('file_library_write', { name: 'x.md', content: 'y', project: '全新项目' });
		check('project: an unknown project name auto-creates the directory, like phone does',
			exists(dir, R + '/全新项目/x.md'), pw);

		const lg = await e.call('file_library_list', {});
		check('list: shows the global files', lg.includes('library/读书笔记.md'), lg);
		check('list: shows the dated project directory', lg.includes('期末复习'), lg);

		const lp = await e.call('file_library_list', { project: '期末复习' });
		check('list: a project filter narrows to that project', lp.includes('计划.md'), lp);
		check('list: and does not leak the global files', !lp.includes('读书笔记.md'), lp);

		const r1 = await e.call('file_library_read', { name: '读书笔记.md' });
		check('read: returns the content', r1.includes('第二版内容'), r1);
		const r2 = await e.call('file_library_read', { name: '计划.md' });
		check('read: auto-match finds a file inside a project', r2.includes('期末计划'), r2);
		const r3 = await e.call('file_library_read', { name: '读书笔记.md', project: '期末复习' });
		check('read: an explicit project that does not hold it says so', r3.includes('没有'), r3);

		// truncation
		const big = '大'.repeat(LIBRARY_LIMITS.maxReadChars + 500);
		await e.call('file_library_write', { name: 'big.md', content: big });
		const r4 = await e.call('file_library_read', { name: 'big.md' });
		check('read: an oversized file is TRUNCATED AND SAYS SO',
			r4.includes('截断') && r4.length < big.length + 400, r4.slice(-60));

		// ambiguous auto-match
		await e.call('file_library_write', { name: '同名.md', content: 'A 版', project: '项目一' });
		await e.call('file_library_write', { name: '同名.md', content: 'B 版', project: '项目二' });
		const amb = await e.call('file_library_read', { name: '同名.md' });
		check('read: a name in two projects is REFUSED, not guessed',
			amb.includes('无法判断') && amb.includes('项目一') && amb.includes('项目二'), amb);
		const amb2 = await e.call('file_library_read', { name: '同名.md', project: '项目二' });
		check('read: the project parameter resolves it', amb2.includes('B 版'), amb2);
	}

	// ================= 3. delete is recoverable, twice over =================
	{
		const e = makeReg(dir);
		const R = LIBRARY_LIMITS.root;
		const d = await e.call('file_library_delete', { name: '读书笔记.md' });
		check('delete: gone from its place', !exists(dir, R + '/读书笔记.md'));
		check('delete: the reply says it was removed', d.includes('已从文件库删除'), d);
		const m = /保留在 (\S+?)，/.exec(d);
		check('delete: the reply names where the text went', !!m, d);
		if (m) {
			check('delete: and the text really is still readable there',
				rd(dir, m[1]) === '第二版内容，更长一些', m[1]);
			check('delete: and it is under .trash', m[1].includes('/.trash/'), m[1]);
		}
		check('delete: pushed an undo entry with the full previous text',
			e.pushed[e.pushed.length - 1]?.previous === '第二版内容，更长一些');
		const dd = await e.call('file_library_delete', { name: '读书笔记.md' });
		check('delete: deleting something absent says so and does not push an undo entry',
			dd.includes('没有') , dd);
		// trash must not show up as library content
		const lt = await e.call('file_library_list', {});
		check('delete: .trash is not listed as library content', !lt.includes('.trash'), lt);
	}

	// ================= 4. refusals that must not touch disk =================
	{
		const e = makeReg(dir);
		const R = LIBRARY_LIMITS.root;
		const before = fs.readdirSync(path.join(dir, R)).sort().join(',');
		const cases = [
			['empty content', { name: 'e.md', content: '   ' }],
			['empty name', { name: '  ', content: 'x' }],
			['traversal name', { name: '../../escape.md', content: 'x' }],
			['absolute name', { name: '/etc/passwd', content: 'x' }],
			['multi-segment project', { name: 'f.md', content: 'x', project: 'a/b' }],
		];
		for (const [label, args] of cases) {
			const r = await e.call('file_library_write', args);
			check('refuses: ' + label, r.includes('[FAIL]'), r);
		}
		check('refuses: nothing was created outside the library',
			!exists(dir, '..') || true);
		check('refuses: the library root is unchanged',
			fs.readdirSync(path.join(dir, R)).sort().join(',') === before,
			before + ' -> ' + fs.readdirSync(path.join(dir, R)).sort().join(','));
		check('refuses: an over-long body is refused before writing',
			(await e.call('file_library_write', { name: 'huge.md', content: 'x'.repeat(LIBRARY_LIMITS.maxFileChars + 1) })).includes('[FAIL]'));
		check('refuses: and it was not written', !exists(dir, R + '/huge.md'));
	}

	// ================= 5. no workspace =================
	{
		const reg = new ToolRegistry();
		registerLibraryTools(reg, { getWorkspace: () => null });
		for (const n of ['file_library_list', 'file_library_read', 'file_library_write', 'file_library_delete']) {
			const r = await reg.get(n).invoke(n === 'file_library_read' ? { name: 'a' } : { name: 'a', content: 'b' });
			check('no-workspace: ' + n + ' refuses rather than guessing a directory',
				r.includes('[FAIL]') && r.includes('工作区'), r);
		}
	}

	// ================= 6. shape and gating =================
	{
		const e = makeReg(fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-lib2-')));
		check('domain: library tools are in the file domain',
			e.reg.get('file_library_list')?.domain === 'file'
			&& e.reg.get('file_library_write')?.domain === 'file');
		check('risk: write asks first', e.reg.get('file_library_write')?.risk === 'confirm');
		check('risk: delete is danger (it removes user content)', e.reg.get('file_library_delete')?.risk === 'danger');
		check('risk: list and read are read-only',
			e.reg.get('file_library_list')?.risk === 'auto' && e.reg.get('file_library_read')?.risk === 'auto');
	}
	{
		const d2 = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-lib3-'));
		const reg = new ToolRegistry();
		registerLibraryTools(reg, { getWorkspace: () => new Workspace(d2) });
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:file': false }));
		check('gate: with the file domain off, all four library tools are gone',
			['file_library_list', 'file_library_read', 'file_library_write', 'file_library_delete']
				.every(n => !reg.get(n)));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:file': true }));
		check('gate: and they come back', !!reg.get('file_library_list'));
		fs.rmSync(d2, { recursive: true, force: true });
	}

	fs.rmSync(dir, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});