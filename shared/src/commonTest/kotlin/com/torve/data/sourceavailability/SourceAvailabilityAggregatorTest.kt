package com.torve.data.sourceavailability

import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAvailabilityAggregatorTest {

    private class FakeProvider(
        override val kind: SourceAvailabilityKind,
        private val signal: SourceAvailabilitySignal?,
        private val matchTmdbId: Int? = null,
    ) : SourceAvailabilityProvider {
        var probeCount: Int = 0
        override suspend fun probe(
            tmdbId: Int,
            mediaType: MediaType,
        ): SourceAvailabilitySignal? {
            probeCount += 1
            if (matchTmdbId != null && tmdbId != matchTmdbId) return null
            return signal
        }
    }

    private class ThrowingProvider(
        override val kind: SourceAvailabilityKind,
    ) : SourceAvailabilityProvider {
        override suspend fun probe(
            tmdbId: Int,
            mediaType: MediaType,
        ): SourceAvailabilitySignal? = throw RuntimeException("network")
    }

    private val downloadedSignal = SourceAvailabilitySignal(
        SourceAvailabilityKind.LOCAL_DOWNLOAD, "Downloaded", SourceAvailabilityRankBoost.LOCAL_DOWNLOAD,
    )
    private val plexSignal = SourceAvailabilitySignal(
        SourceAvailabilityKind.PLEX, "In Plex", SourceAvailabilityRankBoost.PLEX,
    )

    @Test
    fun `lookup merges signals from every provider that matches`() = runTest {
        val agg = DefaultSourceAvailabilityAggregator(
            providers = listOf(
                FakeProvider(SourceAvailabilityKind.LOCAL_DOWNLOAD, downloadedSignal),
                FakeProvider(SourceAvailabilityKind.PLEX, plexSignal),
            ),
        )
        val record = agg.lookup(42, MediaType.MOVIE)
        assertEquals(2, record.signals.size)
        assertEquals(SourceAvailabilityRankBoost.LOCAL_DOWNLOAD, record.score)
        assertEquals("Downloaded", record.primaryBadge?.badge)
    }

    @Test
    fun `lookup returns empty record when no provider matches`() = runTest {
        val agg = DefaultSourceAvailabilityAggregator(
            providers = listOf(
                FakeProvider(SourceAvailabilityKind.LOCAL_DOWNLOAD, signal = null),
                FakeProvider(SourceAvailabilityKind.PLEX, signal = null),
            ),
        )
        val record = agg.lookup(1, MediaType.SERIES)
        assertTrue(record.signals.isEmpty())
        assertTrue(!record.isAvailable)
    }

    @Test
    fun `lookup tolerates a throwing provider without dropping the others`() = runTest {
        val agg = DefaultSourceAvailabilityAggregator(
            providers = listOf(
                ThrowingProvider(SourceAvailabilityKind.LOCAL_DOWNLOAD),
                FakeProvider(SourceAvailabilityKind.PLEX, plexSignal),
            ),
        )
        val record = agg.lookup(7, MediaType.MOVIE)
        assertEquals(1, record.signals.size)
        assertEquals(SourceAvailabilityKind.PLEX, record.signals.single().kind)
    }

    @Test
    fun lookupCachesPerTmdbIdAndMediaType() = runTest {
        val provider = FakeProvider(SourceAvailabilityKind.PLEX, plexSignal)
        val agg = DefaultSourceAvailabilityAggregator(providers = listOf(provider))
        agg.lookup(11, MediaType.MOVIE)
        agg.lookup(11, MediaType.MOVIE)
        agg.lookup(11, MediaType.SERIES)        // different mediaType → new probe
        assertEquals(2, provider.probeCount)
        agg.invalidate()
        agg.lookup(11, MediaType.MOVIE)
        assertEquals(3, provider.probeCount)
    }

    @Test
    fun lookupBatchDedupesByTmdbIdAndMediaType() = runTest {
        val provider = FakeProvider(SourceAvailabilityKind.PLEX, plexSignal)
        val agg = DefaultSourceAvailabilityAggregator(providers = listOf(provider))
        val items = listOf(
            1 to MediaType.MOVIE,
            1 to MediaType.MOVIE,
            2 to MediaType.MOVIE,
        )
        val out = agg.lookupBatch(items)
        assertEquals(2, out.size)
        assertNotNull(out[1])
        assertNotNull(out[2])
    }

    // ── LocalDownloadSourceAvailabilityProvider ───────────────────

    private class FakeDownloadRepository(
        private val rows: List<Download>,
    ) : DownloadRepository {
        override suspend fun enqueueDownload(download: Download) = error("not used")
        override suspend fun getAllDownloads(): List<Download> = rows
        override suspend fun getPendingDownloads(): List<Download> = rows.filter { it.status == DownloadStatus.PENDING }
        override suspend fun getCompletedDownloads(): List<Download> = rows.filter { it.status == DownloadStatus.COMPLETED }
        override suspend fun getDownload(id: String): Download? = rows.firstOrNull { it.id == id }
        override suspend fun getDownloadByMediaId(mediaId: String): Download? = rows.firstOrNull { it.mediaId == mediaId }
        override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) {}
        override suspend fun markCompleted(id: String, filePath: String) {}
        override suspend fun updateFileSize(id: String, fileSizeBytes: Long) {}
        override suspend fun deleteDownload(id: String) {}
        override suspend fun pauseDownload(id: String) {}
        override suspend fun resumeDownload(id: String) {}
    }

    @Test
    fun `local download provider returns Downloaded for completed tmdb item`() = runTest {
        val rows = listOf(
            Download(
                id = "d1", mediaId = "12345", mediaType = MediaType.MOVIE,
                title = "Inception", streamUrl = "https://x", status = DownloadStatus.COMPLETED,
            ),
        )
        val provider = LocalDownloadSourceAvailabilityProvider(FakeDownloadRepository(rows))
        val signal = provider.probe(12345, MediaType.MOVIE)
        assertNotNull(signal)
        assertEquals("Downloaded", signal.badge)
        assertEquals(SourceAvailabilityKind.LOCAL_DOWNLOAD, signal.kind)
    }

    @Test
    fun `local download provider also accepts tmdb-prefixed mediaId`() = runTest {
        val rows = listOf(
            Download(
                id = "d1", mediaId = "tmdb:9999", mediaType = MediaType.MOVIE,
                title = "X", streamUrl = "u", status = DownloadStatus.COMPLETED,
            ),
        )
        val provider = LocalDownloadSourceAvailabilityProvider(FakeDownloadRepository(rows))
        assertNotNull(provider.probe(9999, MediaType.MOVIE))
    }

    @Test
    fun `local download provider ignores incomplete rows`() = runTest {
        val rows = listOf(
            Download(
                id = "d1", mediaId = "1", mediaType = MediaType.MOVIE,
                title = "X", streamUrl = "u", status = DownloadStatus.DOWNLOADING,
            ),
        )
        val provider = LocalDownloadSourceAvailabilityProvider(FakeDownloadRepository(rows))
        assertNull(provider.probe(1, MediaType.MOVIE))
    }

    @Test
    fun `local download provider ignores wrong mediaType`() = runTest {
        val rows = listOf(
            Download(
                id = "d1", mediaId = "5", mediaType = MediaType.MOVIE,
                title = "X", streamUrl = "u", status = DownloadStatus.COMPLETED,
            ),
        )
        val provider = LocalDownloadSourceAvailabilityProvider(FakeDownloadRepository(rows))
        assertNull(provider.probe(5, MediaType.SERIES))
    }

    @Test
    fun `local download provider does not match nzb-prefixed mediaIds`() = runTest {
        val rows = listOf(
            Download(
                id = "d1", mediaId = "nzb_movies_abc", mediaType = MediaType.MOVIE,
                title = "X", streamUrl = "u", status = DownloadStatus.COMPLETED,
            ),
        )
        val provider = LocalDownloadSourceAvailabilityProvider(FakeDownloadRepository(rows))
        assertNull(provider.probe(123, MediaType.MOVIE))
    }
}
