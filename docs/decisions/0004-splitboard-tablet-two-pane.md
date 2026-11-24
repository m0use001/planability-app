# ADR 0004 — One two-pane `SplitBoard` skeleton for wide layouts

Status: accepted

## Context

Phone screens use single-column screens; tablets (600–1600 dp and up) have
room for a master-detail layout, but each screen inventing its own two-pane
layout produced inconsistent headers, dividers, insets and selection behavior.
The first tablet pass shipped per-screen two-pane code, and the drift was
immediate: double top insets, right pane sliding under the top bar, master
lists with different row heights per screen.

## Options

1. **Per-screen two-pane layouts** — rejected: five screens produced five
   dialects of the same idea.
2. **A shared two-pane skeleton** (`SplitBoard`) with a pure-function
   breakpoint helper, used by chat, plans, notes, files, memory, automation.
3. **Force single column on tablets** — rejected: wastes the form factor.

## Decision

Option 2:

- `ui/common/SplitBoard.kt` provides the material: boards, board headers,
  row dividers, and the two-pane layout. A pure function
  (`MasterDetail.paneOf`-style) decides "is there room for two panes, and is
  something selected" — in that order: **width first, selection second**.
  An unselected right pane shows an overview/placeholder, never an empty void.
- Scaffold padding and window insets are applied by the host onto the
  SplitBoard container once, never per pane (double insets were a real bug).
- The desktop client mirrors the same logic in
  `desktop/src/renderer/src/lib/masterDetail.ts` so both platforms break at
  the same widths.
- Guard tests (`SplitBoardTest`, `MasterDetailGuardTest`) pin the
  breakpoint and ordering rules; reverting them goes red.

## Evidence

- `app/src/main/java/io/github/m0use001/planability/ui/common/SplitBoard.kt`
- `app/src/test/java/io/github/m0use001/planability/ui/common/SplitBoardTest.kt`
- `app/src/test/java/io/github/m0use001/planability/ui/common/MasterDetailGuardTest.kt`
- `desktop/src/renderer/src/lib/masterDetail.ts`
