package com.torve.presentation.download

import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
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

class DownloadViewModel(
    private val downloadRepo: DownloadRepository,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    // Platform callbacks — set from Android composable code to trigger WorkManager
    var onDownloadEnqueued: ((downloadId: String) -> Unit)? = null
    var onDownloadCancelled: ((downloadId: String) -> Unit)? = null
    var onFileDelete: ((filePath: String) -> Unit)? = null

    init {
        loadDownloads()
        // Relock hardening: observe content policy state directly.
        if (contentPolicyRepository != null) {
            scope.launch {
                var wasLocked = contentPolicyRepository.state.value.isLocked
                contentPolicyRepository.state.collectLatest { policy ->
                    val nowLocked = policy.isLocked
                    if (nowLocked && !wasLocked) {
                        loadDownloads()
                    }
                    wasLocked = nowLocked
                }
            }
        }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    fun loadDownloads() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val all = downloadRepo.getAllDownloads()
                val policy = currentPolicy()

                // Apply content policy to display metadata
                val filteredAll = contentPolicyFilter.filterDownloads(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST, all)
                val active = filteredAll.filter { it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED) }
                val completed = filteredAll.filter { it.status == DownloadStatus.COMPLETED }

                _state.update {
                    it.copy(
                        downloads = filteredAll,
                        activeDownloads = active,
                        completedDownloads = completed,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.DOWNLOAD_FAILED.messageKey) }
            }
        }
    }

    fun enqueueDownload(download: Download) {
        scope.launch {
            try {
                val saved = downloadRepo.enqueueDownload(download)
                onDownloadEnqueued?.invoke(saved.id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.DOWNLOAD_FAILED.messageKey) }
            }
        }
    }

    fun pauseDownload(id: String) {
        scope.launch {
            try {
                downloadRepo.pauseDownload(id)
                onDownloadCancelled?.invoke(id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.DOWNLOAD_FAILED.messageKey) }
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            try {
                downloadRepo.resumeDownload(id)
                onDownloadEnqueued?.invoke(id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.DOWNLOAD_FAILED.messageKey) }
            }
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            try {
                val download = downloadRepo.getDownload(id)
                onDownloadCancelled?.invoke(id)
                downloadRepo.deleteDownload(id)
                download?.filePath?.let { onFileDelete?.invoke(it) }
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.DOWNLOAD_FAILED.messageKey) }
            }
        }
    }

    fun selectTab(tab: DownloadTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun getDisplayDownloads(): List<Download> {
        val st = _state.value
        return when (st.selectedTab) {
            DownloadTab.ALL -> st.downloads
            DownloadTab.ACTIVE -> st.activeDownloads
            DownloadTab.COMPLETED -> st.completedDownloads
        }
    }
}
