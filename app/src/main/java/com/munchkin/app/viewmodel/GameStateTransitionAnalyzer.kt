package com.munchkin.app.viewmodel

import androidx.annotation.StringRes
import com.munchkin.app.R
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.LogType
import com.munchkin.app.core.PlayerId

class GameStateTransitionAnalyzer {
    fun analyze(
        previous: GameState?,
        current: GameState,
        isHost: Boolean,
        hasRecordedGame: Boolean,
        currentPendingWinnerId: PlayerId?
    ): GameStateTransitionResult {
        val logs = buildList {
            if (previous != null) {
                current.players.forEach { (id, player) ->
                    val previousPlayer = previous.players[id]
                    if (previousPlayer != null && previousPlayer.level != player.level) {
                        if (player.level > previousPlayer.level) {
                            add(
                                GameLogMessage(
                                    messageResId = R.string.log_level_up_format,
                                    args = listOf(player.name, player.level),
                                    type = LogType.LEVEL_UP
                                )
                            )
                        } else {
                            add(
                                GameLogMessage(
                                    messageResId = R.string.log_level_down_format,
                                    args = listOf(player.name, player.level),
                                    type = LogType.INFO
                                )
                            )
                        }
                    }
                }

                if (previous.combat != null && current.combat == null) {
                    add(
                        GameLogMessage(
                            messageResId = R.string.log_combat_finished,
                            type = LogType.combat
                        )
                    )
                }
            }
        }

        val pendingWinnerId = pendingWinnerId(
            current = current,
            isHost = isHost,
            hasRecordedGame = hasRecordedGame,
            currentPendingWinnerId = currentPendingWinnerId
        )

        return GameStateTransitionResult(
            logs = logs,
            pendingWinnerId = pendingWinnerId,
            markGameRecorded = current.phase == GamePhase.FINISHED && current.winnerId != null,
            navigation = if (previous?.phase == GamePhase.LOBBY && current.phase == GamePhase.IN_GAME) {
                GameDestination.BOARD
            } else {
                null
            }
        )
    }

    private fun pendingWinnerId(
        current: GameState,
        isHost: Boolean,
        hasRecordedGame: Boolean,
        currentPendingWinnerId: PlayerId?
    ): PlayerId? {
        if (!isHost || current.phase != GamePhase.IN_GAME || hasRecordedGame) {
            return currentPendingWinnerId
        }

        val winner = current.players.values.find { it.level >= current.settings.maxLevel }
        return winner?.playerId
    }
}

data class GameStateTransitionResult(
    val logs: List<GameLogMessage> = emptyList(),
    val pendingWinnerId: PlayerId?,
    val markGameRecorded: Boolean = false,
    val navigation: GameDestination? = null
)

data class GameLogMessage(
    @StringRes val messageResId: Int,
    val args: List<Any> = emptyList(),
    val type: LogType = LogType.INFO
)
