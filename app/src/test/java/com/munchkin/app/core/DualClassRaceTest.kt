package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Super Munchkin grants the abilities of two Classes, Half-Breed of two Races.
 *
 * Before this, PlayerState held a single class and race, so a Warrior + Cleric
 * Super Munchkin got neither the tie-break nor the undead bonus from the second
 * card. The flags existed, were persisted, and changed nothing.
 */
class DualClassRaceTest {

    private fun player(
        id: String = "p1",
        level: Int = 1,
        gear: Int = 0,
        primaryClass: CharacterClass = CharacterClass.NONE,
        secondClass: CharacterClass = CharacterClass.NONE,
        primaryRace: CharacterRace = CharacterRace.HUMAN,
        secondRace: CharacterRace = CharacterRace.HUMAN,
        superMunchkin: Boolean = false,
        halfBreed: Boolean = false
    ) = PlayerState(
        playerId = PlayerId(id),
        name = "Player $id",
        level = level,
        gearBonus = gear,
        characterClass = primaryClass,
        secondaryClass = secondClass,
        characterRace = primaryRace,
        secondaryRace = secondRace,
        hasSuperMunchkin = superMunchkin,
        hasHalfBreed = halfBreed
    )

    private fun monster(level: Int, isUndead: Boolean = false) = MonsterInstance(
        id = "m1", name = "Monstruo", baseLevel = level, isUndead = isUndead
    )

    private fun gameOf(vararg players: PlayerState) = GameState(
        gameId = GameId("g1"),
        joinCode = "TESTCODE",
        hostId = players.first().playerId,
        players = players.associateBy { it.playerId }
    )

    // ─────────── activeClasses / activeRaces ───────────

    @Test
    fun `a second class only counts with Super Munchkin`() {
        val without = player(
            primaryClass = CharacterClass.WARRIOR,
            secondClass = CharacterClass.CLERIC,
            superMunchkin = false
        )
        assertEquals(listOf(CharacterClass.WARRIOR), without.activeClasses)
        assertFalse("the second class must be inert without the card", without.hasClass(CharacterClass.CLERIC))

        val with = without.copy(hasSuperMunchkin = true)
        assertEquals(listOf(CharacterClass.WARRIOR, CharacterClass.CLERIC), with.activeClasses)
        assertTrue(with.hasClass(CharacterClass.CLERIC))
        assertTrue(with.hasClass(CharacterClass.WARRIOR))
    }

    @Test
    fun `a second race only counts with Half-Breed`() {
        val without = player(
            primaryRace = CharacterRace.DWARF,
            secondRace = CharacterRace.ELF,
            halfBreed = false
        )
        assertEquals(listOf(CharacterRace.DWARF), without.activeRaces)
        assertFalse(without.hasRace(CharacterRace.ELF))

        val with = without.copy(hasHalfBreed = true)
        assertTrue(with.hasRace(CharacterRace.ELF))
        assertTrue(with.hasRace(CharacterRace.DWARF))
    }

    @Test
    fun `NONE and HUMAN are treated as empty slots`() {
        // A Human is the absence of a Race card and grants nothing, so it should not
        // appear as an active race.
        val bare = player()
        assertTrue(bare.activeClasses.isEmpty())
        assertTrue(bare.activeRaces.isEmpty())
        assertFalse(bare.hasRace(CharacterRace.HUMAN))
    }

    @Test
    fun `a duplicated card is not counted twice`() {
        val doubled = player(
            primaryClass = CharacterClass.CLERIC,
            secondClass = CharacterClass.CLERIC,
            superMunchkin = true
        )
        assertEquals(1, doubled.activeClasses.size)
    }

    // ─────────── abilities honour both slots ───────────

    @Test
    fun `a Super Munchkin Warrior-Cleric wins ties`() {
        // The tie-break comes from the second slot here.
        val main = player(
            level = 5,
            primaryClass = CharacterClass.CLERIC,
            secondClass = CharacterClass.WARRIOR,
            superMunchkin = true
        )
        val state = gameOf(main)
        val combat = CombatState(mainPlayerId = main.playerId, monsters = listOf(monster(5)))

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertTrue(result.isWarriorInvolved)
    }

    @Test
    fun `the same player without Super Munchkin loses the tie`() {
        val main = player(
            level = 5,
            primaryClass = CharacterClass.CLERIC,
            secondClass = CharacterClass.WARRIOR,
            superMunchkin = false
        )
        val state = gameOf(main)
        val combat = CombatState(mainPlayerId = main.playerId, monsters = listOf(monster(5)))

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(CombatOutcome.LOSE, result.outcome)
        assertFalse(result.isWarriorInvolved)
    }

    @Test
    fun `a Super Munchkin gets the Cleric bonus from the second slot`() {
        val main = player(
            level = 3,
            primaryClass = CharacterClass.WARRIOR,
            secondClass = CharacterClass.CLERIC,
            superMunchkin = true
        )
        val state = gameOf(main)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            monsters = listOf(monster(5, isUndead = true))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals("3 base + 3 cleric", 6, result.heroesPower)
    }

    @Test
    fun `a Half-Breed helper gains the Elf level from the second slot`() {
        val main = player(id = "p1", level = 9, gear = 5)
        val helper = player(
            id = "p2",
            level = 2,
            primaryRace = CharacterRace.DWARF,
            secondRace = CharacterRace.ELF,
            halfBreed = true
        )
        val state = gameOf(main, helper)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            helperPlayerId = helper.playerId,
            monsters = listOf(monster(3))
        )

        val result = CombatCalculator.calculateResult(combat, state)

        assertEquals(CombatOutcome.WIN, result.outcome)
        assertEquals(1, result.helperLevelsGained)
    }

    @Test
    fun `the breakdown still adds up with a second-slot Cleric`() {
        val main = player(
            level = 4,
            primaryClass = CharacterClass.THIEF,
            secondClass = CharacterClass.CLERIC,
            superMunchkin = true
        )
        val state = gameOf(main)
        val combat = CombatState(
            mainPlayerId = main.playerId,
            monsters = listOf(monster(6, isUndead = true)),
            heroModifier = 2
        )

        val breakdown = CombatCalculator.getBreakdown(combat, state)

        assertEquals(breakdown.result.heroesPower, breakdown.totalHeroPower)
        assertEquals("4 base + 3 cleric + 2 modifier", 9, breakdown.totalHeroPower)
    }

    // ─────────── ability reminders ───────────

    @Test
    fun `reminders cover both slots and mark what the app applies`() {
        val sm = player(
            primaryClass = CharacterClass.WARRIOR,
            secondClass = CharacterClass.WIZARD,
            superMunchkin = true
        )
        val abilities = Abilities.forPlayer(sm)

        assertTrue(
            "must include a Warrior ability",
            abilities.any { it.source == "Guerrero" }
        )
        assertTrue(
            "must include a Wizard ability from the second slot",
            abilities.any { it.source == "Mago" }
        )
        // The tie-break is applied by the app; Berserking and the spells are not.
        assertTrue(abilities.any { it.kind == AbilityKind.AUTOMATIC })
        assertTrue(abilities.any { it.kind == AbilityKind.MANUAL })
    }

    @Test
    fun `a player with no class or race has no reminders`() {
        assertTrue(Abilities.forPlayer(player()).isEmpty())
    }

    @Test
    fun `an Elf gets a run-away bonus and others do not`() {
        val elf = player(primaryRace = CharacterRace.ELF)
        val dwarf = player(primaryRace = CharacterRace.DWARF)
        val halfBreedElf = player(
            primaryRace = CharacterRace.DWARF,
            secondRace = CharacterRace.ELF,
            halfBreed = true
        )

        assertEquals(1, Abilities.runAwayBonus(elf))
        assertEquals(0, Abilities.runAwayBonus(dwarf))
        assertEquals("Half-Breed Dwarf/Elf still runs like an Elf", 1, Abilities.runAwayBonus(halfBreedElf))
    }

    @Test
    fun `every class and race has at least one documented ability except the empty slots`() {
        // Guards against adding an enum case and forgetting the reminder text.
        CharacterClass.entries.forEach { cls ->
            if (cls == CharacterClass.NONE) {
                assertTrue(Abilities.forClass(cls).isEmpty())
            } else {
                assertTrue("$cls has no documented ability", Abilities.forClass(cls).isNotEmpty())
            }
        }
        CharacterRace.entries.forEach { race ->
            if (race == CharacterRace.HUMAN) {
                assertTrue(Abilities.forRace(race).isEmpty())
            } else {
                assertTrue("$race has no documented ability", Abilities.forRace(race).isNotEmpty())
            }
        }
    }
}
