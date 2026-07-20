package com.torve.presentation.download

import com.torve.data.download.DownloadCatalogueBuilder
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.CatalogueState
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadCataloguePrefs
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.DownloadedItem
import com.torve.domain.model.LOCKED_CONTENT_TITLE
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.toDownloadedItem
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DownloadCatalogueUiState(
    val isLoading: Boolean = true,
    val catalogue: CatalogueState = CatalogueState(),
    val prefs: DownloadCataloguePrefs = DownloadCataloguePrefs(),
    val activeDownloads: List<Download> = emptyList(),
    val allDownloadedItems: List<DownloadedItem> = emptyList(),
)

class DownloadCatalogueViewModel(
    private val downloadRepo: DownloadRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val prefsRepo: PreferencesRepository,
    private val catalogueBuilder: DownloadCatalogueBuilder,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DownloadCatalogueUiState())
    val state: StateFlow<DownloadCatalogueUiState> = _state.asStateFlow()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    // Platform callbacks
    var onFileDelete: ((filePath: String) -> Unit)? = null
    var onDownloadCancelled: ((downloadId: String) -> Unit)? = null

    init {
        loadPrefs()
        loadCatalogue()
        // Relock hardening: observe content policy state directly.
        // When policy transitions to locked, immediately clear sensitive metadata
        // from the visible catalogue without waiting for a full async reload.
        if (contentPolicyRepository != null) {
            scope.launch {
                var wasLocked = contentPolicyRepository.state.value.isLocked
                contentPolicyRepository.state.collectLatest { policy ->
                    val nowLocked = policy.isLocked
                    if (nowLocked && !wasLocked) {
                        // Transition to locked — rebuild catalogue with policy applied
                        _state.update { it.copy(isLoading = true) }
                        loadCatalogue()
                    }
                    wasLocked = nowLocked
                }
            }
        }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    fun loadCatalogue() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val allDownloads = downloadRepo.getAllDownloads()
                val policy = currentPolicy()

                // Apply content policy to active downloads (display metadata only)
                val rawActive = allDownloads.filter {
                    it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
                }
                val active = contentPolicyFilter.filterDownloads(
                    policy = policy,
                    context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
                    items = rawActive,
                )

                val completed = allDownloads.filter { it.status == DownloadStatus.COMPLETED }

                // Convert completed downloads to DownloadedItems with watch progress
                val allProgress = try { watchProgressRepo.getAllProgress() } catch (_: Exception) { emptyList() }
                val progressMap = allProgress.associateBy { it.mediaId }

                val rawDownloadedItems = completed.map { download ->
                    download.toDownloadedItem(watchProgress = progressMap[download.mediaId])
                }

                // Apply content policy to catalogue items
                val downloadedItems = applyPolicyToDownloadedItems(rawDownloadedItems, policy)

                val prefs = _state.value.prefs
                val catalogue = catalogueBuilder.buildCatalogue(downloadedItems, prefs)

                _state.update {
                    it.copy(
                        isLoading = false,
                        catalogue = catalogue,
                        activeDownloads = active,
                        allDownloadedItems = downloadedItems,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun applyPolicyToDownloadedItems(
        items: List<DownloadedItem>,
        policy: ContentPolicyState,
    ): List<DownloadedItem> {
        if (!policy.enforcementEnabled) return items
        return items.map { item ->
            val synthetic = MediaItem(
                id = item.mediaId,
                title = item.title,
                type = if (item.type == com.torve.domain.model.DownloadMediaType.MOVIE) MediaType.MOVIE else MediaType.SERIES,
            )
            val decision = contentPolicyFilter.decide(
                policy = policy,
                context = ContentAccessContext.LIBRARY_OR_WATCHLIST,
                item = synthetic,
                sourceType = ContentSourceType.LOCAL_LIBRARY,
                addonPolicyFlags = null,
                allowSensitiveBecauseUserReachedSensitiveParent = false,
            )
            when (decision.action) {
                com.torve.domain.model.ContentFilterAction.ALLOW_FULL -> item
                else -> item.copy(
                    title = LOCKED_CONTENT_TITLE,
                    posterUrl = null,
                    backdropUrl = null,
                    episodeTitle = null,
                    genres = null,
                    contentRating = null,
                )
            }
        }
    }

    fun updatePrefs(update: (DownloadCataloguePrefs) -> DownloadCataloguePrefs) {
        val newPrefs = update(_state.value.prefs)
        _state.update { it.copy(prefs = newPrefs) }
        savePrefs(newPrefs)
        rebuildCatalogue()
    }

    fun deleteGroup(group: DownloadGroup) {
        scope.launch {
            try {
                when (group.type) {
                    DownloadGroupType.MOVIE -> group.movie?.let { deleteItem(it) }
                    DownloadGroupType.SHOW -> group.seasons?.flatMap { it.episodes }?.forEach { deleteItem(it) }
                }
                loadCatalogue()
            } catch (_: Exception) {}
        }
    }

    fun deleteSeason(mediaId: String, seasonNumber: Int) {
        scope.launch {
            try {
                val items = _state.value.allDownloadedItems.filter {
                    it.mediaId == mediaId && it.seasonNumber == seasonNumber
                }
                items.forEach { deleteItem(it) }
                loadCatalogue()
            } catch (_: Exception) {}
        }
    }

    fun deleteEpisode(downloadId: String) {
        scope.launch {
            try {
                val download = downloadRepo.getDownload(downloadId)
                onDownloadCancelled?.invoke(downloadId)
                downloadRepo.deleteDownload(downloadId)
                download?.filePath?.let { onFileDelete?.invoke(it) }
                loadCatalogue()
            } catch (_: Exception) {}
        }
    }

    fun deleteWatched() {
        scope.launch {
            try {
                val watchedItems = _state.value.allDownloadedItems.filter { it.isWatched }
                watchedItems.forEach { deleteItem(it) }
                loadCatalogue()
            } catch (_: Exception) {}
        }
    }

    private suspend fun deleteItem(item: DownloadedItem) {
        onDownloadCancelled?.invoke(item.id)
        downloadRepo.deleteDownload(item.id)
        item.filePath?.let { onFileDelete?.invoke(it) }
    }

    private fun rebuildCatalogue() {
        val items = _state.value.allDownloadedItems
        val prefs = _state.value.prefs
        if (items.isNotEmpty()) {
            val catalogue = catalogueBuilder.buildCatalogue(items, prefs)
            _state.update { it.copy(catalogue = catalogue) }
        }
    }

    private fun loadPrefs() {
        scope.launch {
            try {
                val json = prefsRepo.getString(KEY_CATALOGUE_PREFS)
                if (json != null) {
                    val prefs = jsonParser.decodeFromString<DownloadCataloguePrefs>(json)
                    _state.update { it.copy(prefs = prefs) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun savePrefs(prefs: DownloadCataloguePrefs) {
        scope.launch {
            try {
                prefsRepo.setString(KEY_CATALOGUE_PREFS, jsonParser.encodeToString(prefs))
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val KEY_CATALOGUE_PREFS = "download_catalogue_prefs"
    }
}
