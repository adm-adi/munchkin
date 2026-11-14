package com.munchkin.app.viewmodel

import com.munchkin.app.network.CatalogMonster
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun search_and_loadMore_paginateCleanly() = runTest(mainDispatcherRule.testDispatcher) {
        val page1 = (1..30).map { CatalogMonster(id = "id-$it", name = "Monster $it", level = 1) }
        val page2 = (31..35).map { CatalogMonster(id = "id-$it", name = "Monster $it", level = 2) }
        val gateway = FakeRemoteGateway().apply {
            nextSearchMonsters = { _, offset ->
                when (offset) {
                    0 -> Result.success(page1)
                    30 -> Result.success(page2)
                    else -> Result.success(emptyList())
                }
            }
        }
        val vm = CatalogViewModel(gateway, FakeSessionStore(), FakeStringResolver())

        vm.search("dragon", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals(30, vm.searchResults.value.size)
        assertTrue(vm.hasMore.value)
        assertNull(vm.error.value)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(35, vm.searchResults.value.size)
        assertFalse(vm.hasMore.value)
    }

    @Test
    fun search_networkFailure_exposesExplicitError() = runTest(mainDispatcherRule.testDispatcher) {
        val gateway = FakeRemoteGateway().apply {
            nextSearchMonsters = { _, _ -> Result.failure(SocketTimeoutException("timeout")) }
        }
        val vm = CatalogViewModel(gateway, FakeSessionStore(), FakeStringResolver())

        vm.search("orc", debounceMs = 0L)
        advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertTrue(vm.searchResults.value.isEmpty())
    }

    @Test
    fun createMonster_requiresAuth_andWorksOutsideActiveGame() = runTest(mainDispatcherRule.testDispatcher) {
        val sessionStore = FakeSessionStore()
        val gateway = FakeRemoteGateway()
        val vm = CatalogViewModel(gateway, sessionStore, FakeStringResolver())

        assertFalse(vm.canCreateMonster)
        vm.createMonster("Slime", 1, 0, false, 1, 1, "Lose 1 gear")
        advanceUntilIdle()
        assertNotNull(vm.error.value)

        sessionStore.tokenValue = "jwt-token"
        assertTrue(vm.canCreateMonster)
        vm.createMonster("King Slime", 50, 0, true, 1, 1, "Die")
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertNotNull(vm.statusMessage.value)
    }

    @Test
    fun loadMore_ignoresSecondCallWhenNoMoreResults() = runTest(mainDispatcherRule.testDispatcher) {
        var calls = 0
        val gateway = FakeRemoteGateway().apply {
            nextSearchMonsters = { _, _ ->
                calls++
                Result.success(listOf(CatalogMonster(id = "id-1", name = "Rat", level = 1)))
            }
        }
        val vm = CatalogViewModel(gateway, FakeSessionStore(), FakeStringResolver())

        vm.search("rat", debounceMs = 0L)
        advanceUntilIdle()
        assertFalse(vm.hasMore.value)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, calls)
    }
}
