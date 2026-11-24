/* F3 float state tests — drafts, shutter, and the mode switch.
 *
 * Run: npm test:float3
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-float3-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const S = require(path.join(base, 'float', 'state.js'));

(async () => {
	// ---- 1. drafts: the reason this file exists
	{
		const empty = S.emptyDraft();
		check('draft: starts empty at revision 0', empty.text === '' && empty.revision === 0 && empty.attachments.length === 0);
		check('draft: an empty draft has no content', S.draftHasContent(empty) === false);
		check('draft: whitespace alone is not content', S.draftHasContent({ ...empty, text: '   \n ' }) === false);
		check('draft: text is content', S.draftHasContent({ ...empty, text: 'hi' }) === true);
		check('draft: an attachment alone is content',
			S.draftHasContent({ ...empty, attachments: [{ path: 'a.png' }] }) === true);

		// A no-op write must NOT bump the revision, or two idle windows ping-pong forever.
		let d = S.emptyDraft();
		let r = S.applyDraft(d, { text: 'hello' }, 0, 1);
		check('write: an accepted write bumps the revision', r.ok && r.state.revision === 1);
		check('write: it records the writing window', r.state.byWindow === 1);
		const same = S.applyDraft(r.state, { text: 'hello' }, 1, 2);
		check('write: writing identical text does NOT bump the revision',
			same.ok && same.state.revision === 1, );
	}

	// ---- 2. the concurrency rule: a stale write is REFUSED, not merged
	// Two windows can both have the input focused. If both write, one silently loses text.
	{
		const store = new S.DraftStore('s1');
		const a = store.set('s1', { text: 'from main' }, 0, 1);
		check('conflict: first write wins', a.ok && a.state.text === 'from main');
		// Window 2 still believes it is at revision 0.
		const stale = store.set('s1', { text: 'from float' }, 0, 2);
		check('conflict: a stale-revision write is REFUSED', stale.ok === false && stale.reason === 'stale-revision');
		check('conflict: the refusal carries the current state so the window can re-sync',
			stale.current.text === 'from main' && stale.current.revision === 1);
		check('conflict: the store was not modified by the refused write',
			store.get('s1').text === 'from main');
		// After re-syncing, the write succeeds.
		const retry = store.set('s1', { text: 'from float' }, 1, 2);
		check('conflict: re-syncing then writing succeeds', retry.ok && retry.state.text === 'from float');
		check('conflict: the last writer wins once synced', store.get('s1').text === 'from float');
	}

	// ---- 3. per-session isolation
	{
		// Without the session key, a draft typed in conversation A shows up in conversation B.
		const store = new S.DraftStore('s1');
		store.set('s1', { text: 'for A' }, 0, 1);
		store.set('s2', { text: 'for B' }, 0, 1);
		check('session: drafts are isolated by session', store.get('s1').text === 'for A' && store.get('s2').text === 'for B');
		check('session: an unknown session reads as empty', store.get('s3').text === '');
		// Revisions are per-session, so window 2 is not stale in s2 just because it is stale in s1.
		const r2 = store.set('s2', { text: 'B again' }, 1, 2);
		check('session: revisions are tracked per session, not globally', r2.ok === true);
		store.clear('s1');
		check('session: clear resets to empty at revision 0',
			store.get('s1').text === '' && store.get('s1').revision === 0);
		const kept = store.retain(['s2']);
		check('session: retain drops unknown sessions', kept === 1 && store.get('s1').text === '' && store.get('s2').text === 'B again');
		check('session: retain of everything removes nothing', store.retain(['s1', 's2', 's3']) === 0);
		// Only s2 survives: s1 was cleared then dropped by retain, and reading s3 never created it.
		check('session: the map cannot grow unbounded', store.size === 1);
		check('session: reading an unknown session does not create an entry', store.get('s9').revision === 0 && store.size === 1);
	}

	// ---- 4. attachments
	{
		let d = S.emptyDraft();
		d = S.addAttachment(d, { path: 'a.png', width: 100 });
		check('attach: adds an attachment', d.attachments.length === 1);
		const dup = S.addAttachment(d, { path: 'a.png' });
		check('attach: the same path does not stack twice', dup.attachments.length === 1);
		check('attach: an empty path is ignored', S.addAttachment(d, { path: '' }).attachments.length === 1);
		check('attach: no-path object is ignored', S.addAttachment(d, {}).attachments.length === 1);
		d = S.removeAttachment(d, 'a.png');
		check('attach: remove works', d.attachments.length === 0);
		check('attach: removing a missing path is a no-op', S.removeAttachment(d, 'zzz').attachments.length === 0);

		// Re-taking the same screenshot must not stack two chips.
		let base2 = { ...S.emptyDraft(), revision: 0 };
		const w = S.applyDraft(base2, { text: '', attachments: [{ path: 'a.png' }, { path: 'a.png' }, { path: 'b.png' }] }, 0, 1);
		check('attach: duplicate paths are deduped on write', w.state.attachments.length === 2);
		// Without a cap, twenty screenshots make the composer unusable.
		const many = Array.from({ length: 30 }, (_, i) => ({ path: `p${i}.png` }));
		const capped = S.applyDraft(S.emptyDraft(), { text: '', attachments: many }, 0, 1);
		check('attach: the attachment count is capped', capped.state.attachments.length === S.MAX_ATTACHMENTS);
		check('attach: the cap keeps the FIRST attachments (the ones the user took first)',
			capped.state.attachments[0].path === 'p0.png');
		// A whole-file paste must not wedge the store.
		const huge = S.applyDraft(S.emptyDraft(), { text: 'x'.repeat(500000) }, 0, 1);
		check('draft: an enormous paste is truncated', huge.state.text.length === S.MAX_DRAFT_CHARS);
	}

	// ---- 5. shutter: Q2's model — pick a mode, then press the shutter
	{
		const cfg = { mode: 'rect', delaySec: 0, pickTarget: false };
		const step = S.nextShutterStep(cfg, 5);
		check('shutter: rect mode captures immediately with no picker',
			step.kind === 'capture' && step.mode === 'rect');
		// Window mode with more than one candidate needs a picker FIRST.
		const win = S.nextShutterStep({ ...cfg, mode: 'window', pickTarget: true }, 5);
		check('shutter: window mode with several candidates picks a target first', win.kind === 'pick-window');
		check('shutter: the pick step says what to do after', win.then.mode === 'window');
		check('shutter: a single window needs no picker',
			S.nextShutterStep({ ...cfg, mode: 'window' }, 1).kind === 'capture');
		check('shutter: pickTarget=false skips the picker even with many windows',
			S.nextShutterStep({ ...cfg, mode: 'window', pickTarget: false }, 9).kind === 'capture');
		check('shutter: the delay is carried through',
			S.nextShutterStep({ ...cfg, delaySec: 3 }, 5).delaySec === 3);
		check('shutter: a fractional delay is rounded',
			S.nextShutterStep({ ...cfg, delaySec: 2.6 }, 5).delaySec === 3);
		check('shutter: a negative delay becomes 0', S.nextShutterStep({ ...cfg, delaySec: -4 }, 5).delaySec === 0);
		check('shutter: a NaN delay becomes 0', S.nextShutterStep({ ...cfg, delaySec: NaN }, 5).delaySec === 0);

		check('caption: names the mode', S.shutterCaption(cfg) === '截屏（矩形）');
		check('caption: shows the countdown', S.shutterCaption({ ...cfg, delaySec: 5 }).includes('5s'));
	}

	// ---- 6. the secondary menu is 3 radios + 1 shutter, not 4 capture entries
	{
		const cfg = { mode: 'window', delaySec: 0, pickTarget: true };
		const menu = S.buildSecondaryMenu(cfg, true, true);
		const ids = menu.map(m => m.id);
		check('menu: exactly three mode entries', ids.filter(i => i.startsWith('mode-')).length === 3);
		check('menu: exactly ONE shutter', ids.filter(i => i === 'shutter').length === 1);
		check('menu: the chosen mode is the checked radio',
			menu.find(m => m.id === 'mode-window').checked === true
			&& menu.find(m => m.id === 'mode-full').checked === false);
		// The first reading of this feature was "four capture buttons", which would have made
		// the mode non-sticky and the "pick a way then press once" flow impossible.
		check('menu: the mode radios come before the shutter',
			ids.indexOf('mode-window') < ids.indexOf('shutter'));
		check('menu: the link toggle is present in float mode and reports its state',
			menu.find(m => m.id === 'link').checked === true && menu.find(m => m.id === 'link').label === '解除吸附');
		check('menu: unlinked reads as "auto snap on"', S.buildSecondaryMenu(cfg, false, true)
			.find(m => m.id === 'link').label === '自动吸附');
		// `ids` came from the FLOAT-mode menu, so the docked case needs its own listing.
		check('menu: the link toggle is hidden when docked',
			!S.buildSecondaryMenu(cfg, true, false).map(m => m.id).includes('link'));
		check('menu: the dock entry changes label with the mode',
			S.buildSecondaryMenu(cfg, true, true).find(m => m.id === 'dock').label === '切回主窗口'
			&& S.buildSecondaryMenu(cfg, true, false).find(m => m.id === 'dock').label === '悬浮窗模式');
		check('menu: quit is always last', ids[ids.length - 1] === 'quit');
	}

	// ---- 7. mode transitions, and the streaming guard
	{
		let s = { phase: 'docked' };
		check('float: docked has no output window', S.outputWindowVisible(s) === false);
		check('float: docked has no session binding', S.floatSession(s) === null);
		check('float: entering requires no streaming', S.canFloat({ phase: s, streaming: false }).ok === true);

		const f = S.enterFloat(s, 'sess-1', false);
		check('float: entering binds the session', f.phase === 'floating' && S.floatSession(f) === 'sess-1');
		check('float: the output window appears', S.outputWindowVisible(f) === true);
		// Entering mid-stream means the user's message goes to a box they may not be watching.
		const blocked = S.canFloat({ phase: s, streaming: true });
		check('float: entering mid-stream is refused', blocked.ok === false);
		check('float: the refusal explains the actual harm', /看不到/.test(blocked.reason || ''));
		check('float: a refused entry leaves the state untouched',
			S.enterFloat(s, 'sess-1', true).phase === 'docked');

		const back = S.exitFloat(f);
		check('float: exiting returns to docked', back.phase === 'docked');
		check('float: exiting when already docked is a no-op', S.exitFloat(back).phase === 'docked');
		check('float: exiting is lossless — it does not touch the draft at all',
			JSON.stringify(S.exitFloat(f)) === '{"phase":"docked"}');
	}

	// ---- 8. the switch is lossless BECAUSE the draft is external
	// The real R7 guarantee: collapsing and restoring must not lose the text. This models the
	// sequence with the store surviving the "window switch", which is what the store buys us.
	{
		const store = new S.DraftStore('s1');
		store.set('s1', { text: 'half typed sentence' }, 0, 1);
		// User collapses the input box -> main window closes.
		let s = S.enterFloat({ phase: 'docked' }, 's1', false);
		// The float window is a different renderer; it reads the draft from the store.
		check('R7: the float window reads the draft the main window had',
			store.get('s1').text === 'half typed sentence');
		// User continues typing there, then switches back.
		store.set('s1', { text: 'half typed sentence, finished' }, store.get('s1').revision, 2);
		s = S.exitFloat(s);
		check('R7: after returning to the main window the draft is intact',
			store.get('s1').text === 'half typed sentence, finished');
		// And an attachment taken while floating survives too.
		const withShot = S.addAttachment(store.get('s1'), { path: 'shot.png', width: 800 });
		store.forceSet('s1', withShot);
		S.exitFloat(S.enterFloat({ phase: 'docked' }, 's1', false));
		check('R7: an attachment taken while floating survives the round trip',
			store.get('s1').attachments.length === 1 && store.get('s1').attachments[0].path === 'shot.png');
	}

	// ---- 9. hostile input to the store
	{
		const store = new S.DraftStore('s1');
		check('store: a non-numeric base revision is refused',
			store.set('s1', { text: 'x' }, NaN, 1).ok === false);
		check('store: a missing text becomes empty, not undefined',
			store.set('s1', {}, 0, 1).state.text === '');
		check('store: a non-array attachments field does not throw',
			Array.isArray(store.set('s1', { text: 'y', attachments: 'nope' }, store.get('s1').revision, 1).state.attachments));
		check('store: null attachments does not throw',
			Array.isArray(store.set('s1', { text: 'z', attachments: null }, store.get('s1').revision, 1).state.attachments));
		check('store: get on a garbage session id still returns a usable draft',
			store.get(undefined).revision === 0);
		// forceSet is the teardown path; it must copy rather than alias.
		const d = store.get('s1');
		store.forceSet('s1', d);
		d.text = 'mutated outside';
		check('store: forceSet copies rather than aliasing', store.get('s1').text !== 'mutated outside');
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});