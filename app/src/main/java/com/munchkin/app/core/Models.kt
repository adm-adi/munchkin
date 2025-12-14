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
    val level: Int = 1,
    val gearBonus: Int = 0,
    val tempCombatBonus: Int = 0,
    val raceIds: List<EntryId> = emptyList(),
    val classIds: List<EntryId> = emptyList(),
    val hasHalfBreed: Boolean = false,
    val hasSuperMunchkin: Boolean = false,
    val lastKnownIp: String? = null,
    val isConnected: Boolean = true
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
    val createdAt: Long = System.currentTimeMillis(),
    val settings: GameSettings = GameSettings()
) {
    /**
     * Get sorted list of players by join order (implicit by playerId)
     */
    val playerList: List<PlayerState> 
        get() = players.values.toList()
    
    /**
     * Check if game is full (6 players max)
     */
    val isFull: Boolean get() = players.size >= 6
    
    /**
     * Check if game can start (2+ players)
     */
    val canStart: Boolean get() = players.size >= 2
    
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

@Serializable
data class GameSettings(
    val minLevel: Int = 1,
    val maxLevel: Int = 10,
    val tiesGoToMonsters: Boolean = true,
    val levelTenOnlyCombat: Boolean = true,
    val allowLevelTenOverride: Boolean = false
)

// ============== Player Meta (for joining) ==============

@Serializable
data class PlayerMeta(
    val playerId: PlayerId,
    val name: String,
    val avatarId: Int,
    val gender: Gender
)
