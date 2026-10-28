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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    val error: String? = null,
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
    private val workDispatcher: CoroutineDispatcher = com.torve.util.ioDispatcher,
    private val loadTimeoutMs: Long = 10_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = contentPolicyFilter.profileState(DownloadCatalogueUiState())
    val state: StateFlow<DownloadCatalogueUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    fun clear() { scope.cancel() }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    // Platform callbacks
    var onFileDelete: ((filePath: String) -> Unit)? = null
    var onDownloadCancelled: ((downloadId: String) -> Unit)? = null

    init {
        loadCatalogue()
        scope.launch {
            contentPolicyFilter.profileOwners().distinctUntilChanged().drop(1).collectLatest {
                _state.value = DownloadCatalogueUiState()
                loadCatalogue()
            }
        }
        contentPolicyRepository?.let { repository ->
            scope.launch {
                repository.state.drop(1).collectLatest {
                    // Discard retained metadata immediately when access rules change.
                    _state.value = DownloadCatalogueUiState(prefs = _state.value.prefs)
                    loadCatalogue()
                }
            }
        }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    fun loadCatalogue() {
        loadJob?.cancel()
        loadJob = scope.launch {
            var owner = contentPolicyFilter.profileOwnerKey()
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                withTimeout(loadTimeoutMs) {
                    contentPolicyFilter.awaitProfileBoundary()
                    owner = contentPolicyFilter.profileOwnerKey()
                    val policy = currentPolicy()
                    val fallbackPrefs = _state.value.prefs
                    val loaded = withContext(workDispatcher) {
                        val allDownloads = downloadRepo.getAllDownloads()
                        val active = contentPolicyFilter.filterDownloads(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST,
                            allDownloads.filter { it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED) })
                        val allProgress = watchProgressRepo.getAllProgress()
                        val progressMap = allProgress.associateBy { Triple(it.mediaId, it.seasonNumber, it.episodeNumber) }
                        val items = allDownloads.filter { it.status == DownloadStatus.COMPLETED }.map { download ->
                            download.toDownloadedItem(progressMap[Triple(download.mediaId, download.seasonNumber, download.episodeNumber)])
                        }.let { applyPolicyToDownloadedItems(it, policy) }
                        val prefs = prefsRepo.getString(KEY_CATALOGUE_PREFS)?.let {
                            runCatching { jsonParser.decodeFromString<DownloadCataloguePrefs>(it) }.getOrNull()
                        } ?: fallbackPrefs
                        DownloadCatalogueUiState(isLoading = false, catalogue = catalogueBuilder.buildCatalogue(items, prefs),
                            prefs = prefs, activeDownloads = active, allDownloadedItems = items)
                    }
                    ensureActive()
                    if (owner == contentPolicyFilter.profileOwnerKey()) _state.value = loaded
                }
            } catch (timeout: TimeoutCancellationException) {
                if (owner == contentPolicyFilter.profileOwnerKey()) _state.update {
                    it.copy(isLoading = false, error = "Loading your downloaded library took too long. Try again.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner == contentPolicyFilter.profileOwnerKey()) _state.update {
                    it.copy(isLoading = false, error = "Could not load your downloaded library. Try again.")
                }
            }
        }
    }

    /** Re-read the current profile's entry instead of trusting a retained card or file path. */
    @Throws(Exception::class)
    suspend fun downloadForPlayback(id: String): Download = withTimeout(loadTimeoutMs) {
        contentPolicyFilter.awaitProfileBoundary()
        val owner = contentPolicyFilter.profileOwnerKey()
        val download = withContext(workDispatcher) { downloadRepo.getDownload(id) }
            ?: error("This download is no longer available.")
        check(owner == contentPolicyFilter.profileOwnerKey()) { "The active profile changed." }
        check(download.status == DownloadStatus.COMPLETED && !download.filePath.isNullOrBlank()) { "This download is not ready." }
        val visible = contentPolicyFilter.filterDownloads(currentPolicy(), ContentAccessContext.LIBRARY_OR_WATCHLIST, listOf(download))
        check(visible.singleOrNull()?.title == download.title && download.title != LOCKED_CONTENT_TITLE) { "This download is not available in this profile." }
        download
    }

    private fun applyPolicyToDownloadedItems(
        items: List<DownloadedItem>,
        policy: ContentPolicyState,
    ): List<DownloadedItem> {
        if (!policy.enforcementEnabled && !contentPolicyFilter.hasFamilyBoundary) return items
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
