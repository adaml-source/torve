package com.torve.presentation.addon

import com.torve.domain.model.InstalledAddon

data class AddonUiState(
    val addons: List<InstalledAddon> = emptyList(),
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installingUrl: String = "",
    val lastInstallUrl: String = "",
    val installUrl: String = "",
    val error: String? = null,
    val installError: String? = null,
)
