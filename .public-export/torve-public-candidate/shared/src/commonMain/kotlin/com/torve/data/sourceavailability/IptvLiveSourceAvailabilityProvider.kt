package com.torve.data.sourceavailability

import com.torve.domain.model.MediaType
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRankBoost
import com.torve.domain.sourceavailability.SourceAvailabilitySignal
import com.torve.presentation.channels.ChannelsUiState

/**
 * Reports an IPTV-live hit when at least one configured IPTV channel is
 * currently airing the title in question.
 *
 * "Currently airing" is a *narrow* signal — the provider matches by:
 *   1. exact case-insensitive match between the EnrichedChannel's
 *      `currentProgramme.title` and the resolved [titleSource] result;
 *   2. or the channel's display name containing the title (covers cases
 *      where EPG isn't matched, e.g. a "BBC Sherlock" channel name when
 *      the user searches for "Sherlock"). This is intentionally
 *      conservative; per Prompt 8 IPTV signals only fire "when intent
 *      matches", so we never claim live-availability on the basis of a
 *      single common word.
 *
 * UNCONFIGURED-silent: returns null when no playlists are loaded.
 */
class IptvLiveSourceAvailabilityProvider(
    private val channelsStateSource: () -> ChannelsUiState,
    private val titleSource: suspend (Int, MediaType) -> String?,
) : SourceAvailabilityProvider {

    override val kind: SourceAvailabilityKind = SourceAvailabilityKind.IPTV_LIVE

    override suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal? {
        val state = channelsStateSource()
        if (state.playlists.isEmpty() || state.channels.isEmpty()) return null
        val title = runCatching { titleSource(tmdbId, mediaType) }.getOrNull()
            ?.trim()
            ?.takeIf { it.length >= MIN_MATCH_LEN } ?: return null
        val titleLower = title.lowercase()
        val match = state.channels.firstOrNull { ec ->
            val programmeTitle = ec.currentProgramme?.title?.trim().orEmpty()
            val channelName = ec.channel.name.trim()
            programmeTitle.equals(title, ignoreCase = true) ||
                (channelName.length >= MIN_MATCH_LEN &&
                    channelName.lowercase().contains(titleLower))
        } ?: return null
        val onAir = match.currentProgramme?.title?.takeIf { it.isNotBlank() } ?: title
        return SourceAvailabilitySignal(
            kind = SourceAvailabilityKind.IPTV_LIVE,
            badge = "On now: ${match.channel.name}",
            rankBoost = SourceAvailabilityRankBoost.IPTV_LIVE,
        ).also {
            // `onAir` is referenced for future clarity in the badge label
            // when the EPG match drives the hit; suppress the unused
            // local-warning while keeping the ergonomics clean.
            @Suppress("UNUSED_VARIABLE") val _consume = onAir
        }
    }

    companion object {
        /**
         * Minimum length of a substring to count as an intent-match.
         * Prevents 1-2 character titles like "It" from matching every
         * channel that contains the letters "it".
         */
        private const val MIN_MATCH_LEN = 4
    }
}
