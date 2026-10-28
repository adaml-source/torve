package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceDiscoveryTest {
    private val panda = InstalledAddon("https://panda.torve.app/u/test/manifest.json",
        AddonManifest("panda", "Panda", "1.0.0", resources = listOf("stream")))

    @Test
    fun fullSearchKeepsSlowUsenetResultsWithTorrentioDisabled() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request.url.encodedPath
                assertTrue(request.url.host == "panda.torve.app")
                assertTrue(request.url.encodedPath.contains("/stream/series/tt0386676:1:5.json"))
                delay(14_000)
                respond("""{"streams":[{"name":"Treasure Maps+torbox","title":"The.Office.US.S01E05.GERMAN.1080p","url":"https://panda.torve.app/u/test/nzb/fixture"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val client = HttpClient(engine)
        try {
            val aggregator = StreamAggregator(StremioAddonClient(client, Json { ignoreUnknownKeys = true }),
                DebridClient(client, Json), StreamScorer())
            val disabled = panda.copy(manifestUrl = "https://torrentio.strem.fun/manifest.json", isEnabled = false)
            val streams = aggregator.resolveStreams(listOf(panda, disabled), MediaType.SERIES, "tt0386676", 1, 5,
                preferences = StreamPreferences(cachedOnly = false))
            assertEquals(1, streams.size)
            assertTrue(streams.single().isUsenetStream())
            assertEquals(1, requests.size, "No redundant manifest fetch or retry after a successful source search")
        } finally { client.close() }
    }

    @Test
    fun startupDeadlineStaysShortAndLongDeadlineIsOnlyForPanda() {
        assertEquals(1_800L, addonSearchTimeoutMs(panda.manifestUrl, StreamFetchPolicy.PLAYBACK_STARTUP))
        assertEquals(20_000L, addonSearchTimeoutMs(panda.manifestUrl, StreamFetchPolicy.FULL))
        assertEquals(10_000L, addonSearchTimeoutMs("https://other.example/panda.torve.app", StreamFetchPolicy.FULL))
    }

    @Test
    fun typeAndProviderFiltersRetainUsenetAndSeparateTorrentProviders() {
        val usenet = parse("Treasure Maps+torbox", "https://panda.torve.app/u/test/nzb/file")
        val torrent = parse("[RD+] Torrentio", "https://debrid.example/video.mkv")
        assertTrue(SourceFilters.matches(usenet, "ALL"))
        assertTrue(SourceFilters.matches(usenet, "USENET"))
        assertFalse(SourceFilters.matches(usenet, "TORRENT"))
        assertFalse(SourceFilters.matches(usenet, "TORRENTIO"))
        assertTrue(SourceFilters.matches(torrent, "TORRENT"))
        assertTrue(SourceFilters.matches(torrent, "TORRENTIO"))
        assertTrue(SourceFilters.matches(torrent, "REAL_DEBRID"))
        assertFalse(SourceFilters.matches(torrent, "USENET"))
        assertEquals("Treasure Maps+torbox", SourceFilters.providerLabel(usenet))
        assertTrue(SourceFilters.options(listOf(usenet, torrent)).any { it.key == "REAL_DEBRID" })
    }

    @Test
    fun indexerAudioAndSubtitleMetadataStaySeparate() {
        val stream = StreamParser.parse(StremioStream(name = "Treasure Maps (NZB)",
            title = "The.Office.US.S01E05.1080p | 🗣️ English | 💬 German, French",
            url = "https://panda.torve.app/u/test/nzb/file"), "Panda", "https://panda.torve.app")
        assertFalse(SourceLanguages.matchesPreference(stream, "DE", false))
        assertTrue(SourceLanguages.matchesPreference(stream, "DE", true))
        assertTrue(SourceLanguages.matchesPreference(stream, "FR", true))
        assertTrue(SourceLanguages.matchesPreference(stream, "EN", false))
    }

    private fun parse(name: String, url: String) = StreamParser.parse(
        StremioStream(name = name, title = "The.Office.US.S01E05.GERMAN.1080p", url = url),
        "Panda", "https://panda.torve.app")

    @Test
    fun torBoxProgressUsesTheSelectedDownloadServiceRatherThanUsenetCategory() {
        fun source(name: String, service: String? = null) = StreamParser.parse(StremioStream(
            name = name, title = "Example.Release.1080p", url = "https://extension.example/nzb/file",
            behaviorHints = StemioBehaviorHints(service = service)), "AIOStreams")
        for (badge in listOf("[TB]", "[TB+]", "[TB ⏳]", "[TB ⚡]", "[TB ☁️]", "TorBox")) {
            val stream = source("$badge Newznab 1080p")
            assertTrue(SourceFilters.supportsTorBoxUsenetProgress(stream), badge)
            assertTrue(SourceFilters.matches(stream, "TORBOX"), badge)
        }
        val explicit = source("Newznab", " TorBox ")
        assertEquals("torbox", explicit.downloadService)
        assertTrue(SourceFilters.supportsTorBoxUsenetProgress(explicit))
        for (name in listOf("Easynews", "Newznab", "NZBDav", "[PM+] Newznab", "[RD+] Newznab", "NotTorBox Newznab")) {
            assertFalse(SourceFilters.supportsTorBoxUsenetProgress(source(name)), name)
        }
        assertFalse(SourceFilters.supportsTorBoxUsenetProgress(source("[TB ⚡] Newznab", "premiumize")),
            "An explicit service overrides decorative labels")
        val torrent = explicit.copy(addonName = "[TB+] Torrentio", directUrl = "https://extension.example/video.mkv")
        assertFalse(SourceFilters.supportsTorBoxUsenetProgress(torrent), "TorBox torrents are outside the Usenet status API")
    }
}
