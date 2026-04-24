# ADR-0001: Replace the Node/SQLite backend with Kotlin/Ktor/PostgreSQL

## Status

Accepted

## Context

The project drifted into an inconsistent state:

- Android still carries embedded-host and LAN-discovery code.
- The server uses hand-written JSON/JWT logic that is hard to keep aligned with the Android models.
- Core domain types and transport contracts live inside the Android module instead of a shared module.
- Verification and deployment scripts are tied to the legacy Node/SQLite backend.

## Decision

Adopt a Kotlin-first architecture:

- `shared` becomes the single source of truth for domain models, rules, and wire contracts.
- `backend` becomes a Ktor server using PostgreSQL plus Flyway migrations.
- The Android app becomes a remote-only client with dedicated realtime and API layers.

## Consequences

Positive:

- One typed contract for Android and backend.
- Safer auth handling with Ktor JWT integration and BCrypt password hashing.
- Explicit schema migrations and a path away from ad hoc SQLite evolution.
- Cleaner separation between realtime game transport and HTTP account/catalog/history APIs.

Negative:

- The migration is multi-phase and cannot be treated as a single-file refactor.
- Existing Node deployment scripts and assumptions must be retired.
- Live in-progress rooms are not preserved across the final backend cutover.
