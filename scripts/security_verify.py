import asyncio
import json
import logging
import os
import websockets
import ssl
import sys
import uuid

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

SERVER_URL = os.environ.get("MUNCHKIN_SERVER_URL", "ws://localhost:8765")

async def recv_until(websocket, predicate, timeout=5):
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            raise TimeoutError("Timed out waiting for expected websocket message")
        resp = await asyncio.wait_for(websocket.recv(), timeout=remaining)
        data = json.loads(resp)
        if predicate(data):
            return data

async def create_probe_game(name, requested_player_id=None):
    websocket = await websockets.connect(SERVER_URL)
    await websocket.send(json.dumps({
        "type": "CreateGameMessage",
        "playerMeta": {
            "playerId": requested_player_id or f"requested-{uuid.uuid4().hex}",
            "name": name,
            "avatarId": 0,
            "gender": "MALE"
        },
        "superMunchkin": False,
        "turnTimerSeconds": 0
    }))
    welcome = await recv_until(websocket, lambda data: data.get("type") == "WELCOME")
    return websocket, welcome

async def test_connect():
    """Test basic connection"""
    try:
        async with websockets.connect(SERVER_URL) as websocket:
            logger.info("✅ Connection established")
            return True
    except Exception as e:
        logger.error(f"❌ Connection failed: {e}")
        return False

async def test_sql_injection():
    """Test SQL Injection in Logic (not directly possible via WS but via payloads)"""
    logger.info("Testing SQL Injection resilience...")
    payloads = [
        "' OR '1'='1",
        "admin' --",
        "UNION SELECT * FROM users"
    ]
    
    async with websockets.connect(SERVER_URL) as websocket:
        # Test 1: Register with SQLi username
        for p in payloads:
            msg = {
                "type": "REGISTER",
                "username": f"user_{p}",
                "email": f"test_{p}@example.com",
                "password": "password123",
                "avatarId": 0
            }
            await websocket.send(json.dumps(msg))
            resp = await websocket.recv()
            
            # We expect either success (sanitized) or specific error, NOT a database error
            if "SQLITE_ERROR" in resp or "syntax error" in resp:
                logger.error(f"❌ SQL Injection vulnerability detected with payload: {p}")
                logger.error(f"Response: {resp}")
            else:
                logger.info(f"✅ Input '{p}' handled safely.")

async def test_auth_token():
    """Test Login and Token return"""
    logger.info("Testing Auth Token generation...")
    async with websockets.connect(SERVER_URL) as websocket:
        # Register
        reg_msg = {
            "type": "REGISTER",
            "username": "authtest",
            "email": "authtest@example.com",
            "password": "password123",
            "avatarId": 0
        }
        await websocket.send(json.dumps(reg_msg))
        resp = await websocket.recv()
        data = json.loads(resp)
        
        if "token" in data:
            logger.info("✅ JWT Token received on register")
            token = data['token']
            
            # Test Login with Token
            login_msg = {
                "type": "LOGIN_WITH_TOKEN",
                "token": token
            }
            await websocket.send(json.dumps(login_msg))
            resp2 = await websocket.recv()
            data2 = json.loads(resp2)
            
            if data2.get("type") == "AUTH_SUCCESS":
                logger.info("✅ Login with Token succeeded")
            else:
                logger.error(f"❌ Login with Token failed: {resp2}")
        else:
             # Might be because user already exists, try login
            login_msg = {
                "type": "LOGIN",
                "email": "authtest@example.com",
                "password": "password123"
            }
            await websocket.send(json.dumps(login_msg))
            resp = await websocket.recv()
            data = json.loads(resp)
            if "token" in data:
                logger.info("✅ JWT Token received on login")
            else:
                logger.error(f"❌ No token in auth response: {resp}")

async def test_reconnect_requires_server_secret():
    """Client-supplied playerId alone must not authorize reconnect."""
    logger.info("Testing reconnect identity binding...")
    requested_host_id = f"attacker-chosen-{uuid.uuid4().hex}"
    host_ws, welcome = await create_probe_game("SecurityHost", requested_host_id)
    join_code = welcome["gameState"]["joinCode"]
    host_id = welcome["yourPlayerId"]
    reconnect_token = welcome.get("reconnectToken")

    if host_id == requested_host_id:
        raise AssertionError("Server accepted client-supplied playerId during game creation")
    if not reconnect_token:
        raise AssertionError("Server did not return reconnect token")

    async with websockets.connect(SERVER_URL) as attacker_ws:
        await attacker_ws.send(json.dumps({
            "type": "HELLO",
            "joinCode": join_code,
            "playerMeta": {
                "playerId": host_id,
                "name": "Hijacker",
                "avatarId": 1,
                "gender": "MALE"
            }
        }))
        error = await recv_until(attacker_ws, lambda data: data.get("type") == "ERROR")
        if error.get("code") != "UNAUTHORIZED":
            raise AssertionError(f"Reconnect hijack was not rejected: {error}")

    async with websockets.connect(SERVER_URL) as reconnect_ws:
        await reconnect_ws.send(json.dumps({
            "type": "HELLO",
            "joinCode": join_code,
            "reconnectToken": reconnect_token,
            "playerMeta": {
                "playerId": host_id,
                "name": "SecurityHost",
                "avatarId": 0,
                "gender": "MALE"
            }
        }))
        reconnect_welcome = await recv_until(reconnect_ws, lambda data: data.get("type") == "WELCOME")
        if reconnect_welcome.get("yourPlayerId") != host_id:
            raise AssertionError("Valid reconnect did not preserve host player id")

    await host_ws.close()
    logger.info("Reconnect identity binding passed")

async def test_game_over_room_binding():
    """GAME_OVER must apply only to the caller's current room."""
    logger.info("Testing GAME_OVER room binding...")
    target_ws, target_welcome = await create_probe_game("TargetHost")
    target_game_id = target_welcome["gameState"]["gameId"]
    target_host_id = target_welcome["yourPlayerId"]
    target_join_code = target_welcome["gameState"]["joinCode"]
    target_token = target_welcome["reconnectToken"]

    other_ws, _ = await create_probe_game("OtherHost", target_host_id)
    await other_ws.send(json.dumps({
        "type": "GAME_OVER",
        "gameId": target_game_id,
        "winnerId": target_host_id
    }))
    error = await recv_until(other_ws, lambda data: data.get("type") == "ERROR")
    if error.get("code") != "UNAUTHORIZED":
        raise AssertionError(f"Cross-room GAME_OVER was not rejected: {error}")

    async with websockets.connect(SERVER_URL) as reconnect_ws:
        await reconnect_ws.send(json.dumps({
            "type": "HELLO",
            "joinCode": target_join_code,
            "reconnectToken": target_token,
            "playerMeta": {
                "playerId": target_host_id,
                "name": "TargetHost",
                "avatarId": 0,
                "gender": "MALE"
            }
        }))
        await recv_until(reconnect_ws, lambda data: data.get("type") == "WELCOME")
        await reconnect_ws.send(json.dumps({
            "type": "GAME_OVER",
            "gameId": target_game_id,
            "winnerId": target_host_id
        }))
        finished = await recv_until(
            reconnect_ws,
            lambda data: data.get("type") == "STATE_SNAPSHOT"
            and data.get("gameState", {}).get("phase") == "FINISHED"
        )
        if finished["gameState"].get("winnerId") != target_host_id:
            raise AssertionError("Legitimate host GAME_OVER did not finish target game")

    await target_ws.close()
    await other_ws.close()
    logger.info("GAME_OVER room binding passed")

async def test_catalog_add_requires_authenticated_user():
    """CATALOG_ADD attribution must come from ws.userId, not message.userId."""
    logger.info("Testing catalog write authentication...")
    async with websockets.connect(SERVER_URL) as unauth_ws:
        await unauth_ws.send(json.dumps({
            "type": "CATALOG_ADD",
            "userId": "forged-user",
            "monster": {
                "name": "Forged Monster",
                "level": 5,
                "modifier": 0,
                "treasures": 1,
                "levels": 1,
                "isUndead": False
            }
        }))
        error = await recv_until(unauth_ws, lambda data: data.get("type") == "ERROR")
        if error.get("code") != "UNAUTHORIZED":
            raise AssertionError(f"Unauthenticated catalog add was not rejected: {error}")

    async with websockets.connect(SERVER_URL) as auth_ws:
        unique = uuid.uuid4().hex
        await auth_ws.send(json.dumps({
            "type": "REGISTER",
            "username": f"cat{unique[:12]}",
            "email": f"cat-{unique}@example.test",
            "password": "password123",
            "avatarId": 0
        }))
        auth = await recv_until(auth_ws, lambda data: data.get("type") == "AUTH_SUCCESS", timeout=10)
        await auth_ws.send(json.dumps({
            "type": "CATALOG_ADD",
            "userId": "forged-user",
            "monster": {
                "name": "  Authenticated Probe Monster  ",
                "level": 99,
                "modifier": -99,
                "treasures": 99,
                "levels": 99,
                "isUndead": True
            }
        }))
        success = await recv_until(auth_ws, lambda data: data.get("type") == "CATALOG_ADD_SUCCESS", timeout=10)
        monster = success.get("monster", {})
        if monster.get("createdBy") != auth["user"]["id"]:
            raise AssertionError(f"Catalog attribution did not use authenticated user: {success}")
        if monster.get("name") != "Authenticated Probe Monster" or monster.get("level") != 20:
            raise AssertionError(f"Catalog input was not normalized: {success}")

    logger.info("Catalog write authentication passed")

async def run_tests():
    logger.info("🛡️ Starting Security Verification 🛡️")
    
    if not await test_connect():
        logger.error("Could not connect to server. Ensure it is running.")
        return

    await test_sql_injection()
    await test_auth_token()
    await test_reconnect_requires_server_secret()
    await test_game_over_room_binding()
    await test_catalog_add_requires_authenticated_user()
    
    logger.info("🏁 Verification Complete")

if __name__ == "__main__":
    try:
        asyncio.run(run_tests())
    except KeyboardInterrupt:
        pass
