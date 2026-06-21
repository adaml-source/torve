package com.torve.presentation.tvhome

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.sourceavailability.SourceAvailabilityAggregator
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.lanlibrary.LanLibraryConsumer
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * TV-Home outcome aggregator (Prompt 11B).
 *
 * Subscribes to the existing services and produces a focused
 * [TvHomeOutcomeUiState] for the TV layer to render the four outcome
 * rails + the provider banner. Independent of `HomeViewModel`'s
 * monolithic state so we can ship without touching that 1700-line
 * surface.
 *
 * **What it does NOT do**:
 *   - Resolve provider stream URLs (expensive; left to detail screen).
 *   - Mint LAN tokens (the picker / playback router does that on tap).
 *   - Sort / dedupe rails — those live in [TvHomeRailsBuilder].
 *
 * Buckets sourced:
 *   - `availableNow`  : items with at least one playable signal.
 *     Candidate set = `continueWatching ∪ watchlist ∪ recommended ∪
 *                     watch-history`. The aggregator probes those via
 *     `SourceAvailabilityAggregator.lookupBatch`.
 *   - `downloadsOnDesktop` : items whose title matches the LAN
 *     library consumer's manifest cache.
 *   - `onNow` : currently-airing IPTV channels from
 *     `ChannelsViewModel.state.channels`.
 *   - `recentlyAdded` : addon-shelf items already in the availability
 *     map (so we surface "new on my sources" instead of generic
 *     catalog).
 *   - `providerBanner` : worst-status non-green provider-health row.
 */
class TvHomeOutcomeViewModel(
    private val homeViewModel: HomeViewModel,
    private val availabilityAggregator: SourceAvailabilityAggregator,
    private val lanLibraryConsumer: LanLibraryConsumer,
    private val providerHealthCoordinator: ProviderHealthCoordinator,
    private val channelsViewModel: ChannelsViewModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxAvailable: Int = 24,
    private val maxLan: Int = 24,
    private val maxOnNow: Int = 24,
) {

    private val _state = MutableStateFlow(TvHomeOutcomeUiState())
    val state: StateFlow<TvHomeOutcomeUiState> = _state.asStateFlow()

    init {
        // Single combine over every upstream so a tick in any one
        // re-derives the outcome state. Coroutine scope lives for the
        // VM's lifetime; cancel via [release].
        scope.launch {
            combine(
                homeViewModel.state,
                lanLibraryConsumer.entries,
                providerHealthCoordinator.entries,
                channelsViewModel.state,
            ) { home, lanEntries, healthRows, channelsState ->
                ComposedInputs(home, lanEntries, healthRows, channelsState)
            }.collect { inputs -> recompute(inputs) }
        }
    }

    fun refresh() {
        scope.launch { recompute(snapshot()) }
    }

    private fun snapshot(): ComposedInputs = ComposedInputs(
        home = homeViewModel.state.value,
        lanEntries = lanLibraryConsumer.entries.value,
        healthRows = providerHealthCoordinator.entries.value,
        channelsState = channelsViewModel.state.value,
    )

    private suspend fun recompute(inputs: ComposedInputs) {
        val candidates = collectCandidates(inputs.home)
        // Probe availability only for candidates that have a tmdbId.
        val keys = candidates.mapNotNull { item ->
            val tmdbId = item.tmdbId ?: return@mapNotNull null
            tmdbId to item.type
        }
        val availability: Map<Int, SourceAvailabilityRecord> = if (keys.isEmpty()) {
            emptyMap()
        } else {
            runCatching { availabilityAggregator.lookupBatch(keys) }.getOrDefault(emptyMap())
        }
        val lanTitles = inputs.lanEntries.values
            .mapNotNull { it.entry.title.trim().lowercase().takeIf { t -> t.isNotEmpty() } }
            .toSet()
        val availableNow = TvHomeRailsBuilder
            .availableNow(candidates, availability)
            .take(maxAvailable)
        val downloadsOnDesktop = TvHomeRailsBuilder
            .downloadsOnDesktop(candidates, lanTitles)
            .take(maxLan)
        // Addon-shelf intersection — "Recently Added From My Sources".
        val recentlyAdded = inputs.home.addonShelves
            .flatMap { it.items }
            .let { TvHomeRailsBuilder.availableNow(it, availability) }
            .take(maxAvailable)
        // On-now channels — at least one current programme.
        val onNow = TvHomeRailsBuilder
            .onNow(inputs.channelsState.channels)
            .take(maxOnNow)
        val banner = TvHomeRailsBuilder.providerBanner(inputs.healthRows)
        _state.value = TvHomeOutcomeUiState(
            availableNow = availableNow,
            downloadsOnDesktop = downloadsOnDesktop,
            onNow = onNow,
            recentlyAdded = recentlyAdded,
            providerBanner = banner,
            availabilityByTmdbId = availability,
            lanTitlesLowercase = lanTitles,
        )
    }

    /**
     * Candidate set for the availability probe: the union of items
     * the user has *signal* about (continue watching, watchlist,
     * recommended, recently watched). Generic catalog rails are
     * skipped — surfacing every TMDB row would make the probe expensive
     * and the rail would just mirror the catalog.
     */
    private fun collectCandidates(home: HomeUiState): List<MediaItem> {
        val out = LinkedHashMap<String, MediaItem>(64)
        // continueWatching is List<WatchProgress>; convert to MediaItem.
        for (cw in home.continueWatching) {
            val item = cw.toMediaItemOrNullForOutcome() ?: continue
            if (item.id !in out) out[item.id] = item
        }
        for (item in home.watchlistItems) {
            if (item.id !in out) out[item.id] = item
        }
        for (scored in home.recommendedItems) {
            if (scored.item.id !in out) out[scored.item.id] = scored.item
        }
        for (item in home.recentlyWatched) {
            if (item.id !in out) out[item.id] = item
        }
        return out.values.toList()
    }

    private fun com.torve.domain.model.WatchProgress.toMediaItemOrNullForOutcome(): MediaItem? {
        // Minimal conversion — only the fields downstream consumes.
        if (mediaId.isBlank()) return null
        val tmdb = mediaId.toIntOrNull() ?: return null
        val prefix = if (mediaType == MediaType.SERIES) "series" else "movie"
        return MediaItem(
            id = "$prefix:$tmdb",
            tmdbId = tmdb,
            type = mediaType,
            title = title,
            posterUrl = posterUrl,
        )
    }

    private data class ComposedInputs(
        val home: HomeUiState,
        val lanEntries: Map<String, LanLibraryConsumer.LanMatch>,
        val healthRows: List<ProviderHealthEntry>,
        val channelsState: com.torve.presentation.channels.ChannelsUiState,
    )
}

/**
 * Render-ready state for the TV-Home outcome surfaces. The TV layer
 * checks emptiness per-bucket and hides empty rails.
 */
data class TvHomeOutcomeUiState(
    val availableNow: List<MediaItem> = emptyList(),
    val downloadsOnDesktop: List<MediaItem> = emptyList(),
    val onNow: List<EnrichedChannel> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
    val providerBanner: TvProviderBanner? = null,
    /** Forwarded so the playback router doesn't re-probe on tap. */
    val availabilityByTmdbId: Map<Int, SourceAvailabilityRecord> = emptyMap(),
    val lanTitlesLowercase: Set<String> = emptySet(),
)
