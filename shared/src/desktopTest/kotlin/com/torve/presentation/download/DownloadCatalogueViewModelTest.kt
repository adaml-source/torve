package com.torve.presentation.download

import com.torve.data.download.DownloadCatalogueBuilder
import com.torve.data.profile.FamilyControls
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.domain.security.SecureStorage
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsRefreshNotifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCatalogueViewModelTest {
    private inline fun <reified T> stub(crossinline value: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> value(method.name) } as T
    private val prefs = stub<PreferencesRepository> { if (it == "getString") null else Unit }
    private var progressRows = emptyList<WatchProgress>()
    private val progress = stub<WatchProgressRepository> { if (it == "getAllProgress") progressRows else error(it) }
    private fun movie(id: String = "movie") = Download(id, "10", MediaType.MOVIE, id, streamUrl = "https://unused.test/file",
        filePath = "/offline/$id.mp4", status = DownloadStatus.COMPLETED)
    private class Repo : DownloadRepository {
        var rows = emptyList<Download>()
        var read: suspend () -> List<Download> = { rows }
        override suspend fun getAllDownloads() = read()
        override suspend fun getDownload(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun enqueueDownload(download: Download) = error("unused")
        override suspend fun getPendingDownloads() = error("unused")
        override suspend fun getCompletedDownloads() = error("unused")
        override suspend fun getDownloadByMediaId(mediaId: String) = error("unused")
        override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) = Unit
        override suspend fun markCompleted(id: String, filePath: String) = Unit
        override suspend fun updateFileSize(id: String, fileSizeBytes: Long) = Unit
        override suspend fun deleteDownload(id: String) { rows = rows.filterNot { it.id == id } }
        override suspend fun pauseDownload(id: String) = Unit
        override suspend fun resumeDownload(id: String) = Unit
    }
    private fun TestScope.vm(repo: Repo, filter: ContentPolicyFilter = ContentPolicyFilter()) =
        DownloadCatalogueViewModel(repo, progress, prefs, DownloadCatalogueBuilder(), contentPolicyFilter = filter,
            workDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun readFailureIsVisibleAndRetryLoadsTheLibrary() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { read = { error("disk unavailable") } }; val vm = vm(repo)
        try {
            advanceUntilIdle(); assertFalse(vm.state.value.isLoading); assertNotNull(vm.state.value.error)
            repo.rows = listOf(movie()); repo.read = { repo.rows }; vm.loadCatalogue(); advanceUntilIdle()
            assertNull(vm.state.value.error); assertEquals(1, vm.state.value.catalogue.movieCount)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun hungReadTimesOutAndCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { read = { awaitCancellation() } }; val vm = vm(repo)
        try {
            advanceUntilIdle(); assertFalse(vm.state.value.isLoading); assertNotNull(vm.state.value.error)
            repo.read = { emptyList() }; vm.loadCatalogue(); advanceUntilIdle()
            assertFalse(vm.state.value.isLoading); assertNull(vm.state.value.error); assertTrue(vm.state.value.catalogue.isEmpty)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun refreshCancellationCannotOverwriteANewerLoadWithAnError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { read = { awaitCancellation() } }; val vm = vm(repo)
        try {
            runCurrent(); repo.rows = listOf(movie("latest")); repo.read = { repo.rows }
            vm.loadCatalogue(); advanceUntilIdle()
            assertNull(vm.state.value.error); assertEquals("latest", vm.state.value.allDownloadedItems.single().id)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun activeTransfersAreRetainedEvenWithoutACompletedCatalogue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { rows = listOf(movie().copy(status = DownloadStatus.DOWNLOADING)) }; val vm = vm(repo)
        try {
            advanceUntilIdle(); assertTrue(vm.state.value.catalogue.isEmpty)
            assertEquals(1, vm.state.value.activeDownloads.size); assertFalse(vm.state.value.isLoading)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun episodeCompletionCannotBeCopiedToOtherEpisodesOfTheSameShow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { rows = (1..2).map { movie("e$it").copy(mediaType = MediaType.SERIES, seasonNumber = 1, episodeNumber = it) } }
        progressRows = listOf(WatchProgress("10", MediaType.SERIES, "Show", positionMs = 900, durationMs = 1000, seasonNumber = 1, episodeNumber = 1))
        val vm = vm(repo)
        try {
            advanceUntilIdle(); val items = vm.state.value.allDownloadedItems.associateBy { it.episodeNumber }
            assertTrue(items.getValue(1).isWatched); assertFalse(items.getValue(2).isWatched)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun playbackRechecksThatTheEntryStillExistsAndIsCompleted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo().apply { rows = listOf(movie()) }; val vm = vm(repo)
        try {
            advanceUntilIdle(); assertEquals("movie", vm.downloadForPlayback("movie").id)
            repo.rows = listOf(movie().copy(status = DownloadStatus.PENDING))
            assertFails { vm.downloadForPlayback("movie") }
            repo.rows = emptyList(); assertFails { vm.downloadForPlayback("movie") }
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
    @Test fun profileSwitchDropsOldRowsAndReloadsWithoutRemainingOnTheInitialSpinner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var owner = "parent"
        val settings = stub<DeviceLocalSettingsRepository> { if (it == "getString") null else Unit }
        val secure = stub<SecureStorage> { if (it == "getString") null else Unit }
        val family = FamilyControls(settings, secure, null, SettingsRefreshNotifier(), lookup = { emptyList() },
            scope = backgroundScope, accountId = { "account" }, profileId = { owner })
        family.initialize()
        val repo = Repo().apply { read = { listOf(movie(owner)) } }; val vm = vm(repo, ContentPolicyFilter(family))
        try {
            advanceUntilIdle(); assertEquals("parent", vm.state.value.allDownloadedItems.single().id)
            owner = "other"; family.updateSession()
            assertTrue(vm.state.value.allDownloadedItems.isEmpty())
            advanceUntilIdle(); assertFalse(vm.state.value.isLoading)
            assertEquals("other", vm.state.value.allDownloadedItems.single().id)
        } finally { vm.clear(); Dispatchers.resetMain() }
    }
}
