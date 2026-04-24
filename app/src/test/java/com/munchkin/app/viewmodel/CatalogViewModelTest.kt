package com.munchkin.app.viewmodel

import com.munchkin.app.data.CatalogDataSource
import com.munchkin.app.network.CatalogMonster
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun searchMonstersSuccessUpdatesState() = runTest(mainDispatcherRule.testDispatcher) {
        val monsters = listOf(CatalogMonster(id = "m-1", name = "Goblin", level = 1))
        val repository = FakeCatalogDataSource(searchResult = Result.success(monsters))
        val viewModel = CatalogViewModel(repository)

        viewModel.searchMonsters("goblin")

        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(monsters, viewModel.uiState.value.searchResults)
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("goblin"), repository.searchQueries)
    }

    @Test
    fun createGlobalMonsterClampsInputAndEmitsCreatedMonster() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeCatalogDataSource()
        val viewModel = CatalogViewModel(repository)
        val emittedMonsters = mutableListOf<CatalogMonster>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.createdMonsters.toList(emittedMonsters)
        }

        viewModel.createGlobalMonster(
            name = "Ancient Dragon",
            level = 99,
            modifier = -99,
            isUndead = true,
            userProfile = null,
            fallbackOwnerId = "local-owner"
        )
        advanceUntilIdle()

        val requestedMonster = repository.addedMonsters.single()
        assertEquals(20, requestedMonster.level)
        assertEquals(-10, requestedMonster.modifier)
        assertEquals(true, requestedMonster.isUndead)
        assertEquals("local-owner", requestedMonster.createdBy)
        assertEquals(listOf(requestedMonster.copy(id = "created-monster")), viewModel.uiState.value.searchResults)
        assertEquals(listOf(requestedMonster.copy(id = "created-monster")), emittedMonsters)
    }

    @Test
    fun createGlobalMonsterFailureSetsError() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeCatalogDataSource(
            addResult = Result.failure(IllegalStateException("login required"))
        )
        val viewModel = CatalogViewModel(repository)

        viewModel.createGlobalMonster(
            name = "Goblin",
            level = 1,
            modifier = 0,
            isUndead = false,
            userProfile = null,
            fallbackOwnerId = "local-owner"
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("login required", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    private class FakeCatalogDataSource(
        var searchResult: Result<List<CatalogMonster>> = Result.success(emptyList()),
        var addResult: Result<CatalogMonster>? = null
    ) : CatalogDataSource {
        val searchQueries = mutableListOf<String>()
        val addedMonsters = mutableListOf<CatalogMonster>()

        override suspend fun searchMonsters(query: String): Result<List<CatalogMonster>> {
            searchQueries += query
            return searchResult
        }

        override suspend fun addMonster(monster: CatalogMonster): Result<CatalogMonster> {
            addedMonsters += monster
            return addResult ?: Result.success(monster.copy(id = "created-monster"))
        }
    }
}
