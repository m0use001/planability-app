/* Undo stack for write tools (phone PTL undo_last_write parity).
 * A full-collection snapshot is pushed BEFORE every mutating tool runs; undo pops the
 * latest snapshot and restores the collection. Stack persists (depth 10). */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type { CollectionName, Entity } from '../../../shared/types';
import type { Store } from '../store/store';

interface UndoEntry {
	at: number;
	tool: string;
	collection: CollectionName;
	items: Entity[];
}

/** Tool → collection it mutates (mirrors registerPlanabilityTools write targets).
 *  checkin_add 不在表里：打卡在 sidecar 文件（非 store 集合），撤销走自己的删除——见 plan-tools。 */
export const WRITE_COLLECTIONS: Record<string, CollectionName> = {
	schedule_create: 'schedules',
	schedule_delete: 'schedules',
	todo_create: 'todos',
	todo_complete: 'todos',
	todo_edit: 'todos',
	todo_delete: 'todos',
	note_create: 'notes',
	note_edit: 'notes',
	note_delete: 'notes',
	notebook_create: 'notebooks',
	memory_add: 'memories',
	memory_forget: 'memories'
};

const MAX_DEPTH = 10;

export class UndoStack {
	private file: string;

	constructor(private readonly store: Store) {
		this.file = path.join(store.dataDir, 'undo_stack.json');
	}

	private async load(): Promise<UndoEntry[]> {
		try { return JSON.parse(await fs.readFile(this.file, 'utf8')); } catch { return []; }
	}

	private async save(entries: UndoEntry[]): Promise<void> {
		await fs.writeFile(this.file, JSON.stringify(entries.slice(-MAX_DEPTH)), 'utf8');
	}

	/** Snapshot the collection before a mutating tool runs. */
	async push(collection: CollectionName, tool: string): Promise<void> {
		const items = await this.store.load(collection);
		const entries = await this.load();
		entries.push({ at: Date.now(), tool, collection, items: JSON.parse(JSON.stringify(items)) });
		await this.save(entries);
	}

	/** Pop the newest snapshot and restore. Returns what was undone, or null if empty. */
	async undoLast(): Promise<{ tool: string; collection: CollectionName; restored: number; at: number } | null> {
		const entries = await this.load();
		const entry = entries.pop();
		if (!entry) { return null; }
		await this.save(entries);
		await this.store.replace(entry.collection, entry.items as Entity[]);
		return { tool: entry.tool, collection: entry.collection, restored: entry.items.filter(x => !x.deletedAt).length, at: entry.at };
	}

	async depth(): Promise<number> { return (await this.load()).length; }
}
