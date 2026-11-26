# Changelog

Before 1.0.0 the project had no version tags; internal iterations advanced
through working versions v1–v77. Semantic versioning starts at 1.0.0.

## [1.1.0] — 2026-11-27

### AI workbench
- Fix: writer/creative assistants could not delete or manage memories — their
  tool toggle defaulted to off, so the tool list was never sent to the model
  (existing cards are upgraded in place)
- Fix: memory cross-project bleed — retrieval/renaming/moving are strictly
  project-scoped; batch deletion gains a hard gate; deleted memories no longer
  linger in exports

### Architecture
- Device-specific capabilities (message capture, phone control, shell access)
  and the Minecraft cover-art pack are now **optional source sets**: the core
  app is unaffected; when absent, related entries hide themselves and tools
  stay unregistered (explicit failure instead of silent breakage)
- Data layer KMP phase 1: entities/DAOs/pure logic moved to a shared module

### UI
- Page transitions use M3 Expressive emphasized easing (no pre-jump on
  multi-page flicks); "now" line simplified to a plain thin line

### Tests
- 1768 unit tests green; guard tests skip optional-source-set assertions
  automatically when those source sets are absent

## [1.0.0] — 2026-11-22

First stable milestone: feature surface consolidated, both platforms usable,
tablet adaptation complete. Baseline of capabilities shipped in 1.0:

### AI workbench
- Multi-session chat: five thinking levels (off/adaptive/low/high/max),
  streaming, stop/regenerate, context compaction with per-turn restore
- Tool face: schedule/todo/note/memory/file/screen-time tools with risk tiers
  and an assistant-inbox draft gate (AI output is reviewed before it enters the DB)
- Layered memory (scenes/persona/entries) + semantic retrieval + dual-path FTS
  fused with RRF; invalidation instead of deletion, evidence links, conflict states
- Provider configuration, cross-window context budgeting, character-card
  persona system (macro expansion / quirk blocklist / seed greetings)
- On-device models: Qualcomm NPU (GenieX) + ONNX semantic retrieval
  (lightweight/heavyweight tiers, downloaded on demand)

### Planning
- Schedule/todo dual model; cross-platform recurrence contract; check-ins and progress
- Calendar 3-day/week/month views; drag-to-reschedule (15-min snapping,
  cross-day, recurring items locked)
- Home timeline, today overview, untimed-plan entry point, weekly report and achievements

### Notes & knowledge base
- Markdown notes, `[[wikilink]]` backlinks, tag system
- FTS + vector semantic retrieval fused; "ask the knowledge base" multi-turn
  Q&A with explicit refusal on low evidence coverage
- Formula rendering pipeline (jlatexmath): tall inline formulas promoted to
  block, direct CJK annotation rendering, underbrace/overbrace protection, gathered line breaking

### Focus / files / widgets
- Focus timer (pomodoro, duration presets, overview board)
- File hub (local folders as source of truth + Syncthing sync); docx/xlsx/pptx/
  ppt/pdf/image-OCR readers; bilingual side-by-side document & web reader
- Four home-screen widgets: today timeline, today stats, multi-day overview,
  screen-time breakdown

### Tablet & desktop
- Width breakpoints + two-pane `SplitBoard` skeleton: chat/plans/notes/files/
  memory/automation pages all get wide-screen two-pane layouts
- In-page two-pane chat, tool rows inline with message text, input area fills the pane
- Desktop contract sync (tool list / dump collections / memory format)

### Interaction details (1.0 wrap-up)
- Small fixed-set single-choice rows inside boards/cards unified into a
  dropdown chip (`SelectDropdownChip`) instead of horizontal scrolling
- Hub top segmented switcher with sliding-pill animation
- Fixed "jump to top after send": snapToBottom offset integer overflow

### Infrastructure
- Data safety: full-database AES-256-GCM encrypted backup (Android Keystore),
  install guard (snapshot/restore)
- ~1,800 unit tests, including source-structure guard tests (key layout and
  contract decisions turn tests red when reverted)
- 16 KB page alignment (onnxruntime 1.22)
