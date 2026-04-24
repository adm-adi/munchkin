package com.munchkin.app.viewmodel

import com.munchkin.app.data.AccountSessionStore
import com.munchkin.app.data.AuthDataSource
import com.munchkin.app.data.ProfileDataSource
import com.munchkin.app.network.AuthHttpResponse
import com.munchkin.app.network.UserProfile
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
class AccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initRestoresSavedProfileThenValidatesToken() = runTest(mainDispatcherRule.testDispatcher) {
        val savedProfile = user("saved", "Saved")
        val remoteProfile = user("remote", "Remote")
        val sessionStore = FakeSessionStore(profile = savedProfile, token = "token-1")
        val authDataSource = FakeAuthDataSource(restoreResult = Result.success(AuthHttpResponse(remoteProfile, "token-1")))

        val viewModel = AccountViewModel(sessionStore, authDataSource, FakeProfileDataSource())

        assertEquals(savedProfile, viewModel.uiState.value.userProfile)
        advanceUntilIdle()
        assertEquals(remoteProfile, viewModel.uiState.value.userProfile)
        assertEquals(listOf("token-1"), authDataSource.restoreTokens)
    }

    @Test
    fun loginSuccessUpdatesProfileAndClearsError() = runTest(mainDispatcherRule.testDispatcher) {
        val profile = user("user-1", "Ana")
        val authDataSource = FakeAuthDataSource(
            loginResult = Result.success(AuthHttpResponse(profile, "token-1"))
        )
        val viewModel = AccountViewModel(FakeSessionStore(), authDataSource, FakeProfileDataSource())

        viewModel.login("ana@example.com", "secret")

        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(profile, viewModel.uiState.value.userProfile)
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("ana@example.com" to "secret"), authDataSource.loginRequests)
    }

    @Test
    fun updateProfileSuccessUpdatesCurrentProfile() = runTest(mainDispatcherRule.testDispatcher) {
        val currentProfile = user("user-1", "Ana")
        val updatedProfile = currentProfile.copy(username = "Anita")
        val authDataSource = FakeAuthDataSource(
            loginResult = Result.success(AuthHttpResponse(currentProfile, "token-1"))
        )
        val profileDataSource = FakeProfileDataSource(updateResult = Result.success(updatedProfile))
        val viewModel = AccountViewModel(FakeSessionStore(), authDataSource, profileDataSource)

        viewModel.login("ana@example.com", "secret")
        advanceUntilIdle()
        viewModel.updateProfile(username = "Anita", password = null)
        advanceUntilIdle()

        assertEquals(updatedProfile, viewModel.uiState.value.userProfile)
        assertEquals(listOf("Anita" to null), profileDataSource.updateRequests)
    }

    @Test
    fun logoutClearsRepositoryAndState() = runTest(mainDispatcherRule.testDispatcher) {
        val profile = user("user-1", "Ana")
        val authDataSource = FakeAuthDataSource(
            loginResult = Result.success(AuthHttpResponse(profile, "token-1"))
        )
        val viewModel = AccountViewModel(FakeSessionStore(), authDataSource, FakeProfileDataSource())

        viewModel.login("ana@example.com", "secret")
        advanceUntilIdle()
        viewModel.logout()

        assertEquals(AccountUiState(), viewModel.uiState.value)
        assertTrue(authDataSource.logoutCalled)
    }

    private class FakeSessionStore(
        private var profile: UserProfile? = null,
        private var token: String? = null
    ) : AccountSessionStore {
        override fun saveSession(profile: UserProfile) {
            this.profile = profile
        }

        override fun saveAuthToken(token: String) {
            this.token = token
        }

        override fun getAuthToken(): String? = token

        override fun getSession(): UserProfile? = profile

        override fun clearSession() {
            profile = null
            token = null
        }
    }

    private class FakeAuthDataSource(
        var restoreResult: Result<AuthHttpResponse> = Result.failure(IllegalStateException("no token")),
        var registerResult: Result<AuthHttpResponse> = Result.failure(IllegalStateException("register not configured")),
        var loginResult: Result<AuthHttpResponse> = Result.failure(IllegalStateException("login not configured"))
    ) : AuthDataSource {
        val restoreTokens = mutableListOf<String>()
        val loginRequests = mutableListOf<Pair<String, String>>()
        var logoutCalled = false
            private set

        override suspend fun restore(token: String): Result<AuthHttpResponse> {
            restoreTokens += token
            return restoreResult
        }

        override suspend fun register(username: String, email: String, password: String): Result<AuthHttpResponse> {
            return registerResult
        }

        override suspend fun login(email: String, password: String): Result<AuthHttpResponse> {
            loginRequests += email to password
            return loginResult
        }

        override fun logout() {
            logoutCalled = true
        }
    }

    private class FakeProfileDataSource(
        var updateResult: Result<UserProfile> = Result.failure(IllegalStateException("update not configured"))
    ) : ProfileDataSource {
        val updateRequests = mutableListOf<Pair<String?, String?>>()

        override suspend fun updateProfile(username: String?, password: String?): Result<UserProfile> {
            updateRequests += username to password
            return updateResult
        }
    }

    private fun user(id: String, username: String): UserProfile {
        return UserProfile(
            id = id,
            username = username,
            email = "$username@example.com",
            avatarId = 0
        )
    }
}
