package com.torve.presentation.addon

import com.torve.data.addon.AddonSyncService
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddonViewModel(
    private val addonRepo: AddonRepository,
    private val addonSyncService: AddonSyncService,
    settingsRefreshNotifier: SettingsRefreshNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(AddonUiState())
    val state: StateFlow<AddonUiState> = _state.asStateFlow()

    init {
        loadAddons()
        scope.launch {
            settingsRefreshNotifier.events.collectLatest {
                loadAddons()
            }
        }
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
            _state.update { it.copy(isInstalling = true, installingUrl = url, lastInstallUrl = url, installError = null) }
            try {
                val installedAddon = addonRepo.installAddon(url)
                val addons = addonRepo.getInstalledAddons()
                _state.update {
                    it.copy(
                        addons = addons,
                        isInstalling = false,
                        installingUrl = "",
                        installUrl = "",
                    )
                }
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonInstalled(installedAddon)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isInstalling = false, installingUrl = "", installError = e.message) }
            }
        }
    }

    fun removeAddon(manifestUrl: String) {
        scope.launch {
            try {
                val existingAddon = addonRepo.getAddon(manifestUrl)
                addonRepo.removeAddon(manifestUrl)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonRemoved(existingAddon)
                }
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
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonStateChanged(manifestUrl)
                }
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
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonOrderChanged(orderedUrls)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
