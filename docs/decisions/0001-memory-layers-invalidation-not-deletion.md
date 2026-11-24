# ADR 0001 — Layered memory with invalidation instead of deletion

Status: accepted (v45) · Supersedes: flat single-table memory

## Context

The assistant needs long-term memory that survives sessions, but the first
implementation was a single flat table with same-name overwrite and no
provenance: it could not answer "which messages support this fact?", could not
represent "this was true then, not now", and hard-deleted entries on update,
destroying history.

A survey of open-source memory systems (ReMe, Honcho, Graphiti, TencentDB
memory) found the recurring pattern: memory-as-files, evidence chains,
time-based validity, and layered summaries. None of them were usable as a
runtime dependency on an Android client (server processes, graph databases,
vector stores).

## Options

1. **Adopt a memory framework as a runtime dependency** — rejected: violates
   the no-server/no-extra-runtime constraint of a local-first mobile app.
2. **Copy the mechanisms, not the code**: rebuild evidence links, validity
   windows, tombstones and layered summaries natively in Room/SQLite.
3. **Keep the flat table and add columns ad hoc** — rejected: same-name
   overwrite without tombstones still loses history.

## Decision

Option 2. Concretely:

- L1 atomic entries (`memories`) gain `valid_at` / `invalid_at` /
  `superseded_by` columns; updates create a new version and invalidate the old
  one — **nothing is deleted**.
- A separate evidence table links every memory entry to the message IDs that
  support it; every long-term fact must be traceable.
- L2 scenes ("who with, doing what") and L3 persona (single-row long-term
  profile) sit above L1 and feed the prompt with a token budget:
  persona → scene index → retrieved entries → raw evidence on demand.
- Retrieval fuses lexical FTS and vector similarity with RRF (k=60).
- The DB is an index/acceleration layer; an authoritative human-readable
  markdown mirror is exported alongside, so memory survives even outside the app.
- Automatic consolidation may only add or invalidate, never hard-delete.

## Evidence

- `app/src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt` — schema,
  scoring, evidence, validity columns
- `app/src/main/java/io/github/m0use001/planability/ai/MemoryConsolidator.kt` —
  ADD/UPDATE/DELETE/SKIP planner with evidence requirement
- `app/src/main/java/io/github/m0use001/planability/ai/MemoryPipeline.kt` —
  consolidation scheduling
- `app/src/main/java/io/github/m0use001/planability/data/MemoryFiles.kt` —
  markdown mirror export (the "assets, not DB" half of the invariant)
- Unit tests: memory format/op-plan/progress suites under `app/src/test/.../ai/`
