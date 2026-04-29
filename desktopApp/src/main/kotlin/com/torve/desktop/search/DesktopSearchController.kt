package com.torve.desktop.search

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
            val detail = runCatching { metadataRepository.getDetail(type, tmdbId) }.getOrElse { error ->
                _state.update {
                    it.copy(
                        detailItem = item,
                        isLoadingDetail = false,
                        detailError = error.message ?: "Failed to load detail metadata.",
                    )
                }
                return@launch
            }

            val similar = runCatching { metadataRepository.getSimilar(type, tmdbId) }
                .getOrDefault(emptyList())

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
            (seasonNumber ?: detail.seasons.firstOrNull()?.seasonNumber)?.let { resolvedSeason ->
                selectSeason(
                    seasonNumber = resolvedSeason,
                    preferredEpisodeNumber = episodeNumber,
                )
            }
        }
    }

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
}

private fun MediaType.toTmdbType(): String = when (this) {
    MediaType.MOVIE -> "movie"
    MediaType.SERIES -> "tv"
}
