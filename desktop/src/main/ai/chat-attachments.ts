/* Attachments on the MESSAGE path — the tail the float pane's TODO named.
 *
 * THE GAP: the float pane's draft carries attachments as LOCAL FILE PATHS (float.ts, Q4=A: the
 * bitmap stays on this machine and is referenced by path, never uploaded as bytes). When the
 * user sent, the draft's TEXT was consumed but the attachments were kept — so a screenshot the
 * user had just attached never reached the turn. It vanished with a chip still on screen, which
 * is the worst of both: the user believes it was sent and the model never saw it.
 *
 * WHY THIS IS NOT "inline the file like the main composer does". The main window's attach button
 * reads a TEXT file and pastes its content into the composer — fine, the bytes ARE words. Float
 * attachments are screenshots: binary, and the whole point of Q4=A is that we do NOT read them
 * here. So the honest wiring is a REFERENCE: name + path, no content, appended to the message
 * the model reads, plus the paths on the user row so the transcript can draw the chips. When a
 * vision path lands later, this path IS the handle it needs — nothing to re-plumb.
 *
 * Pure functions, no I/O, so both the send handler and the tests can call them without a window.
 */
import * as path from 'node:path';

/** Same cap as the float draft (float/state.ts): without it, twenty screenshots make the
 *  message — and the transcript — unusable. */
export const MAX_ATTACHMENTS = 6;

/**
 * Sanitise what the renderer sent into the path list the message path will carry.
 *
 * Trusts nothing: a non-array is "none", a non-string entry is dropped, blank is dropped, and
 * duplicates collapse (the same screenshot added twice is one chip, not two). Order is the
 * user's, preserved. Caps at MAX_ATTACHMENTS like the float draft does for the same reason.
 */
export function normalizeAttachments(v: unknown): string[] {
	if (!Array.isArray(v)) { return []; }
	const out: string[] = [];
	for (const x of v) {
		if (typeof x !== 'string') { continue; }
		const p = x.trim();
		if (!p || out.includes(p)) { continue; }
		out.push(p);
		if (out.length >= MAX_ATTACHMENTS) { break; }
	}
	return out;
}

/**
 * Append the attachment reference block to the text the MODEL reads.
 *
 * Names AND paths, so the model can say what it was given (「你给的 shot-….png」) and reach the
 * file if it has a tool for it. The block explicitly says the content was not uploaded — the
 * model must not assume it can "see" a binary, and the user must be able to tell that from the
 * transcript alone. Empty text with attachments yields the block alone (no leading blank line).
 */
export function withAttachmentRefs(text: string, files: readonly string[]): string {
	if (!files.length) { return text; }
	const lines = files.map(p => `- ${path.basename(p)}：${p}`).join('\n');
	const block = `【附件（本机路径，内容未上传）】\n${lines}`;
	return text ? `${text}\n\n${block}` : block;
}
