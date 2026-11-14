import asyncio
import base64
import hashlib
import json
import logging
import os
import ssl
import struct
import sys
import urllib.parse
import urllib.request
import uuid

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)

SERVER_URL = os.environ.get("MUNCHKIN_SERVER_URL", "ws://localhost:8765")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WebSocketHandshakeError(Exception):
    def __init__(self, status_code, status_line, headers):
        super().__init__(f"WebSocket handshake failed: {status_line}")
        self.status_code = status_code
        self.status_line = status_line
        self.headers = headers


class WebSocketClosed(Exception):
    def __init__(self, code=1005, reason=""):
        super().__init__(f"WebSocket closed ({code}: {reason})")
        self.code = code
        self.reason = reason


class SimpleWebSocketClient:
    """Minimal RFC 6455 WebSocket client built purely on Python stdlib asyncio."""

    def __init__(self, reader, writer):
        self._reader = reader
        self._writer = writer
        self._closed = False

    @classmethod
    async def connect(cls, url, origin=None, timeout=10.0):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("ws", "wss"):
            raise ValueError(f"Unsupported WebSocket scheme: {parsed.scheme}")
        host = parsed.hostname
        if not host:
            raise ValueError(f"Invalid WebSocket URL: {url}")
        is_tls = parsed.scheme == "wss"
        port = parsed.port or (443 if is_tls else 80)
        ssl_ctx = ssl.create_default_context() if is_tls else None

        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host, port, ssl=ssl_ctx),
            timeout=timeout,
        )

        try:
            target = parsed.path or "/"
            if parsed.query:
                target = f"{target}?{parsed.query}"
            host_header = parsed.netloc or (f"{host}:{port}" if parsed.port else host)
            sec_key = base64.b64encode(os.urandom(16)).decode("ascii")

            lines = [
                f"GET {target} HTTP/1.1",
                f"Host: {host_header}",
                "Upgrade: websocket",
                "Connection: Upgrade",
                f"Sec-WebSocket-Key: {sec_key}",
                "Sec-WebSocket-Version: 13",
            ]
            if origin is not None:
                lines.append(f"Origin: {origin}")
            raw_request = ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")

            writer.write(raw_request)
            await asyncio.wait_for(writer.drain(), timeout=timeout)

            raw_response = await asyncio.wait_for(
                reader.readuntil(b"\r\n\r\n"),
                timeout=timeout,
            )
            header_lines = raw_response.decode("latin-1").split("\r\n")
            status_line = header_lines[0]
            parts = status_line.split(" ", 2)
            status_code = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else 0

            headers = {}
            for line in header_lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            if status_code != 101:
                raise WebSocketHandshakeError(status_code, status_line, headers)

            expected_accept = base64.b64encode(
                hashlib.sha1((sec_key + WS_GUID).encode("ascii")).digest()
            ).decode("ascii")
            if headers.get("sec-websocket-accept") != expected_accept:
                raise WebSocketHandshakeError(status_code, "Invalid Sec-WebSocket-Accept", headers)

            return cls(reader, writer)
        except Exception:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            raise

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        await self.close()

    async def _send_frame(self, opcode, payload: bytes):
        fin_opcode = 0x80 | (opcode & 0x0F)
        length = len(payload)
        if length < 126:
            header = struct.pack("!BB", fin_opcode, 0x80 | length)
        elif length < 65536:
            header = struct.pack("!BBH", fin_opcode, 0x80 | 126, length)
        else:
            header = struct.pack("!BBQ", fin_opcode, 0x80 | 127, length)

        mask_key = os.urandom(4)
        masked = bytes(b ^ mask_key[i & 3] for i, b in enumerate(payload))
        self._writer.write(header + mask_key + masked)
        await self._writer.drain()

    async def _recv_frame(self):
        try:
            b1, b2 = await self._reader.readexactly(2)
        except (asyncio.IncompleteReadError, EOFError, ConnectionError) as exc:
            raise WebSocketClosed(1006, f"Connection closed: {exc}") from exc

        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        length = b2 & 0x7F

        try:
            if length == 126:
                (length,) = struct.unpack("!H", await self._reader.readexactly(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", await self._reader.readexactly(8))
            mask_key = await self._reader.readexactly(4) if masked else None
            payload = await self._reader.readexactly(length)
        except (asyncio.IncompleteReadError, EOFError, ConnectionError) as exc:
            raise WebSocketClosed(1006, f"Truncated frame: {exc}") from exc

        if mask_key is not None:
            payload = bytes(b ^ mask_key[i & 3] for i, b in enumerate(payload))
        return fin, opcode, payload

    async def send(self, text: str):
        await self._send_frame(0x1, text.encode("utf-8"))

    async def recv(self) -> str:
        fragments = []
        message_opcode = None

        while True:
            fin, opcode, payload = await self._recv_frame()

            if opcode == 0x8:  # CLOSE
                code = 1005
                reason = ""
                if len(payload) >= 2:
                    (code,) = struct.unpack("!H", payload[:2])
                    reason = payload[2:].decode("utf-8", errors="replace")
                if not self._closed:
                    self._closed = True
                    try:
                        await self._send_frame(0x8, struct.pack("!H", 1000))
                    except Exception:
                        pass
                    self._writer.close()
                raise WebSocketClosed(code, reason)

            if opcode == 0x9:  # PING
                await self._send_frame(0xA, payload)
                continue

            if opcode == 0xA:  # PONG
                continue

            if opcode in (0x1, 0x2):
                message_opcode = opcode
                fragments = [payload]
                if fin:
                    break
            elif opcode == 0x0:
                if message_opcode is None:
                    raise WebSocketClosed(1002, "Unexpected continuation frame")
                fragments.append(payload)
                if fin:
                    break
            else:
                raise WebSocketClosed(1002, f"Unsupported opcode {opcode}")

        data = b"".join(fragments)
        return data.decode("utf-8") if message_opcode == 0x1 else data

    async def close(self, code=1000):
        if self._closed:
            return
        self._closed = True
        try:
            await self._send_frame(0x8, struct.pack("!H", code))
        except Exception:
            pass
        self._writer.close()
        try:
            await self._writer.wait_closed()
        except Exception:
            pass


def ws_connect(url=SERVER_URL, origin=None, timeout=10.0):
    return SimpleWebSocketClient.connect(url, origin=origin, timeout=timeout)


async def recv_until(ws, predicate, timeout=5.0, fail_on_error=False):
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            raise TimeoutError("Timed out waiting for expected WebSocket message")
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        data = json.loads(raw)
        if predicate(data):
            return data
        if fail_on_error and data.get("type") == "ERROR":
            raise AssertionError(f"Unexpected server ERROR: {data}")


async def create_probe_game(name, requested_player_id=None):
    ws = await ws_connect(SERVER_URL)
    await ws.send(json.dumps({
        "type": "CreateGameMessage",
        "playerMeta": {
            "playerId": requested_player_id or f"requested-{uuid.uuid4().hex}",
            "name": name,
            "avatarId": 0,
            "gender": "M",
        },
        "superMunchkin": False,
        "turnTimerSeconds": 0,
    }))
    welcome = await recv_until(
        ws,
        lambda data: data.get("type") == "WELCOME",
        fail_on_error=True,
    )
    return ws, welcome


def test_health_endpoint():
    parsed = urllib.parse.urlsplit(SERVER_URL)
    http_scheme = "https" if parsed.scheme == "wss" else "http"
    health_url = urllib.parse.urlunsplit((http_scheme, parsed.netloc, "/health", "", ""))
    logger.info("Checking /health endpoint at %s...", health_url)
    req = urllib.request.Request(health_url, method="GET")
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise AssertionError(f"/health returned HTTP {resp.status}")
        payload = json.loads(resp.read().decode("utf-8"))
        if payload.get("status") != "ok":
            raise AssertionError(f"/health unexpected body: {payload}")
    logger.info("/health returned HTTP 200 OK")


async def test_connect():
    async with await ws_connect(SERVER_URL):
        logger.info("WebSocket connection established")


async def test_forged_origin_rejected():
    logger.info("Testing forged Origin rejection...")
    try:
        async with await ws_connect(SERVER_URL, origin="https://evil.example"):
            raise AssertionError("Server accepted WebSocket upgrade from forged Origin: https://evil.example")
    except WebSocketHandshakeError as exc:
        if exc.status_code != 403:
            raise AssertionError(f"Expected HTTP 403 for forged Origin, got {exc.status_code}") from exc
    logger.info("Forged Origin rejected with HTTP 403")


async def test_oversized_payload_rejected():
    logger.info("Testing >50KB oversized frame rejection...")
    async with await ws_connect(SERVER_URL) as ws:
        oversized = json.dumps({"type": "LIST_GAMES", "pad": "A" * (60 * 1024)})
        assert len(oversized.encode("utf-8")) > 50 * 1024
        try:
            await ws.send(oversized)
            reply = await asyncio.wait_for(ws.recv(), timeout=5.0)
            raise AssertionError(f"Server did not close connection on >50KB frame; received: {reply}")
        except (WebSocketClosed, ConnectionError, EOFError) as exc:
            if isinstance(exc, WebSocketClosed) and exc.code not in (1009, 1006, 1002):
                raise AssertionError(f"Unexpected close code for oversized frame: {exc.code}") from exc
    logger.info("Oversized >50KB frame rejected and connection closed")


async def test_sql_injection():
    logger.info("Testing SQL Injection resilience...")
    payloads = [
        "' OR '1'='1",
        "admin' --",
        "UNION SELECT * FROM users",
    ]
    bad_markers = ("SQLITE_ERROR", "syntax error", "SQLSTATE")

    async with await ws_connect(SERVER_URL) as ws:
        for payload in payloads:
            await ws.send(json.dumps({
                "type": "CATALOG_SEARCH",
                "query": payload,
            }))
            resp_search = await asyncio.wait_for(ws.recv(), timeout=5.0)
            if any(marker.lower() in resp_search.lower() for marker in bad_markers):
                raise AssertionError(f"Potential SQL error leaked on CATALOG_SEARCH payload {payload!r}: {resp_search}")

        await ws.send(json.dumps({
            "type": "LOGIN",
            "email": payloads[0],
            "password": "password123",
        }))
        login_resp = await asyncio.wait_for(ws.recv(), timeout=5.0)
        if any(marker.lower() in login_resp.lower() for marker in bad_markers):
            raise AssertionError(f"Potential SQL error leaked on LOGIN payload: {login_resp}")

        unique = uuid.uuid4().hex[:6]
        await ws.send(json.dumps({
            "type": "REGISTER",
            "username": f"sqli'{unique}--",
            "email": f"sqli-{unique}@example.test",
            "password": "password123",
            "avatarId": 0,
            "gender": "M",
        }))
        reg_resp = await asyncio.wait_for(ws.recv(), timeout=5.0)
        if any(marker.lower() in reg_resp.lower() for marker in bad_markers):
            raise AssertionError(f"Potential SQL error leaked on REGISTER payload: {reg_resp}")

    logger.info("SQL injection probes handled safely")


async def test_auth_token():
    logger.info("Testing REGISTER, LOGIN_WITH_TOKEN, and forged JWT rejection...")
    unique = uuid.uuid4().hex[:10]
    async with await ws_connect(SERVER_URL) as ws:
        await ws.send(json.dumps({
            "type": "REGISTER",
            "username": f"auth{unique}",
            "email": f"auth-{unique}@example.test",
            "password": "password123",
            "avatarId": 0,
            "gender": "M",
        }))
        reg = await recv_until(ws, lambda data: data.get("type") == "AUTH_SUCCESS", timeout=10.0, fail_on_error=True)
        token = reg.get("token")
        if not token:
            raise AssertionError(f"REGISTER did not return JWT token: {reg}")

    # Test forged JWT rejection (tampered signature + alg=none)
    header_b64, payload_b64, sig_b64 = token.split(".")
    tampered_sig = ("A" if sig_b64[0] != "A" else "B") + sig_b64[1:]
    tampered_token = f"{header_b64}.{payload_b64}.{tampered_sig}"

    alg_none_header = base64.urlsafe_b64encode(
        json.dumps({"alg": "none", "typ": "JWT"}).encode("utf-8")
    ).rstrip(b"=").decode("ascii")
    alg_none_token = f"{alg_none_header}.{payload_b64}."

    async with await ws_connect(SERVER_URL) as ws:
        # Reset auth failure tracking first so repeat runs never hit rate limit
        await ws.send(json.dumps({
            "type": "LOGIN_WITH_TOKEN",
            "token": token,
        }))
        first_ok = await recv_until(ws, lambda data: data.get("type") == "AUTH_SUCCESS", timeout=5.0, fail_on_error=True)
        if not first_ok.get("token"):
            raise AssertionError(f"LOGIN_WITH_TOKEN did not return refreshed token: {first_ok}")

        for forged in (tampered_token, alg_none_token):
            await ws.send(json.dumps({
                "type": "LOGIN_WITH_TOKEN",
                "token": forged,
            }))
            err = await recv_until(ws, lambda data: data.get("type") == "ERROR", timeout=5.0)
            if err.get("code") not in ("AUTH_FAILED", "UNAUTHORIZED"):
                raise AssertionError(f"Forged JWT was not rejected properly: {err}")

        # Valid LOGIN_WITH_TOKEN again to clear failed-attempt tracking for this IP
        await ws.send(json.dumps({
            "type": "LOGIN_WITH_TOKEN",
            "token": token,
        }))
        login_ok = await recv_until(ws, lambda data: data.get("type") == "AUTH_SUCCESS", timeout=5.0, fail_on_error=True)
        if not login_ok.get("token"):
            raise AssertionError(f"LOGIN_WITH_TOKEN did not return refreshed token: {login_ok}")

    # Unauthenticated private query must return UNAUTHORIZED
    async with await ws_connect(SERVER_URL) as unauth_ws:
        await unauth_ws.send(json.dumps({
            "type": "GET_HISTORY",
            "userId": reg["user"]["id"],
        }))
        unauth_err = await recv_until(unauth_ws, lambda data: data.get("type") == "ERROR", timeout=5.0)
        if unauth_err.get("code") != "UNAUTHORIZED":
            raise AssertionError(f"Unauthenticated GET_HISTORY was not rejected with UNAUTHORIZED: {unauth_err}")

    logger.info("Auth token & forged JWT checks passed")


async def test_reconnect_requires_server_secret():
    logger.info("Testing reconnect identity binding...")
    requested_host_id = f"attacker-chosen-{uuid.uuid4().hex}"
    host_ws, welcome = await create_probe_game("SecurityHost", requested_host_id)
    try:
        join_code = welcome["gameState"]["joinCode"]
        host_id = welcome["yourPlayerId"]
        reconnect_token = welcome.get("reconnectToken")

        if host_id == requested_host_id:
            raise AssertionError("Server accepted client-supplied playerId during game creation")
        if not reconnect_token:
            raise AssertionError("Server did not return reconnectToken")

        async with await ws_connect(SERVER_URL) as attacker_ws:
            await attacker_ws.send(json.dumps({
                "type": "HELLO",
                "joinCode": join_code,
                "playerMeta": {
                    "playerId": host_id,
                    "name": "Hijacker",
                    "avatarId": 1,
                    "gender": "M",
                },
            }))
            error = await recv_until(attacker_ws, lambda data: data.get("type") == "ERROR")
            if error.get("code") != "UNAUTHORIZED":
                raise AssertionError(f"Reconnect hijack was not rejected: {error}")

        async with await ws_connect(SERVER_URL) as reconnect_ws:
            await reconnect_ws.send(json.dumps({
                "type": "HELLO",
                "joinCode": join_code,
                "reconnectToken": reconnect_token,
                "playerMeta": {
                    "playerId": host_id,
                    "name": "SecurityHost",
                    "avatarId": 0,
                    "gender": "M",
                },
            }))
            reconnect_welcome = await recv_until(
                reconnect_ws,
                lambda data: data.get("type") == "WELCOME",
                fail_on_error=True,
            )
            if reconnect_welcome.get("yourPlayerId") != host_id:
                raise AssertionError("Valid reconnect did not preserve host player id")
            if reconnect_welcome.get("reconnectToken") == reconnect_token:
                raise AssertionError("Reconnect token did not rotate upon reconnect")
    finally:
        await host_ws.close()

    logger.info("Reconnect identity binding passed")


async def test_game_over_room_binding():
    logger.info("Testing GAME_OVER room binding...")
    target_ws, target_welcome = await create_probe_game("TargetHost")
    other_ws = None
    try:
        target_game_id = target_welcome["gameState"]["gameId"]
        target_host_id = target_welcome["yourPlayerId"]
        target_join_code = target_welcome["gameState"]["joinCode"]
        target_token = target_welcome["reconnectToken"]

        other_ws, _ = await create_probe_game("OtherHost", target_host_id)
        await other_ws.send(json.dumps({
            "type": "GAME_OVER",
            "gameId": target_game_id,
            "winnerId": target_host_id,
        }))
        error = await recv_until(other_ws, lambda data: data.get("type") == "ERROR")
        if error.get("code") != "UNAUTHORIZED":
            raise AssertionError(f"Cross-room GAME_OVER was not rejected: {error}")

        async with await ws_connect(SERVER_URL) as reconnect_ws:
            await reconnect_ws.send(json.dumps({
                "type": "HELLO",
                "joinCode": target_join_code,
                "reconnectToken": target_token,
                "playerMeta": {
                    "playerId": target_host_id,
                    "name": "TargetHost",
                    "avatarId": 0,
                    "gender": "M",
                },
            }))
            await recv_until(reconnect_ws, lambda data: data.get("type") == "WELCOME", fail_on_error=True)
            await reconnect_ws.send(json.dumps({
                "type": "GAME_OVER",
                "gameId": target_game_id,
                "winnerId": None,
            }))
            finished = await recv_until(
                reconnect_ws,
                lambda data: data.get("type") == "STATE_SNAPSHOT"
                and data.get("gameState", {}).get("phase") == "FINISHED",
                fail_on_error=True,
            )
            if finished["gameState"].get("phase") != "FINISHED":
                raise AssertionError("Legitimate host GAME_OVER did not finish target game")
    finally:
        await target_ws.close()
        if other_ws is not None:
            await other_ws.close()

    logger.info("GAME_OVER room binding passed")


async def test_catalog_add_requires_authenticated_user():
    logger.info("Testing CATALOG_ADD authentication and input clamping...")
    async with await ws_connect(SERVER_URL) as unauth_ws:
        await unauth_ws.send(json.dumps({
            "type": "CATALOG_ADD",
            "userId": "forged-user",
            "monster": {
                "name": "Forged Monster",
                "level": 5,
                "modifier": 0,
                "treasures": 1,
                "levels": 1,
                "isUndead": False,
            },
        }))
        error = await recv_until(unauth_ws, lambda data: data.get("type") == "ERROR")
        if error.get("code") != "UNAUTHORIZED":
            raise AssertionError(f"Unauthenticated catalog add was not rejected: {error}")

    async with await ws_connect(SERVER_URL) as auth_ws:
        unique = uuid.uuid4().hex
        await auth_ws.send(json.dumps({
            "type": "REGISTER",
            "username": f"cat{unique[:12]}",
            "email": f"cat-{unique}@example.test",
            "password": "password123",
            "avatarId": 0,
            "gender": "M",
        }))
        await recv_until(auth_ws, lambda data: data.get("type") == "AUTH_SUCCESS", timeout=10.0, fail_on_error=True)

        await auth_ws.send(json.dumps({
            "type": "CATALOG_ADD",
            "userId": "forged-user",
            "monster": {
                "name": "  Authenticated Probe Monster  ",
                "level": 99,
                "modifier": -99,
                "treasures": 99,
                "levels": 99,
                "isUndead": True,
                "badStuff": "  Lose 1 level  ",
                "expansion": "   ",
            },
        }))
        success = await recv_until(
            auth_ws,
            lambda data: data.get("type") == "CATALOG_ADD_SUCCESS",
            timeout=10.0,
            fail_on_error=True,
        )
        monster = success.get("monster", {})
        if not isinstance(monster.get("id"), str) or not monster.get("id"):
            raise AssertionError(f"Catalog add did not return monster id: {success}")
        if monster.get("createdBy") == "forged-user":
            raise AssertionError(f"Catalog attribution used forged userId: {success}")
        if (
            monster.get("name") != "Authenticated Probe Monster"
            or monster.get("level") != 20
            or monster.get("modifier") != -10
            or monster.get("treasures") != 10
            or monster.get("levels") != 5
            or monster.get("isUndead") is not True
            or monster.get("badStuff") != "Lose 1 level"
            or monster.get("expansion") != "base"
        ):
            raise AssertionError(f"Catalog input was not normalized/clamped per contract: {success}")

    logger.info("Catalog write authentication & clamping passed")


async def run_tests():
    logger.info("Starting Security Verification against %s", SERVER_URL)
    test_health_endpoint()
    await test_connect()
    await test_forged_origin_rejected()
    await test_oversized_payload_rejected()
    await test_sql_injection()
    await test_auth_token()
    await test_reconnect_requires_server_secret()
    await test_game_over_room_binding()
    await test_catalog_add_requires_authenticated_user()
    logger.info("All security verification checks passed")


if __name__ == "__main__":
    try:
        asyncio.run(run_tests())
    except KeyboardInterrupt:
        sys.exit(1)
    except Exception as exc:
        logger.error("Security verification failed: %s", exc, exc_info=True)
        sys.exit(1)
    sys.exit(0)
