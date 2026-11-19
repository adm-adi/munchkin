# AGENTS.md

This file provides guidance to AI coding agents when working in the **Munchkin Mesa Tracker** repository.

---

## Project Overview

**Munchkin Mesa Tracker** is a multi-client tabletop tracker for Munchkin sessions consisting of:

1. **Authoritative Server + Web PWA (`server/` & `server/public/`)**: Node.js 20+ WebSocket/HTTP server (`createAppServer` in `server/server.js`) backed by SQLite (`server/db.js` in WAL mode with `withWriteLock` and FTS5 catalog search), serving an installable no-build ES-module PWA (`server/public/`) for iOS, desktop, and mobile browsers.
2. **Android Client (`app/`)**: Native Kotlin + Jetpack Compose application (`minSdk = 26`, `targetSdk = 35`) using explicit DI (`MunchkinApp.container: AppContainer`), Compose `NavHost` (14 routes), Ktor CIO WebSockets (`GameClient.kt`), and the **Munchkin Dungeon Edition** visual system defined in `DESIGN.md`.

All clients speak the same authoritative WebSocket dialect against the remote backend (`wss://munchking-sirpepo.duckdns.org:8765`).

---

## Build, Validation & Test Commands

### Server (`server/`)

```bash
npm --prefix server ci
npm --prefix server audit --audit-level=high
npm --prefix server run check
npm --prefix server test
```

- `npm --prefix server run check` validates syntax (`node --check`) across all server modules and `server/public/js/*.mjs`, then runs `tsc --noEmit -p jsconfig.json` (`checkJs: true`).
- `npm --prefix server test` runs all `node:test` suites (`validation.test.js`, `combat.test.js`, `protocol.test.js`, `webclient.test.mjs`).
- Local server startup (requires `JWT_SECRET` >= 32 characters; see `server/.env.example`):

```bash
cd server
JWT_SECRET="replace-with-at-least-32-character-random-secret" npm start
```

### Android Client (`app/`)

```bash
# Compile, unit tests, lint (enforces es/en/fr translation parity), R8 release shrink check, and debug APK
./gradlew :app:testDebugUnitTest :app:lintDebug :app:minifyReleaseWithR8 :app:assembleDebug

# Single unit test class
./gradlew :app:testDebugUnitTest --tests "*.CombatCalculatorTest"

# Release APK (requires signing credentials in local.properties or env vars)
./gradlew :app:assembleRelease
```

Signing credentials (`KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`) are read from `local.properties` (gitignored) or environment variables. Keystore file: `munchkin.keystore` in the repository root.

To target a local or staging server at build time without editing Kotlin source, pass Gradle properties validated in `app/build.gradle.kts`:

```bash
./gradlew :app:assembleDebug -PmunchkinScheme=ws -PmunchkinHost=10.0.2.2 -PmunchkinPort=8765
```

Note: release builds (`!BuildConfig.DEBUG`) strictly reject non-`wss://` schemes in `GameClient.parseWsUrl`.

### Version Sync & Security Verification (`scripts/`)

```bash
# Verify Android versionName, server/package.json, and server/package-lock.json match
python scripts/bump_version.py --check

# Live WebSocket & HTTP security probe against a running server
MUNCHKIN_SERVER_URL=ws://127.0.0.1:8765 python scripts/security_verify.py
```

PowerShell equivalent on Windows:

```powershell
$env:MUNCHKIN_SERVER_URL = "ws://127.0.0.1:8765"; python scripts/security_verify.py
```

### Emulator Testing Against Local Server

```bash
adb -s emulator-5554 reverse tcp:8765 tcp:8765
./gradlew :app:assembleDebug -PmunchkinScheme=ws -PmunchkinHost=127.0.0.1 -PmunchkinPort=8765
```

---

## Architecture

### The Golden Rule

**Clients never mutate gameplay state authoritatively.** Every action flows:

1. UI invokes an action on the domain ViewModel (`AuthViewModel`, `LobbyViewModel`, `GameSessionViewModel`, or `CatalogViewModel`) or the Web PWA controller (`server/public/js/app.mjs`).
2. Client sends a `WsMessage` (`CREATE_GAME`, `HELLO`, `EVENT_REQUEST`, `GAME_OVER`, `END_TURN`, `KICK_PLAYER`, etc.) over WebSocket (`GameClient.kt` / `net.mjs`).
3. `server/server.js` and its manager modules validate permissions, game phase, and bounds, mutate the in-memory `GameRoom`, persist to SQLite via `db.js`, and broadcast `STATE_SNAPSHOT` / `EVENT_BROADCAST` / `PLAYER_STATUS`.
4. Clients fold validated events in `GameEngine.kt` / `engine.mjs` or adopt the authoritative snapshot and re-render.

### Key Data Flow

```text
Compose UI (NavHost) -> Split ViewModels + GameSessionCoordinator -> GameClient (Ktor CIO WS)
                                                                            |
Compose UI <- Split ViewModels + GameSessionCoordinator <- GameClient <-----+-> server.js (authoritative)
                                                                            |
Web PWA UI (app.mjs + ui.mjs) <-------> net.mjs + engine.mjs <--------------+
```

### Critical Serialization & Parity Constraints

- Android uses strict `kotlinx.serialization`; Node.js parses raw JSON and `server/public/js/protocol.mjs` mirrors `Protocol.kt` / `Events.kt` with all defaults populated (`encodeDefaults = true` parity).
- `@JvmInline value class` (`GameId`, `PlayerId`) serializes as a bare `String` on the wire.
- Enum names (`Gender`, `CharacterClass`, `CharacterRace`, `GamePhase`, `DiceRollPurpose`, `CombatOutcome`, `ModifierSide`, `ConditionType`, `ModifierScope`, `ApplyMode`, `ErrorCode`) must match `server/validation.js` (`VALID_GENDERS`, `VALID_CLASSES`, `VALID_RACES`) and `server/combatManager.js` (`VALID_DICE_PURPOSES`) verbatim.
- `ErrorMessage.code` uses `ErrorCode.UNKNOWN` with `coerceInputValues = true`; other enums do not have unknown fallbacks and break room-wide decoding if mismatched.
- Server DTOs sent to clients (`HISTORY_RESULT`, `LEADERBOARD_RESULT`, `CATALOG_SEARCH_RESULT`, etc.) must use camelCase (`avatarId`, `endedAt`, `winnerId`, `didIWin`, etc.).
- Three combat calculators exist (`app/src/main/java/com/munchkin/app/core/CombatCalculator.kt`, `server/combatManager.js`, `server/public/js/combat.mjs`), with the server authoritative; any rule change must update all three.

### Core Modules

| Module | Purpose |
|--------|---------|
| `app/.../MunchkinApp.kt` & `di/AppContainer.kt` | Explicit DI (`MunchkinApp.container`), `StringResolver`, `GameSessionCoordinator`, `ViewModelProvider.Factory` |
| `app/.../core/` | Domain-pure Kotlin: `Models.kt`, `Events.kt`, `Combat.kt`, `CombatCalculator.kt`, `GameEngine.kt`, `Abilities.kt`, `GameLogEntry.kt` |
| `app/.../data/` | `SessionManager.kt` (AES256-GCM `EncryptedSharedPreferences` + KeyStore corruption recovery), `MunchkinDatabase.kt` / `SavedGameDao.kt` / `GameRepository.kt`, `AppSettingsPrefs.kt`, `TutorialPrefs.kt` |
| `app/.../network/` | `GameClient.kt` (`Closeable`, shared CIO `HttpClient`, `lifecycleMutex`, `nudge()`), `Protocol.kt`, `ServerConfig.kt`, `JoinCodeParser.kt` |
| `app/.../update/UpdateChecker.kt` | GitHub Releases updater enforcing HTTPS allowlist, internal `filesDir/updates/` storage, `.sha256` verification, signing cert match, and anti-rollback `longVersionCode` |
| `app/.../viewmodel/` | `AuthViewModel.kt`, `LobbyViewModel.kt`, `GameSessionViewModel.kt`, `CatalogViewModel.kt`, `SessionGateways.kt`, `GameConnectionSupport.kt`, `UiModels.kt` |
| `app/.../MainActivity.kt` | Single-activity Compose `NavHost` with 14 routes + `TutorialScreen` onboarding + deep-link parser |
| `app/.../ui/theme/` | **Munchkin Dungeon Edition** palette (`Color.kt`, `Theme.kt`, `Type.kt`, `AvatarResources.kt` canonical 0..7 avatars) synced with `DESIGN.md` |
| `server/server.js` | `createAppServer`: HTTP (`/health`, `/api/monsters`, static PWA), WebSocket (`ws`, 50 KB `maxPayload`, 25s heartbeat, `Origin` check, `MUNCHKIN_TRUST_PROXY`), room & event routing |
| `server/db.js` | SQLite WAL + `withWriteLock` serialization + `monsters_fts` FTS5 prefix search + `users.token_version` + active game & history persistence |
| `server/*Manager.js` | Domain handlers: `authManager.js`, `catalogManager.js`, `combatManager.js`, `gameAdminManager.js`, `historyManager.js`, `turnManager.js` |
| `server/validation.js` & `staticFiles.js` & `logger.js` | Pure input normalization/clamping, preloaded `ETag`/Brotli/gzip static file server with traversal guard, Winston daily rotating logs |
| `server/jsconfig.json` | TypeScript `checkJs: true` static checking across `server/*.js` |
| `server/public/` | Web PWA (`index.html`, `sw.js`, `manifest.webmanifest`, `css/app.css`, `js/{protocol,combat,engine,net,session,i18n,ui,app}.mjs`) |

### Navigation / Screen Flow (Android `NavHost` — 14 Routes)

First launch shows `TutorialScreen` when `!TutorialPrefs.isShown()`, then enters `NavHost` (`startDestination = "home"`):

- Auth & Profile: `home` -> `auth`, `profile`, `history`, `leaderboard`, `settings` -> `catalog` (Web PWA mirrors `#/settings` -> `#/catalog`)
- Session Creation & Join: `home` -> `create_game` / `join_game?code={code}` -> `lobby`
- Active Gameplay (`GAMEPLAY_KEEP_SCREEN_ON_ROUTES`): `lobby`, `table`, `board`, `combat` (plus `player_detail` from `table`/`board`)

---

## Development Rules

- **Localization**: All user-facing strings go in `app/src/main/res/values/strings.xml` (Spanish), `values-en/strings.xml`, and `values-fr/strings.xml`, plus `server/public/js/i18n.mjs` for the Web PWA. `:app:lintDebug` fails on missing or extra translations.
- **Design System (`DESIGN.md`)**: Follow **Munchkin Dungeon Edition** (`#0F0A08` canvas, `#1A110D` surface, `#271A14` surface variant, `#CC2020` Crimson Torch, `#D4960A` Dungeon Gold with `#1A110D` dark ink, `#E85D00` Torch Ember). Never use purple/violet or nested `cards-inside-cards`. Keep `>= 48.dp` touch targets on Android and `>= 44px` on Web PWA.
- **Universal QR & Deep-Link Contract**: QR codes encode `https://munchking-sirpepo.duckdns.org/#/join/<CODE>`. `JoinCodeParser.extractJoinCode` accepts plain codes, `/join/<CODE>`, `?join=<CODE>`, or legacy JSON while ignoring any `wsUrl` field so clients always connect to configured `ServerConfig.WS_URL`.
- **WebSocket & CSP Safety**: Keep raw WebSocket parsing in `try-catch`, preserve `maxPayload: 50 * 1024`, preserve `verifyClient` `Origin` checks, and keep Web PWA CSP strict (`style-src 'self'` with zero inline `style` attributes).
- **Player & Rule Limits**: Max 6 players per room (`MAX_PLAYERS`), level clamped `1..maxLevel` (`10` standard, `20` Super Munchkin/Epic), `bcryptjs` async-only and max 72 UTF-8 bytes, `GET_HISTORY` authorized strictly against `ws.userId`.
- **Codebase Map**: Check `CODEBASE.md` before modifying shared contracts across Kotlin, Node.js, and Web ES modules.

---

## Production, Docker & DevOps

- **Production WebSocket Endpoint**: `wss://munchking-sirpepo.duckdns.org:8765` (Web PWA & universal QR host: `https://munchking-sirpepo.duckdns.org`).
- **Docker**: `Dockerfile` (`node:20-bookworm-slim`, non-root `node` user, `/health` `HEALTHCHECK`) + `compose.yml` (`read_only: true`, `no-new-privileges:true`, named volumes `munchkin-data` and `munchkin-logs`) + `.dockerignore`.
- **Hardened VPS Provisioning**: `server/setup.sh` installs Node.js 20 via signed NodeSource GPG APT key, creates unprivileged `munchkin` system user, writes `/etc/munchkin/server.env` (`0600`, template in `server/.env.example`) with a generated `JWT_SECRET`, stores SQLite in `/var/lib/munchkin/munchkin.db`, and installs a hardened `systemd` unit (`ProtectSystem=strict`, `NoNewPrivileges=true`).
- **Backups**: `server/scripts/backup_db.sh` creates `umask 077` SQLite hot backups (`.backup`), optionally encrypted via `age` (`MUNCHKIN_BACKUP_AGE_RECIPIENT`) or OpenSSL AES-256-CBC PBKDF2 (`MUNCHKIN_BACKUP_PASSPHRASE`), pruning backups older than 7 days.
- **CI/CD & Dependencies**: `.github/workflows/ci.yml`, `.github/workflows/release.yml` (SHA-pinned Actions, build/publish job separation, `.apk.sha256` sidecars), and `.github/dependabot.yml`.

---

## Versioning & Releases

- Synchronize `app/build.gradle.kts` (`versionCode`, `versionName`), `server/package.json`, and `server/package-lock.json` using `python scripts/bump_version.py` (`--check` to verify, `--sync` to align server files to Gradle).
- Verify release keystore & generate `KEYSTORE_BASE64` safely via `python scripts/verify_releases_secrets.py` (uses `-storepass:env` and redacts raw Base64 unless `--show-secret` is passed).
- On Windows, `.\deploy_updates.ps1` verifies a clean git tree (unless `-AllowDirty`), runs server checks/tests + Android unit tests/lint, installs Release builds on connected ADB devices, or bumps versions, builds Release APK, tags, and pushes when no device is connected.
