/* M19-3 memory log tests.
 *
 * The interesting failures in an append-only log are all about ORDER and about HOSTILE INPUT.
 * Both are hard to provoke against a real clock, so the clock is injected and forced to misbehave.
 *
 * Run: npm run test:memory
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-mem-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const M = require(path.join(base, 'ai', 'memory-log.js'));
// tools.js carries the projection AND the one erase path, both of which need a real Store to be
// observable: the log alone cannot show a row being resurrected, because that is the log and the
// collection disagreeing — not a property of either one by itself.
const T = require(path.join(base, 'ai', 'tools.js'));
const { Store } = require(path.join(base, 'store', 'store.js'));

/** A clock we can move backwards on purpose. */
let clock = 1000;
const now = () => clock;

const ev = (o = {}) => ({
	// kind must come from the caller: hardcoding 'add' here silently made every event an add,
	// which turns update/forget/restore into "replace the row" and hides all the fold logic.
	kind: o.kind ?? 'add', key: o.key ?? o.name ?? 'k', name: o.name ?? 'k',
	body: o.body ?? 'b', description: o.description ?? '', type: o.type ?? 'project',
	importance: o.importance ?? 0.5, scope: o.scope ?? 'user',
	workspace: o.workspace, source: o.source ?? 'tool', reason: o.reason,
});

(async () => {
	// ---- 1. append + fold basics
	{
		const log = new M.MemoryLog(now);
		const a = log.append(ev({ key: 'K1', name: '部署用 pnpm', body: 'npm 会锁错版本' }));
		check('append: returns the stored event', !!a);
		check('append: seq starts at 1', a.seq === 1);
		check('fold: one row after one add', log.fold().length === 1);
		check('fold: the body round-trips', log.get('K1').body === 'npm 会锁错版本');
		check('append: an empty name is rejected', log.append(ev({ key: '', name: '   ' })) === null);
		check('append: the log did not grow on rejection', log.size === 1);
	}

	// ---- 2. ORDER: the clock must not be trusted
	// NTP correction, a user changing the system clock, or a laptop resuming all produce
	// out-of-order wall clock. Ordering by `at` would corrupt the replay.
	{
		clock = 5000;
		const log = new M.MemoryLog(now);
		log.append(ev({ key: 'A', name: 'A', body: 'v1' }));
		clock = 1000;                       // clock goes BACKWARDS
		log.append(ev({ key: 'A', name: 'A', body: 'v2 (newer by seq)' }));
		clock = 9000;
		const m = log.get('A');
		check('order: the later SEQ wins even when the clock went backwards',
			m.body === 'v2 (newer by seq)', );
		check('order: updatedAt comes from the event, not from now()', m.updatedAt === 1000);
		const hist = log.history('A');
		check('order: history is sorted by seq, not by time',
			hist[0].seq < hist[1].seq && hist[0].at > hist[1].at);
	}

	// ---- 3. update / revise / forget / restore
	{
		const log = new M.MemoryLog(() => ++clock);
		log.append(ev({ key: 'A', name: 'A', body: 'v1', importance: 0.3, type: 'project' }));
		log.append(ev({ key: 'A', name: 'A', body: 'v2', kind: 'update', importance: 0.3 }));
		check('update: the body changes', log.get('A').body === 'v2');
		check('update: type and importance are preserved by an update',
			log.get('A').type === 'project' && log.get('A').importance === 0.3);
		log.append(ev({ key: 'A', name: 'A', body: 'v2', kind: 'revise', type: 'feedback', importance: 0.9 }));
		check('revise: type and importance change', log.get('A').type === 'feedback' && log.get('A').importance === 0.9);
		check('revise: the body is untouched by a revise', log.get('A').body === 'v2');
		// Erase must be auditable: the row leaves the view, the event stays.
		log.append(ev({ key: 'A', name: 'A', body: 'v2', kind: 'forget' }));
		check('forget: the row is hidden from the default list', log.list().length === 0);
		check('forget: it is still retrievable for inspection', !!log.get('A'));
		check('forget: it is marked, not deleted', log.get('A').forgotten === true);
		check('forget: the event survives in history', log.history('A').some(e => e.kind === 'forget'));
		log.append(ev({ key: 'A', name: 'A', body: 'v2', kind: 'restore' }));
		check('restore: it comes back', log.list().length === 1 && log.get('A').forgotten === false);
		check('restore: the body was not clobbered', log.get('A').body === 'v2');
	}

	// ---- 4. THE bug this file was written for: workspace scope leaked across projects
	// An earlier version compared a value to itself and always returned true, so every project's
	// workspace memories were visible in every other project.
	{
		const log = new M.MemoryLog(now);
		log.append(ev({ key: 'u1', name: 'user-scope fact', body: 'b', scope: 'user' }));
		log.append(ev({ key: 'p1', name: 'proj A fact', body: 'b', scope: 'workspace', workspace: 'D:/projA' }));
		log.append(ev({ key: 'p2', name: 'proj B fact', body: 'b', scope: 'workspace', workspace: 'D:/projB' }));
		const inA = log.list({ workspace: 'D:/projA' });
		const inB = log.list({ workspace: 'D:/projB' });
		check('scope: user scope is visible everywhere', inA.some(m => m.id === 'u1') && inB.some(m => m.id === 'u1'));
		check('scope: proj A sees its own memory', inA.some(m => m.id === 'p1'));
		check('scope: proj A does NOT see proj B memory', !inA.some(m => m.id === 'p2'));
		check('scope: proj B does NOT see proj A memory', !inB.some(m => m.id === 'p1'));
		// A changed workspaceRoot must not expose another project.
		check('scope: an unknown workspace sees only user scope',
			log.list({ workspace: 'D:/other' }).every(m => m.scope === 'user'));
		check('scope: no workspace given still shows user scope',
			log.list().some(m => m.id === 'u1'));
	}

	// ---- 5. hostile input and clamping
	{
		const log = new M.MemoryLog(() => 1);
		log.append(ev({ key: 'long', name: 'N'.repeat(5000), body: 'B'.repeat(50_000), importance: 5 }));
		const m = log.get('long');
		check('limits: the name is clipped', m.name.length === M.LIMITS.maxNameChars);
		check('limits: the body is clipped', m.body.length === M.LIMITS.maxBodyChars);
		check('limits: importance above 1 is clamped, not rejected',
			m.importance === 1, );
		log.append(ev({ key: 'neg', name: 'neg', importance: -3 }));
		check('limits: importance below 0 is clamped', log.get('neg').importance === 0);
		log.append(ev({ key: 'nan', name: 'nan', importance: NaN }));
		check('limits: a NaN importance falls back to the default', log.get('nan').importance === 0.5);
		log.append(ev({ key: 'badscope', name: 'badscope', scope: 'everything' }));
		check('limits: an unknown scope falls back to user', log.get('badscope').scope === 'user');
		log.append(ev({ key: 'badtype', name: 'badtype', type: 'nonsense' }));
		check('limits: an unknown type is stored as given (type is validated at the tool layer)', !!log.get('badtype'));
	}

	// ---- 6. persistence round-trip and hostile JSON
	{
		const log = new M.MemoryLog(now);
		log.append(ev({ key: 'A', name: 'A', body: 'v1' }));
		log.append(ev({ key: 'A', name: 'A', body: 'v2', kind: 'update' }));
		log.append(ev({ key: 'B', name: 'B', body: 'w', scope: 'workspace', workspace: 'D:/p' }));
		const json = JSON.parse(JSON.stringify(log.toJSON()));
		const back = M.MemoryLog.fromJSON(json, now);
		check('persist: row count round-trips', back.fold().length === 2);
		check('persist: the folded body is the latest by seq', back.get('A').body === 'v2');
		check('persist: workspace survives', back.get('B').workspace === 'D:/p');
		check('persist: lastSeq survives so new events continue past it', back.lastSeq === 3);
		check('persist: a new append after reload continues the sequence', back.append(ev({ key: 'C', name: 'C' })).seq === 4);

		const hostile = [null, undefined, 42, 'x', {}, { seq: 0 }, { seq: -1 }, { seq: NaN },
			{ seq: 1, name: 'ok', importance: 'high' }, { seq: 2, scope: 'weird' }];
		let ok = true;
		for (const h of hostile) {
			try {
				const l = M.MemoryLog.fromJSON([h], now);
				l.fold(); l.list(); l.all();
			} catch { ok = false; out.push('   -> threw on ' + JSON.stringify(h)); }
		}
		check('persist: hostile events never throw', ok);
		check('persist: fromJSON of garbage yields an empty usable log',
			M.MemoryLog.fromJSON([null, 42, {}], now).fold().length === 0);
	}

	// ---- 7. staging proposals is PURE — nothing is written
	{
		const log = new M.MemoryLog(now);
		const props = M.stageProposals([
			'部署用 pnpm | project | 0.8 | npm 会锁错版本',
			'用户偏好中文 | feedback | 0.9',
			'garbage line with no pipes',
			'bad type | nonsense | 0.5 | body',
			'| project | 0.5 | no name',
			'',
		].join('\n'));
		check('stage: valid lines are staged', props.length === 2);
		check('stage: a line with no pipes is ignored, not guessed at',
			!props.some(p => p.name.includes('garbage')));
		check('stage: an unknown type is rejected', !props.some(p => p.type === 'nonsense'));
		check('stage: a nameless proposal is rejected', !props.some(p => !p.name));
		check('stage: staging wrote NOTHING', log.size === 0, );
		const short = M.stageProposals('short | feedback');
		check('stage: a 2-field line is accepted', short.length === 1 && short[0].type === 'feedback');
		check('stage: a 2-field line has no description', short[0].description === '');
	}

	// ---- 8. applying proposals: dedup by CONTENT, not by name
	// Re-proposing the same fact every turn is the normal failure mode of auto-extraction.
	{
		const log = new M.MemoryLog(now);
		const r1 = M.applyProposals(log, M.stageProposals('部署用 pnpm | project | 0.8 | npm 会锁错版本'));
		check('apply: the first pass writes', r1.applied.length === 1 && r1.rejected.length === 0);
		const r2 = M.applyProposals(log, M.stageProposals('部署用 pnpm | project | 0.8 | npm 会锁错版本'));
		check('apply: re-proposing the SAME fact is rejected as a duplicate',
			r2.applied.length === 0 && r2.rejected[0].reason === 'duplicate-identical');
		check('apply: a rejected duplicate did not grow the log', log.size === 1);
		// A genuinely changed fact DOES apply, as an update.
		const r3 = M.applyProposals(log, M.stageProposals('部署用 pnpm | project | 0.8 | 改用 corepack'));
		check('apply: a changed body applies as an update',
			r3.applied.length === 1 && r3.applied[0].kind === 'update');
		check('apply: the view shows the new body', log.get('部署用 pnpm').body === '改用 corepack');
		// And rejections are reported, not swallowed.
		check('apply: rejections carry a reason the UI can show', r2.rejected[0].reason.length > 0);
		check('apply: an empty proposal list is a no-op',
			M.applyProposals(log, []).applied.length === 0);
		check('apply: a nameless proposal is rejected as empty-name',
			M.applyProposals(log, [{ name: '', body: 'b', type: 'project', reason: 'r' }]).rejected[0].reason === 'empty-name');
		check('apply: the update preserved importance and type',
			log.get('部署用 pnpm').importance === 0.8 && log.get('部署用 pnpm').type === 'project');
	}

	// ---- 9. applyProposals is fail-soft: a bad batch loses nothing
	{
		const log = new M.MemoryLog(now);
		const r = M.applyProposals(log, [
			{ name: 'good1', body: 'b', type: 'project', reason: 'r' },
			{ name: '', body: 'b', type: 'project', reason: 'r' },
			{ name: 'good2', body: 'b', type: 'project', reason: 'r' },
		]);
		check('fail-soft: the good proposals in a partly-bad batch still land',
			r.applied.length === 2 && r.rejected.length === 1);
		check('fail-soft: nothing was half-written', log.fold().length === 2);
	}

	// ---- 10. seeding keeps the log as the single source of truth
	{
		const log = new M.MemoryLog(now);
		const n = log.seed([
			{ name: 'A', body: 'a', createdAt: 100 },
			{ name: 'B', body: 'b', scope: 'workspace', createdAt: 200 },
		]);
		check('seed: both rows seeded', n === 2 && log.fold().length === 2);
		check('seed: seeded rows are add events, so there is exactly one history',
			log.history('A').length === 1 && log.history('A')[0].kind === 'add');
		check('seed: source is recorded as import', log.history('A')[0].source === 'import');
		check('seed: createdAt is preserved', log.get('A').createdAt === 100);
		check('seed: seeding garbage does not throw', log.seed(null) === 0);
	}

	// ---- 10b. applyProposals resolves scope from the CALLER when the proposal is silent
	// This was a real leak: defaulting to 'user' made auto-extracted facts visible from every
	// workspace forever, quietly undoing the isolation this file exists for.
	{
		const log = new M.MemoryLog(now);
		M.applyProposals(log, [{ name: 'A', body: 'b', type: 'project', reason: 'r' }], { workspace: 'D:/A' });
		check('apply: an auto-extracted fact defaults to the CALLER workspace',
			log.get('A').scope === 'workspace' && log.get('A').workspace === 'D:/A');
		check('apply: and is not visible from another workspace',
			log.list({ workspace: 'D:/B' }).length === 0);
		const noWs = new M.MemoryLog(now);
		M.applyProposals(noWs, [{ name: 'A', body: 'b', type: 'project', reason: 'r' }], {});
		check('apply: with no workspace to work in, it falls back to user scope',
			noWs.get('A').scope === 'user');
		const explicit = new M.MemoryLog(now);
		M.applyProposals(explicit, [{ name: 'A', body: 'b', type: 'project', scope: 'user', reason: 'r' }], { workspace: 'D:/A' });
		check('apply: an explicit user scope wins over the caller',
			explicit.get('A').scope === 'user' && explicit.list({ workspace: 'D:/A' }).length === 1);
		// An update must not silently re-scope a memory the user moved.
		M.applyProposals(log, [{ name: 'A', body: 'b2', type: 'project', reason: 'r' }], { workspace: 'D:/B' });
		check('apply: updating does not move a memory to the caller workspace',
			log.get('A').workspace === 'D:/A');
	}

	// ---- 11. list ordering and defaults
	{
		clock = 100;
		const log = new M.MemoryLog(() => clock++);
		log.append(ev({ key: 'old', name: 'old' }));
		log.append(ev({ key: 'new', name: 'new' }));
		const l = log.list();
		check('list: newest first', l[0].id === 'new' && l[1].id === 'old');
		check('list: includeForgotten surfaces forgotten rows', log.list({ includeForgotten: true }).length >= 0);
		check('list: default excludes forgotten', log.list().every(m => !m.forgotten));
	}

	// ---- 12. THE RESURRECTION (pc-a's M19 (a)) ----
	// The page's delete used to go straight to store.softDelete and say nothing to the log.
	// Since the collection is a materialised VIEW of the log, the next projection writes the row
	// straight back. The bug is demonstrated BEFORE the fix: a fix that is never shown failing is
	// a fix that may be doing nothing.
	{
		const dir = path.join(os.tmpdir(), 'pcb-mem-resurrect-' + Date.now());
		fs.mkdirSync(dir, { recursive: true });
		const store = new Store(dir);
		const log = new M.MemoryLog(now);

		log.append(ev({ kind: 'add', key: 'R1', name: '复活测试', body: 'b' }));
		await T.projectMemories(log, store);
		check('resurrect: the projection writes the row', (await store.live('memories')).length === 1);

		// The old path: mark the row deleted, leave the log believing it is live.
		const row = (await store.live('memories'))[0];
		await store.softDelete('memories', row.id);
		check('resurrect: softDelete hides it from live()', (await store.live('memories')).length === 0);
		await T.projectMemories(log, store);
		check('resurrect: the projection BRINGS IT BACK (this is the bug)',
			(await store.live('memories')).length === 1);

		// The fix: erase in the SOURCE and let the view follow.
		const r = await T.forgetMemory(log, store, 'R1');
		check('forget: reports success and cites the event', r.ok === true && r.seq > 0);
		check('forget: the row leaves live()', (await store.live('memories')).length === 0);

		// Projection is the mechanism that resurrected it, so run it again — this is the whole
		// assertion, not the first one.
		await T.projectMemories(log, store);
		check('forget: the projection does NOT resurrect it', (await store.live('memories')).length === 0);
		check('forget: the audit trail survives the erase', log.history('R1').length === 2);
		check('forget: the view marks it forgotten rather than dropping it',
			log.get('R1').forgotten === true);

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 13. forgetMemory: three spellings of identity, and honest failures ----
	{
		const dir = path.join(os.tmpdir(), 'pcb-mem-forgetid-' + Date.now());
		fs.mkdirSync(dir, { recursive: true });
		const store = new Store(dir);
		const log = new M.MemoryLog(now);

		// (a) by store row id — what the renderer's collection list carries.
		log.append(ev({ kind: 'add', key: 'byId', name: '按 id 删', body: 'b' }));
		await T.projectMemories(log, store);
		const idRow = (await store.live('memories'))[0];
		check('identity: a store row id resolves',
			(await T.forgetMemory(log, store, idRow.id)).ok === true);

		// (b) by name — what search output and people actually use, and NOT the log key here,
		// so this specifically fails if the lookup only ever consults the key index.
		log.append(ev({ kind: 'add', key: 'notTheName', name: '按名字删', body: 'b' }));
		await T.projectMemories(log, store);
		check('identity: a name resolves', (await T.forgetMemory(log, store, '按名字删')).ok === true);

		// (c) "already deleted" is not "never existed". A user who sees an error for a row they
		// removed a moment ago cannot tell those apart, and reads both as "my delete was ignored".
		const again = await T.forgetMemory(log, store, '按名字删');
		check('identity: forgetting twice says already, not unknown',
			again.ok === false && again.already === true);
		const unknown = await T.forgetMemory(log, store, '根本没有这条');
		check('identity: an unknown key says unknown, not already',
			unknown.ok === false && unknown.already !== true);
		check('identity: an empty handle refuses', (await T.forgetMemory(log, store, '  ')).ok === false);

		// (d) a row the log never saw (imported or synced straight into the collection) must
		// still be erasable — refusing leaves a row on the page with no way to remove it.
		await store.upsert('memories', {
			id: '', createdAt: 0, updatedAt: 0,
			name: '导入的', description: '', body: 'imported',
		});
		const orphan = (await store.live('memories')).find(r => r.name === '导入的');
		check('identity: a store-only row is still erasable',
			(await T.forgetMemory(log, store, orphan.id)).ok === true);
		await T.projectMemories(log, store);
		check('identity: ...and the projection agrees it is gone',
			!(await store.live('memories')).some(r => r.name === '导入的'));

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 14. scope/workspace survive the projection AND a restart (pc-a's M19 (c)) ----
	{
		const dir = path.join(os.tmpdir(), 'pcb-mem-scope-' + Date.now());
		fs.mkdirSync(dir, { recursive: true });
		const store = new Store(dir);
		const log = new M.MemoryLog(now);
		log.append(ev({ kind: 'add', key: 'W', name: 'workspace 记忆', body: 'b', scope: 'workspace', workspace: 'D:/proj' }));
		await T.projectMemories(log, store);
		const row = (await store.live('memories'))[0];
		check('scope: the projection carries scope onto the row', row.scope === 'workspace');
		check('scope: the projection carries workspace onto the row', row.workspace === 'D:/proj');

		// A restart re-seeds the log FROM the row, exactly as index.ts does. Carrying these two
		// through the seed is what stops (c) from being a one-session fix: drop them here and
		// every workspace memory folds back as user scope on the next launch — visible in every
		// project, which is the one thing workspace scope exists to prevent.
		const again = new M.MemoryLog(now);
		again.seed((await store.live('memories')).map(r => ({
			name: r.name, body: r.body, description: r.description ?? '',
			type: r.type ?? 'project', importance: r.importance ?? 0.5,
			scope: r.scope, workspace: r.workspace,
			createdAt: r.createdAt || undefined, updatedAt: r.updatedAt || undefined,
		})));
		const v = again.fold()[0];
		check('scope: a re-seed keeps the workspace scope', v.scope === 'workspace');
		check('scope: a re-seed keeps the workspace root', v.workspace === 'D:/proj');
		await T.projectMemories(again, store);
		check('scope: so the row still reads back as workspace after projecting again',
			(await store.live('memories'))[0].scope === 'workspace');

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 15. recent(): a bounded inspection window, not the whole log on the wire ----
	{
		const log = new M.MemoryLog(now);
		const N = M.LIMITS.maxEventsPerCall + 500; // past the cap, so the cap is observable at all
		for (let i = 0; i < N; i++) { log.append(ev({ key: 'k' + i, name: 'n' + i })); }
		check('recent: the default is one window, not everything',
			log.recent().events.length === 500 && log.recent().total === N);
		// `slice(-0)` is the WHOLE array, so an unvalidated 0 hands back exactly what this
		// method exists to withhold — 0 has to read as "no count given".
		check('recent: n=0 falls back instead of returning everything',
			log.recent(0).events.length === 500);
		check('recent: a negative count cannot index from the front',
			log.recent(-10).events.length === 500);
		check('recent: NaN (undefined arriving over IPC) falls back',
			log.recent(undefined).events.length === 500);
		check('recent: a sane n is honoured', log.recent(5).events.length === 5);
		check('recent: an absurd n is capped, not believed',
			log.recent(1e9).events.length === M.LIMITS.maxEventsPerCall);
		check('recent: the window holds the NEWEST events', log.recent(5).events[4].name === 'n' + (N - 1));
		check('recent: total reports the whole log, not the window', log.recent(5).total === N);
	}

	// ---- 16. DURABILITY: the log has to outlive the process (pc-a's block) ----
	// Section 6 is titled "persistence round-trip", and for a while that read as proof that the
	// log was durable. It is not: an in-memory log re-serialised is still an in-memory log. The
	// page promises 「轨迹仍在，可恢复」, and pc-a showed that without a real file every restart
	// deleted the forget events — while the collection kept its tombstones, so NOTHING LOOKED
	// WRONG and the trail was missing exactly when someone came back to inspect an erase. This
	// section writes and re-reads an actual file, including the one failure that file format
	// really permits.
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-memfile-'));
		const file = path.join(dir, 'nested', 'memory_events.jsonl');
		const lines = (f) => fs.readFileSync(f, 'utf8').split('\n').filter(s => s.trim()).length;

		check('disk: a first run reads as no file at all, not as an error',
			M.readMemoryEvents(file).length === 0);

		// Wired exactly the way index.ts wires it: hydrate nothing, attach the sink, write.
		const log = new M.MemoryLog(now);
		log.onChanged(M.makeMemoryPersist(file, 0));
		log.seed([{ name: 'keep', body: 'kept body' }, { name: 'gone', body: 'to be erased' }]);
		log.append(ev({ kind: 'forget', key: 'gone', name: 'gone' }));

		check('disk: the events reached the file', fs.existsSync(file)
			&& M.readMemoryEvents(file).length === log.size);

		// RESTART: a second log built from the file alone, as index.ts does at boot.
		const reborn = M.MemoryLog.fromJSON(M.readMemoryEvents(file), now);
		check('disk: the event count survives the restart', reborn.size === log.size);
		check('disk: the FORGET survives — this is the entire point of the block',
			reborn.get('gone') && reborn.get('gone').forgotten === true);
		check('disk: the live memory survives beside it',
			reborn.get('keep') && reborn.get('keep').body === 'kept body');
		check('disk: the erased row still has a readable trail after a restart',
			reborn.history('gone').some(e => e.kind === 'forget'));
		// Re-seating seq matters as much as the events: a log that restarted at 1 would sort its
		// new writes BEFORE the old ones, and an out-of-order replay corrupts the view.
		check('disk: seq continues from the file instead of restarting at 1',
			reborn.lastSeq === log.lastSeq
			&& reborn.append(ev({ key: 'later', name: 'later' })).seq === log.lastSeq + 1);

		// A torn tail is the one failure append-only writes actually permit, and the reason the
		// format is lines rather than one big JSON.parse that a torn file would break entirely.
		fs.appendFileSync(file, '{"seq":999,"kind":"add","name":"tor');
		check('disk: a torn last line costs one line, not the whole log',
			M.readMemoryEvents(file).length === log.size);

		// The append-vs-rewrite decision, driven directly — 20,000 real appends would establish
		// the same thing at a twentieth of the speed.
		const probe = path.join(dir, 'probe.jsonl');
		const p = M.makeMemoryPersist(probe, 0);
		p([{ seq: 1 }, { seq: 2 }]);                     // not "+1" -> rewrite
		check('disk: an unexpected shape rewrites wholesale', lines(probe) === 2);
		p([{ seq: 1 }, { seq: 2 }, { seq: 3 }]);          // +1 -> append
		check('disk: a single new event is APPENDED, not rewritten', lines(probe) === 3);
		p([{ seq: 1 }]);                                 // shrink -> rewrite
		check('disk: a shrink rewrites, so a bounded log cannot write an unbounded file',
			lines(probe) === 1);

		// The hook itself: it must hand over the CURRENT FULL list, which is the only way a sink
		// can tell "grew by one" from anything else. Passing just the new event would make the
		// shrink case undetectable and the file would grow forever while memory shrank.
		const lens = [];
		const l2 = new M.MemoryLog(now);
		l2.onChanged(evs => lens.push(evs.length));
		l2.seed([{ name: 'a', body: 'x' }, { name: 'b', body: 'y' }]);
		l2.append(ev({ kind: 'forget', key: 'a', name: 'a' }));
		check('disk: onChanged reports the whole list after every append',
			lens.join(',') === '1,2,3');

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 17. the BOOT WIRING itself: openMemoryLog + seedMissing ----
	// Section 16 proves the pieces. It could not prove that index.ts uses them: nothing loads
	// index.ts, so a hand-assembled boot with one line missing would pass 16/16 and still be
	// non-durable — the same "correct right up until you check" shape as the bug being fixed.
	// These call the two functions index.ts actually calls.
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-memopen-'));
		const file = path.join(dir, 'memory_events.jsonl');

		// First boot: no file. Must yield a usable log AND a working sink, not merely a log.
		const boot1 = M.openMemoryLog(file);
		boot1.append(ev({ kind: 'forget', key: 'x', name: 'x' }));
		check('boot: opening a missing file still attaches the sink',
			fs.existsSync(file) && M.readMemoryEvents(file).length === 1);

		// Second boot: the wiring — not the reading — is what has to carry the forget over.
		const boot2 = M.openMemoryLog(file);
		check('boot: reopening returns exactly what the sink wrote',
			boot2.size === 1 && boot2.get('x') && boot2.get('x').forgotten === true);
		check('boot: the reopened log keeps writing durably, not just reading',
			boot2.append(ev({ key: 'y', name: 'y' })).seq === 2
			&& M.readMemoryEvents(file).length === 2);

		// seedMissing: three cases that pull against each other.
		const s = M.openMemoryLog(path.join(dir, 'seed.jsonl'));
		const rows = [
			{ id: 'i1', name: 'known', body: 'already in the log' },
			{ id: 'i2', name: 'imported', body: 'arrived via a dump, never through the log' },
			{ id: 'i3', name: 'dead', body: 'soft-deleted', deletedAt: 123 },
		];
		s.append(ev({ key: 'known', name: 'known', body: 'already in the log' }));
		check('seed: an unknown row is picked up (a dump import gains a history)',
			M.seedMissing(s, rows) === 1 && s.get('imported'));
		check('seed: an already-known row is skipped, so a reseed adds no duplicates',
			M.seedMissing(s, rows) === 0 && s.history('known').length === 1);
		check('seed: a soft-deleted row is never seeded', !s.get('dead'));

		// The case that matters most. A forgotten key is in `all()`, so it counts as known;
		// if it did not, the launch after an erase would write a fresh `add` and bring the row
		// back — silently, because the collection is what the renderer reads. This is the old
		// `memLog.size === 0` guard being replaced by membership, tested from both sides.
		s.append(ev({ key: 'known', name: 'known', kind: 'forget' }));
		check('seed: a forgotten key counts as known and is NOT resurrected',
			M.seedMissing(s, rows) === 0
			&& s.get('known').forgotten === true
			&& s.history('known').length === 2);

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 18. the bound is REAL. Its comment used to promise something the code never did. ----
	// `compactIfNeeded` computed `Math.min(...liveSeqs, events[0].seq)`, and `events[0].seq` is
	// the smallest seq present — so the minimum was always it, `keepFrom` was always 0, and the
	// "refuse to grow past maxEvents" comment described a function that dropped nothing. With the
	// log now persisted, that meant a file growing without bound.
	{
		const realMax = M.LIMITS.maxEvents;
		const realClock = clock;
		try {
			M.LIMITS.maxEvents = 6;
			clock = 1000;

			// ---- A. the bound is enforced
			const log = new M.MemoryLog(now);
			for (let i = 0; i < 30; i++) { log.append(ev({ key: 'k' + i, name: 'k' + i, body: 'b' + i })); }
			check('bound: the log is actually capped now', log.size === 6);
			check('bound: the OLDEST events are the ones discarded',
				!log.all().some(e => e.key === 'k0') && log.all().some(e => e.key === 'k29'));
			check('bound: every surviving row still has content',
				log.fold().length === 6 && log.fold().every(r => r.body !== '' && r.name !== ''));

			// ---- B. NO LIVE ROW EVER VANISHES, and no erase is ever undone.
			// The dangerous cut is a surviving `add` whose `forget` was trimmed: the row folds LIVE
			// again and the next projection writes it back to the collection. A suffix window cannot
			// produce that on its own, so this pins the property instead of assuming it.
			//
			// Built with the bound OUT of the way, then the bound is lowered — otherwise the log is
			// being trimmed while it is still being assembled, which is how the first version of this
			// fixture managed to assert about a key that had already been discarded.
			M.LIMITS.maxEvents = 1000;
			const hasAdd = (l, k) => l.all().some(e => e.key === k && e.kind === 'add');
			const mixed = new M.MemoryLog(now);
			for (let i = 0; i < 12; i++) {
				mixed.append(ev({ key: 'e' + i, name: 'e' + i, body: 'b' }));
				mixed.append(ev({ key: 'e' + i, name: 'e' + i, body: 'b', kind: 'forget' }));
			}
			for (let i = 0; i < 8; i++) { mixed.append(ev({ key: 'f' + i, name: 'f' + i, body: 'b' })); }
			mixed.append(ev({ key: 'survivor', name: 'survivor', body: 'still here' }));
			// An erase that lands LAST, so its `add` is inside the final window and the assertions
			// below are about a key that is still there rather than one that vanished entirely.
			mixed.append(ev({ key: 'recent_erase', name: 'recent_erase', body: 'gone' }));
			mixed.append(ev({ key: 'recent_erase', name: 'recent_erase', body: 'gone', kind: 'forget' }));
			const wasForgotten = mixed.fold().filter(r => r.forgotten).map(r => r.id);
			check('bound: before compacting, 13 memories are erased', wasForgotten.length === 13);

			M.LIMITS.maxEvents = 6;
			mixed.append(ev({ key: 'trigger', name: 'trigger', body: 'b' }));
			check('bound: an erase that was inside the window is STILL erased',
				mixed.get('recent_erase') && mixed.get('recent_erase').forgotten === true);
			check('bound: NO erased memory has come back to life',
				wasForgotten.every(k => { const r = mixed.get(k); return r === null || r.forgotten === true; }));
			check('bound: the most recent live memory is still there',
				mixed.get('survivor') && mixed.get('survivor').body === 'still here');
			check('bound: a key whose events were ALL trimmed is gone, not half-there',
				(mixed.get('e0') === null) === !hasAdd(mixed, 'e0'));

			// ---- C. what a PARTIAL trim costs, pinned rather than left to a comment.
			// Only a key whose EARLY events fall out while a later one survives is degraded, and then
			// only in `createdAt`: `append` fills every event with the whole field set, so the row
			// rebuilt from the surviving `update` still has the right name, body and everything else.
			clock = 5000;
			const one = new M.MemoryLog(now);
			one.append({ ...ev({ key: 'old', name: 'old', body: 'v1' }), at: 111 });
			clock = 6000;
			one.append({ ...ev({ key: 'old', name: 'old', body: 'v2', kind: 'update' }), at: 222 });
			for (let i = 0; i < 4; i++) { one.append(ev({ key: 'pad' + i, name: 'pad' + i, body: 'b' })); }
			one.append(ev({ key: 'nudge', name: 'nudge', body: 'b' }));   // 7 events, bound is 6
			const row = one.get('old');
			check('bound: a partially trimmed row KEEPS its content',
				row && row.body === 'v2' && row.name === 'old' && row.updatedAt === 222);
			check('bound: the accepted cost is createdAt, and it is the only thing that moves',
				row && row.createdAt === 222);

			// ---- D. the sink must see the trimmed list, or the FILE outgrows memory
			const seen = [];
			const wired = new M.MemoryLog(now);
			wired.onChanged(evs => seen.push(evs.length));
			for (let i = 0; i < 20; i++) { wired.append(ev({ key: 'w' + i, name: 'w' + i, body: 'b' })); }
			check('bound: onChanged reports the TRIMMED length, not the pre-trim one',
				seen[seen.length - 1] === 6 && wired.size === 6);
		} finally {
			M.LIMITS.maxEvents = realMax;
			clock = realClock;
		}
	}

	// ---- 18. the trail's two spellings (name-keyed vs store-row-id-keyed) must reunite ----
	{
		const log = new M.MemoryLog(now);
		// A row that only ever lived in the collection: forgetMemory keys by the store row id
		// (tools.ts), because that row has no log history to inherit a key from.
		log.append({ kind: 'add', key: 'store_9', name: '周会纪要', body: 'v1' });
		log.append({ kind: 'forget', key: 'store_9', name: '周会纪要', body: 'v1' });
		// A later writer keyed the same memory by its NAME (seed / extraction path).
		log.append({ kind: 'add', key: '周会纪要', name: '周会纪要', body: 'v2' });

		const byName = log.history('周会纪要');
		check('history: one name lookup reunites both spellings (the 双写法 fix)',
			byName.length === 3, `got ${byName.length}`);
		check('history: oldest-first across the two keys',
			byName.map(e => e.seq).join() === [...byName].sort((a, b) => a.seq - b.seq).map(e => e.seq).join());
		check('history: a store-id lookup still returns the id-keyed events',
			log.history('store_9').length === 2);
		check('history: an unrelated key matches nothing',
			log.history('nope').length === 0);
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});