/* Contract-drift tests — the seams nobody reads by eye.
 *
 * WHY THIS FILE EXISTS. This repo maintains its contract by hand, in two places, on purpose
 * (AGENTS.md §0: "两端没有物理共享代码，契约靠手工双份保持一致"). A hand-maintained contract does
 * not make drift a risk, it makes drift a schedule. And the drift that actually hurts lives
 * exactly where `tsc` cannot see it, because every one of these failures is "the symbol exists,
 * so the compiler is happy":
 *
 *   - preload exposes `IPC.foo` while main registers no handler. `IPC.foo` EXISTS — typecheck is
 *     green, the renderer calls it, and Electron throws "No handler registered" at RUNTIME.
 *   - main registers a handler preload never exposes: a feature that is complete, tested, and
 *     unreachable by anyone.
 *   - two tools share a name. `ToolRegistry.register` is `defs.set(name, def)`, which does not
 *     warn on a collision, it OVERWRITES — the first tool simply stops existing.
 *   - a shape gets declared twice. That has happened four times already (AutomationKind,
 *     WorkflowInfo.problems, TaskLogEntry, MemoryScope/MemoryEventKind/MemoryEvent) and the
 *     window API was declared in both preload/index.ts and index.d.ts, which produced a type
 *     error the moment anyone looked at it.
 *
 * None of these are logic bugs. They are STRUCTURAL, which means they are set differences — and a
 * set difference takes under a second to compute and otherwise has to be re-derived by eye every
 * single time someone touches shared/ipc.ts.
 *
 * THE WHITESLIST IS THE POINT. Section "dead" does not assert a count, it asserts an exact set of
 * channel names, each with a written reason. A count-based assertion goes green the moment someone
 * wires one channel and unwires another, which is precisely the drift this file exists to catch.
 *
 * Also note the parsing trap baked into this codebase: shared/ipc.ts and shared/chat.ts EACH
 * declare their own `export const IPC`, and index.ts aliases chat's as IPC_CHAT. So the object
 * prefix in a reference says which FILE the key came from, not what it was declared as. Every
 * check below therefore normalizes on the KEY NAME, never on the prefix. Getting that wrong made
 * the first draft of this script report all 25 chat channels as undeclared.
 *
 * Run: npm run test:drift
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-drift.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ROOT = path.join(__dirname, '..');
const read = f => fs.readFileSync(f, 'utf8');
const SKIP = new Set(['node_modules', '.test', 'out', 'native', '.mimosa', 'dist']);
const walk = (dir, acc = []) => {
	if (!fs.existsSync(dir)) { return acc; }
	for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
		if (SKIP.has(e.name)) { continue; }
		const p = path.join(dir, e.name);
		if (e.isDirectory()) { walk(p, acc); }
		else if (/\.tsx?$/.test(e.name)) { acc.push(p); }
	}
	return acc;
};
const rel = f => path.relative(ROOT, f).split(path.sep).join('/');

// ---------------------------------------------------------------------------------------------
// 1. Declarations. Both files declare `IPC`; only the KEY NAME is trustworthy across them.
// ---------------------------------------------------------------------------------------------
// Every file under shared/ — not just ipc.ts and chat.ts. There is also a FLOAT object in
// shared/float.ts, and parsing only the two obvious files reported all 16 float channels as
// undeclared. (Both ipc.ts and chat.ts export a const named IPC, so KEY NAME is the only thing
// that identifies a channel across them; the object prefix in a reference is the FILE's alias.)
const SRC_FILES = fs.readdirSync(path.join(ROOT, 'shared'))
	.filter(f => /\.ts$/.test(f))
	.map(f => path.join(ROOT, 'shared', f));
const CHANNEL = /^[a-z][\w-]*(:[\w-]+)+$/i;      // 'data:list', 'chat:delta' — not 'project'

const decl = new Map();          // keyName -> { str, where }
const strOwner = new Map();      // channel string -> keyName (two keys, one string = cross-talk)
const dupStr = [];
const sameKeyDiffStr = [];
const declaredShapes = new Set();

for (const file of SRC_FILES) {
	const text = read(file);
	const where = path.basename(file);
	for (const block of text.matchAll(/export const (\w+)\s*(?::[^=]+)?=\s*\{([\s\S]*?)\n\}/g)) {
		for (const d of block[2].matchAll(/^\s*([A-Za-z0-9_]+)\s*:\s*'([^']+)'/gm)) {
			const key = d[1], str = d[2];
			if (!CHANNEL.test(str)) { continue; }       // a non-channel string map, not ours
			const prev = decl.get(key);
			if (prev && prev.str !== str) { sameKeyDiffStr.push(`${key}: ${prev.str} vs ${str}`); }
			decl.set(key, { str, where });
			const other = strOwner.get(str);
			if (other && other !== key) { dupStr.push(`${other} & ${key} both -> ${str}`); }
			else { strOwner.set(str, key); }
			declaredShapes.add(str);
		}
	}
}

// ---------------------------------------------------------------------------------------------
// 2. References.
// ---------------------------------------------------------------------------------------------
const mainFiles = walk(path.join(ROOT, 'src/main'));
const preFiles = walk(path.join(ROOT, 'src/preload'));

const handled = new Map();       // keyName -> file:line
const pushed = new Map();        // keyName -> file:line   (main -> renderer)
const rawListens = new Map();    // literal channel -> file:line, string-literal registration
// Two ways to push: the `broadcast()` helper in index.ts, and a direct `webContents.send`.
// Matching a bare `.send(` instead is a false positive — `sub.send(req.prompt || '…')` is an AI
// session message, not an IPC push, and it made this test report a phantom channel named `prompt`.
const PUSHED = /(?:\bbroadcast|webContents\.send)\(\s*\w+\.([A-Za-z0-9_]+)/;
for (const f of mainFiles) {
	const lines = read(f).split(/\r?\n/);
	lines.forEach((l, i) => {
		let m = /\bipcMain\.handle\(\s*\w+\.([A-Za-z0-9_]+)/.exec(l);
		if (m) { handled.set(m[1], `${rel(f)}:${i + 1}`); return; }
		m = PUSHED.exec(l);
		if (m) { pushed.set(m[1], `${rel(f)}:${i + 1}`); }
		// Raw-string channels: the float drag overlay is inline HTML living INSIDE a main-process
		// template literal, so it never goes through shared/. A typo there does not fail typecheck —
		// `ipcMain.once` simply never fires, the overlay never closes, and (per capture.ts) the user
		// is left with a transparent window over their screen that only task manager can remove.
		m = /\bipcMain\.(?:once|handle|on)\(\s*'([^']+)'\s*,/.exec(l);
		if (m) { rawListens.set(m[1], `${rel(f)}:${i + 1}`); }
	});
}

const invoked = new Map();       // keyName -> file:line
const subscribed = new Map();    // keyName -> file:line

// The other half of the raw-string seam: who SENDS on a literal channel. Scanned in main because
// the overlay page is a template literal in host.ts, not a file under preload/.
const rawSends = new Map();
for (const f of mainFiles) {
	const lines = read(f).split(/\r?\n/);
	lines.forEach((l, i) => {
		const m = /\bipcRenderer\.(?:send|invoke|on)\(\s*'([^']+)'/.exec(l);
		if (m) { rawSends.set(m[1], `${rel(f)}:${i + 1}`); }
	});
}
for (const f of preFiles) {
	const lines = read(f).split(/\r?\n/);
	lines.forEach((l, i) => {
		let m = /\bipcRenderer\.(?:invoke|send)\(\s*\w+\.([A-Za-z0-9_]+)/.exec(l);
		if (m) { invoked.set(m[1], `${rel(f)}:${i + 1}`); return; }
		// preload funnels every subscription through one helper, so `.on(` never names the
		// channel itself — match the helper call shape instead.
		m = /(?:^|[^\w.])(?:sub|subscribe)\(\s*\w+\.([A-Za-z0-9_]+)/.exec(l);
		if (m) { subscribed.set(m[1], `${rel(f)}:${i + 1}`); }
	});
}

// ---------------------------------------------------------------------------------------------
// 3. The two seams `tsc` cannot see.
// ---------------------------------------------------------------------------------------------
// Sanity floors first: if a regex above rots, these fail loudly instead of the diff quietly
// going empty and the whole file reporting a false green.
check('parse: shared declares a plausible number of channels (>=100)', decl.size >= 100);
check('parse: main registers a plausible number of handlers (>=100)', handled.size >= 100);
check('parse: preload invokes a plausible number of channels (>=100)', invoked.size >= 100);

const noHandler = [...invoked.keys()].filter(k => !handled.has(k)).sort();
check(`B. every channel preload invokes has a main handler (missing: ${noHandler.length})`,
	noHandler.length === 0);
for (const k of noHandler) { out.push(`      !! ${k}  [${decl.get(k)?.str ?? 'NOT DECLARED'}]  <- ${invoked.get(k)}`); }

const unreachable = [...handled.keys()].filter(k => !invoked.has(k)).sort();
check(`C. every main handler is reachable from preload (orphan: ${unreachable.length})`,
	unreachable.length === 0);
for (const k of unreachable) { out.push(`      !! ${k}  <- ${handled.get(k)}`); }

const unsub = [...subscribed.keys()].filter(k => !pushed.has(k)).sort();
check(`D. every event preload subscribes to is pushed by main (missing: ${unsub.length})`,
	unsub.length === 0);
for (const k of unsub) { out.push(`      !! ${k}  [${decl.get(k)?.str ?? 'NOT DECLARED'}]`); }

// Raw-string channels are their own seam. A sender with no listener means the reply never comes
// back AT ALL — and for the drag overlay that is a transparent window stuck over the user's
// screen with no exit but task manager (the failure capture.ts warns about explicitly).
const rawNoListen = [...rawSends.keys()].filter(k => !rawListens.has(k)).sort();
check(`D2. every raw-string channel has a listener (dead-forever senders: ${rawNoListen.length})`,
	rawNoListen.length === 0);
for (const k of rawNoListen) { out.push(`      !! '${k}' sent at ${rawSends.get(k)} — nothing listens`); }

const rawNoSend = [...rawListens.keys()].filter(k => !rawSends.has(k)).sort();
check(`D3. every raw-string listener has a sender (orphan listeners: ${rawNoSend.length})`,
	rawNoSend.length === 0);
for (const k of rawNoSend) { out.push(`      !! '${k}' listened at ${rawListens.get(k)} — nothing sends it`); }

check('E. no two channel keys share one channel string (silent cross-talk)', dupStr.length === 0);
for (const d of dupStr) { out.push(`      !! ${d}`); }

check('F. no key name is declared twice with different strings', sameKeyDiffStr.length === 0);
for (const d of sameKeyDiffStr) { out.push(`      !! ${d}`); }

const orphanRefs = [...new Set([...handled.keys(), ...invoked.keys(), ...subscribed.keys(), ...pushed.keys()])]
	.filter(k => !decl.has(k)).sort();
check(`G. every IPC reference resolves to a declared key (orphans: ${orphanRefs.length})`,
	orphanRefs.length === 0);
for (const k of orphanRefs) { out.push(`      !! ${k}`); }

// ---------------------------------------------------------------------------------------------
// 4. Dead declarations — an EXACT SET, never a count.
// ---------------------------------------------------------------------------------------------
// A channel is dead when no file in main or preload mentions `.<key>` at all: those are the only
// two places that CAN touch it (renderer reaches channels only through preload method names).
const mainAndPre = [...mainFiles, ...preFiles].map(read).join('\n');
const dead = [...decl.keys()].filter(k => !new RegExp(`\\.${k}\\b`).test(mainAndPre)).sort();

/* eslint-disable no-irregular-whitespace */
const DEAD_WHITELIST = {
	// Filled in from the first run — each entry needs a reason, because the reason is the only
	// thing telling the next reader whether it is intentional or an unfinished feature.
};
/* eslint-enable no-irregular-whitespace */

const unexpectedDead = dead.filter(k => !(k in DEAD_WHITELIST));
const staleEntry = Object.keys(DEAD_WHITELIST).filter(k => !dead.includes(k));
check(`H. every declared-but-unwired channel is on the whitelist (${dead.length} dead)`,
	unexpectedDead.length === 0);
for (const k of unexpectedDead) { out.push(`      !! NOT WHITELISTED: ${k} = ${decl.get(k).str}  [${decl.get(k).where}]`); }
check('I. no stale whitelist entry (a channel got wired — move it out)', staleEntry.length === 0);
for (const k of staleEntry) { out.push(`      ?? whitelisted but now referenced: ${k}`); }

// ---------------------------------------------------------------------------------------------
// 5. Tool registry. Duplicates are silent: `defs.set(name, def)` overwrites without warning.
// ---------------------------------------------------------------------------------------------
	// This list is itself a rot risk: a new tool module that is not listed here is invisible to
	// every check below (J through M and N5), so the tools in it are never inspected at all. The
	// parse guard's floor is deliberately HIGHER than the current count so an unlisted module
	// shows up as a suspiciously low number rather than as silence.
	const TOOL_MODULES = ['tools.ts', 'agent-tools.ts', 'cu-tools.ts', 'file-tools.ts',
		'shell-tools.ts', 'todo-tools.ts', 'web-tools.ts',
		'ics.ts', 'settings-tools.ts', 'expert-tools.ts', 'library-tools.ts',
		'workflow-tools.ts', 'handoff-tools.ts', 'skill-tools.ts', 'automation-tools.ts', 'team-tools.ts', 'checkpoint-tools.ts', 'listquery-tools.ts',
		'plan-tools.ts', 'life-tools.ts', 'file-office-tools.ts', 'file-edit-tools.ts'];
const RISKS = new Set(['auto', 'confirm', 'danger']);
const tools = [];

for (const m of TOOL_MODULES) {
	const f = path.join(ROOT, 'src/main/ai', m);
	if (!fs.existsSync(f)) {
		check(`tools: module ${m} exists`, false);
		continue;
	}
	const text = read(f);
	for (const d of text.matchAll(/\bdef\(\s*'([a-z0-9_]+)'\s*,\s*'([a-z]+)'/g)) {
		// The description sits right after the risk on the same call — grab a window of it, since
		// the danger check below reads what the USER is told, not what the code does.
		const window = text.slice(d.index, d.index + 400);
		tools.push({ name: d[1], risk: d[2], where: `${m}`, desc: window });
	}
}
// Rot guard: if the `def(` pattern stops matching, the section below would pass vacuously.
check(`parse: a plausible number of tools were found (>=50, got ${tools.length})`, tools.length >= 50);

const byName = new Map();
const dupTools = [];
for (const t of tools) {
	if (byName.has(t.name)) { dupTools.push(`${t.name}: ${byName.get(t.name)} & ${t.where}`); }
	else { byName.set(t.name, t.where); }
}
check(`J. no two tools share a name (collision would silently overwrite)`, dupTools.length === 0);
for (const d of dupTools) { out.push(`      !! ${d}`); }

const badRisk = tools.filter(t => !RISKS.has(t.risk));
check(`K. every tool carries a valid risk level (${['auto', 'confirm', 'danger'].join('|')})`,
	badRisk.length === 0);
for (const t of badRisk) { out.push(`      !! ${t.name} risk='${t.risk}' in ${t.where}`); }

const badName = tools.filter(t => !/^[a-z][a-z0-9_]*$/.test(t.name));
check('L. every tool name is snake_case lowercase', badName.length === 0);
for (const t of badName) { out.push(`      !! '${t.name}' in ${t.where}`); }

// A `danger` tool whose description never mentions confirmation is a risk level that exists only
// for the code: the person approving the prompt is told nothing about why it is dangerous.
const quietDanger = tools.filter(t => t.risk === 'danger' && !/确认/.test(t.desc));
check(`M. every danger tool tells the user confirmation is required (${quietDanger.length} quiet)`,
	quietDanger.length === 0);
for (const t of quietDanger) { out.push(`      !! ${t.name} in ${t.where}`); }

// ---------------------------------------------------------------------------------------------
// 6. One shape, one declaration. The rule this codebase broke four times before writing it down.
// ---------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------
// N. Every main-process IPC handler is CLASSIFIED: has a tool, a known gap, or renderer-only.
// ---------------------------------------------------------------------------------------------

/* Handler key -> the tool that exposes the SAME capability to the model.
 *
 * This is a hand-written table and not an inference, deliberately. Name similarity is a trap here:
 * `dataList` has no `data_list` tool (the model goes through typed per-collection tools), and
 * `mcpAdd` maps to `mcp_connect`, whose meaning differs from phone's `mcp_connect`. A guessed
 * mapping would go quietly wrong the day somebody renamed a channel, and the point of this table is
 * that every line is a claim somebody actually made.
 */
const HANDLER_WITH_TOOL = {
	agentList: 'agent_list',
	agentRun: 'task',
	mcpList: 'mcp_list',
	mcpAdd: 'mcp_connect',
	mcpRemove: 'mcp_disconnect',
	// mcpSetEnabled is an on/off switch; the two connection tools cover its two directions.
	mcpSetEnabled: 'mcp_connect / mcp_disconnect',
	memoryForget: 'memory_forget',
	memoryHistory: 'memory_history',
	skillsList: 'skill_list',
	// skill_list 只给名字和简介；skillsRead / skillsWrite 补上另一半。
	skillsRead: 'skill_read',
	skillsWrite: 'skill_write',
	todoAdd: 'todo_create',
	todoList: 'todo_query',
	todoDone: 'todo_complete',
	usageSummary: 'usage_report',
	// configSet is written by model_switch (key mainModel) and by capability_toggle (key plugins).
	// Naming both keeps the claim honest about what a model switch can actually change.
	configSet: 'model_switch / capability_toggle',
	// automation 六件套。automationTaskLog 与 automationLog 共用 automation_log：
	// 那个工具把结构化 tasklog 和 ask 路径的 markdown 尾合在一起返回，所以不是重复。
	automationCreate: 'automation_create',
	automationUpdate: 'automation_update',
	automationList: 'automation_list',
	automationRemove: 'automation_delete',
	automationSetEnabled: 'automation_set_enabled',
	automationLog: 'automation_log',
	automationTaskLog: 'automation_log',
	// M17 sub-agent management surface — task 派出去之后模型原本既查不到 run 也动不了定义。
	agentRuns: 'agent_runs',
	agentUpsert: 'agent_upsert',
	agentDelete: 'agent_delete',
	agentMessage: 'agent_message',
	// workflow 五件套与 handoff 四件套（B6 / B7）。
	// 它们早就实现了，却一直在 GAP 表里躺着 —— 表格没跟着搬，N7 就是为这个腐化加的。
	workflowList: 'workflow_list',
	workflowRun: 'workflow_run',
	workflowRuns: 'workflow_runs',
	workflowReplay: 'workflow_replay',
	workflowValidate: 'workflow_validate',
	handoffList: 'handoff_list',
	handoffPreview: 'handoff_preview',
	handoffRead: 'handoff_read',
	handoffWrite: 'handoff_write',
	// team_assemble 与 IPC 的 teamAssemble 共用 assembleTeam + assembledWorkflows。
	teamAssemble: 'team_assemble',
	// checkpoint 三件套（域 system，与 undo_last_write 同域）。
	// checkpointList 原在 RENDERER_ONLY，现在有工具了必须搬过来 —— N7 会拦着不动。
	checkpointPush: 'checkpoint_push',
	checkpointRewind: 'checkpoint_rewind',
	checkpointList: 'checkpoint_list',
	listQuery: 'list_query',
};

/* Known gaps: the UI has it, the model does not. This list is ALLOWED to grow — growing it is a
 * decision somebody made, and editing this file is the record of that decision. What it must not do
 * is grow SILENTLY, so every entry carries the reason it is still open. */
const HANDLER_GAP = {
};

/* Deliberately renderer-only, each with WHY that is the right answer rather than an oversight. */
const HANDLER_RENDERER_ONLY = {
	// The generic substrate: the renderer drives EVERY collection through these three, while the
	// model goes through typed tools. One handler, many tools — nothing to map one-to-one, which is
	// exactly why this is a table and not an inference.
	dataList: '通用数据面：模型按集合走类型化工具（schedule_query / note_create / …），不经过它',
	dataUpsert: '通用数据面：同上',
	dataSoftDelete: '通用数据面：同上',
	dataExport: '全量导出由用户操作',
	dataImport: '导入是全量覆盖，模型不该有资格触发',
	dataStats: '统计面板',
	// Card action buttons: a fan-out handler — one channel serving EVERY spec's declared
	// actions (schedule/create, checklist/create, …). The model never "clicks" a card; when
	// it wants the same effect it calls the same tool through the same registry gate
	// (schedule_create / todo_create …), so there is no capability here it lacks elsewhere.
	cardAction: '卡片按钮由用户点击；模型要同样效果走同一个注册表工具（schedule_create / todo_create…）',
	// Card ENTITIES (stage 2): the store's read / pin / remove surface for the sidebar. The
	// model already has every capability a stored card names — its data (schedule_query /
	// note_query …) and its tools (schedule_create / todo_create …). These three move the
	// USER's view of cards, not the model's ability to make them.
	cardList: '卡片实体的读取是侧栏的事；模型看数据走 collection 查询工具',
	cardPin: '固定卡片是界面操作（模型没有侧栏）',
	cardRemove: '删除视图卡是界面操作；提案卡的回滚/清空会连带清卡（reconcile）',
	// Sessions. A model that can switch sessions can take over a different conversation.
	sessionCreate: '会话由用户管理：模型能切会话等于能接管另一场对话',
	sessionList: '同上',
	sessionSwitch: '同上',
	sessionRename: '同上',
	sessionRemove: '同上',
	sessionPin: '同上',
	sessionSetProject: '同上',
	// Peer voting is an interactive, user-facing decision surface.
	peerStart: '多方投票由用户发起与操作',
	peerList: '同上',
	peerView: '同上',
	peerPropose: '同上',
	peerVote: '同上',
	peerSettle: '同上',
	peerRemove: '同上',
	peerTemplates: '同上',
	// Sub-agent runtime control.
	agentAbort: '中止运行中的子代理是用户操作；模型没有中止自己子任务的需求',
	agentRunSub: '嵌套派发是内核内部路径，不作为工具暴露',
	agentInbox: '收件箱是用户查看子代理消息的面',
	agentMarkRead: '同上',
	agentSeedTeams: '种子团队由设置页导入',
	agentUsage: '用量面板（模型侧是 usage_report）',
	// Syncthing. Nothing here has a phone analogue, and nothing here belongs to the model.
	syncInstall: '同步内核安装与配置：模型碰同步没有正当理由',
	syncStatus: '同上',
	syncStart: '同上',
	syncStop: '同上',
	syncRestart: '同上',
	// 完整模式：把内核原版 GUI 开在应用窗口里。键（API key）由主进程注入，进不了渲染层。
	syncOpenGui: '原版 GUI 的入口；模型不需要开窗口，要读同步状态走 sync 系列 IPC 的只读面',
	syncScan: '同上',
	syncBrowse: '同上',
	syncSystemInfo: '同上',
	syncSystemLog: '同上',
	syncConfigGet: '同上',
	syncConfigPut: '同上',
	syncPickBinary: '同上',
	syncAddDevice: '同上',
	syncPutDevice: '同上',
	syncDeleteDevice: '同上',
	syncAddFolder: '同上',
	syncPutFolder: '同上',
	syncRemoveFolder: '同上',
	syncDataGet: '同上',
	syncDataSet: '同上',
	syncDataRun: '同上',
	syncPendingGet: '同上',
	syncDecline: '同上',
	syncDefaults: '同上',
	syncErrorsGet: '同上',
	syncErrorsClear: '同上',
	syncIgnoresGet: '同上',
	syncIgnoresPut: '同上',
	syncLogTail: '同上',
	// Focus capsule and system dialogs.
	capsuleShow: '浮窗胶囊：模型控制自己的窗口没有意义，且是个纯副作用面',
	capsuleHide: '同上',
	capsuleUpdate: '同上',
	capsuleAction: '同上',
	focusLog: '专注记录由用户操作',
	focusToday: '同上',
	// 2026-11-24 补登记：专注页/我的页「近 7 天」柱图的纯读聚合（focus.json 逐日分钟）。
	// 模型侧没有对应工具——它要看专注数据走 data_history/focus_query 那套类型化工具，
	// 这个 handler 只喂界面图表，不构成模型缺的能力。
	focusWeek: '近 7 天柱图聚合，纯界面读；模型走类型化查询工具',
	filePick: '系统文件选择对话框',
	fileOpenJson: '同上',
	fileSaveJson: '同上',
	openDataDir: '打开数据目录',
	appInfo: '版本信息',
	guardState: '权限档位由用户选择；模型不能自己放宽确认要求',
	memoryEvents: '记忆事件流水是只读审计视图',
	configGet: '读取配置供界面用；写入侧已由 model_switch / capability_toggle 覆盖',
	mcpTest: '连接测试是设置页动作，工具侧只需要连与断',
	// Todo lifecycle the phone likewise keeps on the UI side.
	todoRemove: '删除待办只在界面侧（手机端同样没有对应工具）',
	todoReopen: '重开待办只在界面侧（同上）',
	todoPolicyGet: '待办策略由设置页配置',
	todoPolicySet: '同上',
	// The chat surface itself. These move the USER's turn, not the model's.
	chatSend: '用户发消息',
	chatStop: '中止当前回合是用户操作',
	chatClear: '清空会话',
	chatEnqueue: '排队中的用户消息',
	chatEnqueueList: '同上',
	chatEnqueueClear: '同上',
	chatInjectMode: '注入模式是界面开关',
	chatSetInjectMode: '同上',
	chatGetRows: '会话行读取供界面渲染',
	chatRollbackUsers: '回滚用户消息由用户操作',
	approvalResolve: '审批回调：只能由真实的审批卡片触发',
	// Context budget. What stays in the transcript is the USER's call: a model that tunes its
	// own context is editing the record of what it was told.
	contextTogglesGet: '上下文取舍由用户决定：模型调自己的上下文等于篡改自己被告知的内容',
	contextTogglesSet: '同上',
	contextHistory: '同上',
	contextManifest: '同上',
	listTargets: '浮窗内可挂载的列表目标',
	// The focus capsule window (float/host.ts) is its own renderer; these are its plumbing.
	state: '专注浮窗的内部状态',
	enter: '同上',
	exit: '同上',
	modeSet: '同上',
	shutter: '同上',
	shutterWindow: '同上',
	movePane: '同上',
	saveLayout: '同上',
	setLinked: '同上',
	draftGet: '草稿读写只服务浮窗自己',
	draftSet: '同上',
	draftAttach: '同上',
	draftRemove: '同上',
};

{
	const buckets = [HANDLER_WITH_TOOL, HANDLER_GAP, HANDLER_RENDERER_ONLY];
	const seen = new Map();
	for (const b of buckets) {
		for (const k of Object.keys(b)) { seen.set(k, (seen.get(k) ?? 0) + 1); }
	}

	const multi = [...seen.entries()].filter(([, n]) => n > 1).map(([k]) => k);
	check(`N0. every handler is in exactly one bucket (in several: ${multi.length})`, multi.length === 0,
		multi.join(', '));

	const unclassified = [...handled.keys()].filter(h => !seen.has(h)).sort();
	check(`N1. every main handler is classified as tool / gap / renderer-only (unclassified: ${unclassified.length})`,
		unclassified.length === 0, unclassified.join(', '));

	// The reverse, per bucket: every entry must still name a live handler. A handler that was
	// removed leaves the buckets quietly lying about what exists.
	const deadEntries = [...seen.keys()].filter(h => !handled.has(h)).sort();
	check(`N3. no stale classification entry (handler gone: ${deadEntries.length})`, deadEntries.length === 0,
		deadEntries.join(', '));

	// And the mapping half: every tool named in HANDLER_WITH_TOOL must still be registered. A
	// deleted tool leaves a handler claiming a capability that no longer exists — drift one layer
	// above a handler with no tool.
	const toolExists = (name) =>
		name.split('/').some(t => tools.some(x => x.name === t.trim()));
	const staleTool = Object.entries(HANDLER_WITH_TOOL).filter(([, t]) => !toolExists(t));
	check(`N2. every mapped tool still exists (stale: ${staleTool.length})`, staleTool.length === 0,
		staleTool.map(([h, t]) => h + '->' + t).join(' '));

	// The count that matters, asserted as an exact SET rather than a count: a new gap has to be
	// added here on purpose, which is what makes this a decision log instead of a drift log.
	const gapKeys = Object.keys(HANDLER_GAP).sort();
	check(`N4. known handler/tool gaps, exactly these ${gapKeys.length}`,
		gapKeys.length === 0, gapKeys.join(', '));

	// A tool that tells the model to CALL ANOTHER TOOL must name one that exists. This is drift one
	// layer above a missing handler: the description promises a capability and the tool set cannot
	// deliver it. Only wording that hands the model an instruction is scanned, so incidental prose
	// ("a note", "the file") never trips it.
	// Non-tool nouns that follow an instruction like 请用 / 调用. Empty on purpose for the first
	// run, so every hit is a real finding; adding to this list is itself a decision.
	const swallow = new Set([]);
	const named = new Set(tools.map(t => t.name));
	const dangling = [];
	for (const m of TOOL_MODULES) {
		const text = read(path.join(ROOT, 'src/main/ai', m));
		for (const mm of text.matchAll(/(?:请用|调用|使用|先用|或用|调用一下)\s*[`'"?]?\s*([a-z][a-z0-9]*(?:_[a-z0-9]+)+)/g)) {
			if (swallow.has(mm[1])) { continue; }
			if (!named.has(mm[1])) { dangling.push(`${m}: ${mm[1]}`); }
		}
	}
	check(`N5. no tool points the model at a tool that does not exist (${dangling.length})`,
		dangling.length === 0, dangling.join(' '));

	// STRUCTURAL, not a count. A floor cannot catch "one module fell off the list": dropping
	// library-tools.ts takes the tool count from 58 to 54, still above any honest floor, and
	// every tool in it goes uninspected. Enumerating the directory is the only version that
	// catches it, which is why this is derived from the filesystem rather than declared.
	const toolBearing = walk(path.join(ROOT, 'src/main/ai'))
		.filter(f => /\bdef\(\s*'[a-z0-9_]+'\s*,/.test(read(f)))
		.map(f => path.basename(f));
	const unlistedModule = toolBearing.filter(f => !TOOL_MODULES.includes(f)).sort();
	check(`N6. every tool-bearing module under ai/ is inspected (unlisted: ${unlistedModule.length})`,
		unlistedModule.length === 0, unlistedModule.join(', '));

	// A key that is BOTH a gap and mapped-to-a-tool is a lying table: the feature shipped and the
	// table never moved. N1 and N3 cannot see it — the handler IS classified, just wrongly — so it
	// is asserted directly, with no name inference involved.
	const bothGapAndTool = Object.keys(HANDLER_GAP).filter(k => k in HANDLER_WITH_TOOL).sort();
	check(`N7. no key is both a gap and mapped to a tool (${bothGapAndTool.length})`,
		bothGapAndTool.length === 0, bothGapAndTool.join(', '));
}

const SHAPES = ['AutomationKind', 'TaskLogEntry', 'MemoryScope', 'MemoryEventKind',
	'MemoryEvent', 'WorkflowInfo', 'PlanabilityApi', 'CardEntity'];
const shapeSrc = [...walk(path.join(ROOT, 'shared')),
	...walk(path.join(ROOT, 'src')),
	...fs.readdirSync(ROOT).filter(f => /\.d\.ts$/.test(f)).map(f => path.join(ROOT, f))];

const defRe = name => new RegExp(`(?:^|\\n)\\s*(?:export\\s+)?(?:type|interface)\\s+${name}\\b`, 'g');
for (const name of SHAPES) {
	const hits = [];
	for (const f of shapeSrc) {
		const text = read(f);
		let m;
		const re = defRe(name);
		while ((m = re.exec(text))) {
			const line = text.slice(0, m.index).split(/\r?\n/).length;
			hits.push(`${rel(f)}:${line}`);
		}
	}
	check(`N. '${name}' is declared exactly once (found ${hits.length})`, hits.length === 1);
	for (const h of hits) { out.push(`      -> ${h}`); }
}

out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
flush();
process.exit(failed === 0 ? 0 : 1);
