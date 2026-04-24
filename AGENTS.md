# AGENTS.md

## Required Context

Before changing code, read:

- `docs/PROBLEMS_AND_SOLUTIONS.md`
- `docs/IMPLEMENTATION_LOG.md`
- `CODEBASE.md`
- `CLAUDE.md`

After implementation sessions, update `docs/PROBLEMS_AND_SOLUTIONS.md` and `docs/IMPLEMENTATION_LOG.md`.

## Editing Rules

- Never replace this whole file if a `## Session Memory` section is added later.
- If editing this file, use an in-place patch and keep manual edits above any generated session memory section.
- Preserve user changes and never revert unrelated work.

## Project Rules

- `shared` is the single source of truth for ids, DTOs, game state, protocol messages, and pure rules.
- Android depends on `shared`.
- Backend depends on `shared`.
- `shared` must not depend on Android or backend-only libraries.
- Do not reintroduce the legacy Node server, embedded Android host, LAN discovery, or host handover code without a new ADR.
