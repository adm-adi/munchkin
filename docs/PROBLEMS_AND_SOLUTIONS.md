# Problems and Solutions

## WebSocket create/join 404 on deployed server

Problem: The Android client defaulted pathless WebSocket URLs to `/game`. The deployed server at `wss://munchking-sirpepo.duckdns.org:8765/` accepts WebSocket upgrades at `/`, while `/game` returns HTTP 404, causing Ktor to report: `Handshake exception, expected status code 101 but was 404`.

Solution: Keep explicit paths when configured, but default pathless WebSocket URLs to `/` in `GameClient.parseWsUrl`.
