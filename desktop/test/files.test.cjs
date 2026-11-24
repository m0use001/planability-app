/* M18 file domain (read-only) tests.
 *
 * The security-critical surface is path confinement, so this suite is mostly ATTACKS:
 * ../ escapes, absolute paths, sibling-directory prefix tricks, symlink/junction escapes,
 * NUL injection, and the unconfigured-root case. A read tool that can be walked out of its
 * root is a data-exfiltration tool.
 *
 * Must run under Electron (part of the npm test chain): npm run test:files
 */
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-files-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { Workspace, LIMITS } = require(path.join(base, 'workspace.js'));
const { registerFileTools } = require(path.join(base, 'file-tools.js'));

(async () => {
	// ---- fixture: a workspace plus a "secret" area OUTSIDE it
	const root = path.join(os.tmpdir(), `pcb-ws-${Date.now()}`);
	const outside = path.join(os.tmpdir(), `pcb-outside-${Date.now()}`);
	fs.mkdirSync(path.join(root, 'src', 'deep'), { recursive: true });
	fs.mkdirSync(path.join(root, 'node_modules', 'pkg'), { recursive: true });
	fs.mkdirSync(outside, { recursive: true });
	fs.writeFileSync(path.join(root, 'src', 'a.ts'), 'export const x = 1;\nconst secretish = "findme";\n');
	fs.writeFileSync(path.join(root, 'src', 'deep', 'b.ts'), 'export const y = 2;\n');
	fs.writeFileSync(path.join(root, 'README.md'), '# 标题\nhello findme world\n');
	fs.writeFileSync(path.join(root, 'node_modules', 'pkg', 'index.js'), 'findme in node_modules\n');
	fs.writeFileSync(path.join(outside, 'secret.txt'), 'TOP_SECRET_VALUE\n');
	// a prefix-sibling directory that must NOT pass a naive startsWith check
	fs.mkdirSync(root + '-evil', { recursive: true });
	fs.writeFileSync(path.join(root + '-evil', 'loot.txt'), 'EVIL_VALUE\n');
	// a symlink pointing out of the tree (skip on Windows without permission)
	let symlinkMade = false;
	try {
		fs.symlinkSync(outside, path.join(root, 'escape'), 'junction');
		symlinkMade = true;
	} catch { /* no privilege on this host */ }

	const ws = new Workspace(root);

	// ---- 1. happy paths
	{
		check('confine: root itself is allowed', (await ws.confine('')).ok);
		check('confine: inside path allowed', (await ws.confine('src/a.ts')).ok);
		check('confine: leading ./ tolerated', (await ws.confine('./src/a.ts')).ok);
		check('confine: absolute-ish path inside root allowed', (await ws.confine('/src/a.ts')).ok);
		const l = await ws.list('src');
		check('list: returns entries', l.ok && l.entries.some(e => e.name === 'a.ts'));
		check('list: dirs sort first', l.ok && l.entries[0].kind === 'dir');
		check('list: skips node_modules', !(await ws.list('')).entries.some(e => e.name === 'node_modules'));
		const r = await ws.read('src/a.ts');
		check('read: returns content', r.ok && r.content.includes('export const x'));
		check('read: rel is workspace-relative', r.rel === 'src/a.ts');
	}

	// ---- 2. ATTACK: traversal out of the root
	{
		for (const evil of ['../', '../../', '..', 'src/../../outside/secret.txt', 'src/../../..']) {
			const c = await ws.confine(evil);
			check(`attack: refuse ${JSON.stringify(evil)}`, !c.ok && /越出|非法/.test(c.reason || ''));
		}
		check('attack: cannot read the outside secret',
			!(await ws.read('../' + path.basename(outside) + '/secret.txt')).ok);
	}

	// ---- 3. ATTACK: sibling directory that shares a name prefix
	{
		const c = await ws.confine('../' + path.basename(root + '-evil') + '/loot.txt');
		check('attack: prefix-sibling outside root refused', !c.ok);
		const r = await ws.read('../' + path.basename(root + '-evil') + '/loot.txt');
		check('attack: prefix-sibling file not readable', !r.ok && !(r.content || '').includes('EVIL_VALUE'));
	}

	// ---- 4. ATTACK: absolute path outside the root
	{
		const c = await ws.confine(path.join(outside, 'secret.txt'));
		check('attack: absolute outside path refused', !c.ok || c.abs.startsWith(root));
		const r = await ws.read(path.join(outside, 'secret.txt'));
		check('attack: absolute outside file not readable', !r.ok || !(r.content || '').includes('TOP_SECRET'));
	}

	// ---- 5. ATTACK: NUL byte
	{
		const c = await ws.confine('src/a.ts\0.txt');
		check('attack: NUL byte refused', !c.ok);
	}

	// ---- 6. ATTACK: symlink / junction escape
	if (symlinkMade) {
		const c = await ws.confine('escape/secret.txt');
		check('attack: symlink out of tree refused', !c.ok && /符号链接/.test(c.reason || ''));
		const r = await ws.read('escape/secret.txt');
		check('attack: symlinked secret not readable', !r.ok && !(r.content || '').includes('TOP_SECRET'));
	} else {
		check('attack: symlink test skipped (no privilege on host)', true);
	}

	// ---- 7. unconfigured root disables the domain (never widens)
	{
		const none = new Workspace('');
		check('unconfigured: reports not configured', none.configured === false);
		check('unconfigured: list refuses', !(await none.list('')).ok);
		check('unconfigured: read refuses', !(await none.read('x')).ok);
		check('unconfigured: search refuses', !(await none.search('x')).ok);
	}

	// ---- 8. search: works, respects skips and caps
	{
		const s = await ws.search('findme');
		check('search: finds across files', s.ok && s.hits.length >= 2);
		check('search: returns rel:line:text', s.ok && /src\/a\.ts:\d+:/.test(s.hits.map(h => `${h.rel}:${h.line}:`).join(' ')));
		check('search: does not descend into node_modules', !s.hits.some(h => h.rel.includes('node_modules')));
		const g = await ws.search('findme', { glob: '**/*.md' });
		check('search: glob filters to .md', g.ok && g.hits.every(h => h.rel.endsWith('.md')));
		const lim = await ws.search('e', { limit: 2 });
		check('search: honours limit', lim.hits.length <= 2);
		check('search: bad regex reports cleanly', !(await ws.search('([', { regex: true })).ok);
		check('search: literal metachars are escaped', (await ws.search('a.ts')).ok);
		check('search: empty query refused', !(await ws.search('  ')).ok);
	}

	// ---- 9. read: binary / oversized / directory handled without garbage
	{
		const binPath = path.join(root, 'bin.dat');
		fs.writeFileSync(binPath, Buffer.from([0x00, 0x01, 0x02, 0x00, 0xff]));
		const r = await ws.read('bin.dat');
		check('read: binary refused with a note', !r.ok && /二进制/.test(r.note || ''));
		const bigPath = path.join(root, 'big.txt');
		fs.writeFileSync(bigPath, 'x'.repeat(LIMITS.maxReadBytes + 10));
		const rb = await ws.read('big.txt');
		check('read: oversized refused with a note', !rb.ok && /超过单次读取上限/.test(rb.note || ''));
		const rd = await ws.read('src');
		check('read: directory refused with guidance', !rd.ok && /file_list/.test(rd.reason || ''));
		check('read: missing file refused', !(await ws.read('nope.txt')).ok);
	}

	// ---- 10. depth cap
	{
		const deep = await ws.list('', 99);
		check('list: depth is capped, not unbounded', deep.ok && deep.entries.length <= LIMITS.maxListEntries);
	}

	// ---- 11. tool surface: all read-only, and refuse cleanly with no root
	{
		const reg = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerFileTools(reg, () => ws);
		for (const n of ['file_list', 'file_read', 'file_search']) {
			check(`tool: ${n} registered`, reg.defs.has(n));
			check(`tool: ${n} is auto (no approval needed for reads)`, reg.defs.get(n).risk === 'auto');
		}
		check('tool: file_list returns listing', (await reg.defs.get('file_list').invoke({ path: 'src' })).includes('a.ts'));
		check('tool: file_read returns content', (await reg.defs.get('file_read').invoke({ path: 'src/a.ts' })).includes('export const x'));
		check('tool: file_search returns hits', (await reg.defs.get('file_search').invoke({ query: 'findme' })).includes('findme'));
		check('tool: traversal surfaces as FAIL, not a crash',
			(await reg.defs.get('file_read').invoke({ path: '../../etc/passwd' })).startsWith('[FAIL]'));

		// with no workspace configured, every tool must refuse
		const reg2 = { defs: new Map(), register(x) { this.defs.set(x.name, x); }, get(n) { return this.defs.get(n); } };
		registerFileTools(reg2, () => null);
		check('tool: file_list refuses when unconfigured', (await reg2.defs.get('file_list').invoke({})).includes('未配置'));
		check('tool: file_read refuses when unconfigured', (await reg2.defs.get('file_read').invoke({ path: 'a' })).includes('未配置'));
		check('tool: file_search refuses when unconfigured', (await reg2.defs.get('file_search').invoke({ query: 'x' })).includes('未配置'));
	}

	await fsp.rm(root, { recursive: true, force: true });
	await fsp.rm(outside, { recursive: true, force: true });
	await fsp.rm(root + '-evil', { recursive: true, force: true });

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});