/* Card store (stage 2 of the card plan): cards become first-class persisted entities.
 *
 * What this file guards, beyond the happy path:
 *
 *   - CAPTURE IS GATED TWICE: the fence body must PARSE and must pass its validator. A card
 *     that fails either never enters the store — and a broken fence must not stop the scan,
 *     or one bad card would silently hide every card after it in the same row.
 *   - IDS ARE POSITIONAL, so a re-scan UPSERTS: capture the same row twice and there must be
 *     one entity per fence, not two, and `at` must not move. This is what makes rescanning on
 *     every settle / load safe, which is how a crash window (rows saved, capture not) heals.
 *   - THE LIFECYCLE RULE IS ASYMMETRIC ON PURPOSE: reconcile drops a proposal card whose row
 *     is gone and KEEPS a view card. Getting that backwards is exactly the "persistent" the
 *     plan asked for, so it is asserted in both directions.
 *   - A VIEW DELETE TOMBSTONES: capture the same live row again after removing a view card and
 *     it must STAY removed. A delete the next rescan undoes is not a delete.
 *   - A PROPOSAL DELETE REFUSES with a reason (its fence is still in the row — re-adding is
 *     certain, so an honest no beats a delete that lies).
 *   - cards.json is treated like every other data file: corrupt bytes are QUARANTINED, and a
 *     file that parses but is not the right shape REFUSES rather than reading as empty (the
 *     silent-empty path is how the next write destroys the original).
 *   - index.ts / preload / contract-drift wiring is asserted at source level (comments
 *     stripped first — a lesson this project has paid for more than once).
 *
 * Run: npm run test:cardstore
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-cardstore.txt');
const out = [];
let failed = 0;
function check(name, cond, extra) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ROOT = path.join(__dirname, '..');
const TEST = path.join(ROOT, '.test');
const ai = path.join(TEST, 'src', 'main', 'ai');
const { CardStore } = require(path.join(ai, 'card-store.js'));
const { RowBridge } = require(path.join(ai, 'rows.js'));
const { CARD_SPECS, cardSpec } = require(path.join(TEST, 'shared', 'cards.js'));

const tmp = (tag) => fs.mkdtempSync(path.join(os.tmpdir(), `pcb-cardstore-${tag}-`));
/** A closed card fence. Kept as a helper so every test's text reads the same way. */
const fence = (kind, body) => '```' + kind + '\n' + body + '\n```';
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

(async () => {
	// ---------- lifecycle is declared on the catalogue, not inferred downstream ----------
	{
		const lc = Object.fromEntries(CARD_SPECS.map(s => [s.kind, s.lifecycle]));
		check('catalogue: every kind declares a lifecycle',
			CARD_SPECS.every(s => s.lifecycle === 'proposal' || s.lifecycle === 'view'),
			JSON.stringify(lc));
		check('catalogue: list is the view, the rest are proposals',
			lc.list === 'view' && lc.plan === 'proposal' && lc.qa === 'proposal'
			&& lc.schedule === 'proposal' && lc.checklist === 'proposal',
			JSON.stringify(lc));
	}

	// ---------- capture: two gates, positional ids, idempotent ----------
	{
		const dir = tmp('cap');
		const store = new CardStore(dir);
		const sid = 's_one', r1 = 'r_1';
		const text = '前言。\n' + fence('plan', '{"steps":["a","b"]}')
			+ '\n中间。\n' + fence('list', '{"collection":"todos"}') + '\n收尾。';

		const n = await store.capture(sid, r1, text);
		check('capture: two valid fences become two entities', n === 2, `n=${n}`);
		let list = await store.list({ sessionId: sid });
		check('capture: ids are positional c_<sid>_<row>_<index>',
			list.length === 2 && list.some(c => c.id === `c_${sid}_${r1}_0`)
			&& list.some(c => c.id === `c_${sid}_${r1}_1`),
			list.map(c => c.id).join(','));
		check('capture: kind / sessionId / rowId are recorded',
			list.every(c => c.sessionId === sid && c.rowId === r1)
			&& list.find(c => c.id.endsWith('_0')).kind === 'plan'
			&& list.find(c => c.id.endsWith('_1')).kind === 'list');
		check('capture: schemaVersion comes from the spec',
			list.every(c => c.schemaVersion === (cardSpec(c.kind) || {}).version));
		check('capture: payload is the parsed JSON',
			JSON.stringify(list.find(c => c.id.endsWith('_0')).payload) === '{"steps":["a","b"]}');
		const ats = list.map(c => c.at);

		// same text again: upsert, not duplicate
		const again = await store.capture(sid, r1, text);
		list = await store.list({ sessionId: sid });
		check('capture: a re-scan upserts (no duplicate, no change reported)',
			again === 0 && list.length === 2);
		check('capture: a re-scan does not move `at`',
			list.map(c => c.at).sort().join() === ats.slice().sort().join());

		// the SECOND gate: validator rejects, parse errors skip, later fences still scan
		const r2 = 'r_2';
		const mixed = fence('plan', '{not json at all}')
			+ '\n' + fence('checklist', '{"items":[123]}')  // validator: items must be strings
			+ '\n' + fence('list', '{"collection":"notes"}'); // must still be reached
		const m = await store.capture(sid, r2, mixed);
		const r2cards = await store.list({ sessionId: sid, rowId: r2 });
		check('capture: unparseable + invalid fences are skipped, a later VALID fence still lands',
			m === 1 && r2cards.length === 1 && r2cards[0].kind === 'list'
			&& r2cards[0].id === `c_${sid}_${r2}_2`,
			`m=${m} ids=${r2cards.map(c => c.id).join(',')}`);

		// row-scoped prune: the text no longer supports a card, the card goes
		const one = await store.capture(sid, r1, fence('plan', '{"steps":["a","b"]}'));
		list = await store.list({ sessionId: sid, rowId: r1 });
		check('capture: a fence edited OUT of the row prunes that card', one === 1 && list.length === 1);
		const none = await store.capture(sid, r1, '现在整段没有卡片了。');
		list = await store.list({ sessionId: sid, rowId: r1 });
		check('capture: a row that lost all fences prunes all its cards', none === 1 && list.length === 0);

		// content edit updates in place, keeping `at`
		await store.capture(sid, r1, fence('plan', '{"steps":["a"]}'));
		const before = (await store.list({ sessionId: sid, rowId: r1 }))[0];
		await store.capture(sid, r1, fence('plan', '{"steps":["a","b"]}'));
		const after = (await store.list({ sessionId: sid, rowId: r1 }))[0];
		check('capture: an edited payload updates the SAME entity',
			after.id === before.id && JSON.stringify(after.payload) === '{"steps":["a","b"]}'
			&& after.at === before.at && after.updatedAt >= before.updatedAt,
			JSON.stringify(after));
	}

	// ---------- reconcile: proposals die with their row, views do not ----------
	{
		const dir = tmp('rec');
		const store = new CardStore(dir);
		const sid = 's_two';
		await store.capture(sid, 'r_prop', fence('plan', '{"steps":["x"]}'));
		await store.capture(sid, 'r_view', fence('list', '{"collection":"todos"}'));
		await store.capture('s_other', 'r_prop', fence('plan', '{"steps":["y"]}'));

		const dropped = await store.reconcile(sid, new Set(['r_view']));
		const list = await store.list({ sessionId: sid });
		check('reconcile: a proposal whose row is gone is dropped (and only it)',
			dropped === 1 && list.length === 1 && list[0].kind === 'list', `dropped=${dropped}`);
		const kept = await store.reconcile(sid, new Set());
		check('reconcile: a VIEW card survives even an empty row set (the persistence rule)',
			kept === 0 && (await store.list({ sessionId: sid })).length === 1);
		check('reconcile: another session is untouched',
			(await store.list({ sessionId: 's_other' })).length === 1);
	}

	// ---------- remove: proposals refuse, views tombstone ----------
	{
		const dir = tmp('rm');
		const store = new CardStore(dir);
		const sid = 's_three';
		await store.capture(sid, 'r1', fence('plan', '{"steps":["a"]}'));
		await store.capture(sid, 'r2', fence('list', '{"collection":"todos"}'));
		const prop = (await store.list({ sessionId: sid, rowId: 'r1' }))[0];
		const view = (await store.list({ sessionId: sid, rowId: 'r2' }))[0];

		const rp = await store.remove(prop.id);
		check('remove: a proposal card refuses WITH a reason',
			rp.ok === false && typeof rp.reason === 'string' && /回滚|清空/.test(rp.reason), JSON.stringify(rp));
		check('remove: the refused proposal is still there',
			(await store.list({ sessionId: sid, rowId: 'r1' })).length === 1);

		const rv = await store.remove(view.id);
		check('remove: a view card is removed', rv.ok === true);
		check('remove: it is gone from the list',
			(await store.list({ sessionId: sid, rowId: 'r2' })).length === 0);

		// resurrection must be impossible: the fence is still in that live row
		await store.capture(sid, 'r2', fence('list', '{"collection":"todos"}'));
		check('remove: a removed view card is NOT resurrected by the next capture (tombstone)',
			(await store.list({ sessionId: sid, rowId: 'r2' })).length === 0);

		const rn = await store.remove('c_nope');
		check('remove: an unknown id refuses honestly', rn.ok === false && !!rn.reason);
	}

	// ---------- pin: flag only, no updatedAt bump, sorted first ----------
	{
		const dir = tmp('pin');
		const store = new CardStore(dir);
		const sid = 's_four';
		await store.capture(sid, 'r1', fence('list', '{"collection":"todos"}'));
		await store.capture(sid, 'r2', fence('list', '{"collection":"notes"}'));
		const first = (await store.list({ sessionId: sid }))[0];
		const before = first.updatedAt;

		const p = await store.pin(first.id, true);
		let list = await store.list({ sessionId: sid });
		const pinned = list.find(c => c.id === first.id);
		check('pin: sets the flag without bumping updatedAt (pin is not activity)',
			p.ok === true && pinned.pinned === true && pinned.updatedAt === before);
		check('pin: pinned sorts first', list[0].id === first.id);

		await store.pin(first.id, false);
		list = await store.list({ sessionId: sid });
		check('pin: unpinning removes the key entirely',
			list.find(c => c.id === first.id).pinned === undefined);
		check('pin: unknown id refuses honestly', (await store.pin('c_nope', true)).ok === false);
	}

	// ---------- the file: quarantine on damage, refusal on wrong shape, round-trip ----------
	{
		const dir = tmp('file');
		fs.writeFileSync(path.join(dir, 'cards.json'), '{ this is not json', 'utf8');
		const store = new CardStore(dir);
		const list = await store.list();
		const quarantined = fs.readdirSync(dir).filter(f => /^cards\.json\.corrupt-/.test(f));
		check('file: a corrupt cards.json is quarantined, not read as empty', list.length === 0 && quarantined.length === 1,
			fs.readdirSync(dir).join(','));
		await store.capture('s_x', 'r1', fence('list', '{"collection":"todos"}'));
		check('file: the store still works after quarantine',
			(await store.list({ sessionId: 's_x' })).length === 1);

		// parses, but is not our shape -> refuse loudly instead of "empty"
		const dir2 = tmp('shape');
		fs.writeFileSync(path.join(dir2, 'cards.json'), '{"cards":"nope"}', 'utf8');
		let refused = false;
		try { await new CardStore(dir2).list(); } catch { refused = true; }
		check('file: a parseable-but-wrong shape REFUSES rather than reading as empty', refused);

		// round-trip through a second instance
		const dir3 = tmp('round');
		const a = new CardStore(dir3);
		await a.capture('s_y', 'r1', fence('schedule', '{"items":[{"title":"会"}],"note":"n"}'));
		await a.pin('c_s_y_r1_0', true);
		const b = new CardStore(dir3);
		const back = await b.list({ sessionId: 's_y' });
		check('file: a second instance reads the same entities back (ids, payload, pin)',
			back.length === 1 && back[0].id === 'c_s_y_r1_0' && back[0].pinned === true
			&& JSON.stringify(back[0].payload).includes('会'), JSON.stringify(back));
	}

	// ---------- dropSession takes every card, view cards included ----------
	{
		const dir = tmp('drop');
		const store = new CardStore(dir);
		await store.capture('s_keep', 'r1', fence('list', '{"collection":"todos"}'));
		await store.capture('s_gone', 'r1', fence('list', '{"collection":"todos"}'));
		await store.capture('s_gone', 'r2', fence('plan', '{"steps":["z"]}'));
		const n = await store.dropSession('s_gone');
		check('dropSession: removes ALL of that session\'s cards (views too)',
			n === 2 && (await store.list({ sessionId: 's_gone' })).length === 0);
		check('dropSession: leaves another session alone',
			(await store.list({ sessionId: 's_keep' })).length === 1);
	}

	// ---------- RowBridge actually drives the sink (runtime, not just source) ----------
	{
		const calls = [];
		const sink = {
			capture: (sid, rowId, text) => { calls.push({ op: 'capture', sid, rowId, hasText: !!text }); },
			reconcile: (sid, live) => { calls.push({ op: 'reconcile', sid, live: [...live].sort() }); },
		};
		const bridge = new RowBridge(() => { }, async () => { }, () => 's_bridge', sink);

		bridge.user('问');
		bridge.text('答复 ' + fence('plan', '{"steps":["a"]}'));
		bridge.settleAssistant();
		const cap = calls.filter(c => c.op === 'capture');
		check('bridge: settle captures the closed assistant row with its own sessionId',
			cap.length === 1 && cap[0].sid === 's_bridge' && cap[0].hasText === true, JSON.stringify(calls));
		const rowId = cap[0].rowId;

		bridge.rollbackUsers(1);
		const rec = calls.filter(c => c.op === 'reconcile');
		check('bridge: rollback reconciles (proposal rows that went out of scope)',
			rec.length >= 1 && rec[rec.length - 1].sid === 's_bridge', JSON.stringify(calls));

		calls.length = 0;
		bridge.replaceAll([{ rowId: 'r_live', kind: 'assistant', text: fence('list', '{"collection":"todos"}'), done: true }]);
		check('bridge: replaceAll captures live rows AND reconciles against them',
			calls.some(c => c.op === 'capture' && c.rowId === 'r_live')
			&& calls.some(c => c.op === 'reconcile' && c.live.join() === 'r_live'),
			JSON.stringify(calls));
		check('bridge: the settled row id is a real row (r_ prefix from the bridge)',
			typeof rowId === 'string' && rowId.startsWith('r_'), rowId);

		calls.length = 0;
		bridge.clear();
		check('bridge: clear reconciles against an EMPTY live set',
			calls.some(c => c.op === 'reconcile' && c.live.length === 0), JSON.stringify(calls));

		// a bridge with no sink must still work (three-argument construction stays valid)
		let threw = false;
		try {
			const bare = new RowBridge(() => { }, async () => { }, () => 's_bare');
			bare.user('x');
			bare.settleAssistant();
			bare.clear();
		} catch { threw = true; }
		check('bridge: a sink-less bridge behaves exactly as before', threw === false);
	}

	// ---------- source-level wiring (comments stripped first) ----------
	{
		const rowsSrc = strip(fs.readFileSync(path.join(ROOT, 'src/main/ai/rows.ts'), 'utf8'));
		check('rows.ts: constructor takes the optional RowCardSink',
			/cards\?\s*:\s*RowCardSink/.test(rowsSrc));
		check('rows.ts: settleAssistant captures',
			/settleAssistant\([\s\S]*?captureCards\(\)/.test(rowsSrc));
		check('rows.ts: replaceAll captures and reconciles',
			/replaceAll\([\s\S]*?captureCards\(\)[\s\S]*?reconcileCards\(\)/.test(rowsSrc));
		check('rows.ts: rollbackUsers reconciles',
			/rollbackUsers\([\s\S]*?reconcileCards\(\)/.test(rowsSrc));
		check('rows.ts: clear reconciles',
			/clear\(\)\s*:[\s\S]*?reconcileCards\(\)/.test(rowsSrc));

		const idx = strip(fs.readFileSync(path.join(ROOT, 'src/main/index.ts'), 'utf8'));
		check('index.ts: CardStore is constructed on dataDir', /new CardStore\(dataDir\)/.test(idx));
		check('index.ts: the main bridge is given cardSink',
			/new RowBridge\([\s\S]{0,400}?\(\) => sessions\.active\(\),\s*\n\s*cardSink/.test(idx));
		check('index.ts: each run\'s bridge is given cardSink',
			/new RowBridge\([\s\S]{0,400}?\(\) => sid,\s*\n\s*cardSink/.test(idx));
		check('index.ts: cardList/cardPin/cardRemove handlers exist',
			/ipcMain\.handle\(IPC_CHAT\.cardList/.test(idx)
			&& /ipcMain\.handle\(IPC_CHAT\.cardPin/.test(idx)
			&& /ipcMain\.handle\(IPC_CHAT\.cardRemove/.test(idx));
		check('index.ts: deleting a session drops its cards',
			/ipcMain\.handle\(IPC\.sessionRemove,[\s\S]{0,400}?dropSession/.test(idx));

		const pre = strip(fs.readFileSync(path.join(ROOT, 'src/preload/index.ts'), 'utf8'));
		check('preload: all three channels are bridged',
			/ipcRenderer\.invoke\(IPC_CHAT\.cardList/.test(pre)
			&& /ipcRenderer\.invoke\(IPC_CHAT\.cardPin/.test(pre)
			&& /ipcRenderer\.invoke\(IPC_CHAT\.cardRemove/.test(pre));

		const drift = strip(fs.readFileSync(path.join(ROOT, 'test/contract-drift.test.cjs'), 'utf8'));
		check('drift: the three card-entity handlers are classified renderer-only',
			/cardList:/.test(drift) && /cardPin:/.test(drift) && /cardRemove:/.test(drift));
		check('drift: CardEntity is registered as a singly-declared shape',
			/'CardEntity'/.test(drift));

		const cardsSrc = strip(fs.readFileSync(path.join(ROOT, 'shared/cards.ts'), 'utf8'));
		const lcCount = (cardsSrc.match(/lifecycle:/g) || []).length;
		check('shared/cards.ts: lifecycle is REQUIRED (declared on all five specs)',
			lcCount >= 6, `lifecycle: occurrences = ${lcCount}`); // 1 in the interface + 5 specs
	}
})()
	.then(() => { process.exit(failed === 0 ? 0 : 1); })
	.catch(e => { out.push('THREW  ' + (e && e.stack || e)); flush(); process.exit(1); });
