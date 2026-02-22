package com.streamvault.presentation.download

import com.streamvault.domain.model.Download
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadViewModel(
    private val downloadRepo: DownloadRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    init {
        loadDownloads()
    }

    fun loadDownloads() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val all = downloadRepo.getAllDownloads()
                val active = all.filter { it.status in listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED) }
                val completed = all.filter { it.status == DownloadStatus.COMPLETED }
                _state.update {
                    it.copy(
                        downloads = all,
                        activeDownloads = active,
                        completedDownloads = completed,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun enqueueDownload(download: Download) {
        scope.launch {
            try {
                downloadRepo.enqueueDownload(download)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun pauseDownload(id: String) {
        scope.launch {
            try {
                downloadRepo.pauseDownload(id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            try {
                downloadRepo.resumeDownload(id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            try {
                downloadRepo.deleteDownload(id)
                loadDownloads()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
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
