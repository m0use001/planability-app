# ADR 0003 — Risk-tiered tool face with a wiring guard

Status: accepted

## Context

An assistant with dozens of tools that read and write personal data (schedules,
todos, notes, memory, files, screen usage) needs an explicit answer to "what
may this tool do without asking me?" — per tool, not per vibe. It also needs to
guarantee that every declared tool is actually reachable, because a tool that
is declared to the model but missing from the dispatch table fails silently at
runtime and looks like a model problem rather than an integration bug.

## Options

1. **Global permission switch (all tools or none)** — rejected: too coarse;
   reading a note and deleting a schedule are not the same risk.
2. **Per-tool risk levels + automatic dispatch auditing** — chosen.
3. **Interactive confirmation for every tool call** — rejected: unusable in
   practice; the assistant does many safe reads per useful write.

## Decision

Option 2:

- Every tool declaration in `ai/ptl/ToolDefs.kt` carries a `Risk`:
  - `AUTO` — runs without prompting (reads, queries, low-impact writes that
    go through the draft gate anyway)
  - `CONFIRM` — asks the user first
  - `DANGER` — destructive (delete/bulk operations), blocked behind explicit
    confirmation
- A source-structure guard test asserts that every tool declared in
  `ToolDefs.kt` appears as a literal in the executor dispatch source. A tool
  that is declared but unroutable turns the test red instead of failing at
  runtime with a confusing model-side error.

## Evidence

- `app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt` —
  `enum class Risk { AUTO, CONFIRM, DANGER }` and per-tool risk assignments
- The dispatch wiring guard (tool-name literal assertion in test sources;
  see the tool dispatch wiring test under `app/src/test/.../ai/`)
