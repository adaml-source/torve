package com.torve.presentation.setup

import com.torve.data.debrid.DebridClient
import com.torve.data.panda.NzbIndexerRow
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.DebridServiceType
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.panda.PandaSetupUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks down the per-intent validators' green/yellow/red verdicts so the
 * wizard never silently saves a misconfigured path as ready. Each
 * validator is exercised across its core branches.
 */
class IntentValidatorsTest {

    // ── DEBRID ────────────────────────────────────────────────────────

    @Test
    fun `debrid validator returns NOT_STARTED when no provider selected`() = runTest {
        val client = newDebridClient(respond = HttpStatusCode.OK)
        val v = DebridIntentValidator(
            providerSource = { null },
            apiKeySource = { "anything" },
            debridClient = client,
        )
        val result = v.validate()
        assertEquals(SetupIntentStatus.NOT_STARTED, result.status)
    }

    @Test
    fun `debrid validator returns NOT_STARTED when api key missing`() = runTest {
        val client = newDebridClient(respond = HttpStatusCode.OK)
        val v = DebridIntentValidator(
            providerSource = { DebridServiceType.REAL_DEBRID },
            apiKeySource = { "" },
            debridClient = client,
        )
        val result = v.validate()
        assertEquals(SetupIntentStatus.NOT_STARTED, result.status)
        assertTrue(result.message?.contains("Real-Debrid") == true)
    }

    @Test
    fun `debrid validator returns READY on a successful verify`() = runTest {
        val client = newDebridClient(
            respond = HttpStatusCode.OK,
            body = """{"username":"adam","email":"a@x","type":"premium","premium":42}""",
        )
        val v = DebridIntentValidator(
            providerSource = { DebridServiceType.REAL_DEBRID },
            apiKeySource = { "good-key" },
            debridClient = client,
        )
        val result = v.validate()
        assertEquals(SetupIntentStatus.READY, result.status)
    }

    @Test
    fun `debrid validator returns INVALID when verify body is unparseable`() = runTest {
        // RD's RdUserResponse has all-default fields so a 401 body that
        // happens to be valid JSON deserializes fine. Send something
        // non-object (like an HTML error page or a JSON array) so
        // deserialization fails — production DebridClient catches the
        // exception and returns success=false.
        val client = newDebridClient(
            respond = HttpStatusCode.Unauthorized,
            body = "<html>401</html>",
        )
        val v = DebridIntentValidator(
            providerSource = { DebridServiceType.REAL_DEBRID },
            apiKeySource = { "bad-key" },
            debridClient = client,
        )
        val result = v.validate()
        assertEquals(SetupIntentStatus.INVALID, result.status)
        assertEquals("Re-enter API key", result.nextAction)
    }

    // ── IPTV ─────────────────────────────────────────────────────────

    @Test
    fun `iptv validator returns NOT_STARTED when no playlist added`() = runTest {
        val v = IptvIntentValidator { ChannelsUiState() }
        assertEquals(SetupIntentStatus.NOT_STARTED, v.validate().status)
    }

    @Test
    fun `iptv validator returns INVALID when parse error is set`() = runTest {
        val v = IptvIntentValidator {
            ChannelsUiState(
                playlists = listOf(playlist("p1", "TestList")),
                selectedPlaylistId = "p1",
                error = "bad m3u",
            )
        }
        val result = v.validate()
        assertEquals(SetupIntentStatus.INVALID, result.status)
        assertTrue(result.message?.contains("bad m3u") == true)
    }

    @Test
    fun `iptv validator ignores stale error when persisted playlist count proves channels loaded`() = runTest {
        val v = IptvIntentValidator {
            ChannelsUiState(
                playlists = listOf(playlist("p1", "8K", channelCount = 8_000)),
                selectedPlaylistId = "p1",
                channels = emptyList(),
                error = "error_channel_load_failed",
                epgState = EpgState.NotConfigured,
            )
        }
        val result = v.validate()
        assertEquals(SetupIntentStatus.READY, result.status)
        assertTrue(result.message?.contains("8000 channels") == true, "got ${result.message}")
    }

    @Test
    fun `iptv validator returns NEEDS_ATTENTION when parse OK but 0 channels`() = runTest {
        val v = IptvIntentValidator {
            ChannelsUiState(
                playlists = listOf(playlist("p1", "Empty")),
                selectedPlaylistId = "p1",
                channels = emptyList(),
                isLoadingChannels = false,
            )
        }
        assertEquals(SetupIntentStatus.NEEDS_ATTENTION, v.validate().status)
    }

    @Test
    fun `iptv validator returns READY when channels load and EPG matches majority`() = runTest {
        val v = IptvIntentValidator {
            ChannelsUiState(
                playlists = listOf(playlist("p1", "Good")),
                selectedPlaylistId = "p1",
                channels = listOf(makeChannel("c1"), makeChannel("c2")),
                epgState = EpgState.Loaded(
                    sourceUrl = "https://epg/x.xml",
                    sourceChannelCount = 100,
                    sourceProgrammeCount = 5_000,
                    matchedChannelCount = 80,
                    unmatchedChannelCount = 20,
                ),
            )
        }
        val result = v.validate()
        assertEquals(SetupIntentStatus.READY, result.status)
        assertTrue(result.message?.contains("80/100") == true, "got ${result.message}")
    }

    @Test
    fun `iptv validator returns NEEDS_ATTENTION when EPG match is below 50pct`() = runTest {
        val v = IptvIntentValidator {
            ChannelsUiState(
                playlists = listOf(playlist("p1", "Mid")),
                selectedPlaylistId = "p1",
                channels = listOf(makeChannel("c1")),
                epgState = EpgState.Loaded(
                    sourceUrl = "https://epg/x.xml",
                    sourceChannelCount = 10,
                    sourceProgrammeCount = 100,
                    matchedChannelCount = 3,
                    unmatchedChannelCount = 7,
                ),
            )
        }
        assertEquals(SetupIntentStatus.NEEDS_ATTENTION, v.validate().status)
    }

    // ── PLEX/JELLYFIN ────────────────────────────────────────────────

    @Test
    fun `plex jellyfin validator returns NOT_STARTED with both empty`() = runTest {
        val v = PlexJellyfinIntentValidator(
            serverUrlSource = { null },
            tokenSource = { null },
            service = StaticOverlay(true),
        )
        assertEquals(SetupIntentStatus.NOT_STARTED, v.validate().status)
    }

    @Test
    fun `plex jellyfin validator returns NEEDS_ATTENTION when only one of url+token`() = runTest {
        val v = PlexJellyfinIntentValidator(
            serverUrlSource = { "https://plex.example" },
            tokenSource = { "" },
            service = StaticOverlay(true),
        )
        assertEquals(SetupIntentStatus.NEEDS_ATTENTION, v.validate().status)
    }

    @Test
    fun `plex jellyfin validator returns READY on a successful test`() = runTest {
        val v = PlexJellyfinIntentValidator(
            serverUrlSource = { "https://plex.example" },
            tokenSource = { "tok" },
            service = StaticOverlay(true),
        )
        assertEquals(SetupIntentStatus.READY, v.validate().status)
    }

    @Test
    fun `plex jellyfin validator returns INVALID when service rejects credentials`() = runTest {
        val v = PlexJellyfinIntentValidator(
            serverUrlSource = { "https://plex.example" },
            tokenSource = { "bad" },
            service = StaticOverlay(false),
        )
        assertEquals(SetupIntentStatus.INVALID, v.validate().status)
    }

    @Test
    fun `plex jellyfin validator returns INVALID when service throws`() = runTest {
        val v = PlexJellyfinIntentValidator(
            serverUrlSource = { "https://plex.example" },
            tokenSource = { "tok" },
            service = ThrowingOverlay(),
        )
        val result = v.validate()
        assertEquals(SetupIntentStatus.INVALID, result.status)
        assertTrue(result.message?.contains("network down") == true)
    }

    // ── USENET ───────────────────────────────────────────────────────

    @Test
    fun `usenet validator returns NOT_STARTED when Panda config is empty`() = runTest {
        val v = UsenetIntentValidator { PandaSetupUiState(isEditMode = true) }
        assertEquals(SetupIntentStatus.NOT_STARTED, v.validate().status)
    }

    @Test
    fun `usenet validator returns NOT_STARTED when Panda is not in edit mode`() = runTest {
        val v = UsenetIntentValidator { PandaSetupUiState(isEditMode = false) }
        assertEquals(SetupIntentStatus.NOT_STARTED, v.validate().status)
    }

    @Test
    fun `usenet validator returns INVALID when no indexer + no download client`() = runTest {
        val v = UsenetIntentValidator {
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "secret",
                nzbIndexers = emptyList(),
                downloadClient = "none",
            )
        }
        assertEquals(SetupIntentStatus.INVALID, v.validate().status)
    }

    @Test
    fun `usenet validator returns INVALID when indexer keys are missing`() = runTest {
        val v = UsenetIntentValidator {
            PandaSetupUiState(
                isEditMode = true,
                nzbIndexers = listOf(NzbIndexerRow(type = "scenenzbs", url = "https://x", apiKey = "")),
                downloadClient = "torbox",
                downloadClientApiKey = "key123",
            )
        }
        val result = v.validate()
        assertEquals(SetupIntentStatus.INVALID, result.status)
        assertTrue(result.message?.contains("missing") == true)
    }

    @Test
    fun `usenet validator returns READY when all three legs are present`() = runTest {
        val v = UsenetIntentValidator {
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "secret",
                nzbIndexers = listOf(NzbIndexerRow(type = "scenenzbs", url = "https://x", apiKey = "key1")),
                downloadClient = "torbox",
                downloadClientApiKey = "key2",
            )
        }
        assertEquals(SetupIntentStatus.READY, v.validate().status)
    }

    @Test
    fun `usenet validator returns NEEDS_ATTENTION when one indexer of two is missing key`() = runTest {
        val v = UsenetIntentValidator {
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "secret",
                nzbIndexers = listOf(
                    NzbIndexerRow(type = "scenenzbs", url = "https://a", apiKey = "k1"),
                    NzbIndexerRow(type = "nzbgeek", url = "https://b", apiKey = ""),
                ),
                downloadClient = "torbox",
                downloadClientApiKey = "k2",
            )
        }
        assertEquals(SetupIntentStatus.NEEDS_ATTENTION, v.validate().status)
    }

    @Test
    fun `usenet validator detects redacted keys as missing`() = runTest {
        val v = UsenetIntentValidator {
            PandaSetupUiState(
                isEditMode = true,
                nzbIndexers = listOf(NzbIndexerRow(type = "scenenzbs", url = "https://x", apiKey = "***REDACTED***")),
                downloadClient = "torbox",
                downloadClientApiKey = "k1",
            )
        }
        // Treated as missing → INVALID (no usable indexer key).
        assertEquals(SetupIntentStatus.INVALID, v.validate().status)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun playlist(id: String, name: String, channelCount: Int = 0): ChannelPlaylist =
        ChannelPlaylist(id = id, name = name, url = "https://x", channelCount = channelCount)

    private fun makeChannel(id: String): com.torve.domain.model.EnrichedChannel =
        com.torve.domain.model.EnrichedChannel(
            channel = com.torve.domain.model.Channel(
                name = id,
                url = "https://x/$id",
                tvgId = id,
            ),
        )

    private fun newDebridClient(
        respond: HttpStatusCode,
        body: String = """{"error":"unauthorized"}""",
    ): DebridClient {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = respond,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return DebridClient(httpClient = httpClient, json = Json { ignoreUnknownKeys = true })
    }

    private class StaticOverlay(private val ok: Boolean) : LibraryOverlayService {
        override suspend fun isInLibrary(tmdbId: Int, mediaType: com.torve.domain.model.MediaType) = false
        override suspend fun getContinueWatching(limit: Int): List<com.torve.domain.model.WatchProgress> = emptyList()
        override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean = ok
    }

    private class ThrowingOverlay : LibraryOverlayService {
        override suspend fun isInLibrary(tmdbId: Int, mediaType: com.torve.domain.model.MediaType) = false
        override suspend fun getContinueWatching(limit: Int): List<com.torve.domain.model.WatchProgress> = emptyList()
        override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean =
            throw RuntimeException("network down")
    }
}
