# Munchkin Mesa Tracker — Project Architecture & Codebase Map

This document serves as the architectural map for **Munchkin Mesa Tracker**. It covers the Android client (`app/`), the authoritative Node.js server (`server/`), the zero-build Web PWA (`server/public/`), the cross-platform Dungeon Edition design specification (`DESIGN.md`), and the DevOps/security tooling.

---

## 1. High-Level Architecture

**Munchkin Mesa Tracker** uses a remote authoritative client-server architecture over WebSockets (`wss://munchking-sirpepo.duckdns.org:8765`) serving both native Android clients and any modern browser via the bundled Web PWA (`https://munchking-sirpepo.duckdns.org`):

- **Android Client (`app/`)**:
  - Kotlin + Jetpack Compose + Material 3 (**Munchkin Dungeon Edition** palette defined in `DESIGN.md`).
  - `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`.
  - Explicit dependency injection via `MunchkinApp.container: AppContainer` (`di/AppContainer.kt`) — no static `Context` singletons.
  - Split presentation layer (`AuthViewModel`, `LobbyViewModel`, `GameSessionViewModel`, `CatalogViewModel`) coordinated by `GameSessionCoordinator` (`StateFlow` + `SharedFlow<GameUiEvent>`).
  - Single-activity Compose `NavHost` (`MainActivity.kt`) with 14 routes + first-launch `TutorialScreen`.
  - Networking via `GameClient.kt` (Ktor CIO WebSockets, `Closeable`, shared `HttpClient`, `lifecycleMutex`, `nudge()`).
- **Authoritative Backend (`server/`)**:
  - Node.js 20+ HTTP(S) and WebSocket (`ws`) server (`server.js` exporting `createAppServer`) with domain managers (`authManager.js`, `catalogManager.js`, `combatManager.js`, `gameAdminManager.js`, `historyManager.js`, `turnManager.js`).
  - Persistence in SQLite (`db.js`) with `PRAGMA journal_mode = WAL`, serialized writes (`withWriteLock`), `monsters_fts` FTS5 prefix search, and `users.token_version` JWT revocation.
  - In-memory active room map (`games`) backed by debounced SQLite checkpoints (`active_games`) and graceful `SIGTERM`/`SIGINT` persistence + `db.closeDb()`.
- **Web PWA (`server/public/`)**:
  - No-build ES-module SPA served directly by `server/staticFiles.js` (preloaded in-memory `ETag` + Brotli/gzip cache, strict CSP).
  - Offline app-shell caching via `sw.js` and `manifest.webmanifest`.
  - Mirrors the Android domain/protocol/combat layers (`js/protocol.mjs`, `js/engine.mjs`, `js/combat.mjs`, `js/net.mjs`) and **Dungeon Edition** CSS (`css/app.css`).

> **Golden Rule**: Clients never mutate gameplay state authoritatively. Actions go to `server/server.js`, which validates rules/phase/bounds and broadcasts `STATE_SNAPSHOT`, `EVENT_BROADCAST`, or `PLAYER_STATUS` to all clients in the room.

---

## 2. Directory Structure

```text
munchkin/
├── app/
│   ├── build.gradle.kts                      # Android SDK 26..35, R8 release shrink, translation lint, BuildConfig endpoint
│   ├── lint-baseline.xml
│   ├── proguard-rules.pro                    # R8 keep rules (Ktor CIO, kotlinx.serialization, Room, ML Kit/ZXing) + strips Log.v/d/i
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml           # allowBackup=false, deep link https://munchking-sirpepo.duckdns.org/#/join, FileProvider
│       │   ├── java/com/munchkin/app/
│       │   │   ├── MunchkinApp.kt            # Application owning MunchkinApp.container: AppContainer
│       │   │   ├── MainActivity.kt           # Compose NavHost (14 routes), TutorialScreen gate, deep links, lifecycle observer
│       │   │   ├── di/
│       │   │   │   └── AppContainer.kt       # AppContainer, DefaultAppContainer, StringResolver, GameSessionCoordinator
│       │   │   ├── core/                     # Domain-pure Kotlin (zero Android dependencies)
│       │   │   │   ├── Abilities.kt
│       │   │   │   ├── Combat.kt
│       │   │   │   ├── CombatCalculator.kt
│       │   │   │   ├── Events.kt
│       │   │   │   ├── GameEngine.kt
│       │   │   │   ├── GameLogEntry.kt
│       │   │   │   └── Models.kt
│       │   │   ├── data/                     # Persistence & preferences
│       │   │   │   ├── AppSettingsPrefs.kt
│       │   │   │   ├── GameRepository.kt
│       │   │   │   ├── MunchkinDatabase.kt
│       │   │   │   ├── SavedGameDao.kt
│       │   │   │   ├── SessionManager.kt     # EncryptedSharedPreferences + KeyStore corruption recovery
│       │   │   │   └── TutorialPrefs.kt
│       │   │   ├── network/                  # WebSocket & URL/QR parsing
│       │   │   │   ├── GameClient.kt         # Closeable, shared CIO HttpClient, lifecycleMutex, nudge()
│       │   │   │   ├── JoinCodeParser.kt     # Extracts 6-8 char joinCode from URLs/JSON; ignores wsUrl
│       │   │   │   ├── Protocol.kt           # Sealed WsMessage hierarchy & ErrorCode
│       │   │   │   └── ServerConfig.kt       # Reads BuildConfig.SERVER_SCHEME/HOST/PORT
│       │   │   ├── update/
│       │   │   │   └── UpdateChecker.kt      # HTTPS GitHub allowlist, .sha256, signing cert & anti-rollback check
│       │   │   ├── util/
│       │   │   │   └── LocaleManager.kt      # System / ES / EN / FR per-app locale switching
│       │   │   ├── viewmodel/                # Split ViewModels & gateways
│       │   │   │   ├── AuthViewModel.kt
│       │   │   │   ├── CatalogViewModel.kt
│       │   │   │   ├── GameConnectionSupport.kt
│       │   │   │   ├── GameSessionViewModel.kt
│       │   │   │   ├── LobbyViewModel.kt
│       │   │   │   ├── SessionGateways.kt
│       │   │   │   └── UiModels.kt
│       │   │   └── ui/
│       │   │       ├── theme/                # Color.kt, Theme.kt, Type.kt, AvatarResources.kt (0..7 WebP)
│       │   │       ├── components/           # Reusable Compose widgets (15 files)
│       │   │       └── screens/              # Compose screens (15 files)
│       │   └── res/
│       │       ├── drawable-nodpi/           # app_icon.webp, avatar_m_0..7.webp, avatar_f_0..7.webp
│       │       ├── values/, values-en/, values-fr/  # Parity-enforced strings.xml + themes.xml + colors.xml
│       │       └── xml/                      # backup_rules.xml, data_extraction_rules.xml, file_paths.xml, locales_config.xml, network_security_config.xml
│       └── test/java/com/munchkin/app/
│           ├── core/                         # 8 domain & combat unit test files
│           └── viewmodel/                    # Auth/Catalog/GameSession/Lobby ViewModel tests + TestFakes.kt
├── server/
│   ├── .env.example                          # Template for /etc/munchkin/server.env or server/.env
│   ├── jsconfig.json                         # TypeScript checkJs: true across server/*.js
│   ├── package.json & package-lock.json
│   ├── server.js                             # createAppServer entry point, HTTP/WS routing, room lifecycle
│   ├── db.js                                 # SQLite WAL, withWriteLock, FTS5 catalog, token_version, persistence
│   ├── authManager.js                        # REGISTER, LOGIN, LOGIN_WITH_TOKEN, UPDATE_PROFILE, DELETE_ACCOUNT
│   ├── catalogManager.js                     # CATALOG_SEARCH & rate-limited CATALOG_ADD
│   ├── combatManager.js                      # Authoritative combat math & dice/run-away helpers
│   ├── gameAdminManager.js                   # GAME_OVER, GAME_END, KICK_PLAYER, TRANSFER_HOST, SWAP_PLAYERS, DELETE_GAME
│   ├── historyManager.js                     # IDOR-safe GET_HISTORY & GET_LEADERBOARD
│   ├── turnManager.js                        # Turn order & clamped backend turn timers
│   ├── validation.js                         # Pure input normalization & enum whitelists
│   ├── staticFiles.js                        # In-memory ETag/Brotli/gzip static server for public/
│   ├── logger.js                             # Winston + daily-rotate-file logger
│   ├── monsters_seed.sql                     # Default monster catalog seed
│   ├── setup.sh                              # Hardened Debian/Ubuntu VPS installer (munchkin user + systemd)
│   ├── testServer.js                         # Subprocess server harness for integration tests
│   ├── *.test.js & webclient.test.mjs        # 4 node:test suites (validation, combat, protocol, webclient)
│   ├── scripts/
│   │   ├── backup_db.sh                      # SQLite hot backup (.backup) with optional age / OpenSSL encryption
│   │   └── generate_web_icons.js
│   └── public/                               # Web PWA
│       ├── index.html, manifest.webmanifest, sw.js, icon.svg, icon-180.png, icon-512.png
│       ├── css/app.css                       # Dungeon Edition tokens & CSP-compliant styles
│       └── js/                               # app.mjs, combat.mjs, engine.mjs, i18n.mjs, net.mjs, protocol.mjs, session.mjs, ui.mjs
├── scripts/
│   ├── bump_version.py                       # Synchronized version bump / --check / --sync
│   ├── security_verify.py                    # Live WS/HTTP security & protocol probe (MUNCHKIN_SERVER_URL)
│   └── verify_releases_secrets.py            # Keystore verification (-storepass:env) & --show-secret Base64 helper
├── .github/
│   ├── dependabot.yml                        # Weekly updates for npm (/server), gradle (/), github-actions (/)
│   └── workflows/
│       ├── ci.yml                            # Android (compile, test, lint, R8) + Server (audit, check, test, security probe)
│       └── release.yml                       # Tagged v* builds: server/Android checks -> signed APK + .sha256 release
├── Dockerfile & compose.yml & .dockerignore  # Hardened container build & runtime (non-root node user, read-only FS)
├── DESIGN.md                                 # Munchkin Dungeon Edition visual spec (colors, avatars 0..7, WCAG, QR contract)
├── deploy_updates.ps1                        # Windows release / ADB device deploy helper
├── AGENTS.md, CLAUDE.md, CODEBASE.md, README.md, server/README.md
```

---

## 3. Core Android Modules Deep Dive

### 3.1 `di/` & `MunchkinApp.kt` - Dependency Injection & Coordination

- **`MunchkinApp.kt`**: Initializes `MunchkinApp.container = DefaultAppContainer(this)` and `SoundManager.init(this)` without holding a static `Context` reference.
- **`di/AppContainer.kt`**:
  - `AppContainer` / `DefaultAppContainer`: Lazily provides `SessionManager`, `GameRepository`, `TutorialPrefs`, `AppSettingsPrefs`, `UpdateChecker`, `AndroidStringResolver`, `clientFactory`, `GameSessionCoordinator`, and `ViewModelProvider.Factory`.
  - `GameSessionCoordinator`: Single source of truth for cross-ViewModel session flows (`userProfile`, `savedGame`, `hostedGames`, `activeClient`, `gameState`, `myPlayerId`, `connectionState`, `reconnectAttempt`, `serverTimeOffsetMs`, `isConnectingOrLoading`) and buffered `SharedFlow<GameUiEvent>` (`ShowError`, `ShowSuccess`, `ShowMessage`, `Reconnected`).

### 3.2 `core/` - Pure Domain & Game Engine

No Android framework dependencies; heavily annotated with `@Serializable` / `@Immutable`:

- **`Models.kt`**: `GameId`, `PlayerId` (`@JvmInline value class`), `Gender` (`M`, `F`, `NA`), `CharacterClass`, `CharacterRace`, `GamePhase`, `PlayerState` (with `activeClasses`, `activeRaces`, `combatPower`, `reachedMaxLevelViaCombat`), `GameSettings`, `GameState`, `canBeConfirmedWinner`.
- **`Events.kt`**: Sealed `GameEvent` hierarchy (`GAME_START`, `PLAYER_ROLL`, `GAME_END`, `END_TURN`, `SET_NAME`, `SET_AVATAR`, `SET_GENDER`, `INC_LEVEL`, `DEC_LEVEL`, `SET_LEVEL`, `INC_GEAR`, `DEC_GEAR`, `SET_GEAR`, `SET_CLASS`, `SET_RACE`, `SET_HALF_BREED`, `SET_SUPER_MUNCHKIN`, `COMBAT_START`, `COMBAT_ADD_MONSTER`, `COMBAT_REMOVE_MONSTER`, `COMBAT_UPDATE_MONSTER`, `COMBAT_ADD_HELPER`, `COMBAT_REMOVE_HELPER`, `COMBAT_SET_MODIFIER`, `COMBAT_ADD_BONUS`, `COMBAT_REMOVE_BONUS`, `COMBAT_END`).
- **`Combat.kt` & `CombatCalculator.kt`**: `CombatState`, `MonsterInstance`, `ConditionalModifier`, `TempBonus`, `CombatResult`, and pure combat calculation (Warrior tie-break, Cleric +3 vs Undead, conditional modifiers).
- **`GameEngine.kt`**: Pure `GameEngine.reduce(state, event)` plus synchronized `stateLock` wrapper for `StateFlow<GameState?>` concurrency safety between `EVENT_BROADCAST` and `PLAYER_STATUS`.
- **`Abilities.kt` & `GameLogEntry.kt`**: Structured class/race reminders (`AUTOMATIC` vs `MANUAL`) and typed game log entries (`LogType`).

### 3.3 `network/` & `update/`

- **`ServerConfig.kt`**: Exposes `SCHEME`, `HOST`, `PORT`, and `WS_URL` sourced from `BuildConfig` (default `wss://munchking-sirpepo.duckdns.org:8765`, overridable at build time via `-PmunchkinScheme`/`-PmunchkinHost`/`-PmunchkinPort`).
- **`JoinCodeParser.kt`**: `extractJoinCode(raw)` accepts plain 6–8 char alphanumeric codes, `/join/<CODE>`, `#/join/<CODE>`, `?join=<CODE>`, or legacy JSON (`{"joinCode": "..."}`), deliberately ignoring any `wsUrl` field to prevent untrusted server redirection.
- **`Protocol.kt`**: Sealed `WsMessage` hierarchy (`CREATE_GAME`, `HELLO`, `EVENT_REQUEST`, `PING`, `WELCOME`, `STATE_SNAPSHOT`, `EVENT_BROADCAST`, `ERROR`, auth/history/leaderboard/catalog/admin messages) and `ErrorCode` (`UNKNOWN` fallback).
- **`GameClient.kt`**: `Closeable` Ktor CIO WebSocket client using a shared `HttpClient(CIO)`, `lifecycleMutex`, `nudge()` conflated `Channel<Unit>` for foreground reconnection, ping/pong clock sync, and one-off request helpers; `parseWsUrl` forbids `ws://` in release builds (`!BuildConfig.DEBUG`).
- **`UpdateChecker.kt`**: Queries GitHub Releases API (`https://api.github.com/repos/adm-adi/munchkin/releases/latest`), enforces `https://` + allowlisted GitHub domains, downloads into internal `context.filesDir/updates/` (exposed via `xml/file_paths.xml`), verifies `.sha256` sidecars when published, checks `packageName`, anti-rollback `longVersionCode`, and signing certificate SHA-256 against the installed APK before calling `FileProvider`.

### 3.4 `data/` - Local Persistence

- **`SessionManager.kt`**: AES256-GCM / AES256-SIV `EncryptedSharedPreferences` (`munchkin_session`) storing `UserProfile`, JWT `authToken`, and per-room `playerId` + `reconnectToken`. Automatically recovers from corrupted preferences or corrupted Android KeyStore master keys by deleting and recreating cleanly; `clearSession()` purges all per-room keys.
- **`MunchkinDatabase.kt` / `SavedGameDao.kt` / `GameRepository.kt`**: Room database (`munchkin_database`) caching the latest active `GameState` locally for quick resume. Both `android:allowBackup="false"` and `backup_rules.xml`/`data_extraction_rules.xml` prevent backing up preferences or local databases.
- **`AppSettingsPrefs.kt` & `TutorialPrefs.kt`**: Non-sensitive UI preferences (`largeNumbersMode`, `tutorial_shown`).

### 3.5 `viewmodel/` & `ui/`

- **Split ViewModels**:
  - `AuthViewModel.kt`: login, registration, startup JWT refresh, profile updates, `DELETE_ACCOUNT`, history, leaderboard, hosted games list/delete.
  - `LobbyViewModel.kt`: active room discovery (`LIST_GAMES`), game creation, joining (via `JoinCodeParser`), saved/hosted game resumption, update checks/downloads, `largeNumbersMode` preference.
  - `GameSessionViewModel.kt`: live gameplay events, combat actions, combat mismatch detection, turn/host actions, reconnection (`nudgeConnection()`, `checkReconnection()`, `retryReconnect()`), bad-stuff overlay state.
  - `CatalogViewModel.kt`: debounced monster search, pagination, global monster creation.
  - `SessionGateways.kt`, `GameConnectionSupport.kt`, `UiModels.kt`: testable gateway abstractions (`SessionStore`, `SavedGameStore`, `UpdateGateway`, `LargeNumbersPrefs`, `SessionConnection`, `RemoteGateway`), shared session attach/resume logic, `canTouchCombat`, localized exception-to-string mapping.
- **`MainActivity.kt` & `ui/`**:
  - Compose `NavHost` with 14 routes: `home`, `create_game`, `join_game?code={code}`, `lobby`, `table`, `board`, `combat`, `player_detail`, `catalog`, `settings`, `auth`, `profile`, `history`, `leaderboard`.
  - Root `Scaffold` hosts `SnackbarHost`, `BadStuffDialog`, `DebugLogViewer` (gated by `BuildConfig.DEBUG`), `GAMEPLAY_KEEP_SCREEN_ON_ROUTES`, and `LifecycleEventObserver` (`ON_START` -> `nudgeConnection()`, `ON_RESUME` -> `checkReconnection()`).
  - `ui/theme/`: **Dungeon Edition** tokens (`Color.kt`, `Theme.kt`, `Type.kt` with `largeNumbersMode`) and `AvatarResources.kt` (`0..7` canonical mapping matching `DESIGN.md` and `server/public/js/i18n.mjs`).

---

## 4. Backend & Web PWA Deep Dive (`server/`)

### 4.1 Server Modules (`server/*.js`)

- **`server.js`**: Exports `createAppServer(options)` (tested without importing side-effect listeners) and starts listening when run as `node server.js`. Enforces `JWT_SECRET.length >= 32`, optional TLS (`MUNCHKIN_TLS_KEY`/`MUNCHKIN_TLS_CERT`), Helmet security headers (strict `style-src 'self'`, HSTS only when TLS is active), `/health` DB liveness check, rate-limited `/api/monsters`, `verifyClient` `Origin` validation, 50 KB `maxPayload`, 25s WebSocket heartbeat, bounded LRU rate limits, and graceful `SIGTERM`/`SIGINT` room persistence + `db.closeDb()`.
- **`db.js`**: Initializes SQLite (`sqlite3`) with `PRAGMA journal_mode = WAL`, `synchronous = NORMAL`, `foreign_keys = ON`, `busy_timeout = 5000`; serializes all mutations through `withWriteLock`; maintains `monsters_fts` FTS5 prefix search table; tracks `users.token_version` for immediate JWT revocation.
- **`authManager.js`**: Async `bcryptjs` (cost 12, rejects passwords > 72 UTF-8 bytes), HS256 JWT issuance/verification (`alg === 'HS256'`, finite `exp`, `tv === user.token_version`), `UPDATE_PROFILE` requiring `currentPassword` when changing password, `DELETE_ACCOUNT` anonymization/deletion.
- **`turnManager.js`, `combatManager.js`, `catalogManager.js`, `historyManager.js`, `gameAdminManager.js`**: Modular domain managers wired via factory functions (`create*Manager`), enforcing phase guards (`IN_GAME` vs `LOBBY`), IDOR protection (`ws.userId`), combat tie/ability math, and camelCase response mapping.
- **`validation.js`**: Zero-side-effect pure sanitizers (`VALID_GENDERS`, `VALID_CLASSES`, `VALID_RACES`, `clampInt`, `stepAmount`, `normalizeGender`, `normalizeName`, `sanitizeMonster`, `sanitizeBonus`, `sanitizeConditionalModifier`).
- **`staticFiles.js`**: Preloads `server/public/` in memory with SHA-256 `ETag`, Brotli (`br`) and `gzip` precompression, `304 Not Modified` handling, strict root-prefix path traversal guard, dotfile exclusion, and extension/MIME allowlist.
- **`jsconfig.json`**: Runs `tsc --noEmit -p jsconfig.json` (`checkJs: true`) as part of `npm --prefix server run check`.

### 4.2 Web PWA (`server/public/`)

- **Assets**: `index.html`, `manifest.webmanifest` (`background_color`/`theme_color` `#0F0A08`, `"orientation": "any"`), `sw.js` (network-first app-shell Service Worker bypassing `/api/*`, `/health`, and WebSocket upgrades), `css/app.css` (**Dungeon Edition**, zero inline styles for strict CSP).
- **ES Modules (`server/public/js/`)**:
  - `protocol.mjs`: Wire constants (`MSG`), defaults-complete event & payload builders matching Kotlin.
  - `engine.mjs` & `combat.mjs`: Client reducer & combat math mirroring `GameEngine.kt` and `CombatCalculator.kt`.
  - `net.mjs`: `GameSocket`, `ConnState`, automatic reconnect with `reconnectToken`, `oneOffRequest` / `authenticatedRequest`.
  - `session.mjs`: Domain-split `localStorage` persistence (`munchkin.auth`, `munchkin.identity`, `munchkin.seat`) with safe JSON guards and complete `clearAuth()` cleanup.
  - `i18n.mjs`: Full ES / EN / FR translations, expansions, class/race abilities, `ErrorCode` messages, and canonical 8-avatar table (`0..7`).
  - `ui.mjs` & `app.mjs`: Incremental DOM reconciler (`patchScreen`), accessible ARIA modal dialogs (`role="dialog"`, `aria-modal`, focus trap, `Escape` close), live-region toasts, pure-JS SVG QR renderer (`https://<host>/#/join/<CODE>`), and full Android screen parity.

---

## 5. Security, QR & Operation Flows

1. **Authentication & Session Continuity**:
   - Android persists JWTs and per-room `reconnectToken`s in AES256-GCM `EncryptedSharedPreferences` (`SessionManager.kt`), recovering cleanly from KeyStore invalidation.
   - Server hashes passwords asynchronously (`bcryptjs`), enforces `token_version` (`tv` claim) on `LOGIN_WITH_TOKEN`, and rotates per-seat `reconnectToken` SHA-256 digests on every `HELLO` reconnect.
2. **Universal QR & Deep Linking**:
   - Both Android (`LobbyScreen`) and Web PWA (`app.mjs`) generate QR URLs of the form `https://munchking-sirpepo.duckdns.org/#/join/<CODE>`.
   - Android intercepts matching `https://munchking-sirpepo.duckdns.org/join` deep links in `MainActivity.kt` and runs `JoinCodeParser.extractJoinCode`, which extracts strictly the 6–8 char `joinCode` (ignoring any `wsUrl`) and always connects to `ServerConfig.WS_URL`.
3. **WebSocket Transport & Payload Safety**:
   - WebSocket upgrades check `Origin` in `verifyClient` (`server.js`), enforce a 50 KB `maxPayload`, run a 25s ping/pong sweep, and wrap all JSON parsing/handling in `try-catch`.
   - Production uses `wss://munchking-sirpepo.duckdns.org:8765`, and Android release builds reject `ws://` in `GameClient.parseWsUrl`.

---

## 6. Cross-File Dependency Map

Before changing shared contracts, update all linked layers together:

| Change Area | Files to Keep Synchronized |
| --- | --- |
| **Wire messages / events / enums** | `app/.../core/Models.kt`, `Events.kt`, `Combat.kt`, `network/Protocol.kt` <-> `server/validation.js`, `server/server.js`, `server/*Manager.js` <-> `server/public/js/protocol.mjs` |
| **Game rules & combat math** | `app/.../core/GameEngine.kt`, `CombatCalculator.kt`, `Abilities.kt` <-> `server/combatManager.js`, `server/turnManager.js`, `server/server.js` <-> `server/public/js/engine.mjs`, `combat.mjs` |
| **Design tokens & canonical avatars (0..7)** | `DESIGN.md` <-> `app/.../ui/theme/{Color,Theme,Type,AvatarResources}.kt` <-> `server/public/css/app.css`, `server/public/js/i18n.mjs`, `manifest.webmanifest`, `index.html` |
| **Localization (`es` / `en` / `fr`)** | `app/src/main/res/values{,-en,-fr}/strings.xml` (checked by `:app:lintDebug`) & `server/public/js/i18n.mjs` |
| **App versions** | `app/build.gradle.kts`, `server/package.json`, `server/package-lock.json` (verified by `python scripts/bump_version.py --check`) |
| **Server runtime env & deployment** | `server/.env.example`, `server/setup.sh`, `server/scripts/backup_db.sh`, `Dockerfile`, `compose.yml` |
