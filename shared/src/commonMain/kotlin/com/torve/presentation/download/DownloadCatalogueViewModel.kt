package com.torve.presentation.download

import com.torve.data.download.DownloadCatalogueBuilder
import com.torve.domain.model.CatalogueState
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadCataloguePrefs
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.DownloadedItem
import com.torve.domain.model.toDownloadedItem
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    fun loadCatalogue() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val allDownloads = downloadRepo.getAllDownloads()
                val active = allDownloads.filter {
                    it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
                }
                val completed = allDownloads.filter { it.status == DownloadStatus.COMPLETED }

                // Convert completed downloads to DownloadedItems with watch progress
                val allProgress = try { watchProgressRepo.getAllProgress() } catch (_: Exception) { emptyList() }
                val progressMap = allProgress.associateBy { it.mediaId }

                val downloadedItems = completed.map { download ->
                    download.toDownloadedItem(watchProgress = progressMap[download.mediaId])
                }

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
