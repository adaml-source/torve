package com.torve.presentation.addon

import com.torve.data.addon.AddonSyncService
import com.torve.data.contentpolicy.AddonPolicyRepository
import com.torve.domain.model.AddonPolicyFlags
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
    private val addonPolicyRepository: AddonPolicyRepository? = null,
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
                // Run Panda dedupe before reading, so the Settings "Addons · N installed"
                // row and every downstream consumer sees at most one Panda.
                runCatching { addonSyncService.collapseLocalPandaDuplicates() }
                val addons = addonRepo.getInstalledAddons()
                val flags = loadPolicyFlags(addons.map { it.manifestUrl })
                _state.update { it.copy(addons = addons, isLoading = false, policyFlagsByUrl = flags) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.ADDON_FAILED.messageKey) }
            }
        }
    }

    private suspend fun loadPolicyFlags(manifestUrls: List<String>): Map<String, AddonPolicyFlags> {
        val repo = addonPolicyRepository ?: return emptyMap()
        // Return ALL backend-known addon flags, so both installed and popular addons are covered
        return repo.getAllFlags()
    }

    /** Check installable flag for a manifest URL. Returns true if install is allowed. */
    fun isInstallAllowed(manifestUrl: String): Boolean {
        val flags = _state.value.policyFlagsByUrl[normalizeManifestUrl(manifestUrl)]
        // If backend hasn't provided flags, default to allowed
        return flags?.installable != false
    }

    fun setInstallUrl(url: String) {
        _state.update { it.copy(installUrl = url) }
    }

    fun installAddon() {
        val url = _state.value.installUrl.trim()
        if (url.isBlank()) return

        // Enforce backend installable flag
        if (!isInstallAllowed(url)) {
            _state.update { it.copy(installError = "This addon is not available on this build.") }
            return
        }

        scope.launch {
            _state.update { it.copy(isInstalling = true, installingUrl = url, lastInstallUrl = url, installError = null) }
            try {
                val installedAddon = addonRepo.installAddon(url)
                val addons = addonRepo.getInstalledAddons()
                val flags = loadPolicyFlags(addons.map { it.manifestUrl })
                _state.update {
                    it.copy(
                        addons = addons,
                        isInstalling = false,
                        installingUrl = "",
                        installUrl = "",
                        policyFlagsByUrl = flags,
                    )
                }
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonInstalled(installedAddon)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isInstalling = false, installingUrl = "", installError = com.torve.presentation.error.UserFacingError.ADDON_INSTALL_FAILED.messageKey) }
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
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.ADDON_FAILED.messageKey) }
            }
        }
    }

    fun toggleAddon(manifestUrl: String, enabled: Boolean) {
        // Block re-enabling addons that the backend marks non-installable
        if (enabled) {
            val flags = _state.value.policyFlagsByUrl[normalizeManifestUrl(manifestUrl)]
            if (flags?.installable == false) {
                _state.update { it.copy(error = "This addon is restricted on this build.") }
                return
            }
        }
        scope.launch {
            try {
                addonRepo.toggleAddon(manifestUrl, enabled)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
                scope.launch(Dispatchers.IO) {
                    addonSyncService.onAddonStateChanged(manifestUrl)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.ADDON_FAILED.messageKey) }
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
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.ADDON_FAILED.messageKey) }
            }
        }
    }

    /** Refresh policy flags from the cached addon policy repository. */
    fun refreshPolicyFlags() {
        scope.launch {
            val flags = loadPolicyFlags(
                _state.value.addons.map { it.manifestUrl },
            )
            _state.update { it.copy(policyFlagsByUrl = flags) }
        }
    }

    companion object {
        fun normalizeManifestUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val base = trimmed.removeSuffix("/manifest.json")
            return "$base/manifest.json"
        }
    }
}
