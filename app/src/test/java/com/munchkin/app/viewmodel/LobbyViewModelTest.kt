package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.data.SavedGame
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.network.UserProfile
import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LobbyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun buildVm(
        sessionStore: FakeSessionStore = FakeSessionStore(),
        savedGameStore: FakeSavedGameStore = FakeSavedGameStore(),
        updateGateway: FakeUpdateGateway = FakeUpdateGateway(),
        prefs: FakeLargeNumbersPrefs = FakeLargeNumbersPrefs(),
        gateway: FakeRemoteGateway = FakeRemoteGateway(),
        coordinator: GameSessionCoordinator = GameSessionCoordinator()
    ): LobbyViewModel {
        return LobbyViewModel(
            sessionStore = sessionStore,
            savedGameStore = savedGameStore,
            updateGateway = updateGateway,
            largeNumbersPrefs = prefs,
            remoteGateway = gateway,
            sessionCoordinator = coordinator,
            stringResolver = FakeStringResolver()
        )
    }

    @Test
    fun joinGame_parsesJoinCode_andAlwaysUsesAuthoritativeServerUrl() = runTest(mainDispatcherRule.testDispatcher) {
        val state = GameState(gameId = GameId("g1"), joinCode = "ABC123", hostId = PlayerId("h1"))
        val session = FakeSessionConnection(state, PlayerId("p2"))
        val gateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(session to state) }
        }
        val coordinator = GameSessionCoordinator()
        val vm = buildVm(gateway = gateway, coordinator = coordinator)

        vm.joinGame("munchkin://join/abc123", "Hero", 1, Gender.F)
        advanceUntilIdle()

        assertEquals(ServerConfig.WS_URL, gateway.lastConnectUrl)
        assertEquals("ABC123", gateway.lastConnectJoinCode)
        assertEquals(state, coordinator.gameState.value)
        assertEquals(PlayerId("p2"), coordinator.myPlayerId.value)
    }

    @Test
    fun joinGame_blankNameWithoutUser_usesLocalizedDefaultPlayerName() = runTest(mainDispatcherRule.testDispatcher) {
        val state = GameState(gameId = GameId("g1"), joinCode = "ABC123", hostId = PlayerId("h1"))
        val session = FakeSessionConnection(state, PlayerId("p2"))
        val gateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(session to state) }
        }
        val vm = buildVm(gateway = gateway)

        vm.joinGame("ABC123", "   ", 0, Gender.M)
        advanceUntilIdle()

        assertEquals("res:${R.string.default_player_name}", gateway.lastConnectMeta?.name)
    }

    @Test
    fun joinGame_invalidCode_setsErrorWithoutConnecting() = runTest(mainDispatcherRule.testDispatcher) {
        val gateway = FakeRemoteGateway()
        val vm = buildVm(gateway = gateway)

        vm.joinGame("bad!", "Hero", 1, Gender.M)
        advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertEquals(null, gateway.lastConnectJoinCode)
    }

    @Test
    fun resumeHostedGame_preservesUserGender() = runTest(mainDispatcherRule.testDispatcher) {
        val state = GameState(
            gameId = GameId("g1"),
            joinCode = "HOST99",
            hostId = PlayerId("p1"),
            phase = GamePhase.IN_GAME
        )
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val gateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(session to state) }
        }
        val coordinator = GameSessionCoordinator().apply {
            setUserProfile(UserProfile(id = "u1", username = "Queen", email = "q@q.com", avatarId = 0, gender = Gender.F))
        }
        val vm = buildVm(gateway = gateway, coordinator = coordinator)

        vm.resumeHostedGame("HOST99")
        advanceUntilIdle()

        assertEquals(Gender.F, gateway.lastConnectMeta?.gender)
        assertEquals(state, coordinator.gameState.value)
    }

    @Test
    fun resumeHostedGame_withoutUserProfile_usesLocalizedDefaultHostName() = runTest(mainDispatcherRule.testDispatcher) {
        val state = GameState(
            gameId = GameId("g1"),
            joinCode = "HOST99",
            hostId = PlayerId("p1"),
            phase = GamePhase.IN_GAME
        )
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val gateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(session to state) }
        }
        val vm = buildVm(gateway = gateway)

        vm.resumeHostedGame("HOST99")
        advanceUntilIdle()

        assertEquals("res:${R.string.default_host_name}", gateway.lastConnectMeta?.name)
    }

    @Test
    fun createGame_guardsDoubleTapWhileInFlight() = runTest(mainDispatcherRule.testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var createCalls = 0
        val state = GameState(gameId = GameId("g1"), joinCode = "NEW123", hostId = PlayerId("p1"))
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val gateway = FakeRemoteGateway().apply {
            nextCreateConnection = {
                createCalls++
                gate.await()
                Result.success(session to state)
            }
        }
        val vm = buildVm(gateway = gateway)

        vm.createGame("Host", 0, Gender.M)
        vm.createGame("Host", 0, Gender.M)
        advanceUntilIdle()
        assertEquals(1, createCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, createCalls)
    }

    @Test
    fun downloadUpdate_failure_emitsErrorEvent() = runTest(mainDispatcherRule.testDispatcher) {
        val updateGateway = FakeUpdateGateway().apply {
            nextCheckResult = UpdateResult.UpdateAvailable(
                UpdateInfo(version = "9.9.9", releaseNotes = "", downloadUrl = "https://example.com/app.apk", fileSize = 100L)
            )
            nextDownloadResult = Result.failure(IllegalStateException("SHA mismatch"))
        }
        val coordinator = GameSessionCoordinator()
        val vm = buildVm(updateGateway = updateGateway, coordinator = coordinator)

        val emittedEvents = mutableListOf<GameUiEvent>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.events.collect { emittedEvents.add(it) }
        }

        vm.checkForUpdates()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()

        assertTrue(emittedEvents.any { it is GameUiEvent.ShowError })
        collectJob.cancel()
    }

    @Test
    fun setLargeNumbersMode_updatesFlow() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = buildVm()
        vm.setLargeNumbersMode(true)
        assertTrue(vm.largeNumbersMode.first())
    }

    @Test
    fun resumeSavedGame_connectsUsingSavedStatePlayerMeta() = runTest(mainDispatcherRule.testDispatcher) {
        val savedPlayerId = PlayerId("saved-p1")
        val savedState = GameState(
            gameId = GameId("g-saved"),
            joinCode = "SAVE42",
            hostId = savedPlayerId,
            players = mapOf(
                savedPlayerId to PlayerState(savedPlayerId, "Veteran", avatarId = 3, gender = Gender.F)
            ),
            phase = GamePhase.IN_GAME
        )
        val savedStore = FakeSavedGameStore().apply {
            saveGame(savedState, savedPlayerId, isHost = true)
        }
        val session = FakeSessionConnection(savedState, savedPlayerId)
        val gateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(session to savedState) }
        }
        val coordinator = GameSessionCoordinator().apply {
            setSavedGame(SavedGame(savedState, savedPlayerId, isHost = true, savedAt = 100L))
        }
        val vm = buildVm(savedGameStore = savedStore, gateway = gateway, coordinator = coordinator)

        vm.resumeSavedGame()
        advanceUntilIdle()

        assertEquals(ServerConfig.WS_URL, gateway.lastConnectUrl)
        assertEquals("SAVE42", gateway.lastConnectJoinCode)
        assertEquals(Gender.F, gateway.lastConnectMeta?.gender)
        assertEquals("Veteran", gateway.lastConnectMeta?.name)
        assertEquals(savedState, coordinator.gameState.value)
        assertEquals(savedPlayerId, coordinator.myPlayerId.value)
    }
}
