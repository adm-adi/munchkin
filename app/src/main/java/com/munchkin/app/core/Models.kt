package com.munchkin.app.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Core domain models for Munchkin Mesa Tracker.
 */

// ============== Identity Types ==============

@Serializable
@JvmInline
value class GameId(val value: String)

@Serializable
@JvmInline
value class PlayerId(val value: String)

// ============== Enums ==============

@Serializable
enum class Gender {
    M,      // Male / Hombre
    F,      // Female / Mujer
    NA      // Not Applicable
}

@Serializable
enum class CharacterClass {
    NONE,
    WARRIOR,    // Guerrero
    WIZARD,     // Mago
    THIEF,      // Ladron
    CLERIC,     // Clerigo
    BARD,       // Bardo (Expansion)
    RANGER;     // Explorador (Expansion)

    val isExpansion: Boolean get() = this == BARD || this == RANGER

    companion object {
        val baseEntries: List<CharacterClass> = listOf(NONE, WARRIOR, WIZARD, THIEF, CLERIC)
        fun availableFor(expansionsEnabled: Boolean): List<CharacterClass> =
            if (expansionsEnabled) entries else baseEntries
    }
}

@Serializable
enum class CharacterRace {
    HUMAN,      // Humano
    ELF,        // Elfo
    DWARF,      // Enano
    HALFLING,   // Mediano
    ORC,        // Orco (Expansion)
    GNOME;      // Gnomo (Expansion)

    val isExpansion: Boolean get() = this == ORC || this == GNOME

    companion object {
        val baseEntries: List<CharacterRace> = listOf(HUMAN, ELF, DWARF, HALFLING)
        fun availableFor(expansionsEnabled: Boolean): List<CharacterRace> =
            if (expansionsEnabled) entries else baseEntries
    }
}

@Immutable
@Serializable
data class ActiveCurse(
    val id: String,
    val name: String,
    val combatPenalty: Int = 0,
    val runAwayPenalty: Int = 0,
    val combatsRemaining: Int = 0,
    val clearOnDeath: Boolean = true
)

@Serializable
enum class GamePhase {
    LOBBY,      // Waiting for players
    IN_GAME,    // Game in progress
    FINISHED    // Game ended
}

// ============== Player State ==============

@Immutable
@Serializable
data class PlayerState(
    val playerId: PlayerId,
    val name: String,
    val avatarId: Int = 0,
    val gender: Gender = Gender.NA,
    val characterClass: CharacterClass = CharacterClass.NONE,
    val characterRace: CharacterRace = CharacterRace.HUMAN,
    /**
     * Second class, only meaningful with Super Munchkin. NONE means no second class.
     */
    val secondaryClass: CharacterClass = CharacterClass.NONE,
    /**
     * Second race, only meaningful with Half-Breed. HUMAN means no second race —
     * in Munchkin a Human is precisely the absence of a Race card.
     */
    val secondaryRace: CharacterRace = CharacterRace.HUMAN,
    val level: Int = 1,
    val gearBonus: Int = 0,
    val tempCombatBonus: Int = 0,
    val treasures: Int = 0,
    val hasHalfBreed: Boolean = false,
    val hasSuperMunchkin: Boolean = false,
    val activeCurses: List<ActiveCurse> = emptyList(),
    val isConnected: Boolean = true,
    val lastRoll: Int? = null, // For initial roll or others
    /**
     * Whether this player's arrival at [GameSettings.maxLevel] came from killing a
     * monster — including an Elf levelling up by helping kill one.
     *
     * Nullable on purpose: `null` means the server does not report this at all.
     * See [canBeConfirmedWinner].
     */
    val reachedMaxLevelViaCombat: Boolean? = null
) {
    /**
     * Combined combat power = level + clamped gear + clamped temp bonus
     */
    val combatPower: Int
        get() = level +
            gearBonus.coerceIn(GameEngine.GEAR_MIN, GameEngine.GEAR_MAX) +
            tempCombatBonus.coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)

    /**
     * Maximum races allowed based on Half-Breed status
     */
    val maxRaces: Int get() = if (hasHalfBreed) 2 else 1

    /**
     * Maximum classes allowed based on Super Munchkin status
     */
    val maxClasses: Int get() = if (hasSuperMunchkin) 2 else 1

    /**
     * Every class whose abilities currently apply.
     *
     * Super Munchkin lets a player hold two Class cards and use the abilities of
     * both, so ability checks must go through this rather than testing
     * [characterClass] alone.
     */
    val activeClasses: List<CharacterClass>
        get() = buildList {
            if (characterClass != CharacterClass.NONE) add(characterClass)
            if (hasSuperMunchkin && secondaryClass != CharacterClass.NONE &&
                secondaryClass != characterClass
            ) {
                add(secondaryClass)
            }
        }

    /**
     * Every race whose abilities currently apply. Half-Breed is the race equivalent
     * of Super Munchkin. HUMAN is omitted because it grants nothing.
     */
    val activeRaces: List<CharacterRace>
        get() = buildList {
            if (characterRace != CharacterRace.HUMAN) add(characterRace)
            if (hasHalfBreed && secondaryRace != CharacterRace.HUMAN &&
                secondaryRace != characterRace
            ) {
                add(secondaryRace)
            }
        }

    fun hasClass(target: CharacterClass): Boolean = activeClasses.contains(target)

    fun hasRace(target: CharacterRace): Boolean = activeRaces.contains(target)

    /**
     * Whether this player may be offered to the host as the winner.
     */
    fun canBeConfirmedWinner(settings: GameSettings): Boolean =
        level >= settings.maxLevel &&
            (!settings.requiresCombatToWin || reachedMaxLevelViaCombat != false)
}

@Immutable
@Serializable
data class PlayerMatchStat(
    val playerId: PlayerId,
    val name: String,
    val finalLevel: Int = 1,
    val monstersKilled: Int = 0,
    val combatsHelped: Int = 0,
    val biggestCombat: Int = 0,
    val deaths: Int = 0,
    val escapes: Int = 0
)

@Immutable
@Serializable
data class MatchSummary(
    val durationMs: Long = 0L,
    val totalTurns: Int = 1,
    val playerStats: List<PlayerMatchStat> = emptyList(),
    val mvpSlayerId: PlayerId? = null,
    val bestHelperId: PlayerId? = null,
    val mostDeathsId: PlayerId? = null
)

// ============== Game State ==============

@Immutable
@Serializable
data class GameState(
    val gameId: GameId,
    val joinCode: String,
    val epoch: Int = 0,
    val seq: Long = 0,
    val hostId: PlayerId,
    val players: Map<PlayerId, PlayerState> = emptyMap(),
    val combat: CombatState? = null,
    val phase: GamePhase = GamePhase.LOBBY,
    val winnerId: PlayerId? = null,
    val turnPlayerId: PlayerId? = null, // Current active player
    val turnEndsAt: Long? = null, // Authoritative server deadline for the active turn
    val turnTimerPausedRemainingMs: Long? = null,
    val spectatorCount: Int = 0,
    val canUndo: Boolean = false,
    val matchSummary: MatchSummary? = null,
    val playerOrder: List<PlayerId> = emptyList(), // Custom Seat Order
    val createdAt: Long = System.currentTimeMillis(),
    val settings: GameSettings = GameSettings()
) {
    /**
     * Get sorted list of players by explicit order or default.
     * Uses a HashSet for O(1) membership checks when appending unordered players.
     */
    val playerList: List<PlayerState>
        get() = if (playerOrder.isNotEmpty()) {
            val orderedSet = playerOrder.toHashSet()
            playerOrder.mapNotNull { players[it] } + players.values.filter { it.playerId !in orderedSet }
        } else {
            players.values.toList().sortedBy { it.playerId.value }
        }

    /**
     * Check if game is full (6 players max)
     */
    val isFull: Boolean get() = players.size >= 6

    /**
     * Check if all players have rolled dice
     */
    val allPlayersRolled: Boolean get() = players.values.all { it.lastRoll != null }

    /**
     * Check if there is a tie for highest dice roll among players who need to re-roll
     */
    val hasRollTie: Boolean
        get() {
            if (!allPlayersRolled) return false
            val maxRoll = players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: return false
            return players.values.count { it.lastRoll == maxRoll } > 1
        }

    /**
     * Get player IDs that need to re-roll (tied for highest)
     */
    val tiedPlayerIds: Set<PlayerId>
        get() {
            if (!allPlayersRolled) return emptySet()
            val maxRoll = players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: return emptySet()
            val tied = players.values.filter { it.lastRoll == maxRoll }.map { it.playerId }.toSet()
            return if (tied.size > 1) tied else emptySet()
        }

    /**
     * Check if game can start (2+ players and all rolled with no ties)
     */
    val canStart: Boolean get() = players.size >= 2 && allPlayersRolled && !hasRollTie
}

// ============== Game Settings ==============

/**
 * The rules in force for a room. The server sends every field, so both sides
 * agree on them rather than each falling back to its own defaults.
 */
@Immutable
@Serializable
data class GameSettings(
    val minLevel: Int = 1,
    val maxLevel: Int = 10,
    /** Ties go to the monster unless a Warrior is in the fight. */
    val tiesGoToMonsters: Boolean = true,
    /** The winning level may only be reached by killing a monster. */
    val levelTenOnlyCombat: Boolean = true,
    /** Lets the host confirm a win that did not come from a kill anyway. */
    val allowLevelTenOverride: Boolean = false,
    val expansionsEnabled: Boolean = false,
    val turnTimerSeconds: Int = 0  // 0 = disabled, otherwise seconds per turn
) {
    /**
     * Whether a player at [maxLevel] must have got there through combat for the
     * win to be offered.
     */
    val requiresCombatToWin: Boolean get() = levelTenOnlyCombat && !allowLevelTenOverride
}

// ============== Player Meta (for joining) ==============

@Immutable
@Serializable
data class PlayerMeta(
    val playerId: PlayerId,
    val name: String,
    val avatarId: Int,
    val gender: Gender,
    val userId: String? = null
)
