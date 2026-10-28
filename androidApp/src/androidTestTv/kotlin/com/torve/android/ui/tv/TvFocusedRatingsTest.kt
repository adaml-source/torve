package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvFocusedRatingsTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun film(id: String) = MediaItem(id, imdbId = id, title = "Saved film", type = MediaType.MOVIE,
        year = 2026, runtime = 174, overview = "Complete saved synopsis", logoUrl = "logo.png",
        cast = listOf(CastMember(1, "Nathan Fielder")),
        ratings = MediaRatings(imdbScore = 8f, imdbVotes = 203, rottenTomatoesScore = 100))
    private val prefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES))

    @Test fun backgroundRefreshCanBeReadFromAnAlreadyOpenCompositionSnapshot() {
        val old = film("tt900000005")
        cacheTvBrowsePreviewEnrichedItem(old)
        val snapshot = androidx.compose.runtime.snapshots.Snapshot.takeSnapshot()
        try {
            runBlocking(Dispatchers.Default) {
                cacheTvFocusedImdbRefresh(old.copy(ratings = old.ratings!!.copy(imdbScore = 9.3f, imdbVotes = null)))
            }
            snapshot.enter {
                val current = withCachedTvBrowsePreviewEnrichment(old)
                assertEquals(9.3f, current.ratings?.imdbScore)
                assertNull(current.ratings?.imdbVotes)
            }
        } finally { snapshot.dispose() }
    }

    @Test fun completeContinueWatchingMetadataRefreshesAndLateCatalogueDataCannotUndoIt() {
        val old = film("tt900000001")
        var calls = 0
        rule.setContent { MaterialTheme { Column {
            RefreshFocusedTvImdb(old, "fixture") { item ->
                calls++
                item.copy(ratings = item.ratings!!.copy(imdbScore = 9.3f, imdbVotes = null))
            }
            TvExpandedPosterMetadata(withCachedTvBrowsePreviewEnrichment(old), prefs)
        } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("9.3").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("9.3").assertIsDisplayed()
        rule.onNodeWithText("100%").assertIsDisplayed()
        rule.onNodeWithText("203 IMDb votes").assertDoesNotExist()
        rule.runOnIdle { cacheTvBrowsePreviewEnrichedItem(old.copy(tmdbId = 900000001)) }
        rule.onNodeWithText("9.3").assertIsDisplayed()
        rule.onNodeWithText("8.0").assertDoesNotExist()
        rule.onNodeWithText("203 IMDb votes").assertDoesNotExist()
        rule.runOnIdle {
            val alias = withCachedTvBrowsePreviewEnrichment(old.copy(id = "900000001", tmdbId = 900000001))
            assertEquals(9.3f, alias.ratings?.imdbScore)
            assertNull(alias.ratings?.imdbVotes)
            assertEquals(100, alias.ratings?.rottenTomatoesScore)
        }
    }

    @Test fun failedRefreshKeepsTheSavedScore() {
        val old = film("tt900000002")
        var finished = false
        rule.setContent { MaterialTheme {
            RefreshFocusedTvImdb(old, "fixture") { finished = true; null }
            TvExpandedPosterMetadata(withCachedTvBrowsePreviewEnrichment(old), prefs)
        } }
        rule.waitUntil(5_000) { finished }
        rule.onNodeWithText("8.0").assertIsDisplayed()
        rule.onNodeWithText("203 IMDb votes").assertIsDisplayed()
    }

    @Test fun movingFocusCancelsThePreviousLookup() {
        val first = film("tt900000003")
        val second = film("tt900000004")
        var selected by mutableStateOf<MediaItem?>(first)
        var started = false
        var cancelled = false
        rule.setContent { MaterialTheme {
            RefreshFocusedTvImdb(selected, "fixture") { item ->
                if (item.id == first.id) {
                    started = true
                    try { awaitCancellation() } finally { cancelled = true }
                } else item.copy(ratings = item.ratings!!.copy(imdbScore = 9.1f, imdbVotes = null))
            }
            selected?.let { TvExpandedPosterMetadata(withCachedTvBrowsePreviewEnrichment(it), prefs) }
        } }
        rule.waitUntil(5_000) { started }
        rule.runOnIdle { selected = second }
        rule.waitUntil(5_000) { cancelled && rule.onAllNodesWithText("9.1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("9.1").assertIsDisplayed()
        rule.runOnIdle { assertEquals(8f, withCachedTvBrowsePreviewEnrichment(first).ratings?.imdbScore) }
    }
}
