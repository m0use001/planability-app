# Architecture

This document describes how Planability is structured and, more importantly,
the invariants that keep it coherent. Code paths refer to this repository.

## 1. Platform overview

Two clients, no shared runtime code:

| | Android | Desktop |
|---|---|---|
| Stack | Kotlin 2.2, Jetpack Compose, Room/SQLite, Glance | Electron, React 18, TypeScript, better-sqlite3 |
| Layout | `app/` | `desktop/` |
| Data source | on-device SQLite | reads project data via its own IPC bridge |

The two clients **share contracts, not code**: the tool list, the dump
collections, and the memory export format are each kept in sync manually and
pinned by guard tests on both sides. When a contract changes, both sides
change in the same commit series and the guard tests on each side go red if one
lags.

## 2. The three invariants

1. **Disk/SQLite is the only source of truth.** Markdown exports, desktop
   caches and widgets are all derived. Anything derivable must be rebuildable
   from the database; nothing treats a mirror as authoritative.
2. **AI never mutates real data directly.** Every AI-produced schedule/todo
   lands as a *draft* in an assistant inbox. `ai/ptl/PendingImporter.kt` is the
   single ingestion channel; commit-time payload reconstruction preserves
   recurrence, priority, urgency and tags instead of re-parsing display text.
3. **UI decisions are verified, not assumed.** Layout changes are validated on
   devices via screenshots/UI dumps, and source-structure guard tests assert
   key layout decisions directly in the code — reverting them fails CI.

## 3. Android client

```
app/src/main/java/io/github/m0use001/planability/
  ai/        model clients (OpenAI-compatible), tool definitions & dispatch,
             context engineering, memory pipeline, retrieval (FTS + vectors)
  core/      windowing/theming/haptics, shell chrome, app time, geometry
  data/      Room entities & DAOs, backup manager, sync, geo codec
  ui/        home (plans/calendar) · notes · info (chat/workbench) ·
             focus · files · profile · common (SplitBoard etc.)
  widget/    four Glance-based home-screen widgets
```

### AI pipeline

- `ai/ptl/ToolDefs.kt` declares every tool with a `Risk` level
  (`AUTO` / `CONFIRM` / `DANGER`). `AUTO` runs without prompting,
  `CONFIRM` asks, `DANGER` is blocked behind explicit confirmation.
- Tools route through a dispatcher with per-tool executors; a wiring guard
  test asserts every declared tool appears in the executor dispatch table, so
  "declared but unroutable" tools cannot exist.
- Memory is layered: L1 atomic entries (with an evidence table linking each
  entry to the messages that support it), L2 scenes, L3 persona. Retrieval
  fuses lexical FTS and vector similarity with RRF; entries are invalidated
  (`invalid_at`) rather than deleted, preserving an audit trail.
- On-device inference is optional: an OpenAI-compatible local endpoint backed
  by Qualcomm GenieX (Snapdragon NPU) and ONNX embedding models for retrieval.

### Draft gate

`ai/ptl/PendingImporter.kt`, `ai/ptl/PendingFilter.kt` and
`data/PendingDedup.kt` form the review pipeline: drafts are deduplicated
(repeated extraction from the same group/day collapses), then either committed
(with overrides) or discarded. Deduplication was driven by a real bug class:
repeated extraction passes used to re-insert the same events with slightly
different time phrasings.

## 4. Desktop client

`desktop/` is an Electron + React + TypeScript client built on Material Web
Components tokens. It mirrors the Android tool face over its own IPC bridge and
keeps the memory format, tool names and dump collections in sync by contract.

## 5. Data safety

- Full-database encrypted snapshots to `Download/Planability/` on every
  background transition (AES-256-GCM, key in Android Keystore, throttled and
  rotated). Snapshots are intentionally unreadable off-device after uninstall —
  the key does not survive it.
- `tools/device-guard/` (PC side) snapshots before any install, refuses
  non-debuggable builds, and hard-blocks Gradle `connected*AndroidTest` tasks
  targeting the daily-driver device (a Gradle-level check fails the build in
  seconds, before any device action). The same guard is enforced at build time
  inside the Android Gradle script.

## 6. Testing posture

- ~1,800 JVM unit tests, including:
  - pure-function state-matrix tests (todo progress, recurrence phases)
  - source-structure guards that assert layout/contract decisions in source
  - data-driven extraction stability guards
- Instrumented tests exist for widgets and rendering, but are run on
  emulators/dedicated devices — never through Gradle on a daily-driver phone.

## 7. Directory map (top level)

| Path | Role |
|---|---|
| `app/` | Android client (the main product) |
| `desktop/` | Desktop client |
| `tools/` | course import, device guard, install gate |
| `docs/` | architecture and decision records |
| `gradle/` | wrapper & version catalog |
