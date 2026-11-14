package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for level validation rules in [GameEngine.reduce].
 */
class LevelValidationTest {

    private val p1Id = PlayerId("test")

    private fun gameWith(
        level: Int,
        settings: GameSettings = GameSettings(),
        reachedViaCombat: Boolean? = false
    ) = GameState(
        gameId = GameId("g1"),
        joinCode = "1234",
        hostId = p1Id,
        phase = GamePhase.IN_GAME,
        settings = settings,
        players = mapOf(
            p1Id to PlayerState(
                playerId = p1Id,
                name = "Test",
                level = level,
                reachedMaxLevelViaCombat = reachedViaCombat
            )
        )
    )

    @Test
    fun `level is clamped to minimum 1`() {
        val state = GameEngine.reduce(
            gameWith(level = 1),
            DecLevel("e1", p1Id, 1L, p1Id, amount = 1)
        )
        assertEquals(1, state.players[p1Id]!!.level)
    }

    @Test
    fun `manual level increment stops at maxLevel minus 1 when requiresCombatToWin`() {
        val state = GameEngine.reduce(
            gameWith(level = 8, settings = GameSettings(levelTenOnlyCombat = true)),
            IncLevel("e1", p1Id, 1L, p1Id, amount = 50)
        )
        assertEquals(9, state.players[p1Id]!!.level)
        assertEquals(false, state.players[p1Id]!!.reachedMaxLevelViaCombat)
    }

    @Test
    fun `manual SetLevel reaches maxLevel only when requiresCombatToWin is false`() {
        val restricted = GameEngine.reduce(
            gameWith(level = 5, settings = GameSettings(levelTenOnlyCombat = true)),
            SetLevel("e1", p1Id, 1L, p1Id, level = 10)
        )
        assertEquals(9, restricted.players[p1Id]!!.level)

        val unrestricted = GameEngine.reduce(
            gameWith(level = 5, settings = GameSettings(levelTenOnlyCombat = false)),
            SetLevel("e2", p1Id, 2L, p1Id, level = 15)
        )
        assertEquals(10, unrestricted.players[p1Id]!!.level)
    }

    @Test
    fun `dropping below maxLevel resets reachedMaxLevelViaCombat to false`() {
        val state = GameEngine.reduce(
            gameWith(level = 10, reachedViaCombat = true),
            DecLevel("e1", p1Id, 1L, p1Id, amount = 1)
        )
        assertEquals(9, state.players[p1Id]!!.level)
        assertEquals(false, state.players[p1Id]!!.reachedMaxLevelViaCombat)
    }

    @Test
    fun `level increments and decrements normally within range`() {
        val incState = GameEngine.reduce(
            gameWith(level = 5),
            IncLevel("e1", p1Id, 1L, p1Id, amount = 1)
        )
        assertEquals(6, incState.players[p1Id]!!.level)

        val decState = GameEngine.reduce(
            incState,
            DecLevel("e2", p1Id, 2L, p1Id, amount = 2)
        )
        assertEquals(4, decState.players[p1Id]!!.level)
    }
}
