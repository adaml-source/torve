package com.torve.presentation.download

import com.torve.domain.model.Download

data class DownloadUiState(
    val downloads: List<Download> = emptyList(),
    val activeDownloads: List<Download> = emptyList(),
    val completedDownloads: List<Download> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTab: DownloadTab = DownloadTab.ALL,
)

enum class DownloadTab {
    ALL,
    ACTIVE,
    COMPLETED,
}
