/* checkpoint tool tests: checkpoint_list / _push / _rewind.
 *
 * The assertions that matter are about what a rewind COSTS and what a push EVICTS:
 *
 *   - `checkpoint_rewind` is `danger`, not `confirm`: it replaces the live history, so every
 *     message after the checkpoint is gone from disk — not soft-deleted, not undoable. The reply
 *     says how many turns died, because the approval card carried the cost and the reply should
 *     confirm what was actually paid.
 *   - The stack is BOUNDED (10) and `CheckpointStack.push` shifts the oldest out SILENTLY. A user
 *     pushes one at a time and never notices; a model that pushes every turn evicts the USER's own
 *     checkpoints. So eviction is reported, and model-pushed ones are stamped `model:` so a human
 *     reading the list can tell them apart.
 *   - A seq with no checkpoint at or below it must refuse and NAME what does exist, rather than
 *     rewinding to something older than the caller asked for without saying so.
 *
 * Run: npm run test:checkpoint
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-checkpoint.txt');
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
const { registerCheckpointTools, CHECKPOINT_LIMITS } = require(path.join(ai, 'checkpoint-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

/** A stand-in CheckpointStack with the REAL semantics: bounded, shift-oldest on overflow. */
function makeStack(limit) {
	const cap = limit || CHECKPOINT_LIMITS.stackCeiling;
	let stack = [];
	const deps = {
		push(label) {
			const seq = stack.length;
			stack.push({ seq, label, at: Date.now(), turns: seq + 1 });
			if (stack.length > cap) { stack.shift(); }
			return seq;
		},
		async rewindTo(seq) {
			const cp = stack.filter(c => c.seq <= seq).pop();
			if (!cp) { return false; }
			stack = stack.filter(c => c.seq <= cp.seq);
			return true;
		},
		list: () => stack.map(c => ({ ...c })),
	};
	return { deps, get stack() { return stack; } };
}

function env(over) {
	const s = makeStack();
	const reg = new ToolRegistry();
	registerCheckpointTools(reg, Object.assign(s.deps, over || {}));
	return { reg, s, call: (n, i) => reg.get(n).invoke(i) };
}

(async () => {
	// ================= 1. registration =================
	{
		const e = env();
		for (const n of ['checkpoint_list', 'checkpoint_push', 'checkpoint_rewind']) {
			check('registered: ' + n, typeof e.reg.get(n)?.invoke === 'function');
		}
		check('domain: system (same domain as undo_last_write)',
			['checkpoint_list', 'checkpoint_push', 'checkpoint_rewind']
				.every(n => e.reg.get(n)?.domain === 'system'),
			'domain=' + e.reg.get('checkpoint_push')?.domain);
		check('risk: push is auto (a snapshot costs nothing and harms nothing)',
			e.reg.get('checkpoint_push')?.risk === 'auto', e.reg.get('checkpoint_push')?.risk);
		// danger, not confirm: every message after the checkpoint is gone from disk.
		check('risk: rewind is DANGER — it discards the live history irreversibly',
			e.reg.get('checkpoint_rewind')?.risk === 'danger', e.reg.get('checkpoint_rewind')?.risk);
		check('description: rewind says so in the words the approval rule requires',
			/确认/.test(String(e.reg.get('checkpoint_rewind')?.description ?? '')),
			e.reg.get('checkpoint_rewind')?.description);
		check('description: and says the turns are unrecoverable',
			/不可恢复/.test(String(e.reg.get('checkpoint_rewind')?.description ?? '')));
	}

	// ================= 2. list =================
	{
		const e = env();
		check('list: empty says so AND says how to make one',
			(await e.call('checkpoint_list', {})).includes('checkpoint_push'),
			await e.call('checkpoint_list', {}));
		await e.call('checkpoint_push', { label: 'before risky' });
		const l = await e.call('checkpoint_list', {});
		check('list: shows the seq, label and turn count',
			l.includes('#0') && l.includes('before risky'), l);
		check('list: shows the occupancy against the ceiling', l.includes('/' + CHECKPOINT_LIMITS.stackCeiling), l);
	}

	// ================= 3. push =================
	{
		const e = env();
		const r = await e.call('checkpoint_push', { label: '手动打的' });
		check('push: reports the seq', r.includes('#0'), r);
		check('push: keeps the caller label as written', r.includes('手动打的'), r);
		check('push: says how to get back to it', r.includes('checkpoint_rewind'), r);

		// A label the model did not supply is stamped, so the list distinguishes the two.
		const auto = await e.call('checkpoint_push', {});
		check('push: an unlabelled push is STAMPED model: so a human can tell it apart',
			auto.includes('model:'), auto);
		const l = await e.call('checkpoint_list', {});
		check('push: and the list shows that distinction', /model:\d\d:\d\d/.test(l), l);

		check('push: an over-long label is refused',
			(await e.call('checkpoint_push', { label: 'x'.repeat(CHECKPOINT_LIMITS.maxLabelChars + 1) })).includes('[FAIL]'));
	}

	// ================= 4. eviction is REPORTED, not silent =================
	{
		// Fill to the ceiling, then push one more. CheckpointStack.push shifts the oldest out
		// without a word; the tool is where that stops being silent.
		const e = env();
		for (let i = 0; i < CHECKPOINT_LIMITS.stackCeiling; i++) {
			await e.call('checkpoint_push', { label: 'user#' + i });
		}
		check('evict: the stack is at the ceiling', e.s.stack.length === CHECKPOINT_LIMITS.stackCeiling);
		const over = await e.call('checkpoint_push', { label: 'one more' });
		check('evict: pushing past the ceiling SAYS the oldest was evicted',
			over.includes('挤掉'), over);
		check('evict: and names the ceiling', over.includes(String(CHECKPOINT_LIMITS.stackCeiling)), over);
		check('evict: it says the consequence — the user lost a rollback door',
			over.includes('回滚的门') || over.includes('用户自己打的'), over);
		check('evict: the user\'s own checkpoint is really gone from the list',
			!(await e.call('checkpoint_list', {})).includes('user#0'),
			await e.call('checkpoint_list', {}));
		check('evict: the stack really is still at the ceiling',
			e.s.stack.length === CHECKPOINT_LIMITS.stackCeiling);
	}

	// ================= 5. rewind =================
	{
		const e = env();
		await e.call('checkpoint_push', { label: 'A' });
		await e.call('checkpoint_push', { label: 'B' });
		await e.call('checkpoint_push', { label: 'C' });
		const r = await e.call('checkpoint_rewind', { seq: 1 });
		check('rewind: says which checkpoint it landed on', r.includes('B'), r);
		// The regex must not be loose enough to match the no-loss message: 「之后没有轮次被丢弃」
		// also reads as 之后…轮…丢弃. Assert the NUMBER. A=1 B=2 C=3 轮, rewind to B → 1 轮 dies.
		check('rewind: names the EXACT number of turns that died', r.includes('1 轮已丢弃'), r);
		check('rewind: and says they are unrecoverable', r.includes('不可恢复'), r);
		check('rewind: the stack really truncated to that point', e.s.stack.length === 2, String(e.s.stack.length));
		check('rewind: the discarded ones are gone',
			!(await e.call('checkpoint_list', {})).includes('C'), await e.call('checkpoint_list', {}));

		// NOTE on reachability: seq starts at 0 and the match is `<= seq`, so with a NON-EMPTY
		// stack every non-negative seq resolves to something. The refusal is therefore only
		// reachable on an EMPTY stack — which is exactly when it matters, so that is what is
		// asserted rather than a case that cannot happen.
		const empty = env();
		const bad = await empty.call('checkpoint_rewind', { seq: 0 });
		check('rewind: on an empty stack it refuses', bad.includes('[FAIL]'), bad);
		check('rewind: and says there are none, instead of dead-ending silently',
			bad.includes('一个都没有'), bad);
		check('rewind: the refusal names the seq it could not honour', bad.includes('0'), bad);
		// And the refusals that DO apply to a non-empty stack must leave it untouched.
		await e.call('checkpoint_rewind', { seq: 1.5 });
		check('rewind: a refused call leaves the stack untouched', e.s.stack.length === 2,
			String(e.s.stack.length));

		check('rewind: a non-integer seq is refused',
			(await e.call('checkpoint_rewind', { seq: 1.5 })).includes('[FAIL]'));
		check('rewind: a negative seq is refused',
			(await e.call('checkpoint_rewind', { seq: -1 })).includes('[FAIL]'));
		check('rewind: a missing seq is refused',
			(await e.call('checkpoint_rewind', {})).includes('[FAIL]'));
	}
	{
		// The upper bound resolves DOWNWARD (newest at or below), and that must be said.
		const e = env();
		await e.call('checkpoint_push', { label: 'A' });
		await e.call('checkpoint_push', { label: 'B' });
		const r = await e.call('checkpoint_rewind', { seq: 99 });
		check('rewind: a seq above every checkpoint lands on the NEWEST, and names it',
			r.includes('B'), r);
	}
	{
		// A failed rewind must not claim success.
		const e = env({ rewindTo: async () => false });
		await e.call('checkpoint_push', { label: 'A' });
		const r = await e.call('checkpoint_rewind', { seq: 0 });
		check('rewind: a failing rewindTo is reported as a failure, not fabricated',
			r.includes('[FAIL]'), r);
	}

	// ================= 6. gating =================
	{
		const reg = new ToolRegistry();
		registerCheckpointTools(reg, makeStack().deps);
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:system': false }));
		// system is alwaysOn, so the switch must NOT be able to hide these — and that is the
		// reason they live there: a `danger` gate is the control, not a hidden tool.
		check('gate: system is alwaysOn, so the tools survive a system-off attempt',
			!!reg.get('checkpoint_rewind'), 'still present');
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});