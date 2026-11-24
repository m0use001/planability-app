/* F3 draft store — the state that must survive moving the input box between windows.
 *
 * WHY THIS FILE EXISTS AT ALL: the user's R7 is "在已有对话内也可以随时切换为悬浮窗模式或切换回来".
 * The naive implementation keeps the draft text in the renderer's React state. That works right
 * up until the moment the input box moves to a different BrowserWindow — at which point there
 * is a brand new renderer with brand new state, and the draft is gone. So the draft has to
 * live in the main process, which both windows talk to.
 *
 * The subtle part is not the storage, it is CONCURRENCY. Two windows can have the input focused
 * at once (the user clicked the float input, then alt-tabbed back to the main window). If both
 * can write, one silently clobbers the other and the user loses whichever text they typed last.
 * So every write carries a monotonically increasing revision, and a write from a stale window is
 * REJECTED rather than merged — the loser is told, so the UI can re-sync instead of pretending.
 */

export type FloatRole = 'input' | 'menu' | 'output';

export interface DraftAttachment {
	/** Absolute path to the image on disk. Never bytes: the model gets the path (Q4=A). */
	path: string;
	width?: number;
	height?: number;
	bytes?: number;
}

export interface DraftState {
	text: string;
	attachments: DraftAttachment[];
	/** Bumped on every accepted write. Clients send it back to detect a stale write. */
	revision: number;
	/** Which window last wrote — for the conflict message, not for correctness. */
	byWindow: number;
	updatedAt: number;
}

/** Max attachments per draft. Without a cap, twenty screenshots make the composer unusable. */
export const MAX_ATTACHMENTS = 9;
/** Max characters of draft text we keep. A paste of a whole file should not wedge the store. */
export const MAX_DRAFT_CHARS = 200_000;

export function emptyDraft(windowId = 0): DraftState {
	return { text: '', attachments: [], revision: 0, byWindow: windowId, updatedAt: 0 };
}

export type WriteVerdict =
	| { ok: true; state: DraftState }
	| { ok: false; reason: 'stale-revision'; current: DraftState };

/**
 * Apply a write if `baseRevision` matches the stored revision.
 *
 * A stale write is REFUSED, not merged. Merging two divergent drafts of the same text is not
 * possible in general, and a last-write-wins would silently discard whatever the user typed
 * most recently. Refusing surfaces the conflict so a window can re-read and re-apply.
 */
export function applyDraft(
	current: DraftState,
	next: { text: string; attachments?: DraftAttachment[] },
	baseRevision: number,
	windowId: number,
): WriteVerdict {
	if (!Number.isFinite(baseRevision) || baseRevision !== current.revision) {
		return { ok: false, reason: 'stale-revision', current: { ...current } };
	}
	const text = String(next.text ?? '').slice(0, MAX_DRAFT_CHARS);
	// Dedup by path: re-taking the same screenshot must not stack two chips on one path.
	const seen = new Set<string>();
	const attachments: DraftAttachment[] = [];
	for (const a of next.attachments ?? []) {
		if (!a?.path || seen.has(a.path)) { continue; }
		seen.add(a.path);
		attachments.push(a);
		if (attachments.length >= MAX_ATTACHMENTS) { break; }
	}
	// No-op writes must not bump the revision, or two idle windows would ping-pong forever.
	const unchanged = text === current.text
		&& attachments.length === current.attachments.length
		&& attachments.every((a, i) => a.path === current.attachments[i]?.path);
	if (unchanged) { return { ok: true, state: current }; }
	return {
		ok: true,
		state: { text, attachments, revision: current.revision + 1, byWindow: windowId, updatedAt: Date.now() },
	};
}

export function addAttachment(draft: DraftState, a: DraftAttachment): DraftState {
	if (!a?.path) { return draft; }
	if (draft.attachments.some(x => x.path === a.path)) { return draft; }
	if (draft.attachments.length >= MAX_ATTACHMENTS) { return draft; }
	return { ...draft, attachments: [...draft.attachments, a] };
}

export function removeAttachment(draft: DraftState, path: string): DraftState {
	const next = draft.attachments.filter(a => a.path !== path);
	return next.length === draft.attachments.length ? draft : { ...draft, attachments: next };
}

/** Is there anything worth preserving when the window goes away? */
export function draftHasContent(d: DraftState): boolean {
	return d.text.trim().length > 0 || d.attachments.length > 0;
}

/**
 * Per-session drafts.
 *
 * Keyed by session because the user can switch conversations in either window; without the key
 * a draft typed for conversation A would appear in conversation B on the next float switch.
 */
export class DraftStore {
	private readonly map = new Map<string, DraftState>();

	get(sessionId: string): DraftState {
		return this.map.get(sessionId) ?? emptyDraft();
	}

	/** Returns null for a stale write; the caller must re-sync the window. */
	set(sessionId: string, next: { text: string; attachments?: DraftAttachment[] },
		baseRevision: number, windowId: number): WriteVerdict {
		const cur = this.get(sessionId);
		const v = applyDraft(cur, next, baseRevision, windowId);
		if (v.ok) { this.map.set(sessionId, v.state); }
		return v;
	}

	/** Unconditional write — used only when a window is being torn down. */
	forceSet(sessionId: string, draft: DraftState): void {
		this.map.set(sessionId, { ...draft, attachments: [...draft.attachments] });
	}

	clear(sessionId: string): DraftState {
		const empty = emptyDraft();
		this.map.set(sessionId, empty);
		return empty;
	}

	/** Forget sessions no longer present, so the map cannot grow without bound. */
	retain(sessionIds: string[]): number {
		const keep = new Set(sessionIds);
		let removed = 0;
		for (const k of [...this.map.keys()]) {
			if (!keep.has(k)) { this.map.delete(k); removed++; }
		}
		return removed;
	}

	get size(): number { return this.map.size; }
}

/* ------------------------------------------------------------------ */
/* Screenshot -> draft flow (Q2: pick a mode, then press the shutter) */
/* ------------------------------------------------------------------ */

export type ShutterMode = 'full' | 'rect' | 'window';

export interface ShutterConfig {
	mode: ShutterMode;
	/** Countdown seconds before the shot fires. */
	delaySec: number;
	/** Show the picker before capturing (window mode needs a target). */
	pickTarget: boolean;
}

/**
 * What the shutter should do next, derived from mode + the current window list.
 *
 * Returning a "step" rather than performing it keeps the click handler in the renderer and the
 * actual capture in the main process — which is the only place that can reach desktopCapturer.
 */
export type ShutterStep =
	| { kind: 'capture'; mode: ShutterMode; delaySec: number }
	| { kind: 'pick-window'; then: { kind: 'capture'; mode: 'window'; delaySec: number } };

export function nextShutterStep(cfg: ShutterConfig, windowCount: number): ShutterStep {
	const delay = Number.isFinite(cfg.delaySec) ? Math.max(0, Math.round(cfg.delaySec)) : 0;
	if (cfg.mode === 'window' && cfg.pickTarget && windowCount > 1) {
		return { kind: 'pick-window', then: { kind: 'capture', mode: 'window', delaySec: delay } };
	}
	return { kind: 'capture', mode: cfg.mode, delaySec: delay };
}

/** Caption for the shutter button. Also the user's only clue after the main window is gone. */
export function shutterCaption(cfg: ShutterConfig): string {
	const names: Record<ShutterMode, string> = { full: '全屏', rect: '矩形', window: '窗口' };
	return cfg.delaySec > 0
		? `截屏（${names[cfg.mode]}）· ${cfg.delaySec}s`
		: `截屏（${names[cfg.mode]}）`;
}

/** Menu entries: three mode radios plus the shutter. NOT four capture entries (Q2). */
export interface SecondaryMenuItem {
	id: 'mode-full' | 'mode-rect' | 'mode-window' | 'shutter' | 'link' | 'dock' | 'quit';
	label: string;
	/** Radio state for the mode entries; absent for actions. */
	checked?: boolean;
	enabled?: boolean;
}

/**
 * Build the secondary menu. The mode entries are radios and the shutter is the only capture
 * action — the first reading of this feature ("four capture buttons") would have made the mode
 * un-sticky and impossible to use as "pick a way, then press once".
 */
export function buildSecondaryMenu(
	cfg: ShutterConfig, linked: boolean, floatMode: boolean,
): SecondaryMenuItem[] {
	const items: SecondaryMenuItem[] = [
		{ id: 'mode-full', label: '全屏', checked: cfg.mode === 'full' },
		{ id: 'mode-rect', label: '矩形', checked: cfg.mode === 'rect' },
		{ id: 'mode-window', label: '窗口', checked: cfg.mode === 'window' },
		{ id: 'shutter', label: shutterCaption(cfg) },
	];
	if (floatMode) {
		// Q3=A: an explicit, reversible toggle. "Drag it far away to detach" was rejected
		// because it cannot be undone without knowing a hidden gesture.
		items.push({ id: 'link', label: linked ? '解除吸附' : '自动吸附', checked: linked });
	}
	items.push({ id: 'dock', label: floatMode ? '切回主窗口' : '悬浮窗模式' });
	items.push({ id: 'quit', label: '退出 Planability' });
	return items;
}

/* ------------------------------------------------------------------ */
/* Mode transitions (R2/R7)                                            */
/* ------------------------------------------------------------------ */

export type FloatModeState =
	| { phase: 'docked' }
	| { phase: 'floating'; sessionId: string }
	/** A run is streaming into the small output window. */
	| { phase: 'floating-streaming'; sessionId: string };

/**
 * Can we enter floating mode right now?
 *
 * Refused while a turn is streaming, and this is not caution for its own sake: the input box
 * becoming a separate window mid-stream means the user is typing into a box whose output goes
 * somewhere they may not be watching. Allowing it produces "where did my message go".
 */
export function canFloat(s: { phase: FloatModeState; streaming: boolean }): { ok: boolean; reason?: string } {
	if (s.streaming) {
		return { ok: false, reason: '正在输出中，等这一轮结束再切换（否则你看不到自己发的消息去哪了）。' };
	}
	return { ok: true };
}

/** Enter floating mode for a session. */
export function enterFloat(s: FloatModeState, sessionId: string, streaming: boolean): FloatModeState {
	if (!canFloat({ phase: s, streaming }).ok) { return s; }
	return { phase: 'floating', sessionId };
}

/**
 * Return to the main window.
 *
 * The draft is NOT touched here — it lives in the DraftStore and both windows read it. That is
 * the whole point: the switch is lossless because no window owns the text.
 */
export function exitFloat(s: FloatModeState): FloatModeState {
	if (s.phase === 'docked') { return s; }
	return { phase: 'docked' };
}

/** Does the small output window need to exist for this state? */
export function outputWindowVisible(s: FloatModeState): boolean {
	return s.phase !== 'docked';
}

/** The session the float group is bound to, or null when docked. */
export function floatSession(s: FloatModeState): string | null {
	return s.phase === 'docked' ? null : s.sessionId;
}