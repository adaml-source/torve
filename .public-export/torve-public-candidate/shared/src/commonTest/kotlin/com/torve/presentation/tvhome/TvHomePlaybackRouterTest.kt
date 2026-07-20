package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Pins the one-click TV-Home routing rules:
 *   - Local file always wins.
 *   - LAN match autoplays unless cellular guard suppresses it.
 *   - Provider-only items punt to the detail screen.
 *   - Items with no signal punt to detail too.
 */
class TvHomePlaybackRouterTest {

    private fun item(tmdbId: Int = 42, title: String = "Sherlock"): MediaItem =
        MediaItem(id = "movie:$tmdbId", tmdbId = tmdbId, type = MediaType.MOVIE, title = title)

    private fun signal(
        kind: SourceAvailabilityKind,
        boost: Int = 100,
    ): SourceAvailabilitySignal = SourceAvailabilitySignal(
        kind = kind, badge = kind.name, rankBoost = boost,
    )

    private fun record(tmdbId: Int, vararg signals: SourceAvailabilitySignal): SourceAvailabilityRecord =
        SourceAvailabilityRecord(tmdbId = tmdbId, mediaType = MediaType.MOVIE, signals = signals.toList())

    @Test
    fun `local download wins even when LAN match also exists`() = runTest {
        val item = item()
        val download = Download(
            id = "d1",
            mediaId = item.tmdbId.toString(),
            mediaType = MediaType.MOVIE,
            title = item.title,
            streamUrl = "https://upstream/x.mkv",
            filePath = "/storage/dl/x.mkv",
            status = DownloadStatus.COMPLETED,
        )
        val repo = StubDownloadRepository(byMediaId = mapOf(item.tmdbId.toString() to download))
        val router = TvHomePlaybackRouter(repo)

        val decision = router.resolve(
            item = item,
            availability = mapOf(item.tmdbId!! to record(
                item.tmdbId!!,
                signal(SourceAvailabilityKind.LOCAL_DOWNLOAD, SourceAvailabilityRankBoost.LOCAL_DOWNLOAD),
            )),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        val autoplay = assertIs<TvHomePlaybackDecision.AutoplayLocal>(decision)
        assertEquals("/storage/dl/x.mkv", autoplay.absolutePath)
    }

    @Test
    fun `non-completed download is ignored`() = runTest {
        val item = item()
        val pending = Download(
            id = "d1",
            mediaId = item.tmdbId.toString(),
            mediaType = MediaType.MOVIE,
            title = item.title,
            streamUrl = "https://upstream/x.mkv",
            filePath = null,
            status = DownloadStatus.DOWNLOADING,
        )
        val repo = StubDownloadRepository(byMediaId = mapOf(item.tmdbId.toString() to pending))
        val router = TvHomePlaybackRouter(repo)

        val decision = router.resolve(
            item = item,
            availability = emptyMap(),
            lanTitlesLowercase = emptySet(),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        // No local file path → no LAN match → OpenDetail.
        assertEquals(TvHomePlaybackDecision.OpenDetail, decision)
    }

    @Test
    fun `LAN match autoplays when not on cellular`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "Sherlock"),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        val lan = assertIs<TvHomePlaybackDecision.AutoplayLan>(decision)
        assertEquals("Sherlock", lan.title)
    }

    @Test
    fun `LAN match autoplays on ethernet too`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "Sherlock"),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.ETHERNET,
            wifiOnlyForLan = true,
        )
        assertIs<TvHomePlaybackDecision.AutoplayLan>(decision)
    }

    @Test
    fun `LAN match suppressed on cellular when wifi-only`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "Sherlock"),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        // Caller chose to gate LAN on cellular — open detail instead so
        // the user sees a labeled choice rather than a silently-dropped
        // candidate.
        assertEquals(TvHomePlaybackDecision.OpenDetail, decision)
    }

    @Test
    fun `LAN match plays on cellular when wifi-only is false`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "Sherlock"),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = false,
        )
        assertIs<TvHomePlaybackDecision.AutoplayLan>(decision)
    }

    @Test
    fun `LAN title match is case- and whitespace-insensitive`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "  Sherlock  "),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertIs<TvHomePlaybackDecision.AutoplayLan>(decision)
    }

    @Test
    fun `provider-only availability defers to detail screen`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val item = item()
        val decision = router.resolve(
            item = item,
            availability = mapOf(item.tmdbId!! to record(
                item.tmdbId!!,
                signal(SourceAvailabilityKind.DEBRID_CACHE, SourceAvailabilityRankBoost.DEBRID_CACHE),
            )),
            lanTitlesLowercase = emptySet(),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        // Provider streams need explicit source disambiguation.
        assertEquals(TvHomePlaybackDecision.OpenDetail, decision)
    }

    @Test
    fun `no signal at all defers to detail screen`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(),
            availability = emptyMap(),
            lanTitlesLowercase = emptySet(),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(TvHomePlaybackDecision.OpenDetail, decision)
    }

    @Test
    fun `download repo failure does not crash router`() = runTest {
        val router = TvHomePlaybackRouter(ThrowingDownloadRepository())
        val decision = router.resolve(
            item = item(),
            availability = emptyMap(),
            lanTitlesLowercase = emptySet(),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(TvHomePlaybackDecision.OpenDetail, decision)
    }

    @Test
    fun `single-OK contract - AutoplayLocal carries the path the caller needs to launch directly`() = runTest {
        // Pins the Prompt 11B/11C "<3 remote actions" promise: the
        // decision MUST carry enough info for the caller to navigate
        // straight to the player route without looking the path up
        // again. If a future refactor returns just `Autoplay` with no
        // payload, the TV layer would have to re-query the repo (a
        // second OK from the user's perspective if it blocks the UI).
        val item = item()
        val download = Download(
            id = "d1",
            mediaId = item.tmdbId.toString(),
            mediaType = MediaType.MOVIE,
            title = item.title,
            streamUrl = "https://upstream/x.mkv",
            filePath = "/storage/dl/x.mkv",
            status = DownloadStatus.COMPLETED,
        )
        val router = TvHomePlaybackRouter(
            StubDownloadRepository(byMediaId = mapOf(item.tmdbId.toString() to download)),
        )
        val decision = router.resolve(
            item = item,
            availability = emptyMap(),
            lanTitlesLowercase = emptySet(),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        val auto = assertIs<TvHomePlaybackDecision.AutoplayLocal>(decision)
        assertTrue(auto.absolutePath.isNotBlank(), "caller must have a path to launch directly")
    }

    @Test
    fun `single-OK contract - AutoplayLan carries title for token-mint without a second click`() = runTest {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val decision = router.resolve(
            item = item(title = "Sherlock"),
            availability = emptyMap(),
            lanTitlesLowercase = setOf("sherlock"),
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        val lan = assertIs<TvHomePlaybackDecision.AutoplayLan>(decision)
        // Title (not just an opaque marker) so the caller can call
        // LanLibraryConsumer.findLanRoute() directly. Single-OK from
        // the user's perspective: tile OK → router → mint LAN token →
        // player. No detail screen detour.
        assertTrue(lan.title.isNotBlank())
    }

    @Test
    fun `hasPlayablePath excludes WATCH_HISTORY-only items`() {
        val router = TvHomePlaybackRouter(StubDownloadRepository())
        val item = item(tmdbId = 7)
        val historyOnly = mapOf(7 to record(
            7,
            signal(SourceAvailabilityKind.WATCH_HISTORY, SourceAvailabilityRankBoost.WATCH_HISTORY),
        ))
        assertFalse(router.hasPlayablePath(item, historyOnly))

        val withDebrid = mapOf(7 to record(
            7,
            signal(SourceAvailabilityKind.WATCH_HISTORY, SourceAvailabilityRankBoost.WATCH_HISTORY),
            signal(SourceAvailabilityKind.DEBRID_CACHE, SourceAvailabilityRankBoost.DEBRID_CACHE),
        ))
        assertTrue(router.hasPlayablePath(item, withDebrid))
    }
}

private class StubDownloadRepository(
    private val byMediaId: Map<String, Download> = emptyMap(),
) : DownloadRepository {
    override suspend fun enqueueDownload(download: Download): Download = error("not used")
    override suspend fun getAllDownloads(): List<Download> = byMediaId.values.toList()
    override suspend fun getPendingDownloads(): List<Download> = emptyList()
    override suspend fun getCompletedDownloads(): List<Download> = byMediaId.values.toList()
    override suspend fun getDownload(id: String): Download? = byMediaId.values.firstOrNull { it.id == id }
    override suspend fun getDownloadByMediaId(mediaId: String): Download? = byMediaId[mediaId]
    override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) = Unit
    override suspend fun markCompleted(id: String, filePath: String) = Unit
    override suspend fun updateFileSize(id: String, fileSizeBytes: Long) = Unit
    override suspend fun deleteDownload(id: String) = Unit
    override suspend fun pauseDownload(id: String) = Unit
    override suspend fun resumeDownload(id: String) = Unit
}

private class ThrowingDownloadRepository : DownloadRepository {
    override suspend fun enqueueDownload(download: Download): Download = error("boom")
    override suspend fun getAllDownloads(): List<Download> = error("boom")
    override suspend fun getPendingDownloads(): List<Download> = error("boom")
    override suspend fun getCompletedDownloads(): List<Download> = error("boom")
    override suspend fun getDownload(id: String): Download? = error("boom")
    override suspend fun getDownloadByMediaId(mediaId: String): Download? = error("boom")
    override suspend fun updateProgress(id: String, downloadedBytes: Long, status: DownloadStatus) = error("boom")
    override suspend fun markCompleted(id: String, filePath: String) = error("boom")
    override suspend fun updateFileSize(id: String, fileSizeBytes: Long) = error("boom")
    override suspend fun deleteDownload(id: String) = error("boom")
    override suspend fun pauseDownload(id: String) = error("boom")
    override suspend fun resumeDownload(id: String) = error("boom")
}
