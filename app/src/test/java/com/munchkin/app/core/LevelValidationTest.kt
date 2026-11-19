package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for level, gear, and modifier validation rules in [GameEngine.reduce].
 */
class LevelValidationTest {

    private val p1Id = PlayerId("test")

    private fun gameWith(
        level: Int,
        gear: Int = 0,
        settings: GameSettings = GameSettings(),
        reachedViaCombat: Boolean? = false,
        combat: CombatState? = null
    ) = GameState(
        gameId = GameId("g1"),
        joinCode = "1234",
        hostId = p1Id,
        phase = GamePhase.IN_GAME,
        settings = settings,
        combat = combat,
        players = mapOf(
            p1Id to PlayerState(
                playerId = p1Id,
                name = "Test",
                level = level,
                gearBonus = gear,
                reachedMaxLevelViaCombat = reachedViaCombat
            )
        )
    )

    @Test
    fun `level is clamped to minimum 1 on DecLevel and SetLevel`() {
        val afterDec = GameEngine.reduce(
            gameWith(level = 1),
            DecLevel("e1", p1Id, 1L, p1Id, amount = 5)
        )
        assertEquals(1, afterDec.players[p1Id]!!.level)

        val afterSet = GameEngine.reduce(
            gameWith(level = 4),
            SetLevel("e2", p1Id, 2L, p1Id, level = -10)
        )
        assertEquals(1, afterSet.players[p1Id]!!.level)
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
    fun `epic mode maxLevel 20 clamps manual level at 19 when requiresCombatToWin`() {
        val epicSettings = GameSettings(maxLevel = 20, levelTenOnlyCombat = true)
        val afterInc = GameEngine.reduce(
            gameWith(level = 17, settings = epicSettings),
            IncLevel("e1", p1Id, 1L, p1Id, amount = 10)
        )
        assertEquals(19, afterInc.players[p1Id]!!.level)
        assertEquals(false, afterInc.players[p1Id]!!.reachedMaxLevelViaCombat)

        val afterSet = GameEngine.reduce(
            gameWith(level = 15, settings = epicSettings),
            SetLevel("e2", p1Id, 2L, p1Id, level = 25)
        )
        assertEquals(19, afterSet.players[p1Id]!!.level)
    }

    @Test
    fun `manual SetLevel reaches maxLevel only when requiresCombatToWin is false`() {
        val restricted = GameEngine.reduce(
            gameWith(level = 5, settings = GameSettings(levelTenOnlyCombat = true)),
            SetLevel("e1", p1Id, 1L, p1Id, level = 10)
        )
        assertEquals(9, restricted.players[p1Id]!!.level)
        assertEquals(false, restricted.players[p1Id]!!.reachedMaxLevelViaCombat)

        val unrestricted = GameEngine.reduce(
            gameWith(level = 5, settings = GameSettings(levelTenOnlyCombat = false)),
            SetLevel("e2", p1Id, 2L, p1Id, level = 15)
        )
        assertEquals(10, unrestricted.players[p1Id]!!.level)
        assertEquals(false, unrestricted.players[p1Id]!!.reachedMaxLevelViaCombat)

        val overridden = GameEngine.reduce(
            gameWith(
                level = 8,
                settings = GameSettings(levelTenOnlyCombat = true, allowLevelTenOverride = true)
            ),
            IncLevel("e3", p1Id, 3L, p1Id, amount = 3)
        )
        assertEquals(10, overridden.players[p1Id]!!.level)
    }

    @Test
    fun `dropping below maxLevel resets reachedMaxLevelViaCombat to false for DecLevel and SetLevel`() {
        val afterDec = GameEngine.reduce(
            gameWith(level = 10, reachedViaCombat = true),
            DecLevel("e1", p1Id, 1L, p1Id, amount = 1)
        )
        assertEquals(9, afterDec.players[p1Id]!!.level)
        assertEquals(false, afterDec.players[p1Id]!!.reachedMaxLevelViaCombat)

        val afterSet = GameEngine.reduce(
            gameWith(level = 10, reachedViaCombat = true),
            SetLevel("e2", p1Id, 2L, p1Id, level = 7)
        )
        assertEquals(7, afterSet.players[p1Id]!!.level)
        assertEquals(false, afterSet.players[p1Id]!!.reachedMaxLevelViaCombat)
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

    @Test
    fun `gear and combat modifiers enforce GameEngine bounds`() {
        val decNegative = GameEngine.reduce(
            gameWith(level = 3, gear = 2),
            DecGear("e1", p1Id, 1L, p1Id, amount = 20)
        )
        assertEquals(-18, decNegative.players[p1Id]!!.gearBonus)

        val decClampedFloor = GameEngine.reduce(
            gameWith(level = 3, gear = -990),
            DecGear("e1b", p1Id, 1L, p1Id, amount = 50)
        )
        assertEquals(GameEngine.GEAR_MIN, decClampedFloor.players[p1Id]!!.gearBonus)

        val incStepCapped = GameEngine.reduce(
            gameWith(level = 3, gear = 5),
            IncGear("e2", p1Id, 2L, p1Id, amount = 999)
        )
        assertEquals(5 + GameEngine.STEP_LIMIT, incStepCapped.players[p1Id]!!.gearBonus)

        val nonPositiveStepDefaultsToOne = GameEngine.reduce(
            gameWith(level = 3, gear = 5),
            IncGear("e3", p1Id, 3L, p1Id, amount = 0)
        )
        assertEquals(6, nonPositiveStepDefaultsToOne.players[p1Id]!!.gearBonus)

        val setGearClamped = GameEngine.reduce(
            gameWith(level = 3, gear = 5),
            SetGear("e4", p1Id, 4L, p1Id, gear = 5000)
        )
        assertEquals(GameEngine.GEAR_MAX, setGearClamped.players[p1Id]!!.gearBonus)

        val combatState = gameWith(
            level = 3,
            combat = CombatState(mainPlayerId = p1Id)
        )
        val afterHeroMod = GameEngine.reduce(
            combatState,
            CombatSetModifier("e5", p1Id, 5L, target = BonusTarget.HEROES, value = 50_000)
        )
        assertEquals(GameEngine.MODIFIER_LIMIT, afterHeroMod.combat!!.heroModifier)

        val afterMonsterMod = GameEngine.reduce(
            afterHeroMod,
            CombatSetModifier("e6", p1Id, 6L, target = BonusTarget.MONSTER, value = -50_000)
        )
        assertEquals(-GameEngine.MODIFIER_LIMIT, afterMonsterMod.combat!!.monsterModifier)
    }
}
