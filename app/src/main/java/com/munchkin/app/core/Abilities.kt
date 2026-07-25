package com.munchkin.app.core

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
    /** Short label, e.g. "Guerrero". */
    val source: String,
    val description: String,
    val kind: AbilityKind
)

object Abilities {

    fun forClass(characterClass: CharacterClass): List<Ability> = when (characterClass) {
        CharacterClass.NONE -> emptyList()

        CharacterClass.WARRIOR -> listOf(
            Ability(
                source = "Guerrero",
                description = "Ganas los empates en combate.",
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                source = "Guerrero",
                description = "Berserk: descarta hasta 3 cartas para +1 al combate cada una.",
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.WIZARD -> listOf(
            Ability(
                source = "Mago",
                description = "Hechizo de encanto: descarta toda tu mano para encantar un " +
                    "monstruo. Lo retiras del combate y te llevas su tesoro, sin subir nivel.",
                kind = AbilityKind.MANUAL
            ),
            Ability(
                source = "Mago",
                description = "Hechizo de vuelo: descarta hasta 3 cartas para +1 a la " +
                    "tirada de huida cada una.",
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.THIEF -> listOf(
            Ability(
                source = "Ladrón",
                description = "Puñalada por la espalda: descarta una carta para dar -2 al " +
                    "combate de otro jugador.",
                kind = AbilityKind.MANUAL
            ),
            Ability(
                source = "Ladrón",
                description = "Robar: descarta una carta y tira el dado para robar un " +
                    "objeto a otro jugador.",
                kind = AbilityKind.MANUAL
            )
        )

        CharacterClass.CLERIC -> listOf(
            Ability(
                source = "Clérigo",
                description = "+3 al combate contra No-Muertos.",
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                source = "Clérigo",
                description = "Resurrección: al descartar, puedes descartar 2 cartas más " +
                    "y robar otras 2.",
                kind = AbilityKind.MANUAL
            )
        )
    }

    fun forRace(characterRace: CharacterRace): List<Ability> = when (characterRace) {
        // A Human is the absence of a Race card, and grants nothing.
        CharacterRace.HUMAN -> emptyList()

        CharacterRace.ELF -> listOf(
            Ability(
                source = "Elfo",
                description = "Subes un nivel cada vez que ayudas a matar un monstruo.",
                kind = AbilityKind.AUTOMATIC
            ),
            Ability(
                source = "Elfo",
                description = "+1 a la tirada de huida.",
                kind = AbilityKind.AUTOMATIC
            )
        )

        CharacterRace.DWARF -> listOf(
            Ability(
                source = "Enano",
                description = "Puedes llevar cualquier número de objetos Grandes.",
                kind = AbilityKind.MANUAL
            ),
            Ability(
                source = "Enano",
                description = "Puedes tener 6 cartas en la mano.",
                kind = AbilityKind.MANUAL
            )
        )

        CharacterRace.HALFLING -> listOf(
            Ability(
                source = "Mediano",
                description = "Puedes vender un objeto por el doble de su valor.",
                kind = AbilityKind.MANUAL
            ),
            Ability(
                source = "Mediano",
                description = "Puedes tener 6 cartas en la mano.",
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

    /** Bonus to a run-away roll granted automatically by the player's races. */
    fun runAwayBonus(player: PlayerState): Int =
        if (player.hasRace(CharacterRace.ELF)) 1 else 0
}
