/* Syncthing integration — PC edition of the phone's SyncthingEngine.
 *
 * Two ways in (tried in this order):
 *   1. attach  — something already answers 127.0.0.1:8384; we discover its API key
 *                (our own key file, then any local config.xml) and drive it over REST.
 *   2. spawn   — we start our own kernel with `serve --home <app dir> --gui-apikey <our key>`.
 * Config + index live in %APPDATA%\planability-desktop\syncthing, the kernel binary in
 * %APPDATA%\planability-desktop\syncthing-bin (downloaded on demand, or a path the user picks).
 * Lifecycle parity with the phone: our own kernel dies with the app. */

import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import * as crypto from 'node:crypto';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import type { StConfig, StDeviceConfig, StError, StFolderConfig, StLogLine, StPending, StSystemInfo, SyncDevice, SyncFolder, SyncStatus, SyncSummary } from '../../shared/ipc';

export const GUI_HOST = '127.0.0.1';
export const GUI_PORT = 8384;

const KEY_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

interface RawFolder {
	id?: string;
	label?: string;
	path?: string;
}

interface RawDevice {
	deviceID?: string;
	name?: string;
}

/** Boundary check handed to every process spawn: absolute path, expected name, no traversal. */
function assertSafeExe(p: string): string {
	const abs = path.resolve(p);
	if (abs.split(path.sep).includes('..') || path.basename(abs).toLowerCase() !== 'syncthing.exe') {
		throw new Error(`内核必须是 syncthing.exe（当前路径：${p}）`);
	}
	return abs;
}

export class SyncthingEngine {
	private proc: ChildProcess | null = null;
	private mode: SyncStatus['mode'] = 'stopped';
	private myId = '';
	private version = '';
	private error: string | undefined;
	private binaryPath = '';
	private binaryResolved = false;
	/** in-flight start attempt (UI button and app-boot share one attempt) */
	private starting: Promise<SyncStatus> | null = null;
	/** Key that unlocks OUR kernel (spawns are started with it). */
	private ownKeyCache = '';
	/** Key currently in use for REST (ours, or the external instance's). */
	private activeKey = '';

	constructor(
		private readonly home: string,
		private readonly binDir: string,
		private readonly configuredBinary: () => string | undefined
	) {
		fs.mkdirSync(home, { recursive: true });
		fs.mkdirSync(binDir, { recursive: true });
	}

	private get keyFile(): string { return path.join(this.home, 'apikey.txt'); }
	private get logFile(): string { return path.join(this.home, 'syncthing.log'); }

	private ownKey(): string {
		if (this.ownKeyCache) { return this.ownKeyCache; }
		try {
			const t = fs.readFileSync(this.keyFile, 'utf8').trim();
			if (t) { this.ownKeyCache = t; return t; }
		} catch { /* generate below */ }
		const buf = crypto.randomBytes(48);
		let token = '';
		for (let i = 0; i < 40; i++) { token += KEY_ALPHABET[buf[i] % KEY_ALPHABET.length]; }
		fs.writeFileSync(this.keyFile, token, 'utf8');
		this.ownKeyCache = token;
		return token;
	}

	// ---------- 完整模式（原版 GUI） ----------

	/**
	 * Where the original Syncthing web GUI listens, and the key that unlocks it.
	 *
	 * WHY THE KEY HAS TO LEAVE THIS CLASS: "完整模式" opens the real GUI instead of our
	 * re-implementation. The GUI authenticates every REST call with `X-API-Key`, and the
	 * served HTML carries no key in it (checked: the index page has no apiKey anywhere —
	 * the GUI asks the browser for one instead), so a window that just loads the URL would
	 * sit at a "API key" prompt. Making that work without a prompt means injecting the header
	 * ourselves, which means the caller needs the key.
	 * It is exposed ONLY to the main process (never via preload/renderer): index.ts injects
	 * it with webRequest on the GUI window's own session, so the renderer still never sees it.
	 */
	guiEndpoint(): { url: string; apiKey: string } {
		return {
			url: `http://${GUI_HOST}:${GUI_PORT}/`,
			apiKey: this.activeKey || this.ownKey(),
		};
	}

	// ---------- REST ----------

	private async restWith(key: string, method: string, p: string, body?: string): Promise<string> {
		const res = await fetch(`http://${GUI_HOST}:${GUI_PORT}${p}`, {
			method,
			headers: {
				'X-API-Key': key,
				...(body ? { 'Content-Type': 'application/json' } : {})
			},
			body,
			signal: AbortSignal.timeout(8000)
		});
		const text = await res.text();
		if (!res.ok) { throw new Error(`REST ${res.status}：${text.slice(0, 200)}`); }
		return text;
	}

	private rest(method: string, p: string, body?: string): Promise<string> {
		return this.restWith(this.activeKey || this.ownKey(), method, p, body);
	}

	/** API keys of Syncthing instances living on this machine (ours is always first). */
	private candidateKeys(): string[] {
		const found: string[] = [this.ownKey()];
		const homes = [process.env.LOCALAPPDATA, process.env.APPDATA]
			.filter(Boolean)
			.map(b => path.join(b as string, 'Syncthing', 'config.xml'));
		homes.push(path.join(this.home, 'config.xml'));
		for (const file of homes) {
			try {
				const xml = fs.readFileSync(file, 'utf8');
				for (const m of xml.matchAll(/<apikey>([^<]+)<\/apikey>/g)) { found.push(m[1].trim()); }
			} catch { /* no such instance */ }
		}
		return [...new Set(found)];
	}

	private async probe(): Promise<boolean> {
		for (const key of this.candidateKeys()) {
			try {
				const raw = await this.restWith(key, 'GET', '/rest/system/status');
				const myID = (JSON.parse(raw) as { myID?: string }).myID;
				if (myID) {
					this.activeKey = key;
					this.myId = myID;
					this.error = undefined;
					return true;
				}
			} catch { /* try next key */ }
		}
		return false;
	}

	/** After a successful probe: is the answering kernel ours or someone else's? */
	private modeFromProbe(): SyncStatus['mode'] {
		const childAlive = !!this.proc && this.proc.exitCode === null;
		return childAlive || this.activeKey === this.ownKey() ? 'own' : 'attached';
	}

	private async readVersion(): Promise<string> {
		try {
			const raw = await this.rest('GET', '/rest/system/version');
			return (JSON.parse(raw) as { version?: string }).version ?? '';
		} catch { return ''; }
	}

	/** Any HTTP answer on our GUI port means a listener is there; our own kernel would
	 *  have answered probe() with our key, so a surviving answer is someone else's.
	 *  Deliberately header-less: the status endpoint rejects unauthenticated callers
	 *  with 401 — which is exactly the "someone answers" signal we want. */
	private async foreignListener(): Promise<boolean> {
		try {
			await fetch(`http://${GUI_HOST}:${GUI_PORT}/rest/system/status`, {
				signal: AbortSignal.timeout(3000)
			});
			return true;
		} catch { return false; }
	}

	// ---------- binary ----------

	private resolveBinary(): string {
		const cands: string[] = [];
		const cfg = this.configuredBinary();
		if (cfg) { cands.push(cfg); }
		cands.push(path.join(this.binDir, 'syncthing.exe'));
		for (const base of [process.env.LOCALAPPDATA, process.env.APPDATA, process.env.ProgramFiles, process.env['ProgramFiles(x86)']]) {
			if (!base) { continue; }
			cands.push(path.join(base, 'Syncthing', 'syncthing.exe'));
			cands.push(path.join(base, 'Programs', 'Syncthing', 'syncthing.exe'));
		}
		for (const c of cands) { if (c && fs.existsSync(c)) { return c; } }
		try {
			const r = spawnSync('where.exe', ['syncthing.exe'], { encoding: 'utf8', windowsHide: true, shell: false });
			const hit = (r.stdout ?? '').split(/\r?\n/).map(s => s.trim()).find(s => s && fs.existsSync(s));
			if (hit) { return hit; }
		} catch { /* where.exe unavailable */ }
		return '';
	}

	/** Download the official Windows build (GitHub release) into binDir. */
	async install(): Promise<string> {
		const api = 'https://api.github.com/repos/syncthing/syncthing/releases/latest';
		const res = await fetch(api, { headers: { 'User-Agent': 'Planability' }, signal: AbortSignal.timeout(20_000) });
		if (!res.ok) { throw new Error(`GitHub API ${res.status}（可能是网络或限流）`); }
		const rel = await res.json() as { assets?: { name: string; browser_download_url: string }[] };
		const asset = rel.assets?.find(a => /^syncthing-windows-amd64.*\.zip$/i.test(a.name));
		if (!asset) { throw new Error('发布页未找到 syncthing-windows-amd64*.zip'); }
		// asset.name is external input — basename only, never a traversal segment.
		const zipName = path.basename(asset.name);
		if (!zipName.toLowerCase().endsWith('.zip')) { throw new Error('非法的资源文件名'); }

		const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'st-install-'));
		const zip = path.join(tmp, zipName);
		const dl = await fetch(asset.browser_download_url, { headers: { 'User-Agent': 'Planability' }, signal: AbortSignal.timeout(300_000) });
		if (!dl.ok) { throw new Error(`下载失败 HTTP ${dl.status}`); }
		fs.writeFileSync(zip, Buffer.from(await dl.arrayBuffer()));

		// Windows ships bsdtar (zip capable) — argv list, no shell, command is a constant.
		const untar = spawnSync('tar', ['-xf', zip, '-C', tmp], { encoding: 'utf8', windowsHide: true, shell: false });
		if (untar.status !== 0) { throw new Error(`解压失败：${String(untar.stderr).slice(0, 200)}`); }

		const exe = this.findExe(tmp);
		if (!exe) { throw new Error('压缩包里没有 syncthing.exe'); }
		const target = assertSafeExe(path.join(this.binDir, 'syncthing.exe'));
		fs.copyFileSync(exe, target);
		fs.rmSync(tmp, { recursive: true, force: true });
		return target;
	}

	private findExe(dir: string): string {
		const entries = fs.readdirSync(dir, { withFileTypes: true });
		for (const e of entries) {
			const full = path.join(dir, e.name);
			if (e.isFile() && e.name.toLowerCase() === 'syncthing.exe') { return assertSafeExe(full); }
			if (e.isDirectory()) {
				const found = this.findExe(full);
				if (found) { return found; }
			}
		}
		return '';
	}

	// ---------- lifecycle ----------

	async status(): Promise<SyncStatus> {
		const running = await this.probe();
		if (!running) {
			if (this.mode === 'own') { this.mode = 'stopped'; }
			this.myId = '';
		} else {
			// answering + (our child alive OR answering with OUR key — the latter means a
			// kernel we own that re-exec'd itself on restart and is no longer our child)
			this.mode = this.modeFromProbe();
		}
		if (running && !this.version) { this.version = await this.readVersion(); }
		if (!this.binaryResolved) {
			this.binaryPath = this.resolveBinary();
			this.binaryResolved = true;
		}
		return {
			running,
			mode: running ? this.mode : 'stopped',
			myId: this.myId,
			version: this.version,
			binaryPath: this.binaryPath,
			error: this.error
		};
	}

	/** UI start: attach to a live instance if one answers; otherwise ensure a kernel
	 *  (auto-download when none is installed) and spawn our own. Concurrent calls share one attempt. */
	start(opts: { autoInstall?: boolean } = {}): Promise<SyncStatus> {
		if (this.starting) { return this.starting; }
		this.starting = this.doStart(opts).finally(() => { this.starting = null; });
		return this.starting;
	}

	/** App-boot path: bring sync up only when a kernel already exists — never downloads
	 *  at launch (first-time setup stays on-demand in 同步中心). */
	async startIfAvailable(): Promise<void> {
		if (await this.probe()) {
			this.mode = this.modeFromProbe();
			this.version = await this.readVersion();
			return;
		}
		if (!this.resolveBinary()) { return; }
		await this.start();
	}

	private async doStart(opts: { autoInstall?: boolean }): Promise<SyncStatus> {
		this.error = undefined;
		if (await this.probe()) {
			this.mode = this.modeFromProbe();
			this.version = await this.readVersion();
			return this.status();
		}
		let bin = this.resolveBinary();
		if (!bin && opts.autoInstall) {
			try {
				bin = await this.install();
			} catch (e) {
				this.error = `内核下载失败：${e instanceof Error ? e.message : String(e)}`;
				return this.status();
			}
		}
		if (!bin) {
			this.error = '未检测到 Syncthing 内核——点「下载内核并启动」自动安装，或「指定内核路径」选择已有的 syncthing.exe。';
			return this.status();
		}
		this.binaryPath = bin;
		this.binaryResolved = true;
		try {
			const exe = assertSafeExe(bin);
			const logFd = fs.openSync(this.logFile, 'a');
			fs.writeSync(logFd, `\n---- ${new Date().toISOString()} launch (${exe}) ----\n`);
			// Launch by the constant name `syncthing.exe` with the resolved directory placed
			// first on PATH (Windows resolves bare names through PATH, parent and child env
			// alike). argv is a pre-built list, shell stays off — no command string exists.
			const exeDir = path.dirname(exe);
			const pathVar = exeDir + ';' + (process.env.PATH ?? '');
			process.env.PATH = pathVar;
			const flagHome = '--home=' + this.home;
			const flagAddress = '--gui-address=' + GUI_HOST + ':' + String(GUI_PORT);
			const flagAccess = '--gui-apikey=' + this.ownKey();
			const argv = ['serve', flagHome, flagAddress, flagAccess, '--no-browser', '--no-console', '--no-upgrade'];
			const childEnv: NodeJS.ProcessEnv = {
				...process.env,
				PATH: pathVar,
				HOME: this.home,
				STNOUPGRADE: '1',
				STNOBROWSER: '1'
			};
			const proc = spawn('syncthing.exe', argv, {
				windowsHide: true,
				shell: false,
				stdio: ['ignore', 'pipe', 'pipe'],
				env: childEnv
			});
			this.proc = proc;
			const drain = (chunk: Buffer): void => { try { fs.writeSync(logFd, chunk.toString('utf8')); } catch { /* closed */ } };
			proc.stdout?.on('data', drain);
			proc.stderr?.on('data', drain);
			proc.on('error', err => {
				this.error = `内核启动失败：${err.message}`;
				this.mode = 'stopped';
			});
			proc.on('exit', code => {
				try { fs.closeSync(logFd); } catch { /* already closed */ }
				if (this.proc === proc) {
					this.proc = null;
					if (this.mode === 'own' && code !== 0 && !this.error) {
						this.error = `内核异常退出（code ${code}），日志见 syncthing.log`;
					}
					if (this.mode === 'own') { this.mode = 'stopped'; }
				}
			});
			this.mode = 'own';
			// ready poll: 45s like the phone engine
			for (let i = 0; i < 90; i++) {
				await new Promise(r => setTimeout(r, 500));
				if (this.proc !== proc) { break; } // died
				if (await this.probe()) {
					this.mode = 'own';
					this.version = await this.readVersion();
					return this.status();
				}
			}
			if (this.proc === proc) {
				// timeout: name the likely cause (the standalone copy that is slated for removal)
				const foreign = await this.foreignListener();
				this.error = foreign
					? '本机 8384 端口被占用（多半是仍存在的独立版 Syncthing）——删除或退出它后重试，之后将一直使用内置内核。'
					: '内核启动超时（45s）——可在同步中心查看日志尾部。';
				try { proc.kill(); } catch { /* already gone */ }
				this.proc = null;
				this.mode = 'stopped';
			}
		} catch (e) {
			this.error = `启动失败：${e instanceof Error ? e.message : String(e)}`;
			this.mode = 'stopped';
		}
		return this.status();
	}

	async stop(): Promise<SyncStatus> {
		// Only ever shut down a kernel that answers with OUR key — an external
		// user-owned instance is left running (we merely detach from it).
		const ours = this.activeKey === this.ownKey();
		if (ours) {
			try { await this.rest('POST', '/rest/system/shutdown'); } catch { /* fall through to kill */ }
			const proc = this.proc;
			if (proc) {
				await new Promise<void>(resolve => {
					const timer = setTimeout(() => { try { proc.kill(); } catch { /* gone */ } resolve(); }, 3000);
					proc.once('exit', () => { clearTimeout(timer); resolve(); });
				});
			}
		}
		this.proc = null;
		this.mode = 'stopped';
		this.myId = '';
		this.version = '';
		this.activeKey = '';
		return this.status();
	}

	// ---------- config ----------

	async summary(): Promise<SyncSummary> {
		const st = await this.status();
		if (!st.running) { return { ...st, folders: [], devices: [] }; }
		const [foldersRaw, devicesRaw, connsRaw] = await Promise.all([
			this.rest('GET', '/rest/config/folders').catch(() => '[]'),
			this.rest('GET', '/rest/config/devices').catch(() => '[]'),
			this.rest('GET', '/rest/system/connections').catch(() => '{}')
		]);
		let conns: Record<string, { connected?: boolean }> = {};
		try { conns = (JSON.parse(connsRaw) as { connections?: Record<string, { connected?: boolean }> }).connections ?? {}; } catch { /* empty */ }

		const folders: SyncFolder[] = [];
		for (const o of this.jsonArray<RawFolder>(foldersRaw)) {
			if (!o.id) { continue; }
			const db = await this.folderStatus(o.id);
			folders.push({
				id: o.id,
				label: (o.label || o.id).trim(),
				path: o.path ?? '',
				state: db.state,
				progress: db.progress
			});
		}
		const devices: SyncDevice[] = this.jsonArray<RawDevice>(devicesRaw)
			.filter(d => d.deviceID)
			.map(d => ({
				id: d.deviceID as string,
				name: (d.name || '').trim() || '未命名设备',
				connected: conns[d.deviceID as string]?.connected === true
			}));
		return { ...st, folders, devices };
	}

	private jsonArray<T>(raw: string): T[] {
		try {
			const v = JSON.parse(raw);
			return Array.isArray(v) ? v as T[] : [];
		} catch { return []; }
	}

	private async folderStatus(id: string): Promise<{ state: string; progress: number }> {
		const enc = encodeURIComponent(id);
		for (const p of [`/rest/db/status?folder=${enc}`, `/rest/db?folder=${enc}`]) {
			try {
				const raw = await this.rest('GET', p);
				const o = JSON.parse(raw) as { state?: string; globalBytes?: number; needBytes?: number; inSyncBytes?: number };
				const global = Number(o.globalBytes ?? 0);
				const need = Number(o.needBytes ?? 0);
				const inSync = typeof o.inSyncBytes === 'number' ? Number(o.inSyncBytes) : Math.max(0, global - need);
				const progress = global > 0 ? Math.min(1, Math.max(0, inSync / global)) : 1;
				return { state: String(o.state ?? '-'), progress };
			} catch { /* try the other endpoint */ }
		}
		return { state: '-', progress: 0 };
	}

	async addDevice(deviceId: string, name: string): Promise<void> {
		const id = deviceId.trim();
		if (id.length < 7) { throw new Error('设备 ID 无效（至少 7 位）'); }
		await this.rest('PUT', `/rest/config/devices/${encodeURIComponent(id)}`, JSON.stringify({
			deviceID: id,
			name: name.trim(),
			compression: 'metadata',
			introducer: false
		}));
		await this.applyConfig();
	}

	async addFolder(label: string, folderPath: string): Promise<void> {
		const devicesRaw = await this.rest('GET', '/rest/config/devices');
		const self = this.myId;
		const devices = this.jsonArray<RawDevice>(devicesRaw)
			.filter(d => d.deviceID && d.deviceID !== self)
			.map(d => ({ deviceID: d.deviceID }));
		const id = `plan-${Math.floor(Date.now() / 1000)}`;
		await this.rest('PUT', `/rest/config/folders/${encodeURIComponent(id)}`, JSON.stringify({
			id,
			label: label.trim(),
			path: folderPath.trim(),
			type: 'sendreceive',
			rescanIntervalS: 60,
			fsWatcherEnabled: true,
			devices
		}));
		await this.applyConfig();
	}

	async removeFolder(id: string): Promise<void> {
		try {
			await this.rest('DELETE', `/rest/config/folders/${encodeURIComponent(id)}`);
			return;
		} catch { /* older builds: rebuild the config without it */ }
		const all = JSON.parse(await this.rest('GET', '/rest/config')) as { folders?: RawFolder[] };
		all.folders = (all.folders ?? []).filter(f => f.id !== id);
		await this.rest('POST', '/rest/system/config?set=1', JSON.stringify(all));
	}

	// ---- targeted config edits (dialogs read-modify-write a single entity) ----

	async putDevice(cfg: StDeviceConfig): Promise<void> {
		if (!cfg.deviceID) { throw new Error('缺少设备 ID'); }
		await this.rest('PUT', `/rest/config/devices/${encodeURIComponent(cfg.deviceID)}`, JSON.stringify(cfg));
		await this.applyConfig();
	}

	async deleteDevice(deviceID: string): Promise<void> {
		await this.rest('DELETE', `/rest/config/devices/${encodeURIComponent(deviceID)}`);
		await this.applyConfig();
	}

	async putFolder(cfg: StFolderConfig): Promise<void> {
		if (!cfg.id) { throw new Error('缺少文件夹 ID'); }
		await this.rest('PUT', `/rest/config/folders/${encodeURIComponent(cfg.id)}`, JSON.stringify(cfg));
		await this.applyConfig();
	}

	/** Older kernels do not auto-apply PUT /rest/config/* — harmless if it fails. */
	private async applyConfig(): Promise<void> {
		try {
			const cfg = await this.rest('GET', '/rest/config');
			await this.rest('POST', '/rest/system/config?set=1', cfg);
		} catch { /* auto-applied already */ }
	}

	// ---------- full management surface (web-GUI parity, in-app) ----------

	async configGet(): Promise<StConfig> {
		return JSON.parse(await this.rest('GET', '/rest/config')) as StConfig;
	}

	/** Whole-config save (raw JSON editor). The official GUI uses PUT /rest/config —
	 *  POST is rejected with 405 on v2, so match the GUI exactly. */
	async configPut(cfg: StConfig): Promise<void> {
		await this.rest('PUT', '/rest/config', JSON.stringify(cfg));
	}

	/** Form defaults straight from the kernel — guaranteed-valid base for add dialogs. */
	async defaults(kind: 'folder' | 'device'): Promise<StFolderConfig | StDeviceConfig> {
		return JSON.parse(await this.rest('GET', `/rest/config/defaults/${kind}`));
	}

	/** Server-side directory listing for the folder path field. */
	async browse(current: string): Promise<string[]> {
		const raw = await this.rest('GET', `/rest/system/browse?current=${encodeURIComponent(current)}`);
		return this.jsonArray<string>(raw);
	}

	async ignoresGet(folderId: string): Promise<string[]> {
		const raw = await this.rest('GET', `/rest/db/ignores?folder=${encodeURIComponent(folderId)}`);
		try {
			const j = JSON.parse(raw) as { ignore?: string[] | null };
			return Array.isArray(j.ignore) ? j.ignore : [];
		} catch { return []; }
	}

	async ignoresPut(folderId: string, patterns: string[]): Promise<void> {
		await this.rest('POST', `/rest/db/ignores?folder=${encodeURIComponent(folderId)}`, JSON.stringify({ ignore: patterns }));
	}

	/** Offers not yet accepted (incoming folder shares, introductions). */
	async pending(): Promise<StPending> {
		const [d, f] = await Promise.all([
			this.rest('GET', '/rest/cluster/pending/devices').catch(() => '{}'),
			this.rest('GET', '/rest/cluster/pending/folders').catch(() => '{}')
		]);
		const parse = (raw: string): Record<string, never> => {
			try { const v = JSON.parse(raw); return v && typeof v === 'object' ? v as Record<string, never> : {}; } catch { return {}; }
		};
		return {
			devices: parse(d) as unknown as StPending['devices'],
			folders: parse(f) as unknown as StPending['folders']
		};
	}

	async decline(target: { device?: string; folder?: string; from?: string }): Promise<void> {
		if (target.folder) {
			const from = target.from ? `&device=${encodeURIComponent(target.from)}` : '';
			await this.rest('DELETE', `/rest/cluster/pending/folders?folder=${encodeURIComponent(target.folder)}${from}`);
			return;
		}
		if (target.device) {
			await this.rest('DELETE', `/rest/cluster/pending/devices?device=${encodeURIComponent(target.device)}`);
		}
	}

	async scan(folderId: string): Promise<void> {
		await this.rest('POST', `/rest/db/scan?folder=${encodeURIComponent(folderId)}`);
	}

	async systemInfo(): Promise<StSystemInfo> {
		const [statusRaw, versionRaw] = await Promise.all([
			this.rest('GET', '/rest/system/status'),
			this.rest('GET', '/rest/system/version').catch(() => '{}')
		]);
		const s = JSON.parse(statusRaw) as { myID?: string; uptime?: number; startTime?: string };
		let version = '';
		try { version = String((JSON.parse(versionRaw) as { version?: string }).version ?? ''); } catch { /* keep empty */ }
		return { myID: String(s.myID ?? ''), uptime: Number(s.uptime ?? 0), startTime: s.startTime, version };
	}

	async systemLog(limit = 300): Promise<StLogLine[]> {
		const raw = await this.rest('GET', '/rest/system/log?since=0');
		try {
			const j = JSON.parse(raw) as { messages?: StLogLine[] };
			return (j.messages ?? []).slice(-limit);
		} catch { return []; }
	}

	async errors(): Promise<StError[]> {
		const raw = await this.rest('GET', '/rest/system/error');
		try {
			const j = JSON.parse(raw) as { errors?: StError[] | null };
			return Array.isArray(j.errors) ? j.errors : [];
		} catch { return []; }
	}

	async clearErrors(): Promise<void> {
		await this.rest('POST', '/rest/system/error/clear');
	}

	/** Restart the kernel we talk to (own or attached instance re-execs itself). */
	async restart(): Promise<void> {
		await this.rest('POST', '/rest/system/restart');
	}

	logTail(chars = 3000): string {
		try { return fs.readFileSync(this.logFile, 'utf8').slice(-chars); } catch { return ''; }
	}

	/** App is quitting: tear down a kernel we own (phone parity: lifecycle follows the app). */
	shutdown(): void {
		if (this.proc) {
			try { this.proc.kill(); } catch { /* already gone */ }
			this.proc = null;
		}
	}
}
