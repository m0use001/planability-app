/* Row events must say WHICH SESSION they belong to.
 *
 * Sub-agent runs / peers / background automations stream their rows on the same chat:event
 * channel as the main chat (one RowBridge per run in index.ts). Before the label existed the
 * renderer could not tell them apart, so a concurrent `task` run wrote its transcript into
 * whatever conversation was on screen. The renderer now drops events whose sessionId is not
 * the session it displays — but that filter is only as good as the stamping tested here.
 *
 * Run: node test/row-session-label.test.cjs  (needs `npm run test:build` first)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-row-session-label.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const root = path.join(__dirname, '..');
const rowsJs = path.join(root, '.test', 'src', 'main', 'ai', 'rows.js');
const { RowBridge } = require(rowsJs);

// Every event a full turn produces, captured with the label it carried.
function drive(label) {
	const seen = [];
	const bridge = new RowBridge(e => seen.push(e), async () => {}, () => label);
	bridge.replaceAll([{ rowId: 'r_seed', kind: 'user', text: 'hi' }], true);
	bridge.user('what');
	bridge.text('a');
	bridge.reasoning('r');
	bridge.toolStart('todo_list', '{}');
	bridge.toolEnd('todo_list', true, false, 'ok', 5);
	bridge.settleAssistant();
	bridge.rollbackUsers(1);
	bridge.clear();
	return { seen, bridge };
}

// 1. A complete turn: EVERY row event is stamped, none forgotten.
{
	const { seen } = drive('s_run_1');
	const kinds = [...new Set(seen.map(e => e.type))].sort();
	check('drive produced all row event kinds: ' + kinds.join(','),
		kinds.join(',') === 'row_delta,row_upsert,rows_set');
	const unlabeled = seen.filter(e => !e.sessionId);
	check('no row event came through unlabeled (' + unlabeled.length + ')',
		unlabeled.length === 0);
	check('every event carries the fixed run label',
		seen.every(e => e.sessionId === 's_run_1'));
}

// 2. Specific variants each stamp (not just upsert).
{
	const { seen } = drive('s_x');
	const delta = seen.filter(e => e.type === 'row_delta');
	const rowsSet = seen.filter(e => e.type === 'rows_set');
	check('row_delta events stamped (' + delta.length + ')',
		delta.length >= 2 && delta.every(e => e.sessionId === 's_x'));
	check('rows_set from replaceAll/rollback/clear stamped (' + rowsSet.length + ')',
		rowsSet.length === 3 && rowsSet.every(e => e.sessionId === 's_x'));
}

// 3. The provider is evaluated PER EMIT — the main bridge follows the active session.
{
	const seen = [];
	let current = 's_active_a';
	const bridge = new RowBridge(e => seen.push(e), async () => {}, () => current);
	bridge.replaceAll([], false);
	bridge.user('one');
	current = 's_active_b';
	bridge.user('two');
	const labels = seen.map(e => e.sessionId);
	check('label follows the provider across a session switch: ' + labels.join(','),
		labels.length === 2 && labels[0] === 's_active_a' && labels[1] === 's_active_b');
}

// 4. Source-level: both RowBridge constructions in index.ts pass a session provider, and the
//    main one is the ACTIVE session (a constant label would compile but lie mid-switch).
{
	const src = fs.readFileSync(path.join(root, 'src', 'main', 'index.ts'), 'utf8');
	const sites = src.split('new RowBridge(').slice(1);
	check('index.ts has exactly 2 RowBridge constructions (found ' + sites.length + ')',
		sites.length === 2);
	// Pin the LABEL PROVIDER itself, next to its persist target — checking either one alone
	// is how a frozen constant label slipped through the first version of this test.
	// Comments stripped first: the per-emit providers carry multi-line说明, and matching
	// across them by window size alone is flaky (card-repair lesson: don't read prose as code).
	const code = src.replace(/\/\/[^\n]*/g, '');
	check('main bridge: persists to active AND labels with sessions.active() (evaluated per emit)',
		/rows\s*=>\s*sessions\.saveRows\(sessions\.active\(\), rows\),\s*\(\)\s*=>\s*sessions\.active\(\)/.test(code));
	// Trailing punctuation is NOT the property: adding a 4th RowBridge arg turned `() => sid`
	// into `() => sid,` and failed a correct implementation. Pin the provider, allow `,` or `)`.
	check('run bridge: persists to sid AND labels with sid',
		/rows\s*=>\s*sessions\.saveRows\(sid,\s*rows\)\s*,\s*\(\)\s*=>\s*sid\s*[,)]/.test(code));
}

console.log(failed === 0
	? `row-session-label: ALL PASS (${out.length})`
	: `row-session-label: ${failed} FAIL of ${out.length}`);
process.exit(failed === 0 ? 0 : 1);
