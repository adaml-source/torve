package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StreamPreferences
import kotlinx.datetime.Clock

/** Destination-owned source sessions. Playback URLs are resolved again when another row is selected. */
internal class DetailSourceResultsCache(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    data class Key(
        val type: MediaType,
        val imdbId: String,
        val season: Int?,
        val episode: Int?,
        val preferences: StreamPreferences,
        val addons: List<InstalledAddon>,
        val accounts: Map<DebridServiceType, String>,
    )

    private data class Results(
        val streams: List<ParsedStream>,
        val startupCandidates: List<StartupCandidate>,
        val hiddenCount: Int,
        val startupStatus: PlaybackStartupStatus,
        val providerStats: List<com.torve.domain.model.ProviderSourceStat>,
        val fetchedAt: Long,
    )

    private data class PlaybackSession(val key: Key, val results: Results)

    private val entries = linkedMapOf<Key, Results>()
    // A single playback session survives ordinary cache expiry/eviction. Keep it
    // after returning too, so reopening the picker does not start another search.
    private var playbackSession: PlaybackSession? = null
    private var playbackReturnPending = false

    fun retain(key: Key, state: DetailUiState, fresh: Boolean = false) {
        if (state.streams.isEmpty()) return
        val fetchedAt = if (fresh) now() else entries[key]?.fetchedAt ?: now()
        entries.remove(key)
        val results = Results(state.streams, state.startupCandidates, state.streamFilterHiddenCount,
            state.playbackStartupStatus, state.sourceStatistics, fetchedAt)
        entries[key] = results
        if (playbackSession?.key == key) playbackSession = PlaybackSession(key, results)
        while (entries.size > 8) entries.remove(entries.keys.first())
    }

    fun restore(key: Key, state: DetailUiState, enrich: (ParsedStream) -> ParsedStream): DetailUiState? {
        val playbackResults = playbackSession?.takeIf { it.key == key }?.results
        val results = playbackResults ?: entries[key] ?: return null
        if (playbackResults == null && now() - results.fetchedAt !in 0 until LIFETIME_MS) {
            entries.remove(key)
            return null
        }
        return state.copy(
            streams = results.streams.map(enrich),
            sourceStatistics = results.providerStats.map { it.copy(fromCache = true) },
            startupCandidates = results.startupCandidates,
            streamFilterHiddenCount = results.hiddenCount,
            playbackStartupStatus = results.startupStatus,
            streamContextSeason = key.season,
            streamContextEpisode = key.episode,
            showStreamPicker = true,
            isLoadingStreams = false,
            isLoadingMoreSources = false,
            isInspectingSourceLanguages = false,
            isResolving = false,
            resolvedStream = null,
            usenetPlaybackIntent = null,
            preparing = null,
            streamsError = null,
            streamsErrorHint = null,
            resolveError = null,
            autoPlayStream = null,
            autoPlayMessage = null,
            autoPlayFailed = false,
            fallbackAttempt = 0,
        )
    }

    fun playbackLaunched(key: Key, state: DetailUiState) {
        if (state.streams.isEmpty()) return
        retain(key, state)
        playbackSession = PlaybackSession(key, entries.getValue(key))
        playbackReturnPending = true
    }

    fun restoreAfterPlayback(state: DetailUiState, enrich: (ParsedStream) -> ParsedStream): DetailUiState? {
        if (!playbackReturnPending) return null
        val session = playbackSession ?: return null
        playbackReturnPending = false
        return restore(session.key, state, enrich)?.copy(showStreamPicker = false)
    }

    fun clear() {
        entries.clear()
        playbackSession = null
        playbackReturnPending = false
    }

    fun invalidate(key: Key) {
        entries.remove(key)
        if (playbackSession?.key == key) {
            playbackSession = null
            playbackReturnPending = false
        }
    }

    companion object {
        const val LIFETIME_MS = 30 * 60 * 1000L
    }
}
