package com.munchkin.app.core

import androidx.compose.runtime.Immutable

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
 */
enum class AbilityKind { AUTOMATIC, MANUAL }

enum class AbilitySourceKey { WARRIOR, WIZARD, THIEF, CLERIC, BARD, RANGER, ELF, DWARF, HALFLING, ORC, GNOME }

enum class AbilityDescriptionKey {
    WARRIOR_TIES, WARRIOR_BERSERK,
    WIZARD_CHARM, WIZARD_FLIGHT,
    THIEF_BACKSTAB, THIEF_STEAL,
    CLERIC_UNDEAD, CLERIC_RESURRECT,
    BARD_ENTHRALL, BARD_LUCK,
    RANGER_TAME, RANGER_HELP,
    ELF_HELP_LEVEL, ELF_RUN_AWAY,
    DWARF_BIG_ITEMS, DWARF_HAND_SIZE,
    HALFLING_SELL, HALFLING_RUN_AWAY,
    ORC_OVERKILL, ORC_IGNORE_CURSE,
    GNOME_ILLUSION, GNOME_NOSE
}

@Immutable
data class Ability(
    val sourceKey: AbilitySourceKey,
    val descriptionKey: AbilityDescriptionKey,
    val kind: AbilityKind
)

object Abilities {
    const val RUN_AWAY_THRESHOLD = 5

    fun forClass(characterClass: CharacterClass): List<Ability> = when (characterClass) {
        CharacterClass.NONE -> emptyList()

        CharacterClass.WARRIOR -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.WARRIOR,
                descriptionKey = AbilityDescriptionKey.WARRIOR_TIES,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceKey = AbilitySourceKey.WARRIOR,
                descriptionKey = AbilityDescriptionKey.WARRIOR_BERSERK,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.WIZARD -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.WIZARD,
                descriptionKey = AbilityDescriptionKey.WIZARD_CHARM,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.WIZARD,
                descriptionKey = AbilityDescriptionKey.WIZARD_FLIGHT,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.THIEF -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.THIEF,
                descriptionKey = AbilityDescriptionKey.THIEF_BACKSTAB,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.THIEF,
                descriptionKey = AbilityDescriptionKey.THIEF_STEAL,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.CLERIC -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.CLERIC,
                descriptionKey = AbilityDescriptionKey.CLERIC_UNDEAD,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceKey = AbilitySourceKey.CLERIC,
                descriptionKey = AbilityDescriptionKey.CLERIC_RESURRECT,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.BARD -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.BARD,
                descriptionKey = AbilityDescriptionKey.BARD_ENTHRALL,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.BARD,
                descriptionKey = AbilityDescriptionKey.BARD_LUCK,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.RANGER -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.RANGER,
                descriptionKey = AbilityDescriptionKey.RANGER_TAME,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.RANGER,
                descriptionKey = AbilityDescriptionKey.RANGER_HELP,
                kind = AbilityKind.MANUAL
            )
        )
    }

    fun forRace(characterRace: CharacterRace): List<Ability> = when (characterRace) {
        // A Human is the absence of a Race card, and grants nothing.
        CharacterRace.HUMAN -> emptyList()

        CharacterRace.ELF -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.ELF,
                descriptionKey = AbilityDescriptionKey.ELF_HELP_LEVEL,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceKey = AbilitySourceKey.ELF,
                descriptionKey = AbilityDescriptionKey.ELF_RUN_AWAY,
                kind = AbilityKind.AUTOMATIC
            )
        )

        CharacterRace.DWARF -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.DWARF,
                descriptionKey = AbilityDescriptionKey.DWARF_BIG_ITEMS,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.DWARF,
                descriptionKey = AbilityDescriptionKey.DWARF_HAND_SIZE,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterRace.HALFLING -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.HALFLING,
                descriptionKey = AbilityDescriptionKey.HALFLING_SELL,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.HALFLING,
                descriptionKey = AbilityDescriptionKey.HALFLING_RUN_AWAY,
                kind = AbilityKind.AUTOMATIC
            )
        )

        CharacterRace.ORC -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.ORC,
                descriptionKey = AbilityDescriptionKey.ORC_OVERKILL,
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                sourceKey = AbilitySourceKey.ORC,
                descriptionKey = AbilityDescriptionKey.ORC_IGNORE_CURSE,
                kind = AbilityKind.MANUAL
            )
        )

        CharacterRace.GNOME -> listOf(
            Ability(
                sourceKey = AbilitySourceKey.GNOME,
                descriptionKey = AbilityDescriptionKey.GNOME_ILLUSION,
                kind = AbilityKind.MANUAL
            ),
            Ability(
                sourceKey = AbilitySourceKey.GNOME,
                descriptionKey = AbilityDescriptionKey.GNOME_NOSE,
                kind = AbilityKind.MANUAL
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
     * Half-Breed holding both cards nets zero.
     */
    fun runAwayModifiers(player: PlayerState): List<Pair<CharacterRace, Int>> = buildList {
        if (player.hasRace(CharacterRace.ELF)) add(CharacterRace.ELF to 1)
        if (player.hasRace(CharacterRace.HALFLING)) add(CharacterRace.HALFLING to -1)
    }

    /**
     * Active curses affecting a player's run-away roll.
     */
    fun curseRunAwayModifiers(player: PlayerState): List<Pair<String, Int>> =
        player.activeCurses.filter { it.runAwayPenalty != 0 }.map { it.name to it.runAwayPenalty }

    /**
     * Net modifier to a run-away roll from the player's races and active curses.
     */
    fun runAwayBonus(player: PlayerState): Int =
        runAwayModifiers(player).sumOf { it.second } +
            player.activeCurses.sumOf { it.runAwayPenalty }
}
