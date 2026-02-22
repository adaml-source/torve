package com.streamvault.presentation.addon

import com.streamvault.domain.model.InstalledAddon

data class AddonUiState(
    val addons: List<InstalledAddon> = emptyList(),
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val error: String? = null,
    val installError: String? = null,
)
