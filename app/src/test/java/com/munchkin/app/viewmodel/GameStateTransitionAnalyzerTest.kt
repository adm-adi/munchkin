package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.core.CombatState
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameSettings
import com.munchkin.app.core.GameState
import com.munchkin.app.core.LogType
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTransitionAnalyzerTest {
    private val analyzer = GameStateTransitionAnalyzer()
    private val hostId = PlayerId("host")
    private val playerId = PlayerId("player-1")

    @Test
    fun logsLevelChangesAndCombatFinish() {
        val previous = gameState(
            player = player(level = 1),
            combat = CombatState(mainPlayerId = playerId),
            phase = GamePhase.IN_GAME
        )
        val current = previous.copy(
            players = mapOf(playerId to player(level = 2)),
            combat = null
        )

        val result = analyzer.analyze(
            previous = previous,
            current = current,
            isHost = false,
            hasRecordedGame = false,
            currentPendingWinnerId = null
        )

        assertEquals(
            listOf(
                GameLogMessage(
                    messageResId = R.string.log_level_up_format,
                    args = listOf("Ana", 2),
                    type = LogType.LEVEL_UP
                ),
                GameLogMessage(
                    messageResId = R.string.log_combat_finished,
                    type = LogType.combat
                )
            ),
            result.logs
        )
    }

    @Test
    fun hostGetsPendingWinnerWhenPlayerReachesMaxLevel() {
        val current = gameState(
            player = player(level = 10),
            phase = GamePhase.IN_GAME
        )

        val result = analyzer.analyze(
            previous = null,
            current = current,
            isHost = true,
            hasRecordedGame = false,
            currentPendingWinnerId = null
        )

        assertEquals(playerId, result.pendingWinnerId)
    }

    @Test
    fun pendingWinnerClearsWhenPlayerDropsBelowMaxLevel() {
        val current = gameState(
            player = player(level = 9),
            phase = GamePhase.IN_GAME
        )

        val result = analyzer.analyze(
            previous = null,
            current = current,
            isHost = true,
            hasRecordedGame = false,
            currentPendingWinnerId = playerId
        )

        assertNull(result.pendingWinnerId)
    }

    @Test
    fun lobbyToInGameTransitionNavigatesToBoard() {
        val previous = gameState(
            player = player(level = 1),
            phase = GamePhase.LOBBY
        )
        val current = previous.copy(phase = GamePhase.IN_GAME)

        val result = analyzer.analyze(
            previous = previous,
            current = current,
            isHost = false,
            hasRecordedGame = false,
            currentPendingWinnerId = null
        )

        assertEquals(GameDestination.BOARD, result.navigation)
    }

    @Test
    fun finishedGameWithWinnerMarksRecorded() {
        val current = gameState(
            player = player(level = 10),
            phase = GamePhase.FINISHED,
            winnerId = playerId
        )

        val result = analyzer.analyze(
            previous = null,
            current = current,
            isHost = true,
            hasRecordedGame = false,
            currentPendingWinnerId = null
        )

        assertTrue(result.markGameRecorded)
        assertNull(result.pendingWinnerId)
    }

    @Test
    fun alreadyRecordedGameDoesNotChangePendingWinner() {
        val current = gameState(
            player = player(level = 10),
            phase = GamePhase.IN_GAME
        )

        val result = analyzer.analyze(
            previous = null,
            current = current,
            isHost = true,
            hasRecordedGame = true,
            currentPendingWinnerId = playerId
        )

        assertFalse(result.markGameRecorded)
        assertEquals(playerId, result.pendingWinnerId)
    }

    private fun gameState(
        player: PlayerState,
        phase: GamePhase = GamePhase.LOBBY,
        combat: CombatState? = null,
        winnerId: PlayerId? = null
    ): GameState {
        return GameState(
            gameId = GameId("game-1"),
            joinCode = "ABCD12",
            hostId = hostId,
            players = mapOf(player.playerId to player),
            combat = combat,
            phase = phase,
            winnerId = winnerId,
            settings = GameSettings(maxLevel = 10)
        )
    }

    private fun player(level: Int): PlayerState {
        return PlayerState(
            playerId = playerId,
            name = "Ana",
            level = level
        )
    }
}
