package com.munchkin.app.ui.components

import androidx.compose.runtime.*
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId

@Composable
fun EventEffects(
    gameState: GameState,
    myPlayerId: PlayerId
) {
    // Track previous states to detect changes
    var previousLevels by remember { mutableStateOf<Map<PlayerId, Int>>(emptyMap()) }
    var previousCombatOutcome by remember(gameState.combat) { mutableStateOf<Boolean?>(null) } // null=active/none, true=win, false=loss
    var previousTurnPlayer by remember { mutableStateOf<PlayerId?>(null) }

    // Init previous levels on first composition
    LaunchedEffect(Unit) {
        previousLevels = gameState.players.mapValues { it.value.level }
        previousTurnPlayer = gameState.turnPlayerId
    }

    // 1. Level Changes
    LaunchedEffect(gameState.players) {
        val currentLevels = gameState.players.mapValues { it.value.level }
        if (previousLevels.isNotEmpty()) {
            // Check for level up/down
            gameState.players.forEach { (pid, player) ->
                val oldLevel = previousLevels[pid]
                if (oldLevel != null) {
                    if (player.level > oldLevel) {
                        SoundManager.playLevelUp()
                    } else if (player.level < oldLevel) {
                        SoundManager.playLevelDown()
                    }
                }
            }
        }
        previousLevels = currentLevels
    }

    // 2. Combat Outcome (Victory/Defeat)
    // We detect when combat ends or changes phase/result
    // Actually, combat result is calculated in CombatScreen. 
    // State doesn't store "Win/Loss" effectively unless we check game over/level changes (handled above)
    // or if combat is resolved. 
    // But typically "Victory" sound is for winning combat.
    // If we want detailed combat outcome, we need to inspect events or changes.
    // For now, let's rely on manual triggers in CombatScreen?
    // In CombatScreen line 344 (Step 8164), we have "VICTORIA! (Terminar)" button. 
    // Clicking it ends combat. We can play sound THERE.
    // AND "ASUMIR DERROTA".
    // Global broadcasting of victory sound logic might be tricky without events.
    // BUT Level Up often accompanies victory.
    // So Level Up sound covers it mostly.
    
    // 3. Turn Start
    LaunchedEffect(gameState.turnPlayerId) {
        if (gameState.turnPlayerId != previousTurnPlayer && gameState.turnPlayerId == myPlayerId) {
            SoundManager.playTurnStart()
        }
        previousTurnPlayer = gameState.turnPlayerId
    }
}
