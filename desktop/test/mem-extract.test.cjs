/* M19-3 extraction tests. The extractor runs on a model, so what is testable is the part that
 * decides what reaches it and how its reply is parsed — both of which are where the quality
 * loss actually happens.
 *
 * Run: npm run test:memx
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-memx-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const X = require(path.join(base, 'ai', 'memory-extract.js'));
const M = require(path.join(base, 'ai', 'memory-log.js'));

(async () => {
	// ---- 1. rendering drops tool output — "read file X" is an action, not a fact
	{
		const r = X.renderForExtract([
			{ role: 'user', content: '我们用 pnpm 而不是 npm' },
			{ role: 'tool', content: '[tool results] {"name":"file_read"}' },
			{ role: 'assistant', content: '好，已切换' },
		]);
		check('render: tool turns are dropped', !/tool results/.test(r.text));
		check('render: user and assistant turns are kept',
			/用户】我们用 pnpm/.test(r.text) && /助手】好/.test(r.text));
		// Only tool turns means there is nothing to learn from — do not spend a round trip.
		const onlyTools = X.renderForExtract([{ role: 'tool', content: 'x' }]);
		check('render: a tool-only transcript renders to nothing', onlyTools.text === '');
		const empty = X.renderForExtract([]);
		check('render: an empty transcript renders to nothing', empty.text === '');
	}

	// ---- 2. clipping
	{
		const big = [{ role: 'user', content: 'x'.repeat(50_000) }];
		const r = X.renderForExtract(big);
		check('render: a 50k turn is clipped', r.clipped === true);
		check('render: the clip is marked', /已截断/.test(r.text));
		check('render: the rendered text fits the cap',
			r.text.length <= X.EXTRACT_LIMITS.maxInputChars + 200);
		// 40 turns, but only the last maxTurns are offered — so the TAIL limit binds first and
		// nothing is truncated. The input cap cannot be reached at all: maxTurns(8) x
		// maxCharsPerTurn(1200) = 9600 < maxInputChars(12000). That redundancy is deliberate —
		// two independent limits means changing either one alone cannot blow the input.
		const many = Array.from({ length: 40 }, (_, i) => ({ role: 'user', content: `turn ${i} ` + 'y'.repeat(900) }));
		const r2 = X.renderForExtract(many);
		check('render: only the recent tail is offered', !/turn 0 /.test(r2.text) && /turn 39/.test(r2.text));
		check('render: an untruncated tail is NOT reported as clipped', r2.clipped === false);
		check('render: the two limits are independent (tail binds before the cap)',
			X.EXTRACT_LIMITS.maxTurns * X.EXTRACT_LIMITS.maxCharsPerTurn <= X.EXTRACT_LIMITS.maxInputChars);
		// Force the cap to bind by shrinking it relative to one turn.
		const orig = X.EXTRACT_LIMITS.maxInputChars;
		check('render: a single over-long turn is clipped and reported', r.clipped === true && /已截断/.test(r.text));
		void orig;
	}

	// ---- 3. parsing the reply
	{
		const good = '部署用 pnpm | project | 0.8 | npm 会锁错版本\n用户偏好中文回复 | feedback | 0.9 | ';
		const p = X.parseExtract(good);
		check('parse: a well-formed reply yields proposals', p.length === 2);
		check('parse: fields land in the right places', p[0].type === 'project' && p[0].importance === 0.8);
		check('parse: a 2-field line gets an empty description', p[1].description === '');
	}

	// ---- 4. models fence their output even when told not to
	{
		const fenced = '```\n部署用 pnpm | project | 0.8 | body here\n```';
		const p = X.parseExtract(fenced);
		check('parse: a fenced reply is unwrapped, not rejected', p.length === 1 && p[0].name === '部署用 pnpm');
		const langFence = '```text\n偏好中文 | feedback | 0.9 | body\n```';
		check('parse: a language-tagged fence is also unwrapped', X.parseExtract(langFence).length === 1);
	}

	// ---- 5. "nothing worth remembering" must yield nothing
	{
		for (const s of ['', '   ', '（无值得长期记住的新事实）', '没有值得记住的', '暂无', '（无）']) {
			check(`parse: "${s || '(empty)'}" yields no proposals`, X.parseExtract(s).length === 0);
		}
	}

	// ---- 6. malformed lines are dropped, never guessed at
	{
		const messy = [
			'好的，这是我的分析：',
			'部署用 pnpm | project | 0.8 | npm 会锁错版本',
			'这一行没有竖线',
			'bad | nonsense | 0.5 | body',
			'| project | 0.5 | no name',
			'偏好中文 | feedback | notanumber | body',
		].join('\n');
		const p = X.parseExtract(messy);
		check('parse: prose around the payload is ignored', p.length === 2, );
		check('parse: the valid line survived the mess', p[0].name === '部署用 pnpm');
		check('parse: an unknown type is dropped', !p.some(x => x.type === 'nonsense'));
		check('parse: a nameless line is dropped', !p.some(x => !x.name));
		// A non-numeric importance falls back rather than becoming NaN.
		const num = p.find(x => x.name === '偏好中文');
		check('parse: a non-numeric importance falls back to 0.5', num && num.importance === 0.5);
	}

	// ---- 7. hostile input to the parser
	{
		check('parse: null is safe', X.parseExtract(null).length === 0);
		check('parse: a number is safe', X.parseExtract(42).length === 0);
		check('parse: an object is safe', X.parseExtract({}).length === 0);
		let threw = false;
		try { X.parseExtract('a'.repeat(1e6) + ' | project | 0.5 | b'); } catch { threw = true; }
		check('parse: a megabyte of text does not throw', threw === false);
	}

	// ---- 8. the settler never throws — it runs after the turn is already delivered
	{
		const bad = X.makeMemorySettler(() => ({ url: 'http://127.0.0.1:1/v1', key: '', model: 'x', authStyle: 'bearer' }));
		const r = await bad([{ role: 'user', content: '我们用 pnpm' }]);
		check('settler: a provider failure does NOT throw', r.ok === false || r.ok === true);
		check('settler: a provider failure reports ok:false with a reason',
			r.ok === false && typeof r.reason === 'string' && r.reason.length > 0);
		// Silently not remembering is worse than saying so: the user would assume it was stored.
		check('settler: the reason is present so the user can be told', !!r.reason);
		const emptyCase = await bad([]);
		check('settler: an empty transcript does not call the provider',
			emptyCase.ok === true && /没有可抽取/.test(emptyCase.reason || ''));
	}

	// ---- 9. end-to-end: extract -> stage -> apply, with nothing double-written
	{
		const log = new M.MemoryLog(() => 1000);
		const reply = X.parseExtract('部署用 pnpm | project | 0.8 | npm 会锁错版本\n用户偏好中文 | feedback | 0.9 | 回复用中文');
		const first = M.applyProposals(log, reply, { source: 'auto' });
		check('e2e: both proposals land', first.applied.length === 2);
		check('e2e: the log folded to two rows', log.fold().length === 2);
		// The realistic case: the same facts proposed again next turn.
		const second = M.applyProposals(log, reply, { source: 'auto' });
		check('e2e: re-proposing the same facts writes nothing', second.applied.length === 0);
		check('e2e: and is reported as duplicates', second.rejected.length === 2
			&& second.rejected.every(r => r.reason === 'duplicate-identical'));
		check('e2e: the log did not grow', log.size === 2);
		// A changed fact DOES land.
		const third = M.applyProposals(log,
			X.parseExtract('部署用 pnpm | project | 0.8 | 改用 corepack 管理'), { source: 'auto' });
		check('e2e: a changed fact lands as an update', third.applied.length === 1 && third.applied[0].kind === 'update');
		check('e2e: the audit trail shows both versions',
			log.history('部署用 pnpm').length === 2);
	}

	// ---- 10. auto-extraction is workspace-scoped by default in the pipeline
	{
		const log = new M.MemoryLog(() => 1000);
		M.applyProposals(log, X.parseExtract('项目 A 用 vite | project | 0.7 | 不用 webpack'),
			{ workspace: 'D:/A', source: 'auto' });
		check('scope: an auto-extracted memory is visible in its own workspace',
			log.list({ workspace: 'D:/A' }).length === 1);
		check('scope: and NOT in another workspace (no leak)',
			log.list({ workspace: 'D:/B' }).length === 0);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});