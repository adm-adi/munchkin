package com.munchkin.app.viewmodel

import com.munchkin.app.core.AddClass
import com.munchkin.app.core.AddRace
import com.munchkin.app.core.BonusTarget
import com.munchkin.app.core.CatalogAddClass
import com.munchkin.app.core.CatalogAddRace
import com.munchkin.app.core.CharacterClass
import com.munchkin.app.core.CharacterRace
import com.munchkin.app.core.CombatAddHelper
import com.munchkin.app.core.CombatAddMonster
import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.CombatOutcome
import com.munchkin.app.core.CombatRemoveHelper
import com.munchkin.app.core.CombatSetModifier
import com.munchkin.app.core.CombatStart
import com.munchkin.app.core.DecGear
import com.munchkin.app.core.DecLevel
import com.munchkin.app.core.DiceRollPurpose
import com.munchkin.app.core.EndTurn
import com.munchkin.app.core.EntryId
import com.munchkin.app.core.GameEnd
import com.munchkin.app.core.GameEvent
import com.munchkin.app.core.GameStart
import com.munchkin.app.core.Gender
import com.munchkin.app.core.IncGear
import com.munchkin.app.core.IncLevel
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerRoll
import com.munchkin.app.core.RemoveClass
import com.munchkin.app.core.RemoveRace
import com.munchkin.app.core.SetClass
import com.munchkin.app.core.SetGender
import com.munchkin.app.core.SetHalfBreed
import com.munchkin.app.core.SetRace
import com.munchkin.app.core.SetSuperMunchkin
import java.util.UUID

class GameEventFactory(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    fun gameStart(actorId: PlayerId): GameEvent = build(actorId) {
        GameStart(eventId, actorId, timestamp)
    }

    fun endTurn(actorId: PlayerId): GameEvent = build(actorId) {
        EndTurn(eventId, actorId, timestamp)
    }

    fun gameEnd(actorId: PlayerId, winnerId: PlayerId?): GameEvent = build(actorId) {
        GameEnd(eventId, actorId, timestamp, winnerId = winnerId)
    }

    fun incrementLevel(actorId: PlayerId): GameEvent = build(actorId) {
        IncLevel(eventId, actorId, timestamp, targetPlayerId = actorId)
    }

    fun decrementLevel(actorId: PlayerId, targetPlayerId: PlayerId = actorId, amount: Int = 1): GameEvent = build(actorId) {
        DecLevel(eventId, actorId, timestamp, targetPlayerId = targetPlayerId, amount = amount)
    }

    fun incrementGear(actorId: PlayerId, amount: Int): GameEvent = build(actorId) {
        IncGear(eventId, actorId, timestamp, targetPlayerId = actorId, amount = amount)
    }

    fun decrementGear(actorId: PlayerId, amount: Int): GameEvent = build(actorId) {
        DecGear(eventId, actorId, timestamp, targetPlayerId = actorId, amount = amount)
    }

    fun setHalfBreed(actorId: PlayerId, enabled: Boolean): GameEvent = build(actorId) {
        SetHalfBreed(eventId, actorId, timestamp, targetPlayerId = actorId, enabled = enabled)
    }

    fun setSuperMunchkin(actorId: PlayerId, enabled: Boolean): GameEvent = build(actorId) {
        SetSuperMunchkin(eventId, actorId, timestamp, targetPlayerId = actorId, enabled = enabled)
    }

    fun addRace(actorId: PlayerId, entryId: EntryId): GameEvent = build(actorId) {
        AddRace(eventId, actorId, timestamp, targetPlayerId = actorId, entryId = entryId)
    }

    fun removeRace(actorId: PlayerId, entryId: EntryId): GameEvent = build(actorId) {
        RemoveRace(eventId, actorId, timestamp, targetPlayerId = actorId, entryId = entryId)
    }

    fun addClass(actorId: PlayerId, entryId: EntryId): GameEvent = build(actorId) {
        AddClass(eventId, actorId, timestamp, targetPlayerId = actorId, entryId = entryId)
    }

    fun removeClass(actorId: PlayerId, entryId: EntryId): GameEvent = build(actorId) {
        RemoveClass(eventId, actorId, timestamp, targetPlayerId = actorId, entryId = entryId)
    }

    fun addRaceToCatalog(actorId: PlayerId, displayName: String): GameEvent = build(actorId) {
        CatalogAddRace(eventId, actorId, timestamp, displayName = displayName)
    }

    fun addClassToCatalog(actorId: PlayerId, displayName: String): GameEvent = build(actorId) {
        CatalogAddClass(eventId, actorId, timestamp, displayName = displayName)
    }

    fun setGender(actorId: PlayerId, gender: Gender): GameEvent = build(actorId) {
        SetGender(eventId, actorId, timestamp, targetPlayerId = actorId, gender = gender)
    }

    fun setCharacterClass(actorId: PlayerId, newClass: CharacterClass): GameEvent = build(actorId) {
        SetClass(eventId, actorId, timestamp, targetPlayerId = actorId, newClass = newClass)
    }

    fun setCharacterRace(actorId: PlayerId, newRace: CharacterRace): GameEvent = build(actorId) {
        SetRace(eventId, actorId, timestamp, targetPlayerId = actorId, newRace = newRace)
    }

    fun addHelper(actorId: PlayerId, helperId: PlayerId): GameEvent = build(actorId) {
        CombatAddHelper(eventId, actorId, timestamp, helperId = helperId)
    }

    fun removeHelper(actorId: PlayerId): GameEvent = build(actorId) {
        CombatRemoveHelper(eventId, actorId, timestamp)
    }

    fun setCombatModifier(actorId: PlayerId, target: BonusTarget, value: Int): GameEvent = build(actorId) {
        CombatSetModifier(eventId, actorId, timestamp, target = target, value = value)
    }

    fun startCombat(actorId: PlayerId, mainPlayerId: PlayerId): GameEvent = build(actorId) {
        CombatStart(eventId, actorId, timestamp, mainPlayerId = mainPlayerId)
    }

    fun addMonster(actorId: PlayerId, name: String, level: Int, modifier: Int, isUndead: Boolean): GameEvent = build(actorId) {
        CombatAddMonster(
            eventId,
            actorId,
            timestamp,
            monster = MonsterInstance(
                id = newId(),
                name = name,
                baseLevel = level.coerceIn(1, 20),
                flatModifier = modifier,
                isUndead = isUndead
            )
        )
    }

    fun endCombat(
        actorId: PlayerId,
        outcome: CombatOutcome,
        levelsGained: Int = 0,
        treasuresGained: Int = 0,
        helperLevelsGained: Int = 0
    ): GameEvent = build(actorId) {
        CombatEnd(
            eventId,
            actorId,
            timestamp,
            outcome = outcome,
            levelsGained = levelsGained,
            treasuresGained = treasuresGained,
            helperLevelsGained = helperLevelsGained
        )
    }

    fun roll(
        actorId: PlayerId,
        result: Int,
        purpose: DiceRollPurpose = DiceRollPurpose.RANDOM,
        success: Boolean = false
    ): GameEvent = build(actorId) {
        PlayerRoll(
            eventId,
            actorId,
            timestamp,
            targetPlayerId = actorId,
            result = result.coerceIn(1, 6),
            purpose = purpose,
            success = success
        )
    }

    private fun <T : GameEvent> build(actorId: PlayerId, block: EventMetadata.() -> T): T {
        return EventMetadata(
            eventId = newId(),
            actorId = actorId,
            timestamp = now()
        ).block()
    }
}

data class EventMetadata(
    val eventId: String,
    val actorId: PlayerId,
    val timestamp: Long
)
