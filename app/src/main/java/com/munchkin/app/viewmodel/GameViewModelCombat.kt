package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.core.CombatCalculator
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.events.CombatAddHelper
import com.munchkin.app.core.events.CombatAddMonster
import com.munchkin.app.core.events.CombatEnd
import com.munchkin.app.core.events.CombatOutcome
import com.munchkin.app.core.events.CombatRemoveHelper
import com.munchkin.app.core.events.CombatSetModifier
import com.munchkin.app.core.events.CombatStart
import com.munchkin.app.core.events.SetLevel
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.models.CatalogMonster
import com.munchkin.app.viewmodel.models.BonusTarget
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

fun GameViewModel.addHelper(helperId: PlayerId) {
    if (helperId == myPlayerId) return
    sendPlayerEvent { playerId ->
        CombatAddHelper(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            helperId = helperId
        )
    }
}

fun GameViewModel.removeHelper() {
    sendPlayerEvent { playerId ->
        CombatRemoveHelper(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis()
        )
    }
}

fun GameViewModel.modifyCombatModifier(target: BonusTarget, delta: Int) {
    val currentState = _uiState.value.gameState ?: return
    val currentCombat = currentState.combat ?: return

    val currentValue = when (target) {
        BonusTarget.HEROES -> currentCombat.heroModifier
        BonusTarget.MONSTER -> currentCombat.monsterModifier
    }
    val newValue = (currentValue + delta).coerceIn(-20, 20)

    sendPlayerEvent { playerId ->
        CombatSetModifier(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            target = target,
            value = newValue
        )
    }
}

fun GameViewModel.startCombat() {
    val playerId = myPlayerId ?: return
    sendPlayerEvent { pid ->
        CombatStart(
            eventId = UUID.randomUUID().toString(),
            actorId = pid,
            timestamp = System.currentTimeMillis(),
            mainPlayerId = playerId
        )
    }
}

fun GameViewModel.searchMonsters(query: String) {
    viewModelScope.launch {
        if (query.isBlank()) {
            _uiState.update { it.copy(monsterSearchResults = emptyList()) }
            return@launch
        }
        try {
            val client = GameClient()
            val result = client.searchMonsters(GameViewModel.SERVER_URL, query)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(monsterSearchResults = result.getOrElse { emptyList() })
                }
            }
        } catch (e: Exception) {
        }
    }
}

fun GameViewModel.requestCreateGlobalMonster(name: String, level: Int, modifier: Int, isUndead: Boolean) {
    val token = sessionManager?.getAuthToken()
    if (token == null) {
        _uiState.update {
            it.copy(error = MunchkinApp.context.getString(R.string.error_session_expired))
        }
        return
    }
    val user = _uiState.value.userProfile

    val monster = CatalogMonster(
        name = name,
        level = level,
        modifier = modifier,
        isUndead = isUndead,
        createdBy = user?.username
    )

    viewModelScope.launch {
        try {
            val client = GameClient()
            val result = client.addMonsterToCatalog(GameViewModel.SERVER_URL, monster, token)

            if (result.isSuccess) {
                val created = result.getOrNull()
                if (created != null) {
                    addMonster(created.name, created.level, created.modifier, created.isUndead)
                    _events.emit(GameUiEvent.ShowSuccess("Monstruo creado: ${created.name}"))
                }
            } else {
                _events.emit(GameUiEvent.ShowError("Error al guardar monstruo"))
            }
        } catch (e: Exception) {
            _events.emit(GameUiEvent.ShowError("Error: ${e.message}"))
        }
    }
}

fun GameViewModel.addMonster(name: String, level: Int, modifier: Int, isUndead: Boolean) {
    val clampedLevel = level.coerceIn(1, 20)
    val clampedModifier = modifier.coerceIn(-10, 10)
    sendPlayerEvent { playerId ->
        CombatAddMonster(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            monster = MonsterInstance(
                id = UUID.randomUUID().toString(),
                name = name,
                baseLevel = clampedLevel,
                flatModifier = clampedModifier,
                isUndead = isUndead
            )
        )
    }
}

fun GameViewModel.endCombat() {
    val currentGameState = _uiState.value.gameState ?: return
    val currentCombat = currentGameState.combat ?: return

    val result = CombatCalculator.calculateResult(currentCombat, currentGameState)

    sendPlayerEvent { playerId ->
        CombatEnd(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            outcome = result.outcome,
            levelsGained = result.totalLevels,
            treasuresGained = result.totalTreasures,
            helperLevelsGained = result.helperLevelsGained
        )
    }
}

fun GameViewModel.resolveRunAway(success: Boolean) {
    val currentGameState = _uiState.value.gameState ?: return
    val currentCombat = currentGameState.combat ?: return

    if (success) {
        sendPlayerEvent { playerId ->
            CombatEnd(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                outcome = CombatOutcome.ESCAPE,
                levelsGained = 0,
                treasuresGained = 0,
                helperLevelsGained = 0
            )
        }
    } else {
        sendPlayerEvent { playerId ->
            SetLevel(
                eventId = UUID.randomUUID().toString(),
                actorId = playerId,
                timestamp = System.currentTimeMillis(),
                targetPlayerId = currentCombat.mainPlayerId,
                newLevel = (currentGameState.players[currentCombat.mainPlayerId]?.level ?: 1) - 1
            )
        }
    }
}
