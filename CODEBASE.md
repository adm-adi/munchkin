# CODEBASE

## Modules

### `:app`

Android client UI and local persistence.

- `app/src/main/java/com/munchkin/app/MainActivity.kt`
  Activity shell for lifecycle, tutorial gating, and theme setup.
- `app/src/main/java/com/munchkin/app/AppConfig.kt`
  Central app runtime constants such as the remote backend URL.
- `app/src/main/java/com/munchkin/app/ui/navigation/AppNavigation.kt`
  `navigation-compose` graph and active-route owner for the current app screens.
- `app/src/main/java/com/munchkin/app/viewmodel/GameViewModel.kt`
  Realtime gameplay session state, saved-game reconnect, and gameplay event dispatch over injectable stores/sessions.
- `app/src/main/java/com/munchkin/app/viewmodel/GameEventFactory.kt`
  Central builder for shared gameplay events emitted by the Android realtime ViewModel.
- `app/src/main/java/com/munchkin/app/viewmodel/PlayerIdFactory.kt`
  Gameplay player identity factory with UUID-backed production implementation.
- `app/src/main/java/com/munchkin/app/viewmodel/GameTextProvider.kt`
  Resource-text and friendly connection-error mapping seams for Android gameplay ViewModel tests.
- `app/src/main/java/com/munchkin/app/viewmodel/GameStateTransitionAnalyzer.kt`
  Pure analyzer for gameplay state transition logs, win prompts, game-over marking, and navigation side effects.
- `app/src/main/java/com/munchkin/app/viewmodel/AccountViewModel.kt`
  Feature-scoped account/session/profile state and auth/profile HTTP actions.
- `app/src/main/java/com/munchkin/app/viewmodel/HistoryViewModel.kt`
  Feature-scoped history and leaderboard state, loading, and error handling.
- `app/src/main/java/com/munchkin/app/viewmodel/CatalogViewModel.kt`
  Feature-scoped monster catalog search/create state and created-monster events.
- `app/src/main/java/com/munchkin/app/viewmodel/GameDirectoryViewModel.kt`
  Feature-scoped open-game discovery and hosted-game management state.
- `app/src/main/java/com/munchkin/app/viewmodel/UpdateViewModel.kt`
  Feature-scoped update check/download state and update-result messages.
- `app/src/main/java/com/munchkin/app/update/UpdateChecker.kt`
  Android implementation of the update service for GitHub release checks and APK download/install.
- `app/src/main/java/com/munchkin/app/network/RealtimeGameSession.kt`
  Long-lived websocket session contract and implementation for typed room creation, lifecycle, gameplay events, and room-deletion signals.
- `app/src/main/java/com/munchkin/app/network/WsEndpoint.kt`
  Tested websocket URL parser used by realtime create, join, and one-off gameplay requests.
- `app/src/main/java/com/munchkin/app/network/ApiClient.kt`
  HTTP client for auth, profile, catalog, history, leaderboard, and game-directory endpoints.
- `app/src/main/java/com/munchkin/app/data/RemoteRepositories.kt`
  Thin feature-oriented repositories and testable data-source contracts over `ApiClient` and `SessionManager`.
- `app/src/main/java/com/munchkin/app/data/GameRepository.kt`
  Saved-game persistence contract plus Room-backed implementation for reconnect/resume.
- `app/src/main/java/com/munchkin/app/data/SessionManager.kt`
  Encrypted session/token/player-id persistence behind account and player-identity contracts.
- `app/src/test/java/com/munchkin/app/viewmodel`
  JVM tests for account, catalog, history, game-directory, update, gameplay transition, event factory, and GameViewModel create/join/action behavior.

### `:shared`

Authoritative shared contract and pure game logic consumed by both app and backend.

- `shared/src/main/kotlin/com/munchkin/app/core/Models.kt`
  Core ids, enums, player state, catalog entries, and game state.
- `shared/src/main/kotlin/com/munchkin/app/core/Events.kt`
  All gameplay events.
- `shared/src/main/kotlin/com/munchkin/app/core/Combat.kt`
  Combat state, monsters, modifiers, and combat result types.
- `shared/src/main/kotlin/com/munchkin/app/core/CombatCalculator.kt`
  Pure combat resolution and trait/conditional matching rules.
- `shared/src/main/kotlin/com/munchkin/app/core/GameEngine.kt`
  Authoritative state reducer used by the backend room manager and client-side replay.
- `shared/src/main/kotlin/com/munchkin/app/network/Protocol.kt`
  Gameplay websocket protocol messages and connection state only.
- `shared/src/main/kotlin/com/munchkin/app/network/ApiModels.kt`
  HTTP request/response DTOs and UI-facing remote DTOs for auth, profile, catalog, history, leaderboard, and game discovery.
- `shared/src/test/kotlin/com/munchkin/app/core`
  Shared rule regression tests.

### `:backend`

Ktor/PostgreSQL replacement backend.

- `backend/src/main/kotlin/com/munchkin/backend/Application.kt`
  Ktor module, HTTP routes, websocket entrypoints, and auth setup.
- `backend/src/main/kotlin/com/munchkin/backend/RoomManager.kt`
  In-memory authoritative room lifecycle and snapshot broadcasting.
- `backend/src/main/kotlin/com/munchkin/backend/Persistence.kt`
  Postgres and in-memory persistence implementations plus history/leaderboard/catalog storage.
- `backend/src/main/kotlin/com/munchkin/backend/Security.kt`
  JWT and password hashing helpers.
- `backend/src/main/kotlin/com/munchkin/backend/RateLimiter.kt`
  Small in-memory sliding-window limiter for authentication endpoints.
- `backend/src/main/kotlin/com/munchkin/backend/SqliteImportTool.kt`
  Legacy SQLite import entrypoint for migration.
- `backend/src/main/kotlin/com/munchkin/backend/DatabaseMigration.kt`
  Flyway migration entrypoint used by Gradle and CI.
- `backend/src/main/resources/db/migration`
  Flyway migrations.
- `backend/src/test/kotlin/com/munchkin/backend/ApplicationTest.kt`
  Backend integration coverage for auth, catalog, history, hosted games, open-game discovery, invalid websocket payloads, and auth rate limiting.

## Dependency Flow

`app` depends on `shared`.

`backend` depends on `shared`.

`shared` must stay platform-neutral and free of Android or server-only dependencies.

Gameplay state changes should flow through `shared` events and reducers before any UI-specific behavior is added.
