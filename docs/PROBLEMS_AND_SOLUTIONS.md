# Problems And Solutions

## 2026-04-23 - Missing repo-operational docs on stable branch

Problem:
The stable branch did not contain the `docs/PROBLEMS_AND_SOLUTIONS.md` and `docs/IMPLEMENTATION_LOG.md` files that the repository instructions require every session.

Solution:
Restore both docs in the repo and keep them updated as part of every implementation session so architectural context and regression history are no longer trapped in uncommitted local files.

## 2026-04-23 - Android app still carries embedded-host/LAN code after remote-server pivot

Problem:
The Android app still includes `GameServer`, `NsdHelper`, `WifiDirectHelper`, and host-migration code plus Ktor server dependencies even though gameplay creation/join now uses a remote server.

Solution:
Remove the embedded-host path from the app, keep one remote realtime session path, and move the shared protocol/domain code into a dedicated `shared` module used by both the Android app and the new backend.

## 2026-04-23 - Shared combat logic split enum traits from catalog traits

Problem:
After extracting combat rules into `:shared`, warrior tie-break behavior still only checked `characterClass` and ignored catalog-backed `classIds`, which broke the regression test and made custom/shared classes behave differently from built-in classes.

Solution:
Centralize trait resolution inside `CombatCalculator` so warrior, cleric, elf, and conditional class/race matching all resolve through both legacy enum fields and catalog entry ids/names/aliases.

## 2026-04-23 - Backend tests can accidentally boot the production module

Problem:
`backend/src/main/resources/application.conf` auto-loads `com.munchkin.backend.ApplicationKt.module`, so plain `testApplication {}` will try to initialize the real Postgres-backed module unless the test environment overrides the config.

Solution:
In backend tests, set `MapApplicationConfig()` explicitly before installing the test module so Ktor only boots the in-memory `BackendServices` configuration.

## 2026-04-24 - Deployment helper mutated git state

Problem:
`deploy_updates.ps1` staged every file, created commits, pushed, bumped versions, and tagged releases as part of local deploy/install. That made a routine device install capable of publishing unrelated work.

Solution:
Replace it with an explicit verifier/local installer only. Backend production deployment now lives in `deploy-backend-prod.ps1`, which builds and packages the Ktor backend, uploads it, runs migrations, and restarts the backend service without touching git history.

## 2026-04-24 - SQLite import only migrated monsters

Problem:
The first Kotlin backend import tool only copied monsters, which would have dropped registered users, password hashes, and completed game history during cutover.

Solution:
Expand `SqliteImportTool` to import users, existing password hashes, monsters, completed games, and participants. Active live rooms remain excluded by design because the backend cutover does not preserve in-progress games.

## 2026-04-24 - MainActivity owned routing details

Problem:
`MainActivity` held the whole manual screen router, which made lifecycle setup, tutorial gating, and per-screen UI rendering tightly coupled.

Solution:
Move screen rendering into `MunchkinNavHost` with `navigation-compose` and keep the activity focused on app setup.

## 2026-04-24 - Websocket invalid JSON could tear down a session

Problem:
The new backend decoded the websocket payload type before entering the safe decode block, so malformed JSON could throw out of the message loop instead of returning a typed protocol error.

Solution:
Validate payload size, parse JSON inside the guarded loop, return `INVALID_DATA` errors for malformed or oversized payloads, and keep the session available for subsequent valid messages.

## 2026-04-24 - Localized resource sets had missing keys

Problem:
The default resource file contained combat, class, and race labels that were absent from English and French resources, so some locales would fall back inconsistently.

Solution:
Complete string-key parity across default, English, and French resources and verify all string files decode as UTF-8.

## 2026-04-24 - History and leaderboard were still coupled to gameplay state

Problem:
`GameViewModel` still owned history and leaderboard repository calls even though those screens are independent HTTP-backed features.

Solution:
Add `HistoryViewModel` with its own state and repository wiring, route history/leaderboard/profile refreshes through it, and leave `GameViewModel` focused on gameplay/session coordination.

## 2026-04-24 - Android manifest still requested LAN-era permissions

Problem:
The app still requested Wi-Fi Direct, Wi-Fi state, multicast, and location permissions after removing LAN hosting/discovery code.

Solution:
Remove LAN-era permissions and the Wi-Fi Direct feature declaration; keep only remote networking, camera, vibration, and update-install permissions.

## 2026-04-24 - Account state was coupled to gameplay state

Problem:
`GameViewModel` still restored auth sessions, registered/logged in users, stored profile state, and updated profiles even though those actions are HTTP account features.

Solution:
Move account/session/profile behavior into `AccountViewModel` and pass the current `UserProfile` into gameplay actions only where the shared protocol needs account attribution.

## 2026-04-24 - Catalog access bypassed the shared API layer

Problem:
The monster catalog screen still used a local raw HTTP client and `GameClient` still exposed dead one-off websocket helpers for auth, catalog, history, profile, and hosted games.

Solution:
Move catalog search/create behavior into `CatalogViewModel` over `CatalogRepository` and `ApiClient`, then keep `GameClient` scoped to the realtime gameplay websocket.

## 2026-04-24 - Game directory state lived inside gameplay state

Problem:
Open-game discovery and hosted-game deletion were HTTP directory features, but their loading/error/list state still lived in `GameViewModel`.

Solution:
Move open-game and hosted-game state into `GameDirectoryViewModel` so joining a discovered room remains a gameplay action, while directory fetch/delete concerns stay outside the realtime session model.

## 2026-04-24 - Update checks and backend URL constants were scattered

Problem:
`GameViewModel` still owned GitHub release checking/download state, and multiple app classes hardcoded the same backend URL.

Solution:
Move update checking/downloading into `UpdateViewModel`, localize update messages, and centralize the backend URL in `AppConfig`.

## 2026-04-24 - ViewModel UI events were partly ignored

Problem:
`GameViewModel` emitted success, error, and message events, but `MainActivity` only displayed the reconnect event.

Solution:
Handle all existing `GameUiEvent` message variants in `MainActivity` so emitted user feedback reaches the UI.

## 2026-04-24 - Visible Android copy was still hardcoded

Problem:
Home, Join Game, Settings, update dialog, and gameplay feedback/log paths still contained user-facing Spanish/English literals after the localization parity pass.

Solution:
Move those strings into default, English, and French resources and keep only non-user placeholder data, such as the sample join code, inline.

## 2026-04-24 - Navigation-compose still mirrored a manual screen enum in gameplay state

Problem:
`MunchkinNavHost` used navigation-compose, but `GameViewModel` still stored the active screen in `GameUiState` and exposed `navigateTo/goBack`, so navigation remained coupled to gameplay state.

Solution:
Move active-route ownership into `MunchkinNavHost`, keep route decisions local to the navigation graph, and let `GameViewModel` emit only one-shot navigation events for gameplay-driven transitions.

## 2026-04-24 - Android feature ViewModels had no JVM coverage

Problem:
`:app:testDebugUnitTest` built successfully but had no app-side ViewModel tests, and the new feature ViewModels constructed concrete Android/network dependencies internally, making fast unit tests awkward.

Solution:
Introduce small repository/session interfaces with default production wiring, inject those contracts into account, catalog, history, and game-directory ViewModels, and add JVM tests for restore/login/logout, profile updates, catalog search/create, history/leaderboard loading, open-game discovery, hosted-game deletion, and error states.

## 2026-04-24 - Update checks and gameplay transitions were hard to test

Problem:
`UpdateViewModel` directly constructed `UpdateChecker` and used Android context/logging, while `GameViewModel.observeClientState` mixed websocket collection with transition side effects such as logs, win prompts, game-over marking, and navigation.

Solution:
Add an `UpdateService` contract plus injectable update messages/logger for pure JVM tests, and extract game-state transition side effects into `GameStateTransitionAnalyzer` with focused tests.

## 2026-04-24 - GameViewModel repeated event-construction boilerplate

Problem:
`GameViewModel` manually built many shared `GameEvent` instances with repeated event ids, timestamps, target ids, and local clamping rules, which made the realtime action surface noisy and harder to test.

Solution:
Extract `GameEventFactory` with injectable id/time providers, route gameplay action methods through it, and add JVM tests for deterministic metadata, monster/modifier/dice clamping, and cross-player level penalties.

## 2026-04-24 - GameViewModel still constructed Android and socket dependencies directly

Problem:
`GameViewModel` directly created `GameRepository`, `SessionManager`, and `RealtimeGameSession`, and used Android logging directly in action paths, so create/join flows could not be tested without Android framework objects or live sockets.

Solution:
Introduce saved-game, player-identity, realtime-session, and logger contracts with production defaults, inject them into `GameViewModel`, and add JVM tests for create-game and join-game success paths using fake stores and fake realtime sessions.

## 2026-04-24 - GameViewModel generated player identities internally

Problem:
`GameViewModel` still called `UUID.randomUUID()` directly for host and join identities, which made create/join tests less deterministic and kept identity generation mixed into orchestration.

Solution:
Add `PlayerIdFactory` with a UUID-backed production implementation, inject it into `GameViewModel`, and extend JVM tests to assert deterministic host ids, first-join player-id persistence, and shared event dispatch after host creation.

## 2026-04-24 - GameViewModel error text depended on Android context

Problem:
`GameViewModel` still resolved resources and friendly connection errors through `MunchkinApp.context`, so resume, join, and send-failure paths could not be tested without Android framework state.

Solution:
Introduce `GameTextProvider` and `FriendlyErrorMapper`, inject them into `GameViewModel`, route join and resume connection failures through the same friendly mapper, and add JVM tests for reconnect, join, and failed realtime-send error behavior.

## 2026-04-24 - Game deletion depended on localized error text

Problem:
The backend had a `GAME_DELETED` protocol message defined but room deletion removed socket mappings without broadcasting it, while Android cleanup depended on matching a Spanish user-facing error string.

Solution:
Broadcast `GameDeletedMessage` with a stable shared reason code before removing a room, expose a typed `gameDeleted` realtime flow on Android, localize the display text only in `GameViewModel`, and add backend/app tests for deletion broadcast and saved-state cleanup.

## 2026-04-24 - Shared websocket contract still exposed HTTP-era features

Problem:
`Protocol.kt` and the backend websocket router still accepted inactive auth, profile, catalog, history, leaderboard, list-games, and hosted-game management messages after those features moved to HTTP endpoints.

Solution:
Remove the inactive websocket envelope messages and backend handlers, keep only the shared DTOs still used by HTTP/UI, move catalog creation to `CatalogAddHttpRequest`, and add a backend regression that rejects the old `LIST_GAMES` websocket message as unsupported.

## 2026-04-24 - HTTP DTOs still lived in the websocket protocol file

Problem:
After removing inactive websocket envelopes, `Protocol.kt` still physically contained HTTP/UI DTOs such as `UserProfile`, `CatalogMonster`, `GameHistoryItem`, `LeaderboardEntry`, `DiscoveredGame`, and `HostedGame`, which made the boundary look broader than it is.

Solution:
Move the remaining HTTP/UI DTO declarations into `ApiModels.kt` while keeping their package names stable, leaving `Protocol.kt` focused on gameplay websocket transport messages and connection state.

## 2026-04-24 - Game creation bypassed typed serialization and dropped timers

Problem:
Android game creation still interpolated `CreateGameRequest` JSON manually, so quoted player names could create invalid payloads, and `timerSeconds` from the create-game screen was not sent through the realtime session to the backend.

Solution:
Serialize `CreateGameRequest` through kotlinx serialization, add `turnTimerSeconds` to the realtime session create path, pass the ViewModel timer value through, and add app/backend assertions for timer propagation and quoted host names.

## 2026-04-24 - Websocket URL parsing required explicit ports and used a dead default path

Problem:
`GameClient` parsed websocket URLs with a narrow regex that required an explicit port and defaulted bare server URLs to `/game`, while the new Ktor backend exposes `/` and `/ws/game`.

Solution:
Add a tested `WsEndpointParser` that accepts `ws`, `wss`, `http`, and `https` URLs, supplies default ports, preserves paths and queries, and defaults bare URLs to `/ws/game`. Route create, join, and one-off gameplay websocket calls through it.

## 2026-04-24 - Win confirmation could hang and lose the winner

Problem:
Android confirmed wins through a one-off websocket request, the backend did not acknowledge successful `GAME_OVER` messages, and the shared reducer ignored `GameEnd.winnerId`, so confirmed games could hang on the client path or persist history without a winner.

Solution:
Send `GAME_OVER` over the active realtime session, authorize it against the connected host, store the winner in shared `GameState`, broadcast the final snapshot, acknowledge with `GAME_OVER_RECORDED`, restore the win prompt if the local send fails, and cover the reducer, backend persistence/broadcast, and Android ViewModel dispatch with tests.

## 2026-04-24 - Player reordering was only UI-authorized

Problem:
The Android UI exposed player reordering as host-only, but the backend accepted `SWAP_PLAYERS` from any connected socket because the shared reducer deliberately bypassed the normal actor-target ownership check for swap events.

Solution:
Enforce host-only authorization in `RoomManager.handleSwapPlayers`, keep the shared reducer focused on pure state changes, make Android report failed reorder sends, and add backend/ViewModel tests for unauthorized and failed reorder paths.

## 2026-04-24 - Ktor backend deploy did not match production service shape

Problem:
The new backend deploy script targeted `/opt/munchkin-backend` and a `munchkin-backend` service, but production still ran the legacy Node server as `munchkin` on `wss://...:8765`; the Ktor backend also had no TLS configuration for that WSS endpoint.

Solution:
Add optional Ktor SSL settings driven by production environment variables, update the deploy script to install the Ktor distribution behind the existing `munchkin` service name, require a server-side `backend.env`, and run Flyway migrations before restart.

## 2026-04-24 - Windows backend packaging stopped production cutover

Problem:
Packaging the backend with PowerShell `Compress-Archive` produced a ZIP with Windows path separators, so Linux `unzip` returned a warning and stopped the remote deployment before chmod and systemd rewrite. Piping a script from Windows PowerShell also reintroduced carriage returns that made `systemctl` see `munchkin\r`.

Solution:
Deploy Gradle's `:backend:distZip` artifact instead of a PowerShell-created archive, chmod the launcher after extraction, strip carriage returns before remote bash execution, and make the script fail on native command errors instead of reporting success.

## 2026-04-24 - Production host lacked the backend Java runtime

Problem:
The legacy server only needed Node.js, so the host did not have the Java runtime required by the Ktor backend. Java 17 was also insufficient because the backend artifacts target Java 21.

Solution:
Install OpenJDK 21 on the production host and keep the deploy script running the packaged Gradle distribution with the server-side `backend.env`.

## 2026-04-24 - Ktor raw map responses failed in production JSON

Problem:
`/health` and API error paths responded with raw `mapOf(...)` values, which Ktor attempted to serialize as a polymorphic `Map` and failed at runtime.

Solution:
Use typed serializable response bodies: `HealthResponse` for health checks and `ApiErrorResponse` for API error messages.

## 2026-04-24 - Backend logs were too noisy for production

Problem:
Without a backend `logback.xml`, Netty, Hikari, and Flyway debug logging flooded systemd during startup and smoke checks.

Solution:
Add a backend logback configuration with INFO as the default level, suppress Netty debug logs, and keep the level overrideable through `MUNCHKIN_LOG_LEVEL`.

## 2026-04-24 - GitHub workflows used the wrong JDK

Problem:
CI and release workflows still installed JDK 17, while the current backend artifacts are compiled for Java 21.

Solution:
Update both GitHub workflows to use Temurin JDK 21 so branch verification and tag releases match local and production runtime requirements.
