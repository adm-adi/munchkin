# 🗺️ Munchkin Mesa Tracker - Project Architecture & Codebase Map

This document serves as the "**Map of the Territory**" for the Munchkin Mesa Tracker project. It is intended for AI agents and human developers alike to quickly understand how the project is structured, its core architecture, and where to find key components.

> **Note to AI Agents**: Read this document thoroughly before proposing structural changes, adding new features, or debugging complex state issues. It defines the core paradigms of the project.

---

## 1. High-Level Architecture

Munchkin Mesa Tracker is an Android application designed for local multiplayer (LAN) using a client-server model over WebSockets.

*   **Platform**: Android (Min SDK 26, Target SDK 35). Written entirely in Kotlin.
*   **UI Framework**: Jetpack Compose with Material 3.
*   **Architecture Pattern**: MVVM (Model-View-ViewModel) + UDF (Unidirectional Data Flow). Actions flow *up* as `GameEvent`s, state flows *down* as a single `GameState` object via Kotlin `StateFlow`.
*   **Networking**:
    *   **Server**: Embedded Node.js server (`server.js`) via WebSockets (ws library). Handles game rooms, state persistence (SQLite `db.js`), simple JWT auth, and event broadcasting.
    *   **Client**: Ktor WebSockets (`GameClient.kt`).
    *   **Discovery**: NSD (Network Service Discovery) and experimental WiFi Direct support.
*   **State Management**: Event Sourcing paradigm. The server maintains the source of truth (`GameState`). Clients dispatch *Intents/Events*, the server validates and processes them via `GameEngine.kt` (or server-side logic), mutates the state, and broadcasts the new state or the validated event to all clients.

---

## 2. Directory Structure

The project is split into two main parts: the **Android Client** (`/app`) and the **Backend Server** (`/server`).

```text
Munchkin/
├── app/src/main/java/com/munchkin/app/    # Android Native Client (Kotlin)
│   ├── core/           # Core Domain Models & Game Logic Engine (Pure Kotlin)
│   ├── network/        # Networking (Ktor WebSockets, NSD, WiFi Direct)
│   ├── data/           # Persistence (Room DB, SharedPreferences Session)
│   ├── ui/             # View Layer (Jetpack Compose)
│   │   ├── screens/    # Full-screen Compose Destinations
│   │   ├── components/ # Reusable UI widgets
│   │   └── theme/      # Material 3 Styling (Colors, Typography)
│   ├── viewmodel/      # Presentation Layer (GameViewModel)
│   ├── update/         # In-App Updater via GitHub Releases
│   └── util/           # Helpers (Localization Manager, etc)
├── server/             # Backend Node.js Server
│   ├── server.js       # Main WebSocket & HTTP server entry point
│   ├── db.js           # SQLite database wrapper
│   ├── package.json    # Node dependencies (ws, sqlite3, helmet, bcryptjs)
│   ├── monsters_seed.sql # Initial dataset for the catalog
│   └── logger.js       # Winston-based logging utility
├── scripts/            # Python utility scripts (Linting, Security, Deploy)
├── .agent/             # AI Agent Rules and Skills definitions
└── README.md           # Quick setup guide and basic info
```

---

## 3. Core Android Modules Deep Dive

### 3.1 `core/` - The Heart of the Game

This package contains pure data structures and the logic engine. It has NO Android dependencies. It relies heavily on `kotlinx.serialization` for JSON conversion.

*   **`Models.kt`**: Defines the source of truth for the game state.
    *   `GameState`: Complete snapshot of a game room (players, combat, phase).
    *   `PlayerState`: Detailed stats of a single player (level, gear, race, class).
    *   `CatalogEntry`: Defines playable Classes and Races.
*   **`Events.kt`**: Defines every single action a player can take using a sealed class hierarchy (`GameEvent`). E.g., `PlayerJoin`, `IncLevel`, `CombatStart`, `PlayerRoll`. When a user clicks a button, a `GameEvent` is constructed and sent to the server.
*   **`Combat.kt`**: Models combat scenarios. `CombatState`, `MonsterInstance`, and `CombatResult`. Supports temporary bonuses and conditions (e.g., +5 against Elves).
*   **`GameEngine.kt`**: The state reducer. *Currently handles client-side predictions or actions, but the server (`server.js`) is the ultimate validator.*
*   **`CombatCalculator.kt`**: Pure logic to evaluate a `CombatState` and determine if heroes are winning or losing (resolves ties based on Warrior class, applies conditional modifiers).

### 3.2 `network/` - Connectivity

*   **`Protocol.kt`**: Defines the WebSocket message envelopes (`WsMessage` sealed class). For example, `EventRequestMessage` (Client -> Server) and `EventBroadcastMessage` (Server -> Client). Also handles Auth messages (`LoginMessage`, `RegisterMessage`).
*   **`GameClient.kt`**: The Ktor WebSocket client. Connects to `ws://IP:PORT`. Sends `WsMessage` objects. Receives state updates and emits them to a Kotlin `SharedFlow` so the ViewModel can react.
*   **`NsdHelper.kt` / `WifiDirectHelper.kt`**: Used by the app to broadcast and discover local games on the LAN automatically, eliminating the need to manually type IP addresses.

### 3.3 `data/` - Persistence

*   **`SessionManager.kt`**: Wrapper around `SharedPreferences` to manage user login sessions (`UserProfile`, JSON Web Tokens) and save the player's last known `playerId` to handle quick reconnections.
*   **`MunchkinDatabase.kt` / `SavedGameDao.kt` / `GameRepository.kt`**: Room Database implementation for persisting offline data or saving local game history.

### 3.4 `viewmodel/` - State Orchestrator

*   **`GameViewModel.kt`**: The massive central ViewModel (approx 60,000 bytes). It bridges the UI and the Network.
    *   Holds the `uiState: StateFlow<GameState?>`.
    *   Handles connection lifecycle.
    *   Provides public functions called by the UI (e.g., `incrementLevel()`, `startCombat()`) which internally instantiate a `GameEvent` and ask the `GameClient` to send it via WebSocket.

### 3.5 `ui/` - Presentation

Built entirely in Jetpack Compose. Follows Material 3 guidelines and strongly enforces customized styling rather than default components.

*   **`screens/`**:
    *   `HomeScreen`, `LobbyScreen`, `JoinGameScreen`, `CreateGameScreen` - Flow for finding and entering a game.
    *   `TableScreen` - The main gameplay screen. Grid/List of all players.
    *   `BoardScreen` - The active player's detailed stats dashboard.
    *   `CombatScreen` - Dedicated screen for resolving fights.
    *   `AuthScreen`, `ProfileScreen`, `HistoryScreen`, `MonsterCatalogScreen`.
*   **`components/`**: Reusable interactive widgets.
    *   `CombatResultOverlay.kt`, `Dice3D.kt`, `RunAwayDialog.kt`, `QrScanner.kt`.
    *   `ModernComponents.kt` - Custom styled cards, buttons, etc.

---

## 4. Backend Module Deep Dive (`/server`)

The server is a lightweight Node.js script. It's meant to run either securely in the cloud or on a local hub device (e.g., Raspberry Pi on the LAN).

*   **`server.js`**: Core logic.
    *   Initializes HTTP and WebSocket Server (using `ws`).
    *   Connects Helmet for security headers and Winston for logging (`logger.js`).
    *   **In-Memory State**: Active `GameRoom` objects are kept in memory (`const games = new Map()`).
    *   **Routing**: The `handleMessage` switch-case routes raw JSON to specific handlers (`handleHello`, `handleEvent`, `handleCombat...`).
    *   **Validation**: It acts as the arbiter. When an `EventRequestMessage` arrives, the server checks the rules, applies the state mutation, updates the `GameRoom`, and broadcasts the change.
*   **`db.js`**: SQLite integration via the `sqlite3` module. Stores User Profiles, hashed passwords (bcrypt), Monster Catalog, and Game History.

---

## 5. Security & Operation Flow

1.  **Authentication**: Users create accounts or play as guests. `SessionManager` locally holds JWTs. `handleHello` over WebSocket passes a `PlayerMeta` with user identifiers.
2.  **Joining a Game**: A player hits `JoinGameScreen`. The app connects to `ws://IP:PORT`. Sends `HELLO` message with `joinCode`. Server checks limits (6 players max), assigns a `playerId`, adds them to the `GameRoom`, and broadcasts a full `WelcomeMessage` with the current `GameState`.
3.  **Taking Actions**: A user taps their Level number to increase it (`BoardScreen.kt`).
    *   The Compose View calls `viewModel.incrementLevel()`.
    *   `GameViewModel` emits an `IncLevel` event wrapped in an `EventRequestMessage`.
    *   `GameClient` serializes it via Ktor WebSocket.
    *   `server.js` receives it, verifies turn/permissions, mutates the in-memory `GameRoom`.
    *   `server.js` broadcasts an `EventBroadcastMessage` back to all clients in that room.
    *   Clients recreate their `GameState`, updating the UI instantly.

> **Crucial Rule**: The client NEVER directly mutates the UI state. It *always* waits for the server response/broadcast before reflecting changes. This prevents desync.

## 6. Development Rules & Guidelines

*   **Serialization Constraints**: The client uses `kotlinx.serialization` while the server uses standard Javascript `JSON.parse`. When modifying `Models.kt` or `Protocol.kt`, ensure that `@JvmInline value classes` and Enums match exactly what the JavaScript server expects (e.g., inline classes serialize as primitive types, not wrapper objects).
*   **Translation**: Support uses `LocaleManager.kt`. New strings must be added to strings.xml (English and Spanish at minimum). No hardcoded strings in Compose.
*   **Security Audits**: Run `python scripts/security_verify.py` manually before pushing. Ensure `try-catch` blocks are maintained around raw WebSocket data parsing (DoS protection).

---
*(End of documentation)*
