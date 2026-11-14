package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for enum-based race/class trait validation and reducer rules.
 */
class TraitValidationTest {

    private val p1Id = PlayerId("test")

    private fun gameWith(player: PlayerState) = GameState(
        gameId = GameId("g1"),
        joinCode = "ABCD",
        hostId = p1Id,
        players = mapOf(p1Id to player)
    )

    @Test
    fun `player without half-breed has max 1 active race`() {
        val player = PlayerState(
            playerId = p1Id,
            name = "Test",
            characterRace = CharacterRace.ELF,
            secondaryRace = CharacterRace.DWARF,
            hasHalfBreed = false
        )

        assertEquals(1, player.maxRaces)
        assertEquals(listOf(CharacterRace.ELF), player.activeRaces)
        assertTrue(player.hasRace(CharacterRace.ELF))
        assertFalse(player.hasRace(CharacterRace.DWARF))
    }

    @Test
    fun `player with half-breed can have 2 distinct active races`() {
        val player = PlayerState(
            playerId = p1Id,
            name = "Test",
            characterRace = CharacterRace.ELF,
            secondaryRace = CharacterRace.DWARF,
            hasHalfBreed = true
        )

        assertEquals(2, player.maxRaces)
        assertEquals(listOf(CharacterRace.ELF, CharacterRace.DWARF), player.activeRaces)
        assertTrue(player.hasRace(CharacterRace.ELF))
        assertTrue(player.hasRace(CharacterRace.DWARF))
    }

    @Test
    fun `player without super munchkin has max 1 active class`() {
        val player = PlayerState(
            playerId = p1Id,
            name = "Test",
            characterClass = CharacterClass.WARRIOR,
            secondaryClass = CharacterClass.WIZARD,
            hasSuperMunchkin = false
        )

        assertEquals(1, player.maxClasses)
        assertEquals(listOf(CharacterClass.WARRIOR), player.activeClasses)
        assertTrue(player.hasClass(CharacterClass.WARRIOR))
        assertFalse(player.hasClass(CharacterClass.WIZARD))
    }

    @Test
    fun `player with super munchkin can have 2 distinct active classes`() {
        val player = PlayerState(
            playerId = p1Id,
            name = "Test",
            characterClass = CharacterClass.WARRIOR,
            secondaryClass = CharacterClass.WIZARD,
            hasSuperMunchkin = true
        )

        assertEquals(2, player.maxClasses)
        assertEquals(listOf(CharacterClass.WARRIOR, CharacterClass.WIZARD), player.activeClasses)
        assertTrue(player.hasClass(CharacterClass.WARRIOR))
        assertTrue(player.hasClass(CharacterClass.WIZARD))
    }

    @Test
    fun `reducer ignores secondary mutations when card flags are disabled`() {
        val initial = gameWith(
            PlayerState(
                playerId = p1Id,
                name = "Test",
                characterClass = CharacterClass.WARRIOR,
                characterRace = CharacterRace.ELF,
                hasSuperMunchkin = false,
                hasHalfBreed = false
            )
        )

        val afterClass = GameEngine.reduce(
            initial,
            SetClass("e1", p1Id, 1L, p1Id, CharacterClass.WIZARD, isSecondary = true)
        )
        assertEquals(CharacterClass.NONE, afterClass.players[p1Id]!!.secondaryClass)

        val afterRace = GameEngine.reduce(
            afterClass,
            SetRace("e2", p1Id, 2L, p1Id, CharacterRace.DWARF, isSecondary = true)
        )
        assertEquals(CharacterRace.HUMAN, afterRace.players[p1Id]!!.secondaryRace)
    }

    @Test
    fun `reducer clears duplicate or disabled secondary class and race`() {
        var state = gameWith(
            PlayerState(
                playerId = p1Id,
                name = "Test",
                characterClass = CharacterClass.WARRIOR,
                characterRace = CharacterRace.ELF,
                hasSuperMunchkin = true,
                hasHalfBreed = true
            )
        )

        // Setting secondary equal to primary clears secondary to NONE/HUMAN
        state = GameEngine.reduce(
            state,
            SetClass("e1", p1Id, 1L, p1Id, CharacterClass.WARRIOR, isSecondary = true)
        )
        assertEquals(CharacterClass.NONE, state.players[p1Id]!!.secondaryClass)

        state = GameEngine.reduce(
            state,
            SetRace("e2", p1Id, 2L, p1Id, CharacterRace.ELF, isSecondary = true)
        )
        assertEquals(CharacterRace.HUMAN, state.players[p1Id]!!.secondaryRace)

        // Setting distinct secondary works, then setting primary to match secondary clears secondary
        state = GameEngine.reduce(
            state,
            SetClass("e3", p1Id, 3L, p1Id, CharacterClass.CLERIC, isSecondary = true)
        )
        state = GameEngine.reduce(
            state,
            SetRace("e4", p1Id, 4L, p1Id, CharacterRace.DWARF, isSecondary = true)
        )
        assertEquals(CharacterClass.CLERIC, state.players[p1Id]!!.secondaryClass)
        assertEquals(CharacterRace.DWARF, state.players[p1Id]!!.secondaryRace)

        val afterPrimaryOverlap = GameEngine.reduce(
            GameEngine.reduce(
                state,
                SetClass("e4b", p1Id, 4L, p1Id, CharacterClass.CLERIC, isSecondary = false)
            ),
            SetRace("e4c", p1Id, 4L, p1Id, CharacterRace.DWARF, isSecondary = false)
        )
        assertEquals(CharacterClass.NONE, afterPrimaryOverlap.players[p1Id]!!.secondaryClass)
        assertEquals(CharacterRace.HUMAN, afterPrimaryOverlap.players[p1Id]!!.secondaryRace)

        // Disabling flags clears secondary slots while keeping primary slots
        state = GameEngine.reduce(state, SetSuperMunchkin("e5", p1Id, 5L, p1Id, enabled = false))
        state = GameEngine.reduce(state, SetHalfBreed("e6", p1Id, 6L, p1Id, enabled = false))
        assertEquals(CharacterClass.WARRIOR, state.players[p1Id]!!.characterClass)
        assertEquals(CharacterClass.NONE, state.players[p1Id]!!.secondaryClass)
        assertEquals(CharacterRace.ELF, state.players[p1Id]!!.characterRace)
        assertEquals(CharacterRace.HUMAN, state.players[p1Id]!!.secondaryRace)
    }
}
