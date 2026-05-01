# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Munchkin Mesa Tracker** is an Android client plus a Node.js backend for synchronized Munchkin sessions. The current product uses a remote authoritative WebSocket server; the old LAN/embedded-host path is no longer part of the active codebase.

## Build Commands

### Android Client

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew :app:test
./gradlew :app:test --tests "*.CombatCalculatorTest"
```

### Node.js Server

```bash
cd server
npm install
npm start
```

### Security Verification

```bash
python scripts/security_verify.py
```

## Architecture

### The Golden Rule

Clients do not authoritatively mutate gameplay state. Every action flows:

1. UI calls a `GameViewModel` action
2. `GameViewModel` sends a `WsMessage` through `GameClient`
3. `server.js` validates and mutates the room state
4. The server sends snapshots and events back to clients
5. The app renders the returned state

### Key Data Flow

```text
UI -> GameViewModel -> GameClient -> server.js
UI <- GameViewModel <- GameClient <- authoritative server state
```

### Core Modules

| Module | Purpose |
| --- | --- |
| `core/Models.kt` | Shared game model |
| `core/Events.kt` | Player/game events |
| `core/Combat.kt` | Combat state types |
| `core/CombatCalculator.kt` | Client-side combat math helpers |
| `core/GameEngine.kt` | Client reducer for snapshots/events |
| `network/Protocol.kt` | WebSocket message types |
| `network/GameClient.kt` | WebSocket client and one-off API requests |
| `network/ServerConfig.kt` | Active backend host/port/url |
| `viewmodel/GameViewModel.kt` | Main app orchestration |
| `server/server.js` | Main WebSocket server |
| `server/db.js` | SQLite persistence |
| `server/turnManager.js` | Turn order and timer lifecycle |
| `server/combatManager.js` | Combat-specific server logic |
| `server/catalogManager.js` | Monster catalog handlers |
| `server/authManager.js` | Login/register/profile handlers |
| `server/historyManager.js` | History and leaderboard handlers |
| `server/gameAdminManager.js` | Game-over/delete/kick/swap/admin handlers |

### Serialization Constraints

The Android client uses `kotlinx.serialization`; the Node server parses raw JSON. When changing `Models.kt`, `Events.kt`, or `Protocol.kt`:

- `@JvmInline value class` serializes as its primitive value
- Enum names must match the server expectations exactly
- `@SerialName` controls the wire field names

## Current Product Assumptions

- The backend is authoritative
- Turn timers are enforced on the server
- Critical turn/combat/win transitions are snapshot-first
- Home/profile/history/leaderboard calls use one-off network requests instead of requiring an active game socket

## Validation

Use these before closing backend/client refactors:

```bash
node --check server/server.js
node --check server/db.js
./gradlew :app:test
```
