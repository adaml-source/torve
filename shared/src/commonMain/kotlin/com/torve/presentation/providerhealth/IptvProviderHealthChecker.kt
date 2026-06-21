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
        val categoriesPresent = state.categories.isNotEmpty() ||
            state.groupedChannels.isNotEmpty() ||
            state.categoryChannels.isNotEmpty()
        val hasCachedCatalog = state.selectedPlaylistId != null &&
            (state.channels.isNotEmpty() || categoriesPresent)
        if (state.playlists.isEmpty() && !hasCachedCatalog) {
            return playlistEntry(
                status = ProviderHealthStatus.UNCONFIGURED,
                message = "No IPTV playlist added.",
                nextAction = "Add a playlist",
            )
        }
        if (state.playlists.isEmpty() && hasCachedCatalog) {
            return playlistEntry(
                status = ProviderHealthStatus.GREEN,
                message = "IPTV channel catalog is loaded.",
                nextAction = null,
            )
        }
        val activeId = state.selectedPlaylistId
        val activeName = activeId?.let { id -> state.playlists.firstOrNull { it.id == id }?.name }
            ?: state.playlists.first().name
        val storedChannelCount = activeId
            ?.let { id -> state.playlists.firstOrNull { it.id == id }?.channelCount }
            ?: state.playlists.firstOrNull()?.channelCount
            ?: 0
        val channelCount = maxOf(state.channels.size, storedChannelCount)
        // Either the channels list itself OR the category counts being
        // populated proves the playlist parsed fine. The Live TV grid
        // renders from `groupedChannels` / `categories`, so a row with
        // many categories but a yet-unrendered `channels` list is also
        // a working state. Mobile settings can also have only the
        // persisted playlist row hydrated; its channelCount is still
        // authoritative proof that the playlist parsed previously.
        val hasLoadedContent = channelCount > 0 || categoriesPresent
        val parseError = state.error
        return when {
            // Authoritative: if we have loaded content right now, the
            // playlist is working regardless of any leftover `error`
            // flag from a prior failed attempt. The error field is set
            // by ChannelsViewModel on transient failures and (per the
            // current call-graph) is not always cleared on subsequent
            // success — so trusting it would surface stale RED rows
            // even when the user is actively browsing channels.
            hasLoadedContent -> playlistEntry(
                status = ProviderHealthStatus.GREEN,
                message = "\"$activeName\": $channelCount channels loaded.",
                nextAction = null,
            )
            parseError != null -> playlistEntry(
                status = ProviderHealthStatus.RED,
                message = "Couldn't load \"$activeName\": $parseError",
                nextAction = "Re-add or refresh playlist",
            )
            state.isLoadingChannels -> playlistEntry(
                status = ProviderHealthStatus.UNKNOWN,
                message = "Loading \"$activeName\"…",
                nextAction = null,
            )
            else -> playlistEntry(
                status = ProviderHealthStatus.YELLOW,
                message = "\"$activeName\" loaded with 0 channels.",
                nextAction = "Refresh playlist",
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
                val pct = if (total > 0) (matched * 100.0 / total).toInt() else null
                entry(
                    status = ProviderHealthStatus.GREEN,
                    message = when {
                        total == 0 -> "EPG data is loaded."
                        matched == 0 -> "EPG data is loaded. Channel mapping can improve programme coverage."
                        pct != null && pct < 50 ->
                            "EPG works for $matched of $total channels ($pct%). Optional mapping can improve coverage."
                        else -> "$matched of $total channels matched ($pct%)."
                    },
                    nextAction = null,
                )
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
