package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.network.AuthSuccessMessage
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.ProfileUpdatedMessage
import com.munchkin.app.network.ServerErrorException
import com.munchkin.app.network.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun updateProfile_savesRotatedToken() = runTest(mainDispatcherRule.testDispatcher) {
        val sessionStore = FakeSessionStore().apply {
            user = UserProfile(id = "u1", username = "Alice", email = "a@b.c", avatarId = 1, gender = Gender.F)
            tokenValue = "old-token"
        }
        val coordinator = GameSessionCoordinator()
        val gateway = FakeRemoteGateway().apply {
            nextUpdateProfile = {
                Result.success(
                    ProfileUpdatedMessage(
                        user = UserProfile(id = "u1", username = "Alice2", email = "a@b.c", avatarId = 2, gender = Gender.F),
                        token = "rotated-token"
                    )
                )
            }
        }
        val vm = AuthViewModel(sessionStore, gateway, coordinator, FakeStringResolver())

        vm.updateProfile(username = "Alice2", avatarId = 2)
        advanceUntilIdle()

        assertEquals("rotated-token", sessionStore.getAuthToken())
        assertEquals("Alice2", vm.userProfile.value?.username)
        assertNull(vm.error.value)
    }

    @Test
    fun deleteAccount_clearsSessionAndEmitsLocalizedSuccess() = runTest(mainDispatcherRule.testDispatcher) {
        val sessionStore = FakeSessionStore().apply {
            user = UserProfile(id = "u1", username = "Bob", email = "b@b.c", avatarId = 0)
            tokenValue = "tok"
        }
        val coordinator = GameSessionCoordinator()
        val emitted = mutableListOf<GameUiEvent>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.events.collect { emitted.add(it) }
        }
        val gateway = FakeRemoteGateway().apply {
            nextDeleteAccount = { Result.success(Unit) }
        }
        val vm = AuthViewModel(sessionStore, gateway, coordinator, FakeStringResolver())

        vm.deleteAccount("secret")
        advanceUntilIdle()

        assertEquals(1, sessionStore.clearSessionCalls)
        assertNull(vm.userProfile.value)
        assertNull(coordinator.userProfile.value)
        assertTrue(emitted.contains(GameUiEvent.ShowSuccess("res:${R.string.account_deleted_success}")))
        collectJob.cancel()
    }

    @Test
    fun loadHistory_and_loadLeaderboard_setExplicitErrorOnFailure() = runTest(mainDispatcherRule.testDispatcher) {
        val sessionStore = FakeSessionStore().apply {
            user = UserProfile(id = "u1", username = "Bob", email = "b@b.c", avatarId = 0)
            tokenValue = "tok"
        }
        val coordinator = GameSessionCoordinator()
        val gateway = FakeRemoteGateway().apply {
            nextHistory = { Result.failure(SocketTimeoutException("timeout")) }
            nextLeaderboard = { Result.failure(SocketTimeoutException("timeout")) }
        }
        val vm = AuthViewModel(sessionStore, gateway, coordinator, FakeStringResolver())

        vm.loadHistory()
        vm.loadLeaderboard()
        advanceUntilIdle()

        assertNotNull(vm.historyError.value)
        assertNotNull(vm.leaderboardError.value)
    }

    @Test
    fun refreshSessionOnStartup_clearsOnAuthFailure_keepsCachedOnTimeout() = runTest(mainDispatcherRule.testDispatcher) {
        val cachedUser = UserProfile(id = "u1", username = "Bob", email = "b@b.c", avatarId = 0)
        val sessionStore = FakeSessionStore().apply {
            user = cachedUser
            tokenValue = "tok"
        }
        val coordinator = GameSessionCoordinator()
        val gateway = FakeRemoteGateway().apply {
            nextLoginWithToken = { Result.failure(SocketTimeoutException("offline")) }
        }
        val vm = AuthViewModel(sessionStore, gateway, coordinator, FakeStringResolver())

        vm.refreshSessionOnStartup()
        advanceUntilIdle()
        assertEquals(cachedUser, vm.userProfile.value)

        gateway.nextLoginWithToken = {
            Result.failure(ServerErrorException(ErrorCode.AUTH_FAILED, "Expired"))
        }
        vm.refreshSessionOnStartup()
        advanceUntilIdle()

        assertNull(vm.userProfile.value)
        assertNull(sessionStore.getAuthToken())
    }

    @Test
    fun login_persistsTokenAndUpdatesUserProfile() = runTest(mainDispatcherRule.testDispatcher) {
        val sessionStore = FakeSessionStore()
        val coordinator = GameSessionCoordinator()
        val user = UserProfile(id = "u2", username = "Munchkin", email = "m@x.com", avatarId = 0, gender = Gender.F)
        val gateway = FakeRemoteGateway().apply {
            nextLogin = { Result.success(AuthSuccessMessage(user = user, token = "jwt-123")) }
        }
        val vm = AuthViewModel(sessionStore, gateway, coordinator, FakeStringResolver())

        vm.login("m@x.com", "pw")
        advanceUntilIdle()

        assertEquals(user, vm.userProfile.value)
        assertEquals(user, coordinator.userProfile.value)
        assertEquals("jwt-123", sessionStore.getAuthToken())
    }
}
