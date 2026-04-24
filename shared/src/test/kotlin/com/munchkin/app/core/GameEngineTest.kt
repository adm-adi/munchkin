package com.munchkin.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {
    @Test
    fun `game end stores confirmed winner`() {
        val hostId = PlayerId("host-player")
        val engine = GameEngine()
        engine.createGame(
            PlayerMeta(
                playerId = hostId,
                name = "Host",
                avatarId = 1,
                gender = Gender.NA
            )
        )

        engine.processEvent(
            GameEnd(
                eventId = "game-end",
                actorId = hostId,
                timestamp = 123L,
                winnerId = hostId
            )
        )

        val state = requireNotNull(engine.gameState.value)
        assertEquals(GamePhase.FINISHED, state.phase)
        assertEquals(hostId, state.winnerId)
    }

    @Test
    fun `combat modifiers are not capped by shared engine`() {
        val hostId = PlayerId("host-player")
        val engine = GameEngine()
        engine.createGame(
            PlayerMeta(
                playerId = hostId,
                name = "Host",
                avatarId = 1,
                gender = Gender.NA
            )
        )

        engine.processEvent(
            CombatStart(
                eventId = "combat-start",
                actorId = hostId,
                timestamp = 123L,
                mainPlayerId = hostId
            )
        )
        engine.processEvent(
            CombatSetModifier(
                eventId = "combat-modifier",
                actorId = hostId,
                timestamp = 124L,
                target = BonusTarget.HEROES,
                value = 250
            )
        )

        val state = requireNotNull(engine.gameState.value)
        assertEquals(250, state.combat?.heroModifier)
    }

    @Test
    fun `lobby tie breaker keeps rolls visible and winner comes from tied players`() {
        val hostId = PlayerId("host-player")
        val tiedGuestId = PlayerId("tied-guest")
        val eliminatedGuestId = PlayerId("eliminated-guest")
        val engine = GameEngine()
        engine.createGame(meta(hostId, "Host"))
        engine.processEvent(join(tiedGuestId, "Tie"))
        engine.processEvent(join(eliminatedGuestId, "Out"))

        engine.processEvent(roll(hostId, 6))
        engine.processEvent(roll(tiedGuestId, 6))
        engine.processEvent(roll(eliminatedGuestId, 5))

        var state = requireNotNull(engine.gameState.value)
        assertEquals(6, state.players[hostId]?.lastRoll)
        assertEquals(6, state.players[tiedGuestId]?.lastRoll)
        assertEquals(5, state.players[eliminatedGuestId]?.lastRoll)
        assertTrue(state.hasRollTie)
        assertFalse(state.canStart)
        assertTrue(state.needsLobbyRoll(hostId))
        assertTrue(state.needsLobbyRoll(tiedGuestId))
        assertFalse(state.needsLobbyRoll(eliminatedGuestId))

        engine.processEvent(roll(hostId, 2))
        state = requireNotNull(engine.gameState.value)
        assertEquals(2, state.players[hostId]?.lastRoll)
        assertTrue(state.hasRollTie)
        assertFalse(state.needsLobbyRoll(hostId))
        assertTrue(state.needsLobbyRoll(tiedGuestId))

        val duplicateRoll = engine.processEvent(roll(hostId, 4))
        assertTrue(duplicateRoll is ValidationResult.Error)
        assertEquals(2, requireNotNull(engine.gameState.value).players[hostId]?.lastRoll)

        engine.processEvent(roll(tiedGuestId, 1))
        state = requireNotNull(engine.gameState.value)
        assertFalse(state.hasRollTie)
        assertTrue(state.canStart)
        assertEquals(hostId, state.lobbyRollWinnerId)

        engine.processEvent(
            GameStart(
                eventId = "start",
                actorId = hostId,
                timestamp = 10L
            )
        )
        state = requireNotNull(engine.gameState.value)
        assertEquals(GamePhase.IN_GAME, state.phase)
        assertEquals(hostId, state.turnPlayerId)
    }

    private fun meta(playerId: PlayerId, name: String): PlayerMeta {
        return PlayerMeta(
            playerId = playerId,
            name = name,
            avatarId = 1,
            gender = Gender.NA
        )
    }

    private fun join(playerId: PlayerId, name: String): PlayerJoin {
        return PlayerJoin(
            eventId = "join-${playerId.value}",
            actorId = playerId,
            timestamp = 1L,
            playerMeta = meta(playerId, name),
            lastKnownIp = null
        )
    }

    private fun roll(playerId: PlayerId, result: Int): PlayerRoll {
        return PlayerRoll(
            eventId = "roll-${playerId.value}-$result",
            actorId = playerId,
            timestamp = result.toLong(),
            targetPlayerId = playerId,
            result = result
        )
    }
}
