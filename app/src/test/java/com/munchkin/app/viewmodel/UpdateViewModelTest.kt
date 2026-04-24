package com.munchkin.app.viewmodel

import com.munchkin.app.update.UpdateInfo
import com.munchkin.app.update.UpdateResult
import com.munchkin.app.update.UpdateService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun autoCheckStoresUpdateWithoutMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val updateInfo = updateInfo("2.0.0")
        val updateService = FakeUpdateService(
            checkResult = UpdateResult.UpdateAvailable(updateInfo)
        )

        val viewModel = UpdateViewModel(
            updateService = updateService,
            messages = testMessages(),
            logWarning = {},
            autoCheck = true
        )
        advanceUntilIdle()

        assertEquals(updateInfo, viewModel.uiState.value.updateInfo)
        assertNull(viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isCheckingUpdate)
        assertEquals(1, updateService.checkCalls)
    }

    @Test
    fun manualCheckNoUpdateClearsInfoAndShowsMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = UpdateViewModel(
            updateService = FakeUpdateService(checkResult = UpdateResult.NoUpdate),
            messages = testMessages(),
            logWarning = {},
            autoCheck = false
        )

        viewModel.checkForUpdates(showResultMessage = true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateInfo)
        assertEquals("up to date", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isCheckingUpdate)
    }

    @Test
    fun manualCheckErrorShowsFormattedMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = UpdateViewModel(
            updateService = FakeUpdateService(checkResult = UpdateResult.Error("network down")),
            messages = testMessages(),
            logWarning = {},
            autoCheck = false
        )

        viewModel.checkForUpdates(showResultMessage = true)
        advanceUntilIdle()

        assertEquals("error: network down", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isCheckingUpdate)
    }

    @Test
    fun dismissUpdateAndClearMessageResetTransientState() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = UpdateViewModel(
            updateService = FakeUpdateService(checkResult = UpdateResult.UpdateAvailable(updateInfo("2.0.0"))),
            messages = testMessages(),
            logWarning = {},
            autoCheck = false
        )

        viewModel.checkForUpdates(showResultMessage = true)
        advanceUntilIdle()
        viewModel.dismissUpdate()
        viewModel.clearMessage()

        assertNull(viewModel.uiState.value.updateInfo)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun downloadUpdateUsesCurrentInfoAndResetsWhenComplete() = runTest(mainDispatcherRule.testDispatcher) {
        val updateInfo = updateInfo("2.0.0")
        val updateService = FakeUpdateService(
            checkResult = UpdateResult.UpdateAvailable(updateInfo),
            completeDownloadImmediately = false
        )
        val viewModel = UpdateViewModel(
            updateService = updateService,
            messages = testMessages(),
            logWarning = {},
            autoCheck = false
        )

        viewModel.checkForUpdates(showResultMessage = false)
        advanceUntilIdle()
        viewModel.downloadUpdate()

        assertTrue(viewModel.uiState.value.isDownloading)
        assertSame(updateInfo, updateService.downloads.single())

        updateService.completeDownload()

        assertFalse(viewModel.uiState.value.isDownloading)
    }

    private class FakeUpdateService(
        var checkResult: UpdateResult,
        private val completeDownloadImmediately: Boolean = true
    ) : UpdateService {
        var checkCalls = 0
            private set
        val downloads = mutableListOf<UpdateInfo>()
        private var onDownloadComplete: (() -> Unit)? = null

        override suspend fun checkForUpdate(): UpdateResult {
            checkCalls += 1
            return checkResult
        }

        override fun downloadAndInstall(updateInfo: UpdateInfo, onComplete: () -> Unit) {
            downloads += updateInfo
            onDownloadComplete = onComplete
            if (completeDownloadImmediately) {
                completeDownload()
            }
        }

        fun completeDownload() {
            val callback = onDownloadComplete ?: return
            onDownloadComplete = null
            callback()
        }
    }

    private fun updateInfo(version: String): UpdateInfo {
        return UpdateInfo(
            version = version,
            releaseNotes = "release notes",
            downloadUrl = "https://example.com/munchkin.apk",
            fileSize = 42L
        )
    }

    private fun testMessages(): UpdateMessages {
        return UpdateMessages(
            updateAvailable = { version -> "update: $version" },
            upToDate = { "up to date" },
            checkError = { error -> "error: $error" }
        )
    }
}
