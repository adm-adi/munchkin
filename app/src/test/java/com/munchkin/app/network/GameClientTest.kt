package com.munchkin.app.network

import com.munchkin.app.core.EndTurn
import com.munchkin.app.core.GameId
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GameClient] URL security policy, lifecycle/Closeable behavior,
 * and disconnected guard paths.
 */
class GameClientTest {

    @Test
    fun `parseWsUrl rejects cleartext ws when allowInsecure is false`() {
        assertNull(GameClient.parseWsUrl("ws://munchking-sirpepo.duckdns.org:8765", allowInsecure = false))
        assertNull(GameClient.parseWsUrl("ws://10.0.2.2:8765/ws", allowInsecure = false))
        assertNull(GameClient.parseWsUrl("WS://localhost:8765", allowInsecure = false))
    }

    @Test
    fun `parseWsUrl allows wss when allowInsecure is false and ws when allowInsecure is true`() {
        assertEquals(
            Triple("munchking-sirpepo.duckdns.org", 8765, "/"),
            GameClient.parseWsUrl("wss://munchking-sirpepo.duckdns.org:8765", allowInsecure = false)
        )
        assertEquals(
            Triple("munchking-sirpepo.duckdns.org", 443, "/socket"),
            GameClient.parseWsUrl("  WSS://munchking-sirpepo.duckdns.org/socket  ", allowInsecure = false)
        )
        assertEquals(
            Triple("10.0.2.2", 8765, "/"),
            GameClient.parseWsUrl("ws://10.0.2.2:8765", allowInsecure = true)
        )
        assertEquals(
            Triple("localhost", 80, "/"),
            GameClient.parseWsUrl("ws://localhost", allowInsecure = true)
        )
    }

    @Test
    fun `parseWsUrl rejects invalid schemes ports and malformed URLs`() {
        assertNull(GameClient.parseWsUrl("", allowInsecure = true))
        assertNull(GameClient.parseWsUrl("https://munchking-sirpepo.duckdns.org:8765", allowInsecure = true))
        assertNull(GameClient.parseWsUrl("http://10.0.2.2:8765", allowInsecure = true))
        assertNull(GameClient.parseWsUrl("wss://example.com:0", allowInsecure = false))
        assertNull(GameClient.parseWsUrl("wss://example.com:65536", allowInsecure = false))
        assertNull(GameClient.parseWsUrl("wss://example.com/ws?token=abc", allowInsecure = false))
        assertNull(GameClient.parseWsUrl("wss://:8765", allowInsecure = false))
    }

    @Test
    fun `instance parseWsUrl matches companion policy`() {
        GameClient().use { client ->
            assertNull(client.parseWsUrl("ws://evil.example:8765", allowInsecure = false))
            assertEquals(
                Triple("munchking-sirpepo.duckdns.org", 8765, "/"),
                client.parseWsUrl("wss://munchking-sirpepo.duckdns.org:8765", allowInsecure = false)
            )
        }
    }

    @Test
    fun `initial state and disconnected operations fail cleanly without throwing`() = runBlocking {
        GameClient().use { client ->
            assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
            assertFalse(client.isConnected())
            assertNull(client.gameState.value)
            assertNull(client.myPlayerId.value)
            assertNull(client.currentPlayerId)
            assertNull(client.currentReconnectToken)
            assertEquals(0, client.reconnectAttempt.value)
            assertEquals(0L, client.serverTimeOffsetMs.value)
            assertEquals(0L, client.latencyMs.value)

            // Disconnected mutations return failure instead of throwing
            assertTrue(client.sendEvent(EndTurn("e1", PlayerId("p1"), 1L)).isFailure)
            assertTrue(client.kickPlayer(PlayerId("p2")).isFailure)
            assertTrue(client.sendTransferHost(PlayerId("p2")).isFailure)
            assertTrue(client.sendDeleteGame().isFailure)
            assertTrue(client.sendGameOver("g1", "p1").isFailure)
            assertTrue(client.sendSwapPlayers(PlayerId("p1"), PlayerId("p2")).isFailure)

            // Invalid server URL on one-off/auth RPCs returns failure cleanly
            assertTrue(client.listGames("https://invalid.example").isFailure)
            assertTrue(client.searchMonsters("http://invalid.example", "dragon").isFailure)
            assertTrue(client.getLeaderboard("not-a-url").isFailure)
            assertTrue(client.login("http://invalid.example", "a@b.c", "secret123").isFailure)
            assertTrue(client.getHistory("http://invalid.example", "u1", "tok").isFailure)
            assertTrue(client.getHostedGames("http://invalid.example", "tok").isFailure)
            assertTrue(client.deleteHostedGame("http://invalid.example", "tok", "g1").isFailure)

            // nudge & disconnect on idle client are safe no-ops
            client.nudge()
            client.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }

    @Test
    fun `close is idempotent and prevents subsequent connect`() = runBlocking {
        val client = GameClient()
        client.close()
        client.close()

        val result = client.connect(
            wsUrl = "wss://munchking-sirpepo.duckdns.org:8765",
            joinCode = "ABCD2345",
            playerMeta = PlayerMeta(PlayerId("p1"), "Hero", 0, Gender.M)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `ServerErrorException exposes error code and message`() {
        val ex = ServerErrorException(ErrorCode.GAME_FULL, "Room is full")
        assertEquals(ErrorCode.GAME_FULL, ex.code)
        assertEquals("Room is full", ex.message)
        assertEquals(GameId("g1").value, "g1")
    }
}
