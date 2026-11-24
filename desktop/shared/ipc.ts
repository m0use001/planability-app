/* IPC contract shared by main / preload / renderer.
 * Renderer never touches node APIs directly — everything goes through the whitelist
 * exposed by preload (window.planability). */

import type { CollectionName, DataStats, Dump, Entity, MemoryEvent } from './types';

	export const IPC = {
	dataList: 'data:list',
	dataUpsert: 'data:upsert',
	dataSoftDelete: 'data:softDelete',
	dataStats: 'data:stats',
	dataExport: 'data:export',
	dataImport: 'data:import',
	/**
	 * Forget a memory BY APPENDING a `forget` event, instead of editing the collection.
	 *
	 * This replaces `dataSoftDelete` for this collection, which is the trap behind the
	 * resurrection: it marks the row deleted while the log still says the memory is live, so the
	 * very next `projectMemories` writes it straight back (the row is absent from `store.live()`,
	 * `m.forgotten` is false, and the upsert re-creates it). Editing a materialised view without
	 * editing its source is not a delete, it is a race the projection always wins.
	 */
	memoryForget: 'memory:forget',
	/** Audit trail for one memory, oldest first — the "可检视" half of M19's requirement. */
	memoryHistory: 'memory:history',
	/** Recent events across every memory, with the total so the UI can page honestly. */
	memoryEvents: 'memory:events',
	/** M19-4 skills management. See SkillsApi for why read/write deal in raw file text. */
	skillsList: 'skills:list',
	skillsRead: 'skills:read',
	skillsWrite: 'skills:write',
	appInfo: 'app:info',
	configGet: 'config:get',
	configSet: 'config:set',
	usageSummary: 'usage:summary',
	automationList: 'automation:list',
	automationCreate: 'automation:create',
	automationSetEnabled: 'automation:setEnabled',
	/**
	 * Patch an automation. PAYLOAD IS A PARTIAL, with phone's automation_edit semantics:
	 * a blank string means "not mentioned" (so a prompt cannot be cleared by sending ""),
	 * while numbers and booleans apply on presence alone. Mirrored from ToolExecImpls.kt so
	 * the two ends do not drift on what a partial edit MEANS. */
	automationUpdate: 'automation:update',
	automationRemove: 'automation:remove',
	automationLog: 'automation:log',
	/**
	 * Structured run history for ONE automation (agent/workflow tasks), newest last.
	 *
	 * Separate from `automationLog`, which tails a markdown file and only ever covers the ask
	 * path. An agent task's record is `TaskLogEntry[]`: ok/summary/steps/error plus the `runId`
	 * that points at the journal, which is what the replay button needs to do anything at all.
	 */
	automationTaskLog: 'automation:taskLog',
	sessionList: 'session:list',
	sessionCreate: 'session:create',
	sessionSwitch: 'session:switch',
	sessionRemove: 'session:remove',
	sessionRename: 'session:rename',
	sessionPin: 'session:pin',
	/** Move a session into a project, or out of all of them with null / ''. */
	sessionSetProject: 'session:setProject',
	filePick: 'file:pick',
	mcpList: 'mcp:list',
	mcpAdd: 'mcp:add',
	mcpRemove: 'mcp:remove',
	mcpSetEnabled: 'mcp:setEnabled',
	mcpTest: 'mcp:test',
	focusLog: 'focus:log',
	focusToday: 'focus:today',
	fileSaveJson: 'file:saveJson',
	fileOpenJson: 'file:openJson',
	openDataDir: 'app:openDataDir',
	syncStatus: 'sync:status',
	syncStart: 'sync:start',
	syncStop: 'sync:stop',
	syncInstall: 'sync:install',
	syncAddDevice: 'sync:addDevice',
	syncAddFolder: 'sync:addFolder',
	syncRemoveFolder: 'sync:removeFolder',
	syncPickBinary: 'sync:pickBinary',
	syncLogTail: 'sync:logTail',
	syncConfigGet: 'sync:configGet',
	syncConfigPut: 'sync:configPut',
	syncDefaults: 'sync:defaults',
	syncBrowse: 'sync:browse',
	syncIgnoresGet: 'sync:ignoresGet',
	syncIgnoresPut: 'sync:ignoresPut',
	syncPendingGet: 'sync:pendingGet',
	syncDecline: 'sync:decline',
	syncScan: 'sync:scan',
	syncPutDevice: 'sync:putDevice',
	syncDeleteDevice: 'sync:deleteDevice',
	syncPutFolder: 'sync:putFolder',
	syncSystemInfo: 'sync:systemInfo',
	syncSystemLog: 'sync:systemLog',
	syncErrorsGet: 'sync:errorsGet',
	syncErrorsClear: 'sync:errorsClear',
	syncRestart: 'sync:restart',
	/**
	 * Open Syncthing's ORIGINAL web GUI in its own window ("完整模式").
	 *
	 * Why an IPC and not `shell.openExternal`: the GUI authenticates every REST call with an
	 * `X-API-Key` header, and the page it serves carries no key — a plain browser opens to a
	 * key prompt. The main process can inject that header into the window's own session, so
	 * this round-trips through main and returns the URL it opened; the renderer never sees
	 * the key, and the key never ends up in a URL, a history entry or a Referer.
	 */
	syncOpenGui: 'sync:openGui',
	syncDataGet: 'sync:dataGet',
	syncDataSet: 'sync:dataSet',
	syncDataRun: 'sync:dataRun',
	capsuleShow: 'capsule:show',
	capsuleUpdate: 'capsule:update',
	capsuleHide: 'capsule:hide',
	capsuleAction: 'capsule:action',
	/** main → main-window event: the focus capsule asked for something */
	focusAction: 'focus:action',
	/** main → capsule-window event: timer state push */
	capsuleState: 'capsule:state',
	// ---- M17 multi-agent kernel ----
	agentList: 'agent:list',
	agentUpsert: 'agent:upsert',
	agentDelete: 'agent:delete',
	agentSeedTeams: 'agent:seedTeams',
	/** Give one primary agent a turn (its own session). */
	agentRun: 'agent:run',
	agentAbort: 'agent:abort',
	/** Spawn a sub-agent run (mirrors the `task` tool; returns its child session + run id). */
	agentRunSub: 'agent:runSub',
	/** Runs currently running/queued, plus the concurrency limit. */
	agentRuns: 'agent:runs',
	/** SendMessage bus. */
	agentMessage: 'agent:post',
	agentInbox: 'agent:inbox',
	agentMarkRead: 'agent:markRead',
	/** Usage ledger. */
	agentUsage: 'agent:usage',
	/** Loop-guard / checkpoint diagnostics (M17 item 4). */
	guardState: 'agent:guardState',
	checkpointPush: 'agent:checkpointPush',
	checkpointList: 'agent:checkpointList',
	checkpointRewind: 'agent:checkpointRewind',
	/** main → main-window event: kernel status changed (run started/finished, bus message). */
	agentEvent: 'agent:event',
	// ---- M17 待办表 (P2) ----
	todoList: 'agent:todoList',
	todoAdd: 'agent:todoAdd',
	todoDone: 'agent:todoDone',
	todoReopen: 'agent:todoReopen',
	todoRemove: 'agent:todoRemove',
	/** Q2: check policy (always / everyN / maxShown). */
	todoPolicyGet: 'agent:todoPolicyGet',
	todoPolicySet: 'agent:todoPolicySet',
	// ---- M17 平级模式 (P3) ----
	peerTemplates: 'agent:peerTemplates',
	peerStart: 'agent:peerStart',
	peerPropose: 'agent:peerPropose',
	peerVote: 'agent:peerVote',
	peerSettle: 'agent:peerSettle',
	peerView: 'agent:peerView',
	peerList: 'agent:peerList',
	peerRemove: 'agent:peerRemove'
} as const;

/** One editable slot of a peer round (Q4 form 3: pre-filled, fully editable). */
export interface PeerSlotInfo {
	key: string;
	label: string;
	prompt: string;
	color: string;
	agentId: string;
}

export interface PeerGroupInfo {
	id: string;
	title: string;
	task: string;
	templateKey: string;
	status: 'open' | 'voting' | 'settled' | 'error';
	winnerId: string;
	summary: string;
	createdAt: number;
	updatedAt: number;
}

export interface PeerVoteOutcome {
	outcome: 'decided' | 'tie' | 'no-proposals' | 'all-abstain' | 'already-settled';
	winnerId: string;
	/** ready-to-render explanation; ties deliberately stay unresolved for the user */
	explain: string;
	tally: { agentId: string; text: string; votes: number }[];
}

/** M17 待办表 entry as it crosses IPC. */
export interface AgentTodoInfo {
	id: string;
	sessionId: string;
	text: string;
	source: 'user' | 'agent';
	status: 'open' | 'done';
	createdAt: number;
	updatedAt: number;
}

/** Q2 selection: every round, or every N rounds. */
export interface TodoCheckPolicy {
	mode: 'always' | 'everyN';
	everyN: number;
	maxShown: number;
}

/** M17 agent profile as it crosses IPC (mirrors main/ai/agents/profile.ts AgentProfile). */
export interface AgentProfileInfo {
	id: string;
	name: string;
	color: string;
	systemPrompt: string;
	/** empty = inherit all tools */
	tools: string[];
	/** empty = inherit all memory domains */
	memoryScope: string[];
	permMode: PermMode;
	maxTurns: number;
	builtin: boolean;
}

export type AgentRunStatus = 'queued' | 'running' | 'done' | 'error';

export interface AgentRunInfo {
	id: string;
	agentId: string;
	sessionId: string;
	parentId: string;
	depth: number;
	kind: 'primary' | 'sub';
	status: AgentRunStatus;
	startedAt: number;
	endedAt?: number;
	error?: string;
	result?: string;
}

export interface AgentUsageRow {
	agentId: string;
	tokensIn: number;
	tokensOut: number;
	cost: number;
	calls: number;
}

export interface AgentBusMessage {
	id: string;
	fromAgent: string;
	toAgent: string;
	sessionId: string;
	kind: 'note' | 'steer' | 'result' | 'terminal';
	body: string;
	readAt?: number;
	createdAt: number;
}

/** Focus floating capsule state (phone FloatBall 132×48dp capsule parity). */
export interface CapsuleState {
	/** seconds left */
	left: number;
	total: number;
	running: boolean;
	/** current round (1-based) */
	round: number;
}

// ---- Syncthing full management (in-app replacement for the web GUI) ----

export interface StDeviceConfig {
	deviceID: string;
	name?: string;
	addresses?: string[];
	compression?: string;
	introducer?: boolean;
	paused?: boolean;
	/** raw-config passthrough (the JSON editor round-trips these objects) */
	[key: string]: unknown;
}

export interface StFolderConfig {
	id: string;
	label?: string;
	path?: string;
	/** sendreceive / sendonly / receiveonly */
	type?: string;
	paused?: boolean;
	devices?: { deviceID: string }[];
	rescanIntervalS?: number;
	fsWatcherEnabled?: boolean;
	versioning?: { type: string; params?: Record<string, string> };
	[key: string]: unknown;
}

export interface StConfig {
	devices: StDeviceConfig[];
	folders: StFolderConfig[];
	options?: Record<string, unknown>;
	[key: string]: unknown;
}

/** /rest/cluster/pending — offers not yet accepted. */
export interface StPending {
	devices: Record<string, { deviceID?: string; name?: string; address?: string; time?: string }>;
	folders: Record<string, { offeredBy?: Record<string, { deviceID?: string; name?: string }> }>;
}

export interface StSystemInfo {
	myID: string;
	uptime: number;
	startTime?: string;
	version?: string;
}

export interface StLogLine { level?: string; message?: string; when?: string; }
export interface StError { when?: string; device?: string; message?: string; }

/** PC⇄phone data sync built on Syncthing (dump v1 in a shared folder). */
export interface DataSyncStatus {
	enabled: boolean;
	/** local sync folder path */
	dir: string;
	folderId: string;
	/** the Syncthing folder exists and is shared with at least one peer */
	shared: boolean;
	lastExportAt: number;
	lastImportAt: number;
	lastImportCount: number;
	error?: string;
}

// ---- Syncthing (phone SyncthingScreen parity) ----

export interface SyncFolder {
	id: string;
	label: string;
	path: string;
	state: string;
	progress: number; // 0..1
}

export interface SyncDevice {
	id: string;
	name: string;
	connected: boolean;
}

export interface SyncStatus {
	/** REST endpoint answered — kernel is usable (our own spawn or an external instance). */
	running: boolean;
	/** own = we spawned it (stoppable) / attached = external Syncthing / stopped. */
	mode: 'stopped' | 'own' | 'attached';
	myId: string;
	version: string;
	/** Resolved syncthing.exe (or '' when none found). */
	binaryPath: string;
	error?: string;
}

export interface SyncSummary extends SyncStatus {
	folders: SyncFolder[];
	devices: SyncDevice[];
}

export interface McpServerDef {
	id: string;
	name: string;
	url: string;
	enabled: boolean;
	createdAt: number;
}

export interface McpTestResult {
	ok: boolean;
	toolNames?: string[];
	error?: string;
}

export interface PickedFile {
	name: string;
	content: string;
	truncated: boolean;
}

export interface SessionMeta {
	id: string;
	title: string;
	createdAt: number;
	updatedAt: number;
	pinned: boolean;
	messageCount: number;
	/**
	 * The `projects` row this session is grouped under, or absent for "no project".
	 *
	 * Absent rather than `null` so an ungrouped session and a session from before grouping existed
	 * are the same shape — and so the persisted `chat_sessions.json` is unchanged for anyone who
	 * never uses this. The renderer groups on this id; a group whose id matches no project is a
	 * dangling reference, which `dataSoftDelete` on a project prevents by clearing the link.
	 */
	projectId?: string;
}

/**
 * What an automation does when it fires (M20-2).
 *
 * Declared here rather than in automation.ts so there is exactly ONE spelling of this union.
 * It used to be spelled inline in `src/main/ai/automation.ts` while shared had none at all,
 * which meant the automation page could not tell the three apart: the field existed at runtime,
 * was absent from the type, so a UI that switched on `kind` had no type to switch on and the
 * kind picker could not be built. `Automation` imports this from here.
 *
 * 'ask' (default) is the historical single-shot call, 'agent' dispatches a workflow through the
 * AgentManager. Both optional and defaulted on read, so every EXISTING automations.json keeps
 * working untouched — and an unknown value falls back to 'ask', because silently turning someone's
 * daily summary into an agent dispatch would be the worst possible surprise.
 */
export type AutomationKind = 'ask' | 'agent' | 'workflow';

export interface AutomationDef {
	id: number;
	name: string;
	prompt: string;
	intervalMinutes?: number;
	dailyHour?: number;
	dailyMinute?: number;
	enabled: boolean;
	lastRun: number;
	createdAt: number;
	/** M20-2: absent/unknown reads back as 'ask'. */
	kind?: AutomationKind;
	/** M20-2: which workflow to run, for agent tasks. */
	workflowId?: string;
	/** M20-2: the goal, overriding the workflow's own default. */
	taskText?: string;
	/** M20-2: hard ceiling for the whole run, in minutes. */
	timeoutMinutes?: number;
}

export interface AutomationInput {
	name: string;
	prompt: string;
	intervalMinutes?: number;
	dailyHour?: number;
	dailyMinute?: number;
	/** M20-2. Omitted by callers that predate it, which is what keeps them working. */
	kind?: AutomationKind;
	/** M20-2: required in practice for kind='agent' — checked in the engine, not the type. */
	workflowId?: string;
	/** M20-2: the goal. */
	taskText?: string;
	/** M20-2: hard ceiling for the whole run, in minutes. */
	timeoutMinutes?: number;
}

/**
 * One line of an agent-task's run history (M20-2's "so the run can be replayed from the UI").
 *
 * Declared here rather than in automation-task.ts for the same reason as `AutomationKind`: the
 * automation page consumes it, so a main-process-only spelling leaves the renderer with no type
 * and the history button with nothing to render. `automation-task.ts` re-exports this.
 *
 * `runId` is the important field — it is the handle on the durable journal, and without a channel
 * that returned it the page could show neither history nor a replay. That is why this is a
 * separate, STRUCTURED read: `automationLog` returns the tail of a markdown file, which has no
 * runId to link back to.
 */
export interface TaskLogEntry {
	/** When the run finished (or was refused), epoch ms. */
	at: number;
	ok: boolean;
	/** The journal id, so the run can be replayed from the UI. */
	runId?: string;
	summary: string;
	error?: string;
	/** Per-step outcome, so a partial failure is visible rather than summarised away. */
	steps?: { stepId: string; role: string; status: string }[];
}

export interface ToolUsage {
	tool: string;
	ok: number;
	fail: number;
	ms: number;
}

export interface UsageSummary {
	totalCalls: number;
	byTool: ToolUsage[];
	recent: { tool: string; ok: boolean; ms: number; at: number }[];
}

export interface AppInfo {
	dataDir: string;
	appVersion: string;
	platform: string;
}

export type PermMode = 'ro' | 'confirm' | 'auto' | 'full';

export interface DesktopConfig {
	/** Phase-2 preview only: model slot settings are stored, chat is not wired yet. */
	mainModel?: string;
	mainKey?: string;
	workerModel?: string;
	workerKey?: string;
	/** Permission gear (phone ChatPermMenu parity): ro=read-only, confirm=ask writes (default),
	 * auto=auto-run writes (danger still asks), full=auto-run writes + danger still asks. */
	permMode?: PermMode;
	/** Thinking display gear (phone 思考档 parity): adaptive=show live & collapse after, on=always show, off=hide. */
	thinkMode?: 'adaptive' | 'on' | 'off';
	/** Active sub-agent key ('' = general, no persona). */
	assistantKey?: string;
	/** Capability plugins: built-in features as toggles (phone PluginMarket v1 shape). */
	plugins?: Record<string, boolean>;
	/** Memory injection: prepend a recall hint to the system prompt. */
	memInject?: boolean;
	/** Syncthing kernel binary (auto-detected when empty). */
	syncthingPath?: string;
	/** Start the bundled kernel at app launch when one is installed (default true). */
	syncAutoStart?: boolean;
	/** Last position of the focus capsule window. */
	capsulePos?: { x: number; y: number };
	/**
	 * M18 file domain: the ONLY directory tree the agent's file tools may touch. Empty means
	 * the domain is disabled entirely — read-only tools refuse rather than defaulting to the
	 * user's home or CWD, because "no root configured" silently widening to somewhere broad is
	 * exactly how a read tool turns into a data-exfiltration tool.
	 */
	workspaceRoot?: string;
	/**
	 * M19-1 ContextBudget per-item switches. Stored here rather than in memory so a switch the
	 * user set and then lost on relaunch is not a thing that can happen. Keys are the six
	 * manifest items; absent = on, so adding a new item never silently disables itself.
	 */
	contextToggles?: Record<string, boolean>;
	/**
	 * M19-4: where user-written skills live. Defaults to <userData>/skills.
	 *
	 * Configurable rather than fixed because skills are the one AI feature people genuinely want
	 * to edit with their own editor and put under version control. A missing directory is not an
	 * error — the built-ins still load.
	 */
	skillsDir?: string;
}

/* ---------------------------------------------------------------------------
 * M19-4 Skills management.
 *
 * The unit of read/write is the FILE'S RAW TEXT, not a set of structured fields, and that is the
 * whole design rather than a convenience: this module exists because "a skill is something a
 * person writes, edits with their own editor, and drops into a folder" (ai/skills.ts). A
 * field-based write would re-serialise on every save, silently dropping frontmatter keys the UI
 * has never heard of and reformatting the parts it did. So the page is a text box over a file,
 * and the parsed view is offered ALONGSIDE the text so the user can be told what the loader will
 * make of it — including before they save.
 * ------------------------------------------------------------------------- */

/** One skill as the management page sees it. The body is NOT included; use skillsRead. */
export interface SkillInfo {
	/** Identity the loader uses. Accept either spelling — see skillsRead. */
	key: string;
	name: string;
	description: string;
	trigger: string;
	priority: number;
	enabled: boolean;
	keywords: string[];
	/** Body length, so the list can show size without shipping every body over IPC. */
	bodyChars: number;
	/** Which file actually won the override pass. */
	origin: 'builtin' | 'user';
	/** True when a user file shadows a built-in of the same key. */
	overridden: boolean;
	/** False for built-ins: they ship inside the app and a write would be undone by the next
	 *  update. The page must offer "create your own copy" instead of an edit box. */
	editable: boolean;
	/** Non-fatal parser complaints. The skill still loads; empty means clean. */
	problems: string[];
}

export interface SkillListResult {
	skills: SkillInfo[];
	/** Files that were skipped entirely, with the reason. The list still works without them. */
	problems: { file: string; reason: string }[];
	/** The directory backing this list, so the page can show it and offer to open it. */
	dir: string;
}

/** One skill file, verbatim, plus what the loader thinks of it. */
export interface SkillFile {
	key: string;
	/** The file exactly as it is on disk. '' when the file does not exist yet. */
	text: string;
	exists: boolean;
	origin: 'builtin' | 'user';
	editable: boolean;
	/** What skillsList would report for this text — shown before the user ever saves. */
	preview: Pick<SkillInfo, 'name' | 'description' | 'keywords' | 'bodyChars'>;
	/** Parser complaints about `text`. Empty means the loader will take it as written. */
	problems: string[];
}

export interface SkillWriteResult {
	ok: boolean;
	/** Why it was refused. A refusal is NEVER reported as a success. */
	error?: string;
	/** The sanitised key the file is now known by — may differ from the one requested. */
	key?: string;
	/** Absolute path written, for the page's "reveal" affordance. */
	file?: string;
	/** True when an existing user file was replaced; false when the file is new. This is how the
	 *  page tells "saved" from "created your own copy, the built-in is untouched" — writing a
	 *  built-in's key lands in the user directory as an override, by design. */
	replaced?: boolean;
	/** What the loader will make of the text just written. Non-blocking: a save that succeeds but
	 *  warns beats a save refused for a half-finished file the user is writing in their own
	 *  editor, which is the premise this whole module rests on. */
	problems?: string[];
}

export interface SkillsApi {
	/** Everything the loader can see, built-ins included. */
	list(): Promise<SkillListResult>;
	/**
	 * One file, verbatim. Accepts either the frontmatter name or the filename stem, because the
	 * built-in called 周计划 lives in `weekly-plan.md` and a user should not have to know which.
	 * A key with no file returns `exists: false` and empty text rather than an error, so the page
	 * can open an empty editor for a new skill.
	 */
	read(key: string): Promise<SkillFile>;
	/** Create or replace a USER skill. There is no delete, and built-ins are never written. */
	write(key: string, text: string): Promise<SkillWriteResult>;
}

export interface DataApi {
	list<T extends Entity>(collection: CollectionName): Promise<T[]>;
	upsert<T extends Entity>(collection: CollectionName, item: T): Promise<T>;
	softDelete(collection: CollectionName, id: string): Promise<boolean>;
	stats(): Promise<DataStats>;
	exportDump(): Promise<Dump>;
	importDump(dump: Dump): Promise<number>;
	appInfo(): Promise<AppInfo>;
	configGet(): Promise<DesktopConfig>;
	configSet(patch: DesktopConfig): Promise<void>;
	usageSummary(): Promise<UsageSummary>;
	automationList(): Promise<AutomationDef[]>;
	automationCreate(input: AutomationInput): Promise<AutomationDef>;
	automationSetEnabled(id: number, enabled: boolean): Promise<boolean>;
	/**
	 * Patch an automation. Resolves `{ ok: false }` when no such id — callers must not read that
	 * as success, which is exactly the silent-wrong-id failure phone's automation_edit guards.
	 */
	automationUpdate(id: number, patch: Partial<AutomationDef>): Promise<
		{ ok: true; item: AutomationDef } | { ok: false }>;
	automationRemove(id: number): Promise<boolean>;
	automationLog(id: number): Promise<string>;
	/**
	 * Structured history for one automation, newest last, bounded by `n` (default 50).
	 *
	 * Each entry carries the `runId` needed to replay it through `workflowReplay`.
	 */
	automationTaskLog(id: number, n?: number): Promise<TaskLogEntry[]>;
	/**
	 * Forget by appending. Accepts EITHER a log key or a store row id — the renderer lists rows
	 * through `dataList`, so it holds the store id, and the two usually coincide because the
	 * projection writes `id: m.id`; imported rows may not.
	 *
	 * Returns ok:false with a reason when nothing matched, rather than reporting success for a
	 * delete that removed nothing.
	 */
	memoryForget(key: string): Promise<{ ok: boolean; reason?: string }>;
	/** One memory's audit trail, oldest first. Empty for an unknown key. */
	memoryHistory(key: string): Promise<MemoryEvent[]>;
	/**
	 * The newest `n` events (default 500) plus the TOTAL count.
	 *
	 * Not a bare `all()`: the log is bounded at 20,000 events, so shipping all of them is a
	 * multi-MB round trip that stalls the very page it exists to serve. Carrying `total` keeps
	 * the window honest — "显示 500 / 共 N" instead of a list that silently looks complete.
	 */
	memoryEvents(n?: number): Promise<{ events: MemoryEvent[]; total: number }>;
	sessionList(): Promise<{ metas: SessionMeta[]; activeId: string }>;
	sessionCreate(title?: string, projectId?: string): Promise<SessionMeta>;
	sessionSwitch(id: string): Promise<boolean>;
	sessionRemove(id: string): Promise<boolean>;
	sessionRename(id: string, title: string): Promise<boolean>;
	sessionPin(id: string, pinned: boolean): Promise<boolean>;
	/**
	 * Group a session under a project, or ungroup it with `null` / `''`.
	 *
	 * The id is not validated against the `projects` collection — sessions and projects live in
	 * different stores, so this cannot see them, and refusing would block "group first, create the
	 * project later". A DELETED project clears the link instead, so no dangling id survives.
	 * False means the session id is unknown, never "silently did nothing".
	 */
	sessionSetProject(id: string, projectId: string | null): Promise<boolean>;
	filePick(): Promise<PickedFile | null>;
	mcpList(): Promise<McpServerDef[]>;
	mcpAdd(name: string, url: string): Promise<McpServerDef>;
	mcpRemove(id: string): Promise<boolean>;
	mcpSetEnabled(id: string, enabled: boolean): Promise<boolean>;
	mcpTest(url: string): Promise<McpTestResult>;
	/** Log a completed focus session (seconds). */
	focusLog(seconds: number): Promise<void>;
	/** Today's total focus seconds. */
	focusToday(): Promise<number>;
	/** Save a JSON payload (backup export). Returns saved path or null. */
	fileSaveJson(json: string, defaultName: string): Promise<string | null>;
	/** Pick a JSON file (backup import). Returns parsed object or null. */
	fileOpenJson(): Promise<unknown | null>;
	openDataDir(): Promise<void>;
	/** Probe the local Syncthing REST API (no spawn). */
	syncStatus(): Promise<SyncSummary>;
	/** Attach to a running instance, or spawn our own kernel. */
	syncStart(): Promise<SyncSummary>;
	/** Shut down a kernel we own (external instances are left alone). */
	syncStop(): Promise<SyncSummary>;
	/** Download the official Syncthing Windows build into the app dir. Returns the exe path. */
	syncInstall(): Promise<string>;
	syncAddDevice(deviceId: string, name: string): Promise<void>;
	syncAddFolder(label: string, folderPath: string): Promise<void>;
	syncRemoveFolder(id: string): Promise<void>;
	/** Pick syncthing.exe with a file dialog; persists to config.syncthingPath. */
	syncPickBinary(): Promise<string | null>;
	/** Tail of the kernel log file (error diagnosis). */
	syncLogTail(): Promise<string>;
	/** Whole Syncthing config (the raw JSON editor round-trips through this). */
	syncConfigGet(): Promise<StConfig>;
	syncConfigPut(cfg: StConfig): Promise<void>;
	syncDefaults(kind: 'folder' | 'device'): Promise<StFolderConfig | StDeviceConfig>;
	/** Server-side directory listing for the folder-path field. */
	syncBrowse(current: string): Promise<string[]>;
	syncIgnoresGet(folderId: string): Promise<string[]>;
	syncIgnoresPut(folderId: string, patterns: string[]): Promise<void>;
	syncPendingGet(): Promise<StPending>;
	/** Decline a pending device (device) or a folder offer (folder+from). */
	syncDecline(target: { device?: string; folder?: string; from?: string }): Promise<void>;
	/** Targeted config edits used by the dialogs. */
	syncPutDevice(cfg: StDeviceConfig): Promise<void>;
	syncDeleteDevice(deviceID: string): Promise<void>;
	syncPutFolder(cfg: StFolderConfig): Promise<void>;
	/** Trigger an immediate rescan of a folder. */
	syncScan(folderId: string): Promise<void>;
	syncSystemInfo(): Promise<StSystemInfo>;
	syncSystemLog(limit?: number): Promise<StLogLine[]>;
	syncErrorsGet(): Promise<StError[]>;
	syncErrorsClear(): Promise<void>;
	syncRestart(): Promise<void>;
	/** Open the original Syncthing GUI in its own window; resolves with the URL it opened. */
	syncOpenGui(): Promise<string>;
	// ---- data sync over Syncthing (pc-dump.json ⇄ phone-dump.json) ----
	syncDataGet(): Promise<DataSyncStatus>;
	syncDataSet(enabled: boolean): Promise<DataSyncStatus>;
	syncDataRun(kind: 'export' | 'import'): Promise<DataSyncStatus>;
	// ---- focus floating capsule ----
	capsuleShow(state: CapsuleState): Promise<void>;
	capsuleUpdate(state: CapsuleState): Promise<void>;
	capsuleHide(): Promise<void>;
	/** Capsule → main window: toggle=pause/resume, end=stop (recordable), open=focus app. */
	capsuleAction(action: 'toggle' | 'end' | 'open'): Promise<void>;
	onFocusAction(cb: (action: 'toggle' | 'end' | 'open') => void): () => void;
	onCapsuleState(cb: (state: CapsuleState) => void): () => void;
	// ---- M17 multi-agent kernel ----
	agentList(): Promise<AgentProfileInfo[]>;
	agentUpsert(p: Partial<AgentProfileInfo> & { name: string }): Promise<AgentProfileInfo>;
	agentDelete(id: string): Promise<boolean>;
	/** Seed the three preset teams; returns how many rows were inserted (idempotent). */
	agentSeedTeams(): Promise<number>;
	agentRun(agentId: string, sessionId: string, prompt: string): Promise<AgentRunInfo>;
	agentRunSub(agentId: string, parentSessionId: string, prompt: string): Promise<AgentRunInfo & { sessionId: string }>;
	agentAbort(agentId: string): Promise<number>;
	agentRuns(): Promise<{ runs: AgentRunInfo[]; running: number; limit: number }>;
	agentPost(fromAgent: string, toAgent: string, body: string, kind?: 'note' | 'steer' | 'result' | 'terminal'): Promise<AgentBusMessage>;
	agentInbox(toAgent: string): Promise<AgentBusMessage[]>;
	agentMarkRead(toAgent: string): Promise<number>;
	agentUsage(): Promise<AgentUsageRow[]>;
	guardState(): Promise<{ tripped: boolean; reason: string; signatures: string[] }>;
	checkpointPush(label: string): Promise<number>;
	checkpointList(): Promise<{ seq: number; label: string; at: number; turns: number }[]>;
	checkpointRewind(seq: number): Promise<boolean>;
	/** M17 kernel events (run started/finished, quota waits, bus messages). */
	onAgentEvent(cb: (e: unknown) => void): () => void;
	// ---- M17 待办表 (P2) ----
	todoList(sessionId?: string): Promise<AgentTodoInfo[]>;
	todoAdd(text: string, sessionId?: string): Promise<AgentTodoInfo | null>;
	todoDone(id: string): Promise<boolean>;
	todoReopen(id: string): Promise<boolean>;
	todoRemove(id: string): Promise<boolean>;
	todoPolicyGet(): Promise<TodoCheckPolicy>;
	todoPolicySet(patch: Partial<TodoCheckPolicy>): Promise<TodoCheckPolicy>;
	// ---- M17 平级模式 (P3) ----
	/** Templates with pre-filled slots (form 3: every slot stays editable). */
	peerTemplates(): Promise<{ key: string; name: string; description: string; slots: Omit<PeerSlotInfo, 'agentId'>[] }[]>;
	/** Fan out to 2-3 peers; each gets its own session (no parent/child). */
	peerStart(title: string, task: string, templateKey: string, slots: PeerSlotInfo[], agentIds: string[]): Promise<{ groupId: string; runIds: string[]; skipped: { index: number; reason: string }[] }>;
	peerPropose(groupId: string, agentId: string, text: string): Promise<boolean>;
	peerVote(groupId: string, voterId: string, targetAgentId: string, vote: number): Promise<boolean>;
	/** Majority vote. Ties come back unresolved on purpose. */
	peerSettle(groupId: string): Promise<PeerVoteOutcome>;
	peerView(groupId: string): Promise<{ group: PeerGroupInfo | null; proposals: { agentId: string; text: string; vote: number }[] }>;
	peerList(): Promise<PeerGroupInfo[]>;
	peerRemove(groupId: string): Promise<boolean>;
}
