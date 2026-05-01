# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

---

## Project Overview

**Munchkin Mesa Tracker** is an Android app for local multiplayer Munchkin board game sessions over LAN. It uses a client-server architecture: an embedded Node.js server handles game state over WebSockets, and an Android Kotlin client connects to it.

---

## Build Commands

### Android Client

```bash
# Build debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build release APK (requires keystore credentials in local.properties)
./gradlew assembleRelease

# Run unit tests
./gradlew :app:test

# Run a specific test class
./gradlew :app:test --tests "*.CombatCalculatorTest"
```

Signing credentials (`KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`) are read from `local.properties` (gitignored) or environment variables. Keystore file: `munchkin.keystore` in the repo root.

### Node.js Server

```bash
cd server
npm install
npm start          # node server.js — runs on port 8765
```

### Security Verification

```bash
python scripts/security_verify.py   # Run before pushing
```

### Validation

```bash
node --check server/server.js
node --check server/db.js
./gradlew :app:test
```

---

## Testing on Emulators (Two-Device Setup)

```bash
# Terminal 1 — Host emulator
emulator -avd Pixel_6_API_30 -port 5554

# Terminal 2 — Client emulator
emulator -avd Pixel_6_API_30_copy -port 5556

# Allow client emulator to reach host emulator's server
adb -s emulator-5556 reverse tcp:8765 tcp:8765
# Client connects to: 10.0.2.2:8765
```

---

## Architecture

### The Golden Rule

**Clients never mutate UI state directly.** Every action flows:
1. UI calls `GameViewModel.someAction()`
2. ViewModel emits a `GameEvent` wrapped in `EventRequestMessage` via WebSocket
3. `server.js` validates, mutates in-memory `GameRoom`, broadcasts back
4. All clients receive `EventBroadcastMessage` and rebuild their `GameState`

### Key Data Flow

```
UI (Compose) → GameViewModel → GameClient (Ktor WS) → server.js
                                                           ↓
UI ← GameViewModel ← GameClient ←────────────── broadcast to all clients
```

### Critical Serialization Constraint

The client uses `kotlinx.serialization`; the server uses plain `JSON.parse`. When modifying `Models.kt`, `Events.kt`, or `Protocol.kt`:
- `@JvmInline value class` serializes as its primitive type (not a wrapper object)
- Enum names must match exactly what JavaScript expects
- `@SerialName` annotations control the JSON field names

### Core Modules

| Module | Purpose |
|--------|---------|
| `core/Models.kt` | `GameState`, `PlayerState`, `CatalogEntry` — the data model |
| `core/Events.kt` | Sealed `GameEvent` hierarchy — every player action |
| `core/Combat.kt` | `CombatState`, `MonsterInstance`, `CombatResult` |
| `core/CombatCalculator.kt` | Pure logic: evaluates combat outcome (warrior tie-break, conditional modifiers) |
| `core/GameEngine.kt` | Client-side state reducer (server is authoritative) |
| `network/Protocol.kt` | WebSocket message types (`WsMessage` sealed class) |
| `network/GameClient.kt` | Ktor WS client, emits to `SharedFlow` for ViewModel |
| `viewmodel/GameViewModel.kt` | ~60KB central ViewModel; owns `uiState: StateFlow<GameState?>` |
| `server/server.js` | Node.js: auth, room management, event routing, state broadcast |
| `server/db.js` | SQLite: users, hashed passwords (bcrypt), monster catalog, game history |

### Navigation / Screen Flow

```
HomeScreen → CreateGameScreen ─┐
           → JoinGameScreen   ─┴→ LobbyScreen → TableScreen
                                                → BoardScreen (own player)
                                                → CombatScreen
```

### Server Architecture

`server.js` uses a `handleMessage` switch-case routing raw WebSocket JSON to handlers (`handleHello`, `handleEvent`, `handleCombat…`). Active game rooms live in `const games = new Map()` (in-memory). Auth uses simple JWTs; passwords are bcrypt-hashed in SQLite.

---

## Development Rules

- **Localization**: All user-facing strings go in `strings.xml`. English and Spanish are required. `LocaleManager.kt` handles switching. No hardcoded strings in Compose.
- **UI style**: Material 3 with custom styling. Avoid default/unstyled components. See `ui/theme/` and `ui/components/ModernComponents.kt`.
- **WebSocket safety**: Always wrap raw WebSocket data parsing in `try-catch` (DoS protection). The server enforces a 50KB max payload.
- **Player limits**: Max 6 players per room. Level is clamped 1–10. Race/class limits enforced server-side.
- **`CODEBASE.md`**: A map of file dependencies lives in `CODEBASE.md` — check it before modifying shared files to identify what else needs updating.

---

## Production Server

The server runs as a `systemd` service on a Hetzner VPS:

```bash
# Deploy
cd /opt/munchkin-server && git pull && systemctl restart munchkin

# Status
systemctl status munchkin

# Logs
journalctl -u munchkin -f

# Stop
systemctl stop munchkin
```

WebSocket endpoint: `ws://23.88.48.58:8765`

---

## Versioning & Releases

Versions are set in `app/build.gradle.kts` (`versionName`, `versionCode`) and mirrored in `server/package.json`. The GitHub Actions workflow at `.github/workflows/release.yml` builds and publishes releases. Use `scripts/bump_version.py` to increment versions consistently.

When release signing needs to be checked or GitHub secrets need to be regenerated, run:

```bash
python scripts/verify_releases_secrets.py
```

On Windows, `.\deploy_updates.ps1` is the repo helper for push + install/release flows. It auto-commits and pushes current changes, then installs a release build on connected devices or bumps/tags/pushes a release when no device is connected.
