package com.munchkin.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Game events for event sourcing architecture.
 * All state changes go through events that are validated by the host.
 */

@Serializable
sealed class GameEvent {
    abstract val eventId: String
    abstract val actorId: PlayerId
    abstract val timestamp: Long
    abstract val targetPlayerId: PlayerId?
}

// ============== Game Lifecycle Events ==============

@Serializable
@SerialName("GAME_CREATE")
data class GameCreate(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val gameId: GameId,
    val joinCode: String,
    val hostMeta: PlayerMeta
) : GameEvent()

@Serializable
@SerialName("PLAYER_JOIN")
data class PlayerJoin(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val playerMeta: PlayerMeta,
    val lastKnownIp: String?
) : GameEvent()

@Serializable
@SerialName("PLAYER_LEAVE")
data class PlayerLeave(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null
) : GameEvent()

@Serializable
@SerialName("GAME_START")
data class GameStart(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null
) : GameEvent()

@Serializable
@SerialName("GAME_END")
data class GameEnd(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val winnerId: PlayerId?
) : GameEvent()

// ============== Player Modification Events ==============

@Serializable
@SerialName("SET_NAME")
data class SetName(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val name: String
) : GameEvent()

@Serializable
@SerialName("SET_AVATAR")
data class SetAvatar(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val avatarId: Int
) : GameEvent()

@Serializable
@SerialName("SET_GENDER")
data class SetGender(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val gender: Gender
) : GameEvent()

@Serializable
@SerialName("INC_LEVEL")
data class IncLevel(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val amount: Int = 1,
    val reason: String? = null  // For level 10 override
) : GameEvent()

@Serializable
@SerialName("DEC_LEVEL")
data class DecLevel(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val amount: Int = 1
) : GameEvent()

@Serializable
@SerialName("INC_GEAR")
data class IncGear(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val amount: Int = 1
) : GameEvent()

@Serializable
@SerialName("DEC_GEAR")
data class DecGear(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val amount: Int = 1
) : GameEvent()

@Serializable
@SerialName("SET_HALF_BREED")
data class SetHalfBreed(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val enabled: Boolean
) : GameEvent()

@Serializable
@SerialName("SET_SUPER_MUNCHKIN")
data class SetSuperMunchkin(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val enabled: Boolean
) : GameEvent()

@Serializable
@SerialName("ADD_RACE")
data class AddRace(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val entryId: EntryId
) : GameEvent()

@Serializable
@SerialName("REMOVE_RACE")
data class RemoveRace(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val entryId: EntryId
) : GameEvent()

@Serializable
@SerialName("CLEAR_RACES")
data class ClearRaces(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId
) : GameEvent()

@Serializable
@SerialName("ADD_CLASS")
data class AddClass(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val entryId: EntryId
) : GameEvent()

@Serializable
@SerialName("REMOVE_CLASS")
data class RemoveClass(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val entryId: EntryId
) : GameEvent()

@Serializable
@SerialName("CLEAR_CLASSES")
data class ClearClasses(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId
) : GameEvent()

// ============== Catalog Events ==============

@Serializable
@SerialName("CATALOG_ADD_RACE")
data class CatalogAddRace(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val displayName: String,
    val aliases: List<String> = emptyList()
) : GameEvent()

@Serializable
@SerialName("CATALOG_ADD_CLASS")
data class CatalogAddClass(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val displayName: String,
    val aliases: List<String> = emptyList()
) : GameEvent()

@Serializable
@SerialName("CATALOG_ARCHIVE_RACE")
data class CatalogArchiveRace(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val entryId: EntryId
) : GameEvent()

@Serializable
@SerialName("CATALOG_ARCHIVE_CLASS")
data class CatalogArchiveClass(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val entryId: EntryId
) : GameEvent()

// ============== Combat Events ==============

@Serializable
@SerialName("COMBAT_START")
data class CombatStart(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val mainPlayerId: PlayerId
) : GameEvent()

@Serializable
@SerialName("COMBAT_ADD_HELPER")
data class CombatAddHelper(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val helperId: PlayerId
) : GameEvent()

@Serializable
@SerialName("COMBAT_REMOVE_HELPER")
data class CombatRemoveHelper(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null
) : GameEvent()

@Serializable
@SerialName("COMBAT_ADD_MONSTER")
data class CombatAddMonster(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val monster: MonsterInstance
) : GameEvent()

@Serializable
@SerialName("COMBAT_REMOVE_MONSTER")
data class CombatRemoveMonster(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val monsterId: String
) : GameEvent()

@Serializable
@SerialName("COMBAT_UPDATE_MONSTER")
data class CombatUpdateMonster(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val monster: MonsterInstance
) : GameEvent()

@Serializable
@SerialName("COMBAT_ADD_BONUS")
data class CombatAddBonus(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val bonus: TempBonus
) : GameEvent()

@Serializable
@SerialName("COMBAT_REMOVE_BONUS")
data class CombatRemoveBonus(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val bonusId: String
) : GameEvent()

@Serializable
@SerialName("COMBAT_END")
data class CombatEnd(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val outcome: CombatOutcome,
    val levelsGained: Int = 0
) : GameEvent()
