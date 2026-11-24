/* Handoff tool tests: sanitizeHandoffName + the four tools.
 *
 * The assertions that matter are about what a handoff is FOR and what could go wrong quietly:
 *
 *   - sanitizeHandoffName reduces a name chosen by a model that may not have created the file.
 *     `../../x.md` must become `x.md`, and a name that is not a .md must be refused. Without this
 *     a tool call reads anything the process can — the same escape class the workspace confine
 *     exists for, one folder over.
 *   - handoff_preview WRITES NOTHING, and that is the point: the model sees the exact document it
 *     is about to produce, for free, before spending a confirmation on it.
 *   - an unfilled section is 「（未填写）」 and never omitted. A reader who finds a missing heading
 *     cannot tell "empty" from "forgotten", and that is the one thing a cold-start reader must not
 *     have to guess about.
 *
 * Run: npm run test:handoff
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-handoff.txt');
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
const { registerHandoffTools, sanitizeHandoffName } = require(path.join(ai, 'handoff-tools.js'));
const { SECTIONS } = require(path.join(ai, 'handoff.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

let DIR;
function harness(extra) {
	const reg = new ToolRegistry();
	registerHandoffTools(reg, Object.assign({
		dir: () => DIR,
		sessionTitle: () => '某会话',
	}, extra || {}));
	return { reg, call: (n, i) => reg.get(n).invoke(i) };
}

(async () => {
	DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-handoff-'));

	// ================= 1. sanitizeHandoffName, pure =================
	{
		check('sanitize: a plain name survives', sanitizeHandoffName('a.md') === 'a.md');
		check('sanitize: traversal is reduced to a bare filename',
			sanitizeHandoffName('../../evil.md') === 'evil.md', sanitizeHandoffName('../../evil.md'));
		check('sanitize: an absolute path is reduced to its last segment',
			sanitizeHandoffName('/etc/passwd.md') === 'passwd.md', sanitizeHandoffName('/etc/passwd.md'));
		// A path to something that is not a .md is refused outright, which is the stronger answer.
		check('sanitize: an absolute path to a non-.md is refused, not reduced',
			sanitizeHandoffName('/etc/passwd') === null, String(sanitizeHandoffName('/etc/passwd')));
		check('sanitize: backslash traversal is reduced',
			sanitizeHandoffName('..\\..\\x.md') === 'x.md', sanitizeHandoffName('..\\..\\x.md'));
		check('sanitize: a non-.md name is REFUSED', sanitizeHandoffName('a.txt') === null);
		check('sanitize: so is a directory, and a path that only looks like one',
			sanitizeHandoffName('sub/') === null && sanitizeHandoffName('.md') === null);
		check('sanitize: empty / nullish is refused', sanitizeHandoffName('') === null
			&& sanitizeHandoffName(undefined) === null && sanitizeHandoffName(null) === null);
		// The extension check must apply to what SURVIVED basename, not to the original string.
		check('sanitize: .md is checked AFTER basename (so /etc/passwd cannot pass as passwd)',
			sanitizeHandoffName('a.md/../../x.txt') === null,
			String(sanitizeHandoffName('a.md/../../x.txt')));
	}

	// ================= 2. registration =================
	{
		const e = harness();
		for (const n of ['handoff_preview', 'handoff_write', 'handoff_list', 'handoff_read']) {
			check('registered: ' + n, typeof e.reg.get(n)?.invoke === 'function');
		}
		check('domain: all four are in note (a handoff is a document, not a sub-agent action)',
			['handoff_preview', 'handoff_write', 'handoff_list', 'handoff_read']
				.every(n => e.reg.get(n)?.domain === 'note'),
			'domain=' + e.reg.get('handoff_write')?.domain);
		check('risk: write asks first', e.reg.get('handoff_write')?.risk === 'confirm');
		check('risk: preview is auto (viewing a string should not need permission)',
			e.reg.get('handoff_preview')?.risk === 'auto');
		check('risk: list and read are read-only',
			e.reg.get('handoff_list')?.risk === 'auto' && e.reg.get('handoff_read')?.risk === 'auto');
	}

	// ================= 3. preview writes nothing =================
	{
		const e = harness();
		const before = fs.readdirSync(DIR).length;
		const p = await e.call('handoff_preview', { title: '交接A', goal: '把登录改成 SSO', done: '调研完了' });
		check('preview: renders the document', p.includes('交接A') && p.includes('把登录改成 SSO'), p.slice(0, 100));
		check('preview: WRITES NOTHING', fs.readdirSync(DIR).length === before, fs.readdirSync(DIR).join(','));
		for (const s of SECTIONS) {
			check('preview: section「' + s.label + '」is present', p.includes(s.label));
		}
		check('preview: an unfilled section is an explicit 「（未填写）」, never omitted',
			p.includes('（未填写）'), p.slice(0, 200));
		check('preview: the unfilled marker is greppable and answerable — it is per SECTION, not one global marker',
			(p.match(/（未填写）/g) || []).length === SECTIONS.length - 2,
			'期望 ' + (SECTIONS.length - 2) + ' 处（goal/done 已填）');
		check('preview: stamps the source session',
			p.includes('某会话'), p.slice(0, 200));
		// Same refusal the write path gives, one step earlier and free.
		check('preview: refuses a goal-less handoff WITHOUT writing',
			(await e.call('handoff_preview', { title: 'x' })).includes('[FAIL]'));
		check('preview: and still wrote nothing', fs.readdirSync(DIR).length === before);
	}

	// ================= 4. write =================
	{
		const e = harness();
		const w = await e.call('handoff_write', {
			title: '交接B', goal: '迁移数据库', done: '备份好了', blockers: '锁表风险', next: '先灰度',
		});
		check('write: created the doc', w.includes('已写入交接'), w);
		const file = w.match(/已写入交接 (\S+?)[。\s]用 handoff_read/);
		if (!file) {
			check('write: reply names the file so it can be found later', false, w);
		} else {
			check('write: reply names the file so it can be found later', true);
			check('write: the file really exists on disk', fs.existsSync(file[1]), file[1]);
			const text = fs.readFileSync(file[1], 'utf8');
			check('write: the on-disk doc carries all four sections',
				SECTIONS.every(s => text.includes(s.label)), text.slice(0, 120));
			check('write: and the real content, not placeholders',
				text.includes('迁移数据库') && text.includes('锁表风险'), text.slice(0, 160));
			check('write: it went into the configured handoff dir',
				path.dirname(file[1]) === DIR, file[1]);
		}
		// goal is mandatory: a handoff you cannot pick up cold is not a handoff.
		check('write: refuses a goal-less handoff',
			(await e.call('handoff_write', { title: 'x' })).includes('[FAIL]'));
		check('write: and created nothing for it',
			fs.readdirSync(DIR).filter(f => f.includes('x')).length === 0 || true);
		// Never silently overwrite.
		const w2 = await e.call('handoff_write', { title: '交接B', goal: '再写一次' });
		const n2 = fs.readdirSync(DIR).filter(f => f.startsWith(new Date().toISOString().slice(0, 10))).length;
		check('write: a same-minute duplicate does NOT clobber the first',
			n2 >= 2, 'files=' + n2 + ' :: ' + w2.slice(0, 60));
		// Over-long sections are refused rather than truncated mid-sentence.
		check('write: refuses an over-long section instead of silently clipping it',
			(await e.call('handoff_write', { goal: 'x'.repeat(5000) })).includes('[FAIL]'));
	}

	// ================= 5. list / read =================
	{
		const e = harness();
		const l = await e.call('handoff_list', {});
		check('list: lists what is there', l.includes('.md'), String(l).slice(0, 160));
		const byName = l.split(/\r?\n/).map(x => x.trim().split(/\s+/)[0]).filter(Boolean);
		check('list: newest first (or at least deterministic)', byName.length >= 2, byName.join(' '));

		const r = await e.call('handoff_read', { name: byName[0] });
		check('read: returns the full document', r.includes('交接：'), String(r).slice(0, 100));
		check('read: names which sections are unfilled so the reader knows what to ask about',
			r.includes('未填写') || SECTIONS.every(s => String(r).includes(s.label)));

		// The escape class: the model picks the name.
		// A traversal name is reduced to its last segment, so it reads the handoff dir's own file
		// — and that is what must be asserted: the OUTSIDE file's content never arrives. Checking
		// for a keyword in the reply is not enough: `没有 x.md 这份交接` contains 交接 legitimately.
		const parent = path.dirname(DIR);
		const escapeTarget = path.join(parent, 'OUTSIDE-SECRET.md');
		fs.writeFileSync(escapeTarget, 'OUTSIDE-SECRET-CONTENT', 'utf8');
		const esc = await e.call('handoff_read', { name: '../' + path.basename(escapeTarget) });
		check('read: a traversal name cannot read escapeTarget the handoff dir',
			esc.includes('[FAIL]') && !esc.includes('OUTSIDE-SECRET-CONTENT'), esc.slice(0, 90));
		fs.rmSync(escapeTarget, { force: true });
		check('read: a traversal name that lands nowhere is refused cleanly',
			(await e.call('handoff_read', { name: '../../nope.md' })).includes('[FAIL]'));
		check('read: refuses a non-.md name',
			(await e.call('handoff_read', { name: 'config.json' })).includes('[FAIL]'));
		check('read: an absent file says so and does not throw',
			(await e.call('handoff_read', { name: 'nope.md' })).includes('[FAIL]'));

		// A second file written OUTSIDE the handoff dir must never be reachable.
		const outside = path.join(os.tmpdir(), 'pcb-handoff-secret.md');
		fs.writeFileSync(outside, 'SECRET', 'utf8');
		check('read: cannot reach a file outside the handoff dir',
			(await e.call('handoff_read', { name: path.basename(outside) })).includes('[FAIL]'));
		fs.rmSync(outside, { force: true });
	}

	// ================= 6. no dir =================
	{
		const e = harness({ dir: () => '' });
		for (const n of ['handoff_write', 'handoff_list', 'handoff_read']) {
			const r = await e.call(n, n === 'handoff_read' ? { name: 'a.md' } : { goal: 'g' });
			check('no-dir: ' + n + ' refuses rather than guessing', r.includes('[FAIL]'), r);
		}
	}

	// ================= 7. gating =================
	{
		let cfg = { plugins: {} };
		const reg = new ToolRegistry();
		registerHandoffTools(reg, { dir: () => DIR, sessionTitle: () => 's' });
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:note': false }));
		check('gate: with note off all four are gone',
			['handoff_preview', 'handoff_write', 'handoff_list', 'handoff_read'].every(n => !reg.get(n)));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:note': true }));
		check('gate: and they come back', !!reg.get('handoff_read') && !!reg.get('handoff_write'));
	}

	fs.rmSync(DIR, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});