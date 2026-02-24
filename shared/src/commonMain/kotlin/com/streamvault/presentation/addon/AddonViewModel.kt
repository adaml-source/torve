package com.streamvault.presentation.addon

import com.streamvault.domain.repository.AddonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddonViewModel(
    private val addonRepo: AddonRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(AddonUiState())
    val state: StateFlow<AddonUiState> = _state.asStateFlow()

    init {
        loadAddons()
    }

    fun loadAddons() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setInstallUrl(url: String) {
        _state.update { it.copy(installUrl = url) }
    }

    fun installAddon() {
        val url = _state.value.installUrl.trim()
        if (url.isBlank()) return

        scope.launch {
            _state.update { it.copy(isInstalling = true, installingUrl = url, installError = null) }
            try {
                addonRepo.installAddon(url)
                val addons = addonRepo.getInstalledAddons()
                _state.update {
                    it.copy(
                        addons = addons,
                        isInstalling = false,
                        installingUrl = "",
                        installUrl = "",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isInstalling = false, installingUrl = "", installError = e.message) }
            }
        }
    }

    fun removeAddon(manifestUrl: String) {
        scope.launch {
            try {
                addonRepo.removeAddon(manifestUrl)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleAddon(manifestUrl: String, enabled: Boolean) {
        scope.launch {
            try {
                addonRepo.toggleAddon(manifestUrl, enabled)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun reorderAddons(orderedUrls: List<String>) {
        scope.launch {
            try {
                addonRepo.reorderAddons(orderedUrls)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
