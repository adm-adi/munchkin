import asyncio
import json
import logging
import websockets
import ssl
import sys

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

SERVER_URL = "ws://23.88.48.58:8765"
# SERVER_URL = "ws://localhost:8765"

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

async def run_tests():
    logger.info("🛡️ Starting Security Verification 🛡️")
    
    if not await test_connect():
        logger.error("Could not connect to server. Ensure it is running.")
        return

    await test_sql_injection()
    await test_auth_token()
    
    logger.info("🏁 Verification Complete")

if __name__ == "__main__":
    try:
        asyncio.run(run_tests())
    except KeyboardInterrupt:
        pass
