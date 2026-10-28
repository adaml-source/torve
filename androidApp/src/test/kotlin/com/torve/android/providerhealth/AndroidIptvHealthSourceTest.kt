package com.torve.android.providerhealth

import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.channels.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidIptvHealthSourceTest {
    @Test fun unopenedChannelsUseOnlySavedPlaylistSummaryForHealth() = runBlocking {
        var reads = 0
        val source = AndroidIptvHealthSource(ChannelsStateRelay()) {
            reads++
            listOf(ChannelPlaylist("saved", "Saved TV", "https://example.test/list", channelCount = 18000,
                epgUrl = "https://example.test/guide"))
        }
        val check = source.checker(epg = false).check()
        assertEquals(ProviderHealthStatus.GREEN, check.status)
        assertTrue(check.message.orEmpty().contains("18000"))
        assertEquals(1, reads)
        assertEquals(ProviderHealthStatus.UNKNOWN, source.checker(epg = true).check().status)
    }

    @Test fun openedChannelsRemainTheAuthoritativeHealthSource() = runBlocking {
        val live = ChannelsUiState(playlists = listOf(ChannelPlaylist("live", "Current", "https://example.test/list")),
            selectedPlaylistId = "live", error = "Offline")
        val relay = ChannelsStateRelay().also { it.attach(MutableStateFlow(live)) }
        val source = AndroidIptvHealthSource(relay) { error("A live screen must not be replaced by saved state") }
        assertSame(live, source.snapshot())
        assertEquals(ProviderHealthStatus.RED, source.checker(epg = false).check().status)
    }
}
