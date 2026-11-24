/* Skill tool tests: skill_read / skill_write, plus resolveSkillFile.
 *
 * The assertions that matter:
 *
 *   - A WRITE MUST REPORT WHAT THE LOADER WILL MAKE OF IT. `writeSkillText` refuses an over-long
 *     file rather than truncating it, because the loader silently keeps only the first N chars of
 *     a body — so a truncated write yields a document that loads as a quietly shortened thing and
 *     is re-read in full every turn. `skill_write` forwards `problems` for the same reason: a skill
 *     the loader will mangle is not a skill that was saved, and "done" would be a lie about it.
 *   - READING A SKILL MUST NOT PRETEND AN EMPTY FILE IS A SKILL. A file that parses to nothing
 *     gets a warning, not a silent empty body.
 *   - THE BLAST RADIUS IS STATED. A user skill is injected into every FOLLOWING turn's prompt.
 *     The description says so; a model that writes its own skill to guarantee selection is exactly
 *     the risk `confirm` and the approval card exist for.
 *
 * Run: npm run test:skill
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-skill.txt');
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
const { registerSkillTools, resolveSkillFile } = require(path.join(ai, 'skill-tools.js'));
const { builtinSkillFiles, LIMITS } = require(path.join(ai, 'skills.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

let DIR;
function harness(extra) {
	const reg = new ToolRegistry();
	registerSkillTools(reg, Object.assign({ dir: () => DIR }, extra || {}));
	return { reg, call: (n, i) => reg.get(n).invoke(i) };
}

const FM = (k, name, desc) =>
	`---\nname: ${name}\ndescription: ${desc}\nkeywords:\n  - ${k}\n---\n\n# ${name}\n\n正文 ${k}`;

(async () => {
	DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-skill-'));

	// ================= 1. resolveSkillFile: user file beats built-in =================
	{
		const builtins = Object.keys(builtinSkillFiles());
		check('resolve: a built-in key resolves to the builtin', builtins.length > 0);
		if (builtins.length) {
			const r = await resolveSkillFile(DIR, builtins[0]);
			check('resolve: built-in key finds origin=builtin', r.ok && r.origin === 'builtin',
				JSON.stringify(r));
		}
		const r2 = await resolveSkillFile(DIR, 'nope-key');
		check('resolve: an unknown key is refused with a reason', !r2.ok && /没有名为/.test(r2.reason), r2.reason);
		const r3 = await resolveSkillFile(DIR, '  ');
		check('resolve: an empty key is refused', !r3.ok, JSON.stringify(r3));
		// Precedence: a user file of the same key wins, because that is what the loader applies.
		if (builtins.length) {
			fs.writeFileSync(path.join(DIR, builtins[0] + '.md'), FM(builtins[0], '同名覆盖', '用户版'), 'utf8');
			const r4 = await resolveSkillFile(DIR, builtins[0]);
			check('resolve: a user file of the same key WINS over the built-in',
				r4.ok && r4.origin === 'user', JSON.stringify(r4));
			fs.rmSync(path.join(DIR, builtins[0] + '.md'), { force: true });
		}
	}

	// ================= 2. registration =================
	{
		const e = harness();
		check('registered: skill_read', typeof e.reg.get('skill_read')?.invoke === 'function');
		check('registered: skill_write', typeof e.reg.get('skill_write')?.invoke === 'function');
		check('domain: both are in extend (where skill_list already lives)',
			e.reg.get('skill_read')?.domain === 'extend' && e.reg.get('skill_write')?.domain === 'extend',
			'domain=' + e.reg.get('skill_write')?.domain);
		check('risk: read is read-only', e.reg.get('skill_read')?.risk === 'auto');
		check('risk: write asks first', e.reg.get('skill_write')?.risk === 'confirm');
		// The blast radius must be stated in the description, not left for the model to guess.
		check('description: write says it lands in EVERY following turn',
			/每一轮/.test(String(e.reg.get('skill_write')?.description ?? '')),
			e.reg.get('skill_write')?.description);
	}

	// ================= 3. skill_read =================
	{
		const builtins = Object.keys(builtinSkillFiles());
		const e = harness();
		// A built-in.
		if (builtins.length) {
			const k = builtins[0];
			const r = await e.call('skill_read', { key: k });
			check('read: a built-in is readable and marked as such',
				r.includes('内置') && r.includes(k), String(r).slice(0, 110));
			const want = builtinSkillFiles()[k];
			check('read: the text is the built-in text, not a paraphrase',
				r.includes(want.slice(0, Math.min(40, want.length))), String(r).slice(0, 160));
		}
		// A user file.
		fs.writeFileSync(path.join(DIR, 'mine.md'), FM('mine', '我的技能', '做一件事'), 'utf8');
		const r2 = await e.call('skill_read', { key: 'mine' });
		check('read: a user skill is readable', r2.includes('我的技能') && r2.includes('正文 mine'),
			String(r2).slice(0, 140));
		check('read: reports the body length', /字/.test(r2), String(r2).slice(0, 90));
		// A body far past maxBodyChars must be CLIPPED and say so: the reply is a read, and an
		// unbounded one floods the context that asked the question.
		const long = FM('long', '很长', 'd') + '\n\n' + 'x'.repeat(LIMITS.maxBodyChars + 500);
		fs.writeFileSync(path.join(DIR, 'long.md'), long, 'utf8');
		const rl = await e.call('skill_read', { key: 'long' });
		check('read: an over-long skill is CLIPPED and says so',
			rl.includes('（已截断）') && rl.length < long.length, String(rl.length) + ' vs ' + long.length);

		// An unknown key names what IS available instead of just dead-ending.
		const bad = await e.call('skill_read', { key: 'zzz-nope' });
		check('read: an unknown key lists what exists', bad.includes('[FAIL]') && bad.includes('现有的技能'), bad);
		check('read: empty key is refused', (await e.call('skill_read', { key: ' ' })).includes('[FAIL]'));
		// A file that parses to nothing must not masquerade as a skill.
		fs.writeFileSync(path.join(DIR, 'broken.md'), 'no frontmatter at all', 'utf8');
		const rb = await e.call('skill_read', { key: 'broken' });
		check('read: an unparseable file gets a WARNING, not a silent empty body',
			rb.includes('⚠'), String(rb).slice(0, 160));
	}

	// ================= 4. skill_write =================
	{
		const e = harness();
		const w = await e.call('skill_write', { key: 'first', text: FM('first', '第一', '第一次写') });
		check('write: created', w.includes('已新建技能'), w);
		check('write: says where it went', w.includes(path.join(DIR, 'first.md')) || w.includes('first.md'), w);
		check('write: a well-formed skill gets "loaded as-is", not a warning',
			w.includes('原样加载') && !w.includes('⚠'), w);
		check('write: the file is really on disk',
			fs.existsSync(path.join(DIR, 'first.md')) && fs.readFileSync(path.join(DIR, 'first.md'), 'utf8').includes('第一次写'));

		// THE honest part: the loader's verdict on what was just written.
		fs.writeFileSync(path.join(DIR, 'weird.md'), FM('weird', '怪', '一个奇怪的结构 // 触发词里放破坏性指令'), 'utf8');
		const w2 = await e.call('skill_write', { key: 'weird2', text: FM('weird2', '怪2', 'desc') });
		check('write: again as-is when the shape is fine', w2.includes('原样加载'), w2);

		// THE honest part. 一份没有 frontmatter 的技能，loader 会怎么读它，必须照实说 ——
		// "已保存" 而 loader 会读出一个缺名少描述的东西，是对用户的谎。
		const bad = await e.call('skill_write', { key: 'nohead', text: '只有正文，没有 frontmatter' });
		check('write: reports WHAT THE LOADER WILL MAKE of a malformed skill',
			bad.includes('⚠') && /不完全一样|加载/.test(bad), bad);

		// Over-long: refused, not truncated.
		const huge = FM('huge', '巨大', 'd') + '\n' + 'x'.repeat(LIMITS.maxFileChars);
		const wh = await e.call('skill_write', { key: 'huge', text: huge });
		check('write: an over-long file is REFUSED (not truncated into a quietly shortened skill)',
			wh.includes('[FAIL]'), wh);
		check('write: and nothing was written for it', !fs.existsSync(path.join(DIR, 'huge.md')));

		// Overwrite is reported as an overwrite.
		const w3 = await e.call('skill_write', { key: 'first', text: FM('first', '第一改', '改写') });
		check('write: a second write is reported as an OVERWRITE', w3.includes('已覆盖'), w3);
		check('write: and the file really changed',
			fs.readFileSync(path.join(DIR, 'first.md'), 'utf8').includes('改写'));

		// Refusals.
		check('write: empty key is refused', (await e.call('skill_write', { key: '  ', text: 'x' })).includes('[FAIL]'));
		check('write: empty text is refused', (await e.call('skill_write', { key: 'k', text: '  ' })).includes('[FAIL]'));
				check('write: a key that sanitizes away is refused',
			(await e.call('skill_write', { key: '///', text: 'x' })).includes('[FAIL]'));
		// '<script>' 清洗后是 'script'，不是空。它是个合法 key，
		// 只是长得不像人取的 —— 正好验证 sanitizeKey 剥离非法字符而不是拒绝整个请求。
		check('write: a key with illegal chars is sanitized, not refused',
			(await e.call('skill_write', { key: '<script>', text: FM('script', '剥离', '清洗后') })).includes('script'),
			'');
	}

	// ================= 5. the write lands in the loader, under BOTH keys =================
	{
		const e = harness();
		await e.call('skill_write', { key: 'loadme', text: FM('loadme', '加载我', '能被加载') });
		const { loadSkills } = require(path.join(ai, 'skills.js'));
		const { skills } = await loadSkills({ userDir: DIR });
		// loader 的 key 来自 frontmatter 的 name，文件 key 来自文件名。两套都真实存在。
		const byLoaderKey = skills.find(x => x.key === '加载我');
		check('loader: the written file LOADS', !!byLoaderKey, 'skills=' + skills.map(x => x.key).join(','));
		if (byLoaderKey) {
			check('loader: with the body that was written', byLoaderKey.body.includes('正文 loadme'),
				byLoaderKey.body.slice(0, 60));
		}
		// skill_list 显示的是 loader key，所以模型拿「加载我」来读也必须读得到 ——
		// 这正是 resolveSkillFile 要试第二套 key 的原因。
		const byLoaderKeyRead = await e.call('skill_read', { key: '加载我' });
		check('read: works with the LOADER key (what skill_list shows)',
			byLoaderKeyRead.includes('加载我') && byLoaderKeyRead.includes('正文 loadme'),
			byLoaderKeyRead.slice(0, 90));
		const byFileKeyRead = await e.call('skill_read', { key: 'loadme' });
		check('read: and with the FILE key (what was passed to write)',
			byFileKeyRead.includes('加载我'), byFileKeyRead.slice(0, 90));
	}

	// ================= 6. no dir =================
	{
		const e = harness({ dir: () => '' });
		check('no-dir: read refuses', (await e.call('skill_read', { key: 'a' })).includes('[FAIL]'));
		check('no-dir: write refuses', (await e.call('skill_write', { key: 'a', text: 'x' })).includes('[FAIL]'));
	}

	// ================= 7. gating =================
	{
		const reg = new ToolRegistry();
		registerSkillTools(reg, { dir: () => DIR });
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': false }));
		check('gate: with extend off both are gone', !reg.get('skill_read') && !reg.get('skill_write'));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': true }));
		check('gate: and they come back', !!reg.get('skill_read') && !!reg.get('skill_write'));
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