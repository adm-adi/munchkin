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
}
