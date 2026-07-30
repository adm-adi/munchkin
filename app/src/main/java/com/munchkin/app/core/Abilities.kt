package com.munchkin.app.core

import androidx.annotation.StringRes
import com.munchkin.app.R

/**
 * Reminders of what a player's class and race actually do in combat.
 *
 * Most Munchkin abilities are driven by discarding cards or by what is in a
 * player's inventory, and this app tracks neither — it tracks level, a single gear
 * total and a treasure count. So the abilities fall into two groups, and the
 * distinction is the whole point of showing them:
 *
 *  - [AbilityKind.AUTOMATIC]: the app already applies it. The player does nothing.
 *  - [AbilityKind.MANUAL]: the rules grant it, but the app cannot know when it
 *    applies. The player resolves it at the table and enters the effect with the
 *    combat modifier.
 *
 * Keeping both in one list stops the app from silently looking like it handles
 * everything, which is what makes a half-automated tracker untrustworthy.
 */
enum class AbilityKind { AUTOMATIC, MANUAL }

data class Ability(
    /** Short label naming the card the ability comes from, e.g. the Warrior class. */
    @StringRes val sourceRes: Int,
    @StringRes val descriptionRes: Int,
    val kind: AbilityKind
)

object Abilities {

    fun forClass(characterClass: CharacterClass): List<Ability> = when (characterClass) {
        CharacterClass.NONE -> emptyList()

        CharacterClass.WARRIOR -> listOf(
            Ability(
                sourceRes = R.string.class_warrior,
                descriptionRes = R.string.ability_warrior_ties,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceRes = R.string.class_warrior,
                descriptionRes = R.string.ability_warrior_berserk,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.WIZARD -> listOf(
            Ability(
                sourceRes = R.string.class_wizard,
                descriptionRes = R.string.ability_wizard_charm,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceRes = R.string.class_wizard,
                descriptionRes = R.string.ability_wizard_flight,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.THIEF -> listOf(
            Ability(
                sourceRes = R.string.class_thief,
                descriptionRes = R.string.ability_thief_backstab,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceRes = R.string.class_thief,
                descriptionRes = R.string.ability_thief_steal,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.CLERIC -> listOf(
            Ability(
                sourceRes = R.string.class_cleric,
                descriptionRes = R.string.ability_cleric_undead,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceRes = R.string.class_cleric,
                descriptionRes = R.string.ability_cleric_resurrect,
                kind = AbilityKind.MANUAL
            )
        )
    }

    fun forRace(characterRace: CharacterRace): List<Ability> = when (characterRace) {
        // A Human is the absence of a Race card, and grants nothing.
        CharacterRace.HUMAN -> emptyList()

        CharacterRace.ELF -> listOf(
            Ability(
                sourceRes = R.string.race_elf,
                descriptionRes = R.string.ability_elf_help_level,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceRes = R.string.race_elf,
                descriptionRes = R.string.ability_elf_run_away,
                kind = AbilityKind.AUTOMATIC
            )
        )

        CharacterRace.DWARF -> listOf(
            Ability(
                sourceRes = R.string.race_dwarf,
                descriptionRes = R.string.ability_dwarf_big_items,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceRes = R.string.race_dwarf,
                descriptionRes = R.string.ability_dwarf_hand_size,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterRace.HALFLING -> listOf(
            Ability(
                sourceRes = R.string.race_halfling,
                descriptionRes = R.string.ability_halfling_sell,
                kind = AbilityKind.MANUAL
            ),
            // A penalty, not a bonus — and the app applies it to the roll, so it
            // is automatic like the Elf's. This slot previously held the Dwarf's
            // hand-size clause, which the Halfling does not have.
            Ability(
                sourceRes = R.string.race_halfling,
                descriptionRes = R.string.ability_halfling_run_away,
                kind = AbilityKind.AUTOMATIC
            )
        )
    }

    /**
     * Every ability currently in play for a player, across both class and race
     * slots. Super Munchkin and Half-Breed are respected via
     * [PlayerState.activeClasses] / [PlayerState.activeRaces].
     */
    fun forPlayer(player: PlayerState): List<Ability> = buildList {
        player.activeClasses.forEach { addAll(forClass(it)) }
        player.activeRaces.forEach { addAll(forRace(it)) }
    }

    /**
     * Every race modifier that applies to this player's run-away roll, in the
     * order they should be shown. An Elf is +1 and a Halfling is -1, so a
     * Half-Breed holding both cards nets zero — which is why this returns the
     * breakdown rather than only the total.
     */
    fun runAwayModifiers(player: PlayerState): List<Pair<CharacterRace, Int>> = buildList {
        if (player.hasRace(CharacterRace.ELF)) add(CharacterRace.ELF to 1)
        if (player.hasRace(CharacterRace.HALFLING)) add(CharacterRace.HALFLING to -1)
    }

    /**
     * Net modifier to a run-away roll from the player's races.
     *
     * The Halfling's -1 was missing entirely: only the Elf's +1 was ever counted,
     * so a Halfling saw an unmodified roll.
     */
    fun runAwayBonus(player: PlayerState): Int =
        runAwayModifiers(player).sumOf { it.second }
}
