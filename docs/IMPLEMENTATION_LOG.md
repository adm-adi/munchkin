# Implementation Log

## 2026-04-23 - Clean rework foundation

Added the missing repo-level docs required by the instructions, introduced a new `shared` Kotlin module for protocol/domain/rules code, and added a new `backend` Gradle module for the Ktor/PostgreSQL replacement server.

## 2026-04-23 - Remote-first cleanup start

Started removing Android embedded-host assumptions so the app architecture can converge on a single remote authoritative backend instead of carrying both LAN-host and remote-server code paths.

## 2026-04-23 - Shared combat semantics and backend coverage

Finished the shared combat extraction by making warrior, cleric, and elf rule checks resolve through both legacy enum traits and catalog-backed race/class entries. Added regression tests for warrior tie-breaks, cleric-undead bonus, and elf helper rewards in `:shared`, plus backend integration tests for auth/profile, history/leaderboard/catalog, and websocket-created lobby discovery/hosted game management in `:backend`.

## 2026-04-23 - Android remote layers introduced

Added `ApiClient`, `RealtimeGameSession`, and thin remote repositories so the Android app can separate long-lived websocket gameplay from HTTP auth/profile/catalog/history/hosted-game calls. Updated `GameViewModel` to use the new layers for remote discovery, auth/session restore, hosted game management, catalog lookup/create, history, leaderboard, and profile updates instead of spinning up throwaway websocket clients for those actions.

## 2026-04-24 - Navigation, migration, CI, and legacy backend removal

Moved Android screen routing into `MunchkinNavHost` using `navigation-compose`, leaving `MainActivity` responsible only for lifecycle, tutorial gating, theme setup, and app shell wiring. Expanded the legacy SQLite importer so it preserves users, password hashes, monsters, and completed game history while intentionally excluding live active rooms. Removed the legacy Node/SQLite server directory, including `server.js.bak`, and updated README/CLAUDE/CODEBASE docs to describe the Kotlin/Ktor/PostgreSQL backend. Added CI coverage for shared tests, backend tests, app unit/compile checks, and Flyway migrations against a PostgreSQL service. Replaced the unsafe deploy script that auto-staged, committed, and pushed with explicit local Android install verification and added `deploy-backend-prod.ps1` for packaged backend deployment.

## 2026-04-24 - Backend hardening and Android cleanup

Added backend auth rate limiting and websocket payload validation for oversized or malformed JSON messages, with integration coverage for both cases. Deleted inactive host-handover protocol messages and Android handling now that gameplay is remote-backend authoritative. Restored the board win-confirmation dialog, moved related board UI text into resources, completed English/French string-key parity with the default Spanish resources, and verified the resource files are valid UTF-8.

## 2026-04-24 - History ViewModel split

Moved history and leaderboard loading out of `GameViewModel` into `HistoryViewModel`, then wired it through `MainActivity` and `MunchkinNavHost` for the history, leaderboard, and profile screens. Removed leftover Wi-Fi Direct/LAN permissions from the Android manifest now that discovery is backed by the remote game listing endpoint.

## 2026-04-24 - Account ViewModel split

Moved session restore, login/register, logout, and profile updates into `AccountViewModel`. `GameViewModel` no longer stores account profile state; navigation passes the current account profile into gameplay actions only when a user id is needed for room ownership, reconnect, or catalog attribution.

## 2026-04-24 - Catalog ViewModel split

Moved monster catalog search and global monster creation into `CatalogViewModel`, using `ApiClient` and `CatalogRepository` instead of the old raw `HttpURLConnection` screen implementation. `GameViewModel` now only adds created monsters to combat through gameplay events. Removed dead websocket one-off auth/catalog/history/profile/hosted-game helpers from `GameClient`.

## 2026-04-24 - Game directory ViewModel split

Moved open-game discovery and hosted-game listing/deletion into `GameDirectoryViewModel` over `HostedGamesRepository`. `GameViewModel` no longer owns HTTP directory state and stays scoped to realtime gameplay, saved-game reconnect, navigation, and update checks.

## 2026-04-24 - Update ViewModel and app config split

Moved GitHub release checking and APK download state out of `GameViewModel` into `UpdateViewModel`, wired Home and Settings through that state, and localized update-result/download strings. Added `AppConfig` as the single app-side source for the remote backend URL and replaced duplicated constants across feature ViewModels and join flow. Wired existing `GameUiEvent` success/error/message events to Toast display instead of only handling reconnect events.

## 2026-04-24 - Home, join, settings, and gameplay text localization

Moved remaining visible hardcoded text from the Home, Join Game, Settings, update dialog, and gameplay feedback/log paths into resource strings. Added matching Spanish, English, and French keys for saved-game summaries, open-game listing, developer/about labels, gameplay errors, and game-log entries.

## 2026-04-24 - Navigation state moved out of gameplay state

Removed the active `Screen` value from `GameUiState` and deleted `GameViewModel.navigateTo/goBack`. `MunchkinNavHost` now owns the navigation-compose back stack directly, while `GameViewModel` emits one-shot `GameUiEvent.Navigate` events only when realtime gameplay outcomes need to move the UI to Home, Lobby, Board, or Combat.

## 2026-04-24 - Android ViewModel unit coverage

Added explicit repository/session interfaces for account, catalog, history, and game-directory app flows so feature ViewModels can be tested without Android context or live HTTP clients. Added JVM tests covering account restore/login/profile/logout behavior, catalog search/create event emission and clamping, history/leaderboard loading, open-game discovery, hosted-game deletion, and failure states.

## 2026-04-24 - Update and transition test seams

Added `UpdateService` so `UpdateViewModel` can be tested without Android context or GitHub/network access, with tests for auto-check, manual messages, errors, dismiss, and download completion. Extracted `GameStateTransitionAnalyzer` from `GameViewModel.observeClientState` to make level/combat logs, win-prompt pending state, game-over marking, and lobby-to-board navigation pure and covered by JVM tests.

## 2026-04-24 - Gameplay event factory extraction

Extracted repeated shared `GameEvent` construction from `GameViewModel` into `GameEventFactory`. Gameplay actions now share one source for event ids, timestamps, target ids, monster clamping, combat modifier clamping, dice clamping, and run-away level penalties. Added JVM coverage for deterministic event metadata and clamping behavior.

## 2026-04-24 - GameViewModel dependency seams

Added `SavedGameStore`, `PlayerIdentityStore`, `RealtimeSession`, `RealtimeSessionFactory`, and `GameLogger` contracts so `GameViewModel` no longer hardwires Room, encrypted preferences, websocket sessions, or Android logging in tests. Added JVM tests for create-game and join-game success paths, including saved-game persistence, player-id reuse, and navigation events.

## 2026-04-24 - Deterministic gameplay identity tests

Added `PlayerIdFactory` for Android gameplay identity creation and injected it into `GameViewModel`. Expanded GameViewModel JVM coverage so create-game uses a deterministic host id, first-time join persists the generated player id, and host actions dispatch shared gameplay events through the realtime session.

## 2026-04-24 - Gameplay text and connection-error seams

Added `GameTextProvider` and `FriendlyErrorMapper` so `GameViewModel` no longer needs Android context for resource lookup or connection-error classification in tests. Join and resume failures now use the same friendly connection mapping, and GameViewModel JVM tests cover join failure, saved-game reconnect failure, and failed realtime-send feedback.

## 2026-04-24 - Typed game deletion flow

Wired room deletion through the shared `GameDeletedMessage` protocol with the stable `GAME_DELETED_BY_HOST_REASON` reason code. The backend now broadcasts the typed deletion message before removing room sockets, Android exposes it as `RealtimeSession.gameDeleted`, and `GameViewModel` clears saved state and navigates home from that typed signal instead of matching localized error text.

## 2026-04-24 - Websocket contract narrowed to gameplay

Removed inactive websocket auth/profile/catalog/history/leaderboard/list-games/hosted-game envelopes and their backend compatibility handlers. Catalog monster creation now uses the HTTP-specific `CatalogAddHttpRequest`, and backend tests assert that a legacy `LIST_GAMES` websocket message returns `INVALID_DATA` instead of reviving compatibility behavior.

## 2026-04-24 - API DTO declarations split from websocket protocol

Moved `UserProfile`, `CatalogMonster`, `GameHistoryItem`, `LeaderboardEntry`, `DiscoveredGame`, and `HostedGame` from `Protocol.kt` into `ApiModels.kt`. The package names remain unchanged for callers, but the source layout now reflects the intended boundary: gameplay websocket envelopes in `Protocol.kt`, HTTP/UI DTOs in `ApiModels.kt`.

## 2026-04-24 - Typed game creation payloads and timer propagation

Replaced Android's manual create-game JSON construction with `json.encodeToString<WsMessage>(CreateGameRequest(...))`. Extended `RealtimeSession.createGame` to carry `turnTimerSeconds` so the create-game screen timer reaches backend room settings, with app ViewModel coverage for the forwarded timer and backend coverage for quoted host names plus timer persistence in the welcome state.

## 2026-04-24 - Websocket endpoint parser

Added `WsEndpointParser` for Android realtime connections. `GameClient` now resolves create, join, and one-off gameplay websocket URLs through the same parser, supporting default ports and defaulting bare backend URLs to `/ws/game` instead of the inactive `/game` path. Added JVM tests for explicit ports, bare secure hosts, HTTP-to-websocket conversion, query preservation, and invalid URLs.

## 2026-04-24 - Typed game-over confirmation

Moved win confirmation from a one-off websocket request to the active gameplay session. The backend now validates that the connected host is confirming the winner, applies `GameEnd` through the shared reducer, persists finished-game history with the resolved winner user id, broadcasts the final `FINISHED` snapshot, and sends `GAME_OVER_RECORDED` as a typed acknowledgement. Android restores the pending winner prompt if the local send fails. Added shared, backend, and Android ViewModel tests for the flow.

## 2026-04-24 - Server-authoritative player reordering

Tightened `SWAP_PLAYERS` so the backend rejects non-host sockets with `UNAUTHORIZED` before applying the shared reducer. Android now treats failed reorder sends as user-visible errors, and the remaining private one-off websocket helper was removed from `GameClient`. Added backend and GameViewModel tests for the protected reorder flow.

## 2026-04-24 - Release cutover preparation

Bumped the Android release to `2.20.0` / version code `94`. Added optional Ktor SSL configuration so production can continue serving the Android app over `wss://munchking-sirpepo.duckdns.org:8765`, and updated `deploy-backend-prod.ps1` to deploy the Ktor distribution to the existing `munchkin` systemd service after running Flyway migrations from the server-side environment file.

## 2026-04-24 - Production Ktor cutover and migration

Deployed the Kotlin/Ktor backend to the existing production `munchkin` service on HTTPS/WSS port `8765`, backed by the new Dockerized PostgreSQL database. Installed the required Java 21 runtime, switched the deploy package to Gradle's distribution ZIP, fixed remote script CRLF handling, added typed health/error responses, added production logging configuration, and aligned GitHub CI/release workflows with JDK 21. Ran Flyway on production, imported the legacy SQLite users, monster catalog, completed game history, and participants into PostgreSQL, then verified HTTPS health, public HTTP endpoints, auth/profile flow, WSS create/ping/delete gameplay flow, and zero remaining open smoke-test rooms.

## 2026-04-24 - Combat power cap fix

Bumped Android to `2.20.1` / version code `95` and removed the Android-side combat power ceilings. Combat hero/monster modifiers can now go past `+20` or `-20`, custom monster flat modifiers are no longer restricted to `-10..10`, and player level remains the bounded stat through `GameSettings.maxLevel`. Added app and shared regression tests for uncapped combat modifiers.

## 2026-04-24 - Lobby dice and table polish fix

Bumped Android to `2.20.2` / version code `96`. Reworked lobby starter dice state so ties keep previous rolls visible, only tied players reroll, duplicate lobby rolls are rejected, and the resolved winner is used as the starting turn player. Localized player-detail race/class dropdown labels and removed unreadable level/power/gender badges from table-mode avatars.

## 2026-04-25 - New gendered avatar set

Bumped Android to `2.20.3` / version code `97`. Imported the new warrior, wizard, thief, cleric, human, elf, dwarf, and halfling male/female avatar artwork as optimized 512 px drawable resources. Reworked `AvatarResources` to use semantic avatar slots, added localized avatar-name strings, and fixed join-game plus table-mode avatar rendering so changing/selecting gender shows the matching male or female artwork.
