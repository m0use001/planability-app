/* F1 FloatLayout tests. Pure geometry — no Electron, no windows, no display needed.
 *
 * Run: npm test:float
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-float-test.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main');
const L = require(path.join(base, 'float', 'layout.js'));

const DISPLAYS = [
	{ id: 1, bounds: { x: 0, y: 0, width: 1920, height: 1080 }, scaleFactor: 1 },
	// A monitor to the RIGHT, and one ABOVE-LEFT with a NEGATIVE origin: the layout that
	// breaks every naive "clamp to 0,0" implementation.
	{ id: 2, bounds: { x: 1920, y: 0, width: 2560, height: 1440 }, scaleFactor: 1 },
	{ id: 3, bounds: { x: -1920, y: -300, width: 1920, height: 1080 }, scaleFactor: 1 },
];

// INPUT is the GROUP'S FRAME (x, y, width) plus the COMPOSER's height. It is deliberately NOT
// "where the composer goes" — the composer is no longer the top pane. Which pane sits at (x, y)
// is decided by PANE_ORDER, and pinned once, in section 1.
const INPUT = { x: 100, y: 200, width: 520, height: 120 };
const MENU_H = 44, OUT_H = 300;
const HEIGHTS = { input: INPUT.height, menu: MENU_H, output: OUT_H };
const gapless = () => L.defaultLayout(INPUT, MENU_H, OUT_H);

/**
 * Where a pane must land, given the group's top edge and the gap.
 *
 * Derived from PANE_ORDER rather than hardcoded, so the assertions below test GEOMETRY — heights,
 * per-joint gaps, x/width propagation, no mutation of the source — while the stack ORDER is
 * asserted on its own. Reversing the order used to mean rewriting a dozen assertions that each
 * said some form of "input is on top", which is exactly how a requirement that has since been
 * rejected stays pinned in the suite after it is rejected.
 */
function expectY(role, topY = INPUT.y, gap = 0) {
	let y = topY;
	for (const r of L.PANE_ORDER) {
		if (r === role) { return y; }
		y += HEIGHTS[r] + gap;
	}
	throw new Error('unknown pane role ' + role);
}

(async () => {
	// ---- 1. default stack is edge-to-edge and width-matched ("默认上下相连")
	{
		const s = gapless();
		// THE ORDER, stated as the requirement it is: the composer belongs at the BOTTOM, so the
		// transcript is on top with the toolbar between them.
		check('order: the stack is transcript, toolbar, composer',
			JSON.stringify(L.PANE_ORDER) === JSON.stringify(['output', 'menu', 'input']));
		check('order: the composer is the LAST pane, i.e. at the bottom',
			L.PANE_ORDER[L.PANE_ORDER.length - 1] === 'input');
		check('order: the transcript really is above the composer',
			s.panes.output.rect.y < s.panes.menu.rect.y && s.panes.menu.rect.y < s.panes.input.rect.y);
		// stackY only ever goes DOWNWARD, so the anchor must be the top pane. A middle or bottom
		// anchor would keep its own rect while everything else was placed below it, overlapping.
		check('order: the anchor is the top pane, never a middle or bottom one',
			s.anchor === L.PANE_ORDER[0] && s.anchor === 'output');
		check('default: the top pane keeps the base frame',
			JSON.stringify(s.panes.output.rect) === JSON.stringify(
				{ x: INPUT.x, y: INPUT.y, width: INPUT.width, height: OUT_H }));
		check('default: the toolbar sits flush under the transcript (no gap)',
			s.panes.menu.rect.y === expectY('menu'));
		check('default: the composer sits flush under the toolbar',
			s.panes.input.rect.y === expectY('input'));
		check('default: all three share the anchor width',
			L.PANE_ORDER.every(r => s.panes[r].rect.width === INPUT.width));
		check('default: all three share the anchor x',
			L.PANE_ORDER.every(r => s.panes[r].rect.x === INPUT.x));
		check('default: everything starts linked', L.allLinked(s) === true && L.anyDetached(s) === false);
		check('default: heights are preserved per pane',
			L.PANE_ORDER.every(r => s.panes[r].rect.height === HEIGHTS[r]));
	}

	// ---- 2. a non-zero gap actually inserts a gap (twice, not once)
	{
		const s = L.defaultLayout(INPUT, MENU_H, OUT_H, { gap: 8 });
		check('gap: the toolbar is 8 below the transcript', s.panes.menu.rect.y === expectY('menu', INPUT.y, 8));
		check('gap: the composer is a SECOND 8 below the toolbar — gap is per-joint',
			s.panes.input.rect.y === expectY('input', INPUT.y, 8));
		check('gap: nothing is stacked above the top pane', s.panes.output.rect.y === INPUT.y);
	}

	// ---- 3. moving the anchor carries the group
	{
		const s = gapless();
		const moved = L.movePane(s, 'output', { x: 400, y: 500, width: INPUT.width, height: OUT_H });
		check('move: the anchor moved', moved.panes.output.rect.x === 400 && moved.panes.output.rect.y === 500);
		check('move: the toolbar followed x and y', moved.panes.menu.rect.x === 400 && moved.panes.menu.rect.y === 500 + OUT_H);
		check('move: the composer followed x and y, and stayed at the bottom',
			moved.panes.input.rect.x === 400 && moved.panes.input.rect.y === 500 + OUT_H + MENU_H);
		check('move: the composer width followed the anchor', moved.panes.input.rect.width === INPUT.width);
		// The source must not be mutated — a shared-reference bug here shows up as the old
		// layout changing under the caller, which is maddening to debug.
		check('move: the snapshot is NOT mutated', s.panes.menu.rect.x === 100);
	}

	// ---- 4. moving a LINKED non-anchor snaps it back: the group is rigid
	{
		const s = gapless();
		// The composer is the linked non-anchor now, so this is the exact gesture a user makes
		// when they try to drag the window: it must snap back rather than tear off silently.
		const dragged = L.movePane(s, 'input', { x: 900, y: 900, width: INPUT.width, height: INPUT.height });
		check('rigid: a linked composer dragged far away returns to the bottom of the stack',
			dragged.panes.input.rect.x === INPUT.x && dragged.panes.input.rect.y === expectY('input'));
	}

	// ---- 5. DETACHED is stored, not inferred — and reversible
	{
		const s = gapless();
		const off = L.setLinked(s, 'input', false);
		check('detach: flag is set', off.panes.input.linked === false);
		check('detach: anyDetached reports it', L.anyDetached(off) === true && L.allLinked(off) === false);
		const dragged = L.movePane(off, 'input', { x: 900, y: 900, width: INPUT.width, height: INPUT.height });
		check('detach: a detached composer moves FREELY (no snap-back)',
			dragged.panes.input.rect.x === 900 && dragged.panes.input.rect.y === 900);
		check('detach: the rest of the group did not follow', dragged.panes.output.rect.x === INPUT.x);
		// The whole point of Q3=A: one toggle restores it, no gesture archaeology needed.
		const back = L.movePane(L.setLinked(dragged, 'input', true), 'output',
			{ x: INPUT.x, y: 260, width: INPUT.width, height: OUT_H });
		check('re-attach: one toggle restores group membership',
			back.panes.input.rect.x === INPUT.x && back.panes.input.rect.y === 260 + OUT_H + MENU_H);
		check('re-attach: relinked pane is inside the drift budget',
			L.isStillLinked(back, 'input') === true);
	}

	// ---- 6. dragging the anchor does NOT rescue a detached pane (that would be sneaky)
	{
		let s = gapless();
		s = L.setLinked(s, 'menu', false);
		s = L.movePane(s, 'output', { x: 700, y: 700, width: INPUT.width, height: OUT_H });
		check('anchor-move: detached toolbar stays where the user put it', s.panes.menu.rect.x === INPUT.x);
		check('anchor-move: the still-linked composer followed, still at the bottom',
			s.panes.input.rect.x === 700 && s.panes.input.rect.y === 700 + OUT_H + MENU_H);
	}

	// ---- 7. setAllLinked / the chain button
	{
		const s = L.setAllLinked(gapless(), false);
		check('chain: all unlinked', L.allLinked(s) === false && L.anyDetached(s) === true);
		const dragged = L.movePane(s, 'output', { x: 50, y: 50, width: 300, height: 200 });
		check('chain: with everything unlinked, panes are independent', dragged.panes.output.rect.x === 50);
		const back = L.setAllLinked(s, true);
		check('chain: relinking all is a single call', L.allLinked(back) === true);
	}

	// ---- 8. edge snapping — nearest edge wins, and OVERLAP is required
	{
		const target = { x: 0, y: 0, width: 400, height: 300 };
		// Ranges deliberately overlap, because that is the only situation snapping is for.
		const r = L.snapRect({ x: 405, y: 100, width: 200, height: 100 }, target);
		check('snap: a pane just right of the target snaps flush to it',
			r !== null && r.x === 400 && r.edge === 'right');
		const below = L.snapRect({ x: 100, y: 305, width: 200, height: 100 }, target);
		check('snap: a pane just below snaps flush', below !== null && below.y === 300 && below.edge === 'bottom');
		const far = L.snapRect({ x: 900, y: 900, width: 200, height: 100 }, target);
		check('snap: a distant pane does NOT snap', far === null);

		// Just outside the threshold: must not grab. This is the "sticky vs usable" line.
		const outside = L.snapRect({ x: 400 + 12 + 1, y: 100, width: 200, height: 100 }, target, 12);
		check('snap: one DIP beyond the threshold does not grab', outside === null);
		const inside = L.snapRect({ x: 400 + 12 - 1, y: 100, width: 200, height: 100 }, target, 12);
		check('snap: one DIP inside the threshold grabs', inside !== null && inside.x === 400);

		// THE property that keeps the stack from teleporting: edge proximity alone is true for
		// nearly every pair of windows, so a pane dragged clear of the stack must NOT be pulled
		// to a random side. Overlap on the perpendicular axis is the gate.
		const diagRight = L.snapRect({ x: 405, y: 500, width: 200, height: 100 }, target);
		check('snap: right of target but BELOW its Y range does not snap sideways',
			diagRight === null, );
		const diagBelow = L.snapRect({ x: 500, y: 305, width: 200, height: 100 }, target);
		check('snap: below target but RIGHT of its X range does not snap down',
			diagBelow === null);
		check('snap: no-overlap diagonal returns null entirely',
			L.snapRect({ x: 900, y: 305, width: 100, height: 100 }, target) === null);

		// Both axes within threshold: must resolve deterministically, never to a bogus position.
		const corner = L.snapRect({ x: 398, y: 298, width: 100, height: 100 }, target);
		check('snap: both-axes-in-range picks a real edge, not null',
			corner !== null && (corner.edge === 'right' || corner.edge === 'bottom'));
		check('snap: the chosen position is flush to the target',
			corner !== null && (corner.x === 400 || corner.y === 300));
		// The edge label must name the side of the TARGET, verified by the resulting position
		// rather than by the string — a mislabelled edge is a bug that looks like a snap bug.
		const labelled = L.snapRect({ x: 405, y: 100, width: 200, height: 100 }, target);
		check('snap: edge label names the TARGET side (right => x at target.right)',
			labelled !== null && labelled.edge === 'right' && labelled.x === target.x + target.width);
	}

	// ---- 9. clamping across displays, including the NEGATIVE-origin monitor
	{
		// The real invariant, rather than a magic number: after clamping, at least KEEP px of
		// the pane must lie inside SOME display. Stating it this way is what makes the
		// negative-origin monitor testable at all — "x >= 0" is simply the wrong requirement.
		const KEEP = 80;
		// Reachability, with the same min(KEEP, size) rule the implementation uses: a 44px
		// menu pane can never show 80px, so demanding it would be an impossible requirement
		// rather than a real one.
		const onSomeDisplay = (r) => DISPLAYS.some(d => {
			const vx = Math.min(r.x + r.width, d.bounds.x + d.bounds.width) - Math.max(r.x, d.bounds.x);
			const vy = Math.min(r.y + r.height, d.bounds.y + d.bounds.height) - Math.max(r.y, d.bounds.y);
			return vx >= Math.min(KEEP, r.width) && vy >= Math.min(KEEP, r.height);
		});

		// Already fully visible on the negative-origin monitor: must be left ALONE. Forcing
		// it to >= 0 would yank a window the user deliberately parked off the primary screen.
		const parked = { x: -1800, y: -100, width: 400, height: 200 };
		const c = L.clampToDisplay(parked, DISPLAYS);
		check('clamp: a fully-visible rect on a negative-origin display is untouched',
			c.x === parked.x && c.y === parked.y,
			);
		check('clamp: negative-origin display is honoured (x stays negative)', c.x < 0);

		// Way off every screen: must come back, and must be grabbable afterwards.
		const far = { x: 99999, y: 99999, width: 400, height: 200 };
		const c2 = L.clampToDisplay(far, DISPLAYS);
		check('clamp: an off-screen rect is pulled back', c2.x < 99999 && c2.y < 99999);
		check('clamp: the recovered rect is grabbable on some display', onSomeDisplay(c2));

		// Partially hanging off the primary: enough is visible, so leave the user's placement.
		const hang = { x: -50, y: -50, width: 300, height: 200 };
		const c3 = L.clampToDisplay(hang, [{ id: 1, bounds: { x: 0, y: 0, width: 1366, height: 768 } }]);
		check('clamp: a pane hanging slightly off the edge keeps the user\'s position',
			c3.x === hang.x && c3.y === hang.y);
		check('clamp: single-display (laptop) still guarantees grabbability',
			onSomeDisplay(L.clampToDisplay({ x: -5000, y: -5000, width: 300, height: 200 },
				[{ id: 1, bounds: { x: 0, y: 0, width: 1366, height: 768 } }])));
		check('clamp: no displays = no clamping rather than a crash',
			JSON.stringify(L.clampToDisplay({ x: 5, y: 5, width: 1, height: 1 }, [])) === '{"x":5,"y":5,"width":1,"height":1}');
		// A non-finite rect must pass straight through. Arithmetic on it would poison every
		// downstream rect; silently "fixing" it would invent a position the user never chose.
		const nanRect = { x: NaN, y: 0, width: 10, height: 10 };
		const cNaN = L.clampToDisplay(nanRect, DISPLAYS);
		check('clamp: a non-finite rect passes through untouched', Number.isNaN(cNaN.x) && cNaN.y === 0);

		// The group must follow the anchor onto the SECOND monitor, not snap back to primary.
		const s = gapless();
		const onSecond = L.movePane(s, 'output', { x: 2000, y: 300, width: INPUT.width, height: OUT_H });
		const snapped = L.relayout(onSecond);
		check('multi-display: group moved to display 2 stays there',
			snapped.panes.menu.rect.x === 2000 && snapped.panes.menu.rect.y === expectY('menu', 300));
		// Stacking DOWNWARD from an anchor near the bottom of the screen pushes the lower panes off
		// it. So the clamp has to be applied to every pane of the group, not just the anchor —
		// otherwise the group comes back "visible" with its composer window unreachable.
		const bottomEdge = L.clampToDisplay({ x: 900, y: 1000, width: INPUT.width, height: OUT_H }, DISPLAYS);
		const groupAtBottom = L.relayout(L.movePane(gapless(), 'output', bottomEdge));
		// NOTE: clamp every pane and do NOT relayout afterwards. relayout re-derives the linked
		// panes from the anchor, which silently undoes the clamp — an ordering trap worth
		// asserting on, because it is the natural way to write this and it does not work.
		const clampedGroup = {
			anchor: groupAtBottom.anchor,
			panes: Object.fromEntries(L.PANE_ORDER.map(r => [r, {
				...groupAtBottom.panes[r],
				rect: L.clampToDisplay(groupAtBottom.panes[r].rect, DISPLAYS),
			}])),
		};
		const afterRelayout = L.relayout(clampedGroup);
		// The pane that falls off the bottom is whichever is LAST in the stack — the composer now.
		check('multi-display: relayout AFTER clamping undoes the clamp (ordering trap)',
			!onSomeDisplay(afterRelayout.panes[L.PANE_ORDER[L.PANE_ORDER.length - 1]].rect));
		check('multi-display: a clamped anchor drags the whole group with it',
			clampedGroup.panes.output.rect.x === clampedGroup.panes.input.rect.x);
		check('multi-display: every pane of a bottom-edge group is grabbable',
			L.PANE_ORDER.every(r => onSomeDisplay(clampedGroup.panes[r].rect)));
		// Document the ordering requirement this test just encoded: clamping the anchor ALONE
		// is insufficient, and the fix is cheap — but it has to be deliberate.
		const anchorOnly = L.relayout(L.movePane(gapless(), 'output', bottomEdge));
		check('multi-display: clamping only the anchor WOULD strand the composer (regression guard)',
			!onSomeDisplay(anchorOnly.panes.input.rect));
	}

	// ---- 10. drift budget
	{
		const s = gapless();
		// A LINKED NON-anchor, or there would be nothing to measure: moving the anchor is a
		// legitimate group move, not drift.
		const near = L.movePane(s, 'input', { x: INPUT.x + 399, y: INPUT.y + 399, width: INPUT.width, height: INPUT.height });
		check('drift: within budget still counts as linked', L.isStillLinked(near, 'input') === true);
		check('drift: an untouched pane is trivially still linked', L.isStillLinked(s, 'menu') === true);
		const cfg = { maxDrift: 10 };
		check('drift: a custom budget is honoured', L.isStillLinked(near, 'input', cfg) === false);
	}

	// ---- 11. persistence round-trip and hostile input
	{
		const s = L.setLinked(gapless(), 'menu', false);
		const stored = L.saveLayout(s, 6);
		const back = L.loadLayout(stored, gapless());
		check('persist: round-trip keeps rects',
			JSON.stringify(back.panes.output.rect) === JSON.stringify(s.panes.output.rect));
		check('persist: round-trip keeps the detached flag', back.panes.menu.linked === false);
		check('persist: round-trip keeps the anchor', back.anchor === 'output');
		check('persist: does not alias the saved object', back.panes.input.rect !== stored.panes.input.rect);
		check('persist: the stack order is recorded, so an old layout can be recognised',
			stored.v === L.STACK_ORDER_VERSION);

		// This data is off disk and may be from an older build. One NaN makes the group
		// undraggable with no visible cause, so every field gets validated.
		const hostile = [
			null, undefined, 42, 'nope', [], {},
			{ panes: null }, { panes: {} }, { panes: { input: null } },
			{ panes: { input: { rect: null, linked: true } } },
			{ panes: { input: { rect: { x: NaN, y: 0, width: 10, height: 10 } } } },
			{ panes: { input: { rect: { x: 'a', y: 0, width: 10, height: 10 } } } },
			{ panes: { input: { rect: { x: 0, y: 0, width: Infinity, height: 10 } } } },
			{ anchor: 'evil', panes: {} },
		];
		let allOk = true;
		for (const h of hostile) {
			const r = L.loadLayout(h, gapless());
			if (L.PANE_ORDER.some(role => {
				const rect = r.panes[role].rect;
				return !Number.isFinite(rect.x) || !Number.isFinite(rect.y)
					|| !Number.isFinite(rect.width) || !Number.isFinite(rect.height);
			})) { allOk = false; out.push('   -> leaked from ' + JSON.stringify(h)); }
		}
		check('persist: every hostile input falls back to finite rects', allOk);
		// The stored anchor is never honoured — `stackY` only goes downward, so the anchor has to
		// be the top pane and no stored value can say otherwise. See the note on `loadLayout`.
		check('persist: the anchor is always the top pane, whatever was stored',
			L.loadLayout({ anchor: 'evil' }, gapless()).anchor === L.PANE_ORDER[0]
			&& L.loadLayout({ anchor: 'input', v: L.STACK_ORDER_VERSION }, gapless()).anchor === L.PANE_ORDER[0]);
		check('persist: unknown role keys are ignored, PANE_ORDER is the source of truth',
			Object.keys(L.loadLayout({ panes: { bogus: { rect: { x: 1, y: 1, width: 1, height: 1 } } } }, gapless()).panes).length === 3);
	}

	// ---- 11b. a layout stored under the OLD stack order must not come back as it was
	// The composer was the top pane before, so those rects describe a group the current engine
	// would never produce: the anchor would sit where the composer used to be and everything else
	// would stack below it, off the bottom of the screen.
	{
		const oldStored = {
			panes: {
				input: { rect: { x: 100, y: 200, width: 520, height: 120 }, linked: true },
				menu: { rect: { x: 100, y: 320, width: 520, height: 44 }, linked: false },
				output: { rect: { x: 100, y: 364, width: 520, height: 300 }, linked: true },
			},
			anchor: 'input',
			gap: 0,
			// no `v` — this is what the previous build wrote
		};
		const back = L.loadLayout(oldStored, gapless());
		check('migrate: the transcript is on top after loading an old layout',
			back.panes.output.rect.y === INPUT.y);
		check('migrate: and the composer is at the BOTTOM',
			back.panes.output.rect.y < back.panes.menu.rect.y && back.panes.menu.rect.y < back.panes.input.rect.y);
		check('migrate: the anchor follows the new order rather than the stored value',
			back.anchor === L.PANE_ORDER[0]);
		check('migrate: the detached flag is preserved across the reorder',
			back.panes.menu.linked === false);
		// Reading an ABSENT flag as false would silently detach the whole group on the first launch
		// after the upgrade — three windows that no longer follow each other, and no error.
		const noFlags = L.loadLayout({ panes: { input: { rect: { x: 1, y: 1, width: 1, height: 1 } } } }, gapless());
		check('migrate: a missing link flag means LINKED, not detached',
			L.allLinked(noFlags) === true);
	}

	// ---- 11c. planBounds: the rule that pushes rectangles onto real windows
	// This is the piece that used to live on FloatHost, where nothing could test it — and it
	// skipped every pane with linked === false, i.e. exactly the panes that have their own
	// position. So an unlinked pane could not be dragged anywhere and said nothing about it.
	{
		const s = gapless();
		const at = (role) => ({ x: s.panes[role].rect.x, y: s.panes[role].rect.y });
		check('bounds: nothing to do when every window is already where it belongs',
			L.planBounds(s, at).length === 0);
		check('bounds: a missing or destroyed window is skipped, not moved to 0,0',
			L.planBounds(s, (r) => (r === 'menu' ? null : at(r))).every(m => m.role !== 'menu'));

		const moved = L.movePane(s, 'output', { x: 400, y: 500, width: INPUT.width, height: OUT_H });
		const from = () => ({ x: 0, y: 0 });
		const moves = L.planBounds(moved, from);
		check('bounds: every pane of a moved group is repositioned',
			moves.length === 3 && L.PANE_ORDER.every(r => moves.some(m => m.role === r)));
		check('bounds: positions are rounded, so a fractional rect cannot loop forever',
			moves.every(m => Number.isInteger(m.x) && Number.isInteger(m.y)));

		// THE regression. A detached pane owns its rect, so it must be pushed like any other.
		const detached = L.movePane(L.setLinked(moved, 'input', false), 'input',
			{ x: 900, y: 900, width: INPUT.width, height: INPUT.height });
		const dMoves = L.planBounds(detached, from);
		check('bounds: a DETACHED pane is still moved — it owns its rect',
			dMoves.some(m => m.role === 'input' && m.x === 900 && m.y === 900));
		check('bounds: detaching one pane does not stop the others from moving',
			dMoves.some(m => m.role === 'output'));
	}

	// ---- 12. degenerate input must not produce NaN geometry
	{
		const zeros = L.defaultLayout({ x: 0, y: 0, width: 0, height: 0 }, 0, 0);
		check('degenerate: zero-size panes give finite geometry',
			L.PANE_ORDER.every(r => Number.isFinite(zeros.panes[r].rect.y)));
		const moved = L.movePane(zeros, 'input', { x: 10, y: 10, width: 0, height: 0 });
		check('degenerate: moving a zero-size anchor stays finite',
			L.PANE_ORDER.every(r => Number.isFinite(moved.panes[r].rect.y)));
		// A gap larger than the screen is nonsense input but must not loop forever.
		const bigGap = L.defaultLayout(INPUT, MENU_H, OUT_H, { gap: 1e6 });
		check('degenerate: an absurd gap still terminates with finite values',
			L.PANE_ORDER.every(r => Number.isFinite(bigGap.panes[r].rect.y)));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});