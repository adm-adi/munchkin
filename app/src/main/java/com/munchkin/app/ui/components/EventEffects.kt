package com.munchkin.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId

/**
 * Observes authoritative game state deltas to trigger local haptic/audio feedback
 * for level changes and local turn starts.
 */
@Composable
fun EventEffects(
    gameState: GameState,
    myPlayerId: PlayerId
) {
    var previousLevels by remember(gameState.gameId) {
        mutableStateOf(gameState.players.mapValues { it.value.level })
    }
    var previousTurnPlayer by remember(gameState.gameId) {
        mutableStateOf(gameState.turnPlayerId)
    }

    LaunchedEffect(gameState.players) {
        val currentLevels = gameState.players.mapValues { it.value.level }
        if (previousLevels.isNotEmpty()) {
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

    LaunchedEffect(gameState.turnPlayerId) {
        if (gameState.turnPlayerId != previousTurnPlayer && gameState.turnPlayerId == myPlayerId) {
            SoundManager.playTurnStart()
        }
        previousTurnPlayer = gameState.turnPlayerId
    }
}
