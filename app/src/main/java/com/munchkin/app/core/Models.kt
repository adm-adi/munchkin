package com.munchkin.app.core

import kotlinx.serialization.Serializable

/**
 * Core domain models for Munchkin Mesa Tracker
 */

// ============== Identity Types ==============

@Serializable
@JvmInline
value class GameId(val value: String)

@Serializable  
@JvmInline
value class PlayerId(val value: String)

@Serializable
@JvmInline
value class EntryId(val value: String)

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
    CLERIC      // Clerigo
}

@Serializable
enum class CharacterRace {
    HUMAN,      // Humano
    ELF,        // Elfo
    DWARF,      // Enano
    HALFLING    // Mediano
}

@Serializable
enum class GamePhase {
    LOBBY,      // Waiting for players
    IN_GAME,    // Game in progress
    FINISHED    // Game ended
}

// ============== Player State ==============

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
    val raceIds: List<EntryId> = emptyList(),
    val classIds: List<EntryId> = emptyList(),
    val hasHalfBreed: Boolean = false,
    val hasSuperMunchkin: Boolean = false,
    val lastKnownIp: String? = null,
    val isConnected: Boolean = true,
    val lastRoll: Int? = null, // For initial roll or others
    /**
     * Whether this player's arrival at [GameSettings.maxLevel] came from killing a
     * monster — including an Elf levelling up by helping kill one.
     *
     * The rule is that the winning level may only be reached that way, and
     * [GameSettings.levelTenOnlyCombat] declares it, but nothing recorded *how* a
     * level was gained, so the win prompt fired for anyone who tapped +1 up to the
     * maximum. Cleared by the server if the player drops back below it.
     */
    val reachedMaxLevelViaCombat: Boolean = false
) {
    /**
     * Combined combat power = level + gear + temp bonus
     */
    val combatPower: Int get() = level + gearBonus + tempCombatBonus
    
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
     * Check if player can add another race
     */
    val canAddRace: Boolean get() = raceIds.size < maxRaces

    /**
     * Check if player can add another class
     */
    val canAddClass: Boolean get() = classIds.size < maxClasses
}

// ============== Catalog Entry ==============

@Serializable
data class CatalogEntry(
    val entryId: EntryId,
    val displayName: String,
    val normalizedName: String,
    val aliases: List<String> = emptyList(),
    val createdByPlayerId: PlayerId,
    val createdAt: Long,
    val isArchived: Boolean = false
) {
    companion object {
        /**
         * Normalize a name for comparison (lowercase, trim, single spaces)
         */
        fun normalize(name: String): String {
            return name.trim().lowercase().replace(Regex("\\s+"), " ")
        }
        
        /**
         * Validate display name length
         */
        fun isValidName(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.length in 2..24
        }
    }
}

// ============== Game State ==============

@Serializable
data class GameState(
    val gameId: GameId,
    val joinCode: String,
    val epoch: Int = 0,
    val seq: Long = 0,
    val hostId: PlayerId,
    val players: Map<PlayerId, PlayerState> = emptyMap(),
    val races: Map<EntryId, CatalogEntry> = emptyMap(),
    val classes: Map<EntryId, CatalogEntry> = emptyMap(),
    val combat: CombatState? = null,
    val phase: GamePhase = GamePhase.LOBBY,
    val winnerId: PlayerId? = null,
    val turnPlayerId: PlayerId? = null, // Current active player
    val turnEndsAt: Long? = null, // Authoritative server deadline for the active turn
    val playerOrder: List<PlayerId> = emptyList(), // Custom Seat Order
    val createdAt: Long = System.currentTimeMillis(),
    val settings: GameSettings = GameSettings()
) {
    /**
     * Get sorted list of players by explicit order or default
     */
    val playerList: List<PlayerState> 
        get() = if (playerOrder.isNotEmpty()) {
            playerOrder.mapNotNull { players[it] } + players.values.filter { !playerOrder.contains(it.playerId) }
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
    val hasRollTie: Boolean get() {
        if (!allPlayersRolled) return false
        val maxRoll = players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: return false
        return players.values.count { it.lastRoll == maxRoll } > 1
    }
    
    /**
     * Get player IDs that need to re-roll (tied for highest)
     */
    val tiedPlayerIds: Set<PlayerId> get() {
        if (!allPlayersRolled) return emptySet()
        val maxRoll = players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: return emptySet()
        return players.values.filter { it.lastRoll == maxRoll }.map { it.playerId }.toSet()
    }
    
    /**
     * Check if game can start (2+ players and all rolled with no ties)
     */
    val canStart: Boolean get() = players.size >= 2 && allPlayersRolled && !hasRollTie
    
    /**
     * Get active (non-archived) races
     */
    val activeRaces: Map<EntryId, CatalogEntry>
        get() = races.filterValues { !it.isArchived }
    
    /**
     * Get active (non-archived) classes
     */
    val activeClasses: Map<EntryId, CatalogEntry>
        get() = classes.filterValues { !it.isArchived }
}

// ============== Game Settings ==============

/**
 * The rules in force for a room. The server sends every field, so both sides
 * agree on them rather than each falling back to its own defaults.
 */
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
    val turnTimerSeconds: Int = 0  // 0 = disabled, otherwise seconds per turn
) {
    /**
     * Whether a player at [maxLevel] must have got there through combat for the
     * win to be offered.
     */
    val requiresCombatToWin: Boolean get() = levelTenOnlyCombat && !allowLevelTenOverride
}

// ============== Player Meta (for joining) ==============

@Serializable
data class PlayerMeta(
    val playerId: PlayerId,
    val name: String,
    val avatarId: Int,
    val gender: Gender,
    val userId: String? = null
)
