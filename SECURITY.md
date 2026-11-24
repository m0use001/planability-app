# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | yes |

## Reporting a vulnerability

Please open a [GitHub Security Advisory](../../security/advisories/new) rather
than a public issue for anything that could expose user data or allow
unintended actions by the AI assistant (tool abuse, sandbox escape, path
traversal in the file hub, etc.).

You can expect an initial response within a week.

## Scope notes

This is a local-first app: it ships without cloud services, so the main attack
surfaces are:

- the AI tool face (what an assistant can be talked into executing)
- the file hub and document readers (parsing untrusted docx/xlsx/pdf/ppt)
- the desktop client's IPC bridge and its exec/path-handling tools
- the Syncthing integration

Reports about the bundled third-party components should go upstream; the
notable ones are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## What we will not claim

Release builds are signed with a debug key and are intended for personal use;
they are not distributed through an app store. We do not represent the project
as having completed a formal security audit.
