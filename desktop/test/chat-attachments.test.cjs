/* Attachments on the message path (the float pane's tail).
 *
 * What this guards:
 *
 *   - The sanitising is TOTAL, not trusting: a non-array is "none", non-string entries and
 *     blanks are dropped, duplicates collapse, and the cap holds. Every one of those is a way
 *     the renderer could hand us something the transcript cannot render.
 *   - The reference block NAMES the files and says the content was not uploaded. The model must
 *     not be left to assume it received bytes it never got, and the text is what the model reads.
 *   - The user ROW carries the paths (so the transcript can draw chips) and OMITS the key when
 *     there are none — an always-present `[]` would grow every persisted user row for nothing
 *     and would also be a second way to spell "no attachments".
 *   - index.ts / preload / shared wiring is asserted at source level (comments stripped first).
 *
 * Run: npm run test:chatattach
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-chatattach.txt');
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
const { normalizeAttachments, withAttachmentRefs, MAX_ATTACHMENTS } =
	require(path.join(ai, 'chat-attachments.js'));
const { RowBridge } = require(path.join(ai, 'rows.js'));
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

// ---------- normalizeAttachments: total, never trusts the renderer ----------
{
	check('normalize: non-array is none', normalizeAttachments(undefined).length === 0
		&& normalizeAttachments(null).length === 0 && normalizeAttachments('a.png').length === 0
		&& normalizeAttachments({ 0: 'a.png' }).length === 0);
	check('normalize: drops non-strings and blanks, trims the rest',
		JSON.stringify(normalizeAttachments(['  a.png ', 42, '', '   ', null, 'b.png']))
			=== JSON.stringify(['a.png', 'b.png']));
	check('normalize: collapses duplicates, first wins, order kept',
		JSON.stringify(normalizeAttachments(['b.png', 'a.png', 'b.png'])) === JSON.stringify(['b.png', 'a.png']));
	const many = Array.from({ length: 20 }, (_, i) => `f${i}.png`);
	check('normalize: caps at MAX_ATTACHMENTS', normalizeAttachments(many).length === MAX_ATTACHMENTS
		&& MAX_ATTACHMENTS === 6);
}

// ---------- withAttachmentRefs: names + paths, never content ----------
{
	check('refs: no attachments leaves the text untouched (identity)',
		withAttachmentRefs('你好', []) === '你好');
	const one = withAttachmentRefs('看这个', ['C:\\shots\\shot-1.png']);
	check('refs: names the file and its path, and says content was not uploaded',
		one.startsWith('看这个') && one.includes('shot-1.png') && one.includes('C:\\shots\\shot-1.png')
		&& /未上传/.test(one), one);
	check('refs: never inlines file bytes (path only)', !one.includes('data:') && !/base64/.test(one));
	const empty = withAttachmentRefs('', ['D:\\a\\b.png']);
	check('refs: empty text yields the block alone, with no leading blank line',
		empty.startsWith('【附件') && empty.includes('b.png'), empty);
}

// ---------- the user row carries the paths, and only when there are some ----------
{
	const events = [];
	const bridge = new RowBridge(e => events.push(e), async () => { }, () => 's_att');
	const src = ['p1.png', 'p2.png'];
	const row = bridge.user('hi', src);
	check('row: attachments are stored on the user row',
		JSON.stringify(row.attachments) === JSON.stringify(['p1.png', 'p2.png'])
		&& row.kind === 'user' && row.text === 'hi');
	check('row: the emitted row_upsert carries them',
		events.some(e => e.type === 'row_upsert' && JSON.stringify(e.row.attachments) === JSON.stringify(['p1.png', 'p2.png'])));
	src.push('p3.png');
	check('row: the list is copied, not aliased', JSON.stringify(row.attachments) === JSON.stringify(['p1.png', 'p2.png']));

	const plain = bridge.user('no files');
	check('row: no attachments => the key is ABSENT, not an empty array',
		!Object.prototype.hasOwnProperty.call(plain, 'attachments'));
	const emptyList = bridge.user('empty list', []);
	check('row: an empty list is also absent (one way to say none, not two)',
		!Object.prototype.hasOwnProperty.call(emptyList, 'attachments'));
}

// ---------- source-level wiring (comments stripped) ----------
{
	const idx = strip(fs.readFileSync(path.join(ROOT, 'src/main/index.ts'), 'utf8'));
	check('index.ts: chatSend sanitises and folds attachments into the model text',
		/normalizeAttachments\(attachments\)/.test(idx) && /withAttachmentRefs\(raw, files\)/.test(idx));
	check('index.ts: the user row is given the raw text and the paths',
		/rowBridge\.user\(raw, files\)/.test(idx));
	check('index.ts: the model receives the referenced text, queued or live',
		/getAgent\(\)\.send\(said\)/.test(idx) && /queueThought\(said\)/.test(idx));

	const pre = strip(fs.readFileSync(path.join(ROOT, 'src/preload/index.ts'), 'utf8'));
	check('preload: send forwards the attachments channel arg',
		/send:\s*\(text:\s*string,\s*attachments\?:\s*string\[\]\)\s*=>\s*ipcRenderer\.invoke\(IPC_CHAT\.chatSend,\s*text,\s*attachments\)/.test(pre));

	const chat = strip(fs.readFileSync(path.join(ROOT, 'shared/chat.ts'), 'utf8'));
	check('shared/chat.ts: ConvRow gains an optional attachments list',
		/attachments\?:\s*string\[\]/.test(chat));
	check('shared/chat.ts: ChatApi.send accepts the optional attachments',
		/send\(text:\s*string,\s*attachments\?:\s*string\[\]\)/.test(chat));
}

out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
