/* SessionStore tests — grouping sessions by project.
 *
 * Pure Node: sessions.ts imports node:fs and types only, so none of this needs a display or
 * Electron. It lives in its own file rather than in peer-integration because that suite is about
 * peers, and a session assertion buried there is a session assertion nobody re-runs.
 *
 * The property that matters most here: a `projectId` is a reference ACROSS two stores. Sessions
 * live in `chat_sessions.json`, projects live in the `projects` collection, and nothing cascades on
 * its own — so the only thing standing between "deleted a project" and "a sidebar group with no
 * title" is `clearProjectFor` being called. That is asserted from both ends.
 *
 * Run: npm run test:sessions
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-sessions-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const { SessionStore } = require(path.join(base, 'ai', 'sessions.js'));

const FILE = 'chat_sessions.json';

(async () => {
	// ---- 1. create: the link is optional in every direction
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess1-'));
		const st = new SessionStore(dir);

		const grouped = await st.create('有分组的', 'p_alpha');
		check('create: the project link is stored', grouped.projectId === 'p_alpha');

		const plain = await st.create('没有分组的');
		check('create: omitting it leaves the field absent',
			plain.projectId === undefined && !('projectId' in plain));

		// Blank is what a cleared text input produces. Storing it would make a group whose title
		// is empty — a group the UI cannot label and never asked for.
		for (const blank of ['', '   ', null, undefined]) {
			const s = await st.create('空链接', blank);
			check('create: a blank link (' + JSON.stringify(blank) + ') means no project',
				s.projectId === undefined && !('projectId' in s));
		}

		// An id longer than anything we mint can only be a paste error. Storing it would create a
		// reference that can never match a project — precisely the dangling case this normalises away.
		const huge = await st.create('超长', 'x'.repeat(500));
		check('create: an over-long id is dropped rather than stored', huge.projectId === undefined);

		// The file itself: an ungrouped session must not grow a key, so a file written by someone
		// who never uses grouping is byte-identical in shape to one written before this feature.
		const onDisk = JSON.parse(fs.readFileSync(path.join(dir, FILE), 'utf8'));
		const diskGrouped = onDisk.filter(s => s.projectId === 'p_alpha');
		const diskUngrouped = onDisk.filter(s => s.id === plain.id);
		check('persist: the link is on disk for a grouped session', diskGrouped.length === 1);
		check('persist: an ungrouped session has NO projectId key on disk',
			diskUngrouped.length === 1 && !('projectId' in diskUngrouped[0]));

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 2. the link survives a restart, which is the whole point of persisting it
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess2-'));
		const st = new SessionStore(dir);
		const s = await st.create('重启前', 'p_beta');

		const reopened = new SessionStore(dir);
		const [back] = await reopened.list();
		check('restart: the project link survives', back.id === s.id && back.projectId === 'p_beta');

		// A file written before this feature existed has no such field anywhere. It must load.
		fs.writeFileSync(path.join(dir, FILE), JSON.stringify([
			{ id: 's_old', title: '老会话', createdAt: 1, updatedAt: 2, pinned: false, history: [], rows: [] },
		]), 'utf8');
		const legacy = new SessionStore(dir);
		const [old] = await legacy.list();
		check('legacy: a session file with no projectId loads unchanged',
			old.id === 's_old' && old.projectId === undefined && !('projectId' in old));

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 3. setProject: move, ungroup, and honest failures
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess3-'));
		const st = new SessionStore(dir);
		const s = await st.create('原始');
		check('set: starts ungrouped', s.projectId === undefined);

		check('set: an unknown session id is refused, not silently ignored',
			await st.setProject('s_nope', 'p_x') === false);
		check('set: a known id moves', await st.setProject(s.id, 'p_x') === true
			&& (await st.list()).find(x => x.id === s.id).projectId === 'p_x');

		// Every spelling of "no project", including the empty string the UI would send.
		for (const none of [null, '', '   ']) {
			await st.setProject(s.id, 'p_x');
			check('set: ' + JSON.stringify(none) + ' ungroups',
				await st.setProject(s.id, none) === true
				&& (await st.list()).find(x => x.id === s.id).projectId === undefined);
		}

		// Whitespace around a real id is trimmed rather than stored, or the same project would end
		// up as two groups that look identical.
		await st.setProject(s.id, '  p_trim  ');
		check('set: a padded id is trimmed', (await st.list()).find(x => x.id === s.id).projectId === 'p_trim');

		// Hostile input must not throw or store junk.
		for (const junk of [42, {}, [], true, 'z'.repeat(400)]) {
			let threw = false;
			try { await st.setProject(s.id, junk); } catch { threw = true; }
			const now = (await st.list()).find(x => x.id === s.id).projectId;
			check('set: hostile ' + JSON.stringify(junk).slice(0, 24) + ' does not throw and stores a sane value',
				!threw && (now === undefined || typeof now === 'string'));
		}

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 4. updatedAt: grouping is an edit, pinning is not
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess4-'));
		const st = new SessionStore(dir);
		const s = await st.create('时间戳', 'p_a');
		const before = s.updatedAt;

		await new Promise(r => setTimeout(r, 5));
		await st.setProject(s.id, 'p_b');
		const afterGroup = (await st.list()).find(x => x.id === s.id).updatedAt;
		// Consistent with `rename`, which already bumps it: a metadata edit is an edit.
		check('time: grouping bumps updatedAt, like rename does', afterGroup > before);

		await new Promise(r => setTimeout(r, 5));
		const beforePin = afterGroup;
		await st.pin(s.id, true);
		const afterPin = (await st.list()).find(x => x.id === s.id).updatedAt;
		// Pinning is NOT activity. Bumping it here would let a pin reorder the list — a behaviour
		// change nobody asked for, made while adding an unrelated feature.
		check('time: pinning does NOT bump updatedAt', afterPin === beforePin);

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 5. clearProjectFor: the cross-store reference actually holds
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess5-'));
		const st = new SessionStore(dir);
		const a = await st.create('A', 'p_gone');
		const b = await st.create('B', 'p_gone');
		const c = await st.create('C', 'p_stays');
		const d = await st.create('D');

		check('clear: it reports how many sessions it touched',
			await st.clearProjectFor('p_gone') === 2);
		const after = await st.list();
		const byId = (id) => after.find(x => x.id === id);
		check('clear: every session in that project lost the link',
			byId(a.id).projectId === undefined && byId(b.id).projectId === undefined);
		check('clear: a session in another project is untouched', byId(c.id).projectId === 'p_stays');
		check('clear: an ungrouped session is untouched', byId(d.id).projectId === undefined);

		// No dangling reference survives — the assertion the whole cascade exists for.
		check('clear: NO session points at the deleted project',
			after.every(s => s.projectId !== 'p_gone'));

		// And it is on disk, not just in memory.
		const disk = JSON.parse(fs.readFileSync(path.join(dir, FILE), 'utf8'));
		check('clear: the cleared link is persisted', disk.every(s => s.projectId !== 'p_gone'));

		check('clear: an unknown project clears nothing and reports 0',
			await st.clearProjectFor('p_never_existed') === 0);
		check('clear: a blank project id is a no-op, not a wipe-everything',
			await st.clearProjectFor('') === 0 && byId(c.id).projectId === 'p_stays');
		check('clear: null is a no-op too', await st.clearProjectFor(null) === 0
			&& byId(c.id).projectId === 'p_stays');

		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 6. two stores over one file must not lose each other's writes
	// The single-flight load and the write chain exist for exactly this; grouping adds another
	// writer, so the invariant is worth re-asserting from this side too.
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-sess6-'));
		const a = new SessionStore(dir);
		const b = new SessionStore(dir);
		const sa = await a.create('来自 A', 'p_a');
		const sb = await b.create('来自 B', 'p_b');
		const rows = JSON.parse(fs.readFileSync(path.join(dir, FILE), 'utf8'));
		check('concurrency: both sessions are on disk', rows.length === 2
			&& rows.some(s => s.id === sa.id) && rows.some(s => s.id === sb.id));
		check('concurrency: both links survived', rows.find(s => s.id === sa.id).projectId === 'p_a'
			&& rows.find(s => s.id === sb.id).projectId === 'p_b');

		fs.rmSync(dir, { recursive: true, force: true });
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
