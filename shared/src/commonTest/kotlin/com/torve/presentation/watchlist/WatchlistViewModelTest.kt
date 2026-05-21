package com.torve.presentation.watchlist

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchlistItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchlistMutationResult
import com.torve.domain.repository.WatchlistRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchlistViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun traktConnectedAddSuccessUpdatesStateAndEmitsSuccess() = runTest(dispatcher) {
        val repo = FakeWatchlistRepository()
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.toggleWatchlist(movie())
        advanceUntilIdle()

        assertTrue(repo.addCalled)
        assertEquals(1, repo.addCallCount)
        assertTrue(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertEquals(WatchlistViewModel.MESSAGE_ADDED, viewModel.state.value.snackbarMessage)
        val mutation = assertIs<WatchlistMutationState.Success>(viewModel.state.value.mutationState)
        assertTrue(mutation.isInWatchlist)
    }

    @Test
    fun traktConnectedRemoveSuccessUpdatesStateAfterConfirmedRemove() = runTest(dispatcher) {
        val existing = movie().toInitialWatchlistItem()
        val repo = FakeWatchlistRepository(initialItems = listOf(existing))
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.containsMedia("tmdb:movie:123"))

        viewModel.toggleWatchlist(movie())
        advanceUntilIdle()

        assertEquals(1, repo.removeCallCount)
        assertFalse(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertEquals(WatchlistViewModel.MESSAGE_REMOVED, viewModel.state.value.snackbarMessage)
        val mutation = assertIs<WatchlistMutationState.Success>(viewModel.state.value.mutationState)
        assertFalse(mutation.isInWatchlist)
    }

    @Test
    fun missingTraktConnectionEmitsConnectTraktMessageWithoutLocalSuccess() = runTest(dispatcher) {
        val repo = FakeWatchlistRepository(
            addResult = { WatchlistMutationResult.MissingTraktConnection(it.mediaId) },
        )
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.toggleWatchlist(movie())
        advanceUntilIdle()

        assertTrue(repo.addCalled)
        assertFalse(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertEquals(WatchlistViewModel.MESSAGE_CONNECT_TRAKT, viewModel.state.value.snackbarMessage)
        assertIs<WatchlistMutationState.Error>(viewModel.state.value.mutationState)
    }

    @Test
    fun insufficientMetadataEmitsSanitizedErrorWithoutCallingTrakt() = runTest(dispatcher) {
        val repo = FakeWatchlistRepository()
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.toggleWatchlist(
            MediaItem(
                id = "local-only",
                type = MediaType.MOVIE,
                title = "No IDs",
            ),
        )
        advanceUntilIdle()

        assertFalse(repo.addCalled)
        assertEquals(WatchlistViewModel.MESSAGE_UPDATE_FAILED, viewModel.state.value.snackbarMessage)
        assertIs<WatchlistMutationState.Error>(viewModel.state.value.mutationState)
    }

    @Test
    fun traktFailureEmitsSanitizedErrorWithoutLocalSuccess() = runTest(dispatcher) {
        val repo = FakeWatchlistRepository(
            addResult = { WatchlistMutationResult.Failed(it.mediaId) },
        )
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.toggleWatchlist(movie())
        advanceUntilIdle()

        assertTrue(repo.addCalled)
        assertFalse(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertEquals(WatchlistViewModel.MESSAGE_UPDATE_FAILED, viewModel.state.value.snackbarMessage)
        assertIs<WatchlistMutationState.Error>(viewModel.state.value.mutationState)
    }

    @Test
    fun localStateDoesNotUpdateBeforeConfirmedTraktSuccessAndDuplicateTapIsIgnored() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val repo = FakeWatchlistRepository(
            addResult = {
                release.await()
                WatchlistMutationResult.Success(it.mediaId, isInWatchlist = true, item = it)
            },
        )
        val viewModel = WatchlistViewModel(repo, FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.toggleWatchlist(movie())
        runCurrent()

        assertEquals(1, repo.addCallCount)
        assertFalse(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertIs<WatchlistMutationState.Loading>(viewModel.state.value.mutationState)

        viewModel.toggleWatchlist(movie())
        runCurrent()

        assertEquals(1, repo.addCallCount)

        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.containsMedia("tmdb:movie:123"))
        assertEquals(WatchlistViewModel.MESSAGE_ADDED, viewModel.state.value.snackbarMessage)
    }

    private fun movie() = MediaItem(
        id = "tmdb:movie:123",
        tmdbId = 123,
        imdbId = "tt0000123",
        type = MediaType.MOVIE,
        title = "Test Movie",
    )

    private fun MediaItem.toInitialWatchlistItem() = WatchlistItem(
        mediaId = "123",
        mediaType = type,
        tmdbId = tmdbId ?: 0,
        imdbId = imdbId,
        title = title,
        addedAt = 1L,
    )
}

private class FakeWatchlistRepository(
    initialItems: List<WatchlistItem> = emptyList(),
    private val addResult: suspend (WatchlistItem) -> WatchlistMutationResult = {
        WatchlistMutationResult.Success(it.mediaId, isInWatchlist = true, item = it)
    },
) : WatchlistRepository {
    var addCalled = false
    var addCallCount = 0
    var removeCallCount = 0
    private val items = initialItems.toMutableList()

    override suspend fun getAll(): List<WatchlistItem> = items.toList()
    override suspend fun getByType(mediaType: String): List<WatchlistItem> =
        items.filter { it.mediaType.name.equals(mediaType, ignoreCase = true) }

    override suspend fun isInWatchlist(mediaId: String): Boolean =
        items.any { it.mediaId == mediaId.normalizedWatchlistMediaId() }

    override suspend fun add(item: WatchlistItem) {
        items += item
    }

    override suspend fun add(item: WatchlistItem, syncTrakt: Boolean, syncSimkl: Boolean) {
        items += item
    }

    override suspend fun remove(mediaId: String) {
        items.removeAll { it.mediaId == mediaId.normalizedWatchlistMediaId() }
    }

    override suspend fun clear() {
        items.clear()
    }

    override suspend fun syncFromTrakt() = Unit

    override suspend fun addToTraktWatchlist(item: WatchlistItem): WatchlistMutationResult {
        addCalled = true
        addCallCount += 1
        return addResult(item).also { result ->
            if (result is WatchlistMutationResult.Success && result.isInWatchlist && result.item != null) {
                items += result.item
            }
        }
    }

    override suspend fun removeFromTraktWatchlist(mediaId: String): WatchlistMutationResult {
        val normalized = mediaId.normalizedWatchlistMediaId()
        removeCallCount += 1
        items.removeAll { it.mediaId == normalized }
        return WatchlistMutationResult.Success(normalized, isInWatchlist = false)
    }

    override suspend fun toggleTraktWatchlist(item: WatchlistItem): WatchlistMutationResult =
        if (items.any { it.mediaId == item.mediaId }) {
            removeFromTraktWatchlist(item.mediaId)
        } else {
            addToTraktWatchlist(item)
        }
}

private class FakePreferencesRepository : PreferencesRepository {
    override suspend fun getString(key: String): String? = null
    override suspend fun setString(key: String, value: String) = Unit
    override suspend fun remove(key: String) = Unit
}
