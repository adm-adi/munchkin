package com.munchkin.app.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Game events for event sourcing architecture.
 * All state changes go through events validated by the server.
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
@SerialName("GAME_START")
data class GameStart(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null
) : GameEvent()

@Serializable
@SerialName("PLAYER_ROLL")
data class PlayerRoll(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId?,
    val result: Int,
    val purpose: DiceRollPurpose = DiceRollPurpose.RANDOM,
    val success: Boolean = false,
    val modifier: Int = 0
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

@Serializable
@SerialName("END_TURN")
data class EndTurn(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null
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
    val reason: String? = null
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
@SerialName("SET_LEVEL")
data class SetLevel(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val level: Int
) : GameEvent()

@Serializable
@SerialName("SET_GEAR")
data class SetGear(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val gear: Int
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
@SerialName("SET_CLASS")
data class SetClass(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val newClass: CharacterClass,
    /** Targets the Super Munchkin second slot instead of the primary one. */
    val isSecondary: Boolean = false
) : GameEvent()

@Serializable
@SerialName("SET_RACE")
data class SetRace(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val newRace: CharacterRace,
    /** Targets the Half-Breed second slot instead of the primary one. */
    val isSecondary: Boolean = false
) : GameEvent()

@Serializable
@SerialName("PLAYER_DEATH")
data class PlayerDeath(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId
) : GameEvent()

@Serializable
@SerialName("ADD_CURSE")
data class AddCurse(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val curse: ActiveCurse
) : GameEvent()

@Serializable
@SerialName("REMOVE_CURSE")
data class RemoveCurse(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId,
    val curseId: String
) : GameEvent()

// ============== Combat Events ==============

@Serializable
@SerialName("COMBAT_START")
data class CombatStart(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val mainPlayerId: PlayerId,
    val monsters: List<MonsterInstance> = emptyList()
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
@SerialName("COMBAT_SET_HELPER_TREASURES")
data class CombatSetHelperTreasures(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val treasures: Int? = null
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
    val levelsGained: Int = 0,
    val treasuresGained: Int = 0,
    val helperLevelsGained: Int = 0,
    val expectedSeq: Long? = null,
    val cancelled: Boolean = false
) : GameEvent()

@Serializable
@SerialName("COMBAT_SET_MODIFIER")
data class CombatSetModifier(
    override val eventId: String,
    override val actorId: PlayerId,
    override val timestamp: Long,
    override val targetPlayerId: PlayerId? = null,
    val target: BonusTarget, // HEROES or MONSTER
    val value: Int           // Absolute value
) : GameEvent()

// ============== Dice Roll Models ==============

@Serializable
enum class DiceRollPurpose {
    COMBAT,         // Combat roll
    RUN_AWAY,       // Escaping combat
    CURSE,          // Curse resolution roll
    RANDOM,         // Generic roll
    TIE_BREAKER     // Tie-breaker roll
}

@Immutable
@Serializable
data class DiceRollInfo(
    val playerId: PlayerId,
    val playerName: String,
    val result: Int,
    val success: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val purpose: DiceRollPurpose = DiceRollPurpose.RANDOM,
    val modifier: Int = 0
)
