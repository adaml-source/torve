package com.torve.presentation.contentpolicy

import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.LOCKED_CONTENT_TITLE
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Targeted tests for content-policy enforcement on watch history and download surfaces.
 */
class ContentPolicyHistoryDownloadsTest {
    private val filter = ContentPolicyFilter()

    // ── Watch History: locked placeholder ──

    @Test
    fun lockedPolicyPlaceholdersHistoryWithSensitiveTitle() {
        val entry = sensitiveHistoryEntry()
        val result = filter.filterWatchHistory(
            policy = lockedPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(entry),
        )

        assertEquals(1, result.size)
        assertTrue(result.first().isContentPlaceholder)
        assertEquals(LOCKED_CONTENT_TITLE, result.first().title)
        assertNull(result.first().posterUrl)
        assertNull(result.first().backdropUrl)
        assertNull(result.first().showTitle)
    }

    @Test
    fun lockedPolicyPassesSafeHistoryThrough() {
        val entry = safeHistoryEntry()
        val result = filter.filterWatchHistory(
            policy = lockedPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(entry),
        )

        assertEquals(1, result.size)
        assertFalse(result.first().isContentPlaceholder)
        assertEquals("Family Movie", result.first().title)
        assertEquals("https://img/poster.jpg", result.first().posterUrl)
    }

    @Test
    fun signedOutPolicyPlaceholdersHistoryWithSensitiveTitle() {
        val result = filter.filterWatchHistory(
            policy = signedOutPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveHistoryEntry()),
        )

        assertTrue(result.first().isContentPlaceholder)
    }

    @Test
    fun unknownAgePolicyPlaceholdersHistoryWithSensitiveTitle() {
        val result = filter.filterWatchHistory(
            policy = unknownAgePolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveHistoryEntry()),
        )

        assertTrue(result.first().isContentPlaceholder)
    }

    @Test
    fun adultEnabledPolicyAllowsSensitiveHistory() {
        val result = filter.filterWatchHistory(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveHistoryEntry()),
        )

        assertEquals(1, result.size)
        assertFalse(result.first().isContentPlaceholder)
        assertEquals("Explicit Adult Film", result.first().title)
    }

    @Test
    fun relockAfterUnlockPlaceholdersHistoryImmediately() {
        // Simulate: user was adult-enabled, then disabled
        val relocked = adultEnabledPolicy().copy(sensitiveMaterialEnabled = false)
        val result = filter.filterWatchHistory(
            policy = relocked,
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveHistoryEntry()),
        )

        assertTrue(result.first().isContentPlaceholder)
        assertNull(result.first().posterUrl)
    }

    @Test
    fun enforcementDisabledPassesAllHistory() {
        val result = filter.filterWatchHistory(
            policy = ContentPolicyState.unrestricted(),
            context = ContentAccessContext.HISTORY_DERIVED,
            items = listOf(sensitiveHistoryEntry(), safeHistoryEntry()),
        )

        assertEquals(2, result.size)
        assertFalse(result[0].isContentPlaceholder)
        assertFalse(result[1].isContentPlaceholder)
    }

    // ── Downloads: locked placeholder ──

    @Test
    fun lockedPolicyPlaceholdsDownloadWithSensitiveTitle() {
        val result = filter.filterDownloads(
            policy = lockedPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveDownload()),
        )

        assertEquals(1, result.size)
        assertEquals(LOCKED_CONTENT_TITLE, result.first().title)
        assertNull(result.first().posterUrl)
    }

    @Test
    fun lockedPolicyPassesSafeDownloadThrough() {
        val result = filter.filterDownloads(
            policy = lockedPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(safeDownload()),
        )

        assertEquals(1, result.size)
        assertEquals("Family Movie", result.first().title)
        assertEquals("https://img/poster.jpg", result.first().posterUrl)
    }

    @Test
    fun lockedPolicyPreservesDownloadFunctionalFields() {
        val download = sensitiveDownload()
        val result = filter.filterDownloads(
            policy = lockedPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(download),
        )

        val filtered = result.first()
        // Functional fields preserved for pause/resume/delete
        assertEquals(download.id, filtered.id)
        assertEquals(download.mediaId, filtered.mediaId)
        assertEquals(download.streamUrl, filtered.streamUrl)
        assertEquals(download.filePath, filtered.filePath)
        assertEquals(download.status, filtered.status)
        // Display fields scrubbed
        assertEquals(LOCKED_CONTENT_TITLE, filtered.title)
        assertNull(filtered.posterUrl)
    }

    @Test
    fun signedOutPolicyPlaceholdsDownloadWithSensitiveTitle() {
        val result = filter.filterDownloads(
            policy = signedOutPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveDownload()),
        )

        assertEquals(LOCKED_CONTENT_TITLE, result.first().title)
    }

    @Test
    fun adultEnabledPolicyAllowsSensitiveDownload() {
        val result = filter.filterDownloads(
            policy = adultEnabledPolicy(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveDownload()),
        )

        assertEquals("Explicit Adult Film", result.first().title)
        assertEquals("https://img/poster.jpg", result.first().posterUrl)
    }

    @Test
    fun relockAfterUnlockPlaceholdsDownloadsImmediately() {
        val relocked = adultEnabledPolicy().copy(sensitiveMaterialEnabled = false)
        val result = filter.filterDownloads(
            policy = relocked,
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveDownload()),
        )

        assertEquals(LOCKED_CONTENT_TITLE, result.first().title)
        assertNull(result.first().posterUrl)
    }

    @Test
    fun enforcementDisabledPassesAllDownloads() {
        val result = filter.filterDownloads(
            policy = ContentPolicyState.unrestricted(),
            context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
            items = listOf(sensitiveDownload(), safeDownload()),
        )

        assertEquals(2, result.size)
        assertEquals("Explicit Adult Film", result[0].title)
        assertEquals("Family Movie", result[1].title)
    }

    // ── Helpers ──

    private fun lockedPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = false,
    )

    private fun signedOutPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = false,
    )

    private fun unknownAgePolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        ageBand = ContentAgeBand.UNKNOWN,
    )

    private fun adultEnabledPolicy() = ContentPolicyState(
        enforcementEnabled = true,
        isSignedIn = true,
        ageBand = ContentAgeBand.ADULT,
        adultEligible = true,
        sensitiveMaterialEnabled = true,
    )

    private fun sensitiveHistoryEntry() = WatchHistoryEntry(
        id = "h1",
        mediaId = "movie_1",
        mediaType = "movie",
        title = "Explicit Adult Film",
        posterUrl = "https://img/poster.jpg",
        backdropUrl = "https://img/backdrop.jpg",
        watchedAt = 1000L,
        durationWatchedMs = 5400000,
        seasonNumber = null,
        episodeNumber = null,
        showTitle = null,
    )

    private fun safeHistoryEntry() = WatchHistoryEntry(
        id = "h2",
        mediaId = "movie_2",
        mediaType = "movie",
        title = "Family Movie",
        posterUrl = "https://img/poster.jpg",
        backdropUrl = "https://img/backdrop.jpg",
        watchedAt = 2000L,
        durationWatchedMs = 7200000,
        seasonNumber = null,
        episodeNumber = null,
        showTitle = null,
    )

    private fun sensitiveDownload() = Download(
        id = "d1",
        mediaId = "movie_1",
        mediaType = MediaType.MOVIE,
        title = "Explicit Adult Film",
        posterUrl = "https://img/poster.jpg",
        streamUrl = "https://stream/file.mkv",
        filePath = "/data/downloads/file.mkv",
        status = DownloadStatus.COMPLETED,
    )

    private fun safeDownload() = Download(
        id = "d2",
        mediaId = "movie_2",
        mediaType = MediaType.MOVIE,
        title = "Family Movie",
        posterUrl = "https://img/poster.jpg",
        streamUrl = "https://stream/safe.mkv",
        filePath = "/data/downloads/safe.mkv",
        status = DownloadStatus.COMPLETED,
    )
}
