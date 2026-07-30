package com.munchkin.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.core.CombatCalculator
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.CombatAddHelper
import com.munchkin.app.core.CombatAddMonster
import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.CombatOutcome
import com.munchkin.app.core.CombatRemoveHelper
import com.munchkin.app.core.CombatSetModifier
import com.munchkin.app.core.CombatStart
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.core.BonusTarget
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
    // Munchkin bonus items have no upper bound. v2.19.18 removed the ±20 cap on the
    // server but left this client-side clamp in place, so the UI still refused to go
    // past ±20 and the fix never actually reached the user. The wide bound here only
    // keeps the value a sane serialisable integer, matching MODIFIER_LIMIT on the server.
    val newValue = (currentValue + delta).coerceIn(-9999, 9999)

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
                    _events.emit(GameUiEvent.ShowSuccess(
                        MunchkinApp.context.getString(R.string.monster_created, created.name)
                    ))
                }
            } else {
                _events.emit(GameUiEvent.ShowError(
                    MunchkinApp.context.getString(R.string.error_save_monster)
                ))
            }
        } catch (e: Exception) {
            _events.emit(GameUiEvent.ShowError(getFriendlyErrorMessage(e)))
        }
    }
}

fun GameViewModel.addMonster(
    name: String,
    level: Int,
    modifier: Int,
    isUndead: Boolean,
    badStuff: String = ""
) {
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
                isUndead = isUndead,
                // Losing a fight means suffering this monster's Bad Stuff, so it
                // has to travel with the monster into the combat.
                badStuff = badStuff
            )
        )
    }
}

fun GameViewModel.endCombat() {
    val currentGameState = _uiState.value.gameState ?: return
    val currentCombat = currentGameState.combat ?: return

    val result = CombatCalculator.calculateResult(currentCombat, currentGameState)

    // Same as a failed escape: losing the fight means the monster's Bad Stuff
    // applies, and only the card knows what that is.
    if (result.outcome == CombatOutcome.LOSE) {
        _pendingBadStuff.value = currentCombat.monsters
    }

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

/**
 * Resolves a run-away attempt.
 *
 * Failing used to cost exactly one level, which is a rule Munchkin does not have.
 * Failing to escape means the monster's **Bad Stuff** applies, and that is whatever
 * the card says — lose your armour, discard cards, lose your class, drop two
 * levels, die. A flat -1 was wrong in most cases, and it also contradicted the
 * app's own handling of a straight defeat, which applied no penalty at all.
 *
 * Both paths now end the combat and hand the player the Bad Stuff text to resolve
 * at the table, which is the same division of labour the ability reminders use.
 */
fun GameViewModel.resolveRunAway(success: Boolean) {
    val currentGameState = _uiState.value.gameState ?: return
    val currentCombat = currentGameState.combat ?: return

    if (!success) {
        // Captured before the combat is cleared, so the prompt can name the
        // monsters the player just failed to escape.
        _pendingBadStuff.value = currentCombat.monsters
    }

    sendPlayerEvent { playerId ->
        CombatEnd(
            eventId = UUID.randomUUID().toString(),
            actorId = playerId,
            timestamp = System.currentTimeMillis(),
            outcome = if (success) CombatOutcome.ESCAPE else CombatOutcome.LOSE,
            levelsGained = 0,
            treasuresGained = 0,
            helperLevelsGained = 0
        )
    }
}

/** Dismisses the Bad Stuff prompt once the player has applied it at the table. */
fun GameViewModel.dismissBadStuff() {
    _pendingBadStuff.value = emptyList()
}
