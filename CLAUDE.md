# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Munchkin Mesa Tracker** is an Android client plus a Node.js backend for synchronized Munchkin sessions, plus a web client (`server/public/`) served by the backend itself so iPhone/desktop players can join the same rooms. The current product uses a remote authoritative WebSocket server; the old LAN/embedded-host path is no longer part of the active codebase.

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
| `viewmodel/GameViewModel.kt` | Shared state holder; behaviour lives in the `GameViewModel*.kt` extension files |
| `viewmodel/GameViewModelAuth/Combat/Lobby/Player/System.kt` | Top-level `GameViewModel` extension functions by area |
| `server/server.js` | Main WebSocket server |
| `server/validation.js` | Pure input-validation helpers (unit tested; no side effects on require) |
| `server/db.js` | SQLite persistence |
| `server/turnManager.js` | Turn order and timer lifecycle |
| `server/combatManager.js` | Combat-specific server logic |
| `server/catalogManager.js` | Monster catalog handlers |
| `server/authManager.js` | Login/register/profile handlers |
| `server/historyManager.js` | History and leaderboard handlers |
| `server/gameAdminManager.js` | Game-over/delete/kick/swap/admin handlers |
| `server/staticFiles.js` | Safe static serving for the web client (path-traversal proof) |
| `server/public/` | Web client (PWA, no build step): `js/protocol.mjs` mirrors `Protocol.kt`/`Events.kt`, `js/engine.mjs` mirrors `GameEngine.kt`, `js/combat.mjs` mirrors `CombatCalculator.kt`, `js/net.mjs` mirrors `GameClient.kt`, `js/app.mjs` is the UI |

### Serialization Constraints

The Android client uses `kotlinx.serialization`; the Node server parses raw JSON. When changing `Models.kt`, `Events.kt`, or `Protocol.kt`:

- `@JvmInline value class` serializes as its primitive value
- Enum names must match the server expectations exactly
- `@SerialName` controls the wire field names

**Decoding is strict and failures are room-wide.** Client state arrives as a full
snapshot broadcast to everyone, so a value the client cannot decode breaks every
player in the room, not just the sender. Two consequences:

- Any gameplay value the server writes must be validated first. `server/validation.js`
  holds the whitelists and clamps; its `VALID_GENDERS` / `VALID_CLASSES` / `VALID_RACES`
  sets must mirror the Kotlin enums in `core/Models.kt` exactly, and a test asserts it.
- Adding an enum case server-side without adding it client-side is a breaking change.
  `ErrorCode` has an `UNKNOWN` fallback plus `coerceInputValues` for this reason; other
  enums do not, so they need both sides changed together.

Response DTOs must be mapped to camelCase before sending. SQLite returns snake_case
(`avatar_id`), and a client field with no default (`LeaderboardEntry.avatarId`) makes
decoding throw outright.

## Current Product Assumptions

- The backend is authoritative
- Turn timers are enforced on the server
- Critical turn/combat/win transitions are snapshot-first
- Home/profile/history/leaderboard calls use one-off network requests instead of requiring an active game socket

## Validation

Run these before closing backend/client changes:

```bash
cd server && npm run check && npm test
```

```bash
./gradlew :app:test
```

The Android build is the real gate — v2.20.12 was published with imports that did
not resolve, so a green `node --check` alone proves very little:

```bash
./gradlew :app:compileDebugKotlin
```

Server tests use the built-in `node:test` runner, so there is no test dependency to
install. There are two layers:

- `validation.test.js` — pure helpers from `validation.js`. Keep validation logic
  there rather than in `server.js`: requiring `server.js` binds a port and opens the
  database, so nothing defined in it is testable.
- `protocol.test.js` — integration tests that boot the real server on a free port
  against a temporary database (`testServer.js`) and drive it over a real
  WebSocket. This is the layer that covers the handlers, which is where the bugs
  have actually been.
- `webclient.test.mjs` — static-file serving (MIME types, traversal attempts) plus
  the same boot-a-real-server pattern driven through the web client's own modules
  (`public/js/protocol.mjs`, `net.mjs`, `engine.mjs`, `combat.mjs`), proving the
  web client and the server stay wire-compatible.

When writing protocol tests, use `client.drain()` / `await client.settle()` before
asserting on a reply. Joins, another player's actions, and timer changes all
broadcast to every client, so an assertion that accepts `STATE_SNAPSHOT` will
otherwise match a stale queued broadcast instead of the response it just triggered.

Test-only environment knobs, all with production-safe defaults: `PORT`,
`MUNCHKIN_DB_PATH`, `MUNCHKIN_LOG_DIR`, `MUNCHKIN_REGISTER_LIMIT`.

## Invariants worth preserving

- **Never commit `server/munchkin.db`.** It holds `users(email, password_hash)` and
  `active_games.players_json` with per-seat `reconnectTokenHash` values. It was
  committed to this public repo once already.
- **Never log credentials.** WELCOME carries a `reconnectToken`, AUTH_SUCCESS carries
  a JWT, and login/register requests carry a plaintext password. The in-app
  `DebugLogViewer` makes client-side logs user-visible.
- **bcrypt must stay async.** The sync variants block Node's single event loop for the
  full cost-12 hash, freezing every active game on each login.
- **Client-side combat math must match `combatManager.js`.** Three implementations
  compute the outcome — `core/CombatCalculator.kt`, `server/combatManager.js`, and
  `server/public/js/combat.mjs` — and the server wins. A rule change must land in
  all three. Divergence shows up as the `COMBAT_END mismatch` warning.
- **The web client speaks the Android dialect.** Events built in
  `public/js/protocol.mjs` are re-broadcast verbatim to Android clients, whose
  kotlinx decoding throws on a missing field with no default — builders must send
  every field, defaults included (`webclient.test.mjs` asserts the shapes).
- **Requests carrying a `userId` must authorize it against `ws.userId`.** Trusting the
  client-supplied id is how `GET_HISTORY` became an IDOR.
