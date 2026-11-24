/* F1 FloatLayout — geometry engine for the floating input/output window group.
 *
 * WHY THIS IS SEPARATE AND PURE: every interesting bug in a snapping system is arithmetic,
 * and arithmetic bugs are invisible until a user drags a window 3 pixels onto a second
 * monitor. So this file contains no Electron at all — it takes rectangles and returns
 * rectangles. That makes the whole engine unit-testable without a display, which matters
 * because I cannot do runtime/AX acceptance (that is pc-a's lane).
 *
 * THE FOUR DECISIONS THAT PREVENT SILENT WRONGS, all of them learned the hard way:
 *
 * 1. ONE ANCHOR, ONE DIRECTION. Menu and output follow the anchor; they never follow each
 *    other. Three windows that each reposition the other oscillate, and the user sees the
 *    group jitter. There is exactly one source of truth for the group's position.
 *
 * 2. "DETACHED" IS A STORED FLAG, NOT INFERRED. An earlier design detached a window when it
 *    was dragged far away. That is unrecoverable: the user cannot get the group back without
 *    knowing a magic gesture. The user chose an explicit, reversible link toggle (Q3=A), so
 *    that is what the state model says.
 *
 * 3. CLAMPING USES THE ANCHOR'S DISPLAY. Clamping to the primary display means dragging the
 *    group to a second monitor silently shoves it back. screen.getDisplayMatching(anchor)
 *    is what Electron offers for this and it is the reason the caller passes displays in.
 *
 * 4. THE DEFAULT IS A STACK, TOP-DOWN, COMPOSER LAST. The user said "默认上下相连", so out of
 *    the box the three panes are edge-to-edge with zero gap and identical width. The order is
 *    transcript, menu, composer — see `PANE_ORDER` for why the composer is at the bottom. Gap is
 *    a per-user preference, not a constant buried in the code.
 */

/** A window rectangle in DIPs. */
export interface Rect { x: number; y: number; width: number; height: number }

/** Minimal shape of Electron's Display — injected so the engine stays Electron-free. */
export interface DisplayLike {
	id: number;
	bounds: Rect;
	scaleFactor?: number;
}

export type PaneRole = 'input' | 'menu' | 'output';

/**
 * The three panes, in stack order, TOP FIRST.
 *
 * The composer is LAST on purpose. It is the pane you reach for when you already know what you
 * want to say, so putting it at the bottom keeps the eye on the transcript while reading and
 * puts the only thing you need at the end of the group rather than at the top of it. It also
 * matches how the panes are used in sequence: the answer arrives in `output`, and the reply is
 * typed underneath it.
 *
 * `stackY` always stacks DOWNWARD from the anchor, so the anchor must be `PANE_ORDER[0]` — a
 * middle or last pane as anchor would keep its own rect while everything else was placed below
 * it, and the two would overlap. `ANCHOR` below exists so that pairing cannot be got wrong by
 * hand in two different places.
 */
export const PANE_ORDER: readonly PaneRole[] = ['output', 'menu', 'input'];

/** The top of the stack. Structurally `PANE_ORDER[0]`; see the note there. */
export const ANCHOR: PaneRole = PANE_ORDER[0];

/**
 * Bumped when `PANE_ORDER` changes, so a layout stored under the old order is re-stacked instead
 * of being restored into a geometry the current engine would never produce.
 */
export const STACK_ORDER_VERSION = 2;

/**
 * Edge gap in DIPs. 0 means "相连" (edges touching), which is what "默认上下相连" asks for.
 * Snap threshold: how close an edge must come before it grabs. 12 DIP is deliberate —
 * much larger feels sticky and fights the user, much smaller almost never fires because
 * windows snap to integer positions and the pointer lands between them.
 */
export const FLOAT_LAYOUT_DEFAULTS = {
	gap: 0,
	snapThreshold: 12,
	/** How far a pane may stray while still linked before we pull it back. Generous on
	 * purpose: the link should survive sloppy dragging, not punish it. */
	maxDrift: 400,
} as const;

export interface FloatLayoutConfig {
	gap?: number;
	snapThreshold?: number;
	maxDrift?: number;
}

/** One pane's placement plus whether it is still part of the group. */
export interface PaneState {
	rect: Rect;
	/** false = the user detached it with the link toggle; it then moves on its own. */
	linked: boolean;
}

export interface LayoutSnapshot {
	panes: Record<PaneRole, PaneState>;
	/** The pane the group follows. Only it can move the others. */
	anchor: PaneRole;
}

function isFiniteRect(r: Rect | null | undefined): r is Rect {
	return !!r && Number.isFinite(r.x) && Number.isFinite(r.y)
		&& Number.isFinite(r.width) && Number.isFinite(r.height);
}

/** Keep a window on the display it is on. Windows cannot be positioned off-screen. */
export function clampToDisplay(rect: Rect, displays: DisplayLike[]): Rect {
	if (!isFiniteRect(rect) || !displays.length) { return rect; }
	// getDisplayMatching semantics: the display with the greatest overlap with the rect.
	let best = displays[0];
	let bestArea = -1;
	for (const d of displays) {
		const ox = Math.max(0, Math.min(rect.x + rect.width, d.bounds.x + d.bounds.width) - Math.max(rect.x, d.bounds.x));
		const oy = Math.max(0, Math.min(rect.y + rect.height, d.bounds.y + d.bounds.height) - Math.max(rect.y, d.bounds.y));
		const area = ox * oy;
		if (area > bestArea) { bestArea = area; best = d; }
	}
	// How much of the pane must stay reachable so the user can always drag it back.
	const KEEP = 80;
	const b = best.bounds;
	// Per-axis: if the pane FITS on this display, put it fully inside — that is what the user
	// means by "I dragged it near the edge". Only when it does NOT fit do we fall back to
	// keeping KEEP px visible. The first version applied the KEEP rule unconditionally, which
	// made a 44px menu pane sit 80px above the bottom edge and a 120px input pane overlap it —
	// i.e. clamping actively created the overlap it was supposed to prevent.
	const keepX = Math.min(KEEP, rect.width);
	const keepY = Math.min(KEEP, rect.height);
	// Only move the pane when too little of it would remain visible. Containment is NOT the
	// goal — reachability is. A pane hanging 50px off the edge is left where the user put it.
	const visX = Math.min(rect.x + rect.width, b.x + b.width) - Math.max(rect.x, b.x);
	const visY = Math.min(rect.y + rect.height, b.y + b.height) - Math.max(rect.y, b.y);
	const x = visX >= keepX ? rect.x : Math.round(Math.min(Math.max(rect.x, b.x), b.x + b.width - keepX));
	const y = visY >= keepY ? rect.y : Math.round(Math.min(Math.max(rect.y, b.y), b.y + b.height - keepY));
	return { ...rect, x, y };
}

/**
 * Where a pane of the given height belongs in the stack, given the anchor's top edge.
 * Pure function of (anchorTop, heights, index, gap) — the whole engine in one expression.
 */
export function stackY(anchorY: number, heights: Record<PaneRole, number>, index: number, gap: number): number {
	let y = anchorY;
	for (let i = 0; i < index; i++) {
		y += (heights[PANE_ORDER[i]] || 0) + gap;
	}
	return y;
}

/**
 * Recompute every LINKED pane from the anchor. Detached panes are returned untouched —
 * that is the entire meaning of the flag.
 */
export function relayout(snap: LayoutSnapshot, cfg: FloatLayoutConfig = {}): LayoutSnapshot {
	const gap = cfg.gap ?? FLOAT_LAYOUT_DEFAULTS.gap;
	const { anchor, panes } = snap;
	const anchorPane = panes[anchor];
	const heights = {} as Record<PaneRole, number>;
	for (const role of PANE_ORDER) { heights[role] = panes[role].rect.height; }
	const out: Record<PaneRole, PaneState> = {} as Record<PaneRole, PaneState>;
	for (let i = 0; i < PANE_ORDER.length; i++) {
		const role = PANE_ORDER[i];
		const pane = panes[role];
		if (!pane.linked || role === anchor) {
			out[role] = { rect: { ...pane.rect }, linked: pane.linked };
			continue;
		}
		out[role] = {
			rect: {
				x: anchorPane.rect.x,
				y: stackY(anchorPane.rect.y, heights, i, gap),
				width: anchorPane.rect.width,
				height: pane.rect.height,
			},
			linked: true,
		};
	}
	return { anchor, panes: out };
}

/**
 * Which windows have to move, and where. Pure: the caller supplies each window's current
 * position, or null when there is no window (or it is destroyed).
 *
 * Split out of the host because the rule was otherwise untestable. `pushBounds` lives on
 * FloatHost, which needs Electron, so nothing in the suite could see it — and it skipped every
 * pane with `linked === false`. That flag means "this pane keeps its OWN rect", so skipping it
 * meant every path that ended there (applyMove, setLinkedPane, relayoutNow) updated the rect and
 * then never told the window: an unlinked pane could not be dragged anywhere, silently.
 *
 * No-op moves are filtered out: a drag produces a stream of these, and `setPosition` on a
 * frameless transparent window is not free.
 */
export function planBounds(
	layout: LayoutSnapshot,
	current: (role: PaneRole) => { x: number; y: number } | null,
): { role: PaneRole; x: number; y: number }[] {
	const out: { role: PaneRole; x: number; y: number }[] = [];
	for (const role of PANE_ORDER) {
		const cur = current(role);
		if (!cur) { continue; }
		const r = layout.panes[role].rect;
		const x = Math.round(r.x), y = Math.round(r.y);
		if (cur.x !== x || cur.y !== y) { out.push({ role, x, y }); }
	}
	return out;
}

export type SnapEdge = 'left' | 'right' | 'top' | 'bottom';

/**
 * Would dropping `rect` here snap it to `target`'s edge?
 *
 * Returns the adjusted x/y or null. Proximity is measured on the gap between the two edges,
 * and only the NEAREST edge wins — otherwise a small window near a tall one matches top and
 * left at once and the result depends on iteration order.
 */
export function snapRect(
	rect: Rect, target: Rect, threshold = FLOAT_LAYOUT_DEFAULTS.snapThreshold,
): { x: number; y: number; edge: SnapEdge } | null {
	// Edge names refer to the side of the TARGET the pane lands against, not the side of the
	// pane that moves. "left" therefore means "flush to the target's left edge". The first
	// version named these after the moving pane and read backwards, which is exactly the kind
	// of ambiguity that makes a snapping system feel broken while working correctly.
	const gapX = [
		// pane's left edge near the target's RIGHT edge -> flush to the target's right
		{ edge: 'right' as const, d: Math.abs(rect.x - (target.x + target.width)) },
		// pane's right edge near the target's LEFT edge -> flush to the target's left
		{ edge: 'left' as const, d: Math.abs((rect.x + rect.width) - target.x) },
	];
	const gapY = [
		{ edge: 'bottom' as const, d: Math.abs(rect.y - (target.y + target.height)) },
		{ edge: 'top' as const, d: Math.abs((rect.y + rect.height) - target.y) },
	];
	// A pane only snaps to a vertical edge if their vertical ranges actually overlap-ish,
	// otherwise a tall input pane snaps sideways to a short output pane across the screen.
	const overlapsY = rect.y < target.y + target.height && target.y < rect.y + rect.height;
	const overlapsX = rect.x < target.x + target.width && target.x < rect.x + rect.width;
	const vx = overlapsY ? gapX : null;
	const hy = overlapsX ? gapY : null;
	const cands = [...(vx ?? []), ...(hy ?? [])].filter(c => c.d <= threshold);
	if (!cands.length) { return null; }
	cands.sort((a, b) => a.d - b.d);
	const win = cands[0];
	switch (win.edge) {
		case 'right': return { x: target.x + target.width, y: rect.y, edge: win.edge };
		case 'left': return { x: target.x - rect.width, y: rect.y, edge: win.edge };
		case 'bottom': return { x: rect.x, y: target.y + target.height, edge: win.edge };
		case 'top': return { x: rect.x, y: target.y - rect.height, edge: win.edge };
	}
}

/**
 * Move a pane. If it is the anchor, the whole group comes along; otherwise the pane moves
 * alone UNLESS it is linked, in which case it is immediately re-stacked — that is what makes
 * the group feel rigid instead of rubbery.
 */
export function movePane(
	snap: LayoutSnapshot, role: PaneRole, next: Rect, cfg: FloatLayoutConfig = {},
): LayoutSnapshot {
	const pane = snap.panes[role];
	if (!pane) { return snap; }
	const moved: LayoutSnapshot = {
		anchor: snap.anchor,
		panes: { ...snap.panes, [role]: { ...pane, rect: { ...next } } },
	};
	if (role === snap.anchor) { return relayout(moved, cfg); }
	if (!pane.linked) { return moved; }
	return relayout(moved, cfg);
}

/** Is a linked pane still close enough to the group to be considered part of it? */
export function isStillLinked(snap: LayoutSnapshot, role: PaneRole, cfg: FloatLayoutConfig = {}): boolean {
	const pane = snap.panes[role];
	if (!pane || !pane.linked) { return false; }
	const maxDrift = cfg.maxDrift ?? FLOAT_LAYOUT_DEFAULTS.maxDrift;
	const a = snap.panes[snap.anchor].rect;
	const b = pane.rect;
	return Math.abs(a.x - b.x) <= maxDrift && Math.abs(a.y - b.y) <= maxDrift;
}

/** Toggle one pane's membership. Explicit and reversible — the whole point of Q3=A. */
export function setLinked(snap: LayoutSnapshot, role: PaneRole, linked: boolean): LayoutSnapshot {
	const pane = snap.panes[role];
	if (!pane) { return snap; }
	return {
		anchor: snap.anchor,
		panes: { ...snap.panes, [role]: { ...pane, linked } },
	};
}

/** Link or unlink everything at once (the chain button in the UI). */
export function setAllLinked(snap: LayoutSnapshot, linked: boolean): LayoutSnapshot {
	const panes = {} as Record<PaneRole, PaneState>;
	for (const role of PANE_ORDER) {
		panes[role] = { ...snap.panes[role], linked };
	}
	return { anchor: snap.anchor, panes };
}

/** True when every pane is linked, so the UI can show the toggle in its "on" state. */
export function allLinked(snap: LayoutSnapshot): boolean {
	return PANE_ORDER.every(r => snap.panes[r]?.linked);
}

export function anyDetached(snap: LayoutSnapshot): boolean {
	return PANE_ORDER.some(r => snap.panes[r] && !snap.panes[r].linked);
}

/** Serialisable form for config persistence. */
export interface StoredLayout {
	panes: Record<PaneRole, { rect: Rect; linked: boolean }>;
	anchor: PaneRole;
	gap?: number;
	/** The `PANE_ORDER` these rects were computed under. See `STACK_ORDER_VERSION`. */
	v?: number;
}

export function saveLayout(snap: LayoutSnapshot, gap?: number): StoredLayout {
	const panes = {} as StoredLayout['panes'];
	for (const role of PANE_ORDER) {
		panes[role] = { rect: { ...snap.panes[role].rect }, linked: snap.panes[role].linked };
	}
	return { panes, anchor: snap.anchor, gap, v: STACK_ORDER_VERSION };
}

/**
 * Restore a persisted layout. Every field is validated: this data comes off disk and may
 * have been written by an older build, and one NaN here would make the whole group
 * un-draggable with no clue why.
 *
 * The stored `anchor` is deliberately NOT trusted. `stackY` only ever goes downward, so the
 * anchor has to be the top pane; an anchor persisted under the old order names a pane that is no
 * longer on top, and honouring it would place every linked pane underneath a rect the anchor is
 * not using. `ANCHOR` is derived from `PANE_ORDER`, so it is the only anchor this engine can
 * express anyway.
 */
export function loadLayout(stored: unknown, fallback: LayoutSnapshot): LayoutSnapshot {
	if (!stored || typeof stored !== 'object') { return fallback; }
	const s = stored as Partial<StoredLayout>;

	// A layout from before the stack was reordered: rebuild the default stack, keeping only the
	// link flags. Throwing away the stored rects is safe *right now* because `movePane` has no
	// caller — nothing has ever been able to put a pane anywhere, so every stored layout is the
	// default one with maybe a link toggled. That is a property of the current state, not a
	// general licence to discard geometry: the day dragging lands, this branch has to become a
	// real migration that keeps x/width and recomputes y. Flagged here so the next reader does
	// not mistake it for one.
	if (s.v !== STACK_ORDER_VERSION) {
		const panes = {} as Record<PaneRole, PaneState>;
		for (const role of PANE_ORDER) {
			panes[role] = {
				rect: { ...fallback.panes[role].rect },
				// Absent means linked, matching `defaultLayout`. Reading it as false would
				// silently detach the whole group on the first launch after an upgrade.
				linked: s.panes?.[role]?.linked !== false,
			};
		}
		return { panes, anchor: ANCHOR };
	}

	const panes = {} as Record<PaneRole, PaneState>;
	for (const role of PANE_ORDER) {
		const p = s.panes?.[role];
		panes[role] = isFiniteRect(p?.rect)
			? { rect: { ...(p!.rect as Rect) }, linked: !!p!.linked }
			: { rect: { ...fallback.panes[role].rect }, linked: fallback.panes[role].linked };
	}
	return { panes, anchor: ANCHOR };
}

/**
 * Build the default "上下相连" group from one rectangle.
 *
 * `base` supplies the group's frame — x, y and width, which are shared by every linked pane — and
 * `base.height` is the INPUT pane's height. The vertical arrangement is left entirely to
 * `relayout`, so this function does not have to know the stack order: the anchor is `PANE_ORDER[0]`
 * and every pane is seeded at the group's top-left.
 */
export function defaultLayout(
	base: Rect, menuH: number, outputH: number, cfg: FloatLayoutConfig = {},
): LayoutSnapshot {
	const seed = (height: number): Rect => ({ x: base.x, y: base.y, width: base.width, height });
	return relayout({
		anchor: ANCHOR,
		panes: {
			input: { rect: seed(base.height), linked: true },
			menu: { rect: seed(menuH), linked: true },
			output: { rect: seed(outputH), linked: true },
		},
	}, cfg);
}