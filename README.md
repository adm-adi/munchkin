# Munchkin Mesa Tracker

Android app and Kotlin backend for synchronized Munchkin table sessions.

## Stack

- Android client: Kotlin, Jetpack Compose, Material 3, Room, Ktor client.
- Shared rules/contracts: Kotlin JVM module with serializable game state, events, protocol DTOs, and pure combat logic.
- Backend: Kotlin, Ktor, WebSockets, HTTP JSON APIs, PostgreSQL, Flyway migrations, JWT auth, BCrypt password hashing.

## Build And Test

```powershell
.\gradlew.bat :shared:test
.\gradlew.bat :backend:test
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release signing reads `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, and `KEYSTORE_KEY_PASSWORD` from `local.properties` or environment variables. The release keystore is `munchkin.keystore` in the repo root.

## Run Backend Locally

Set PostgreSQL environment variables if you are not using the defaults:

```powershell
$env:MUNCHKIN_DATABASE_URL="jdbc:postgresql://localhost:5432/munchkin"
$env:MUNCHKIN_DATABASE_USER="munchkin"
$env:MUNCHKIN_DATABASE_PASSWORD="munchkin"
$env:MUNCHKIN_JWT_SECRET="change-this"
.\gradlew.bat :backend:migrateDatabase
.\gradlew.bat :backend:run
```

Default websocket endpoints:

```text
ws://localhost:8765/
ws://localhost:8765/ws/game
```

HTTP endpoints:

```text
GET  /health
POST /api/auth/register
POST /api/auth/login
GET  /api/profile
PATCH /api/profile
GET  /api/catalog/monsters
POST /api/catalog/monsters
GET  /api/history
GET  /api/leaderboard
GET  /api/games/open
GET  /api/games/hosted
DELETE /api/games/hosted/{gameId}
```

## Legacy Data Migration

Completed legacy SQLite data can be imported into PostgreSQL:

```powershell
$env:MUNCHKIN_DATABASE_URL="jdbc:postgresql://localhost:5432/munchkin"
$env:MUNCHKIN_DATABASE_USER="munchkin"
$env:MUNCHKIN_DATABASE_PASSWORD="munchkin"
.\gradlew.bat :backend:importLegacySqlite --args "C:\path\to\munchkin.db"
```

The importer migrates users, password hashes, monsters, and completed game history. Live in-progress rooms are intentionally not imported.

## Deploy

Local Android install:

```powershell
.\deploy_updates.ps1
```

Production backend deploy:

```powershell
.\deploy-backend-prod.ps1
```

The deploy script packages the Ktor backend, uploads it to Hetzner, runs Flyway migrations, and restarts the `munchkin-backend` systemd service.

## Project Map

See `CODEBASE.md` for the current module map and dependency flow.
