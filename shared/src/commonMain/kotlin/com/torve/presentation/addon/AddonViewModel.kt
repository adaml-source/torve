package com.torve.presentation.addon

import com.torve.data.addon.AddonSyncService
import com.torve.data.contentpolicy.AddonPolicyRepository
import com.torve.data.panda.PandaApiClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.AddonPolicyFlags
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.util.ioDispatcher
import com.torve.util.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddonViewModel(
    private val addonRepo: AddonRepository,
    private val addonSyncService: AddonSyncService,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val addonPolicyRepository: AddonPolicyRepository? = null,
    private val pandaClient: PandaApiClient? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow(AddonUiState())
    val state: StateFlow<AddonUiState> = _state.asStateFlow()

    init {
        loadAddons()
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            settingsRefreshNotifier.events
                // Debounce: the notifier fires several times in quick
                // succession during startup and after each install/remove.
                // Collapse the burst so loadAddons() runs once, not 3-5×.
                .debounce(500L)
                .collectLatest {
                    loadAddons()
                }
        }
    }

    /**
     * Broadcast that the addon set has changed so downstream VMs (notably
     * [com.torve.presentation.settings.SettingsViewModel.hasStreamAddon])
     * recompute their gates without polling.
     */
    private fun pokeSettingsRefresh() {
        settingsRefreshNotifier.notifyRefresh(
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
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

        // Reject the bare/placeholder Panda manifest. A valid Panda manifest lives at
        // https://panda.torve.app/u/<token>/manifest.json after the user completes
        // the setup flow (POST /api/v1/configs). The base URL returns a configurable
        // placeholder whose /stream routes respond 404, so installing it produces a
        // broken addon. Any install attempt against that URL is a bug — route users
        // through the guided Panda setup instead.
        if (isBarePandaManifest(url)) {
            _state.update {
                it.copy(installError = "Run the Panda setup first — the bare Panda URL is a placeholder.")
            }
            return
        }

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
                scope.launch(ioDispatcher) {
                    addonSyncService.onAddonInstalled(installedAddon)
                }
                pokeSettingsRefresh()
            } catch (e: Exception) {
                _state.update { it.copy(isInstalling = false, installingUrl = "", installError = com.torve.presentation.error.UserFacingError.ADDON_INSTALL_FAILED.messageKey) }
            }
        }
    }

    fun removeAddon(manifestUrl: String) {
        scope.launch {
            try {
                val existingAddon = addonRepo.getAddon(manifestUrl)
                val wasPanda = existingAddon != null && (
                    existingAddon.manifest.id == "com.torve.panda" ||
                        existingAddon.manifestUrl.contains("panda.torve.app")
                )
                addonRepo.removeAddon(manifestUrl)
                val addons = addonRepo.getInstalledAddons()
                _state.update { it.copy(addons = addons) }
                pokeSettingsRefresh()
                scope.launch(ioDispatcher) {
                    addonSyncService.onAddonRemoved(existingAddon)
                    // If the user uninstalled Panda, also purge the per-user config on
                    // panda.torve.app and the cached PANDA_TOKEN. Otherwise the next
                    // Panda setup attempt would enter edit mode with a stale token
                    // and never actually install a new addon row.
                    if (wasPanda) {
                        val store = integrationSecretStore ?: return@launch
                        // Tear down server-side with the per-config management token
                        // if we still have it. The manifest-only path can't mutate
                        // since the contract update, so without a management token
                        // the server config survives as an orphan until its TTL.
                        val configId = existingAddon?.configId
                        if (!configId.isNullOrBlank()) {
                            val mgmt = store.get(
                                IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                                subKey = configId,
                            )
                            if (!mgmt.isNullOrBlank()) {
                                runCatching { pandaClient?.deleteConfig(configId, mgmt) }
                            }
                            store.remove(
                                IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                                subKey = configId,
                            )
                        }
                        store.remove(IntegrationSecretKey.PANDA_TOKEN)
                    }
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
                scope.launch(ioDispatcher) {
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
                scope.launch(ioDispatcher) {
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

    private fun isBarePandaManifest(url: String): Boolean = isBarePandaManifestUrl(url)

    companion object {
        fun normalizeManifestUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val base = trimmed.removeSuffix("/manifest.json")
            return "$base/manifest.json"
        }

        /**
         * True when the URL is the unconfigured Panda placeholder
         * (panda.torve.app/manifest.json) rather than a per-user manifest
         * (panda.torve.app/u/<token>/manifest.json). Registering the placeholder
         * is a bug — every /stream request 404s.
         */
        fun isBarePandaManifestUrl(url: String): Boolean {
            val normalized = normalizeManifestUrl(url).lowercase()
            if (!normalized.contains("panda.torve.app")) return false
            // Valid per-user Panda URLs include a "/u/<token>/" segment before manifest.json.
            return !normalized.contains("/u/")
        }
    }
}
