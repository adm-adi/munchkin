package com.munchkin.app.viewmodel

import com.munchkin.app.core.BonusTarget
import com.munchkin.app.core.CombatAddBonus
import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.CombatOutcome
import com.munchkin.app.core.CombatRemoveBonus
import com.munchkin.app.core.CombatState
import com.munchkin.app.core.CombatUpdateMonster
import com.munchkin.app.core.EndTurn
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerRoll
import com.munchkin.app.core.PlayerState
import com.munchkin.app.data.SavedGame
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun baseState(
        hostId: PlayerId = PlayerId("p1"),
        combat: CombatState? = null,
        seq: Long = 5L,
        players: Map<PlayerId, PlayerState> = mapOf(
            PlayerId("p1") to PlayerState(PlayerId("p1"), "Hero", level = 2, gearBonus = 1),
            PlayerId("p2") to PlayerState(PlayerId("p2"), "Ally", level = 3, gearBonus = 0)
        )
    ) = GameState(
        gameId = GameId("game-1"),
        joinCode = "CODE99",
        seq = seq,
        hostId = hostId,
        players = players,
        combat = combat,
        phase = GamePhase.IN_GAME
    )

    @Test
    fun isHost_isDerivedFromHostId_andUpdatesOnTransfer() = runTest(mainDispatcherRule.testDispatcher) {
        val state = baseState(hostId = PlayerId("p1"))
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, state, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        assertTrue(vm.isHost.value)

        vm.transferHost(PlayerId("p2"))
        advanceUntilIdle()
        assertEquals(listOf(PlayerId("p2")), session.transferHostTargets)

        session.mutableGameState.value = state.copy(hostId = PlayerId("p2"))
        advanceUntilIdle()
        assertFalse(vm.isHost.value)
    }

    @Test
    fun endCombat_lossIsNonOptimistic_showsBadStuffOnlyAfterServerConfirmsLoss() = runTest(mainDispatcherRule.testDispatcher) {
        val monster = MonsterInstance(id = "m1", name = "Plutonium Dragon", baseLevel = 20)
        val combat = CombatState(mainPlayerId = PlayerId("p1"), monsters = listOf(monster))
        val initialState = baseState(combat = combat, seq = 42L)
        val session = FakeSessionConnection(initialState, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, initialState, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.endCombat()
        advanceUntilIdle()

        // Non-optimistic: Bad Stuff must NOT be shown prior to server clearing combat.
        assertTrue(vm.isCombatResolving.value)
        assertTrue(vm.pendingBadStuff.value.isEmpty())
        val sentEnd = session.sentEvents.filterIsInstance<CombatEnd>().single()
        assertEquals(42L, sentEnd.expectedSeq)
        assertEquals(CombatOutcome.LOSE, sentEnd.outcome)
        assertFalse(sentEnd.cancelled)

        // Server confirms combat ended without main player gaining levels/treasures.
        session.mutableGameState.value = initialState.copy(combat = null, seq = 43L)
        advanceUntilIdle()

        assertFalse(vm.isCombatResolving.value)
        assertEquals(listOf(monster), vm.pendingBadStuff.value)
    }

    @Test
    fun endCombat_serverFlipsToWin_doesNotShowBadStuff() = runTest(mainDispatcherRule.testDispatcher) {
        val monster = MonsterInstance(id = "m1", name = "Orc", baseLevel = 10)
        val combat = CombatState(mainPlayerId = PlayerId("p1"), monsters = listOf(monster))
        val initialState = baseState(combat = combat, seq = 10L)
        val session = FakeSessionConnection(initialState, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, initialState, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.endCombat()
        advanceUntilIdle()
        assertTrue(vm.isCombatResolving.value)

        // Server resolves combat with a concurrent WIN (main player level increased).
        val boostedPlayers = initialState.players.toMutableMap().apply {
            this[PlayerId("p1")] = this[PlayerId("p1")]!!.copy(level = 3, treasures = 2)
        }
        session.mutableGameState.value = initialState.copy(combat = null, players = boostedPlayers, seq = 11L)
        advanceUntilIdle()

        assertFalse(vm.isCombatResolving.value)
        assertTrue(vm.pendingBadStuff.value.isEmpty())
    }

    @Test
    fun endCombat_errorResetsResolvingFlagWithoutShowingBadStuff() = runTest(mainDispatcherRule.testDispatcher) {
        val monster = MonsterInstance(id = "m1", name = "Dragon", baseLevel = 20)
        val combat = CombatState(mainPlayerId = PlayerId("p1"), monsters = listOf(monster))
        val initialState = baseState(combat = combat, seq = 7L)
        val session = FakeSessionConnection(initialState, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, initialState, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.endCombat()
        advanceUntilIdle()
        assertTrue(vm.isCombatResolving.value)

        session.mutableErrors.emit("CONFLICT")
        advanceUntilIdle()

        assertFalse(vm.isCombatResolving.value)
        assertTrue(vm.pendingBadStuff.value.isEmpty())
    }

    @Test
    fun combatParity_updateMonster_and_tempBonuses_emitExpectedEvents() = runTest(mainDispatcherRule.testDispatcher) {
        val monster = MonsterInstance(id = "m1", name = "Goblin", baseLevel = 1)
        val combat = CombatState(mainPlayerId = PlayerId("p1"), monsters = listOf(monster))
        val initialState = baseState(combat = combat)
        val session = FakeSessionConnection(initialState, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, initialState, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.updateMonster("m1", 5)
        vm.addTempBonus(BonusTarget.HEROES, "Potion", 3)
        vm.removeTempBonus("b1")
        vm.rollDice(9)
        advanceUntilIdle()

        assertEquals(5, session.sentEvents.filterIsInstance<CombatUpdateMonster>().single().monster.flatModifier)
        assertEquals(3, session.sentEvents.filterIsInstance<CombatAddBonus>().single().bonus.amount)
        assertEquals("b1", session.sentEvents.filterIsInstance<CombatRemoveBonus>().single().bonusId)
        assertEquals(6, session.sentEvents.filterIsInstance<PlayerRoll>().single().result)
    }

    @Test
    fun multiWinner_and_recordGameOverRetryOnFailure() = runTest(mainDispatcherRule.testDispatcher) {
        val stateAtTen = baseState(
            players = mapOf(
                PlayerId("p1") to PlayerState(PlayerId("p1"), "Hero1", level = 10, reachedMaxLevelViaCombat = true),
                PlayerId("p2") to PlayerState(PlayerId("p2"), "Hero2", level = 10, reachedMaxLevelViaCombat = true)
            )
        )
        val session = FakeSessionConnection(stateAtTen, PlayerId("p1")).apply {
            sendGameOverResult = Result.failure(IllegalStateException("Network failed"))
        }
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, stateAtTen, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        assertEquals(listOf(PlayerId("p1"), PlayerId("p2")), vm.pendingWinners.value.map { it.playerId })

        // Failure must NOT mark hasRecordedGame = true; retry must succeed!
        vm.confirmWin(PlayerId("p1"))
        advanceUntilIdle()
        assertEquals(2, vm.pendingWinners.value.size)

        session.sendGameOverResult = Result.success(Unit)
        vm.confirmWin(PlayerId("p1"))
        advanceUntilIdle()
        assertTrue(vm.pendingWinners.value.isEmpty())
    }

    @Test
    fun endTurn_enforcesDoubleTapCooldown() = runTest(mainDispatcherRule.testDispatcher) {
        var now = 1000L
        val state = baseState()
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, state, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver(),
            clockMs = { now }
        )
        advanceUntilIdle()

        vm.endTurn()
        now += 100L
        vm.endTurn()
        advanceUntilIdle()
        assertEquals(1, session.sentEvents.filterIsInstance<EndTurn>().size)

        now += 500L
        vm.endTurn()
        advanceUntilIdle()
        assertEquals(2, session.sentEvents.filterIsInstance<EndTurn>().size)
    }

    @Test
    fun leaveGame_clearsSessionAndSavedGame() = runTest(mainDispatcherRule.testDispatcher) {
        val state = baseState()
        val session = FakeSessionConnection(state, PlayerId("p1"))
        val savedStore = FakeSavedGameStore()
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, state, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            savedStore,
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.leaveGame()
        advanceUntilIdle()

        assertEquals(1, session.disconnectCount)
        assertEquals(1, session.closeCount)
        assertEquals(1, savedStore.deleteAllCalls)
        assertEquals(null, vm.gameState.value)
        assertEquals(null, vm.myPlayerId.value)
    }

    @Test
    fun deleteGame_failureEmitsError_andSuccessCleansSession() = runTest(mainDispatcherRule.testDispatcher) {
        val state = baseState(hostId = PlayerId("p1"))
        val session = FakeSessionConnection(state, PlayerId("p1")).apply {
            sendDeleteGameResult = Result.failure(IllegalStateException("Server down"))
        }
        val savedStore = FakeSavedGameStore()
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(session, state, PlayerId("p1"))
        }
        val events = mutableListOf<GameUiEvent>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.events.collect { events.add(it) }
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            savedStore,
            FakeRemoteGateway(),
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.deleteGame()
        advanceUntilIdle()
        assertTrue(events.lastOrNull() is GameUiEvent.ShowError)
        assertEquals(state, vm.gameState.value)

        session.sendDeleteGameResult = Result.success(Unit)
        vm.deleteGame()
        advanceUntilIdle()

        assertTrue(events.lastOrNull() is GameUiEvent.ShowSuccess)
        assertEquals(1, savedStore.deleteAllCalls)
        assertEquals(1, session.closeCount)
        assertEquals(null, vm.gameState.value)
        collectJob.cancel()
    }

    @Test
    fun checkReconnection_nudgesActiveSession_orResumesWhenNull() = runTest(mainDispatcherRule.testDispatcher) {
        val state = baseState()
        val activeSession = FakeSessionConnection(
            initialState = state,
            initialPlayerId = PlayerId("p1"),
            initialConnectionState = ConnectionState.RECONNECTING
        )
        val gateway = FakeRemoteGateway()
        val coordinator = GameSessionCoordinator().apply {
            attachActiveSession(activeSession, state, PlayerId("p1"))
        }
        val vm = GameSessionViewModel(
            FakeSessionStore(),
            FakeSavedGameStore(),
            gateway,
            coordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm.checkReconnection()
        advanceUntilIdle()
        assertEquals(1, activeSession.nudgeCount)

        // When disconnected/no active session, checkReconnection falls back to resumeSavedGameSession.
        coordinator.clearActiveSession(closeClient = true)
        val savedStore = FakeSavedGameStore().apply {
            saveGame(state, PlayerId("p1"), isHost = true)
        }
        val resumedSession = FakeSessionConnection(state, PlayerId("p1"))
        val reconnectGateway = FakeRemoteGateway().apply {
            nextConnectConnection = { Result.success(resumedSession to state) }
        }
        val disconnectedCoordinator = GameSessionCoordinator().apply {
            setSavedGame(SavedGame(state, PlayerId("p1"), isHost = true, savedAt = 123L))
        }
        val vm2 = GameSessionViewModel(
            FakeSessionStore(),
            savedStore,
            reconnectGateway,
            disconnectedCoordinator,
            FakeStringResolver()
        )
        advanceUntilIdle()

        vm2.checkReconnection()
        advanceUntilIdle()

        assertEquals(ServerConfig.WS_URL, reconnectGateway.lastConnectUrl)
        assertEquals("CODE99", reconnectGateway.lastConnectJoinCode)
    }
}
