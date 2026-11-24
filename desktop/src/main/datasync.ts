/* PC ⇄ phone data sync over Syncthing — the shared-folder contract both apps use:
 *
 *   <sync folder>/pc-dump.json     written by this app (dump v1, atomic tmp+rename)
 *   <sync folder>/phone-dump.json  written by the phone app, merged here on arrival
 *
 * Merge semantics are the existing dump v1 rule (record-level newer updatedAt wins),
 * the same code path as the 文件页 import. The folder lives at
 * %APPDATA%\planability-desktop\sync-data and is shared with every configured device. */

import * as fs from 'node:fs';
import * as path from 'node:path';
import type { DataSyncStatus } from '../../shared/ipc';
import type { Store } from './store/store';
import type { SyncthingEngine } from './syncthing';

export const DATA_FOLDER_ID = 'planability-data';
const EXPORT_FILE = 'pc-dump.json';
const IMPORT_FILE = 'phone-dump.json';
const TICK_MS = 60_000;

interface PersistedState {
	lastExportAt?: number;
	lastImportAt?: number;
	lastImportMtime?: number;
	lastImportCount?: number;
}

export class DataSyncService {
	private enabled = false;
	private ensured = false;
	private exportTimer: NodeJS.Timeout | null = null;
	private importTimer: NodeJS.Timeout | null = null;
	private lastExportPayload = '';
	private error: string | undefined;
	private state: PersistedState = {};

	constructor(
		private readonly store: Store,
		private readonly engine: SyncthingEngine,
		private readonly dir: string
	) { }

	private get exportPath(): string { return path.join(this.dir, EXPORT_FILE); }
	private get importPath(): string { return path.join(this.dir, IMPORT_FILE); }

	/** Boot: restore the persisted switch; if on, wire loops (folder ensured lazily). */
	async init(): Promise<void> {
		try {
			this.enabled = (await this.store.kvGet('sync.dataEnabled')) === '1';
			const raw = await this.store.kvGet('sync.dataState');
			if (raw) { this.state = JSON.parse(raw) as PersistedState; }
		} catch { /* first run */ }
		if (this.enabled) { this.startLoops(); }
	}

	async status(): Promise<DataSyncStatus> {
		return {
			enabled: this.enabled,
			dir: this.dir,
			folderId: DATA_FOLDER_ID,
			shared: await this.isConfigured(),
			lastExportAt: this.state.lastExportAt ?? 0,
			lastImportAt: this.state.lastImportAt ?? 0,
			lastImportCount: this.state.lastImportCount ?? 0,
			error: this.error
		};
	}

	async setEnabled(on: boolean): Promise<DataSyncStatus> {
		this.enabled = on;
		await this.store.kvPut('sync.dataEnabled', on ? '1' : '0');
		if (on) {
			this.startLoops();
			await this.run('export').catch(() => undefined);
			await this.run('import').catch(() => undefined);
		} else {
			this.stopLoops();
		}
		return this.status();
	}

	async run(kind: 'export' | 'import'): Promise<DataSyncStatus> {
		if (!this.enabled) { return this.status(); }
		try {
			await this.ensureFolder();
			if (kind === 'export') { await this.exportOnce(); } else { await this.importOnce(); }
			this.error = undefined;
		} catch (e) {
			this.error = e instanceof Error ? e.message : String(e);
		}
		await this.persist();
		return this.status();
	}

	// ---- syncthing folder ----

	private async isConfigured(): Promise<boolean> {
		try {
			const cfg = await this.engine.configGet();
			return !!cfg.folders?.some(f => f.id === DATA_FOLDER_ID);
		} catch { return false; }
	}

	/** Create (or repair) the data folder and share it with every device. */
	private async ensureFolder(): Promise<void> {
		if (this.ensured && await this.isConfigured()) { return; }
		fs.mkdirSync(this.dir, { recursive: true });
		const cfg = await this.engine.configGet();
		const devices = (cfg.devices ?? []).map(d => ({ deviceID: d.deviceID }));
		const existing = cfg.folders?.find(f => f.id === DATA_FOLDER_ID);
		await this.engine.putFolder({
			id: DATA_FOLDER_ID,
			label: 'Planability 数据',
			path: this.dir,
			type: 'sendreceive',
			rescanIntervalS: 60,
			fsWatcherEnabled: true,
			...(existing ?? {}),
			devices
		});
		this.ensured = true;
	}

	// ---- transfer ----

	private async exportOnce(): Promise<void> {
		const dump = await this.store.exportDump();
		const payload = JSON.stringify(dump, null, 1);
		if (payload === this.lastExportPayload) { return; }
		// compare with what is already on disk (session restart / external edits)
		let onDisk = '';
		try { onDisk = fs.readFileSync(this.exportPath, 'utf8'); } catch { /* not yet */ }
		if (payload === onDisk) { this.lastExportPayload = payload; return; }
		fs.mkdirSync(this.dir, { recursive: true });
		const tmp = this.exportPath + '.tmp';
		fs.writeFileSync(tmp, payload, 'utf8');
		fs.renameSync(tmp, this.exportPath); // atomic — the peer never sees a half file
		this.lastExportPayload = payload;
		this.state.lastExportAt = Date.now();
	}

	private async importOnce(): Promise<void> {
		let mtime = 0;
		try { mtime = fs.statSync(this.importPath).mtimeMs; } catch { return; } // no phone dump yet
		if (mtime <= (this.state.lastImportMtime ?? 0)) { return; }
		const raw = fs.readFileSync(this.importPath, 'utf8');
		const merged = await this.store.mergeDump(JSON.parse(raw));
		this.state.lastImportMtime = mtime;
		this.state.lastImportAt = Date.now();
		this.state.lastImportCount = merged;
	}

	// ---- loops ----

	private startLoops(): void {
		this.stopLoops();
		this.exportTimer = setInterval(() => { void this.run('export'); }, TICK_MS);
		this.importTimer = setInterval(() => { void this.run('import'); }, TICK_MS);
	}

	private stopLoops(): void {
		if (this.exportTimer) { clearInterval(this.exportTimer); this.exportTimer = null; }
		if (this.importTimer) { clearInterval(this.importTimer); this.importTimer = null; }
	}

	private async persist(): Promise<void> {
		try { await this.store.kvPut('sync.dataState', JSON.stringify(this.state)); } catch { /* best effort */ }
	}

	shutdown(): void {
		this.stopLoops();
	}
}
