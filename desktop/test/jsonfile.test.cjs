/* Quarantine tests: a file we could not read must never be overwritten by the next write.
 *
 * This file exists because every JSON store in this app used to open with
 *   try { JSON.parse(await fs.readFile(p)) } catch { /* first run: empty *\/ }
 * which gives the SAME answer — an empty list — for "no file yet" and "file we could not read".
 * The first is fine. The second is a data-loss bug, because the next `upsert` calls `save()` and
 * stringifies that empty list over the original. One truncated file away from the user's whole
 * schedule collection, with no error shown anywhere.
 *
 * Section 3 is the reproduction: it drives the REAL Store through the exact sequence that used
 * to destroy data (corrupt file -> load -> upsert -> save) and then asserts the original bytes
 * are still recoverable from disk. Sections 1/2/4 pin the helper's individual rules so a future
 * edit cannot quietly reintroduce the conflation.
 *
 * Compile first (package.json test:build now starts with src/main/jsonfile.ts).
 * Run: npm run test:jsonfile
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-jsonfile.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const { readJsonFile, writeJsonFile } = require(path.join(base, 'jsonfile.js'));
const { Store } = require(path.join(base, 'store', 'store.js'));

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-jsonfile-'));
const p = (n) => path.join(tmpRoot, n);

(async () => {
	// ---- 1. first run must not look like a failure ----
	{
		const r = await readJsonFile(p('absent.json'), []);
		check('ENOENT -> fallback', JSON.stringify(r.value) === '[]');
		check('ENOENT -> NO error (a first run is not a problem)', r.error === undefined);
		check('ENOENT -> not quarantined', r.quarantinedTo === undefined);
		check('ENOENT leaves nothing on disk', fs.readdirSync(tmpRoot).length === 0);
	}

	// ---- 2. a healthy file is just read ----
	{
		fs.writeFileSync(p('ok.json'), JSON.stringify([{ id: 'a' }, { id: 'b' }]), 'utf8');
		const r = await readJsonFile(p('ok.json'), []);
		check('valid JSON -> parsed', r.value.length === 2 && r.value[0].id === 'a');
		check('valid JSON -> no error, no quarantine', !r.error && !r.quarantinedTo);
		check('valid JSON leaves the file where it was', fs.existsSync(p('ok.json')));
	}

	// ---- 3. THE BUG: corrupt -> load -> upsert -> save must not destroy the original ----
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-store-corrupt-'));
		const original = '{"id":"important","title":"keep me","dele';   // truncated mid-write
		fs.writeFileSync(path.join(dir, 'todos.json'), original, 'utf8');

		const store = new Store(dir);
		const list = await store.load('todos');
		check('corrupt: load falls back to empty instead of throwing', Array.isArray(list) && list.length === 0);

		const quarantined = fs.readdirSync(dir).filter(f => f.includes('.corrupt-'));
		check('corrupt: the unreadable original was MOVED ASIDE, not left to be overwritten',
			quarantined.length === 1);
		check('corrupt: the original bytes survive verbatim in the quarantine',
			quarantined.length === 1
			&& fs.readFileSync(path.join(dir, quarantined[0]), 'utf8') === original);
		check('corrupt: the live file is gone (so the next write cannot collide with it)',
			!fs.existsSync(path.join(dir, 'todos.json')));

		// The step that used to wipe it.
		await store.upsert('todos', { id: 'n1', title: 'new' });
		check('corrupt: after the next write, the original is STILL recoverable from disk',
			fs.readdirSync(dir).some(f => f.includes('.corrupt-')
				&& fs.readFileSync(path.join(dir, f), 'utf8') === original));
		check('corrupt: and the new write landed as valid JSON',
			JSON.parse(fs.readFileSync(path.join(dir, 'todos.json'), 'utf8')).length === 1);
		check('corrupt: no leftover .tmp from the atomic write',
			!fs.readdirSync(dir).some(f => f.endsWith('.tmp')));
		fs.rmSync(dir, { recursive: true, force: true });
	}

	// ---- 4. unreadable is NOT the same as absent: refuse instead of answering "empty" ----
	{
		// A directory where a file is expected -> EISDIR on read. Returning `fallback` here would
		// be the same conflation as before, just moved to a different error code.
		fs.mkdirSync(p('asdir.json'));
		let threw = false;
		try { await readJsonFile(p('asdir.json'), []); } catch { threw = true; }
		check('unreadable (EISDIR) throws rather than returning the fallback', threw);
		fs.rmSync(p('asdir.json'), { recursive: true, force: true });
	}

	// ---- 5. writeJsonFile is atomic in practice: valid, complete, no temp left behind ----
	{
		fs.writeFileSync(p('w.json'), JSON.stringify([1, 2, 3]), 'utf8');
		await writeJsonFile(p('w.json'), [{ id: 'x' }], 1);
		check('write replaces the target with valid JSON',
			JSON.parse(fs.readFileSync(p('w.json'), 'utf8'))[0].id === 'x');
		check('write leaves no .tmp behind', !fs.readdirSync(tmpRoot).some(f => f.endsWith('.tmp')));

		await writeJsonFile(p('fresh.json'), { a: 1 });
		check('write creates a file that did not exist', fs.existsSync(p('fresh.json')));
		check('indent is optional and respected',
			fs.readFileSync(p('fresh.json'), 'utf8').includes('\n') === false);
	}

	// ---- 6. corrupt KV behaves the same (kv holds sync switches, handoff, automation anchors) ----
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-store-kv-'));
		fs.writeFileSync(path.join(dir, 'kv.json'), 'not json at all', 'utf8');
		const store = new Store(dir);
		check('corrupt kv: kvGet answers undefined, not a crash',
			(await store.kvGet('sync.dataEnabled')) === undefined);
		check('corrupt kv: the broken file was quarantined',
			fs.readdirSync(dir).some(f => f.includes('.corrupt-')));
		await store.kvPut('sync.dataEnabled', 'true');
		check('corrupt kv: after kvPut the original garbage is still on disk',
			fs.readdirSync(dir).some(f => f.includes('.corrupt-')
				&& fs.readFileSync(path.join(dir, f), 'utf8') === 'not json at all'));
		fs.rmSync(dir, { recursive: true, force: true });
	}

	fs.rmSync(tmpRoot, { recursive: true, force: true });
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
