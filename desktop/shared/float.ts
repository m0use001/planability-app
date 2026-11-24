/* Float-mode IPC contract. Added by pc-b for the floating input/output feature.
 *
 * WHY A NEW FILE rather than more entries in shared/ipc.ts: the float group is a separate
 * window family with its own lifecycle, and mixing its channels into the main DataApi would
 * make the main window look like it owns floating windows it knows nothing about.
 *
 * Both windows talk to the SAME main-process state, which is what makes the mode switch
 * lossless. See float/state.ts for the draft store and its revision rule.
 */

/* NOTE ON DIRECTION: this file must NOT import from src/main. The renderer's tsconfig
 * (tsconfig.web.json) only includes src/renderer/** and shared/**, so an import into
 * src/main would fail the web typecheck for every renderer file that touches FloatApi.
 * So the contract types are declared here and the main-process modules keep their own
 * definitions; float/conformance.ts asserts at compile time that the two agree. Structural
 * typing means the assertion is enough — no runtime coupling, no drift.
 */

export type PaneRole = 'input' | 'menu' | 'output';

export type CaptureMode = 'full' | 'rect' | 'window';

export interface CaptureTarget {
	id: string;
	title: string;
	thumb?: Buffer;
	appIcon?: Buffer;
}

/**
 * An image attached to the draft. Path only, never bytes: per Q4=A the bitmap stays local and
 * the model is given the path plus dimensions.
 */
export interface DraftAttachment {
	path: string;
	width?: number;
	height?: number;
	bytes?: number;
}

export interface DraftState {
	text: string;
	attachments: DraftAttachment[];
	/** Bumped on every accepted write; a stale write is refused, not merged. */
	revision: number;
	byWindow: number;
	updatedAt: number;
}

export type FloatModeState =
	| { phase: 'docked' }
	| { phase: 'floating'; sessionId: string }
	| { phase: 'floating-streaming'; sessionId: string };

export const FLOAT = {
	/** Current mode + which windows are open + the layout. */
	state: 'float:state',
	/** Enter floating mode for a session (refused while streaming). */
	enter: 'float:enter',
	/** Return to the main window. The draft is untouched — it lives in the main process. */
	exit: 'float:exit',

	/** Read the draft for a session. */
	draftGet: 'float:draftGet',
	/**
	 * Write the draft. `baseRevision` must match the stored revision or the write is REFUSED
	 * (two windows can both hold focus; refusing beats silently dropping what the user typed).
	 */
	draftSet: 'float:draftSet',
	/** Attach a captured screenshot to the draft. */
	draftAttach: 'float:draftAttach',
	draftRemove: 'float:draftRemove',

	/** Mode radio: set the capture mode without capturing anything. */
	modeSet: 'float:modeSet',
	/** The shutter. Captures using the current mode, then attaches the result to the draft. */
	shutter: 'float:shutter',
	/**
	 * Capture one SPECIFIC window in window mode — the second half of `listTargets`.
	 *
	 * Without this, `shutter()` returning `needTarget:true` is a dead end: the renderer shows the
	 * picker, the user chooses, and there is no call that can carry their choice back. The
	 * capture method existed (`FloatHost.shutterWindow`) while the channel did not.
	 */
	shutterWindow: 'float:shutterWindow',
	/** Window-mode target picker. */
	listTargets: 'float:listTargets',

	/** Move one pane; the group follows when it is the anchor. */
	movePane: 'float:movePane',
	/** Q3=A: explicit, reversible link toggle for the whole group (or one pane). */
	setLinked: 'float:setLinked',
	/** Persist / restore the group geometry. */
	saveLayout: 'float:saveLayout',

	/** Pushed to every float window when the group changes (drag, detach, mode switch). */
	event: 'float:event',
} as const;

export interface FloatLayoutPane {
	rect: { x: number; y: number; width: number; height: number };
	linked: boolean;
}

export interface FloatStateInfo {
	mode: FloatModeState;
	/** Capture mode radio selection (Q2: persistent). */
	captureMode: CaptureMode;
	/** Which panes currently exist. */
	open: PaneRole[];
	layout: Record<PaneRole, FloatLayoutPane>;
	anchor: PaneRole;
	allLinked: boolean;
	/** True when the native capture backend is unavailable, so the UI can say so up front. */
	shotSupported: boolean;
}

/** What the float group looks like when nothing is floating yet. */
export interface FloatEvent {
	type: 'mode' | 'layout' | 'draft' | 'shot' | 'gone';
	/** Present on 'shot'. Path only — per Q4=A the bitmap never enters model context. */
	attachment?: DraftAttachment;
	/** Present on 'shot' when it failed, so the UI can explain rather than silently do nothing. */
	error?: string;
}

export interface FloatApi {
	state(): Promise<FloatStateInfo>;
	enter(sessionId: string): Promise<{ ok: boolean; reason?: string; state: FloatStateInfo }>;
	exit(): Promise<FloatStateInfo>;

	draftGet(sessionId: string): Promise<DraftState>;
	/**
	 * Write the draft. On success returns the ACCEPTED state; on a stale revision returns
	 * `current` (the stored state to resync to) with ok:false and no `state`. Two diverged drafts
	 * cannot be merged safely, so the loser resyncs rather than merging.
	 */
	draftSet(sessionId: string, text: string, attachments: DraftAttachment[], baseRevision: number):
	Promise<{ ok: boolean; reason?: string; state?: DraftState; current?: DraftState }>;
	draftAttach(sessionId: string, a: DraftAttachment): Promise<DraftState>;
	draftRemove(sessionId: string, path: string): Promise<DraftState>;

	/** Set the mode radio. Captures nothing — pressing the shutter is a separate call. */
	modeSet(mode: CaptureMode): Promise<CaptureMode>;
	/**
	 * Press the shutter. Returns the attachment on success, or an error string. Callers should
	 * treat `pickTarget` as "you must show the window picker first".
	 */
	shutter(sessionId: string): Promise<{ ok: boolean; attachment?: DraftAttachment; error?: string; needTarget?: boolean }>;
	/**
	 * Capture the window the user picked from `listTargets()`. This is how `needTarget` gets
	 * resolved — `shutter()` alone can only report that a target is needed.
	 */
	shutterWindow(sessionId: string, id: string): Promise<{ ok: boolean; attachment?: DraftAttachment; error?: string }>;
	listTargets(): Promise<CaptureTarget[]>;

	movePane(role: PaneRole, x: number, y: number): Promise<FloatStateInfo>;
	setLinked(role: PaneRole | 'all', linked: boolean): Promise<FloatStateInfo>;
	saveLayout(): Promise<void>;

	onFloat(cb: (e: FloatEvent) => void): () => void;
}