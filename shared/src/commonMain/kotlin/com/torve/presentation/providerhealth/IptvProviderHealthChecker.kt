package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState

/**
 * Inspects the live [ChannelsUiState] (already maintained by
 * `ChannelsViewModel`, which fetches and parses the user's M3U/Xtream
 * playlists) and converts it into provider-health rows.
 *
 * Splits into two entries: the IPTV playlist itself, and the matched EPG
 * data. UI groups them under the IPTV intent automatically — both
 * categories are listed in [com.torve.presentation.setup.SetupIntent.IPTV].
 *
 * No new network calls — this checker is a pure projection. The
 * underlying `ChannelsViewModel` is the single source of truth for both
 * playlist health and EPG health and already publishes errors as state.
 */
class IptvProviderHealthChecker(
    private val stateSource: () -> ChannelsUiState,
) : ProviderHealthChecker {

    override val providerKey: String = "iptv:active"

    override suspend fun check(): ProviderHealthEntry {
        val state = stateSource()
        if (state.playlists.isEmpty()) {
            return playlistEntry(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "No IPTV playlist added.",
                nextAction = "Add a playlist",
            )
        }
        val activeId = state.selectedPlaylistId
        val activeName = activeId?.let { id -> state.playlists.firstOrNull { it.id == id }?.name }
            ?: state.playlists.first().name
        val channelCount = state.channels.size
        val parseError = state.error
        return when {
            parseError != null -> playlistEntry(
                status = ProviderHealthStatus.RED,
                message = "Couldn't load \"$activeName\": $parseError",
                nextAction = "Re-add or refresh playlist",
            )
            channelCount == 0 && state.isLoadingChannels -> playlistEntry(
                status = ProviderHealthStatus.UNKNOWN,
                message = "Loading \"$activeName\"…",
                nextAction = null,
            )
            channelCount == 0 -> playlistEntry(
                status = ProviderHealthStatus.YELLOW,
                message = "\"$activeName\" loaded with 0 channels.",
                nextAction = "Refresh playlist",
            )
            else -> playlistEntry(
                status = ProviderHealthStatus.GREEN,
                message = "\"$activeName\" — $channelCount channels.",
                nextAction = null,
            )
        }
    }

    private fun playlistEntry(
        status: ProviderHealthStatus,
        message: String,
        nextAction: String?,
    ): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.IPTV,
        providerKey = providerKey,
        label = "IPTV playlist",
        status = status,
        message = message,
        nextAction = nextAction,
    )
}

/**
 * Reads the EPG slice of [ChannelsUiState]. Sits in its own row so the UI
 * can show "playlist green / EPG yellow" without one masking the other.
 */
class IptvEpgProviderHealthChecker(
    private val stateSource: () -> ChannelsUiState,
) : ProviderHealthChecker {

    override val providerKey: String = "iptv:epg"

    override suspend fun check(): ProviderHealthEntry {
        val epg = stateSource().epgState
        return when (epg) {
            EpgState.NotConfigured -> entry(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "EPG URL not set.",
                nextAction = "Add an EPG source",
            )
            EpgState.Loading -> entry(
                status = ProviderHealthStatus.UNKNOWN,
                message = "Loading EPG data…",
            )
            is EpgState.Error -> entry(
                status = ProviderHealthStatus.RED,
                message = "EPG load failed: ${epg.message}",
                nextAction = "Check EPG URL",
            )
            is EpgState.Loaded -> {
                val matched = epg.matchedChannelCount
                val total = matched + epg.unmatchedChannelCount
                if (total == 0) {
                    entry(
                        status = ProviderHealthStatus.YELLOW,
                        message = "EPG loaded but no channels matched.",
                        nextAction = "Check tvg-id mapping",
                    )
                } else if (matched == 0) {
                    entry(
                        status = ProviderHealthStatus.RED,
                        message = "0 of $total channels matched the EPG.",
                        nextAction = "Check tvg-id mapping",
                    )
                } else {
                    val pct = (matched * 100.0 / total).toInt()
                    val status = if (pct < 50) ProviderHealthStatus.YELLOW else ProviderHealthStatus.GREEN
                    entry(
                        status = status,
                        message = "$matched of $total channels matched ($pct%).",
                        nextAction = if (status == ProviderHealthStatus.YELLOW) "Improve tvg-id mapping" else null,
                    )
                }
            }
        }
    }

    private fun entry(
        status: ProviderHealthStatus,
        message: String,
        nextAction: String? = null,
    ): ProviderHealthEntry = ProviderHealthEntry(
        category = ProviderHealthCategory.EPG,
        providerKey = providerKey,
        label = "IPTV EPG",
        status = status,
        message = message,
        nextAction = nextAction,
    )
}
