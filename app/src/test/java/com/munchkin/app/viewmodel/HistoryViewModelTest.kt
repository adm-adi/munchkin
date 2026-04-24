package com.munchkin.app.viewmodel

import com.munchkin.app.data.HistoryDataSource
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.LeaderboardEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadHistorySuccessUpdatesState() = runTest(mainDispatcherRule.testDispatcher) {
        val history = listOf(GameHistoryItem("game-1", endedAt = 123L, winnerId = "player-1", playerCount = 2))
        val repository = FakeHistoryDataSource(historyResult = Result.success(history))
        val viewModel = HistoryViewModel(repository)

        viewModel.loadHistory()

        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(history, viewModel.uiState.value.gameHistory)
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, repository.historyCalls)
    }

    @Test
    fun loadLeaderboardFailureSetsError() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeHistoryDataSource(
            leaderboardResult = Result.failure(IllegalStateException("leaderboard unavailable"))
        )
        val viewModel = HistoryViewModel(repository)

        viewModel.loadLeaderboard()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("leaderboard unavailable", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.leaderboard.isEmpty())
        assertEquals(1, repository.leaderboardCalls)
    }

    @Test
    fun clearResetsState() = runTest(mainDispatcherRule.testDispatcher) {
        val leaderboard = listOf(LeaderboardEntry("player-1", "Ana", avatarId = 0, wins = 3))
        val repository = FakeHistoryDataSource(leaderboardResult = Result.success(leaderboard))
        val viewModel = HistoryViewModel(repository)

        viewModel.loadLeaderboard()
        advanceUntilIdle()
        viewModel.clear()

        assertEquals(HistoryUiState(), viewModel.uiState.value)
    }

    private class FakeHistoryDataSource(
        var historyResult: Result<List<GameHistoryItem>> = Result.success(emptyList()),
        var leaderboardResult: Result<List<LeaderboardEntry>> = Result.success(emptyList())
    ) : HistoryDataSource {
        var historyCalls = 0
            private set
        var leaderboardCalls = 0
            private set

        override suspend fun loadHistory(): Result<List<GameHistoryItem>> {
            historyCalls += 1
            return historyResult
        }

        override suspend fun loadLeaderboard(): Result<List<LeaderboardEntry>> {
            leaderboardCalls += 1
            return leaderboardResult
        }
    }
}
