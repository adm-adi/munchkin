package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the combat breakdown shown in the UI.
 *
 * The breakdown is built independently of the result calculation, so the two can
 * silently disagree. Every test here asserts they stay consistent.
 */
class CombatBreakdownTest {

    private fun player(
        id: String,
        level: Int,
        gear: Int,
        characterClass: CharacterClass = CharacterClass.NONE,
        characterRace: CharacterRace = CharacterRace.HUMAN
    ) = PlayerState(
        playerId = PlayerId(id),
        name = "Player $id",
        level = level,
        gearBonus = gear,
        characterClass = characterClass,
        characterRace = characterRace
    )

    private fun monster(
        id: String = "m1",
        level: Int,
        modifier: Int = 0,
        isUndead: Boolean = false
    ) = MonsterInstance(
        id = id,
        name = "Monster $id",
        baseLevel = level,
        flatModifier = modifier,
        isUndead = isUndead
    )

    private fun gameOf(vararg players: PlayerState) = GameState(
        gameId = GameId("g1"),
        joinCode = "TESTCODE",
        hostId = players.first().playerId,
        players = players.associateBy { it.playerId }
    )

    /**
     * calculateResult() folds heroModifier into heroesPower. getBreakdown() used to
     * omit it entirely, so the itemised list did not add up to the total the UI
     * displayed beside it.
     */
    @Test
    fun `breakdown includes manual hero modifier`() {
        val p1 = player("p1", level = 5, gear = 2)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = p1.playerId,
            monsters = listOf(monster(level = 4)),
            heroModifier = 7
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(
            "hero sources must sum to the reported heroesPower",
            breakdown.result.heroesPower,
            breakdown.totalHeroPower
        )
        assertEquals(14, breakdown.totalHeroPower) // 5 + 2 + 7
    }

    @Test
    fun `breakdown includes manual monster modifier`() {
        val p1 = player("p1", level = 5, gear = 0)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = p1.playerId,
            monsters = listOf(monster(level = 3)),
            monsterModifier = 6
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(
            "monster sources must sum to the reported monstersPower",
            breakdown.result.monstersPower,
            breakdown.totalMonsterPower
        )
        assertEquals(9, breakdown.totalMonsterPower) // 3 + 6
    }

    /**
     * The modifier cap was lifted in v2.19.18, so the breakdown has to stay
     * consistent at values well beyond the old ±20 bound.
     */
    @Test
    fun `breakdown stays consistent with modifiers beyond the old cap`() {
        val p1 = player("p1", level = 1, gear = 0)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = p1.playerId,
            monsters = listOf(monster(level = 1)),
            heroModifier = 250,
            monsterModifier = -75
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(breakdown.result.heroesPower, breakdown.totalHeroPower)
        assertEquals(breakdown.result.monstersPower, breakdown.totalMonsterPower)
        assertEquals(251, breakdown.totalHeroPower)
        assertEquals(-74, breakdown.totalMonsterPower)
    }

    @Test
    fun `breakdown accounts for temp bonuses on both sides`() {
        val p1 = player("p1", level = 4, gear = 1)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = p1.playerId,
            monsters = listOf(monster(level = 5)),
            tempBonuses = listOf(
                TempBonus(id = "b1", label = "Poción", amount = 4, appliesTo = BonusTarget.HEROES),
                TempBonus(id = "b2", label = "Refuerzo", amount = 2, appliesTo = BonusTarget.MONSTER)
            )
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(breakdown.result.heroesPower, breakdown.totalHeroPower)
        assertEquals(breakdown.result.monstersPower, breakdown.totalMonsterPower)
    }

    /**
     * A Cleric contributes +3 against undead. The breakdown lists it separately, so
     * it must not drift from the calculated total either.
     */
    @Test
    fun `breakdown consistent with cleric bonus against undead`() {
        val p1 = player("p1", level = 3, gear = 0, characterClass = CharacterClass.CLERIC)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = p1.playerId,
            monsters = listOf(monster(level = 5, isUndead = true))
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(breakdown.result.heroesPower, breakdown.totalHeroPower)
        assertEquals(6, breakdown.totalHeroPower) // 3 + 3 cleric
    }

    /**
     * The client grants a tie to the heroes when *either* participant is a Warrior.
     * The server used to check only the main player, which made it overrule a win
     * the client had already shown; this pins the client half of that contract.
     */
    @Test
    fun `warrior helper wins ties`() {
        val main = player("p1", level = 3, gear = 0)
        val helper = player("p2", level = 2, gear = 0, characterClass = CharacterClass.WARRIOR)
        val state = gameOf(main, helper)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            helperPlayerId = helper.playerId,
            monsters = listOf(monster(level = 5))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertTrue(result.isWarriorInvolved)
        assertTrue(result.warriorTieBreak)
    }

    @Test
    fun `tie without a warrior goes to the monsters`() {
        val main = player("p1", level = 5, gear = 0)
        val state = gameOf(main)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            monsters = listOf(monster(level = 5))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(CombatOutcome.LOSE, result.outcome)
        assertFalse(result.isWarriorInvolved)
    }

    /** An Elf helper earns a level when the party wins. */
    @Test
    fun `elf helper gains a level on a win`() {
        val main = player("p1", level = 9, gear = 5)
        val helper = player("p2", level = 2, gear = 0, characterRace = CharacterRace.ELF)
        val state = gameOf(main, helper)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            helperPlayerId = helper.playerId,
            monsters = listOf(monster(level = 3))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(CombatOutcome.WIN, result.outcome)
        assertEquals(1, result.helperLevelsGained)
    }

    @Test
    fun `non elf helper gains nothing on a win`() {
        val main = player("p1", level = 9, gear = 5)
        val helper = player("p2", level = 2, gear = 0, characterRace = CharacterRace.DWARF)
        val state = gameOf(main, helper)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            helperPlayerId = helper.playerId,
            monsters = listOf(monster(level = 3))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(CombatOutcome.WIN, result.outcome)
        assertEquals(0, result.helperLevelsGained)
    }

    /** A combat whose main player is not in the room must not crash the calculator. */
    @Test
    fun `unknown main player yields an empty breakdown`() {
        val p1 = player("p1", level = 5, gear = 0)
        val state = gameOf(p1)
        val combat = CombatState(
            mainPlayerId = PlayerId("ghost"),
            monsters = listOf(monster(level = 3))
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertTrue(breakdown.heroSources.isEmpty())
        assertTrue(breakdown.monsterSources.isEmpty())
    }
}
