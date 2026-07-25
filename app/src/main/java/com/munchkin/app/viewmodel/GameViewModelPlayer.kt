package com.munchkin.app.viewmodel

import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.EntryId
import com.munchkin.app.core.AddClass
import com.munchkin.app.core.AddRace
import com.munchkin.app.core.CatalogAddClass
import com.munchkin.app.core.CatalogAddRace
import com.munchkin.app.core.DecGear
import com.munchkin.app.core.DecLevel
import com.munchkin.app.core.IncGear
import com.munchkin.app.core.IncLevel
import com.munchkin.app.core.RemoveClass
import com.munchkin.app.core.RemoveRace
import com.munchkin.app.core.SetClass
import com.munchkin.app.core.SetGender
import com.munchkin.app.core.SetHalfBreed
import com.munchkin.app.core.SetRace
import com.munchkin.app.core.SetSuperMunchkin
import com.munchkin.app.core.CharacterClass
import com.munchkin.app.core.CharacterRace
import com.munchkin.app.core.Gender
import kotlinx.coroutines.flow.update
import java.util.UUID

fun GameViewModel.selectPlayer(playerId: PlayerId) {
    _uiState.update {
        it.copy(
            selectedPlayerId = playerId,
            screen = Screen.PLAYER_DETAIL
        )
    }
}

fun GameViewModel.incrementLevel() {
    sendPlayerEvent { playerId ->
        IncLevel(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId
        )
    }
}

fun GameViewModel.decrementLevel() {
    sendPlayerEvent { playerId ->
        DecLevel(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId
        )
    }
}

fun GameViewModel.incrementGear(amount: Int = 1) {
    sendPlayerEvent { playerId ->
        IncGear(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            amount = amount
        )
    }
}

fun GameViewModel.decrementGear(amount: Int = 1) {
    sendPlayerEvent { playerId ->
        DecGear(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            amount = amount
        )
    }
}

fun GameViewModel.setHalfBreed(enabled: Boolean) {
    sendPlayerEvent { playerId ->
        SetHalfBreed(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            enabled = enabled
        )
    }
}

fun GameViewModel.setSuperMunchkin(enabled: Boolean) {
    sendPlayerEvent { playerId ->
        SetSuperMunchkin(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            enabled = enabled
        )
    }
}

fun GameViewModel.addRace(entryId: EntryId) {
    sendPlayerEvent { playerId ->
        AddRace(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            entryId = entryId
        )
    }
}

fun GameViewModel.removeRace(entryId: EntryId) {
    sendPlayerEvent { playerId ->
        RemoveRace(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            entryId = entryId
        )
    }
}

fun GameViewModel.addClass(entryId: EntryId) {
    sendPlayerEvent { playerId ->
        AddClass(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            entryId = entryId
        )
    }
}

fun GameViewModel.removeClass(entryId: EntryId) {
    sendPlayerEvent { playerId ->
        RemoveClass(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            entryId = entryId
        )
    }
}

fun GameViewModel.addRaceToCatalog(displayName: String) {
    sendPlayerEvent { playerId ->
        CatalogAddRace(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            displayName = displayName
        )
    }
}

fun GameViewModel.addClassToCatalog(displayName: String) {
    sendPlayerEvent { playerId ->
        CatalogAddClass(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            displayName = displayName
        )
    }
}

fun GameViewModel.toggleGender() {
    val playerId = myPlayerId ?: return
    val currentPlayer = _uiState.value.gameState?.players?.get(playerId) ?: return

    val newGender = when (currentPlayer.gender) {
        Gender.M -> Gender.F
        Gender.F -> Gender.NA
        Gender.NA -> Gender.M
    }

    sendPlayerEvent { pid ->
        SetGender(
            eventId = UUID.randomUUID().toString(),
            actorId = pid,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = pid,
            gender = newGender
        )
    }
}

fun GameViewModel.setCharacterClass(newClass: CharacterClass) {
    sendPlayerEvent { playerId ->
        SetClass(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            newClass = newClass
        )
    }
}

fun GameViewModel.setCharacterRace(newRace: CharacterRace) {
    sendPlayerEvent { playerId ->
        SetRace(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            targetPlayerId = playerId,
            newRace = newRace
        )
    }
}
