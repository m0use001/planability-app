import { app, BrowserWindow, clipboard, desktopCapturer, dialog, ipcMain, Menu, nativeImage, Notification, screen, session, Tray } from 'electron';
import path from 'node:path';
import * as fs from 'node:fs';
import { promises as fsp } from 'node:fs';
import { Store, type Memory } from './store/store';
import { enabledDomains } from '../../shared/tool-domains';
import { IPC, type AutomationInput, type SkillFile, type SkillListResult, type SkillWriteResult } from '../../shared/ipc';
import { IPC as IPC_CHAT, EVENTS as CHAT_EVENTS, type ApprovalDecision, type ApprovalRequest, type CardActionRequest, type CardListQuery, type ChatEvent, type WorkflowInfo } from '../../shared/chat';
import { registerPlanabilityTools, projectMemories, forgetMemory } from './ai/tools';
import { ChatAgent, type AgentEvent } from './ai/agent';
import { RowBridge, normalizeRows, type RowCardSink } from './ai/rows';
import { normalizeListQuery, runListQuery } from './ai/list-card';
import { runCardAction } from './ai/card-actions';
import { CardStore } from './ai/card-store';
import { registerListQueryTool } from './ai/listquery-tools';
import { normalizeAttachments, withAttachmentRefs } from './ai/chat-attachments';
import type { Automation } from './ai/automation';
import { AutomationEngine } from './ai/automation';
import { SessionStore, type ChatSession } from './ai/sessions';
import { UndoStack } from './ai/undo';
import { McpHub } from './ai/mcp';
import { AiDb } from './ai/db';
import { AgentManager } from './ai/agents/AgentManager';
import { TodoStore } from './ai/todos';
import { registerTodoTools } from './ai/todo-tools';
import { registerPlanTools } from './ai/plan-tools';
import { registerLifeTools } from './ai/life-tools';
import { PeerStore } from './ai/peers';
import { PeerOrchestrator } from './ai/peer-run';
import { PEER_TEMPLATES, templateSlots } from './ai/peer-templates';
import { registerAgentTools, type SubAgentHost } from './ai/agent-tools';
import { TypedPartStore } from './ai/typed-parts';
import { Workspace } from './ai/workspace';
import { registerFileTools, FileUndoStack } from './ai/file-tools';
import { resolveProjectRoot } from './ai/project-root';
import { mirrorTo, mirrorMarkdown, sessionDirName } from './ai/session-mirror';
import { registerFileOfficeTools } from './ai/file-office-tools';
import { registerFileEditTools } from './ai/file-edit-tools';
import { registerFileReadDocument } from './ai/doc-read-tools';
import { registerLibraryTools } from './ai/library-tools';
import { registerWebTools } from './ai/web-tools';
import { registerShellTools } from './ai/shell-tools';
import { registerIcsTools } from './ai/ics';
import { registerSettingsTools } from './ai/settings-tools';
import { registerExpertTools } from './ai/expert-tools';
import { registerWorkflowTools } from './ai/workflow-tools';
import { registerTeamTool } from './ai/team-tools';
import { registerCheckpointTools } from './ai/checkpoint-tools';
import { registerHandoffTools, sanitizeHandoffName } from './ai/handoff-tools';
import { registerSkillTools } from './ai/skill-tools';
import { registerAutomationTools } from './ai/automation-tools';
import { renderDiff, diffStat } from './ai/diff';
import { CaptureStore, CU_LIMITS, type ScreenGeometry, type WindowInfo } from './ai/cu';
import { registerCuTools } from './ai/cu-tools';
import { cuNativeError, loadCuNative } from './ai/cu-native';
import { makeSummarizer } from './ai/summarize';
import { openMemoryLog, seedMissing, applyProposals } from './ai/memory-log';
import { makeMemorySettler } from './ai/memory-extract';
import { loadSkills, renderSkills, selectSkills, builtinSkillFiles, toSkill, sanitizeKey, listUserSkillFiles, resolveUserSkillFile, writeSkillText } from './ai/skills';
import { JournalStore, agentStepRunner, demoWorkflow, matchRole, replayJournal, runWorkflow, validateWorkflow, type Workflow } from './ai/workflow';
import { buildWorkflowCatalog, type WorkflowCatalog } from './ai/workflow-store';
import { backgroundSessionName } from './ai/automation-task';
import { assembleTeam } from './ai/team-assemble';
import { renderHandoff, writeHandoff } from './ai/handoff';
import { MANIFEST_KEYS, type ManifestToggles } from './ai/context-budget';
import { decideKeepAlive, trayIconDataUrl, trayStatusLine, trayTooltip, type WindowRole as AliveWindowRole } from './float/keepalive';
import { FloatHost } from './float/host';
import { GUI_HOST, GUI_PORT, SyncthingEngine } from './syncthing';
import { DataSyncService } from './datasync';
import { callNonStream, mainSlotConfig, type SlotConfig } from './ai/provider';
import type { AppInfo, DesktopConfig, McpServerDef, McpTestResult, PermMode, SessionMeta, SyncSummary, ToolUsage, UsageSummary } from '../../shared/ipc';
import type { CollectionName, DataStats, Dump, Project, Todo, Assistant } from '../../shared/types';

/**
 * The ONE place that decides where Planability keeps its files.
 *
 * This used to be spelled as a literal 'Planability' in six separate path.join() calls, while
 * the capture store used app.getPath('userData'). Those two are not the same directory and the
 * reason why is the launch method: userData is derived from app.getName(), and Electron resolves
 * that differently depending on how it was invoked. Given a directory (`electron .`) it reads
 * package.json `name` -> %APPDATA%\planability-desktop; given a bundled JS file
 * (`electron out/main/index.js`, which is what launch-dev.cmd does) there is no package.json to
 * read, so it falls back to the default "Electron" -> %APPDATA%\Electron. Both spellings were in
 * use, so a user who launched two different ways ended up with their sessions, screenshots and
 * syncthing index split across three directories.
 *
 * Fix: decide once, explicitly, before anything reads a path, and make every subsystem use this.
 * `app.setName` must run before app.whenReady and before the first getPath call, otherwise
 * Electron has already cached the old default.
 *
 * Note this is deliberately NOT productName. productName is what the installer and the Start Menu
 * entry show the user ("Planability", capitalised); the directory name is an internal identifier and
 * is kept lower-case to match package.json `name`.
 */
const APP_DIR_NAME = 'planability-desktop';
app.setName(APP_DIR_NAME);

/** %APPDATA%\planability-desktop. Everything the app persists lives under here. */
function appDir(): string {
	return path.join(app.getPath('appData'), APP_DIR_NAME);
}

/**
 * Fan a main-process event out to every live window.
 *
 * Deliberately not `mainWindow`: that window is hidden whenever the user is working in the
 * floating group, which is the whole point of F0 — so anything aimed only at mainWindow arrives
 * while nobody is looking at it, and a floating window's transcript never updates (it had to poll
 * every 800ms as a workaround). Destroyed windows are skipped because a half-torn-down window
 * still answers getAllWindows() and throws on send.
 */
function broadcast(channel: string, payload: unknown): void {
	for (const win of BrowserWindow.getAllWindows()) {
		if (!win.isDestroyed()) { win.webContents.send(channel, payload); }
	}
}

/** Fan a chat event out — see broadcast(). */
function broadcastChat(e: ChatEvent): void { broadcast(CHAT_EVENTS.chat, e); }

let mainWindow: BrowserWindow | null = null;

function createWindow(): void {
	// --size=WxH：无头视觉验收用，强制内容区尺寸（useContentSize=真，capturePage 出图即 W×H）。
	// 平板目标 1600×1000，必须与手机参照截图同一逻辑尺寸，否则宽窄分档/列缘根本不在一个基准上。
	const sizeArg = process.argv.find(a => a.startsWith('--size='));
	let winW = 1200;
	let winH = 800;
	if (sizeArg) {
		const parts = sizeArg.slice('--size='.length).split('x');
		const w = Number(parts[0]);
		const h = Number(parts[1]);
		if (Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0) { winW = w; winH = h; }
	}
	mainWindow = new BrowserWindow({
		width: winW,
		height: winH,
		useContentSize: !!sizeArg,
		// 直接 show：hidden-titlebar+overlay 窗口走 show:false→ready-to-show→show 时合成器
		// 不产 BeginFrame（实测 rAF=0、入场动画冻结在 opacity 0 = 整页白屏；invalidate/
		// resize 微推都救不回，show:true 则一切正常）。闪白由 backgroundColor 兜住（= 应用底色，
		// 与原 no-flash 效果等价），首帧内容就绪前就是一块纯底色。
		show: true,
		backgroundColor: '#171717',
		// 隐藏原生标题栏：内容顶到窗顶，窗口三键走系统 overlay（Windows）；
		// renderer 顶部铺一条 app-region:drag 拖拽带（styles.css .titlebar-drag）。
		// 原生菜单栏（File/Edit/…）整个移除——那是默认壳没收拾的遗留。
		titleBarStyle: 'hidden',
		titleBarOverlay: {
			color: '#121316',
			symbolColor: '#c7c6cc',
			height: 36
		},
		webPreferences: {
			preload: path.join(__dirname, '../preload/index.js'),
			contextIsolation: true,
			nodeIntegration: false,
			sandbox: false
		}
	});
	mainWindow.once('ready-to-show', () => mainWindow?.focus());
	// Diagnostics: surface renderer crashes and console errors in the main-process stdout.
	mainWindow.webContents.on('render-process-gone', (_e, details) => {
		console.error('[renderer-gone]', details.reason, details.exitCode);
	});
	mainWindow.webContents.on('console-message', (_e, level, message, line, sourceId) => {
		if (level >= 2) { console.error(`[renderer:${level}] ${message} (${sourceId}:${line})`); }
	});
	// --kitchen：开发用，直达 /dev/kitchen 组件试验场。
	// --shot=<file>#<hash>：无头视觉验收（P3 每页 A/B）——加载带 hash 的页面，渲染稳定后
	// 主进程 capturePage 存 PNG 后退出。CDP captureScreenshot 在本机此启动方式下会挂
	// （pc-b 10-30 冒烟实录：fromSurface:false / bringToFront / captureBeyondViewport 全超时），
	// 所以走 Electron 自己的 capturePage。
	const shotArg = process.argv.find(a => a.startsWith('--shot='));
	const shotSpec = shotArg ? shotArg.split('=').slice(1).join('=') : '';
	const shotFile = shotSpec.split('#')[0];
	const shotHash = shotArg ? (shotSpec.split('#')[1] || 'dev-kitchen') : (process.argv.includes('--kitchen') ? 'dev-kitchen' : undefined);
	// --theme=light|dark：视觉验收用绝对指定（theme.ts 读 ?theme=，优先于 localStorage；
	// 否则 --shot 独立实例会受上一个进程的存储落盘状态影响，截图不可复现）
	const themeArg = process.argv.find(a => a.startsWith('--theme='));
	const theme = themeArg ? themeArg.split('=')[1] : undefined;
	if (process.env.ELECTRON_RENDERER_URL) {
		const q = theme ? `?theme=${theme}` : '';
		mainWindow.loadURL(shotHash ? `${process.env.ELECTRON_RENDERER_URL}${q}#${shotHash}` : `${process.env.ELECTRON_RENDERER_URL}${q}`);
	} else {
		mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'), {
			hash: shotHash,
			query: theme ? { theme } : undefined,
		});
	}
	if (shotArg) {
		mainWindow.webContents.once('did-finish-load', () => {
			// 2.5s：等首屏 IPC 数据往返与字体稳定；抓完即退，不留进程
			setTimeout(async () => {
				const win = mainWindow;
				if (!win) { app.quit(); return; }
				try {
					const image = await win.webContents.capturePage();
					fs.writeFileSync(shotFile, image.toPNG());
					console.log('[shot]', shotFile, `${image.getSize().width}x${image.getSize().height}`);
				} catch (e) {
					console.error('[shot] 失败', e);
				} finally {
					app.quit();
				}
			}, 2500);
		});
	}
}

app.whenReady().then(() => {
	// 移除默认应用菜单（File/Edit/View/Window/Help）：无它，功能一个不少；
	// 有它，hidden 标题栏下仍会顶出一条菜单行（用户点名的丑陋顶栏）。
	Menu.setApplicationMenu(null);
	const store = new Store(path.join(appDir(), 'data'));

	ipcMain.handle(IPC.dataList, (_e, collection: CollectionName) => store.live(collection));
	ipcMain.handle(IPC.dataUpsert, (_e, collection: CollectionName, item) =>
		store.upsert(collection, item).then(rec => {
			if (collection === 'assistants') { void refreshAssistants(); }
			return rec;
		}));
	// A memory cannot go straight through `softDelete`: the collection is a MATERIALISED VIEW of
	// memLog, and a row marked deleted while the log still says it is live is written straight
	// back by the next projection. Routing here enforces that for every caller, rather than
	// relying on each page to remember the nicer channel — the invariant belongs to the boundary.
	//
	// Deleting a PROJECT is the same kind of boundary case for the opposite reason: sessions live
	// in their own JSON file, so nothing cascades by itself, and a `projectId` pointing at a row
	// that no longer exists becomes a sidebar group with no title — which reads as a rendering bug
	// rather than as deleted data. Clearing the link here is what makes the reference real.
	ipcMain.handle(IPC.dataSoftDelete, async (_e, collection: CollectionName, id: string) => {
		if (collection === 'memories') { return forgetMemory(memLog, store, id).then(r => r.ok); }
		const ok = await store.softDelete(collection, id);
		if (ok && collection === 'projects') { await sessions.clearProjectFor(id); }
		return ok;
	});
	ipcMain.handle(IPC.dataStats, async (): Promise<DataStats> => {
		const [schedules, todos, notes, notebooks, memories, projects] = await Promise.all([
			store.live('schedules'), store.live<Todo>('todos'), store.live('notes'),
			store.live('notebooks'), store.live('memories'), store.live('projects')
		]);
		return {
			schedules: schedules.length,
			todos: todos.length,
			todosOpen: todos.filter(t => t.status !== 'done').length,
			notes: notes.length,
			notebooks: notebooks.length,
			memories: memories.length,
			projects: projects.length
		};
	});
	ipcMain.handle(IPC.dataExport, (): Promise<Dump> => store.exportDump());
	ipcMain.handle(IPC.dataImport, (_e, dump: Dump): Promise<number> => store.mergeDump(dump));

	// ---- app info + phase-2 config (keys stay on disk only, never in renderer state) ----
	const dataDir = path.join(appDir(), 'data');
	const configFile = path.join(appDir(), 'config.json');

	const readConfigFile = (): DesktopConfig => {
		try { return JSON.parse(fs.readFileSync(configFile, 'utf8')); } catch { return {}; }
	};
	/** Capability plugins (phone PluginMarket v1 shape): feature toggles, next-boot effective. */
	const pluginOn = (key: string): boolean => readConfigFile().plugins?.[key] !== false;
	const readMemInject = (): boolean => readConfigFile().memInject !== false;

/**
 * The WORKER slot, for background work that must not compete with the answer.
 *
 * Falls back to the main slot when no worker model is configured, and that is deliberate rather
 * than lazy: a summariser with no worker slot should still work. The cost is that summarisation
 * then shares the main quota, which is exactly what the split exists to avoid — so `mainModel`
 * is used and the behaviour is honest rather than silently absent.
 */
const readWorkerSlot = (): SlotConfig => {
	const c = readConfigFile();
	const base = mainSlotConfig();
	if (c.workerModel && c.workerKey) {
		return { ...base, model: c.workerModel, key: c.workerKey };
	}
	return base;
};

	// Built-in sub-agent personas (phone assistants parity) — seeded once.
	const BUILTIN_ASSISTANTS = [
		{ key: 'general', name: '通用助手', color: '#8ab4f8', slogan: '日常问答与整理', systemPrompt: '', builtin: true },
		{ key: 'coder', name: '编程搭档', color: '#7ddba0', slogan: '代码作业与调试', systemPrompt: '你是一名耐心的编程导师，帮助大一学生完成编程课作业：解释概念时由浅入深，给出的代码要带注释并说明运行方式，遇到报错先定位原因再改。', builtin: true },
		{ key: 'planner', name: '日程规划师', color: '#ffd666', slogan: '时间安排与提醒', systemPrompt: '你是日程规划师：安排任务时考虑优先级与时间冲突，给出的计划要具体到日期与时间段，并主动建议需要创建的日程/待办。', builtin: true }
	];
	const assistantCache = new Map<string, { name: string; systemPrompt: string; temperature?: number }>();
	const refreshAssistants = async (): Promise<void> => {
		try {
			assistantCache.clear();
			for (const a of await store.live<Assistant>('assistants')) {
				assistantCache.set(a.key, a);
			}
		} catch { /* non-fatal */ }
	};
	void (async () => {
		try {
			const existing = await store.live('assistants');
			if (!existing.length) {
				for (const a of BUILTIN_ASSISTANTS) {
					await store.upsert('assistants', { id: '', createdAt: 0, updatedAt: 0, ...a } as never);
				}
			}
			await refreshAssistants();
		} catch { /* non-fatal */ }
	})();
	/** Effective slot: config (UI) first → env → defaults, so model/key switches apply next turn. */
	const readSlot = (): SlotConfig => {
		const c = readConfigFile();
		const base = mainSlotConfig();
		return { ...base, model: c.mainModel || base.model, key: c.mainKey || base.key };
	};
	const readPermMode = (): PermMode => readConfigFile().permMode ?? 'confirm';

	ipcMain.handle(IPC.appInfo, (): AppInfo => ({
		dataDir,
		appVersion: app.getVersion(),
		platform: process.platform
	}));
	ipcMain.handle(IPC.configGet, (): DesktopConfig => {
		try { return JSON.parse(fs.readFileSync(configFile, 'utf8')); } catch { return {}; }
	});
	const writeConfig = (patch: DesktopConfig): void => {
		let cur: DesktopConfig = {};
		try { cur = JSON.parse(fs.readFileSync(configFile, 'utf8')); } catch { /* first run */ }
		fs.mkdirSync(path.dirname(configFile), { recursive: true });
		fs.writeFileSync(configFile, JSON.stringify({ ...cur, ...patch }, null, 1), 'utf8');
	};
	// Single writer. Exported to the closure so the context-toggles handler can reuse it
	// instead of opening a second path to the same file.
	const configSet = (patch: DesktopConfig): void => writeConfig(patch);
	ipcMain.handle(IPC.configSet, (_e, patch: DesktopConfig) => writeConfig(patch));

	ipcMain.handle(IPC.usageSummary, async (): Promise<UsageSummary> => {
		const file = path.join(dataDir, 'tools_audit.jsonl');
		const byTool = new Map<string, ToolUsage>();
		const recent: UsageSummary['recent'] = [];
		let totalCalls = 0;
		try {
			const lines = fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean);
			for (const line of lines) {
				try {
					const e = JSON.parse(line);
					const t = byTool.get(e.tool) ?? { tool: e.tool, ok: 0, fail: 0, ms: 0 };
					e.ok ? t.ok++ : t.fail++;
					t.ms += e.ms ?? 0;
					byTool.set(e.tool, t);
					totalCalls++;
					recent.push({ tool: e.tool, ok: !!e.ok, ms: e.ms ?? 0, at: e.at ?? 0 });
				} catch { /* skip */ }
			}
		} catch { /* no audit yet */ }
		return { totalCalls, byTool: [...byTool.values()].sort((a, b) => (b.ok + b.fail) - (a.ok + a.fail)), recent: recent.slice(-20).reverse() };
	});

	// ---- AI workbench (phase 2): agent lives in main, streams events to renderer ----
	const undo = new UndoStack(store);
	// M19-3: memory tools write through an append-only log, not straight to the collection.
	// Created ONCE and injected below -- a second instance would fold to its own view and
	// silently disagree with this one.
	//
	// It also has to OUTLIVE the process. The page promises 「轨迹仍在，可恢复」, and pc-a
	// demonstrated that without this file a restart deleted every forget event: the row stayed
	// soft-deleted (the collection is what the renderer reads), so nothing LOOKED wrong, while
	// the trail behind the erase was gone exactly when someone came to check it. JSONL append
	// keeps writes O(1) and a torn tail to one lost line rather than the whole log.
	// Read the log and attach its sink in ONE step: the wiring is the part that broke — a log
	// hydrated without a sink reads perfectly and then silently stops being durable on the next
	// write — so it lives behind the function the tests actually call, not as steps index.ts
	// assembles by hand where nothing would notice a missing line.
	const memLog = openMemoryLog(path.join(dataDir, 'memory_events.jsonl'));
	// Seed from what the collection already holds, so existing memories gain an audit trail
	// instead of appearing with no history on their next write.
	void (async () => {
		try {
			const rows = await store.live<Memory>('memories');
			const seeded = seedMissing(memLog, rows);
			if (seeded) { console.log('[memory] seeded', seeded, 'events from the collection'); }
		} catch (e) {
			console.error('[memory] seed failed --', (e as Error)?.message ?? e);
		}
	})();

	// ---- M19: the memory page's data face (pc-a 09:52 ①) ----
	//
	// The erase itself lives in tools.ts, next to `projectMemories` and shared with the
	// `memory_forget` danger tool. The page's delete never had one: it arrived through the
	// generic dataSoftDelete below, stopped at `store.softDelete`, and never appended the event
	// the projection reads — which is why it kept resurrecting the row it had just removed while
	// the tool's erase held. One erase path now, three callers: this channel, that tool, and
	// dataSoftDelete.
	ipcMain.handle(IPC.memoryForget, (_e, key: string) => forgetMemory(memLog, store, key));
	ipcMain.handle(IPC.memoryHistory, (_e, key: string) => memLog.history(String(key ?? '')));
	// The windowing lives in `MemoryLog.recent` rather than here: an inline slice in an
	// ipcMain handler is unreachable from the test suite, and this is exactly the arithmetic
	// (slice(-0) returning everything, an uncapped count) that needs a test to hold it still.
	ipcMain.handle(IPC.memoryEvents, (_e, n?: number) => memLog.recent(n));

	// ---- M19-4 Skills management (see SkillsApi for why read/write deal in raw file text) ----
	//
	// The directory is resolved ONCE here. It used to be written out twice and the copies had
	// already drifted — workflows read `<skillsDir>/workflows` while skills read `<skillsDir>`
	// itself — and now three readers depend on it (the prompt builder, skillsRead, skillsWrite).
	// That is the same shape as the workflowList/workflowRun disagreement, so it is one function.
	// Existing files are deliberately NOT moved to tidy the layout: that is a migration with its
	// own risks, and the channels do not need it in order to agree.
	const skillsDir = (): string => readConfigFile().skillsDir || path.join(appDir(), 'skills');

	/** Every built-in, addressable by BOTH its frontmatter name and its filename stem. */
	const builtinByKey = (): Map<string, string> => {
		const m = new Map<string, string>();
		for (const [fileKey, text] of Object.entries(builtinSkillFiles())) {
			// toSkill is what decides the key the loader will use, so asking it here is what keeps
			// this lookup from drifting from the loader again.
			const k = toSkill(fileKey, text, `builtin://${fileKey}.md`, 'builtin').key;
			m.set(k, text);
			m.set(fileKey, text);
		}
		return m;
	};

	const skillInfoOf = (s: { key: string; name: string; description: string; trigger: string; priority: number; enabled: boolean; keywords: string[]; body: string; source: string; overridden: boolean; problem?: string }) => ({
		key: s.key, name: s.name, description: s.description, trigger: s.trigger,
		priority: s.priority, enabled: s.enabled, keywords: s.keywords,
		bodyChars: s.body.length,
		origin: (s.source.startsWith('builtin://') ? 'builtin' : 'user') as 'builtin' | 'user',
		overridden: s.overridden,
		editable: !s.source.startsWith('builtin://'),
		problems: s.problem ? [s.problem] : [],
	});

	ipcMain.handle(IPC.skillsList, async (): Promise<SkillListResult> => {
		const dir = skillsDir();
		try {
			const { skills, problems } = await loadSkills({ userDir: dir });
			return { dir, problems, skills: skills.map(skillInfoOf) };
		} catch (e) {
			// A skills page that renders an error beats a page that renders nothing: the built-ins
			// still exist and the user needs to be able to read them to find out what is wrong.
			return { dir, skills: [], problems: [{ file: dir, reason: `加载失败：${(e as Error)?.message ?? e}` }] };
		}
	});

	ipcMain.handle(IPC.skillsRead, async (_e, key: string): Promise<SkillFile> => {
		const dir = skillsDir();
		const k = sanitizeKey(key);
		const empty = (origin: 'builtin' | 'user'): SkillFile => ({
			key: k || String(key ?? ''), text: '', exists: false, origin, editable: origin === 'user',
			preview: { name: '', description: '', keywords: [], bodyChars: 0 }, problems: [],
		});
		if (!k) { return { ...empty('user'), problems: ['技能标识无效'] }; }
		try {
			// A user file wins over a built-in, because that is the precedence the loader applies.
			const file = resolveUserSkillFile(await listUserSkillFiles(dir), k);
			if (file) {
				const text = await fs.promises.readFile(file, 'utf8');
				const s = toSkill(k, text, file, 'user');
				return {
					key: s.key || k, text, exists: true, origin: 'user', editable: true,
					preview: { name: s.name, description: s.description, keywords: s.keywords, bodyChars: s.body.length },
					problems: s.problem ? [s.problem] : [],
				};
			}
			const text = builtinByKey().get(k);
			if (text !== undefined) {
				const s = toSkill(k, text, `builtin://${k}.md`, 'builtin');
				return {
					key: s.key || k, text, exists: true, origin: 'builtin',
					// Read-only, and said so rather than letting the page offer an edit box that
					// would be undone by the next app update.
					editable: false,
					preview: { name: s.name, description: s.description, keywords: s.keywords, bodyChars: s.body.length },
					problems: s.problem ? [s.problem] : [],
				};
			}
			// Not an error: the page opens an empty editor for a new skill.
			return empty('user');
		} catch (e) {
			return { ...empty('user'), problems: [`读取失败：${(e as Error)?.message ?? e}`] };
		}
	});

	ipcMain.handle(IPC.skillsWrite, async (_e, key: string, text: string): Promise<SkillWriteResult> => {
		const out = await writeSkillText(skillsDir(), key, text);
		if (!out.ok) { return { ok: false, error: out.error }; }
		// `replaced` is all the page needs to say the right thing: a built-in's key lands in the
		// user directory as an override, which is the documented way to customise one, but it is
		// NOT the same event as "saved my own file" and must not be reported as if it were.
		return {
			ok: true, key: out.key, file: out.file, replaced: out.replaced, problems: out.problems,
		};
	});
	// Settling is a two-stage pipeline: extract (worker slot, may fail) then apply (local, may
	// reject individual proposals). Both stages report honestly; neither can lose the turn.
	const extractor = makeMemorySettler(() => readWorkerSlot());
	// M20-1: journals live here, but the role resolver needs the AgentManager, which is built
	// further down once ai.db is open -- hence the lazy accessor below.
	const journals = new JournalStore();
	const builtinWorkflows = () => [demoWorkflow()];

	/** Where user-authored workflows live: `<dir>/*.json`, declarative JSON per M20-1. */
	const workflowDir = (): string => {
		const custom = readConfigFile().skillsDir;
		return custom ? path.join(custom, 'workflows') : path.join(appDir(), 'workflows');
	};

	/**
	 * Workflows assembled from a proposal (M20-3), keyed by id.
	 *
	 * Deliberately in memory only: an assembly is a proposal, not a commitment, and writing every
	 * throwaway "review this PR" into the user's workflow directory would mean the directory fills
	 * with things they never asked to keep. The consequence is stated rather than hidden — one of
	 * these disappears on restart, so an automation must never be configured to depend on it.
	 */
	const assembledWorkflows = new Map<string, Workflow>();

	/**
	 * EVERY workflow the app can run, and the ONE lookup workflowList, workflowRun and the
	 * automation sweep all share.
	 *
	 * This used to be two independent resolvers that disagreed: `workflowList` walked the skills
	 * loader and JSON.parsed markdown bodies (so entries came back with no steps, and the demo was
	 * absent from the list entirely) while `workflowRun` only looked in `builtinWorkflows()` — so
	 * nearly everything the UI showed was unrunnable, and it took a click to find out. The whole
	 * invariant is: if you can see it, you can run it. Hence one function, not two.
	 *
	 * The merge/parse/report rules live in ai/workflow-store.ts (fs layer; workflow.ts stays a pure
	 * kernel so tests can compile it standalone). Synchronous because the automation sweep's
	 * `resolve` must be — an async list would mean the scheduler cannot resolve inside its tick.
	 */
	const allWorkflows = (): WorkflowCatalog => buildWorkflowCatalog({
		builtin: builtinWorkflows(),
		userDir: workflowDir(),
		assembled: assembledWorkflows.values(),
	});

	/** The shared lookup itself. */
	const resolveWorkflow = (id: string): Workflow | undefined =>
		allWorkflows().list.find(w => w.id === id);

	/**
	 * Resolve a workflow role name to an agent id, or throw naming the role it could not play.
	 *
	 * The matching ladder — and the ORDER that matters (literal match before alias) — lives in
	 * `matchRole` in ai/workflow.ts, so there is no second copy of it here to drift from the one
	 * the tests actually exercise.
	 */
	const resolveRole = (role: string): string => {
		const hit = matchRole(am().listAgents(), role);
		if (!hit) { throw new Error(`没有名为「${role}」的 agent（也没有对应的预置角色映射）`); }
		return hit.id;
	};
	const registry = registerPlanabilityTools(store, undo, {
		memLog,
		workspaceRoot: () => readConfigFile().workspaceRoot || appDir(),
		settle: async () => {
			const r = await extractor(getAgent().transcriptForMemory());
			if (!r.ok) { return { ok: false, reason: r.reason, applied: [], rejected: [] }; }
			if (!r.proposals.length) {
				return { ok: true, applied: [], rejected: [], note: '（本轮没有值得沉淀的新记忆）' };
			}
			const res = applyProposals(memLog, r.proposals, {
				workspace: readConfigFile().workspaceRoot || appDir(),
				source: 'auto',
			});
			await projectMemories(memLog, store);
			return { ok: true, applied: res.applied, rejected: res.rejected };
		},
	}, // A switch now removes the tool outright rather than letting the model discover it and
				// fail — that is the difference between "you turned this off" and "this tool is broken"
				// from the model's point of view. null (no plugins config at all) leaves everything.
				enabledDomains(readConfigFile().plugins), {
			// A 类（把安卓端已有能力开放给模型）：PC 的 UI 早就在管这些，工具只是同一个模块的另一个入口。
			// inject 而不是 import，因为 index.ts 拥有这些实例（人格缓存、MCP hub）—— 两个拥有者就是
			// 两个会互相不同意的缓存。
			onAssistantsChanged: () => refreshAssistants(),
			listSkills: async () => {
				const dir = readConfigFile().skillsDir || appDir();
				const set = await loadSkills({ userDir: dir });
				return set.skills.map(k => ({ key: k.key, name: k.name, description: k.description, origin: k.source }));
			},
			mcp: {
				// list() is async on the hub, so the tool awaits it — ExtendToolDeps.mcp.list is a Promise.
				list: async () => await mcp.list(),
				setEnabled: (id, on) => void mcp.setEnabled(id, on),
			},
	});
	// M17 待办表 (P2): the DB is opened here (not in the kernel block below) because the todo
	// tools must be registered onto the same registry, and the registry is built at this line.
	const aiDb = new AiDb(path.join(dataDir, 'ai'));
	let agentManager: AgentManager | null = null;
	try {
		aiDb.migrate();
	} catch (e) {
		console.error('[M17] SQLite 迁移失败，多代理内核已禁用：', e);
	}
	const todoStore = new TodoStore(aiDb);
	registry.setDomain('todo');
	registerTodoTools(registry, todoStore);
	// M17 `task` tool + typed-parts store. The registry is built here but AgentManager only
	// exists further down, so the tool closes over a holder that we fill in after boot.
	const agentHost: { host: SubAgentHost | null } = { host: null };
	registry.setDomain('agent');
	registerAgentTools(registry, agentHost);
	const typedParts = new TypedPartStore(aiDb);
	// M18 file domain (read+write). The root is re-read per call so changing it in settings
	// takes effect on the next tool call; an unset root leaves the domain DISABLED rather than
	// defaulting to somewhere broad.
	const fileUndo = new FileUndoStack();
	// ---- P0-c（双端项目同步）：文件工具按会话项目切根 ----
	// 会话绑了项目 → project-root 解析链（dump 的 sync 字段 → PC 本地绑定 → workspaceRoot/<名>）；
	// 解析不出 / 任何一步异常 → 整个 workspaceRoot（旧行为）。同步层永远不弄停文件工具。
	const sessionWorkspace = async (sessionId?: string): Promise<Workspace | null> => {
		if (sessionId) {
			try {
				const s = await sessions.get(sessionId);
				if (s?.projectId) {
					const r = await resolveProjectRoot(s.projectId, {
						loadProjects: () => store.load<Project>('projects'),
						getSyncFolders: async () => {
							const st = await syncthing.summary();
							return st.folders.map(f => ({ id: f.id, path: f.path }));
						},
						workspaceRoot: () => readConfigFile().workspaceRoot || '',
						readBinding: pid => store.kvGet(`project.dirPath.${pid}`),
					});
					if (r) { return new Workspace(r.root); }
				}
			} catch { /* 解析失败=回退旧行为 */ }
		}
		const root = readConfigFile().workspaceRoot;
		return root ? new Workspace(root) : null;
	};
	// ---- P0-d（双端项目同步）：PC 会话镜像器 ----
	// 会话绑了项目且解析出项目目录 → 全量重写 <项目根>/项目数据/当前会话/<标题>__pc<id>/对话.md。
	// 只镜像 user/assistant；手机端 Syncthing 收到后可读（跨端续聊 P3 按 mtime 取最近）。
	// best-effort：镜像失败绝不影响回合——所有异常吞掉，连日志都不拖慢主链路。
	const mirrorSession = (sessionId: string): void => {
		void (async () => {
			try {
				const s = await sessions.get(sessionId);
				if (!s || !s.projectId || !s.history.length) { return; }
				const r = await resolveProjectRoot(s.projectId, {
					loadProjects: () => store.load<Project>('projects'),
					getSyncFolders: async () => {
						const st = await syncthing.summary();
						return st.folders.map(f => ({ id: f.id, path: f.path }));
					},
					workspaceRoot: () => readConfigFile().workspaceRoot || '',
					readBinding: pid => store.kvGet(`project.dirPath.${pid}`),
				});
				if (!r) { return; }
				await mirrorTo(r.root, sessionDirName(s.title || '会话', sessionId),
					mirrorMarkdown(s.title, s.history), sessionId);
			} catch { /* best-effort */ }
		})();
	};
	registry.setDomain('file');
	registerFileTools(registry, sessionWorkspace, {
		// File snapshots ride the existing UndoStack so 「撤销」 restores file contents too.
		pushFile: (rel, previous) => { fileUndo.pushFile(rel, previous); },
	});
	// ---- B3 文件库（域 file）：library/ 是工作区的子目录，与 exports/ 平级。
	// 共用同一个 fileUndo，写入因此和 file_write 一样可撤销。
	registerLibraryTools(registry, {
		getWorkspace: sessionWorkspace,
		undo: { pushFile: (rel, previous) => { fileUndo.pushFile(rel, previous); } },
	});
	// ---- B2 文件/办公域批 1（2026-11-05）：file_export / file_export_document / file_build_deck
	// （域 office）+ file_read_text / file_read_image（域 file）。渲染库 docx/exceljs/pptxgenjs
	// 是主进程依赖，不走 office-bridge（那是给手机用的本机 HTTP 服务）。
	registerFileOfficeTools(registry, sessionWorkspace);
	// ---- B2 文件/办公域批 2（2026-11-07）：file_edit_document / file_edit_sheet / file_edit_deck
	// 三段管线「读回 → 结构化编辑 → 重渲染」，渲染复用批 1；三个都是 confirm（改用户已有文件）。
	registerFileEditTools(registry, sessionWorkspace);
	// file_read_document（B2 收尾，2026-11-08）：docx/xlsx/pptx -> 带编号文本，编号口径与
	// file_edit_document 的 index / file_edit_deck 的 pageNo 一致（改稿要靠它定位）。
	registerFileReadDocument(registry, sessionWorkspace);
	// M18 web + shell domains. web_* are risk=danger (always ask); shell_run classifies into
	// the three-layer net inside the tool, where the hard-deny list actually binds.
	registry.setDomain('web');
	registerWebTools(registry);
	registry.setDomain('shell');
	registerShellTools(registry, () => readConfigFile().workspaceRoot || appDir());

	// 计划域第二批 + 生活数据（phone PlanTools/LifeTools 对齐，2026-10-31 assign → 2026-11-05）。
	// 域由模块内部 set（todo/schedule/focus/system/note），与其它组走同一道 applyDomainSwitches。
	registerPlanTools(registry, store, path.join(dataDir, 'checkins.json'));
	registerLifeTools(registry, store, dataDir);

	// One filter, applied at startup and again whenever a switch is flipped from inside a turn.
	// Two copies of this logic is two places to forget, so it is a named function.
	const applyDomainSwitches = (): void => {
		registry.applyDomainFilter(enabledDomains(readConfigFile().plugins));
	};

	// ---- automation 六件套（域 extend）----
	// N 节数出的最后一个缺口：6 个 IPC handler、0 个工具。
	// 语义逐字镜像手机端 ToolExecImpls.kt 的 automation_create/edit ——
	// 包括那条"空白串算没提这个字段、数字布尔只要给了就算"，不然就是第 12 处 D 类漂移。
	registerAutomationTools(registry, {
		list: () => automation.list(),
		create: (input) => automation.create(input as any),
		update: (id, patch) => automation.update(id, patch),
		remove: (id) => automation.remove(id),
		setEnabled: (id, on) => automation.setEnabled(id, on),
		recentLog: (id) => automation.recentLog(id),
		taskHistory: (id, n) => automation.taskHistory(id, n),
	});
	// ---- skill_read / skill_write（域 extend）----
	// skill_list 直给名字和简介，这两个给它内容。
	// skill_write 的爆炸半径要在描述里说清：用户技能文件会注入之后【每一轮】的系统提示，
	// 那不是"存个笔记"。
	registerSkillTools(registry, { dir: () => skillsDir() });
	// ---- handoff 工具（域 note）：四段固定文档，另一个会话可拿它冷启动 ----
	// 域跟它的实际内容走：交接是模型写的文档，不是子代理动作。放进 agent 会让
	// 那条开关的文案（不能分派子任务）对它正在关掉的东西说谎。
	registerHandoffTools(registry, {
		dir: () => handoffDir(),
		sessionTitle: async () => {
			// sessions.active() returns the session id, not the row. Resolving the title through
			// the same list the IPC path uses keeps the stamped source identical on both surfaces.
			const id = sessions.active();
			if (!id) { return undefined; }
			const row = (await sessions.list()).find((x: any) => x.id === id);
			return row ? String((row as any).title ?? '') || undefined : undefined;
		},
	});
	// ---- checkpoint 三件套（域 system，与 undo_last_write 同域）----
	// undo_last_write 也从 memory 挪到了 system：memory 的 offCost 只说关掉记忆，
	// 而撤销挂在它下面，关 memory 会连带关掉撤销 —— 那句话因此是假的。
	registerCheckpointTools(registry, {
		push: (label) => getAgent().pushCheckpoint(label),
		rewindTo: (seq) => getAgent().rewindTo(seq),
		list: () => getAgent().checkpointList(),
	});

	// ---- list_query（域 system）：与 chat:listQuery 同一条查询路 ----
	// 同一个 normalizeListQuery 做校验，所以模型问的和卡片显示的不会是两套东西；
	// 只读 —— runListQuery 只调 store.live，没有写路径。
	registerListQueryTool(registry, {
		run: (q) => runListQuery(store, q),
	});	// ---- team_assemble（域 agent）----
	// 与 IPC 的 teamAssemble 共用 assembleTeam 与 assembledWorkflows：
	// UI 点一下注册一个，模型调一次也注册一个，所以加了道上限 ——
	// 那张表是无界的，而模型可能在循环里调它。
	registerTeamTool(registry, {
		assemble: (requirement, prefer) => {
			const r = assembleTeam({
				requirement: String(requirement ?? ''),
				agents: am().listAgents().map(a => ({ id: a.id, name: a.name, tools: a.tools })),
				preferTemplates: Array.isArray(prefer) ? prefer.map(String) : undefined,
			});
			if (r.ok) { assembledWorkflows.set(r.proposal.workflow.id, r.proposal.workflow); }
			return r;
		},
		countRegistered: () => assembledWorkflows.size,
	});
	// ---- workflow 工具（域 agent）：链式工作流，B4 刻意没走的那一半 ----
	// 与 IPC 的 workflowRun 共用同一个解析器（whatever the UI showed is exactly what can run），
	// 但等待语义不同：IPC 是 fire-and-forget（返回 runId），工具必须等结果 ——
	// 模型回合在工具返回时就结束，一个它等不到的 runId 是个死胡同。
	registerWorkflowTools(registry, {
		listWorkflows: () => {
			const { list, problems } = allWorkflows();
			return list.map(w => ({
				id: w.id, name: w.name, task: w.task,
				steps: (w.steps ?? []).map(st => ({ id: st.id, role: st.role, prompt: st.prompt })),
				problems: problems.get(w.id),
			}));
		},
		resolveWorkflow: (id) => resolveWorkflow(String(id ?? '')),
		runWorkflow: async (wf, { waitMs }) => {
			const runId = journals.nextRunId();
			// Same runner the IPC handler builds. Two copies of this is two places to drift.
			const runner = agentStepRunner({
				start: (role, prompt) => am().submitPrimary(resolveRole(role), sessions.active(), prompt).id,
				peek: (id) => {
					const r = am().getRun(id);
					return r ? { status: r.status, result: r.result, error: r.error } : undefined;
				},
				resolveRole,
			});
			const done = runWorkflow(wf, runner, { runId })
				.then(r => { journals.add(r.journal); return r; });
			// The run is NOT cancelled when the wait expires — it keeps going and lands in the journal.
			// This second handler exists so a rejection after the race is settled by the timer
			// does not become an unhandled rejection that takes the main process down.
			void done.catch(() => { /* reported by the timeout path below if it still matters */ });
			let timer: ReturnType<typeof setTimeout> | undefined;
			const expired = new Promise<null>(res => {
				timer = setTimeout(() => res(null), waitMs);
			});
			const outcome = await Promise.race([done, expired]);
			if (timer) { clearTimeout(timer); }
			return { outcome: outcome ?? undefined, runId };
		},
		listRuns: (limit) => journals.list(limit).map(j => ({
			runId: j.runId, workflowId: j.workflowId, workflowName: j.workflowName, task: j.task,
			status: j.status, failedAt: j.failedAt,
			steps: j.entries.map(e => ({ stepId: e.stepId, role: e.role, status: e.status })),
			startedAt: j.startedAt, endedAt: j.endedAt,
		})),
		replay: (runId) => {
			const j = journals.get(runId);
			return j ? { ok: true, text: replayJournal(j).lines } : { ok: false };
		},
	});
	// ---- B4 专家/深规划（域 agent）----
	// WORKER slot, not main: these fan out to several model calls, and running them on the
	// main slot would make the user wait longer to get LESS context — the exact reason the
	// worker split exists. Registered before the filter below so the agent switch governs them.
	registerExpertTools(registry, {
		call: (system, user) => callNonStream(readWorkerSlot(), system, user),
	});
	// ---- B5 设置类工具（capability_toggle / model_switch，域 extend）----
	// configSet is the single writer of the config file (see above); writing it directly would
	// open a second path to the same JSON and the two would race on every flip.
	registerSettingsTools(registry, {
		readConfig: () => readConfigFile(),
		writeConfig: (patch) => configSet(patch as DesktopConfig),
		refreshDomains: applyDomainSwitches,
	});
	// ---- B1 ICS 导出（域 ics）：排在这里是为了落在下面那次 applyDomainFilter 之前 ——
	// 注册在过滤之后的工具不受开关管辖，开关就成了摆设。
	// 域由 registerIcsTools 自己设，不在这里设：同一个域写两处，迟早有人删掉对的那一处，
	// 然后工具静悄悄掉进 alwaysOn 的 system 域，用户再也关不掉它。
	registerIcsTools(registry, {
		getWorkspace: sessionWorkspace,
		getSchedule: async (id) => (await store.live<any>('schedules')).find((x: any) => x.id === id) ?? null,
	});
	// ---- M21 Computer Use: read-only blade ----
	// The bitmap is written to disk and never handed to the model (plan: 双槽 vision 待探测，
	// 先按不支持设计) — which is also what makes "截图默认不出网" structural rather than a
	// promise: there is nothing screen-shaped in the request to the provider.
	const captureStore = new CaptureStore(path.join(appDir(), 'cu'));
	// Load once at startup: an optional feature that cannot load must degrade to a clear
	// message, never take the main process down with it.
	const cuNative = loadCuNative(process.resourcesPath);
	const cuNativeErr = cuNative ? null : cuNativeError();
	if (cuNative) {
		console.log('[cu] native module loaded, abi', cuNative.abiVersion());
	} else {
		console.warn('[cu] native module unavailable —', cuNativeErr);
	}
	const selfTitle = () => mainWindow?.getTitle() ?? '';
	const cuWindows = (): WindowInfo[] => BrowserWindow.getAllWindows().map(w => ({
		title: w.getTitle(),
		bounds: w.getBounds(),
		focused: w.isFocused(),
		isSelf: w === mainWindow,
	}));
	/**
	 * One desktop-wide capture.
	 *
	 * Two coordinate spaces meet here and the whole CU path assumes PHYSICAL: `disp.bounds` and
	 * `disp.size` are DIPs, the PNG is physical pixels, and `ScreenGeometry.bounds` is declared
	 * (and validated against) physical. Publishing Electron's DIP rect straight into `bounds` —
	 * which is what happened — means the type and the producer disagree: `cu_geometry` reported a
	 * 1600x1000 "physical" screen on a 3200x2000 desktop, so a click at (1849,1538) was perfectly
	 * on-screen and still denied as out of range. One multiplication here removes the ambiguity
	 * for every consumer and leaves `dip` meaning what it says.
	 */
	const cuCapture = async (): Promise<{ ok: boolean; png?: Buffer; geometry?: ScreenGeometry; reason?: string }> => {
		try {
			const disp = screen.getPrimaryDisplay();
			const s = disp.scaleFactor || 1;
			const png = await desktopCapturer.getSources({
				types: ['screen'],
				thumbnailSize: {
					width: Math.min(Math.round(disp.size.width * s), CU_LIMITS.maxEdge),
					height: Math.min(Math.round(disp.size.height * s), CU_LIMITS.maxEdge),
				},
			}).then(srcs => srcs[0]?.thumbnail.toPNG());
			if (!png) { return { ok: false, reason: 'desktopCapturer 未返回屏幕源。' }; }
			return {
				ok: true, png,
				geometry: {
					// PHYSICAL — this is what SendInput takes and what cu_click validates against.
					// Every component comes from `bounds` (not `size`) so that
					// `bounds === dip * scaleFactor` holds by construction rather than by luck.
					bounds: {
						x: Math.round(disp.bounds.x * s),
						y: Math.round(disp.bounds.y * s),
						width: Math.round(disp.bounds.width * s),
						height: Math.round(disp.bounds.height * s),
					},
					scaleFactor: s,
					dip: { width: disp.bounds.width, height: disp.bounds.height },
				},
			};
		} catch (e) {
			return { ok: false, reason: String((e as Error)?.message ?? e) };
		}
	};
	registry.setDomain('cu');
	registerCuTools(registry, {
		capture: cuCapture,
		listWindows: cuWindows,
		systemForeground: cuNative ? () => { try { return cuNative.foregroundTitle(); } catch { return null; } } : undefined,
		store: captureStore,
		injectMissing: cuNativeErr ?? undefined,
		// Inject via the napi-rs module when it is built; absent otherwise and the tools say why.
		inject: cuNative ? {
			click: async (p, b) => { cuNative.injectClick(p.x, p.y, b); },
			scroll: async (p, n) => { cuNative.injectScroll(p.x, p.y, n); },
			key: async (k) => { cuNative.injectKey(k); },
			// Clipboard path per plan: copy -> Ctrl+V -> restore. Two injected keystrokes
			// instead of one per character, and it survives IME/odd keyboard layouts.
			pasteText: async (text) => {
				const prev = clipboard.readText();
				try {
					clipboard.writeText(text);
					cuNative.injectKey('ctrl+v');
				} finally {
					clipboard.writeText(prev);
				}
			},
		} : undefined,
		// screenText is intentionally left unset: OCR access needs a separate decision (WinRT
		// binding), and it is the only remaining M21 read that has no path today.
	});

	// Every group has now been registered with its domain, so one filter over all of them is
	// enough. Doing it here rather than inside registerPlanabilityTools is what makes the
	// sub-module groups (todo/agent/file/web/shell/cu) gated by exactly the same mechanism as
	// the core ones — two filters would be two places to forget.
	// One filter, applied at startup and again whenever a switch is flipped from inside a turn.
	// Two copies of this logic is two places to forget, so it is a named function — defined
	// further up (before the tools that need to call it) and applied for the first time here.
	applyDomainSwitches();

	/**
	 * M18: the approval card shows a unified diff for file_write rather than the raw content.
	 * Synchronous on purpose — it runs on the approval path, where a round trip would make the
	 * card feel laggy, and where a thrown error must not break the turn.
	 */
	const approvalArgs = (toolName: string, input: Record<string, unknown>, argsJson: string): string => {
		if (toolName !== 'file_write') { return argsJson; }
		const root = readConfigFile().workspaceRoot;
		const rel = String(input?.path ?? '');
		if (!root || !rel) { return argsJson; }
		try {
			const w = new Workspace(root);
			const abs = path.resolve(w.root, rel.replace(/^[/\\]+/, ''));
			// Reuse the same containment rule before touching the disk for the preview.
			if (abs !== w.root && !abs.startsWith(w.root + path.sep)) { return `将写入 ${rel}（路径越界，已拒绝）`; }
			let prev: string | null = null;
			try { prev = fs.readFileSync(abs, 'utf8'); } catch { prev = null; }
			const st = diffStat(prev, String(input?.content ?? ''));
			return `将写入 ${rel}（${prev === null ? '新建文件' : '覆盖已有文件'}，+${st.added} / -${st.removed} 行）：\n`
				+ renderDiff(prev, String(input?.content ?? ''));
		} catch {
			return argsJson; // never let a preview failure break the approval flow
		}
	};
	const sessions = new SessionStore(dataDir);
	// Card store (stage 2): cards captured from settled rows, listed/pinned/removed via
	// chat:cardList/cardPin/cardRemove. Lives next to sessions because it is the same kind
	// of thing — a JSON-backed collection under dataDir with its own load-once discipline.
	const cardStore = new CardStore(dataDir);
	const mcp = new McpHub(dataDir);
	// Merge MCP tools into the registry at boot (enabled servers only; failures are logged).
	if (pluginOn('mcpTools')) {
		void mcp.connectEnabled(registry).catch(e => console.error('[mcp] boot connect failed', String(e)));
	}

	ipcMain.handle(IPC.mcpList, () => mcp.list());
	ipcMain.handle(IPC.mcpAdd, (_e, name: string, url: string) => mcp.add(name, url));
	ipcMain.handle(IPC.mcpRemove, (_e, id: string) => mcp.remove(id));
	ipcMain.handle(IPC.mcpSetEnabled, (_e, id: string, enabled: boolean) => mcp.setEnabled(id, enabled));
	ipcMain.handle(IPC.mcpTest, (_e, url: string): Promise<McpTestResult> => mcp.test(url));

	// ---- focus sessions (phone 专注 parity, PC timer) ----
	const focusFile = path.join(dataDir, 'focus.json');
	ipcMain.handle(IPC.focusLog, (_e, seconds: number) => {
		try {
			const arr: { at: number; seconds: number }[] = JSON.parse(fs.readFileSync(focusFile, 'utf8'));
			arr.push({ at: Date.now(), seconds: Math.max(1, Math.round(Number(seconds) || 0)) });
			fs.mkdirSync(dataDir, { recursive: true });
			fs.writeFileSync(focusFile, JSON.stringify(arr.slice(-500)), 'utf8');
		} catch { /* first run */ }
	});
	ipcMain.handle(IPC.focusToday, (): number => {
		try {
			const arr: { at: number; seconds: number }[] = JSON.parse(fs.readFileSync(focusFile, 'utf8'));
			const dayStart = new Date(); dayStart.setHours(0, 0, 0, 0);
			return arr.filter(x => x.at >= dayStart.getTime()).reduce((n, x) => n + x.seconds, 0);
		} catch { return 0; }
	});
	// 近 7 天逐日分钟（手机专注页右板「近 7 天」柱图同源 focus.json， oldest first；label=M/D 供我的页图）
	ipcMain.handle(IPC.focusWeek, (): { day: string; label: string; minutes: number }[] => {
		const out: { day: string; label: string; minutes: number }[] = [];
		const arr: { at: number; seconds: number }[] = (() => {
			try { return JSON.parse(fs.readFileSync(focusFile, 'utf8')); } catch { return []; }
		})();
		for (let i = 6; i >= 0; i--) {
			const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - i);
			const next = new Date(d); next.setDate(d.getDate() + 1);
			const seconds = arr.filter(x => x.at >= d.getTime() && x.at < next.getTime()).reduce((n, x) => n + x.seconds, 0);
			out.push({ day: String(d.getDate()), label: `${d.getMonth() + 1}/${d.getDate()}`, minutes: Math.round(seconds / 60) });
		}
		return out;
	});

	// ---- focus floating capsule (phone FloatBall parity): always-on-top mini window ----
	const CAPSULE_W = 180;
	const CAPSULE_H = 64;
	// Module-level mirror so the F0 keep-alive policy (which lives outside this closure) can
	// see the capsule. capsuleWin itself is block-scoped inside whenReady.
	let capsuleWin: BrowserWindow | null = null;
	let capsulePosTimer: NodeJS.Timeout | null = null;
	capsuleWinRef = () => capsuleWin;
	const CAPSULE_HTML = `<!doctype html><html><head><meta charset="utf-8"><style>
		html,body{margin:0;height:100%;background:transparent;overflow:hidden;-webkit-user-select:none;user-select:none}
		.pill{width:${CAPSULE_W}px;height:${CAPSULE_H}px;display:flex;align-items:center;
			background:rgba(40,44,62,.94);border-radius:${CAPSULE_H / 2}px;
			box-shadow:0 4px 14px rgba(0,0,0,.35);-webkit-app-region:drag}
		.island{-webkit-app-region:no-drag;display:flex;align-items:center;gap:9px;flex:1;height:100%;
			padding:0 4px 0 11px;cursor:pointer;border:none;background:none;color:inherit}
		.grip{-webkit-app-region:drag;width:16px;height:100%}
		svg{flex:none}
		.main{font:700 20px/1.1 "Segoe UI Variable","Segoe UI","Microsoft YaHei UI",sans-serif;color:#fff;letter-spacing:.5px}
		.sub{font:500 11.5px/1.2 "Segoe UI Variable","Segoe UI","Microsoft YaHei UI",sans-serif;color:rgba(255,255,255,.75);margin-top:3px}
		.island:active{transform:scale(.98)}
	</style></head><body>
	<div class="pill">
		<button class="island" id="island">
			<svg width="40" height="40" viewBox="0 0 40 40">
				<circle id="track" cx="20" cy="20" r="17.5" fill="none" stroke="rgba(255,138,101,.25)" stroke-width="4.5"/>
				<circle id="ring" cx="20" cy="20" r="17.5" fill="none" stroke="#FF8A65" stroke-width="4.5"
					stroke-linecap="round" stroke-dasharray="110" stroke-dashoffset="0"
					transform="rotate(-90 20 20)"/>
				<circle id="dot" cx="20" cy="20" r="4" fill="#FF8A65" style="display:none"/>
			</svg>
			<div style="text-align:left">
				<div class="main" id="main">番茄钟</div>
				<div class="sub" id="sub">点按开始</div>
			</div>
		</button>
		<div class="grip"></div>
	</div>
	<script>
		const C = 2 * Math.PI * 17.5;
		const fmt = s => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
		let st = { left: 0, total: 0, running: false, round: 1 };
		function render() {
			const idle = !st.running && st.left >= st.total;
			const paused = !st.running && st.left < st.total;
			const accent = paused ? '#96969E' : '#FF8A65';
			document.getElementById('main').textContent = idle ? '番茄钟' : fmt(Math.max(0, st.left));
			document.getElementById('sub').textContent = st.running
				? ('专注 · 第' + st.round + '轮')
				: paused ? '已暂停 · 点按继续' : '点按开始';
			const ring = document.getElementById('ring');
			const dot = document.getElementById('dot');
			const track = document.getElementById('track');
			ring.setAttribute('stroke', accent);
			track.setAttribute('stroke', paused ? 'rgba(150,150,158,.25)' : 'rgba(255,138,101,.25)');
			dot.setAttribute('fill', accent);
			if (idle) { ring.style.display = 'none'; track.style.display = 'none'; dot.style.display = ''; }
			else {
				ring.style.display = ''; track.style.display = ''; dot.style.display = 'none';
				const frac = st.total > 0 ? Math.max(0, Math.min(1, st.left / st.total)) : 0;
				ring.setAttribute('stroke-dashoffset', String(C * (1 - frac)));
			}
		}
		window.planability.onCapsuleState(s => { st = s; render(); });
		render();
		// click = pause/resume, long-press(600ms) = end (phone FloatBall parity)
		let t0 = 0, longFired = false, lp = null;
		const island = document.getElementById('island');
		island.addEventListener('mousedown', e => {
			if (e.button !== 0) { return; }
			t0 = Date.now(); longFired = false;
			lp = setTimeout(() => { longFired = true; window.planability.capsuleAction('end'); }, 600);
		});
		const up = () => {
			if (lp) { clearTimeout(lp); lp = null; }
			if (!longFired && Date.now() - t0 < 600) { window.planability.capsuleAction('toggle'); }
		};
		island.addEventListener('mouseup', up);
		island.addEventListener('mouseleave', () => { if (lp) { clearTimeout(lp); lp = null; } });
	</script></body></html>`;

	ipcMain.handle(IPC.capsuleShow, (_e, state: import('../../shared/ipc').CapsuleState) => {
		if (!capsuleWin || capsuleWin.isDestroyed()) {
			const wa = screen.getPrimaryDisplay().workArea;
			const saved = readConfigFile().capsulePos;
			let x = wa.x + wa.width - CAPSULE_W - 24;
			let y = wa.y + wa.height - CAPSULE_H - 28;
			if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
				x = Math.min(Math.max(saved.x, wa.x), wa.x + wa.width - CAPSULE_W);
				y = Math.min(Math.max(saved.y, wa.y), wa.y + wa.height - CAPSULE_H);
			}
			capsuleWin = new BrowserWindow({
				x, y, width: CAPSULE_W, height: CAPSULE_H,
				frame: false, transparent: true, resizable: false, movable: true,
				alwaysOnTop: true, skipTaskbar: true, hasShadow: false, focusable: false,
				webPreferences: {
					preload: path.join(__dirname, '../preload/index.js'),
					contextIsolation: true,
					nodeIntegration: false,
					sandbox: false
				}
			});
			capsuleWin.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(CAPSULE_HTML));
			capsuleWin.on('closed', () => { capsuleWin = null; });
			capsuleWin.on('moved', () => {
				if (capsulePosTimer) { clearTimeout(capsulePosTimer); }
				capsulePosTimer = setTimeout(() => {
					try {
						const b = capsuleWin?.getBounds();
						if (!b) { return; }
						fs.mkdirSync(path.dirname(configFile), { recursive: true });
						fs.writeFileSync(configFile, JSON.stringify({ ...readConfigFile(), capsulePos: { x: b.x, y: b.y } }, null, 1), 'utf8');
					} catch { /* best effort */ }
				}, 400);
			});
		}
		capsuleWin?.webContents.send(IPC.capsuleState, state);
	});
	ipcMain.handle(IPC.capsuleUpdate, (_e, state: import('../../shared/ipc').CapsuleState) => {
		if (capsuleWin && !capsuleWin.isDestroyed()) { capsuleWin.webContents.send(IPC.capsuleState, state); }
	});
	ipcMain.handle(IPC.capsuleHide, () => {
		if (capsuleWin && !capsuleWin.isDestroyed()) { capsuleWin.destroy(); }
		capsuleWin = null;
	});
	ipcMain.handle(IPC.capsuleAction, (_e, action: 'toggle' | 'end' | 'open') => {
		if (action === 'end') { mainWindow?.focus(); } // confirm dialogs live in the main window
		mainWindow?.webContents.send(IPC.focusAction, action);
	});

	// ---- backup file save/open + reveal ----
	ipcMain.handle(IPC.fileSaveJson, (_e, json: string, defaultName: string) => {
		const win = BrowserWindow.getFocusedWindow() ?? mainWindow;
		const res = dialog.showSaveDialogSync(win!, {
			title: '导出备份',
			defaultPath: path.join(app.getPath('documents'), defaultName || 'planability-export.json'),
			filters: [{ name: 'JSON', extensions: ['json'] }]
		});
		if (!res) { return null; }
		fs.writeFileSync(res, json, 'utf8');
		return res;
	});
	ipcMain.handle(IPC.fileOpenJson, () => {
		const win = BrowserWindow.getFocusedWindow() ?? mainWindow;
		const res = dialog.showOpenDialogSync(win!, {
			title: '导入备份',
			filters: [{ name: 'JSON', extensions: ['json'] }],
			properties: ['openFile']
		});
		if (!res?.[0]) { return null; }
		try { return JSON.parse(fs.readFileSync(res[0], 'utf8')); } catch { return null; }
	});
	ipcMain.handle(IPC.openDataDir, async () => {
		const { shell } = await import('electron');
		await shell.openPath(dataDir);
	});

	// ---- Syncthing 同步中心 (phone SyncthingScreen parity) ----
	const syncthing = new SyncthingEngine(
		path.join(appDir(), 'syncthing'),
		path.join(appDir(), 'syncthing-bin'),
		() => readConfigFile().syncthingPath
	);
	const syncSummary = async (): Promise<SyncSummary> => syncthing.summary();
	ipcMain.handle(IPC.syncStatus, () => syncSummary());
	ipcMain.handle(IPC.syncStart, async (): Promise<SyncSummary> => {
		// full integration: a missing kernel is downloaded on demand from the UI
		await syncthing.start({ autoInstall: true });
		return syncSummary();
	});
	ipcMain.handle(IPC.syncStop, async (): Promise<SyncSummary> => {
		await syncthing.stop();
		await new Promise(r => setTimeout(r, 800)); // let the kernel finish exiting before re-probe
		return syncSummary();
	});
	ipcMain.handle(IPC.syncInstall, () => syncthing.install());
	ipcMain.handle(IPC.syncAddDevice, (_e, deviceId: string, name: string) => syncthing.addDevice(deviceId, name));
	ipcMain.handle(IPC.syncAddFolder, (_e, label: string, folderPath: string) => syncthing.addFolder(label, folderPath));
	ipcMain.handle(IPC.syncRemoveFolder, (_e, id: string) => syncthing.removeFolder(id));
	ipcMain.handle(IPC.syncPickBinary, async (): Promise<string | null> => {
		const win = BrowserWindow.getFocusedWindow() ?? mainWindow;
		const res = await dialog.showOpenDialog(win!, {
			title: '选择 syncthing.exe',
			filters: [{ name: 'Executable', extensions: ['exe'] }],
			properties: ['openFile']
		});
		const file = res.filePaths[0];
		if (!file) { return null; }
		fs.writeFileSync(configFile, JSON.stringify({ ...readConfigFile(), syncthingPath: file }, null, 1), 'utf8');
		return file;
	});
	// Our own kernel follows the app lifecycle (phone parity); external instances are untouched.
	app.on('will-quit', () => syncthing.shutdown());
	ipcMain.handle(IPC.syncLogTail, () => syncthing.logTail());

	// ---- full in-app management (web-GUI parity — no browser needed) ----
	ipcMain.handle(IPC.syncConfigGet, () => syncthing.configGet());
	ipcMain.handle(IPC.syncConfigPut, (_e, cfg: import('../../shared/ipc').StConfig) => syncthing.configPut(cfg));
	ipcMain.handle(IPC.syncDefaults, (_e, kind: 'folder' | 'device') => syncthing.defaults(kind));
	ipcMain.handle(IPC.syncBrowse, (_e, current: string) => syncthing.browse(String(current ?? '')));
	ipcMain.handle(IPC.syncIgnoresGet, (_e, folderId: string) => syncthing.ignoresGet(String(folderId)));
	ipcMain.handle(IPC.syncIgnoresPut, (_e, folderId: string, patterns: string[]) => syncthing.ignoresPut(String(folderId), patterns));
	ipcMain.handle(IPC.syncPendingGet, () => syncthing.pending());
	ipcMain.handle(IPC.syncDecline, (_e, target: { device?: string; folder?: string; from?: string }) => syncthing.decline(target ?? {}));
	ipcMain.handle(IPC.syncScan, (_e, folderId: string) => syncthing.scan(String(folderId)));
	ipcMain.handle(IPC.syncPutDevice, (_e, cfg: import('../../shared/ipc').StDeviceConfig) => syncthing.putDevice(cfg));
	ipcMain.handle(IPC.syncDeleteDevice, (_e, deviceID: string) => syncthing.deleteDevice(String(deviceID)));
	ipcMain.handle(IPC.syncPutFolder, (_e, cfg: import('../../shared/ipc').StFolderConfig) => syncthing.putFolder(cfg));
	ipcMain.handle(IPC.syncSystemInfo, () => syncthing.systemInfo());
	ipcMain.handle(IPC.syncSystemLog, (_e, limit: number) => syncthing.systemLog(Number(limit) || 300));
	ipcMain.handle(IPC.syncErrorsGet, () => syncthing.errors());
	ipcMain.handle(IPC.syncErrorsClear, () => syncthing.clearErrors());
	ipcMain.handle(IPC.syncRestart, () => syncthing.restart());

	// ---- 完整模式：打开 Syncthing 原版 GUI ----
	//
	// 本页是本应用重写的同步界面；原版 GUI 里还有一堆这里没做的东西（发现/中继/GUI TLS/
	// 文件夹高级项/每设备速率……）。完整模式把它们原样交出去，但**必须由主进程开门**：
	// 原版 GUI 的每个 REST 调用都要 X-API-Key，而它自己的页面里没有 key（实测页面里搜不到
	// apiKey），所以拿 `shell.openExternal` 丢给浏览器只会停在要 key 的提示上。
	//
	// 做法是给它一个独立 session，用 webRequest 在发往内核的请求上加头。于是 key 只在主进程
	// 里待着：不进 URL（不会留在浏览历史 / 不随 Referer 泄给页面加载的任何第三方资源），
	// 不经过 preload，渲染层永远看不到它。
	//
	// 用 persist: 分区而不是临时分区：原版 GUI 会把自己的面板布局/主题存 localStorage，
	// 关了再开应该还是那个样子；同时也和应用自己的渲染层 session 隔开。
	let guiWin: BrowserWindow | null = null;
	ipcMain.handle(IPC.syncOpenGui, async (): Promise<string> => {
		const st = await syncthing.status();
		if (!st.running) {
			throw new Error('内核还没启动：完整模式连的是同一个内核，请先点「启动」');
		}
		const { url, apiKey } = syncthing.guiEndpoint();
		if (guiWin && !guiWin.isDestroyed()) {
			if (guiWin.isMinimized()) { guiWin.restore(); }
			guiWin.focus();
			return url;
		}
		const ses = session.fromPartition('persist:syncthing-gui');
		// 只对内核自己的地址加头：同一个 session 里万一有别的页面，不该拿到 key。
		// 通配写法刻意用拼接，不写字面量：test/ 里的源码断言用朴素正则剥注释，字符串里出现
		// 星号紧挨斜杠会被它当成块注释开头、一路吃到下一个结束符，把后半个文件删掉
		// （已经踩过：card-store 的源码断言因此误红）。
		const GUI_GLOB = 'http://' + GUI_HOST + ':' + GUI_PORT + '/';
		const filter = { urls: [GUI_GLOB + '*'] };
		const inject = (
			details: Electron.OnBeforeSendHeadersListenerDetails,
			callback: (r: Electron.BeforeSendResponse) => void
		): void => {
			details.requestHeaders['X-API-Key'] = apiKey;
			callback({ requestHeaders: details.requestHeaders });
		};
		ses.webRequest.onBeforeSendHeaders(filter, inject);
		const win = new BrowserWindow({
			width: 1280,
			height: 860,
			minWidth: 900,
			minHeight: 600,
			title: 'Syncthing · 完整模式',
			autoHideMenuBar: true,
			webPreferences: {
				// 原版 GUI 是第三方网页：不挂 preload、不给 node、开沙箱。
				session: ses,
				contextIsolation: true,
				nodeIntegration: false,
				sandbox: true
			}
		});
		guiWin = win;
		win.on('closed', () => {
			// onBeforeSendHeaders 挂在 session 上，窗口关了必须撤掉，否则这个持久分区
			// 以后开的任何页面都会带上 key。
			ses.webRequest.onBeforeSendHeaders(filter, null);
			if (guiWin === win) { guiWin = null; }
		});
		await win.loadURL(url);
		return url;
	});


	// ---- data sync over Syncthing (pc-dump.json ⇄ phone-dump.json) ----
	const dataSync = new DataSyncService(
		store,
		syncthing,
		path.join(appDir(), 'sync-data')
	);
	void dataSync.init().catch(e => console.error('[sync] data init failed', String(e)));
	ipcMain.handle(IPC.syncDataGet, () => dataSync.status());
	ipcMain.handle(IPC.syncDataSet, (_e, on: boolean) => dataSync.setEnabled(!!on));
	ipcMain.handle(IPC.syncDataRun, (_e, kind: 'export' | 'import') => dataSync.run(kind));
	app.on('will-quit', () => dataSync.shutdown());

	// Full integration: after the standalone Syncthing is removed the app owns the kernel —
	// auto-start at launch when one is installed; first-time download stays on-demand.
	if (readConfigFile().syncAutoStart !== false) {
		const syncBootTimer = setTimeout(() => {
			void syncthing.startIfAvailable().catch(e => console.error('[sync] boot start failed', String(e)));
		}, 2500);
		app.on('will-quit', () => clearTimeout(syncBootTimer));
	}
	const approvalResolvers = new Map<string, (d: ApprovalDecision) => void>();
	let agent: ChatAgent | null = null;
	let abort: AbortController | null = null;
	let running = false;

	// Card store hook for every row bridge. Same object for the main bridge and each run's
	// bridge: the store keys everything by sessionId, and the bridge's sessionId provider is
	// what labels a capture correctly, so one sink serves both.
	const cardSink: RowCardSink = {
		capture: (sid, rowId, text) => cardStore.capture(sid, rowId, text),
		reconcile: (sid, live) => cardStore.reconcile(sid, live),
	};

	// Row bridge: converts agent events to v2 rows, streams them to the renderer and
	// persists them (main = single writer). Rows follow the active session; the bridge is
	// reloaded at turn start / rollback / switch, so a stale in-memory copy can't leak.
	const rowBridge = new RowBridge(
		e => broadcastChat(e),
		rows => sessions.saveRows(sessions.active(), rows),
		// Label follows the same session the rows persist to — main chat = the active one.
		() => sessions.active(),
		cardSink
	);

	// Legacy single-conversation files → session #1 (one-time import).
	void (async () => {
		try {
			const history = JSON.parse(fs.readFileSync(path.join(dataDir, 'chat_agent.json'), 'utf8'));
			const display = JSON.parse(fs.readFileSync(path.join(dataDir, 'chat_display.json'), 'utf8'));
			await sessions.importLegacy(history, display);
		} catch { /* no legacy */ }
	})();

	const toMeta = (s: ChatSession): SessionMeta => ({
		id: s.id, title: s.title, createdAt: s.createdAt, updatedAt: s.updatedAt,
		pinned: s.pinned, messageCount: (s.rows ?? []).filter(r => r.kind === 'user').length,
		// Spread, so an ungrouped session has NO `projectId` key rather than `projectId: undefined`.
		// Both read the same to a renderer, but only the absent form matches what is on disk.
		...(s.projectId ? { projectId: s.projectId } : {}),
	});

	const getAgent = (): ChatAgent => agent ??= new ChatAgent({
		registry,
		store,
		sessions,
		// The main chat follows the active session (user can switch mid-conversation).
		sessionId: () => sessions.active(),
		undo,
		slot: readSlot,
		permMode: readPermMode,
		assistant: () => {
			const key = readConfigFile().assistantKey;
			if (!key) { return null; }
			return assistantCache.get(key) ?? null;
		},
		plugins: () => ({ undo: pluginOn('undo'), cards: pluginOn('cards') }),
		memInject: readMemInject,
		// M17 待办表: re-inject open items into the system prompt each round (Q2 policy).
		todoReminder: (round: number) => todoStore.render(round),
		// M19-1: summarisation runs on the WORKER slot so it never competes with the answer for
		// the main quota. A summary that blocks the reply makes the user wait longer for LESS
		// context, which is the opposite of the point.
		summarize: makeSummarizer(() => readWorkerSlot()),
		// Toggles come from config each round so a change applies to the next request without a
		// restart — reading it per round also keeps the manifest honest about what was sent.
		manifestToggles: () => (readConfigFile().contextToggles ?? {}) as ManifestToggles,
		// M19-3: settle the finished turn into the append-only log. The agent calls this AFTER
		// emitting done, so it runs on the worker slot without delaying the answer.
		// M19-4: skills are loaded once per turn and selected by keyword. Loaded in the main
		// process because it needs the filesystem; rendered with the "reference, not instruction"
		// framing so a user-written file can never act as an operator directive.
		skillBlock: async (text: string) => {
			// skillsDir(), not a second copy of the resolution — see its declaration above.
			const dir = skillsDir();
			try {
				const { skills, problems } = await loadSkills({ userDir: dir });
				if (problems.length) {
					for (const p of problems) { console.warn('[skills]', p.file, '-', p.reason); }
				}
				return renderSkills(selectSkills(skills, text));
			} catch (e) {
				console.error('[skills] load failed —', (e as Error)?.message ?? e);
				return '';
			}
		},
		settleMemories: async () => {
			const r = await extractor(getAgent().transcriptForMemory());
			if (!r.ok) { return { ok: false, reason: r.reason, applied: [], rejected: [] }; }
			if (!r.proposals.length) { return { ok: true, applied: [], rejected: [] }; }
			const res = applyProposals(memLog, r.proposals, {
				workspace: readConfigFile().workspaceRoot || appDir(),
				source: 'auto',
			});
			await projectMemories(memLog, store);
			return { ok: true, applied: res.applied, rejected: res.rejected };
		},
		// M18: approval card enrichment (file_write renders a diff).
		approvalArgs,
		// P0-d（双端项目同步）：每轮 persist 后把会话镜像到项目文件夹（best-effort）。
		mirrorSession,
		emit: (e: AgentEvent) => {
			switch (e.type) {
				case 'text': rowBridge.text(e.value); break;
				case 'reasoning': rowBridge.reasoning(e.value); break;
				case 'tool_start': rowBridge.toolStart(e.name, e.argsJson); break;
				case 'user_injected':
					// A queued thought just reached the model: render it as a real user row so the
					// transcript shows exactly what the AI was told.
					rowBridge.user(e.text);
					broadcastChat({ type: 'row_user_queued', text: e.text, at: e.at, injected: true });
					void rowBridge.save();
					break;
				case 'tool_end':
					rowBridge.toolEnd(e.name, e.ok, !!e.denied, e.summary, e.ms, e.result);
					void rowBridge.save();
					break;
				case 'done':
					rowBridge.settleAssistant();
					void rowBridge.save();
					// Announce the turn end. This contract member existed in shared/chat.ts but
					// was never sent — the renderer's listener had nothing to listen to, so a
					// panel that refreshed on `done` simply never refreshed and the fallback
					// looked like the feature was missing. Sent to EVERY window, not just
					// mainWindow: when the main window is hidden a floating window still needs
					// to know the turn finished.
					broadcastChat({ type: 'done' });
					break;
				case 'error':
					rowBridge.settleAssistant(e.message);
					void rowBridge.save();
					// The row settles with an error message, but a row is not a signal: the
					// renderer cannot tell "assistant said something" from "the turn died" by
					// reading a row alone. Hence a distinct event.
					broadcastChat({ type: 'error', message: e.message });
					break;
			}
		},
		requestApproval: req => new Promise<ApprovalDecision>(resolve => {
			approvalResolvers.set(req.id, resolve);
			broadcast(CHAT_EVENTS.approval, req);
			// Phone-parity DeadMansSwitch: nobody answers in 120s → auto-deny.
			setTimeout(() => {
				if (approvalResolvers.has(req.id)) {
					approvalResolvers.delete(req.id);
					resolve({ allow: false, allowAlways: false });
				}
			}, 120_000);
		}),
		isAborted: () => abort?.signal.aborted ?? false
	});

	// ---- M17 multi-agent kernel ----
	// AgentManager owns scheduling/identity; each run drives a real ChatAgent turn whose
	// events land in that run's own session rows, so parallel agents never share a row buffer.
	//
	// Fail-soft on purpose: the kernel is an optional feature, so a broken ai.db (locked,
	// corrupt, unwritable disk, a future bad migration) must NOT take the whole app down at
	// boot — chat, notes and sync must still come up. Handlers below answer with an error
	// while the rest of the app keeps working. (AiDb + migrate happen above, next to the
	// registry, because the todo tools need the same handle.)
	if (aiDb.schemaVersion() > 0) {
		agentManager = new AgentManager({
		db: aiDb,
		concurrency: 4,
		emit: e => {
			// Broadcast: the sub-agent event stream is on the CHAT_EVENTS namespace, and a
			// floating window watching a run has the same need the main one does.
			if (e.type === 'bus') { broadcast(CHAT_EVENTS.agentEvent, { type: 'bus', message: e.message }); }
			else { broadcast(CHAT_EVENTS.agentEvent, e); }
		},
		run: async req => {
			// A dedicated agent instance per run: its own session + its own row bridge target.
			const sid = req.sessionId || `ag_s_${req.agent.id}_${Date.now().toString(36)}`;
			agentManager!.ensureSession(sid, req.agent.name, req.agent.id);
			// The rows/history for this run live in the JSON SessionStore, which no-ops on an
			// unknown id. Creating the row here — one choke point for fan-out, the task tool and
			// IPC alike — is what stops a peer's answer coming back empty.
			await sessions.ensure(sid, `${req.agent.name} · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`);
			const bridge = new RowBridge(
				e => broadcastChat(e),
				rows => sessions.saveRows(sid, rows),
				// Fixed to this run's own session: the renderer drops row events whose
				// sessionId is not the one it displays, so a background run can no longer
				// write its transcript into the conversation the user is reading.
				() => sid,
				cardSink
			);
			bridge.replaceAll(normalizeRows(await sessions.loadRows(sid)));
			if (req.prompt) { bridge.user(req.prompt); void bridge.save(); }

			// Sub-agents inherit a narrowed permission gear and the parent's tools by default.
			const sub = new ChatAgent({
				registry,
				store,
				sessions,
				// CRITICAL: the run's OWN session, never sessions.active(). Using the active one
				// made every sub-agent/peer load the parent's transcript and write its turns back
				// into it — pc-a caught three peers all answering with the parent's opening line.
				sessionId: () => sid,
				undo,
				slot: readSlot,
				permMode: () => req.agent.permMode,
				assistant: () => ({ name: req.agent.name, systemPrompt: req.agent.systemPrompt }),
				plugins: () => ({ undo: pluginOn('undo'), cards: pluginOn('cards') }),
				memInject: readMemInject,
				approvalArgs,
				emit: (e: AgentEvent) => {
					switch (e.type) {
						case 'text': bridge.text(e.value); break;
						case 'reasoning': bridge.reasoning(e.value); break;
						case 'tool_start': bridge.toolStart(e.name, e.argsJson); break;
						case 'tool_end':
							bridge.toolEnd(e.name, e.ok, !!e.denied, e.summary, e.ms, e.result);
							// M17 audit lands in SQLite as well as the existing jsonl.
							aiDb.raw.prepare(
								'INSERT INTO tool_audit (sessionId,agentId,tool,input,ok,denied,ms,at) VALUES (?,?,?,?,?,?,?,?)',
							).run(sid, req.agent.id, e.name, '{}', e.ok ? 1 : 0, e.denied ? 1 : 0, e.ms, Date.now());
							void bridge.save();
							break;
						case 'done': bridge.settleAssistant(); void bridge.save(); break;
						case 'error': bridge.settleAssistant(e.message); void bridge.save(); break;
					}
				},
				// Sub-agent danger/confirm operations still bubble up to the same approval
				// card as the main chat (M17 acceptance: danger must not run silently).
				requestApproval: req2 => new Promise<ApprovalDecision>(resolve => {
					approvalResolvers.set(req2.id, resolve);
					broadcast(CHAT_EVENTS.approval, req2);
					setTimeout(() => {
						if (approvalResolvers.has(req2.id)) {
							approvalResolvers.delete(req2.id);
							resolve({ allow: false, allowAlways: false });
						}
					}, 120_000);
				}),
				isAborted: () => abort?.signal.aborted ?? false,
				// 子 agent 的会话同样镜像（会话绑了项目才会真正写盘）。
				mirrorSession,
			});
			const before = sub.estimateContextTokens();
			await sub.send(req.prompt || '（继续当前任务）');
			// Token ledger: the provider exposes no usage, so estimate from the transcript.
			const after = sub.estimateContextTokens();
			agentManager!.recordUsage(req.agent.id, sid, 0, Math.max(0, after - before), 0);
			const last = (await sessions.loadRows(sid)).filter(r => r.kind === 'assistant').pop();
			let answer = typeof last?.text === 'string' ? last.text.trim().slice(0, 2000) : '';
			// Fallback to the persisted history: a row can be missing/empty (e.g. the turn
			// settled on a tool error) while the transcript still holds the real answer.
			// Never fall back to proposing an empty string — that is what silently produced
			// zero proposals before.
			if (!answer) {
				const hist = await sessions.loadHistory(sid);
				for (let i = hist.length - 1; i >= 0; i--) {
					const m = hist[i];
					if (m.role === 'assistant' && typeof m.content === 'string' && m.content.trim()) {
						answer = m.content.trim().slice(0, 2000);
						break;
					}
				}
			}

			// P3: peer sessions are named `${groupId}_${slotKey}` — feed the finished answer
			// into the group as that peer's proposal. Without this the vote has nothing to
			// count and every round would report「还没有任何 agent 提交方案」.
			// NOTE: split on the LAST underscore — group ids themselves contain one
			// (`pg_<base36>_<rand>`), so indexOf('_', 3) would cut inside the id.
			if (peerOrch && sid.startsWith('pg_')) {
				const cut = sid.lastIndexOf('_');
				if (cut > 0) { peerOrch.propose(sid.slice(0, cut), req.agent.id, answer); }
			}
			// typed-parts: mirror this run's transcript into SQLite so the messages table is
			// populated (pc-a's M17 acceptance ⑥). Incremental by seq — not a full rewrite.
			try { typedParts.persist(sid, await sessions.loadHistory(sid) as never); } catch { /* non-fatal */ }
			return answer;
		},
	});
		try { agentManager.seedPresetTeams(); } catch (e) { console.error('[M17] 预置团队写入失败：', e); }
	}

	// Fill in the `task` tool's host now that the kernel exists. spawn() creates the child's
	// OWN session and hands the parent id through, so the depth guard sees a real tree.
	agentHost.host = {
		spawn: (agentId, parentSessionId, prompt) => {
			if (!agentManager) { return null; }
			const sid = `ag_sub_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
			const agent = agentManager.getAgent(agentId);
			agentManager.ensureSession(sid, `${agent?.name ?? agentId} · 子任务`, agentId, parentSessionId);
			const run = agentManager.submitSub(agentId, sid, parentSessionId, prompt);
			return { id: run.id, status: run.status, sessionId: sid, error: run.error };
		},
		listAgents: () => (agentManager ? agentManager.listAgents().map(a => ({ id: a.id, name: a.name })) : []),
		runs: () => agentManager ? {
			runs: agentManager.activeRuns().map(r => ({
				id: r.id, agentId: r.agentId, sessionId: r.sessionId, kind: r.kind,
				status: r.status, prompt: r.prompt, startedAt: r.startedAt,
				...(r.error ? { error: r.error } : {}),
			})),
			running: agentManager.runningCount(),
			limit: agentManager.concurrency,
		} : null,
		agent: id => {
			const a = agentManager?.getAgent(id);
			return a ? { id: a.id, name: a.name, builtin: a.builtin } : null;
		},
		// The guards live HERE, at the boundary to the DB, not in the tool: upsertAgent itself
		// happily rewrites a builtin profile and creates whatever id it is handed, and its
		// missing-permMode fallback is 'auto' (the most permissive gear). A tool that trusted
		// all three would be a quiet privilege escalation.
		upsert: p => {
			if (!agentManager) { return { ok: false, error: '多代理内核未就绪（ai.db 不可用）' }; }
			const prev = p.id ? agentManager.getAgent(p.id) : null;
			if (p.id && !prev) { return { ok: false, error: `没有编号为 ${p.id} 的 agent（不按不存在的 id 创建）` }; }
			if (prev?.builtin) { return { ok: false, error: `「${prev.name}」是内置 agent，不可修改` }; }
			const rec = agentManager.upsertAgent({ ...p, permMode: prev ? prev.permMode : 'confirm' });
			return { ok: true, id: rec.id, name: rec.name, permMode: rec.permMode };
		},
		delete: id => {
			if (!agentManager) { return { ok: false, error: '多代理内核未就绪（ai.db 不可用）' }; }
			const prev = agentManager.getAgent(id);
			if (!prev) { return { ok: false, error: `没有编号为 ${id} 的 agent` }; }
			if (prev.builtin) { return { ok: false, error: `「${prev.name}」是内置 agent，不可删除` }; }
			return agentManager.deleteAgent(id) ? { ok: true } : { ok: false, error: '数据库拒绝了这次删除' };
		},
		// kind is pinned to 'note' at the TOOL layer: steer/terminal have a docstring and a
		// column comment but no consumer anywhere in the run loop — accepting them here would
		// let the model believe it had steered or ended a live run.
		post: (from, to, body) => {
			if (!agentManager) { return { ok: false, error: '多代理内核未就绪（ai.db 不可用）' }; }
			const msg = agentManager.post(from, to, body, 'note');
			return { ok: true, id: msg.id };
		},
	};
	// P3 平级模式: peers ride the same AgentManager (and its concurrency gate) but get their own
	// sessions with no parentId — that is what makes them peers rather than sub-agents.
	const peerStore = new PeerStore(aiDb);
	let peerOrch: PeerOrchestrator | null = null;
	if (agentManager) { peerOrch = new PeerOrchestrator(peerStore, agentManager); }

	ipcMain.handle(IPC_CHAT.chatSend, async (_e, text: string, attachments?: unknown) => {
		// Attachments ride the MESSAGE path (the float pane's tail): the draft kept its path-only
		// attachments while the text was sent, so a screenshot the user just attached never
		// reached the turn. They go on the user row (transcript chips) and into a reference block
		// on the text the model reads — name + path, never content. See ai/chat-attachments.ts.
		const raw = String(text ?? '');
		const files = normalizeAttachments(attachments);
		const said = withAttachmentRefs(raw, files);
		// M17 实时插话: a busy turn no longer rejects the message — the thought is parked and
		// injected at the next round boundary (or when the turn concludes, per injectMode).
		// This is the whole point: the user must not have to stop the AI to add an idea.
		// A queued thought is text-only, so the references ride inside `said`; the parked row
		// therefore names them rather than showing chips.
		if (running) { return queueThought(said); }
		running = true;
		abort = new AbortController();
		try {
			rowBridge.replaceAll(normalizeRows(await sessions.loadRows(sessions.active())));
			rowBridge.user(raw, files);
			void rowBridge.save();
			await getAgent().send(said);
			// typed-parts: keep SQLite `messages` in step with the JSON rows for this session.
			try { typedParts.persist(sessions.active(), await sessions.loadHistory(sessions.active()) as never); } catch { /* non-fatal */ }
		} catch (e) {
			// pre-turn failures (running flag etc.) — agent-internal errors already settled their row
			rowBridge.turnError(String((e as Error)?.message ?? e));
		} finally {
			running = false;
			void rowBridge.save();
		}
	});
	ipcMain.handle(IPC_CHAT.chatStop, () => { abort?.abort(); });

	// ---- M17 实时插话 ----
	/** Park a thought for the running turn and surface it immediately as a queued row. */
	const queueThought = (text: string): { queued: number } => {
		const before = getAgent().pendingThoughts().length;
		const pending = getAgent().enqueueThought(text);
		// Show it right away so the user knows it was accepted (not silently swallowed).
		for (let i = before; i < pending.length; i++) {
			const q = pending[i];
			broadcastChat({ type: 'row_user_queued', text: q.text, at: q.at });
		}
		return { queued: pending.length };
	};
	ipcMain.handle(IPC_CHAT.chatEnqueue, (_e, text: string) => queueThought(String(text ?? '')));
	ipcMain.handle(IPC_CHAT.chatEnqueueList, () => getAgent().pendingThoughts());
	ipcMain.handle(IPC_CHAT.chatEnqueueClear, () => { getAgent().clearThoughts(); });
	ipcMain.handle(IPC_CHAT.chatInjectMode, () => getAgent().getInjectMode());
	ipcMain.handle(IPC_CHAT.chatSetInjectMode, (_e, m: any) => getAgent().setInjectMode(m === 'afterTurn' ? 'afterTurn' : 'nextRound'));
	// ---- M19-1 ContextBudget ----
	// Toggles live in the config file, not in memory, so they survive a restart — a context
	// switch the user set and then lost on relaunch is worse than no switch at all.
	ipcMain.handle(IPC_CHAT.contextTogglesGet, () => readConfigFile().contextToggles ?? {});
	ipcMain.handle(IPC_CHAT.contextTogglesSet, (_e, t: Record<string, boolean>) => {
		const next: Record<string, boolean> = { ...(readConfigFile().contextToggles ?? {}) };
		// Only known keys are persisted. An unknown key here means a renderer/main version
		// mismatch, and writing it through would let it stick around forever.
		for (const k of MANIFEST_KEYS) {
			if (typeof (t as any)?.[k] === 'boolean') { next[k] = (t as any)[k]; }
		}
		// Written through configSet's handler rather than with its own fs.writeFileSync: two
		// writers to one file is how the next settings edit silently clobbers these.
		configSet({ contextToggles: next } as DesktopConfig);
		return next;
	});
	ipcMain.handle(IPC_CHAT.contextManifest, () => getAgent().manifest());
	ipcMain.handle(IPC_CHAT.contextHistory, (_e, limit: number) => {
		const n = Number.isFinite(Number(limit)) ? Math.min(Math.max(Number(limit), 1), 200) : 20;
		return getAgent().manifestHistory(n);
	});

	// ---- `list` cards: a card that carries a QUERY and shows real data ----
	// Read-only by construction: the query can only name a collection and a closed filter set, so
	// there is no path from a card to a write — anything that changes data goes through the tool
	// registry with its risk level, approval card and undo entry.
	//
	// A bad query THROWS instead of returning an empty list, because「没有匹配项」and「我读不懂
	// 你的筛选」look identical to the user and only one of them is true. Returning [] here would be
	// a card silently showing the wrong thing.
	ipcMain.handle(IPC_CHAT.listQuery, async (_e, q: unknown) => {
		const n = normalizeListQuery(q);
		if (!n.ok) { throw new Error(n.error); }
		return runListQuery(store, n.query);
	});
	// ---- Card ACTION buttons: stage 3 of the card plan ----
	// A button click becomes a REAL tool call, but never a second write path: the tool is
	// resolved through the SAME registry
	// the model's calls go through (so a domain switch that hides `schedule_create` refuses
	// the button too), risk comes from the TOOL not the card (confirm/danger pop the SAME
	// approval card as a model-initiated call — arg edits included, 120s auto-deny), and the
	// invoke is the registry tool's own, so undo/audit happen exactly as they would for a
	// model call. Args are assembled in ai/card-actions.ts from the CARD's fields — the
	// spec in shared/cards.ts may only name tools, never smuggle raw args to them.
	ipcMain.handle(IPC_CHAT.cardAction, async (_e, req: CardActionRequest) =>
		runCardAction({
			registry,
			requestApproval: (toolName, risk, argsJson) => new Promise<ApprovalDecision>(resolve => {
				const approvalReq: ApprovalRequest = {
					id: `ca_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`,
					toolName, risk, argsJson,
				};
				approvalResolvers.set(approvalReq.id, resolve);
				broadcast(CHAT_EVENTS.approval, approvalReq);
				// Same DeadMansSwitch as the model path: an unanswered card denies, it never
				// hangs the IPC call open forever.
				setTimeout(() => {
					if (approvalResolvers.has(approvalReq.id)) {
						approvalResolvers.delete(approvalReq.id);
						resolve({ allow: false, allowAlways: false });
					}
				}, 120_000);
			}),
		}, req)
	);
	// ---- Card ENTITIES: stage 2 of the card plan — cards as first-class persisted things ----
	// The store's own read/pin/remove surface. The model never touches these (the drift table
	// classifies them renderer-only): a card the renderer can see is not a capability the model
	// lacked — its data and tools are already its own. cardList is what the sidebar reads;
	// cardRemove refuses proposal cards with a reason (see CardStore.remove).
	ipcMain.handle(IPC_CHAT.cardList, (_e, q?: CardListQuery) => cardStore.list(q ?? {}));
	ipcMain.handle(IPC_CHAT.cardPin, (_e, id: string, pinned: boolean) =>
		cardStore.pin(String(id ?? ''), !!pinned));
	ipcMain.handle(IPC_CHAT.cardRemove, (_e, id: string) => cardStore.remove(String(id ?? '')));
	// ---- M20-1 workflows ----
	// Roles are resolved to agents by NAME at dispatch time, so a workflow stays readable
	// ("researcher") and survives an agent being renamed or re-keyed underneath it.
	ipcMain.handle(IPC_CHAT.workflowList, (): WorkflowInfo[] => {
		// One resolver, shared with workflowRun and the automation sweep — see allWorkflows().
		const { list, shadowed, problems } = allWorkflows();
		return list.map(w => ({
			id: w.id,
			name: w.name,
			task: w.task,
			// Always map real steps. An entry that fails validation surfaces via `problems`;
			// reporting steps only when valid made a broken file look like an empty one.
			steps: (w.steps ?? []).map(s => ({ id: s.id, role: s.role, prompt: s.prompt })),
			overridden: shadowed.has(w.id) || undefined,
			problems: problems.get(w.id),
		}));
	});
	ipcMain.handle(IPC_CHAT.workflowValidate, (_e, w: unknown) => validateWorkflow(w as any));
	ipcMain.handle(IPC_CHAT.workflowRun, (_e, workflowId: string, task: string) => {
		// Same resolver as workflowList: whatever the UI showed is exactly what can run.
		const w = resolveWorkflow(String(workflowId ?? ''));
		if (!w) { return { ok: false, error: `未知工作流 ${workflowId}` }; }
		const goal = String(task ?? '').trim() || w.task;
		if (!goal || goal === '（未指定任务）') {
			// Running the demo with no task would feed the agents a literal placeholder — the
			// exact failure the leftover-placeholder check exists to prevent.
			return { ok: false, error: '需要提供任务描述。' };
		}
		const wf = { ...w, task: goal };
		// Refuse before dispatching rather than half-way through: runWorkflow would return a
		// failed journal either way, but the UI can act on an immediate error with reasons and a
		// journal has to be written, listed and read to say the same thing.
		const v = validateWorkflow(wf);
		if (v.ok === false) {
			return { ok: false, error: `工作流定义无效：${v.errors.join('、')}` };
		}
		const runId = journals.nextRunId();
		const runner = agentStepRunner({
			start: (role, prompt) => am().submitPrimary(resolveRole(role), sessions.active(), prompt).id,
			peek: (id) => {
				const r = am().getRun(id);
				return r ? { status: r.status, result: r.result, error: r.error } : undefined;
			},
			resolveRole,
		});
		void runWorkflow(wf, runner, {
			runId,
			onStep: (e) => {
				for (const w2 of BrowserWindow.getAllWindows()) {
					if (!w2.isDestroyed()) {
						w2.webContents.send(CHAT_EVENTS.workflowEvent, {
							runId, stepId: e.stepId, role: e.role, status: e.status, error: e.error,
						});
					}
				}
			},
		}).then(r => { journals.add(r.journal); });
		return { ok: true, runId };
	});
	ipcMain.handle(IPC_CHAT.workflowReplay, (_e, runId: string) => {
		const j = journals.get(String(runId));
		if (!j) { return { ok: false }; }
		return { ok: true, text: replayJournal(j).lines };
	});
	ipcMain.handle(IPC_CHAT.workflowRuns, (_e, limit: number) => {
		const n = Number.isFinite(Number(limit)) ? Math.min(Math.max(Number(limit), 1), 100) : 20;
		return journals.list(n).map(j => ({
			runId: j.runId, workflowId: j.workflowId, workflowName: j.workflowName, task: j.task,
			status: j.status, failedAt: j.failedAt,
			steps: j.entries.map(e => ({ stepId: e.stepId, role: e.role, status: e.status })),
			startedAt: j.startedAt, endedAt: j.endedAt,
		}));
	});
	// ---- M20-3: team assembly. Returns a PROPOSAL and dispatches nothing — the user presses
	// the button, which is the only sane default for spending quota on a guessed team.
	ipcMain.handle(IPC_CHAT.teamAssemble, async (_e, requirement: string, preferTemplates?: string[]) => {
		const agents = am().listAgents().map(a => ({ id: a.id, name: a.name, tools: a.tools }));
		const r = assembleTeam({
			requirement: String(requirement ?? ''),
			agents,
			preferTemplates: Array.isArray(preferTemplates) ? preferTemplates.map(String) : undefined,
		});
		if (!r.ok) { return { ok: false, reason: r.reason, detail: r.detail }; }
		const p = r.proposal;
		// Register it, otherwise "run this proposal" is a button that can never work: the
		// proposal's workflow only existed in the response payload, so by the time the user
		// pressed run, workflowRun was looking for `assembled-*` in a resolver that had never
		// heard of it. See allWorkflows() for why this map is memory-only.
		assembledWorkflows.set(p.workflow.id, p.workflow);
		return {
			ok: true,
			template: { key: p.template.key, name: p.template.name, description: p.template.description },
			steps: p.steps, weakest: p.weakest, warnings: p.warnings,
			workflow: { id: p.workflow.id, name: p.workflow.name, task: p.workflow.task, steps: p.workflow.steps },
		};
	});
	// ---- M20-4: handoff. Written into the synced workspace folder so the phone can see it.
	const handoffDir = () => path.join(readConfigFile().workspaceRoot || appDir(), 'handoff');
	ipcMain.handle(IPC_CHAT.handoffPreview, (_e, h: unknown) => {
		// Preview first so the editor can show the EXACT document being produced. Same refusal
		// as writing: a handoff with no goal cannot be picked up cold, so it is not one.
		if (!String((h as any)?.goal ?? '').trim()) {
			return { ok: false, reason: '交接必须写明目标（goal），否则接手方无从下手。' };
		}
		return { ok: true, text: renderHandoff(h as any) };
	});
	ipcMain.handle(IPC_CHAT.handoffWrite, (_e, h: unknown) => writeHandoff(handoffDir(), h as any)
		.then(r => ({ ok: r.ok, path: r.path, reason: r.reason })));
	ipcMain.handle(IPC_CHAT.handoffRead, async (_e, name: string) => {
		// The filename comes from the renderer, so it must not be able to escape the folder.
		const safe = path.basename(String(name ?? ''));
		if (!safe.endsWith('.md')) { return { ok: false }; }
		try {
			return { ok: true, text: await fsp.readFile(path.join(handoffDir(), safe), 'utf8') };
		} catch { return { ok: false }; }
	});
	ipcMain.handle(IPC_CHAT.handoffList, async () => {
		try {
			const names = await fsp.readdir(handoffDir());
			const out: { name: string; bytes: number }[] = [];
			for (const n of names.filter(x => x.endsWith('.md')).sort().reverse()) {
				try { out.push({ name: n, bytes: (await fsp.stat(path.join(handoffDir(), n))).size }); } catch { /* skip */ }
			}
			return out;
		} catch { return []; }
	});
	ipcMain.handle(IPC_CHAT.chatClear, () => {
		agent?.clear();
		rowBridge.clear();
		void rowBridge.save();
	});
	ipcMain.handle(IPC_CHAT.approvalResolve, (_e, id: string, decision: ApprovalDecision) => {
		const r = approvalResolvers.get(id);
		if (r) { approvalResolvers.delete(id); r(decision); }
	});
	// note: renderer passes {allow, allowAlways, args?} — args edits flow straight into the tool input

	// Conversation rows: the bridge owns persistence; the renderer only reads.
	ipcMain.handle(IPC_CHAT.chatGetRows, async () =>
		normalizeRows(await sessions.loadRows(sessions.active())));
	ipcMain.handle(IPC_CHAT.chatRollbackUsers, async (_e, k: number) => {
		await getAgent().rollbackUsers(Number(k) || 1);
		rowBridge.replaceAll(normalizeRows(await sessions.loadRows(sessions.active())));
		rowBridge.rollbackUsers(Number(k) || 1);
		await rowBridge.save();
	});

	// ---- sessions (multi-session parity) ----
	ipcMain.handle(IPC.sessionList, async () => ({
		metas: (await sessions.list()).map(toMeta),
		activeId: sessions.active()
	}));
	ipcMain.handle(IPC.sessionCreate, async (_e, title?: string, projectId?: string) =>
		toMeta(await sessions.create(title || '新对话', projectId)));
	ipcMain.handle(IPC.sessionSwitch, (_e, id: string) => sessions.switch(id));
	// Deleting a session deletes its cards too, view cards included — persistence is
	// persistence FOR something, and the sidebar it served is gone with the session.
	ipcMain.handle(IPC.sessionRemove, async (_e, id: string) => {
		const ok = await sessions.remove(id);
		if (ok) { await cardStore.dropSession(id).catch(e => console.error('[cards] dropSession', String(e))); }
		return ok;
	});
	ipcMain.handle(IPC.sessionRename, (_e, id: string, title: string) => sessions.rename(id, title));
	ipcMain.handle(IPC.sessionPin, (_e, id: string, pinned: boolean) => sessions.pin(id, pinned));
	ipcMain.handle(IPC.sessionSetProject, (_e, id: string, projectId: string | null) =>
		sessions.setProject(id, projectId));

	// ---- M17 multi-agent kernel ----
	// `am()` guards the optional kernel: if ai.db could not be migrated at boot these handlers
	// answer with a readable error instead of throwing a null-dereference.
	const am = (): AgentManager => {
		if (!agentManager) { throw new Error('多代理内核不可用（ai.db 初始化失败），请重启或检查数据目录权限'); }
		return agentManager;
	};
	ipcMain.handle(IPC.agentList, () => am().listAgents());
	ipcMain.handle(IPC.agentUpsert, (_e, p: any) => am().upsertAgent({
		id: p?.id, name: String(p?.name ?? '新 agent'),
		color: p?.color, systemPrompt: p?.systemPrompt,
		tools: Array.isArray(p?.tools) ? p.tools : undefined,
		memoryScope: Array.isArray(p?.memoryScope) ? p.memoryScope : undefined,
		permMode: p?.permMode, maxTurns: Number(p?.maxTurns) || undefined,
	}));
	ipcMain.handle(IPC.agentDelete, (_e, id: string) => am().deleteAgent(id));
	ipcMain.handle(IPC.agentSeedTeams, () => am().seedPresetTeams());
	ipcMain.handle(IPC.agentRun, (_e, agentId: string, sessionId: string, prompt: string) => {
		const sid = String(sessionId || '');
		am().ensureSession(sid, 'agent 会话', agentId);
		return am().submitPrimary(String(agentId), sid, String(prompt ?? ''));
	});
	ipcMain.handle(IPC.agentAbort, (_e, agentId: string) => am().abortAgent(String(agentId)));
	// Spawn a sub-agent from the UI too (the `task` tool does the same via the host).
	ipcMain.handle(IPC.agentRunSub, (_e, agentId: string, parentSessionId: string, prompt: string) => {
		const host = agentHost.host;
		if (!host) { throw new Error('多代理内核未就绪'); }
		const r = host.spawn(String(agentId), String(parentSessionId ?? ''), String(prompt ?? ''));
		if (!r) { throw new Error('多代理内核未就绪'); }
		return { ...r, kind: 'sub', depth: 1, startedAt: Date.now() } as never;
	});
	ipcMain.handle(IPC.agentRuns, () => ({
		runs: am().activeRuns(),
		running: am().runningCount(),
		// concurrency, not a second hardcoded 4 — same number the agent_runs tool reports.
		limit: am().concurrency,
	}));
	ipcMain.handle(IPC.agentMessage, (_e, from: string, to: string, body: string, kind: any) =>
		am().post(String(from), String(to), String(body ?? ''), kind ?? 'note'));
	ipcMain.handle(IPC.agentInbox, (_e, to: string) => am().inbox(String(to)));
	ipcMain.handle(IPC.agentMarkRead, (_e, to: string) => am().markRead(String(to)));
	ipcMain.handle(IPC.agentUsage, () => am().usageByAgent());

	// ---- M17 待办表 (P2) ----
	// Unlike the kernel handlers these do NOT require the manager: the list lives in the same
	// SQLite file but is useful even with the kernel disabled.
	ipcMain.handle(IPC.todoList, (_e, sessionId?: string) => todoStore.all(sessionId));
	ipcMain.handle(IPC.todoAdd, (_e, text: string, sessionId?: string) =>
		todoStore.add(String(text ?? ''), sessionId ? String(sessionId) : ''));
	ipcMain.handle(IPC.todoDone, (_e, id: string) => todoStore.done(String(id)));
	ipcMain.handle(IPC.todoReopen, (_e, id: string) => todoStore.reopen(String(id)));
	ipcMain.handle(IPC.todoRemove, (_e, id: string) => todoStore.remove(String(id)));
	ipcMain.handle(IPC.todoPolicyGet, () => todoStore.getPolicy());
	ipcMain.handle(IPC.todoPolicySet, (_e, patch: any) => todoStore.setPolicy({
		mode: patch?.mode, everyN: patch?.everyN, maxShown: patch?.maxShown,
	}));

	// ---- M17 平级模式 (P3) ----
	const po = (): PeerOrchestrator => {
		if (!peerOrch) { throw new Error('平级模式不可用（多代理内核未启用）'); }
		return peerOrch;
	};
	ipcMain.handle(IPC.peerTemplates, () => PEER_TEMPLATES.map(t => ({
		key: t.key, name: t.name, description: t.description, slots: t.slots,
	})));
	ipcMain.handle(IPC.peerStart, (_e, title: string, task: string, templateKey: string, slots: any[], agentIds: string[]) => {
		// Form 3: the caller sends pre-filled slots; an empty list means "use the template as-is".
		const effective = Array.isArray(slots) && slots.length
			? slots.map((x, i) => ({
				key: String(x?.key ?? `slot${i}`), label: String(x?.label ?? `槽位${i + 1}`),
				prompt: String(x?.prompt ?? ''), color: String(x?.color ?? '#4C8DFF'),
				agentId: String(x?.agentId ?? agentIds?.[i] ?? ''),
			}))
			: templateSlots(String(templateKey ?? '')).map((x, i) => ({ ...x, agentId: String(agentIds?.[i] ?? '') }));
		const r = po().start(String(title ?? ''), String(task ?? ''), {
			templateKey: String(templateKey ?? 'custom'), slots: effective,
			agentIds: effective.map(s => s.agentId),
		});
		if (!r.group) {
			// Nothing runnable — do not leave an empty group behind.
			return { groupId: '', runIds: [], skipped: r.skipped };
		}
		return { groupId: r.group.id, runIds: r.runIds, skipped: r.skipped };
	});
	ipcMain.handle(IPC.peerPropose, (_e, groupId: string, agentId: string, text: string) =>
		po().propose(String(groupId), String(agentId), String(text ?? '')));
	ipcMain.handle(IPC.peerVote, (_e, groupId: string, voterId: string, targetAgentId: string, vote: number) =>
		po().vote(String(groupId), String(voterId), String(targetAgentId), Number(vote) || 0));
ipcMain.handle(IPC.peerSettle, (_e, groupId: string) => {
		// Nobody voted → each peer backs its own option, which for 2-3 peers means a tie.
		// That is intentional (Q3): we will not fabricate a majority on the user's behalf.
		if (!peerStore.votes(String(groupId)).length) { po().selfVote(String(groupId)); }
		return po().settle(String(groupId));
	});
	ipcMain.handle(IPC.peerView, (_e, groupId: string) => {
		const v = po().view(String(groupId));
		return { group: v.group, proposals: v.proposals };
	});
	ipcMain.handle(IPC.peerList, () => po().list());
	ipcMain.handle(IPC.peerRemove, (_e, groupId: string) => po().remove(String(groupId)));
	// loop guard / checkpoints (diagnostics + manual rewind from the renderer)
	ipcMain.handle(IPC.guardState, () => getAgent().guardState());
	ipcMain.handle(IPC.checkpointPush, (_e, label: string) => getAgent().pushCheckpoint(String(label ?? 'manual')));
	ipcMain.handle(IPC.checkpointList, () => getAgent().checkpointList());
	ipcMain.handle(IPC.checkpointRewind, async (_e, seq: number) => {
		const ok = await getAgent().rewindTo(Number(seq));
		if (ok) {
			rowBridge.replaceAll(normalizeRows(await sessions.loadRows(sessions.active())));
			await rowBridge.save();
		}
		return ok;
	});

	// ---- attachment picker (text files; binary rejected) ----
	ipcMain.handle(IPC.filePick, async () => {
		const win = BrowserWindow.getFocusedWindow() ?? mainWindow;
		const res = await dialog.showOpenDialog(win!, {
			title: '选择要附加的文件',
			properties: ['openFile']
		});
		if (res.canceled || !res.filePaths[0]) { return null; }
		const file = res.filePaths[0];
		try {
			const buf = fs.readFileSync(file);
			if (buf.includes(0)) { return null; } // binary
			const MAX = 12000;
			const text = buf.toString('utf8');
			return { name: path.basename(file), content: text.slice(0, MAX), truncated: text.length > MAX };
		} catch { return null; }
	});

	// ---- automation (60s sweep, phone AutomationWorker parity) ----
	const automation = new AutomationEngine(
		store,
		(system, user) => callNonStream(readSlot(), system, user),
		(title, body, error) => {
			if (Notification.isSupported()) {
				new Notification({ title, body, silent: !!error === false }).show();
			}
		},
		// M20-2: agent tasks run through the same AgentManager + workflow kernel the UI uses.
		// The session is the agent's OWN — see below, because this is the sharp edge.
		{
			resolve: resolveWorkflow,
			// Refuse before sessionFor() creates one — see checkTask's docstring on why a role
			// that cannot resolve must not cost an empty background session per tick.
			canResolveRole: (role) => { resolveRole(role); return true; },
			sessionFor: async (a, at) => {
				// A background run MUST NOT touch the user's active session: its turns would
				// appear in whatever conversation they happen to have open. So it gets a named
				// session that is created but never activated.
				const name = backgroundSessionName(a, at);
				const created = await sessions.create(name);
				return created.id;
			},
			run: async (wf, opts) => {
				const runner = agentStepRunner({
					start: (role, prompt) => am().submitPrimary(resolveRole(role), opts.sessionId, prompt).id,
					peek: (id) => {
						const r = am().getRun(id);
						return r ? { status: r.status, result: r.result, error: r.error } : undefined;
					},
					resolveRole,
				});
				const r = await runWorkflow(wf, runner, {
					runId: opts.runId,
					onStep: (e) => {
						for (const win of BrowserWindow.getAllWindows()) {
							if (!win.isDestroyed()) {
								win.webContents.send(CHAT_EVENTS.workflowEvent, {
									runId: opts.runId, stepId: e.stepId, role: e.role,
									status: e.status, error: e.error,
								});
							}
						}
					},
				});
				// The journal is kept so a scheduled run can be REPLAYED later — otherwise a job
				// that misbehaves while the user was asleep cannot be inspected at all.
				journals.add(r.journal);
				return r.journal;
			},
			nextRunId: () => journals.nextRunId(),
			onProgress: (a, entry) => {
				for (const win of BrowserWindow.getAllWindows()) {
					if (!win.isDestroyed()) {
						win.webContents.send(CHAT_EVENTS.workflowEvent, {
							runId: entry.runId ?? '', stepId: `automation:${a.id}`,
							role: 'automation', status: entry.ok ? 'done' : 'failed',
							error: entry.error,
						});
					}
				}
			},
		}
	);
	if (pluginOn('automation')) {
		void automation.start();
	}
	app.on('will-quit', () => automation.stop());

	ipcMain.handle(IPC.automationList, () => automation.list());
	ipcMain.handle(IPC.automationCreate, (_e, input: AutomationInput) => automation.create(input));
	ipcMain.handle(IPC.automationSetEnabled, (_e, id: number, enabled: boolean) => automation.setEnabled(id, enabled));

	// Patch an automation. Null means no such id, which the caller must not read as success —
	// the same silent-wrong-id failure automation_edit guards against on the phone side.
	ipcMain.handle(IPC.automationUpdate, (_e, id: number, patch: Partial<Automation>) =>
		automation.update(Number(id), patch ?? {}).then(a => a ? { ok: true as const, item: a } : { ok: false as const }));
	ipcMain.handle(IPC.automationRemove, (_e, id: number) => automation.remove(id));
	ipcMain.handle(IPC.automationLog, (_e, id: number) => automation.recentLog(id));
	// Structured task history: this is the one that carries runId back to the renderer, so
	// automationLog's markdown tail cannot stand in for it.
	ipcMain.handle(IPC.automationTaskLog, (_e, id: number, n?: number) =>
		automation.taskHistory(Number(id), Number.isFinite(Number(n)) ? Number(n) : undefined));

	// ---- F3: the floating window group ----
	// Built and registered BEFORE createWindow(), so every float:* handler exists by the time the
	// renderer loads. A renderer that boots into `float:state` first gets "No handler registered",
	// which looks like a broken feature rather than a race with startup.
	floatHost = new FloatHost({
		openMainWindow,
		// Hide, not destroy: "switch back" must be instant, and it is the tray — not a live
		// window — that holds the process while the body is stowed.
		hideMainWindow: () => { if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.hide(); } },
		readConfig: () => readConfigFile() as unknown as Record<string, unknown>,
		writeConfig: (patch) => writeConfig(patch as unknown as DesktopConfig),
		// The same directory the CU screenshots already use, so the two do not scatter shots.
		shotsDir: path.join(appDir(), 'cu'),
		// A turn streaming in the main window is what `enter` refuses to race against.
		isStreaming: () => running,
		// Shared with index.ts so both sides agree on whether we quit — see currentAliveState().
		aliveState: () => currentAliveState(),
	});
	floatHost.register();

	createWindow();

	app.on('activate', () => {
		if (BrowserWindow.getAllWindows().length === 0) { createWindow(); }
	});

	// ---- F0: tray keeps the app reachable when no window is on screen ----
	// The user's flow closes the main window and keeps working in the floating group. Without
	// a tray the process is invisible AND if a floater is then closed there is no way back
	// except killing the process, which reads as malware. So the tray is the way out.
	setupTray();
});

/** Roles of our own windows, for the keep-alive decision. */
function windowRoles(): AliveWindowRole[] {
	const roles: AliveWindowRole[] = [];
	const all = BrowserWindow.getAllWindows();
	if (mainWindow && !mainWindow.isDestroyed() && all.includes(mainWindow)) { roles.push('main'); }
	// The focus capsule is an always-on-top window that is not one of ours to identify by role.
	const cap = capsuleWinRef();
	if (cap && !cap.isDestroyed()) { roles.push('capsule'); }
	// The panes are FloatHost's, so only FloatHost can say whether they are open. This used to
	// read a local `floatWins` record that nothing ever wrote — see floatModeActive() below for
	// what that cost.
	if (floatHost) {
		for (const role of floatHost.openPaneRoles) { roles.push(`float-${role}`); }
		if (floatHost.overlayOpen) { roles.push('overlay'); }
	}
	return roles;
}

/** Is the floating group engaged — stowed, or out and being used? */
function floatModeActive(): boolean {
	// 'docked' is the only idle phase; 'floating' and 'floating-streaming' both mean the group
	// is what the user is working in, and the main window may be gone.
	return floatHost ? floatHost.currentMode.phase !== 'docked' : false;
}

function currentAliveState() {
	return {
		floatMode: floatModeActive(),
		open: windowRoles(),
		trayVisible: !!tray,
		quitRequested: quitRequested,
		shuttingDown: appShuttingDown,
	};
}

let tray: Tray | null = null;
let quitRequested = false;
let appShuttingDown = false;

/**
 * The floating group's owner. Assigned once, while whenReady wires the app up.
 *
 * The keep-alive policy reads IT rather than a local record, because the record it used to read
 * (`floatWins`) was declared "F3 will own these" and then nothing ever wrote to it. Every read
 * therefore said no: `floatMode` was permanently false and no `float-*` role was ever reported,
 * so destroying the main window while floating out of an empty list produced `no-windows-left`
 * and quit the process — taking the floating group and the user's session with it. Whoever owns
 * the windows is the one that has to answer for them.
 */
let floatHost: FloatHost | null = null;
/** Set by the capsule handler; the capsule's own variable is block-scoped. */
let capsuleWinRef: () => BrowserWindow | null = () => null;

function setupTray(): void {
	if (tray) { return; }
	try {
		const img = nativeImage.createFromDataURL(trayIconDataUrl(16));
		tray = new Tray(img.isEmpty() ? nativeImage.createEmpty() : img);
	} catch (e) {
		// No tray means the user can lose the app. Fall back to quitting with the last window,
		// which is the pre-existing behaviour, and say why in the log.
		console.error('[tray] unavailable —', (e as Error)?.message ?? e);
		tray = null;
		return;
	}
	const rebuild = () => {
		const st = currentAliveState();
		try {
			tray!.setToolTip(trayTooltip(st));
			tray!.setContextMenu(Menu.buildFromTemplate([
				{ label: trayStatusLine(st), enabled: false },
				{ type: 'separator' },
				{ label: '打开主窗口', click: () => openMainWindow() },
				{ type: 'separator' },
				{ label: '退出 Planability', click: () => { quitRequested = true; app.quit(); } },
			]));
		} catch (e) {
			console.error('[tray] menu rebuild failed —', (e as Error)?.message ?? e);
		}
	};
	tray.on('click', () => openMainWindow());
	// Registered here rather than in setupTray: openMainWindow can create a NEW mainWindow,
	// and a listener bound to the old one would silently stop refreshing the menu.
	app.on('browser-window-focus', refreshTray);
	mainWindow?.on('show', refreshTray);
	mainWindow?.on('hide', refreshTray);
	mainWindow?.on('closed', refreshTray);
	refreshTray();
}

function refreshTray(): void {
	if (!tray || tray.isDestroyed()) { return; }
	const st = currentAliveState();
	try {
		tray.setToolTip(trayTooltip(st));
		tray.setContextMenu(Menu.buildFromTemplate([
			{ label: trayStatusLine(st), enabled: false },
			{ type: 'separator' },
			{ label: '打开主窗口', click: () => openMainWindow() },
			{ type: 'separator' },
			{ label: '退出 Planability', click: () => { quitRequested = true; app.quit(); } },
		]));
	} catch (e) {
		console.error('[tray] menu refresh failed —', (e as Error)?.message ?? e);
	}
}

/** Bring the main window back from the tray, creating it if it was destroyed. */
function openMainWindow(): void {
	if (!mainWindow || mainWindow.isDestroyed()) {
		createWindow();
		return;
	}
	if (mainWindow.isMinimized()) { mainWindow.restore(); }
	mainWindow.show();
	mainWindow.focus();
}

app.on('window-all-closed', () => {
	// Decide via the tested policy rather than an inline `app.quit()`. In floating mode the
	// group is the app; quitting here would delete the user's work with no trace.
	const decision = decideKeepAlive(currentAliveState());
	if (decision.quit && process.platform !== 'darwin') { app.quit(); }
	else { console.log('[keepalive] staying alive —', decision.reason); }
});

app.on('before-quit', () => { appShuttingDown = true; });
