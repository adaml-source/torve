package com.streamvault.presentation.detail

import com.streamvault.data.addon.ParsedStream
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val metadataRepo: MetadataRepository,
    private val streamRepo: StreamRepository,
    private val watchProgressRepo: WatchProgressRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    fun loadDetail(type: String, id: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val item = metadataRepo.getDetail(type, id)
                _state.update { it.copy(mediaItem = item, isLoading = false) }

                // Load similar items
                val similar = metadataRepo.getSimilar(type, id)
                _state.update { it.copy(similar = similar) }

                // Load watch progress
                if (item != null) {
                    val progress = watchProgressRepo.getProgress(item.id)
                    _state.update { it.copy(watchProgress = progress) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun fetchStreams(season: Int? = null, episode: Int? = null) {
        val item = _state.value.mediaItem ?: return
        val imdbId = item.imdbId ?: return

        scope.launch {
            _state.update { it.copy(isLoadingStreams = true, streamsError = null, streams = emptyList()) }
            try {
                val streams = streamRepo.fetchStreams(
                    type = item.type,
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                )
                _state.update {
                    it.copy(
                        streams = streams,
                        isLoadingStreams = false,
                        showStreamPicker = streams.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoadingStreams = false, streamsError = e.message ?: "Failed to fetch streams")
                }
            }
        }
    }

    fun resolveStream(stream: ParsedStream, provider: DebridServiceType, apiKey: String) {
        scope.launch {
            _state.update { it.copy(isResolving = true, resolveError = null) }
            try {
                val resolved = streamRepo.resolveStream(stream, provider, apiKey)
                _state.update {
                    it.copy(
                        resolvedStream = resolved,
                        isResolving = false,
                        showStreamPicker = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isResolving = false, resolveError = e.message ?: "Failed to resolve stream")
                }
            }
        }
    }

    fun toggleStreamPicker() {
        _state.update { it.copy(showStreamPicker = !it.showStreamPicker) }
    }

    fun dismissStreamPicker() {
        _state.update { it.copy(showStreamPicker = false) }
    }

    fun clearResolvedStream() {
        _state.update { it.copy(resolvedStream = null) }
    }
}
