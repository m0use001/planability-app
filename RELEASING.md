# RELEASING

How a release of Planability is cut and published.

## Release model

This repository publishes **source snapshots**: every release is one commit
that builds and passes its test suite on its own. History is the release log —
there is no long-lived development branch in public; development happens in a
private upstream and is exported here.

## Cutting a release

1. **Export** — the upstream project maintains an export pipeline that copies
   the release source into a clean tree (excluding internal tooling, memory
   logs and any private context) and applies its sanitization checklist.
2. **Gates (all must be green before anything is pushed):**
   - `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` — build + full
     unit suite
   - `desktop/`: `npm install && npm run build && npm test`
   - secret scan over the tree (gitleaks or equivalent) — zero findings
   - the maintainer's private-context checklist (names, places, account
     identifiers, internal doc references) — zero findings
3. **Commit** the exported tree as a single release commit:
   `release vX.Y.Z`.
4. **Tag** `vX.Y.Z` and push.

## Versioning

- `MAJOR.MINOR.PATCH`; 1.0.x is bug-fix only, features go to 1.1+.
- The Room database version (`data/AppDatabase.kt`) must be bumped with a
  migration whenever the schema changes — releases never ship a schema change
  without its migration and a shape test.

## Dependency and license hygiene

- New dependencies must have a GPL-3.0-compatible license; add them to
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) in the same release.
- Vendored assets (anything under `app/src/main/assets/`) need an explicit
  source + license row in the notices file.
- Run `./gradlew :app:dependencies` / `npm ls --all --json` when in doubt.

## Localization

The app UI is currently zh-CN only (see [docs/i18n.md](docs/i18n.md)); an
incomplete translation must not half-ship a screen.
