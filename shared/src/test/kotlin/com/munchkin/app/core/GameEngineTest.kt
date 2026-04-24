package com.munchkin.app.core

import org.junit.Assert.assertEquals
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
}
