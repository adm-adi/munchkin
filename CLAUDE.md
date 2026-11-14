# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in the **Munchkin Mesa Tracker** repository.

## Project Overview

**Munchkin Mesa Tracker** is a synchronized multiplayer Munchkin tabletop tracker composed of:

- **Authoritative Backend + Web PWA (`server/` & `server/public/`)**: Node.js 20+ WebSocket/HTTP server (`server/server.js` exporting `createAppServer`) with SQLite WAL + `withWriteLock` + FTS5 (`server/db.js`), serving a zero-build ES-module PWA (`server/public/`) for iOS, desktop, and mobile browsers.
- **Native Android Client (`app/`)**: Kotlin + Jetpack Compose (`MunchkinApp.container: AppContainer` DI, 14-route Compose `NavHost`, Ktor CIO WebSockets, **Munchkin Dungeon Edition** theme per `DESIGN.md`).

All clients connect to the remote authoritative backend (`wss://munchking-sirpepo.duckdns.org:8765`).

## Build, Validation & Test Commands

### Server (`server/`)

```bash
npm --prefix server audit --audit-level=high
npm --prefix server run check
npm --prefix server test
```

Run locally (see `server/.env.example` for all supported variables):

```bash
cd server
npm ci
JWT_SECRET="replace-with-at-least-32-character-random-secret" npm start
```

### Android Client (`app/`)

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:minifyReleaseWithR8 :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*.CombatCalculatorTest"
./gradlew :app:assembleRelease
```

Target a local/staging server at build time without editing source:

```bash
./gradlew :app:assembleDebug -PmunchkinScheme=ws -PmunchkinHost=10.0.2.2 -PmunchkinPort=8765
```

### Version Sync & Security Probe (`scripts/`)

```bash
python scripts/bump_version.py --check
MUNCHKIN_SERVER_URL=ws://127.0.0.1:8765 python scripts/security_verify.py
```

PowerShell on Windows:

```powershell
$env:MUNCHKIN_SERVER_URL = "ws://127.0.0.1:8765"; python scripts/security_verify.py
```

## Architecture

### The Golden Rule

Clients never authoritatively mutate gameplay state. Every action flows:

1. UI triggers a method on one of the split ViewModels (`AuthViewModel`, `LobbyViewModel`, `GameSessionViewModel`, `CatalogViewModel`) or Web PWA (`server/public/js/app.mjs`).
2. Client transmits a `WsMessage` via `GameClient.kt` (`Closeable`, shared CIO `HttpClient`, `lifecycleMutex`, `nudge()`) or `server/public/js/net.mjs`.
3. `server/server.js` and its manager modules validate session/phase/bounds, mutate `GameRoom`, persist via `server/db.js`, and broadcast snapshots/events.
4. Clients reduce broadcasts via `GameEngine.kt` / `engine.mjs` or adopt `STATE_SNAPSHOT`.

### Core Modules

| Module | Purpose |
| --- | --- |
| `app/.../MunchkinApp.kt` & `di/AppContainer.kt` | Explicit `AppContainer` DI (no static `Context`), `StringResolver`, `GameSessionCoordinator`, `ViewModelProvider.Factory` |
| `app/.../core/` | Domain-pure Kotlin: `Models.kt`, `Events.kt`, `Combat.kt`, `CombatCalculator.kt`, `GameEngine.kt`, `Abilities.kt`, `GameLogEntry.kt` |
| `app/.../data/` | `SessionManager.kt` (`EncryptedSharedPreferences` + KeyStore corruption recovery), `GameRepository.kt`, `MunchkinDatabase.kt` / `SavedGameDao.kt`, `AppSettingsPrefs.kt`, `TutorialPrefs.kt` |
| `app/.../network/` | `GameClient.kt`, `Protocol.kt`, `ServerConfig.kt`, `JoinCodeParser.kt` |
| `app/.../update/UpdateChecker.kt` | HTTPS GitHub allowlist updater with internal storage (`filesDir/updates/`), `.sha256` digest check, signing cert verification, and anti-rollback `longVersionCode` check |
| `app/.../viewmodel/` | `AuthViewModel.kt`, `LobbyViewModel.kt`, `GameSessionViewModel.kt`, `CatalogViewModel.kt`, `SessionGateways.kt`, `GameConnectionSupport.kt`, `UiModels.kt` |
| `app/.../MainActivity.kt` | Single-activity Compose `NavHost` with 14 routes (`home`, `create_game`, `join_game?code={code}`, `lobby`, `table`, `board`, `combat`, `player_detail`, `catalog`, `settings`, `auth`, `profile`, `history`, `leaderboard`) + `TutorialScreen` |
| `app/.../ui/theme/` | **Munchkin Dungeon Edition** tokens (`Color.kt`, `Theme.kt`, `Type.kt`, `AvatarResources.kt` canonical 0..7 mapping) aligned with `DESIGN.md` |
| `server/server.js` | `createAppServer`: HTTP (`/health`, `/api/monsters`, static PWA), WebSocket (`ws`, 50 KB `maxPayload`, 25s heartbeat, `Origin` check, `MUNCHKIN_TRUST_PROXY`), room & event router |
| `server/db.js` | SQLite WAL + `withWriteLock` write queue + `monsters_fts` FTS5 prefix search + `users.token_version` + `active_games` & history persistence |
| `server/authManager.js` | `createAuthManager`: async `bcryptjs`, HS256 JWT (`alg` + `exp` + `token_version` validation), `REGISTER`, `LOGIN`, `LOGIN_WITH_TOKEN`, `UPDATE_PROFILE` (requires `currentPassword`), `DELETE_ACCOUNT` |
| `server/catalogManager.js` | `createCatalogManager`: `CATALOG_SEARCH` (FTS5 + expansion filter, omits `createdBy`) & rate-limited `CATALOG_ADD` (supports 0 treasures) |
| `server/combatManager.js` | `createCombatManager`: authoritative combat math, `VALID_DICE_PURPOSES`, `getRunAwayBonus`, `requiresCombatToWin` |
| `server/gameAdminManager.js` | `createGameAdminManager`: `GAME_OVER`, `GAME_END`, `KICK_PLAYER`, `TRANSFER_HOST`, `SWAP_PLAYERS`, `GET_HOSTED_GAMES`, `DELETE_HOSTED_GAME`, `DELETE_GAME` |
| `server/historyManager.js` | `createHistoryManager`: IDOR-protected `GET_HISTORY`, `GET_LEADERBOARD` (camelCase DTOs, excludes guests) |
| `server/turnManager.js` | `createTurnManager`: turn order & server-enforced clamped turn timers (`MAX_TURN_TIMER_SECONDS = 7200`) |
| `server/validation.js` | Pure enum whitelists, integer clamping, name/gender/monster/bonus sanitization (zero side effects on `require`) |
| `server/staticFiles.js` & `logger.js` | In-memory preloaded `ETag`/Brotli/gzip static server (traversal/dotfile proof), Winston daily rotating logs |
| `server/jsconfig.json` | TypeScript `checkJs: true` config for `server/*.js` |
| `server/public/` | Web PWA (`index.html`, `sw.js`, `manifest.webmanifest`, `css/app.css`, `js/{protocol,combat,engine,net,session,i18n,ui,app}.mjs`) |

### Serialization & Decoding Constraints

- Android `kotlinx.serialization` decoding is strict and snapshot failures break the entire room.
- `server/validation.js` (`VALID_GENDERS`, `VALID_CLASSES`, `VALID_RACES`, `sanitizeConditionalModifier`) must mirror `core/Models.kt` and `core/Combat.kt` enums exactly.
- `ErrorMessage.code` falls back to `ErrorCode.UNKNOWN` via `coerceInputValues`; all other enums require lockstep updates across Kotlin, Node.js, and `server/public/js/protocol.mjs`.
- Response DTOs (`HISTORY_RESULT`, `LEADERBOARD_RESULT`, etc.) must map SQLite snake_case columns (`avatar_id`, `ended_at`) to camelCase (`avatarId`, `endedAt`).

## Testing Architecture

- **Server (`node:test`)**:
  - `server/validation.test.js` & `server/combat.test.js`: unit tests for pure validation/combat logic.
  - `server/protocol.test.js`: end-to-end WebSocket & HTTP integration tests spinning up a real `server.js` subprocess per server via `server/testServer.js` (`PORT`, `MUNCHKIN_DB_PATH`, `MUNCHKIN_LOG_DIR`, `MUNCHKIN_REGISTER_LIMIT`, `MUNCHKIN_CREATE_GAME_LIMIT`).
  - `server/webclient.test.mjs`: tests `staticFiles.js` (MIME types, `ETag`/304/Brotli, CSP, path traversal protection) and drives the real server using `server/public/js/{protocol,net,engine,combat,session}.mjs`.
  - Use `client.drain()` / `await client.settle()` before asserting on protocol responses so stale broadcasts do not race assertions.
- **Android (`app/src/test/`)**:
  - `core/*Test.kt` (8 suites): domain rules, combat breakdown, tie-breaking, run-away modifiers, reducer semantics, level/trait validation.
  - `viewmodel/*Test.kt` + `TestFakes.kt` (4 suites + fakes): `AuthViewModelTest`, `LobbyViewModelTest`, `GameSessionViewModelTest`, `CatalogViewModelTest`.
- **CI Gate (`.github/workflows/ci.yml`)**:
  - Android: `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `lintDebug`, and `minifyReleaseWithR8` (with ephemeral keystore).
  - Server: `npm audit --audit-level=high`, `npm run check`, `npm test`, and live `scripts/security_verify.py` probe.

## Invariants Worth Preserving

- **Never commit `server/munchkin.db`, `server/.env`, `local.properties`, or `munchkin.keystore` secrets.**
- **Never log credentials or tokens.** `WELCOME` carries `reconnectToken`, `AUTH_SUCCESS` carries a JWT, and `proguard-rules.pro` strips `Log.v/d/i` in release builds.
- **Keep `bcryptjs` async** and reject passwords over 72 UTF-8 bytes so login never blocks the Node event loop or silently truncates passwords.
- **Three-engine combat parity**: any change to combat outcome rules must update `core/CombatCalculator.kt`, `server/combatManager.js`, and `server/public/js/combat.mjs`.
- **Web PWA speaks the Android dialect**: `server/public/js/protocol.mjs` builders must include all fields required by Kotlin decoding, and `css/app.css` / `ui.mjs` must keep strict CSP (zero inline `style="..."` or `innerHTML`).
- **IDOR & QR safety**: server endpoints carrying `userId` must authorize against `ws.userId`; `JoinCodeParser` extracts only the `joinCode` (`https://munchking-sirpepo.duckdns.org/#/join/<CODE>`) and ignores any `wsUrl`, always connecting to configured `ServerConfig.WS_URL`.
- **Design system parity**: follow `DESIGN.md` (**Munchkin Dungeon Edition**) across Android and Web PWA — zero purple/violet and zero nested `cards-inside-cards`.

## DevOps & Release Files

- Containerization: `Dockerfile`, `compose.yml`, `.dockerignore`
- VPS setup & backups: `server/.env.example`, `server/setup.sh` (`munchkin` user, `/etc/munchkin/server.env`, `/var/lib/munchkin/munchkin.db`), `server/scripts/backup_db.sh`
- Automation & CI: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `.github/dependabot.yml`, `scripts/{security_verify,verify_releases_secrets,bump_version}.py`, `deploy_updates.ps1`
