import { contextBridge, ipcRenderer } from 'electron';
import { IPC, type AppInfo, type DesktopConfig, type SkillsApi, type SyncSummary } from '../../shared/ipc';
import { EVENTS, IPC as IPC_CHAT, type ApprovalDecision, type ApprovalRequest, type ChatApi, type ChatEvent } from '../../shared/chat';
import { FLOAT, type FloatApi } from '../../shared/float';
import type { PlanabilityApi } from './api';
import type { CollectionName, DataStats, Dump, Entity } from '../../shared/types';

function subscribe<T>(channel: string, cb: (payload: T) => void): () => void {
	const listener = (_e: Electron.IpcRendererEvent, payload: T) => cb(payload);
	ipcRenderer.on(channel, listener);
	return () => ipcRenderer.removeListener(channel, listener);
}

const chatApi: ChatApi = {
	send: (text: string, attachments?: string[]) => ipcRenderer.invoke(IPC_CHAT.chatSend, text, attachments),
	stop: () => ipcRenderer.invoke(IPC_CHAT.chatStop),
	clear: () => ipcRenderer.invoke(IPC_CHAT.chatClear),
	resolveApproval: (id: string, decision: ApprovalDecision) => ipcRenderer.invoke(IPC_CHAT.approvalResolve, id, decision),
	onChat: (cb: (e: ChatEvent) => void) => subscribe(EVENTS.chat, cb),
	onApproval: (cb: (req: ApprovalRequest) => void) => subscribe(EVENTS.approval, cb),
	getRows: () => ipcRenderer.invoke(IPC_CHAT.chatGetRows) as Promise<import('../../shared/chat').ConvRow[]>,
	rollbackUsers: (k: number) => ipcRenderer.invoke(IPC_CHAT.chatRollbackUsers, k) as Promise<void>,
	// ---- M17 实时插话 ----
	enqueue: (text: string) => ipcRenderer.invoke(IPC_CHAT.chatEnqueue, text) as Promise<{ queued: number }>,
	enqueueList: () => ipcRenderer.invoke(IPC_CHAT.chatEnqueueList) as Promise<{ text: string; at: number }[]>,
	enqueueClear: () => ipcRenderer.invoke(IPC_CHAT.chatEnqueueClear) as Promise<void>,
	injectMode: () => ipcRenderer.invoke(IPC_CHAT.chatInjectMode) as Promise<import('../../shared/chat').InjectMode>,
	setInjectMode: (m: import('../../shared/chat').InjectMode) =>
		ipcRenderer.invoke(IPC_CHAT.chatSetInjectMode, m) as Promise<import('../../shared/chat').InjectMode>,
	// ---- M19-1 ContextBudget ----
	// The renderer is handed the main process's numbers, never asked to compute them: the
	// request also carries the system prompt and the tool definitions, so a panel counting
	// visible text reports a different figure than the provider was billed.
	contextManifest: () => ipcRenderer.invoke(IPC_CHAT.contextManifest) as Promise<import('../../shared/chat').ContextManifest | null>,
	contextTogglesGet: () => ipcRenderer.invoke(IPC_CHAT.contextTogglesGet) as Promise<Partial<Record<import('../../shared/chat').ContextKey, boolean>>>,
	contextTogglesSet: (t) => ipcRenderer.invoke(IPC_CHAT.contextTogglesSet, t) as Promise<Partial<Record<import('../../shared/chat').ContextKey, boolean>>>,
	contextHistory: (limit?: number) => ipcRenderer.invoke(IPC_CHAT.contextHistory, limit) as Promise<import('../../shared/chat').ContextManifest[]>,
	// ---- M20-1 workflows ----
	workflowList: () => ipcRenderer.invoke(IPC_CHAT.workflowList) as Promise<import('../../shared/chat').WorkflowInfo[]>,
	workflowValidate: (w: unknown) => ipcRenderer.invoke(IPC_CHAT.workflowValidate, w) as Promise<{ ok: boolean; errors: string[] }>,
	workflowRun: (workflowId: string, task: string) => ipcRenderer.invoke(IPC_CHAT.workflowRun, workflowId, task) as Promise<{ ok: boolean; runId?: string; error?: string }>,
	workflowReplay: (runId: string) => ipcRenderer.invoke(IPC_CHAT.workflowReplay, runId) as Promise<{ ok: boolean; text?: string }>,
	workflowRuns: (limit?: number) => ipcRenderer.invoke(IPC_CHAT.workflowRuns, limit) as Promise<import('../../shared/chat').WorkflowRunInfo[]>,
	/** Run a `list` card's query. Rejects on a malformed query rather than returning []. */
	listQuery: (q: unknown) => ipcRenderer.invoke(IPC_CHAT.listQuery, q) as Promise<import('../../shared/chat').ListCardResult>,
	/** Run a declared card action as a real gated tool call. `denied` means the user said no
	 *  (or nobody answered in 120s) — show it rather than pretending the click did nothing. */
	cardAction: (req: import('../../shared/chat').CardActionRequest) =>
		ipcRenderer.invoke(IPC_CHAT.cardAction, req) as Promise<import('../../shared/chat').CardActionRequestResult>,
	// ---- card entities (stage 2): the store's read / pin / remove surface ----
	/** Stored cards (filter by session / row / kind). `list` view cards persist; proposals
	 *  belong to their message. */
	cardList: (q?: import('../../shared/chat').CardListQuery) =>
		ipcRenderer.invoke(IPC_CHAT.cardList, q) as Promise<import('../../shared/cards').CardEntity[]>,
	cardPin: (id: string, pinned: boolean) =>
		ipcRenderer.invoke(IPC_CHAT.cardPin, id, pinned) as Promise<import('../../shared/chat').CardOpResult>,
	/** Removes a VIEW card; a proposal card refuses with `reason` — show it, do not swallow. */
	cardRemove: (id: string) =>
		ipcRenderer.invoke(IPC_CHAT.cardRemove, id) as Promise<import('../../shared/chat').CardOpResult>,
	onWorkflow: (cb: (e: import('../../shared/chat').WorkflowEvent) => void) => subscribe(EVENTS.workflowEvent, cb),
	// ---- M20-3/4 ----
	teamAssemble: (requirement: string, preferTemplates?: string[]) =>
		ipcRenderer.invoke(IPC_CHAT.teamAssemble, requirement, preferTemplates) as Promise<import('../../shared/chat').TeamAssemblyResult>,
	handoffPreview: (h: unknown) =>
		ipcRenderer.invoke(IPC_CHAT.handoffPreview, h) as Promise<{ ok: boolean; text?: string; reason?: string }>,
	handoffWrite: (h: unknown) =>
		ipcRenderer.invoke(IPC_CHAT.handoffWrite, h) as Promise<{ ok: boolean; path?: string; reason?: string }>,
	handoffRead: (name: string) =>
		ipcRenderer.invoke(IPC_CHAT.handoffRead, name) as Promise<{ ok: boolean; text?: string }>,
	handoffList: () =>
		ipcRenderer.invoke(IPC_CHAT.handoffList) as Promise<import('../../shared/chat').HandoffFileInfo[]>
};

// ---- M19-4 技能管理：读写回环的是「文件原文」而不是结构化字段 ----
// 页面是文件上的文本框，不是表单。理由见 shared/ipc.ts 的 SkillsApi —— 结构化写入会在每次
// 保存时静默丢掉 UI 不认识的 frontmatter 键并重排文件，而这个模块存在的理由就是「技能是人
// 拿自己的编辑器写的」。
const skillsApi: SkillsApi = {
	list: () => ipcRenderer.invoke(IPC.skillsList),
	read: (key: string) => ipcRenderer.invoke(IPC.skillsRead, key),
	write: (key: string, text: string) => ipcRenderer.invoke(IPC.skillsWrite, key, text),
};

// ---- M21 悬浮窗（shared/float.ts 契约的桥；实现方是 main/float/host.ts）----
const floatApi: FloatApi = {
	state: () => ipcRenderer.invoke(FLOAT.state),
	enter: (sessionId: string) => ipcRenderer.invoke(FLOAT.enter, sessionId),
	exit: () => ipcRenderer.invoke(FLOAT.exit),
	draftGet: (sessionId: string) => ipcRenderer.invoke(FLOAT.draftGet, sessionId),
	draftSet: (sessionId: string, text: string, attachments, baseRevision: number) =>
		ipcRenderer.invoke(FLOAT.draftSet, sessionId, text, attachments, baseRevision),
	draftAttach: (sessionId: string, a) => ipcRenderer.invoke(FLOAT.draftAttach, sessionId, a),
	draftRemove: (sessionId: string, p: string) => ipcRenderer.invoke(FLOAT.draftRemove, sessionId, p),
	modeSet: (m) => ipcRenderer.invoke(FLOAT.modeSet, m),
	shutter: (sessionId: string) => ipcRenderer.invoke(FLOAT.shutter, sessionId),
	shutterWindow: (sessionId: string, id: string) => ipcRenderer.invoke(FLOAT.shutterWindow, sessionId, id),
	listTargets: () => ipcRenderer.invoke(FLOAT.listTargets),
	movePane: (role, x, y) => ipcRenderer.invoke(FLOAT.movePane, role, x, y),
	setLinked: (role, linked) => ipcRenderer.invoke(FLOAT.setLinked, role, linked),
	saveLayout: () => ipcRenderer.invoke(FLOAT.saveLayout),
	onFloat: (cb) => subscribe(FLOAT.event, cb)
};

const api: PlanabilityApi = {
	list: <T extends Entity>(collection: CollectionName) =>
		ipcRenderer.invoke(IPC.dataList, collection) as Promise<T[]>,
	upsert: <T extends Entity>(collection: CollectionName, item: T) =>
		ipcRenderer.invoke(IPC.dataUpsert, collection, item) as Promise<T>,
	softDelete: (collection: CollectionName, id: string) =>
		ipcRenderer.invoke(IPC.dataSoftDelete, collection, id) as Promise<boolean>,
	stats: () => ipcRenderer.invoke(IPC.dataStats) as Promise<DataStats>,
	exportDump: () => ipcRenderer.invoke(IPC.dataExport) as Promise<Dump>,
	importDump: (dump: Dump) => ipcRenderer.invoke(IPC.dataImport, dump) as Promise<number>,
	appInfo: () => ipcRenderer.invoke(IPC.appInfo) as Promise<AppInfo>,
	configGet: () => ipcRenderer.invoke(IPC.configGet) as Promise<DesktopConfig>,
	configSet: (patch: DesktopConfig) => ipcRenderer.invoke(IPC.configSet, patch) as Promise<void>,
	usageSummary: () => ipcRenderer.invoke(IPC.usageSummary) as Promise<import('../../shared/ipc').UsageSummary>,
	automationList: () => ipcRenderer.invoke(IPC.automationList) as Promise<import('../../shared/ipc').AutomationDef[]>,
	automationCreate: (input: import('../../shared/ipc').AutomationInput) => ipcRenderer.invoke(IPC.automationCreate, input) as Promise<import('../../shared/ipc').AutomationDef>,
	automationSetEnabled: (id: number, enabled: boolean) => ipcRenderer.invoke(IPC.automationSetEnabled, id, enabled) as Promise<boolean>,
	automationUpdate: (id: number, patch: Partial<import('../../shared/ipc').AutomationDef>) =>
		ipcRenderer.invoke(IPC.automationUpdate, id, patch) as Promise<{ ok: true; item: import('../../shared/ipc').AutomationDef } | { ok: false }>,
	automationRemove: (id: number) => ipcRenderer.invoke(IPC.automationRemove, id) as Promise<boolean>,
	automationLog: (id: number) => ipcRenderer.invoke(IPC.automationLog, id) as Promise<string>,
	automationTaskLog: (id: number, n?: number) => ipcRenderer.invoke(IPC.automationTaskLog, id, n) as Promise<import('../../shared/ipc').TaskLogEntry[]>,
	memoryForget: (key: string) => ipcRenderer.invoke(IPC.memoryForget, key) as Promise<{ ok: boolean; reason?: string }>,
	memoryHistory: (key: string) => ipcRenderer.invoke(IPC.memoryHistory, key) as Promise<import('../../shared/types').MemoryEvent[]>,
	memoryEvents: (n?: number) => ipcRenderer.invoke(IPC.memoryEvents, n) as Promise<{ events: import('../../shared/types').MemoryEvent[]; total: number }>,
	sessionList: () => ipcRenderer.invoke(IPC.sessionList) as Promise<{ metas: import('../../shared/ipc').SessionMeta[]; activeId: string }>,
	sessionCreate: (title?: string, projectId?: string) => ipcRenderer.invoke(IPC.sessionCreate, title, projectId) as Promise<import('../../shared/ipc').SessionMeta>,
	sessionSwitch: (id: string) => ipcRenderer.invoke(IPC.sessionSwitch, id) as Promise<boolean>,
	sessionRemove: (id: string) => ipcRenderer.invoke(IPC.sessionRemove, id) as Promise<boolean>,
	sessionRename: (id: string, title: string) => ipcRenderer.invoke(IPC.sessionRename, id, title) as Promise<boolean>,
	sessionPin: (id: string, pinned: boolean) => ipcRenderer.invoke(IPC.sessionPin, id, pinned) as Promise<boolean>,
	// null / '' both mean "no project". false means the session id is unknown — never a silent no-op.
	sessionSetProject: (id: string, projectId: string | null) => ipcRenderer.invoke(IPC.sessionSetProject, id, projectId) as Promise<boolean>,
	// ---- M17 multi-agent kernel (main-process only; no node APIs cross this boundary) ----
	agentList: () => ipcRenderer.invoke(IPC.agentList) as Promise<import('../../shared/ipc').AgentProfileInfo[]>,
	agentUpsert: (p: Partial<import('../../shared/ipc').AgentProfileInfo> & { name: string }) =>
		ipcRenderer.invoke(IPC.agentUpsert, p) as Promise<import('../../shared/ipc').AgentProfileInfo>,
	agentDelete: (id: string) => ipcRenderer.invoke(IPC.agentDelete, id) as Promise<boolean>,
	agentSeedTeams: () => ipcRenderer.invoke(IPC.agentSeedTeams) as Promise<number>,
	agentRun: (agentId: string, sessionId: string, prompt: string) =>
		ipcRenderer.invoke(IPC.agentRun, agentId, sessionId, prompt) as Promise<import('../../shared/ipc').AgentRunInfo>,
	agentRunSub: (agentId: string, parentSessionId: string, prompt: string) =>
		ipcRenderer.invoke(IPC.agentRunSub, agentId, parentSessionId, prompt) as Promise<import('../../shared/ipc').AgentRunInfo & { sessionId: string }>,
	agentAbort: (agentId: string) => ipcRenderer.invoke(IPC.agentAbort, agentId) as Promise<number>,
	agentRuns: () => ipcRenderer.invoke(IPC.agentRuns) as Promise<{ runs: import('../../shared/ipc').AgentRunInfo[]; running: number; limit: number }>,
	agentPost: (fromAgent: string, toAgent: string, body: string, kind?: 'note' | 'steer' | 'result' | 'terminal') =>
		ipcRenderer.invoke(IPC.agentMessage, fromAgent, toAgent, body, kind) as Promise<import('../../shared/ipc').AgentBusMessage>,
	agentInbox: (toAgent: string) => ipcRenderer.invoke(IPC.agentInbox, toAgent) as Promise<import('../../shared/ipc').AgentBusMessage[]>,
	agentMarkRead: (toAgent: string) => ipcRenderer.invoke(IPC.agentMarkRead, toAgent) as Promise<number>,
	agentUsage: () => ipcRenderer.invoke(IPC.agentUsage) as Promise<import('../../shared/ipc').AgentUsageRow[]>,
	guardState: () => ipcRenderer.invoke(IPC.guardState) as Promise<{ tripped: boolean; reason: string; signatures: string[] }>,
	checkpointPush: (label: string) => ipcRenderer.invoke(IPC.checkpointPush, label) as Promise<number>,
	checkpointList: () => ipcRenderer.invoke(IPC.checkpointList) as Promise<{ seq: number; label: string; at: number; turns: number }[]>,
	checkpointRewind: (seq: number) => ipcRenderer.invoke(IPC.checkpointRewind, seq) as Promise<boolean>,
	filePick: () => ipcRenderer.invoke(IPC.filePick) as Promise<import('../../shared/ipc').PickedFile | null>,
	mcpList: () => ipcRenderer.invoke(IPC.mcpList) as Promise<import('../../shared/ipc').McpServerDef[]>,
	mcpAdd: (name: string, url: string) => ipcRenderer.invoke(IPC.mcpAdd, name, url) as Promise<import('../../shared/ipc').McpServerDef>,
	mcpRemove: (id: string) => ipcRenderer.invoke(IPC.mcpRemove, id) as Promise<boolean>,
	mcpSetEnabled: (id: string, enabled: boolean) => ipcRenderer.invoke(IPC.mcpSetEnabled, id, enabled) as Promise<boolean>,
	mcpTest: (url: string) => ipcRenderer.invoke(IPC.mcpTest, url) as Promise<import('../../shared/ipc').McpTestResult>,
	focusLog: (seconds: number) => ipcRenderer.invoke(IPC.focusLog, seconds) as Promise<void>,
	focusToday: () => ipcRenderer.invoke(IPC.focusToday) as Promise<number>,
	fileSaveJson: (json: string, defaultName: string) => ipcRenderer.invoke(IPC.fileSaveJson, json, defaultName) as Promise<string | null>,
	fileOpenJson: () => ipcRenderer.invoke(IPC.fileOpenJson) as Promise<unknown | null>,
	openDataDir: () => ipcRenderer.invoke(IPC.openDataDir) as Promise<void>,
	syncStatus: () => ipcRenderer.invoke(IPC.syncStatus) as Promise<SyncSummary>,
	syncStart: () => ipcRenderer.invoke(IPC.syncStart) as Promise<SyncSummary>,
	syncStop: () => ipcRenderer.invoke(IPC.syncStop) as Promise<SyncSummary>,
	syncInstall: () => ipcRenderer.invoke(IPC.syncInstall) as Promise<string>,
	syncAddDevice: (deviceId: string, name: string) => ipcRenderer.invoke(IPC.syncAddDevice, deviceId, name) as Promise<void>,
	syncAddFolder: (label: string, folderPath: string) => ipcRenderer.invoke(IPC.syncAddFolder, label, folderPath) as Promise<void>,
	syncRemoveFolder: (id: string) => ipcRenderer.invoke(IPC.syncRemoveFolder, id) as Promise<void>,
	syncPickBinary: () => ipcRenderer.invoke(IPC.syncPickBinary) as Promise<string | null>,
	syncLogTail: () => ipcRenderer.invoke(IPC.syncLogTail) as Promise<string>,
	syncConfigGet: () => ipcRenderer.invoke(IPC.syncConfigGet) as Promise<import('../../shared/ipc').StConfig>,
	syncConfigPut: (cfg: import('../../shared/ipc').StConfig) => ipcRenderer.invoke(IPC.syncConfigPut, cfg) as Promise<void>,
	syncDefaults: (kind: 'folder' | 'device') => ipcRenderer.invoke(IPC.syncDefaults, kind) as Promise<import('../../shared/ipc').StFolderConfig | import('../../shared/ipc').StDeviceConfig>,
	syncBrowse: (current: string) => ipcRenderer.invoke(IPC.syncBrowse, current) as Promise<string[]>,
	syncIgnoresGet: (folderId: string) => ipcRenderer.invoke(IPC.syncIgnoresGet, folderId) as Promise<string[]>,
	syncIgnoresPut: (folderId: string, patterns: string[]) => ipcRenderer.invoke(IPC.syncIgnoresPut, folderId, patterns) as Promise<void>,
	syncPendingGet: () => ipcRenderer.invoke(IPC.syncPendingGet) as Promise<import('../../shared/ipc').StPending>,
	syncDecline: (target: { device?: string; folder?: string; from?: string }) => ipcRenderer.invoke(IPC.syncDecline, target) as Promise<void>,
	syncPutDevice: (cfg: import('../../shared/ipc').StDeviceConfig) => ipcRenderer.invoke(IPC.syncPutDevice, cfg) as Promise<void>,
	syncDeleteDevice: (deviceID: string) => ipcRenderer.invoke(IPC.syncDeleteDevice, deviceID) as Promise<void>,
	syncPutFolder: (cfg: import('../../shared/ipc').StFolderConfig) => ipcRenderer.invoke(IPC.syncPutFolder, cfg) as Promise<void>,
	syncScan: (folderId: string) => ipcRenderer.invoke(IPC.syncScan, folderId) as Promise<void>,
	syncSystemInfo: () => ipcRenderer.invoke(IPC.syncSystemInfo) as Promise<import('../../shared/ipc').StSystemInfo>,
	syncSystemLog: (limit?: number) => ipcRenderer.invoke(IPC.syncSystemLog, limit) as Promise<import('../../shared/ipc').StLogLine[]>,
	syncErrorsGet: () => ipcRenderer.invoke(IPC.syncErrorsGet) as Promise<import('../../shared/ipc').StError[]>,
	syncErrorsClear: () => ipcRenderer.invoke(IPC.syncErrorsClear) as Promise<void>,
	syncRestart: () => ipcRenderer.invoke(IPC.syncRestart) as Promise<void>,
	syncOpenGui: () => ipcRenderer.invoke(IPC.syncOpenGui) as Promise<string>,
	syncDataGet: () => ipcRenderer.invoke(IPC.syncDataGet) as Promise<import('../../shared/ipc').DataSyncStatus>,
	syncDataSet: (enabled: boolean) => ipcRenderer.invoke(IPC.syncDataSet, enabled) as Promise<import('../../shared/ipc').DataSyncStatus>,
	syncDataRun: (kind: 'export' | 'import') => ipcRenderer.invoke(IPC.syncDataRun, kind) as Promise<import('../../shared/ipc').DataSyncStatus>,
	capsuleShow: (state: import('../../shared/ipc').CapsuleState) => ipcRenderer.invoke(IPC.capsuleShow, state) as Promise<void>,
	capsuleUpdate: (state: import('../../shared/ipc').CapsuleState) => ipcRenderer.invoke(IPC.capsuleUpdate, state) as Promise<void>,
	capsuleHide: () => ipcRenderer.invoke(IPC.capsuleHide) as Promise<void>,
	capsuleAction: (action: 'toggle' | 'end' | 'open') => ipcRenderer.invoke(IPC.capsuleAction, action) as Promise<void>,
	onFocusAction: (cb: (action: 'toggle' | 'end' | 'open') => void) => subscribe(IPC.focusAction, cb),
	onCapsuleState: (cb: (state: import('../../shared/ipc').CapsuleState) => void) => subscribe(IPC.capsuleState, cb),
	/** M17 kernel events. */
	onAgentEvent: (cb: (e: unknown) => void) => subscribe(IPC.agentEvent, cb),
	// ---- M17 待办表 (P2) ----
	todoList: (sessionId?: string) => ipcRenderer.invoke(IPC.todoList, sessionId) as Promise<import('../../shared/ipc').AgentTodoInfo[]>,
	todoAdd: (text: string, sessionId?: string) => ipcRenderer.invoke(IPC.todoAdd, text, sessionId) as Promise<import('../../shared/ipc').AgentTodoInfo | null>,
	todoDone: (id: string) => ipcRenderer.invoke(IPC.todoDone, id) as Promise<boolean>,
	todoReopen: (id: string) => ipcRenderer.invoke(IPC.todoReopen, id) as Promise<boolean>,
	todoRemove: (id: string) => ipcRenderer.invoke(IPC.todoRemove, id) as Promise<boolean>,
	todoPolicyGet: () => ipcRenderer.invoke(IPC.todoPolicyGet) as Promise<import('../../shared/ipc').TodoCheckPolicy>,
	todoPolicySet: (patch: Partial<import('../../shared/ipc').TodoCheckPolicy>) =>
		ipcRenderer.invoke(IPC.todoPolicySet, patch) as Promise<import('../../shared/ipc').TodoCheckPolicy>,
	// ---- M17 平级模式 (P3) ----
	peerTemplates: () => ipcRenderer.invoke(IPC.peerTemplates) as Promise<
		{ key: string; name: string; description: string; slots: Omit<import('../../shared/ipc').PeerSlotInfo, 'agentId'>[] }[]>,
	peerStart: (title: string, task: string, templateKey: string, slots: import('../../shared/ipc').PeerSlotInfo[], agentIds: string[]) =>
		ipcRenderer.invoke(IPC.peerStart, title, task, templateKey, slots, agentIds) as Promise<
			{ groupId: string; runIds: string[]; skipped: { index: number; reason: string }[] }>,
	peerPropose: (groupId: string, agentId: string, text: string) =>
		ipcRenderer.invoke(IPC.peerPropose, groupId, agentId, text) as Promise<boolean>,
	peerVote: (groupId: string, voterId: string, targetAgentId: string, vote: number) =>
		ipcRenderer.invoke(IPC.peerVote, groupId, voterId, targetAgentId, vote) as Promise<boolean>,
	peerSettle: (groupId: string) => ipcRenderer.invoke(IPC.peerSettle, groupId) as Promise<import('../../shared/ipc').PeerVoteOutcome>,
	peerView: (groupId: string) => ipcRenderer.invoke(IPC.peerView, groupId) as Promise<
		{ group: import('../../shared/ipc').PeerGroupInfo | null; proposals: { agentId: string; text: string; vote: number }[] }>,
	peerList: () => ipcRenderer.invoke(IPC.peerList) as Promise<import('../../shared/ipc').PeerGroupInfo[]>,
	peerRemove: (groupId: string) => ipcRenderer.invoke(IPC.peerRemove, groupId) as Promise<boolean>,
	chat: chatApi,
	float: floatApi,
	skills: skillsApi
};

contextBridge.exposeInMainWorld('planability', api);
