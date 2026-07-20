package com.torve.presentation.providerhealth

import com.torve.data.panda.NzbIndexerRow
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.panda.PandaSetupUiState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IptvAndPandaProviderHealthCheckerTest {

    // ── IPTV playlist ────────────────────────────────────────────────

    @Test
    fun `iptv reports unconfigured when no playlists`() = runTest {
        val out = IptvProviderHealthChecker { ChannelsUiState() }.check()
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
        assertEquals(ProviderHealthCategory.IPTV, out.category)
    }

    @Test
    fun `iptv reports green when cached catalog exists before playlist rows hydrate`() = runTest {
        val state = ChannelsUiState(
            playlists = emptyList(),
            selectedPlaylistId = "p1",
            categories = listOf(com.torve.domain.model.ChannelCategory("News", 42)),
            groupedChannels = mapOf(
                "News" to listOf(
                    com.torve.domain.model.EnrichedChannel(
                        channel = com.torve.domain.model.Channel(
                            name = "ZDF",
                            url = "u",
                            playlistId = "p1",
                        ),
                    ),
                ),
            ),
        )

        val out = IptvProviderHealthChecker { state }.check()

        assertEquals(ProviderHealthStatus.GREEN, out.status)
        assertTrue(out.message?.contains("catalog is loaded") == true, "message was ${out.message}")
    }

    @Test
    fun `iptv reports green when channels loaded`() = runTest {
        val state = ChannelsUiState(
            playlists = listOf(com.torve.domain.model.ChannelPlaylist(
                id = "p1", name = "My Provider", url = "https://x", epgUrl = null,
            )),
            selectedPlaylistId = "p1",
            channels = (1..42).map {
                com.torve.domain.model.EnrichedChannel(
                    channel = com.torve.domain.model.Channel(
                        name = "Ch$it", url = "u", playlistId = "p1",
                    ),
                )
            },
        )
        val out = IptvProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
        assertTrue(out.message?.contains("42 channels") == true, "message was ${out.message}")
        assertTrue(out.message?.contains("My Provider") == true)
    }

    @Test
    fun `iptv reports yellow when playlist loaded with zero channels`() = runTest {
        val state = ChannelsUiState(
            playlists = listOf(com.torve.domain.model.ChannelPlaylist(
                id = "p1", name = "Empty", url = "https://x", epgUrl = null,
            )),
            selectedPlaylistId = "p1",
            channels = emptyList(),
            isLoadingChannels = false,
        )
        val out = IptvProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.YELLOW, out.status)
    }

    @Test
    fun `iptv reports red on parse error`() = runTest {
        val state = ChannelsUiState(
            playlists = listOf(com.torve.domain.model.ChannelPlaylist(
                id = "p1", name = "Bad", url = "https://x", epgUrl = null,
            )),
            selectedPlaylistId = "p1",
            error = "Invalid M3U: missing #EXTM3U header",
        )
        val out = IptvProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.RED, out.status)
        assertTrue(out.message?.contains("Invalid M3U") == true)
    }

    @Test
    fun `iptv reports green when persisted playlist count exists despite stale error`() = runTest {
        val state = ChannelsUiState(
            playlists = listOf(com.torve.domain.model.ChannelPlaylist(
                id = "p1",
                name = "8K",
                url = "https://x",
                epgUrl = null,
                channelCount = 8_000,
            )),
            selectedPlaylistId = "p1",
            channels = emptyList(),
            categories = emptyList(),
            groupedChannels = emptyMap(),
            error = "error_channel_load_failed",
        )
        val out = IptvProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
        assertTrue(out.message?.contains("8000 channels") == true, "message was ${out.message}")
    }

    // ── EPG ─────────────────────────────────────────────────────────

    @Test
    fun `epg unconfigured`() = runTest {
        val out = IptvEpgProviderHealthChecker { ChannelsUiState() }.check()
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
        assertEquals(ProviderHealthCategory.EPG, out.category)
    }

    @Test
    fun `epg green at high match rate`() = runTest {
        val state = ChannelsUiState(
            epgState = EpgState.Loaded(
                sourceUrl = "https://epg",
                sourceChannelCount = 100,
                sourceProgrammeCount = 1000,
                matchedChannelCount = 80,
                unmatchedChannelCount = 20,
            ),
        )
        val out = IptvEpgProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }

    @Test
    fun `epg green at low match rate when programmes are available`() = runTest {
        val state = ChannelsUiState(
            epgState = EpgState.Loaded(
                sourceUrl = "https://epg",
                sourceChannelCount = 100,
                sourceProgrammeCount = 1000,
                matchedChannelCount = 10,
                unmatchedChannelCount = 90,
            ),
        )
        val out = IptvEpgProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
        assertTrue(out.message?.contains("Optional mapping") == true)
    }

    @Test
    fun `epg loaded with empty match counters is not a warning`() = runTest {
        val state = ChannelsUiState(
            epgState = EpgState.Loaded(
                sourceUrl = "https://epg",
                sourceChannelCount = 0,
                sourceProgrammeCount = 0,
                matchedChannelCount = 0,
                unmatchedChannelCount = 0,
            ),
        )
        val out = IptvEpgProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
        assertEquals("EPG data is loaded.", out.message)
        assertEquals(null, out.nextAction)
    }

    @Test
    fun `epg red on error`() = runTest {
        val state = ChannelsUiState(epgState = EpgState.Error("404 not found"))
        val out = IptvEpgProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.RED, out.status)
    }

    // ── Panda Usenet stack ─────────────────────────────────────────

    @Test
    fun `panda indexer unconfigured when not in edit mode`() = runTest {
        val out = PandaUsenetProviderHealthChecker { PandaSetupUiState() }.check()
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
        assertEquals("panda:usenet_indexer", out.providerKey)
    }

    @Test
    fun `panda indexer green when all rows have api keys`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            nzbIndexers = listOf(
                NzbIndexerRow(type = "scenenzbs", url = "https://scenenzbs.com", apiKey = "real-key-1"),
                NzbIndexerRow(type = "nzbgeek", url = "https://api.nzbgeek.info", apiKey = "real-key-2"),
            ),
        )
        val out = PandaUsenetProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }

    @Test
    fun `panda indexer red when keys redacted on this device`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            nzbIndexers = listOf(
                NzbIndexerRow(type = "scenenzbs", url = "https://x", apiKey = "[redacted]"),
            ),
        )
        val out = PandaUsenetProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.RED, out.status)
    }

    @Test
    fun `panda indexer yellow when partial coverage`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            nzbIndexers = listOf(
                NzbIndexerRow(type = "scenenzbs", url = "https://x", apiKey = "real"),
                NzbIndexerRow(type = "nzbgeek", url = "https://y", apiKey = ""),
            ),
        )
        val out = PandaUsenetProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.YELLOW, out.status)
    }

    @Test
    fun `panda usenet provider checker green with password`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            enableUsenet = true,
            usenetProvider = "newshosting",
            usenetPassword = "real-password",
        )
        val out = PandaUsenetProviderProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }

    @Test
    fun `panda download client green when api key present`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            downloadClient = "torbox",
            downloadClientApiKey = "real-key",
        )
        val out = PandaDownloadClientProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }

    @Test
    fun `panda download client red when only redacted keys present`() = runTest {
        val state = PandaSetupUiState(
            isEditMode = true,
            downloadClient = "torbox",
            downloadClientApiKey = "[redacted]",
            downloadClientPassword = "[redacted]",
        )
        val out = PandaDownloadClientProviderHealthChecker { state }.check()
        assertEquals(ProviderHealthStatus.RED, out.status)
    }

    // ── Trakt / SIMKL token presence ───────────────────────────────

    @Test
    fun `trakt unconfigured without token`() = runTest {
        val out = TraktProviderHealthChecker { null }.check()
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
    }

    @Test
    fun `trakt green with token`() = runTest {
        val out = TraktProviderHealthChecker { "abc" }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }

    @Test
    fun `simkl unconfigured without token`() = runTest {
        val out = SimklProviderHealthChecker { "" }.check()
        assertEquals(ProviderHealthStatus.UNCONFIGURED, out.status)
    }

    @Test
    fun `simkl green with token`() = runTest {
        val out = SimklProviderHealthChecker { "tok" }.check()
        assertEquals(ProviderHealthStatus.GREEN, out.status)
    }
}
