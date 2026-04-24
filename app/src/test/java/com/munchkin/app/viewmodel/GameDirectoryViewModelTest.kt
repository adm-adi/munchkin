package com.munchkin.app.viewmodel

import com.munchkin.app.data.GameDirectoryDataSource
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.HostedGame
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
class GameDirectoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun discoverGamesSuccessUpdatesState() = runTest(mainDispatcherRule.testDispatcher) {
        val games = listOf(
            DiscoveredGame(hostName = "Ana", joinCode = "ABCD12", playerCount = 2, maxPlayers = 6, wsUrl = "ws://test")
        )
        val repository = FakeGameDirectoryDataSource(discoverResult = Result.success(games))
        val viewModel = GameDirectoryViewModel(repository)

        viewModel.discoverGames()

        assertTrue(viewModel.uiState.value.isDiscovering)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDiscovering)
        assertEquals(games, viewModel.uiState.value.discoveredGames)
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, repository.discoverCalls)
    }

    @Test
    fun loadHostedGamesFailureSetsError() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeGameDirectoryDataSource(
            hostedResult = Result.failure(IllegalStateException("session expired"))
        )
        val viewModel = GameDirectoryViewModel(repository)

        viewModel.loadHostedGames()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoadingHostedGames)
        assertEquals("session expired", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.hostedGames.isEmpty())
        assertEquals(1, repository.hostedCalls)
    }

    @Test
    fun deleteHostedGameSuccessRemovesGame() = runTest(mainDispatcherRule.testDispatcher) {
        val hostedGames = listOf(
            HostedGame(gameId = "game-1", joinCode = "AAAA11", playerCount = 2, phase = "LOBBY", createdAt = 1L),
            HostedGame(gameId = "game-2", joinCode = "BBBB22", playerCount = 3, phase = "IN_GAME", createdAt = 2L)
        )
        val repository = FakeGameDirectoryDataSource(
            hostedResult = Result.success(hostedGames),
            deleteResult = Result.success(Unit)
        )
        val viewModel = GameDirectoryViewModel(repository)

        viewModel.loadHostedGames()
        advanceUntilIdle()
        viewModel.deleteHostedGame("game-1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoadingHostedGames)
        assertEquals(listOf(hostedGames[1]), viewModel.uiState.value.hostedGames)
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("game-1"), repository.deletedGameIds)
    }

    private class FakeGameDirectoryDataSource(
        var discoverResult: Result<List<DiscoveredGame>> = Result.success(emptyList()),
        var hostedResult: Result<List<HostedGame>> = Result.success(emptyList()),
        var deleteResult: Result<Unit> = Result.success(Unit)
    ) : GameDirectoryDataSource {
        var discoverCalls = 0
            private set
        var hostedCalls = 0
            private set
        val deletedGameIds = mutableListOf<String>()

        override suspend fun discoverGames(): Result<List<DiscoveredGame>> {
            discoverCalls += 1
            return discoverResult
        }

        override suspend fun getHostedGames(): Result<List<HostedGame>> {
            hostedCalls += 1
            return hostedResult
        }

        override suspend fun deleteHostedGame(gameId: String): Result<Unit> {
            deletedGameIds += gameId
            return deleteResult
        }
    }
}
