# Implementation Log

## 2026-06-10 - WebSocket root path default

Updated `GameClient.parseWsUrl` so pathless WebSocket URLs default to `/` instead of `/game`. This matches the deployed server behavior and fixes create/join failures caused by HTTP 404 during WebSocket upgrade. Bumped the app/server version to 2.20.7 for a GitHub release so installed apps can detect the update.

Validation: `.\gradlew.bat clean :app:test :app:assembleRelease` passed.
