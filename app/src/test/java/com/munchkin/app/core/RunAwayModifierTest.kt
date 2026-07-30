package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Race modifiers to the run-away roll.
 *
 * Only the Elf's +1 was ever counted. The Halfling's -1 was missing entirely — the
 * ability slot held the Dwarf's hand-size clause instead, so a Halfling rolled
 * unmodified and the dialog labelled any modifier as coming from an Elf.
 */
class RunAwayModifierTest {

    private fun player(
        race: CharacterRace = CharacterRace.HUMAN,
        secondRace: CharacterRace = CharacterRace.HUMAN,
        halfBreed: Boolean = false
    ) = PlayerState(
        playerId = PlayerId("p1"),
        name = "Player",
        characterRace = race,
        secondaryRace = secondRace,
        hasHalfBreed = halfBreed
    )

    @Test
    fun `an elf gets plus one`() {
        assertEquals(1, Abilities.runAwayBonus(player(race = CharacterRace.ELF)))
    }

    @Test
    fun `a halfling gets minus one`() {
        assertEquals(-1, Abilities.runAwayBonus(player(race = CharacterRace.HALFLING)))
    }

    @Test
    fun `races without a run-away clause do not modify the roll`() {
        assertEquals(0, Abilities.runAwayBonus(player()))
        assertEquals(0, Abilities.runAwayBonus(player(race = CharacterRace.DWARF)))
    }

    @Test
    fun `a half-breed elf halfling cancels out`() {
        val both = player(
            race = CharacterRace.ELF,
            secondRace = CharacterRace.HALFLING,
            halfBreed = true
        )
        assertEquals(0, Abilities.runAwayBonus(both))
        assertEquals(
            "both sources must still be reported so the total can be explained",
            2,
            Abilities.runAwayModifiers(both).size
        )
    }

    @Test
    fun `the second race slot is inert without the half-breed card`() {
        val noCard = player(race = CharacterRace.ELF, secondRace = CharacterRace.HALFLING)
        assertEquals(1, Abilities.runAwayBonus(noCard))
    }

    @Test
    fun `the halfling ability list carries the run-away penalty, not a hand size`() {
        val abilities = Abilities.forRace(CharacterRace.HALFLING)
        assertTrue(
            "the run-away penalty must be listed",
            abilities.any { it.descriptionRes == com.munchkin.app.R.string.ability_halfling_run_away }
        )
        assertTrue(
            "the app applies it to the roll, so it is automatic",
            abilities.any {
                it.descriptionRes == com.munchkin.app.R.string.ability_halfling_run_away &&
                    it.kind == AbilityKind.AUTOMATIC
            }
        )
    }
}
