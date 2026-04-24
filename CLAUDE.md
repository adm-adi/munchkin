# CLAUDE.md

This file provides guidance to Claude Code and Codex when working in this repository.

## Project Overview

Munchkin Mesa Tracker is an Android app plus Kotlin backend for synchronized Munchkin board-game sessions. The app keeps the familiar table flow, while the backend is authoritative for live gameplay and persistent account/catalog/history data.

## Required Context

Before changing code, read:

- `docs/PROBLEMS_AND_SOLUTIONS.md`
- `docs/IMPLEMENTATION_LOG.md`
- `CODEBASE.md`

After implementation sessions, update the two docs above with new problems, fixes, and architectural changes.

## Build Commands

```powershell
.\gradlew.bat :shared:test
.\gradlew.bat :backend:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

Backend distribution:

```powershell
.\gradlew.bat :backend:installDist
.\gradlew.bat :backend:distZip
```

Database migrations:

```powershell
.\gradlew.bat :backend:migrateDatabase
```

Legacy SQLite import:

```powershell
.\gradlew.bat :backend:importLegacySqlite --args "C:\path\to\munchkin.db"
```

## Architecture

The dependency flow is:

```text
Android app -> shared <- backend
```

`shared` owns ids, DTOs, game state, combat state, websocket protocol, and pure rule logic. Keep it free of Android and server-only dependencies.

The websocket protocol is gameplay-only. Auth, profile, catalog, history, leaderboard, open games, and hosted-game management belong to HTTP DTOs and routes; keep those DTO declarations in `ApiModels.kt`, not `Protocol.kt`.

The backend is a Ktor service with PostgreSQL persistence and Flyway migrations. Gameplay rooms are authoritative in memory; finished games, users, monsters, history, and leaderboard data are persistent.

The Android app uses:

- `RealtimeGameSession` for the long-lived gameplay websocket.
- `CreateGameRequest` must be serialized with kotlinx serialization; do not manually interpolate websocket JSON.
- `WsEndpointParser` owns realtime websocket URL normalization; do not reintroduce ad-hoc regex parsing in `GameClient`.
- `SavedGameStore`, `PlayerIdentityStore`, and `RealtimeSessionFactory` seams around `GameViewModel` so core gameplay flows can be JVM-tested.
- `ApiClient` plus feature repositories for auth, profile, catalog, history, leaderboard, and game discovery.
- Repository/session interfaces on the app side so feature ViewModels can be unit-tested without Android context or live HTTP clients.
- `AccountViewModel` for session restore, login/register, logout, and profile updates.
- `HistoryViewModel` for history and leaderboard screens instead of routing those calls through gameplay state.
- `CatalogViewModel` for monster catalog search/create; combat only consumes created monsters as gameplay events.
- `GameDirectoryViewModel` for open-game discovery and hosted-game deletion/list state.
- `UpdateViewModel` for GitHub release checks and APK update download state.
- `MunchkinNavHost` for navigation-compose routing and active-route ownership.
- `GameEventFactory` for Android-side construction of shared gameplay events.
- `PlayerIdFactory` for deterministic gameplay identity creation in tests and UUID-backed production ids.
- `GameTextProvider` and `FriendlyErrorMapper` for testable gameplay resource text and connection-error mapping.
- `GameStateTransitionAnalyzer` for pure gameplay transition side effects that should stay unit-tested outside `GameViewModel`.
- `RealtimeSession.gameDeleted` for typed room-deletion cleanup; do not route this through localized error strings.

`AppConfig` is the single app-side source for the remote backend URL.

## Golden Rule

Clients do not mutate gameplay state directly. UI actions create shared `GameEvent` values, the backend validates and applies them through `GameEngine`, then clients rebuild UI from snapshots or validated broadcasts.

## Serialization Constraints

- `@JvmInline value class` ids serialize as primitive strings.
- Sealed websocket messages use `classDiscriminator = "type"`.
- Enum and `@SerialName` values are wire protocol, not cosmetic names.

## Development Rules

- User-facing strings belong in resources. Keep Spanish and English complete when adding text.
- Keep max players, level clamping, race/class limits, and win confirmation server-authoritative.
- Wrap websocket JSON parsing in `try-catch`.
- Do not reintroduce embedded Android host, LAN discovery, Node server, or host handover code without a new ADR.

## Production

Backend deploy:

```powershell
.\deploy-backend-prod.ps1
```

Local Android install:

```powershell
.\deploy_updates.ps1
```
