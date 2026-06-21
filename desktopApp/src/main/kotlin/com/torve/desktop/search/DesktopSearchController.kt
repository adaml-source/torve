package com.torve.desktop.search

import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.Episode
import com.torve.domain.repository.MetadataRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DesktopSearchDetailUiState(
    val selectedResult: MediaItem? = null,
    val detailItem: MediaItem? = null,
    val similarItems: List<MediaItem> = emptyList(),
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,
    val actionMessage: String? = null,
    val selectedSeasonNumber: Int? = null,
    val selectedSeason: Season? = null,
    val selectedEpisodeNumber: Int? = null,
    val isLoadingSeason: Boolean = false,
)

class DesktopSearchController(
    private val metadataRepository: MetadataRepository,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(DesktopSearchDetailUiState())
    val state: StateFlow<DesktopSearchDetailUiState> = _state.asStateFlow()

    fun start() {
        started.compareAndSet(false, true)
    }

    fun dispose() {
        scope.cancel()
    }

    fun onResultsChanged(results: List<MediaItem>) {
        val selectedId = _state.value.selectedResult?.id
        when {
            results.isEmpty() -> {
                _state.value = DesktopSearchDetailUiState()
            }

            selectedId == null -> {
                selectResult(results.first())
            }

            results.none { it.id == selectedId } -> {
                selectResult(results.first())
            }
        }
    }

    fun selectResult(
        item: MediaItem,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        _state.update {
            it.copy(
                selectedResult = item,
                detailItem = item,
                isLoadingDetail = true,
                detailError = null,
                actionMessage = null,
                selectedSeasonNumber = null,
                selectedSeason = null,
                selectedEpisodeNumber = null,
                isLoadingSeason = false,
                similarItems = emptyList(),
            )
        }

        scope.launch {
            val tmdbId = item.tmdbId ?: item.id.toIntOrNull()
            if (tmdbId == null) {
                if (!isCurrentSelection(item)) return@launch
                _state.update {
                    it.copy(
                        detailItem = item,
                        isLoadingDetail = false,
                        detailError = "This result has no TMDB identifier yet, so richer detail could not be loaded.",
                    )
                }
                return@launch
            }

            val type = item.type.toTmdbType()
            val rawDetail = runCatching { metadataRepository.getDetail(type, tmdbId) }.getOrElse { error ->
                if (!isCurrentSelection(item)) return@launch
                _state.update {
                    it.copy(
                        detailItem = item,
                        isLoadingDetail = false,
                        detailError = error.message ?: "Failed to load detail metadata.",
                    )
                }
                return@launch
            }

            val rawSimilar = runCatching { metadataRepository.getSimilar(type, tmdbId) }
                .getOrDefault(emptyList())

            // Phase 1: hydrate from local ratings cache so the user sees
            // multi-pill ratings on first paint when they're cached.
            val detail = ratingsEnricher?.hydrateFromCache(rawDetail) ?: rawDetail
            val similar = ratingsEnricher?.hydrateListFromCache(rawSimilar) ?: rawSimilar

            if (!isCurrentSelection(item)) return@launch
            _state.update {
                it.copy(
                    detailItem = detail,
                    similarItems = similar,
                    isLoadingDetail = false,
                    detailError = null,
                    selectedSeasonNumber = seasonNumber ?: detail.seasons.firstOrNull()?.seasonNumber,
                    selectedSeason = null,
                    selectedEpisodeNumber = episodeNumber,
                )
            }

            // Phase 2: async enrich via MDBList/OMDB/Trakt. Until this
            // commit the desktop detail page never enriched at all -- it
            // only ever showed TMDB pills because DesktopSearchController
            // was constructed with just the metadata repo, no enricher.
            launchEnrich(detail, similar)
            (seasonNumber ?: detail.seasons.firstOrNull()?.seasonNumber)?.let { resolvedSeason ->
                selectSeason(
                    seasonNumber = resolvedSeason,
                    preferredEpisodeNumber = episodeNumber,
                )
            }
        }
    }

    private fun isCurrentSelection(item: MediaItem): Boolean =
        _state.value.selectedResult?.id == item.id

    fun refreshSelectedDetail() {
        state.value.selectedResult?.let(::selectResult)
    }

    fun selectSeason(
        seasonNumber: Int,
        preferredEpisodeNumber: Int? = null,
    ) {
        val detail = _state.value.detailItem ?: return
        val tmdbId = detail.tmdbId ?: return
        _state.update {
            it.copy(
                selectedSeasonNumber = seasonNumber,
                selectedSeason = null,
                selectedEpisodeNumber = null,
                isLoadingSeason = true,
                detailError = null,
            )
        }
        scope.launch {
            val season = runCatching {
                metadataRepository.getSeasonDetail(tmdbId, seasonNumber)
            }.getOrElse { error ->
                _state.update {
                    it.copy(
                        isLoadingSeason = false,
                        detailError = error.message ?: "Failed to load season metadata.",
                    )
                }
                return@launch
            }
            _state.update {
                if (it.detailItem?.id != detail.id) return@update it
                it.copy(
                    selectedSeasonNumber = seasonNumber,
                    selectedSeason = season,
                    selectedEpisodeNumber = preferredEpisodeNumber ?: season.episodes.firstOrNull()?.episodeNumber,
                    isLoadingSeason = false,
                )
            }
        }
    }

    fun selectEpisode(
        episodeNumber: Int,
    ) {
        _state.update { it.copy(selectedEpisodeNumber = episodeNumber) }
    }

    fun clearActionMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    /**
     * Background MDBList/OMDB/Trakt enrichment for the detail item and
     * its Related rail. The phase-1 cache hydrate already happened in
     * selectResult; this fires a fresh API pass so newly-discovered
     * items get full pill coverage instead of TMDB-only.
     *
     * Best-effort: failures are swallowed, the user just keeps the
     * cache-hydrate state if the API doesn't respond.
     */
    private fun launchEnrich(detail: MediaItem, similar: List<MediaItem>) {
        val enricher = ratingsEnricher ?: return
        scope.launch {
            val apiKey = runCatching {
                integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)

            // Detail item — single enrich. Swap into state when done so
            // the header pills update without flicker.
            launch {
                runCatching { enricher.enrichSingle(detail, apiKey) }
                    .getOrNull()
                    ?.let { enriched ->
                        // Only overwrite if the user is still on the same item.
                        if (_state.value.detailItem?.id == detail.id) {
                            _state.update { it.copy(detailItem = enriched) }
                        }
                    }
            }
            // Similar list — bulk enrich, single state update at the end.
            if (similar.isNotEmpty()) {
                launch {
                    runCatching { enricher.enrichList(similar, apiKey) }
                        .getOrNull()
                        ?.let { enriched ->
                            if (_state.value.detailItem?.id == detail.id) {
                                _state.update { it.copy(similarItems = enriched) }
                            }
                        }
                }
            }
        }
    }
}

private fun MediaType.toTmdbType(): String = when (this) {
    MediaType.MOVIE -> "movie"
    MediaType.SERIES -> "tv"
}
