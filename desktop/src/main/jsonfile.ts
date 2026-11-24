/* Reading and writing the JSON files a user's data actually lives in.
 *
 * WHY THIS EXISTS — the bug it is here to kill.
 *
 * Every one of these files used to open with the same two lines:
 *
 *     try { list = JSON.parse(await fs.readFile(p, 'utf8')); } catch { /* first run *\/ }
 *
 * which conflates two completely different answers to "what is on disk?". ENOENT means nothing
 * has been created yet, and an empty list is the right reply. A parse error means there IS data
 * there and we could not read it — and replying "empty list" to THAT is how a damaged file
 * becomes an empty collection, because the very next `upsert` calls `save()`, which
 * `JSON.stringify`s the empty list over the top of the original. The user's schedules are gone,
 * no error is shown anywhere, and the page simply says "还没有日程". A crash that leaves a
 * truncated file, an antivirus holding the file, a bad migration — all of them arrive as that
 * same silent `catch`.
 *
 * So the rule is: ENOENT falls back, nothing else does.
 *   - Unparseable file: it is moved aside to `<name>.corrupt-<ts>` FIRST, so the next write
 *     cannot reach it and the original bytes stay recoverable instead of being destroyed. The
 *     caller gets `fallback` plus an error string to log.
 *   - Unreadable file (permissions, I/O): it is not masked at all — writing to a collection we
 *     could not read is precisely the operation that loses data, so this throws.
 *
 * Writes go through tmp + rename. `datasync.ts` already did this and said why ("the peer never
 * sees a half file"); the rest of the store did not, which is how a crash produced the parse
 * error in the first place. Fixing only the read side would just be waiting for the next crash.
 */

import * as fs from 'node:fs/promises';

export interface JsonRead<T> {
	/** Parsed file contents, or `fallback` when the file simply does not exist yet. */
	value: T;
	/** Set when the file existed but could not be used. The original is preserved on disk. */
	error?: string;
	/** Where the unreadable original was moved, when it could be moved. */
	quarantinedTo?: string;
}

const stamp = () => new Date().toISOString().replace(/[:.]/g, '-');

export async function readJsonFile<T>(file: string, fallback: T): Promise<JsonRead<T>> {
	let raw: string;
	try {
		raw = await fs.readFile(file, 'utf8');
	} catch (e) {
		// ENOENT is the only "nothing is wrong" answer here: first run, empty by definition.
		if ((e as NodeJS.ErrnoException)?.code === 'ENOENT') { return { value: fallback }; }
		throw e;
	}

	try {
		return { value: JSON.parse(raw) as T };
	} catch (e) {
		let moved: string;
		try {
			moved = `${file}.corrupt-${stamp()}`;
			await fs.rename(file, moved);
		} catch (re) {
			// Could not move it out of the way either. Handing back `fallback` would let the next
			// save overwrite the only copy, so refuse instead — loudly beats silently.
			throw new Error(`无法解析 ${file}，且无法把损坏文件移开：${(re as Error)?.message ?? re}`);
		}
		const msg = `无法解析，已移到 ${moved}：${(e as Error)?.message ?? e}`;
		console.error('[jsonfile]', msg);
		return { value: fallback, error: msg, quarantinedTo: moved };
	}
}

/**
 * Atomic replace: write to a sibling `.tmp`, then rename over the target. A crash mid-write
 * leaves the previous good file instead of a truncated one (which is what `readJsonFile` then
 * has to quarantine).
 */
export async function writeJsonFile(file: string, value: unknown, indent?: number): Promise<void> {
	const payload = JSON.stringify(value, null, indent);
	const tmp = `${file}.tmp`;
	await fs.writeFile(tmp, payload, 'utf8');
	await fs.rename(tmp, file);
}
