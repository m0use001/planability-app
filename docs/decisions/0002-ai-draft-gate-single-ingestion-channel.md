# ADR 0002 — AI output enters real data only through a review draft gate

Status: accepted

## Context

Tool-calling assistants that write directly to the user's real schedule/todo
database fail in two ways: hallucinated or malformed writes end up in real
data, and once written they are hard to tell apart from human entries. Early
versions of this app let AI tools create todos/schedules directly, and the
result was duplicated and wrong entries that users had to clean up manually.

## Options

1. **Direct writes with prompts begging the model to be careful** — rejected:
   prompt-level discipline is not a mechanism; it fails exactly when the model
   is weakest (long context, unusual input).
2. **Post-hoc undo (soft-delete AI entries)** — rejected: cleanup is still the
   user's job; soft-deleted entries still pollute views and widgets.
3. **Draft gate**: every AI-produced schedule/todo lands as a *pending draft*
   in an assistant inbox; a human reviews and commits; a single importer
   converts drafts to real rows.

## Decision

Option 3, with two reinforcing rules:

- **`PendingImporter` is the only ingestion channel** from drafts to real
  schedule/todo rows. Both the chat-side commit tool and the inbox card's
  confirm button call the same importer, so behavior cannot drift.
- Commit reconstructs rows from the draft's **original tool payload** (recurrence,
  priority, urgency, tags), not from display text — the draft carries
  structured data, and the importer replays it faithfully, applying only
  explicit human overrides (e.g. a corrected time).
- Drafts are deduplicated: repeated extraction from the same source in the
  same day collapses into one draft (this was a real bug class — repeated
  extraction passes re-inserted the same events with slightly different time
  phrasings, dozens per day).

## Evidence

- `app/src/main/java/io/github/m0use001/planability/ai/ptl/PendingImporter.kt` —
  the single ingestion channel (doc comment states the invariant)
- `app/src/main/java/io/github/m0use001/planability/ai/ptl/PendingFilter.kt` —
  draft filtering
- `app/src/main/java/io/github/m0use001/planability/data/PendingDedup.kt` —
  draft deduplication, with the incident documented in-code
- `app/src/test/java/io/github/m0use001/planability/data/PendingDedupTest.kt` and
  `app/src/test/java/io/github/m0use001/planability/ai/ptl/PendingFilterTest.kt`
