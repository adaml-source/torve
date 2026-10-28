package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.MediaType
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.StreamPreferences
import kotlin.test.*

class DetailSourceResultsCacheTest {
    private val source = ParsedStream("Provider", "1080p", "Movie", directUrl = "https://example.test/source")
    private fun key(episode: Int? = null) = DetailSourceResultsCache.Key(
        if (episode == null) MediaType.MOVIE else MediaType.SERIES, "tt123", episode?.let { 1 }, episode,
        StreamPreferences(), emptyList(), emptyMap())

    @Test fun playbackReturnKeepsSourcesAndNewAudioWithoutReplayingOldUrl() {
        val cache = DetailSourceResultsCache()
        cache.playbackLaunched(key(), DetailUiState(streams = listOf(source), streamFilterHiddenCount = 3))
        val playing = DetailUiState(isResolving = true, resolvedStream = ResolvedStream(url = "https://example.test/resolved"))
        val restored = cache.restoreAfterPlayback(playing) { it.copy(languages = listOf("EN"), trackLanguagesVerified = true) }!!
        assertEquals(source.directUrl, restored.streams.single().directUrl)
        assertEquals(listOf("EN"), restored.streams.single().languages)
        assertTrue(restored.streams.single().trackLanguagesVerified)
        assertFalse(restored.showStreamPicker)
        assertNull(restored.resolvedStream)
        assertFalse(restored.isResolving)
        assertEquals(3, restored.streamFilterHiddenCount)
        assertNull(cache.restoreAfterPlayback(restored) { it })
    }

    @Test fun episodesAndProviderCredentialsDoNotShareResults() {
        val cache = DetailSourceResultsCache()
        cache.retain(key(1), DetailUiState(streams = listOf(source)))
        assertNull(cache.restore(key(2), DetailUiState()) { it })
        assertNull(cache.restore(key(), DetailUiState()) { it })
        assertNull(cache.restore(key(1).copy(imdbId = "tt456"), DetailUiState()) { it })
        assertNull(cache.restore(key(1).copy(accounts = mapOf(DebridServiceType.REAL_DEBRID to "changed-key")), DetailUiState()) { it })
        assertNull(cache.restore(key(1).copy(preferences = StreamPreferences(autoPlayEnabled = false)), DetailUiState()) { it })
        cache.retain(key(2), DetailUiState(streams = listOf(source.copy(title = "Episode 2"))))
        assertEquals("Movie", cache.restore(key(1), DetailUiState()) { it }!!.streams.single().title)
        assertEquals(1, cache.restore(key(1), DetailUiState()) { it }!!.streamContextEpisode)
    }

    @Test fun idleResultsStillExpireWithoutAFreshSearch() {
        var now = 1000L
        val cache = DetailSourceResultsCache { now }
        val state = DetailUiState(streams = listOf(source))
        cache.retain(key(), state, fresh = true)
        now += DetailSourceResultsCache.LIFETIME_MS - 1
        cache.retain(key(), state)
        assertNotNull(cache.restore(key(), DetailUiState()) { it })
        now++
        assertNull(cache.restore(key(), DetailUiState()) { it })
    }

    @Test fun cacheIsBoundedAndClearedWhenDestinationIsReleased() {
        val cache = DetailSourceResultsCache()
        (1..9).forEach { cache.retain(key(it), DetailUiState(streams = listOf(source))) }
        assertNull(cache.restore(key(1), DetailUiState()) { it })
        assertNotNull(cache.restore(key(9), DetailUiState()) { it })
        cache.clear()
        assertNull(cache.restore(key(9), DetailUiState()) { it })
    }

    @Test fun explicitRefreshDropsSavedResults() {
        val cache = DetailSourceResultsCache()
        cache.retain(key(), DetailUiState(streams = listOf(source)))
        cache.invalidate(key())
        assertNull(cache.restore(key(), DetailUiState()) { it })
    }

    @Test fun longPlaybackPreservesResultsAndAudioWhenReturningAndReopening() {
        var now = 1000L
        val cache = DetailSourceResultsCache { now }
        val state = DetailUiState(streams = listOf(source), streamFilterHiddenCount = 3)
        cache.retain(key(), state, fresh = true)
        now += DetailSourceResultsCache.LIFETIME_MS - 1
        cache.playbackLaunched(key(), state)
        now += 4 * 60 * 60 * 1000L
        val playing = DetailUiState(resolvedStream = ResolvedStream(url = "https://example.test/expired-resolved"))
        val restored = assertNotNull(cache.restoreAfterPlayback(playing) {
            it.copy(languages = listOf("DE", "EN"), trackLanguagesVerified = true)
        })
        assertEquals(listOf("DE", "EN"), restored.streams.single().languages)
        assertTrue(restored.streams.single().trackLanguagesVerified)
        assertFalse(restored.showStreamPicker)
        assertEquals(3, restored.streamFilterHiddenCount)
        assertNull(restored.resolvedStream)
        assertNull(restored.autoPlayStream)
        assertNull(cache.restoreAfterPlayback(restored) { it })
        cache.retain(key(), restored)
        now += DetailSourceResultsCache.LIFETIME_MS
        assertEquals(listOf("DE", "EN"), assertNotNull(cache.restore(key(), DetailUiState()) { it }).streams.single().languages)
    }

    @Test fun playbackResultsSurviveEvictionWhileIdleEntriesRemainBounded() {
        var now = 1000L
        val cache = DetailSourceResultsCache { now }
        cache.playbackLaunched(key(), DetailUiState(streams = listOf(source)))
        (1..9).forEach { cache.retain(key(it), DetailUiState(streams = listOf(source))) }
        assertNull(cache.restore(key(1), DetailUiState()) { it })
        assertNotNull(cache.restore(key(9), DetailUiState()) { it })
        now += 4 * 60 * 60 * 1000L
        assertNotNull(cache.restoreAfterPlayback(DetailUiState()) { it })
        assertNull(cache.restore(key(9), DetailUiState()) { it })
    }

    @Test fun playbackPinDoesNotCrossCredentialsOrEpisodeBoundaries() {
        var now = 1000L
        val cache = DetailSourceResultsCache { now }
        cache.playbackLaunched(key(1), DetailUiState(streams = listOf(source)))
        now += 4 * 60 * 60 * 1000L
        assertNull(cache.restore(key(2), DetailUiState()) { it })
        assertNull(cache.restore(key(1).copy(accounts = mapOf(DebridServiceType.REAL_DEBRID to "new-account")), DetailUiState()) { it })
        assertNull(cache.restore(key(1).copy(preferences = StreamPreferences(autoPlayEnabled = false)), DetailUiState()) { it })
        assertNotNull(cache.restore(key(1), DetailUiState()) { it })
    }

    @Test fun refreshAndScopeClearDiscardPinnedPlaybackResults() {
        val cache = DetailSourceResultsCache()
        val state = DetailUiState(streams = listOf(source))
        cache.playbackLaunched(key(), state)
        cache.invalidate(key(1))
        assertNotNull(cache.restore(key(), DetailUiState()) { it })
        cache.invalidate(key())
        assertNull(cache.restoreAfterPlayback(DetailUiState()) { it })
        assertNull(cache.restore(key(), DetailUiState()) { it })
        cache.playbackLaunched(key(), state)
        cache.clear()
        assertNull(cache.restoreAfterPlayback(DetailUiState()) { it })
        assertNull(cache.restore(key(), DetailUiState()) { it })
    }

    @Test fun startingAnotherPlaybackReleasesThePreviousPin() {
        var now = 1000L
        val cache = DetailSourceResultsCache { now }
        cache.playbackLaunched(key(1), DetailUiState(streams = listOf(source)))
        now += DetailSourceResultsCache.LIFETIME_MS
        cache.playbackLaunched(key(2), DetailUiState(streams = listOf(source.copy(title = "Episode 2"))))
        assertNull(cache.restore(key(1), DetailUiState()) { it })
        now += 4 * 60 * 60 * 1000L
        val restored = assertNotNull(cache.restoreAfterPlayback(DetailUiState()) { it })
        assertEquals(2, restored.streamContextEpisode)
        assertEquals("Episode 2", restored.streams.single().title)
    }
}
