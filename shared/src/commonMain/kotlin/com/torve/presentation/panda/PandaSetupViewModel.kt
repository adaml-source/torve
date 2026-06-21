package com.torve.presentation.panda

import com.torve.data.addon.AddonSyncService
import com.torve.data.debrid.DebridClient
import com.torve.data.debrid.DeviceCodeInfo
import com.torve.data.panda.DownloadClientFieldSpec
import com.torve.data.panda.PandaApiClient
import com.torve.data.panda.PandaConfigPatch
import com.torve.data.panda.PandaConfigPayload
import com.torve.data.panda.PandaConfigSecrets
import com.torve.data.panda.PandaDebridConnection
import com.torve.data.panda.PandaProvider
import com.torve.data.panda.PandaSchema
import com.torve.data.panda.PandaSourceProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.DebridServiceType
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.addon.AddonViewModel
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.torveVerboseLog
import com.torve.presentation.integrations.findTorBoxCredential
import com.torve.presentation.integrations.syncTorBoxCredentialPair
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.util.ioDispatcher
import com.torve.util.mainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PandaSetupStep { SETUP_TYPE, PROVIDER, AUTH, SOURCES, USENET, QUALITY, REVIEW }

enum class PandaSetupMode { DEBRID, USENET_ONLY }

data class PandaSetupUiState(
    val currentStep: PandaSetupStep = PandaSetupStep.SETUP_TYPE,
    val setupMode: PandaSetupMode? = null,
    // Provider
    val providers: List<PandaProvider> = emptyList(),
    val providersLoading: Boolean = false,
    val selectedProvider: PandaProvider? = null,
    // Auth
    val authMethod: String = "oauth",
    val deviceCode: DeviceCodeInfo? = null,
    val apiKeyInput: String = "",
    val debridApiKey: String = "",
    val debridApiKeys: Map<String, String> = emptyMap(),
    val authLoading: Boolean = false,
    val authConnected: Boolean = false,
    val existingCredentialDetected: Boolean = false,
    // Sources
    val sourceProviders: List<PandaSourceProvider> = emptyList(),
    val enabledSources: Set<String> = emptySet(),
    // Usenet
    val enableUsenet: Boolean = false,
    val usenetProvider: String = "none",
    val usenetHost: String = "",
    val usenetPort: Int = 563,
    val usenetUsername: String = "",
    val usenetPassword: String = "",
    val usenetSSL: Boolean = true,
    val usenetConnections: Int = 10,
    // Legacy single-indexer fields — mirrored on save from nzbIndexers.first()
    // so older Panda deploys keep working. The multi-indexer array is authoritative.
    val nzbIndexer: String = "none",
    val nzbIndexerUrl: String = "",
    val nzbIndexerApiKey: String = "",
    // Preferred multi-indexer list. A single empty row keeps the UI rendered
    // even before the user configures anything.
    val nzbIndexers: List<com.torve.data.panda.NzbIndexerRow> = listOf(com.torve.data.panda.NzbIndexerRow()),
    val downloadClient: String = "none",
    val downloadClientUrl: String = "",
    val downloadClientUsername: String = "",
    val downloadClientPassword: String = "",
    val downloadClientApiKey: String = "",
    /**
     * When true and the full gate is satisfied (usenet on, at least one indexer
     * with a key, downloadClient in premiumize/torbox/alldebrid), Panda routes
     * playback through the cloud NZB path instead of Easynews for titles
     * available in both — preserving Easynews monthly bandwidth.
     */
    val easynewsPreferNzb: Boolean = false,
    // Quality
    val maxQuality: String = "1080p",
    val qualityProfile: String = "balanced",
    // Legacy single-language field — still read/written for older backends. Source of
    // truth for new configs is `releaseLanguages`. When editing an existing config
    // saved with just `releaseLanguage`, the ViewModel init seeds `releaseLanguages`
    // from it so the multi-select chip UI pre-selects correctly.
    val releaseLanguage: String = "any",
    val releaseLanguages: List<String> = listOf("any"),
    // Config save
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val pandaToken: String? = null,
    val manifestUrl: String? = null,
    val addonInstalled: Boolean = false,
    // Panda config id — returned by create, required for management-token
    // PATCH/DELETE/rotate. Stored alongside the manifest URL on disk.
    val configId: String? = null,
    /**
     * Increments on every successful `saveConfigAndInstall` call. The
     * setup screen watches this for transition events so it can show
     * "Saved" feedback and auto-close on edit-mode updates without
     * needing a separate "Save & Exit" button.
     */
    val saveCompletionToken: Int = 0,
    // True when a management token is persisted locally for [configId]. Edit
    // mode needs one to PATCH; missing ⇒ route to recovery.
    val hasManagementToken: Boolean = false,
    // Shown exactly once after create or rotate. UI masks by default, offers
    // copy, dismisses on "I've saved it". Never logged.
    val pendingManagementTokenDisplay: String? = null,
    val managementTokenNotice: String? = null,
    // True when the user opened edit mode for a config created before the
    // two-token rollout (or on a device that never saw the onboarding flow).
    // UI should gate the Save button and route to the recovery flow.
    val editRequiresRecovery: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryError: String? = null,
    val rotateInProgress: Boolean = false,
    val rotateError: String? = null,
    // General
    val error: String? = null,
    // Re-edit
    val isEditMode: Boolean = false,
    // Schema (server-driven dropdown options; populated at init)
    val schema: PandaSchema = FALLBACK_SCHEMA,
    /**
     * Field keys for credentials the server confirmed it has stored, even
     * though the read returned a redacted placeholder. The UI uses this to
     * show "Saved — type to replace" hints next to blank password fields.
     * Edit-mode save then omits any blank credential whose key is in this
     * set, so re-saving without re-typing doesn't blank out the server
     * value. Keys: `debrid_api_key`, `usenet_password`, `indexer_api_key_<index>`,
     * `download_client_password`, `download_client_api_key`.
     */
    val serverHasSecrets: Set<String> = emptySet(),
)

fun PandaSetupUiState.progressStepNumber(): Int {
    val steps = progressSteps()
    return (steps.indexOf(currentStep).takeIf { it >= 0 } ?: 0) + 1
}

fun PandaSetupUiState.progressStepCount(): Int = progressSteps().size

private fun PandaSetupUiState.progressSteps(): List<PandaSetupStep> =
    if (setupMode == PandaSetupMode.USENET_ONLY || selectedProvider?.id == "none") {
        listOf(
            PandaSetupStep.SETUP_TYPE,
            PandaSetupStep.USENET,
            PandaSetupStep.QUALITY,
            PandaSetupStep.REVIEW,
        )
    } else if (setupMode == PandaSetupMode.DEBRID) {
        listOf(
            PandaSetupStep.SETUP_TYPE,
            PandaSetupStep.AUTH,
            PandaSetupStep.SOURCES,
            PandaSetupStep.USENET,
            PandaSetupStep.QUALITY,
            PandaSetupStep.REVIEW,
        )
    } else {
        PandaSetupStep.entries.toList()
    }

/**
 * Returns true if [value] looks like a server-side redaction placeholder
 * (e.g. `[redacted]`, `***redacted***`, `<redacted>`). Panda emits these
 * for credential fields when reading with anything other than the
 * management/owner JWT — we treat them as "no value" and rely on
 * [PandaSetupUiState.serverHasSecrets] to remember which fields actually
 * had something on the server.
 */
internal fun isRedactedPlaceholder(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return value.contains("redact", ignoreCase = true)
}

internal fun cleanCredential(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank() || isRedactedPlaceholder(trimmed)) return null
    return trimmed
}

internal fun pandaDebridConnectionsForPayload(state: PandaSetupUiState): List<PandaDebridConnection> {
    if (state.setupMode == PandaSetupMode.USENET_ONLY || state.selectedProvider?.id == "none") {
        return emptyList()
    }
    val merged = linkedMapOf<String, String>()
    state.debridApiKeys.forEach { (provider, apiKey) ->
        cleanCredential(apiKey)?.let { merged[provider] = it }
    }
    val selectedId = state.selectedProvider?.id
    if (!selectedId.isNullOrBlank() && selectedId != "none") {
        cleanCredential(state.debridApiKey)?.let { merged[selectedId] = it }
    }
    return merged.map { (provider, apiKey) ->
        PandaDebridConnection(provider = provider, apiKey = apiKey, enabled = true)
    }
}

internal fun primaryPandaDebridConnection(state: PandaSetupUiState): PandaDebridConnection {
    if (state.setupMode == PandaSetupMode.USENET_ONLY || state.selectedProvider?.id == "none") {
        return PandaDebridConnection(provider = "none", apiKey = "", enabled = false)
    }
    val connections = pandaDebridConnectionsForPayload(state)
    val selectedId = state.selectedProvider?.id
    return connections.firstOrNull { it.provider == selectedId }
        ?: connections.firstOrNull()
        ?: PandaDebridConnection(provider = "none", apiKey = "", enabled = false)
}

private val FALLBACK_DOWNLOAD_CLIENT_FIELDS: Map<String, DownloadClientFieldSpec> = mapOf(
    "none" to DownloadClientFieldSpec(emptyList()),
    "premiumize" to DownloadClientFieldSpec(listOf("apiKey"), cloud = true),
    "torbox" to DownloadClientFieldSpec(listOf("apiKey"), cloud = true),
    "alldebrid" to DownloadClientFieldSpec(listOf("apiKey"), cloud = true),
    "nzbget" to DownloadClientFieldSpec(listOf("url", "username", "password")),
    "sabnzbd" to DownloadClientFieldSpec(listOf("url", "apiKey")),
)

internal val FALLBACK_SCHEMA = PandaSchema(
    debridServices = listOf(
        "none", "realdebrid", "premiumize", "alldebrid", "debridlink",
        "easydebrid", "offcloud", "torbox", "putio",
    ),
    usenetProviders = listOf("none", "easynews", "generic"),
    nzbIndexers = listOf("none", "nzbgeek", "scenenzbs", "dognzb", "nzbplanet", "custom"),
    downloadClients = listOf("none", "premiumize", "torbox", "alldebrid", "nzbget", "sabnzbd"),
    qualityOptions = listOf("2160p", "1080p", "720p", "480p"),
    qualityProfiles = listOf("balanced", "best_quality", "fast_start", "data_saver"),
    releaseLanguages = listOf(
        "any", "english", "german", "spanish", "italian", "french",
        "portuguese", "turkish", "japanese", "korean", "chinese", "hindi", "multi",
    ),
    sortOptions = listOf("quality", "qualitysize", "seeders", "size"),
    resultLimits = listOf("5", "10", "15", "20"),
    downloadClientFields = FALLBACK_DOWNLOAD_CLIENT_FIELDS,
)

class PandaSetupViewModel(
    private val pandaClient: PandaApiClient,
    private val debridClient: DebridClient,
    private val integrationSecretStore: IntegrationSecretStore,
    private val prefsRepo: com.torve.domain.repository.PreferencesRepository,
    private val addonRepo: AddonRepository,
    private val addonSyncService: AddonSyncService,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val accountSessionCoordinator: com.torve.presentation.session.AccountSessionCoordinator,
    private val configStateStore: PandaConfigStateStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow(PandaSetupUiState())
    val state: StateFlow<PandaSetupUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var configHydrationJob: Job? = null

    // Synthetic "no debrid" option so users with their own Usenet setup can skip debrid entirely.
    // Selecting this provider jumps past the AUTH step and the save payload goes out with
    // debridService = "none" (server already accepts this).
    private val noDebridProvider = PandaProvider(
        id = "none",
        name = "Use Usenet only (skip debrid)",
        authMethods = emptyList(),
    )

    // All debrid services supported by Panda web config — supplements the API response
    private val allPandaProviders = listOf(
        PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey"), helpUrl = "https://real-debrid.com/apitoken"),
        PandaProvider("premiumize", "Premiumize", listOf("oauth", "apikey"), helpUrl = "https://www.premiumize.me/account"),
        PandaProvider("alldebrid", "AllDebrid", listOf("oauth", "apikey"), helpUrl = "https://alldebrid.com/apikeys/"),
        PandaProvider("debridlink", "DebridLink", listOf("apikey"), helpUrl = "https://debrid-link.com/webapp/apikey"),
        PandaProvider("easydebrid", "EasyDebrid", listOf("apikey"), helpUrl = "https://easydebrid.com/settings"),
        PandaProvider("offcloud", "Offcloud", listOf("apikey"), helpUrl = "https://offcloud.com/#/account"),
        PandaProvider("torbox", "TorBox", listOf("apikey"), helpUrl = "https://torbox.app/settings"),
        PandaProvider("putio", "Put.io", listOf("apikey"), helpUrl = "https://app.put.io/settings/account/oauth/apps"),
    )

    // Torrent source providers (matches Panda web config)
    private val allSourceProviders = listOf(
        PandaSourceProvider("yts", "YTS", "Fast movie torrents", "movies"),
        PandaSourceProvider("eztv", "EZTV", "Episode-focused TV torrents", "series"),
        PandaSourceProvider("1337x", "1337x", "General torrent index", "general"),
        PandaSourceProvider("thepiratebay", "The Pirate Bay", "Large general torrent index", "general"),
        PandaSourceProvider("torrentgalaxy", "TorrentGalaxy", "Popular general tracker", "general"),
        PandaSourceProvider("magnetdl", "MagnetDL", "Lightweight public index", "general"),
        PandaSourceProvider("kickasstorrents", "Kickass Torrents", "General torrent index", "general"),
        PandaSourceProvider("nyaasi", "Nyaa", "Anime-focused source", "anime"),
        PandaSourceProvider("tokyotosho", "Tokyo Toshokan", "Anime and Japanese media", "anime"),
        PandaSourceProvider("anidex", "Anidex", "Anime-oriented source", "anime"),
        PandaSourceProvider("rutor", "Rutor", "Russian torrent source", "regional"),
        PandaSourceProvider("rutracker", "RuTracker", "Deep Russian catalog", "regional"),
    )

    private val defaultEnabledSources = setOf("yts", "eztv", "1337x", "thepiratebay", "torrentgalaxy", "nyaasi")

    // Maps provider IDs to IntegrationSecretKey for credential detection
    private val providerSecretKeys = mapOf(
        "realdebrid" to IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
        "alldebrid" to IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID,
        "premiumize" to IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
        "torbox" to IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
    )

    private suspend fun readStoredDebridApiKeys(): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        for ((providerId, secretKey) in providerSecretKeys) {
            val key = if (providerId == "torbox") {
                integrationSecretStore.syncTorBoxCredentialPair()
                integrationSecretStore.findTorBoxCredential()
            } else {
                integrationSecretStore.get(secretKey)
            }
            cleanCredential(key)?.let { entries[providerId] = it }
        }
        return entries
    }

    init {
        _state.update { it.copy(sourceProviders = allSourceProviders, enabledSources = defaultEnabledSources) }
        loadSchema()
        loadProviders()
        checkExistingConfig()
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            settingsRefreshNotifier.events
                .debounce(500L)
                .collectLatest {
                    checkExistingConfig()
                }
        }
        // Mirror every state change into the singleton store so
        // provider-health checkers see the current Panda config even
        // after this factory-bound VM is GC'd. Nullable when running
        // in legacy callers (tests) that don't pass a store.
        if (configStateStore != null) {
            scope.launch {
                _state.collect { latest -> configStateStore.publish(latest) }
            }
        }
    }

    private fun loadSchema() {
        // Hit the shared cache first (set once per app session).
        pandaClient.cachedSchemaOrNull()?.let { cached ->
            _state.update { it.copy(schema = mergeSchemaWithFallback(cached)) }
            return
        }
        scope.launch {
            try {
                val fetched = pandaClient.getPandaSchema()
                _state.update { it.copy(schema = mergeSchemaWithFallback(fetched)) }
            } catch (_: Exception) {
                // Fallback list already seeded via PandaSetupUiState default.
            }
        }
    }

    /**
     * Defensive: if the server returns a partial schema (e.g. an empty
     * `releaseLanguages` list — observed in the wild after a Panda config
     * change broke the language manifest), swap in the bundled fallback
     * for any list field that came back empty. Without this guard the
     * Quality step renders no language chips at all and users can't pick
     * a release language.
     */
    private fun mergeSchemaWithFallback(server: com.torve.data.panda.PandaSchema): com.torve.data.panda.PandaSchema =
        com.torve.data.panda.PandaSchema(
            debridServices = server.debridServices.ifEmpty { FALLBACK_SCHEMA.debridServices },
            usenetProviders = server.usenetProviders.ifEmpty { FALLBACK_SCHEMA.usenetProviders },
            nzbIndexers = server.nzbIndexers.ifEmpty { FALLBACK_SCHEMA.nzbIndexers },
            downloadClients = server.downloadClients.ifEmpty { FALLBACK_SCHEMA.downloadClients },
            qualityOptions = server.qualityOptions.ifEmpty { FALLBACK_SCHEMA.qualityOptions },
            qualityProfiles = server.qualityProfiles.ifEmpty { FALLBACK_SCHEMA.qualityProfiles },
            releaseLanguages = server.releaseLanguages.ifEmpty { FALLBACK_SCHEMA.releaseLanguages },
            sortOptions = server.sortOptions.ifEmpty { FALLBACK_SCHEMA.sortOptions },
            resultLimits = server.resultLimits.ifEmpty { FALLBACK_SCHEMA.resultLimits },
            downloadClientFields = server.downloadClientFields.ifEmpty { FALLBACK_SCHEMA.downloadClientFields },
        )

    private fun loadProviders() {
        scope.launch {
            _state.update { it.copy(providersLoading = true, error = null) }
            try {
                val apiProviders = pandaClient.getProviders()
                // Merge: use API data for providers it knows, add missing ones from the full list,
                // and always keep the "no debrid" skip row at the top.
                val apiIds = apiProviders.map { it.id }.toSet()
                val merged = apiProviders.filter { it.id != "none" } +
                    allPandaProviders.filter { it.id !in apiIds && it.id != "none" }
                val storedDebridKeys = readStoredDebridApiKeys()
                _state.update {
                    it.copy(
                        providers = merged,
                        providersLoading = false,
                        debridApiKeys = it.debridApiKeys + storedDebridKeys,
                        authConnected = it.authConnected || storedDebridKeys.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                val storedDebridKeys = readStoredDebridApiKeys()
                // Fallback to hardcoded list if API is unreachable
                _state.update {
                    it.copy(
                        providers = allPandaProviders,
                        providersLoading = false,
                        debridApiKeys = it.debridApiKeys + storedDebridKeys,
                        authConnected = it.authConnected || storedDebridKeys.isNotEmpty(),
                    )
                }
            }
        }
    }

    fun retryLoadProviders() {
        loadProviders()
    }

    fun selectSetupMode(mode: PandaSetupMode) {
        when (mode) {
            PandaSetupMode.DEBRID -> {
                pollJob?.cancel()
                _state.update { state ->
                    val selected = state.selectedProvider?.takeUnless { it.id == "none" }
                    state.copy(
                        setupMode = PandaSetupMode.DEBRID,
                        selectedProvider = selected,
                        authConnected = state.debridApiKeys.isNotEmpty(),
                        enabledSources = state.enabledSources.ifEmpty { defaultEnabledSources },
                        currentStep = PandaSetupStep.AUTH,
                        error = null,
                    )
                }
            }
            PandaSetupMode.USENET_ONLY -> selectUsenetOnly()
        }
    }

    private fun selectUsenetOnly() {
        pollJob?.cancel()
        // Usenet-only path: skip provider auth and torrent sources entirely.
        // Keep a synthetic provider internally because the Panda payload still
        // represents "no debrid" as debridService = "none".
        _state.update {
            it.copy(
                setupMode = PandaSetupMode.USENET_ONLY,
                selectedProvider = noDebridProvider,
                authMethod = "apikey",
                authConnected = true,
                existingCredentialDetected = false,
                debridApiKey = "",
                apiKeyInput = "",
                deviceCode = null,
                error = null,
                enabledSources = emptySet(),
                enableUsenet = true,
                currentStep = PandaSetupStep.USENET,
            )
        }
    }

    fun selectProvider(provider: PandaProvider) {
        if (provider.id == "none") {
            selectUsenetOnly()
            return
        }
        val defaultAuth = if ("oauth" in provider.authMethods) "oauth" else "apikey"
        _state.update {
            val existingKey = cleanCredential(it.debridApiKeys[provider.id])
            it.copy(
                setupMode = PandaSetupMode.DEBRID,
                selectedProvider = provider,
                authMethod = defaultAuth,
                authConnected = it.debridApiKeys.isNotEmpty(),
                existingCredentialDetected = existingKey != null,
                debridApiKey = existingKey.orEmpty(),
                apiKeyInput = existingKey.orEmpty(),
                deviceCode = null,
                error = null,
                currentStep = PandaSetupStep.AUTH,
            )
        }
        // Check for existing credential
        checkExistingCredential(provider.id)
    }

    private fun checkExistingCredential(providerId: String) {
        val secretKey = providerSecretKeys[providerId] ?: return
        scope.launch {
            val existingKey = integrationSecretStore.get(secretKey)
            if (!existingKey.isNullOrBlank()) {
                _state.update {
                    val clean = cleanCredential(existingKey) ?: return@update it
                    val updatedKeys = it.debridApiKeys + (providerId to clean)
                    val isSelected = it.selectedProvider?.id == providerId
                    it.copy(
                        debridApiKeys = updatedKeys,
                        debridApiKey = if (isSelected) clean else it.debridApiKey,
                        apiKeyInput = if (isSelected) clean else it.apiKeyInput,
                        authConnected = updatedKeys.isNotEmpty(),
                        existingCredentialDetected = isSelected || it.existingCredentialDetected,
                    )
                }
            }
        }
    }

    fun setAuthMethod(method: String) {
        pollJob?.cancel()
        _state.update {
            it.copy(
                authMethod = method,
                deviceCode = null,
                authLoading = false,
                error = null,
            )
        }
    }

    private fun toDebridServiceType(providerId: String): DebridServiceType? {
        return when (providerId) {
            "realdebrid" -> DebridServiceType.REAL_DEBRID
            "premiumize" -> DebridServiceType.PREMIUMIZE
            "alldebrid" -> DebridServiceType.ALL_DEBRID
            "torbox" -> DebridServiceType.TORBOX
            else -> null
        }
    }

    private fun providerIdForDebridType(provider: DebridServiceType): String = when (provider) {
        DebridServiceType.REAL_DEBRID -> "realdebrid"
        DebridServiceType.ALL_DEBRID -> "alldebrid"
        DebridServiceType.PREMIUMIZE -> "premiumize"
        DebridServiceType.TORBOX -> "torbox"
    }

    fun startOAuth() {
        val provider = _state.value.selectedProvider ?: return
        val debridType = toDebridServiceType(provider.id)
        if (debridType == null) {
            _state.update { it.copy(authMethod = "apikey", error = "Browser sign-in not available for ${provider.name}.") }
            return
        }

        scope.launch {
            _state.update { it.copy(authLoading = true, error = null, deviceCode = null) }
            try {
                val code = debridClient.getDeviceCode(debridType)
                if (code != null) {
                    _state.update { it.copy(deviceCode = code, authLoading = false) }
                    pollOAuth(debridType, code)
                } else {
                    _state.update {
                        it.copy(authLoading = false, authMethod = "apikey", error = "Browser sign-in not available. Enter your API key instead.")
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(authLoading = false, authMethod = "apikey", error = "Browser sign-in failed. Enter your API key instead.")
                }
            }
        }
    }

    private fun pollOAuth(debridType: DebridServiceType, code: DeviceCodeInfo) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                try {
                    val result = debridClient.pollDeviceAuth(debridType, code.deviceCode, code.userCode)
                    if (result.done && result.apiKey != null) {
                        // Persist credentials to IntegrationSecretStore so the
                        // shared rdTokenRefresher can find them at runtime.
                        // Without this, the access token expires after ~24h and
                        // refresh always returns null (because the OAuth bundle
                        // it needs - refresh_token, client_id, client_secret -
                        // was never stored), forcing the user to re-authenticate.
                        // Mirrors Android's SettingsViewModel.kt:651-658.
                        integrationSecretStore.put(
                            debridSecretKey(debridType),
                            result.apiKey,
                        )
                        if (debridType == DebridServiceType.TORBOX) {
                            integrationSecretStore.syncTorBoxCredentialPair(result.apiKey)
                        }
                        result.oauthTokens?.let { tokens ->
                            integrationSecretStore.put(
                                IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN,
                                tokens.refreshToken,
                            )
                            integrationSecretStore.put(
                                IntegrationSecretKey.DEBRID_RD_CLIENT_ID,
                                tokens.clientId,
                            )
                            integrationSecretStore.put(
                                IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET,
                                tokens.clientSecret,
                            )
                        }
                        _state.update {
                            val providerId = providerIdForDebridType(debridType)
                            val updatedKeys = it.debridApiKeys + (providerId to result.apiKey)
                            val isSelected = it.selectedProvider?.id == providerId
                            it.copy(
                                debridApiKey = if (isSelected) result.apiKey else it.debridApiKey,
                                apiKeyInput = if (isSelected) result.apiKey else it.apiKeyInput,
                                debridApiKeys = updatedKeys,
                                authConnected = updatedKeys.isNotEmpty(),
                                deviceCode = null,
                            )
                        }
                        return@launch
                    }
                } catch (_: Exception) {
                    // keep polling
                }
            }
            _state.update { it.copy(deviceCode = null, error = "Authorization timed out. Try again.") }
        }
    }

    private fun debridSecretKey(provider: DebridServiceType): IntegrationSecretKey = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }

    private suspend fun cachedDownloadClientApiKey(downloadClient: String): String? {
        val cached = integrationSecretStore.get(
            IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
            subKey = downloadClient,
        )?.takeIf { it.isNotBlank() && !isRedactedPlaceholder(it) }
        if (cached != null) {
            if (downloadClient == "torbox") {
                integrationSecretStore.syncTorBoxCredentialPair(cached)
            }
            return cached
        }
        return if (downloadClient == "torbox") {
            integrationSecretStore.syncTorBoxCredentialPair()
            integrationSecretStore.findTorBoxCredential()
        } else {
            null
        }
    }

    fun setApiKeyInput(key: String) {
        _state.update { it.copy(apiKeyInput = key) }
    }

    fun validateApiKey() {
        val provider = _state.value.selectedProvider ?: return
        val key = _state.value.apiKeyInput.trim()
        if (key.isBlank()) return

        scope.launch {
            _state.update { it.copy(authLoading = true, error = null) }
            try {
                pandaClient.validateApiKey(provider.id, key)
                toDebridServiceType(provider.id)?.let { debridType ->
                    integrationSecretStore.put(debridSecretKey(debridType), key)
                    if (debridType == DebridServiceType.TORBOX) {
                        integrationSecretStore.syncTorBoxCredentialPair(key)
                    }
                }
                _state.update {
                    val updatedKeys = it.debridApiKeys + (provider.id to key)
                    it.copy(
                        debridApiKey = key,
                        debridApiKeys = updatedKeys,
                        authConnected = updatedKeys.isNotEmpty(),
                        authLoading = false,
                        existingCredentialDetected = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(authLoading = false, error = e.message) }
            }
        }
    }

    fun disconnectSelectedDebrid() {
        val provider = _state.value.selectedProvider ?: return
        val debridType = toDebridServiceType(provider.id)
        pollJob?.cancel()
        if (debridType != null) {
            scope.launch {
                integrationSecretStore.remove(debridSecretKey(debridType))
                if (debridType == DebridServiceType.REAL_DEBRID) {
                    integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN)
                    integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_CLIENT_ID)
                    integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET)
                }
            }
        }
        _state.update {
            val updatedKeys = it.debridApiKeys - provider.id
            it.copy(
                debridApiKeys = updatedKeys,
                debridApiKey = "",
                apiKeyInput = "",
                authConnected = updatedKeys.isNotEmpty(),
                existingCredentialDetected = false,
                deviceCode = null,
                authLoading = false,
                error = null,
            )
        }
    }

    fun reconnectSelectedDebrid() {
        val supportsOAuth = _state.value.selectedProvider?.authMethods?.contains("oauth") == true
        disconnectSelectedDebrid()
        _state.update {
            it.copy(authMethod = if (supportsOAuth) "oauth" else "apikey")
        }
        if (supportsOAuth) {
            startOAuth()
        }
    }

    // Usenet setters
    fun setEnableUsenet(enabled: Boolean) { _state.update { it.copy(enableUsenet = enabled) } }
    fun setUsenetProvider(provider: String) { _state.update { it.copy(usenetProvider = provider) } }
    fun setUsenetHost(host: String) { _state.update { it.copy(usenetHost = host) } }
    fun setUsenetPort(port: Int) { _state.update { it.copy(usenetPort = port) } }
    fun setUsenetUsername(username: String) { _state.update { it.copy(usenetUsername = username) } }
    fun setUsenetPassword(password: String) { _state.update { it.copy(usenetPassword = password) } }
    fun setUsenetSSL(ssl: Boolean) { _state.update { it.copy(usenetSSL = ssl) } }
    fun setUsenetConnections(connections: Int) { _state.update { it.copy(usenetConnections = connections) } }
    // Legacy single-indexer setters — still used by any caller that hasn't
    // migrated to the per-row handlers. They also seed the array so the new UI
    // stays in sync.
    fun setNzbIndexer(indexer: String) {
        _state.update {
            val updatedRows = it.nzbIndexers.ifEmpty { listOf(com.torve.data.panda.NzbIndexerRow()) }
            it.copy(
                nzbIndexer = indexer,
                nzbIndexers = listOf(updatedRows.first().copy(type = indexer)) + updatedRows.drop(1),
            )
        }
    }
    fun setNzbIndexerUrl(url: String) {
        _state.update {
            val updatedRows = it.nzbIndexers.ifEmpty { listOf(com.torve.data.panda.NzbIndexerRow()) }
            it.copy(
                nzbIndexerUrl = url,
                nzbIndexers = listOf(updatedRows.first().copy(url = url)) + updatedRows.drop(1),
            )
        }
    }
    fun setNzbIndexerApiKey(apiKey: String) {
        _state.update {
            val updatedRows = it.nzbIndexers.ifEmpty { listOf(com.torve.data.panda.NzbIndexerRow()) }
            it.copy(
                nzbIndexerApiKey = apiKey,
                nzbIndexers = listOf(updatedRows.first().copy(apiKey = apiKey)) + updatedRows.drop(1),
            )
        }
    }

    /** Append a blank indexer row. UI uses this for the "Add another indexer" button. */
    fun addIndexer() {
        _state.update { it.copy(nzbIndexers = it.nzbIndexers + com.torve.data.panda.NzbIndexerRow()) }
    }

    /** Remove the indexer row at [index]. Always leaves at least one (empty) row. */
    fun removeIndexer(index: Int) {
        _state.update { state ->
            val remaining = state.nzbIndexers.filterIndexed { i, _ -> i != index }
                .ifEmpty { listOf(com.torve.data.panda.NzbIndexerRow()) }
            // Keep legacy scalars pointed at the first remaining row so save-time
            // compat fields stay sensible immediately, before next save.
            val first = remaining.first()
            state.copy(
                nzbIndexers = remaining,
                nzbIndexer = first.type,
                nzbIndexerUrl = first.url,
                nzbIndexerApiKey = first.apiKey,
            )
        }
    }

    /** Mutate one indexer row in place via [transform]. */
    fun updateIndexer(index: Int, transform: (com.torve.data.panda.NzbIndexerRow) -> com.torve.data.panda.NzbIndexerRow) {
        _state.update { state ->
            val updated = state.nzbIndexers.mapIndexed { i, row ->
                if (i == index) transform(row) else row
            }
            val first = updated.firstOrNull() ?: com.torve.data.panda.NzbIndexerRow()
            state.copy(
                nzbIndexers = updated,
                // Legacy scalars track index 0 so save-time compat is trivial.
                nzbIndexer = first.type,
                nzbIndexerUrl = first.url,
                nzbIndexerApiKey = first.apiKey,
            )
        }
    }

    fun setBandwidthSaver(on: Boolean) {
        _state.update { it.copy(easynewsPreferNzb = on) }
    }
    fun setDownloadClient(client: String) { _state.update { it.copy(downloadClient = client) } }
    fun setDownloadClientUrl(url: String) { _state.update { it.copy(downloadClientUrl = url) } }
    fun setDownloadClientUsername(username: String) { _state.update { it.copy(downloadClientUsername = username) } }
    fun setDownloadClientPassword(password: String) { _state.update { it.copy(downloadClientPassword = password) } }
    fun setDownloadClientApiKey(apiKey: String) { _state.update { it.copy(downloadClientApiKey = apiKey) } }

    fun toggleSource(sourceId: String) {
        _state.update {
            val current = it.enabledSources.toMutableSet()
            if (sourceId in current) current.remove(sourceId) else current.add(sourceId)
            it.copy(enabledSources = current)
        }
    }

    fun setMaxQuality(quality: String) {
        _state.update { it.copy(maxQuality = quality) }
    }

    fun setQualityProfile(profile: String) {
        _state.update { it.copy(qualityProfile = profile) }
    }

    /**
     * Single-language setter kept for the legacy scalar. New UI should drive the
     * multi-select via [toggleLanguage]; this function still mirrors the value
     * into the legacy field so older Panda backends keep working.
     */
    fun setReleaseLanguage(language: String) {
        _state.update {
            it.copy(
                releaseLanguage = language,
                releaseLanguages = listOf(language),
            )
        }
    }

    /**
     * Toggle a language in the multi-select.
     *
     * "any" is exclusive: selecting it clears specific languages; selecting a
     * specific language clears "any". An empty selection is never persisted —
     * it falls back to `["any"]` so the user always has a valid state.
     */
    fun toggleLanguage(code: String, selected: Boolean) {
        _state.update { s ->
            val current = s.releaseLanguages.toMutableSet()
            if (code == "any") {
                if (selected) {
                    current.clear()
                    current.add("any")
                } else {
                    current.remove("any")
                }
            } else {
                if (selected) {
                    current.remove("any")
                    current.add(code)
                } else {
                    current.remove(code)
                }
            }
            if (current.isEmpty()) current.add("any")
            val list = current.toList()
            s.copy(
                releaseLanguages = list,
                // Keep legacy scalar in sync with the first selection so the
                // fallback field stays meaningful on older backends.
                releaseLanguage = list.firstOrNull() ?: "any",
            )
        }
    }

    fun nextStep() {
        _state.update { s ->
            val next = when (s.currentStep) {
                PandaSetupStep.SETUP_TYPE -> when (s.setupMode) {
                    PandaSetupMode.DEBRID -> PandaSetupStep.AUTH
                    PandaSetupMode.USENET_ONLY -> PandaSetupStep.USENET
                    null -> PandaSetupStep.SETUP_TYPE
                }
                PandaSetupStep.PROVIDER -> PandaSetupStep.AUTH
                PandaSetupStep.AUTH -> PandaSetupStep.SOURCES
                PandaSetupStep.SOURCES -> PandaSetupStep.USENET
                PandaSetupStep.USENET -> PandaSetupStep.QUALITY
                PandaSetupStep.QUALITY -> PandaSetupStep.REVIEW
                PandaSetupStep.REVIEW -> PandaSetupStep.REVIEW
            }
            s.copy(currentStep = next)
        }
    }

    fun previousStep() {
        pollJob?.cancel()
        _state.update { s ->
            val skipDebrid = s.setupMode == PandaSetupMode.USENET_ONLY || s.selectedProvider?.id == "none"
            val prev = when (s.currentStep) {
                PandaSetupStep.SETUP_TYPE -> PandaSetupStep.SETUP_TYPE
                PandaSetupStep.PROVIDER -> PandaSetupStep.SETUP_TYPE
                PandaSetupStep.AUTH -> PandaSetupStep.SETUP_TYPE
                // "No debrid" path never enters AUTH — jump straight back to PROVIDER.
                PandaSetupStep.SOURCES -> if (skipDebrid) PandaSetupStep.SETUP_TYPE else PandaSetupStep.AUTH
                // "No debrid" path skips SOURCES entirely — back from USENET → PROVIDER.
                PandaSetupStep.USENET -> if (skipDebrid) PandaSetupStep.SETUP_TYPE else PandaSetupStep.SOURCES
                PandaSetupStep.QUALITY -> PandaSetupStep.USENET
                PandaSetupStep.REVIEW -> PandaSetupStep.QUALITY
            }
            s.copy(currentStep = prev, error = null)
        }
    }

    fun saveConfigAndInstall() {
        scope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val s = _state.value
                torveVerboseLog { "TORVE PANDA | saveConfigAndInstall start editMode=${s.isEditMode} hasManifestCredential=${s.pandaToken != null} hasConfigId=${!s.configId.isNullOrBlank()} hasManagementCredential=${s.hasManagementToken}" }
                s.selectedProvider ?: throw Exception("No provider selected")
                val debridConnections = pandaDebridConnectionsForPayload(s)
                val primaryDebrid = primaryPandaDebridConnection(s)

                // Filter out the blank placeholder rows (type=="none" or empty key)
                // before emitting. The first surviving row also drives the legacy
                // scalar fields so older Panda deploys still see a usable indexer.
                val configuredIndexers = s.nzbIndexers.filter {
                    it.type != "none" && it.apiKey.isNotBlank()
                }
                val firstIndexer = configuredIndexers.firstOrNull()

                if (s.isEditMode && s.pandaToken != null) {
                    val configId = s.configId
                    if (configId.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveError = "This Panda config is missing its identifier. Remove and re-install the addon to continue.",
                            )
                        }
                        return@launch
                    }
                    // Prefer the Torve JWT (Panda binds configs to Torve
                    // accounts as of 2026-04-27); fall back to a cached
                    // management_token for old rows that haven't been
                    // backfilled. Either token goes through `bearerToken`
                    // — Panda picks the right validator server-side.
                    val torveToken = accountSessionCoordinator.getTorveAccessToken()
                    val mgmtToken = integrationSecretStore.get(
                        IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                        subKey = configId,
                    )
                    val bearer = torveToken ?: mgmtToken
                    if (bearer.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveError = "You're signed out — sign in to Torve to update your Panda config.",
                            )
                        }
                        return@launch
                    }
                    // Skip-blank-credentials: if the read returned a redacted
                    // value for a credential, [serverHasSecrets] will contain
                    // its key. The user may not have re-typed it. Sending the
                    // empty string would overwrite the server's real value.
                    // Send `null` instead so the server keeps what it has.
                    fun keepOrNull(value: String, key: String): String? =
                        if (value.isBlank() && key in s.serverHasSecrets) null else value
                    val hasServerDebridSecret = "debrid_api_key" in s.serverHasSecrets ||
                        s.serverHasSecrets.any { it.startsWith("debrid_api_key_") }
                    val patchedDebridApiKey = if (primaryDebrid.apiKey.isBlank() && hasServerDebridSecret) {
                        null
                    } else {
                        keepOrNull(primaryDebrid.apiKey, "debrid_api_key")
                    }
                    val patchedUsenetPassword = keepOrNull(s.usenetPassword, "usenet_password")
                    val patchedDownloadClientPassword =
                        keepOrNull(s.downloadClientPassword, "download_client_password")
                    val patchedDownloadClientApiKey =
                        keepOrNull(s.downloadClientApiKey, "download_client_api_key")
                    // Indexer preservation: if ANY row has a blank apiKey
                    // whose position was server-redacted on read, the user
                    // hasn't re-typed it. Sending the current `nzbIndexers`
                    // would either drop the row (filtered above) or push
                    // an empty key, both of which clear the server's stored
                    // indexers — and that's exactly what wiped usenet
                    // sources from the picker. Bail out: send `null` for
                    // the array AND the legacy scalars so the server keeps
                    // its existing list intact. Trade-off: a user who edits
                    // one indexer must re-type the others; the alternative
                    // (silent data loss) is worse.
                    val hasUnchangedIndexerSecret = s.nzbIndexers.withIndex().any { (idx, row) ->
                        row.type != "none" &&
                            row.apiKey.isBlank() &&
                            ("indexer_api_key_$idx" in s.serverHasSecrets ||
                                (idx == 0 && "indexer_api_key_legacy" in s.serverHasSecrets))
                    }
                    val patchedIndexers: List<com.torve.data.panda.NzbIndexerRow>? =
                        if (hasUnchangedIndexerSecret) null else configuredIndexers
                    val patchedFirstIndexer = configuredIndexers.firstOrNull()
                    val patchedNzbIndexer: String? =
                        if (hasUnchangedIndexerSecret) null else patchedFirstIndexer?.type ?: "none"
                    val patchedNzbIndexerUrl: String? =
                        if (hasUnchangedIndexerSecret) null else patchedFirstIndexer?.url.orEmpty()
                    val patchedNzbIndexerApiKey: String? =
                        if (hasUnchangedIndexerSecret) null else patchedFirstIndexer?.apiKey.orEmpty()
                    torveVerboseLog { "TORVE PANDA | saveConfigAndInstall edit hasUnchangedIndexerCredential=$hasUnchangedIndexerSecret indexerRows=${s.nzbIndexers.size} configured=${configuredIndexers.size}" }
                    pandaClient.updateConfig(
                        configId = configId,
                        bearerToken = bearer,
                        patch = PandaConfigPatch(
                            debridService = primaryDebrid.provider,
                            debridApiKey = patchedDebridApiKey,
                            debridConnections = debridConnections.takeIf { it.isNotEmpty() },
                            enabledProviders = s.enabledSources.toList(),
                            maxQuality = s.maxQuality,
                            qualityProfile = s.qualityProfile,
                            // Emit both: the array is authoritative on new Panda deploys,
                            // scalar keeps older ones happy.
                            releaseLanguage = s.releaseLanguages.firstOrNull() ?: s.releaseLanguage,
                            releaseLanguages = s.releaseLanguages,
                            enableUsenet = s.enableUsenet,
                            usenetProvider = s.usenetProvider,
                            usenetHost = s.usenetHost,
                            usenetPort = s.usenetPort,
                            usenetUsername = s.usenetUsername,
                            usenetPassword = patchedUsenetPassword,
                            usenetSSL = s.usenetSSL,
                            usenetConnections = s.usenetConnections,
                            // Legacy scalars mirror the first configured row,
                            // null when we're preserving the indexer list.
                            nzbIndexer = patchedNzbIndexer,
                            nzbIndexerUrl = patchedNzbIndexerUrl,
                            nzbIndexerApiKey = patchedNzbIndexerApiKey,
                            // Preferred multi-indexer array (null preserves server).
                            nzbIndexers = patchedIndexers,
                            downloadClient = s.downloadClient,
                            downloadClientUrl = s.downloadClientUrl,
                            downloadClientUsername = s.downloadClientUsername,
                            downloadClientPassword = patchedDownloadClientPassword,
                            downloadClientApiKey = patchedDownloadClientApiKey,
                            easynewsPreferNzb = s.easynewsPreferNzb,
                        ),
                    )
                    _state.update {
                        it.copy(
                            isSaving = false,
                            addonInstalled = true,
                            saveCompletionToken = it.saveCompletionToken + 1,
                        )
                    }
                } else {
                    val configPayload = PandaConfigPayload(
                        enabledProviders = s.enabledSources.toList(),
                        debridService = primaryDebrid.provider,
                        debridApiKey = primaryDebrid.apiKey,
                        debridConnections = debridConnections,
                        maxQuality = s.maxQuality,
                        qualityProfile = s.qualityProfile,
                        // Emit both legacy scalar + preferred array.
                        releaseLanguage = s.releaseLanguages.firstOrNull() ?: s.releaseLanguage,
                        releaseLanguages = s.releaseLanguages,
                        enableUsenet = s.enableUsenet,
                        usenetProvider = s.usenetProvider,
                        usenetHost = s.usenetHost,
                        usenetPort = s.usenetPort,
                        usenetUsername = s.usenetUsername,
                        usenetPassword = s.usenetPassword,
                        usenetSSL = s.usenetSSL,
                        usenetConnections = s.usenetConnections,
                        // Legacy scalars mirror the first configured row.
                        nzbIndexer = firstIndexer?.type ?: "none",
                        nzbIndexerUrl = firstIndexer?.url.orEmpty(),
                        nzbIndexerApiKey = firstIndexer?.apiKey.orEmpty(),
                        nzbIndexers = configuredIndexers,
                        downloadClient = s.downloadClient,
                        downloadClientUrl = s.downloadClientUrl,
                        downloadClientUsername = s.downloadClientUsername,
                        downloadClientPassword = s.downloadClientPassword,
                        downloadClientApiKey = s.downloadClientApiKey,
                        easynewsPreferNzb = s.easynewsPreferNzb,
                    )
                    val response = pandaClient.createConfig(configPayload)
                    val token = response.pandaToken ?: throw Exception("No token returned")
                    val manifestUrl = response.manifestUrl ?: throw Exception("No manifest URL returned")
                    val configId = response.configId
                    val managementToken = response.managementToken

                    integrationSecretStore.put(IntegrationSecretKey.PANDA_TOKEN, token)
                    integrationSecretStore.setStorageMode(
                        IntegrationSecretKey.PANDA_TOKEN,
                        com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT,
                    )

                    // Management token now syncs as ACCOUNT mode alongside the
                    // manifest token — see "Panda integration sync bundle" doc
                    // in memory. Cross-device users (desktop signing in to a
                    // mobile-configured account) need the management token to
                    // edit the existing Panda config. Keyed by config_id so
                    // multiple configs coexist on a single install.
                    if (!configId.isNullOrBlank() && !managementToken.isNullOrBlank()) {
                        integrationSecretStore.put(
                            key = IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                            value = managementToken,
                            subKey = configId,
                        )
                        integrationSecretStore.setStorageMode(
                            IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                            com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT,
                        )
                    }
                    // Push the full bundle to /me/integrations so a fresh install /
                    // new device can fully manage the Panda config (read AND edit),
                    // not just install the manifest. Best-effort: failures are
                    // non-fatal (creds still live locally).
                    runCatching {
                        accountSessionCoordinator.saveIntegrationToBackend(
                            integrationType = "PANDA_TOKEN",
                            credentials = buildMap {
                                put("token", token)
                                put("manifest_url", manifestUrl)
                                if (!configId.isNullOrBlank()) put("config_id", configId)
                                if (!managementToken.isNullOrBlank()) put("management_token", managementToken)
                            },
                            displayIdentifier = "Panda",
                        )
                    }

                    // Dedupe: Panda may already be installed under the base manifest URL
                    // (panda.torve.app/manifest.json — e.g. from the addon catalog "Install"
                    // button) or under a prior config's URL (panda.torve.app/{id}/manifest.json).
                    // Keep exactly one Panda row: remove any existing Panda installation
                    // (matched by manifest id or by PANDA base URL), then install the
                    // freshly-configured manifest URL.
                    val newNormalizedUrl = AddonViewModel.normalizeManifestUrl(manifestUrl)
                    runCatching {
                        addonRepo.getInstalledAddons()
                            .filter { a ->
                                val aUrl = a.manifestUrl
                                val normalizedExisting = AddonViewModel.normalizeManifestUrl(aUrl)
                                val isPanda = a.manifest.id == "com.torve.panda" ||
                                    aUrl.contains("panda.torve.app") ||
                                    normalizedExisting.contains("panda.torve.app")
                                // Don't remove the one we're about to install (can't happen here
                                // since we just created a new config, but guard anyway).
                                isPanda && normalizedExisting != newNormalizedUrl
                            }
                            .forEach { stale ->
                                runCatching { addonRepo.removeAddon(stale.manifestUrl) }
                            }
                    }

                    val existing = try { addonRepo.getAddon(newNormalizedUrl) } catch (_: Exception) { null }
                    if (existing == null) {
                        val installed = addonRepo.installAddon(manifestUrl)
                        scope.launch(ioDispatcher) {
                            addonSyncService.onAddonInstalled(installed)
                        }
                    }
                    // Persist config_id on the addon row so later management-token
                    // calls know which config to address. Done unconditionally so
                    // a stale id from a prior install is overwritten.
                    if (!configId.isNullOrBlank()) {
                        runCatching { addonRepo.setAddonConfigId(newNormalizedUrl, configId) }
                    }

                    _state.update {
                        it.copy(
                            isSaving = false,
                            pandaToken = token,
                            manifestUrl = manifestUrl,
                            configId = configId,
                            hasManagementToken = !managementToken.isNullOrBlank(),
                            pendingManagementTokenDisplay = managementToken?.takeIf { it.isNotBlank() },
                            managementTokenNotice = response.managementTokenNotice,
                            addonInstalled = true,
                            saveCompletionToken = it.saveCompletionToken + 1,
                        )
                    }
                }

                // Also store every debrid credential locally so Torve's direct
                // streaming services can use the same keys Panda has.
                debridConnections.forEach { connection ->
                    val debridType = toDebridServiceType(connection.provider) ?: return@forEach
                    val secretKey = debridSecretKey(debridType)
                    integrationSecretStore.put(secretKey, connection.apiKey)
                    if (debridType == DebridServiceType.TORBOX) {
                        integrationSecretStore.syncTorBoxCredentialPair(connection.apiKey)
                    }
                }
                toDebridServiceType(primaryDebrid.provider)?.let { prefsRepo.setString("debrid_provider", it.name) }
                prefsRepo.setString("panda_download_client", s.downloadClient)
                prefsRepo.setString("panda_usenet_provider", if (s.enableUsenet) s.usenetProvider else "none")
                prefsRepo.setString(
                    "panda_indexer_subkeys",
                    s.nzbIndexers.filter { it.type != "none" && it.url.isNotBlank() }
                        .joinToString("\n") { "${it.type}|${it.url}" },
                )

                // Mirror the user's typed Panda secrets into the device's
                // secret store. Panda always returns these as `[redacted]`
                // on read, so without a local cache the desktop's NZB
                // catalog tabs (Adult / Sports / Movies-via-Usenet, which
                // talk to the Newznab indexer directly) lose the apiKey
                // across every restart. Storing them here closes that gap
                // — on next hydrate the local copy fills in for the
                // redacted server value. DEVICE_ONLY: a re-issued
                // management token from a different device shouldn't be
                // able to read these via the Torve account sync.
                s.nzbIndexers.forEachIndexed { idx, row ->
                    if (row.type != "none" && row.apiKey.isNotBlank() &&
                        !isRedactedPlaceholder(row.apiKey)
                    ) {
                        val subKey = "${row.type}|${row.url}"
                        integrationSecretStore.put(
                            key = IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                            value = row.apiKey,
                            subKey = subKey,
                        )
                        integrationSecretStore.put(
                            key = IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                            value = row.apiKey,
                            subKey = row.type,
                        )
                    }
                }
                if (s.downloadClient != "none") {
                    if (s.downloadClientApiKey.isNotBlank() &&
                        !isRedactedPlaceholder(s.downloadClientApiKey)
                    ) {
                        integrationSecretStore.put(
                            key = IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                            value = s.downloadClientApiKey,
                            subKey = s.downloadClient,
                        )
                        if (s.downloadClient == "torbox") {
                            integrationSecretStore.syncTorBoxCredentialPair(s.downloadClientApiKey)
                        }
                    }
                    if (s.downloadClientPassword.isNotBlank() &&
                        !isRedactedPlaceholder(s.downloadClientPassword)
                    ) {
                        integrationSecretStore.put(
                            key = IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD,
                            value = s.downloadClientPassword,
                            subKey = s.downloadClient,
                        )
                    }
                }
                if (s.enableUsenet && s.usenetProvider != "none" &&
                    s.usenetPassword.isNotBlank() &&
                    !isRedactedPlaceholder(s.usenetPassword)
                ) {
                    integrationSecretStore.put(
                        key = IntegrationSecretKey.PANDA_USENET_PASSWORD,
                        value = s.usenetPassword,
                        subKey = s.usenetProvider,
                    )
                }

                settingsRefreshNotifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                torveVerboseLog { "TORVE PANDA | saveConfigAndInstall succeeded" }
            } catch (e: Exception) {
                val safeMessage = DiagnosticsRedactor.redact(e.message).ifBlank { "Save failed" }
                torveVerboseLog { "TORVE PANDA | saveConfigAndInstall failed: $safeMessage" }
                _state.update { it.copy(isSaving = false, saveError = safeMessage) }
            }
        }
    }

    private fun checkExistingConfig() {
        configHydrationJob?.cancel()
        configHydrationJob = scope.launch {
            val token = integrationSecretStore.get(IntegrationSecretKey.PANDA_TOKEN) ?: return@launch

            try {
                // First read with the manifest token to discover config_id (may be
                // missing from the local addon row on a fresh restore).
                val manifestRecord = pandaClient.getConfig(token)

                var pandaAddon = runCatching {
                    addonRepo.getInstalledAddons().firstOrNull { a ->
                        a.manifest.id == "com.torve.panda" ||
                            a.manifestUrl.contains("panda.torve.app")
                    }
                }.getOrNull()

                // Self-heal: the local addon row may have been GC'd by
                // AddonSyncService when the server's `/me/addons` list
                // didn't include Panda (push race, or older client that
                // never pushed the addon row). The Panda config itself is
                // still valid server-side — getConfig(token) just succeeded
                // — so re-install the addon under its canonical URL instead
                // of nuking the user's setup. Without this, every restart
                // after a sync GC looked like a fresh-install wizard.
                if (pandaAddon == null) {
                    val rebuiltManifestUrl = "${com.torve.data.panda.PandaApiClient.BASE_URL}/u/$token/manifest.json"
                    runCatching {
                        val installed = addonRepo.installAddon(rebuiltManifestUrl)
                        scope.launch(ioDispatcher) {
                            addonSyncService.onAddonInstalled(installed)
                        }
                        pandaAddon = installed
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig re-installed missing Panda addon" }
                    }.onFailure { err ->
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig re-install failed: ${err::class.simpleName} ${DiagnosticsRedactor.redact(err.message)}" }
                    }
                }

                val resolvedConfigId = pandaAddon?.configId ?: manifestRecord.configId
                if (!resolvedConfigId.isNullOrBlank() && pandaAddon?.configId.isNullOrBlank() && pandaAddon != null) {
                    runCatching { addonRepo.setAddonConfigId(pandaAddon!!.manifestUrl, resolvedConfigId) }
                }

                // Prefer the Torve JWT for the un-redacted read — Panda
                // binds configs to Torve accounts (commit 972fa4a), so
                // any owner can read the full config without needing a
                // management_token. Fall back to a cached management
                // token if the JWT call fails (e.g. legacy rows where
                // owner_torve_user_id wasn't backfilled).
                val torveToken = accountSessionCoordinator.getTorveAccessToken()
                val managementToken = if (!resolvedConfigId.isNullOrBlank()) {
                    integrationSecretStore.get(
                        IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                        subKey = resolvedConfigId,
                    )
                } else null

                torveVerboseLog { "TORVE PANDA | checkExistingConfig hasConfigId=${!resolvedConfigId.isNullOrBlank()} hasAuth=${!torveToken.isNullOrBlank()} hasManagementCredential=${!managementToken.isNullOrBlank()}" }
                val record = if (!resolvedConfigId.isNullOrBlank() && !torveToken.isNullOrBlank()) {
                    val viaJwt = runCatching {
                        pandaClient.getConfigAsManager(resolvedConfigId, torveToken)
                    }
                    if (viaJwt.isSuccess) {
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig hydrated via account auth" }
                    } else {
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig account-auth path failed: ${DiagnosticsRedactor.redact(viaJwt.exceptionOrNull()?.message)}" }
                    }
                    viaJwt.getOrNull()
                        ?: managementToken?.takeIf { it.isNotBlank() }?.let { mgmt ->
                            val viaMgmt = runCatching {
                                pandaClient.getConfigAsManager(resolvedConfigId, mgmt)
                            }
                            if (viaMgmt.isSuccess) {
                                torveVerboseLog { "TORVE PANDA | checkExistingConfig hydrated via management credential" }
                            } else {
                                torveVerboseLog { "TORVE PANDA | checkExistingConfig management credential path failed: ${DiagnosticsRedactor.redact(viaMgmt.exceptionOrNull()?.message)}" }
                            }
                            viaMgmt.getOrNull()
                        }
                        ?: run {
                            torveVerboseLog { "TORVE PANDA | checkExistingConfig falling back to manifest credential read" }
                            manifestRecord
                        }
                } else if (!resolvedConfigId.isNullOrBlank() && !managementToken.isNullOrBlank()) {
                    val viaMgmt = runCatching {
                        pandaClient.getConfigAsManager(resolvedConfigId, managementToken)
                    }
                    if (viaMgmt.isSuccess) {
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig hydrated via management credential without account auth" }
                    } else {
                        torveVerboseLog { "TORVE PANDA | checkExistingConfig management credential path failed: ${DiagnosticsRedactor.redact(viaMgmt.exceptionOrNull()?.message)}; falling back to manifest read" }
                    }
                    viaMgmt.getOrNull() ?: manifestRecord
                } else {
                    torveVerboseLog { "TORVE PANDA | checkExistingConfig no account auth or management credential; manifest credential read only" }
                    manifestRecord
                }
                val config = record.config ?: return@launch

                var attempts = 0
                while (_state.value.providersLoading && attempts < 20) {
                    delay(250)
                    attempts++
                }

                val primarySavedProviderId = if (config.debridService != "none") {
                    config.debridService
                } else {
                    config.debridConnections.firstOrNull { it.provider != "none" }?.provider ?: "none"
                }
                val matchedProvider = _state.value.providers.find { it.id == primarySavedProviderId }
                    ?: if (primarySavedProviderId == "none") noDebridProvider else null

                val hasMgmt = !managementToken.isNullOrBlank()
                // Recovery flow retired (Panda commit 972fa4a, 2026-04-27):
                // configs now bind to the Torve account, so a missing
                // management_token is no longer an error condition. The
                // edit path authenticates via the Torve JWT plus
                // X-Panda-Config-Id — see PandaApiClient.

                // Redacted-value bookkeeping. Panda always returns
                // credential fields as `[redacted]` on read by design.
                // We layer in a device-local cache (written on save in
                // [saveConfigAndInstall]) so:
                //   1. The catalog pages that need raw apiKeys (Adult /
                //      Sports / Movies-via-Usenet → direct Newznab calls)
                //      keep working across restarts.
                //   2. The setup screen shows a clear "Saved on server"
                //      badge for fields where neither the server nor the
                //      local cache has a usable value, so users don't
                //      think the secret was wiped.
                //   3. Edit-mode save skips blank credentials in the
                //      patch (preserving server values).
                // Pull plaintext secrets from Panda's owner-only reveal endpoint.
                // Panda commit 6ed7af5 (2026-04-28) added /api/v1/configs/me/secrets,
                // which returns unredacted credentials when called with the
                // owner's Torve JWT. This closes the cross-device gap: any
                // signed-in machine now hydrates the real keys, no per-device
                // re-entry needed. Falls back gracefully (null) if the user
                // is signed out, the JWT is for a different owner, or the
                // endpoint is rate-limited / disabled — older code paths
                // (device-local cache → "Saved on server" badge) take over.
                val secrets: PandaConfigSecrets? = if (
                    !resolvedConfigId.isNullOrBlank() && !torveToken.isNullOrBlank()
                ) {
                    runCatching { pandaClient.getConfigSecrets(resolvedConfigId, torveToken) }
                        .also { result ->
                            if (result.isSuccess) {
                                val s = result.getOrNull()
                                torveVerboseLog {
                                    "TORVE PANDA | getConfigSecrets ok indexers=${s?.nzbIndexers?.size ?: 0} hasDebridCredential=${s?.debridApiKey?.isNotBlank()} hasTorBoxCredential=${s?.downloadClientApiKey?.isNotBlank()} hasUsenetPassword=${s?.usenetPassword?.isNotBlank()}"
                                }
                            } else {
                                torveVerboseLog { "TORVE PANDA | getConfigSecrets failed: ${DiagnosticsRedactor.redact(result.exceptionOrNull()?.message)}" }
                            }
                        }
                        .getOrNull()
                } else null

                fun secret(field: String?): String? = field?.takeIf { it.isNotBlank() }

                val secretsOnServer = mutableSetOf<String>()
                fun cleanSecret(raw: String, key: String, localFallback: String? = null): String {
                    if (!isRedactedPlaceholder(raw)) return raw
                    // Server returned redacted → try the device-local
                    // cache before flagging as "server-only".
                    if (!localFallback.isNullOrBlank()) return localFallback
                    secretsOnServer += key
                    return ""
                }
                val cleanDebridApiKey = cleanSecret(
                    config.debridApiKey,
                    "debrid_api_key",
                    secret(secrets?.debridApiKey),
                )
                val localDebridApiKeys = readStoredDebridApiKeys()
                val secretDebridApiKeys = secrets?.debridConnections.orEmpty()
                    .mapNotNull { row -> cleanCredential(row.apiKey)?.let { row.provider to it } }
                    .toMap()
                val cleanedDebridApiKeys = linkedMapOf<String, String>().apply {
                    putAll(localDebridApiKeys)
                    config.debridConnections.forEach { row ->
                        val key = cleanSecret(
                            row.apiKey,
                            "debrid_api_key_${row.provider}",
                            secretDebridApiKeys[row.provider] ?: localDebridApiKeys[row.provider],
                        )
                        cleanCredential(key)?.let { put(row.provider, it) }
                    }
                    if (config.debridService != "none") {
                        cleanCredential(cleanDebridApiKey)?.let { put(config.debridService, it) }
                    }
                    putAll(secretDebridApiKeys)
                }
                val cachedUsenetPassword = if (config.usenetProvider != "none") {
                    integrationSecretStore.get(
                        IntegrationSecretKey.PANDA_USENET_PASSWORD,
                        subKey = config.usenetProvider,
                    )
                } else null
                val cleanUsenetPassword = cleanSecret(
                    config.usenetPassword,
                    "usenet_password",
                    secret(secrets?.usenetPassword) ?: cachedUsenetPassword,
                )
                val cachedDownloadClientPassword = if (config.downloadClient != "none") {
                    integrationSecretStore.get(
                        IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD,
                        subKey = config.downloadClient,
                    )
                } else null
                val cleanDownloadClientPassword = cleanSecret(
                    config.downloadClientPassword,
                    "download_client_password",
                    secret(secrets?.downloadClientPassword) ?: cachedDownloadClientPassword,
                )
                val cachedDownloadClientApiKey = if (config.downloadClient != "none") {
                    cachedDownloadClientApiKey(config.downloadClient)
                } else null
                val cleanDownloadClientApiKey = cleanSecret(
                    config.downloadClientApiKey,
                    "download_client_api_key",
                    secret(secrets?.downloadClientApiKey) ?: cachedDownloadClientApiKey,
                )
                val cachedLegacyIndexerApiKey = if (config.nzbIndexer != "none") {
                    integrationSecretStore.get(
                        IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                        subKey = "${config.nzbIndexer}|${config.nzbIndexerUrl}",
                    )
                } else null
                val cleanLegacyIndexerApiKey = cleanSecret(
                    config.nzbIndexerApiKey,
                    "indexer_api_key_legacy",
                    secret(secrets?.nzbIndexerApiKey) ?: cachedLegacyIndexerApiKey,
                )
                val cleanedIndexerRows = config.nzbIndexers.mapIndexed { idx, row ->
                    val secretFromServer = secrets?.nzbIndexers
                        ?.firstOrNull { it.type == row.type && it.url == row.url }
                        ?.apiKey
                        ?.takeIf { it.isNotBlank() }
                    val cachedRowApiKey = if (row.type != "none") {
                        integrationSecretStore.get(
                            IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                            subKey = "${row.type}|${row.url}",
                        )
                    } else null
                    row.copy(
                        apiKey = cleanSecret(
                            row.apiKey,
                            "indexer_api_key_$idx",
                            secretFromServer ?: cachedRowApiKey,
                        ),
                    )
                }

                // Mirror the freshly-fetched plaintext secrets into the device's
                // secret store so the next offline / signed-out hydrate still
                // reaches them. Idempotent — same put() shape as saveConfigAndInstall.
                if (secrets != null) {
                    secret(secrets.debridApiKey)?.let { key ->
                        val dtype = toDebridServiceType(config.debridService)
                        if (dtype != null) {
                            integrationSecretStore.put(debridSecretKey(dtype), key)
                            if (dtype == DebridServiceType.TORBOX) {
                                integrationSecretStore.syncTorBoxCredentialPair(key)
                            }
                        }
                    }
                    secrets.debridConnections.forEach { row ->
                        val key = cleanCredential(row.apiKey) ?: return@forEach
                        val dtype = toDebridServiceType(row.provider) ?: return@forEach
                        integrationSecretStore.put(debridSecretKey(dtype), key)
                        if (dtype == DebridServiceType.TORBOX) {
                            integrationSecretStore.syncTorBoxCredentialPair(key)
                        }
                    }
                    secret(secrets.usenetPassword)?.let { v ->
                        if (config.usenetProvider != "none") {
                            integrationSecretStore.put(
                                key = IntegrationSecretKey.PANDA_USENET_PASSWORD,
                                value = v,
                                subKey = config.usenetProvider,
                            )
                        }
                    }
                    secret(secrets.downloadClientApiKey)?.let { v ->
                        if (config.downloadClient != "none") {
                            integrationSecretStore.put(
                                key = IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                                value = v,
                                subKey = config.downloadClient,
                            )
                            if (config.downloadClient == "torbox") {
                                integrationSecretStore.syncTorBoxCredentialPair(v)
                            }
                        }
                    }
                    secret(secrets.downloadClientPassword)?.let { v ->
                        if (config.downloadClient != "none") {
                            integrationSecretStore.put(
                                key = IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD,
                                value = v,
                                subKey = config.downloadClient,
                            )
                        }
                    }
                    secrets.nzbIndexers.forEach { row ->
                        if (row.type != "none" && row.apiKey.isNotBlank()) {
                            integrationSecretStore.put(
                                key = IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                                value = row.apiKey,
                                subKey = "${row.type}|${row.url}",
                            )
                            integrationSecretStore.put(
                                key = IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                                value = row.apiKey,
                                subKey = row.type,
                            )
                        }
                    }
                }

                prefsRepo.setString("panda_download_client", config.downloadClient)
                prefsRepo.setString("panda_usenet_provider", if (config.enableUsenet) config.usenetProvider else "none")
                prefsRepo.setString(
                    "panda_indexer_subkeys",
                    cleanedIndexerRows.filter { it.type != "none" && it.url.isNotBlank() }
                        .joinToString("\n") { "${it.type}|${it.url}" },
                )

                torveVerboseLog { "TORVE PANDA | checkExistingConfig hydrated serverHasCredentials=$secretsOnServer enableUsenet=${config.enableUsenet} usenetProvider=${config.usenetProvider} indexerCount=${cleanedIndexerRows.size} cachedIndexerCredentialCount=${cleanedIndexerRows.count { it.apiKey.isNotBlank() }}" }

                _state.update {
                    it.copy(
                        isEditMode = true,
                        pandaToken = token,
                        configId = resolvedConfigId,
                        hasManagementToken = hasMgmt,
                        editRequiresRecovery = false,
                        setupMode = if (config.debridService == "none") {
                            PandaSetupMode.USENET_ONLY
                        } else {
                            PandaSetupMode.DEBRID
                        },
                        selectedProvider = matchedProvider,
                        debridApiKey = cleanedDebridApiKeys[primarySavedProviderId] ?: cleanDebridApiKey,
                        apiKeyInput = cleanedDebridApiKeys[primarySavedProviderId] ?: cleanDebridApiKey,
                        debridApiKeys = cleanedDebridApiKeys,
                        // Treat "no debrid" saved configs as "auth satisfied" so the save
                        // button in edit mode stays enabled. A redacted debrid_api_key
                        // (which we cleared above) still counts as "auth connected" —
                        // the server has a key, the user just can't read it.
                        authConnected = config.debridService == "none" ||
                            cleanedDebridApiKeys.isNotEmpty() ||
                            cleanDebridApiKey.isNotBlank() ||
                            "debrid_api_key" in secretsOnServer ||
                            secretsOnServer.any { key -> key.startsWith("debrid_api_key_") },
                        enabledSources = config.enabledProviders.toSet().ifEmpty { defaultEnabledSources },
                        maxQuality = config.maxQuality,
                        qualityProfile = config.qualityProfile,
                        // Prefer the array when present; fall back to the legacy scalar so
                        // configs saved before multi-select shipped still pre-select correctly.
                        releaseLanguages = config.releaseLanguages.ifEmpty { listOf(config.releaseLanguage) },
                        releaseLanguage = config.releaseLanguage,
                        enableUsenet = config.enableUsenet,
                        usenetProvider = config.usenetProvider,
                        usenetHost = config.usenetHost,
                        usenetPort = config.usenetPort,
                        usenetUsername = config.usenetUsername,
                        usenetPassword = cleanUsenetPassword,
                        usenetSSL = config.usenetSSL,
                        usenetConnections = config.usenetConnections,
                        nzbIndexer = config.nzbIndexer,
                        nzbIndexerUrl = config.nzbIndexerUrl,
                        nzbIndexerApiKey = cleanLegacyIndexerApiKey,
                        // Prefer the array when present; fall back to hydrating a
                        // single-row list from the legacy scalars so configs saved
                        // before multi-indexer shipped still pre-populate correctly.
                        nzbIndexers = cleanedIndexerRows.ifEmpty {
                            if (config.nzbIndexer != "none" &&
                                (cleanLegacyIndexerApiKey.isNotBlank() ||
                                    "indexer_api_key_legacy" in secretsOnServer)
                            ) {
                                listOf(
                                    com.torve.data.panda.NzbIndexerRow(
                                        type = config.nzbIndexer,
                                        url = config.nzbIndexerUrl,
                                        apiKey = cleanLegacyIndexerApiKey,
                                    ),
                                )
                            } else {
                                listOf(com.torve.data.panda.NzbIndexerRow())
                            }
                        },
                        downloadClient = config.downloadClient,
                        downloadClientUrl = config.downloadClientUrl,
                        downloadClientUsername = config.downloadClientUsername,
                        downloadClientPassword = cleanDownloadClientPassword,
                        downloadClientApiKey = cleanDownloadClientApiKey,
                        easynewsPreferNzb = config.easynewsPreferNzb,
                        serverHasSecrets = secretsOnServer.toSet(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.torve.data.panda.PandaApiException) {
                // Only drop the token if the server says the config is definitely gone.
                // Transient 5xx / auth / network errors must not wipe cross-device sync state.
                if (e.httpStatus == 404 || e.httpStatus == 410) {
                    integrationSecretStore.remove(IntegrationSecretKey.PANDA_TOKEN)
                }
            } catch (_: Exception) {
                // Network/parse errors: leave the token in place so a later retry succeeds.
            }
        }
    }

    fun deleteConfig() {
        scope.launch {
            val configId = _state.value.configId
            if (!configId.isNullOrBlank()) {
                val mgmt = integrationSecretStore.get(
                    IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                    subKey = configId,
                )
                if (!mgmt.isNullOrBlank()) {
                    runCatching { pandaClient.deleteConfig(configId, mgmt) }
                }
                // Remove the per-config management token regardless of server
                // DELETE outcome — keeping it locally after tear-down leaks a
                // secret that can't address any live config.
                integrationSecretStore.remove(
                    IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                    subKey = configId,
                )
            }
            integrationSecretStore.remove(IntegrationSecretKey.PANDA_TOKEN)
            _state.update {
                PandaSetupUiState(
                    providers = it.providers,
                    currentStep = PandaSetupStep.SETUP_TYPE,
                    schema = it.schema,
                    sourceProviders = it.sourceProviders,
                    enabledSources = defaultEnabledSources,
                )
            }
        }
    }

    /**
     * Recovery flow: user pastes an admin-issued management token for the
     * current config. Validates by hitting GET with the token; on success,
     * persists device-local and clears the [editRequiresRecovery] gate.
     */
    fun recoverManagementToken(adminIssuedToken: String) {
        scope.launch {
            val configId = _state.value.configId
            val trimmed = adminIssuedToken.trim()
            if (configId.isNullOrBlank()) {
                _state.update {
                    it.copy(recoveryError = "No Panda config loaded. Reopen Manage Panda and try again.")
                }
                return@launch
            }
            if (trimmed.isEmpty()) {
                _state.update { it.copy(recoveryError = "Paste your management token to continue.") }
                return@launch
            }
            _state.update { it.copy(recoveryInProgress = true, recoveryError = null) }
            try {
                pandaClient.getConfigAsManager(configId = configId, bearerToken = trimmed)
                integrationSecretStore.put(
                    key = IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                    value = trimmed,
                    subKey = configId,
                )
                integrationSecretStore.setStorageMode(
                    IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                    com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY,
                )
                // Backfill server-side so the next device this user signs
                // in on doesn't have to do the same recovery dance. The
                // backend merges the new `management_token` key into the
                // existing PANDA_TOKEN integration row (preserving the
                // manifest token already there).
                val patchOutcome = accountSessionCoordinator.patchIntegrationCredentials(
                    integrationType = "PANDA_TOKEN",
                    credentials = mapOf("management_token" to trimmed),
                )
                when (patchOutcome) {
                    com.torve.data.account.PatchCredentialsOutcome.Ok -> {
                        torveVerboseLog { "[PandaRecover] Backfilled management credential server-side" }
                    }
                    com.torve.data.account.PatchCredentialsOutcome.RowMissing -> {
                        // No PANDA_TOKEN row yet — fall back to a full PUT
                        // so this device contributes both keys cleanly.
                        torveVerboseLog { "[PandaRecover] PATCH 404; fallback PUT" }
                        accountSessionCoordinator.saveIntegrationToBackend(
                            integrationType = "PANDA_TOKEN",
                            credentials = mapOf(
                                "management_token" to trimmed,
                                "config_id" to configId,
                            ),
                            displayIdentifier = "Panda",
                        )
                    }
                    com.torve.data.account.PatchCredentialsOutcome.PremiumRequired -> {
                        // Local recovery succeeded; just couldn't sync.
                        // Don't fail the whole flow — surface a hint.
                        _state.update {
                            it.copy(
                                managementTokenNotice = "Recovered locally. Sign in and sync this token through your account when available.",
                            )
                        }
                    }
                    is com.torve.data.account.PatchCredentialsOutcome.Error -> {
                        torveVerboseLog { "[PandaRecover] PATCH error: ${DiagnosticsRedactor.redact(patchOutcome.message)}" }
                    }
                }
                _state.update {
                    it.copy(
                        recoveryInProgress = false,
                        recoveryError = null,
                        hasManagementToken = true,
                        editRequiresRecovery = false,
                    )
                }
            } catch (e: com.torve.data.panda.PandaApiException) {
                val msg = when (e.httpStatus) {
                    401, 403 -> "That token isn't valid for this Panda config. Double-check it and try again."
                    else -> e.message
                }
                _state.update { it.copy(recoveryInProgress = false, recoveryError = msg) }
            } catch (e: Exception) {
                _state.update { it.copy(recoveryInProgress = false, recoveryError = e.message ?: "Recovery failed.") }
            }
        }
    }

    /**
     * Rotate the management token. Caller MUST already have a stored one —
     * server rejects rotate-management with manifest-token bearer for any
     * config that's already had its token minted.
     */
    fun rotateManagementToken() {
        scope.launch {
            val configId = _state.value.configId
            if (configId.isNullOrBlank()) return@launch
            val current = integrationSecretStore.get(
                IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                subKey = configId,
            )
            if (current.isNullOrBlank()) {
                _state.update { it.copy(rotateError = "No management token on this device. Use the recovery flow first.") }
                return@launch
            }
            _state.update { it.copy(rotateInProgress = true, rotateError = null) }
            try {
                val response = pandaClient.rotateManagement(configId = configId, bearerToken = current)
                val minted = response.managementToken
                if (minted.isBlank()) {
                    _state.update { it.copy(rotateInProgress = false, rotateError = "Server returned an empty token.") }
                    return@launch
                }
                integrationSecretStore.put(
                    key = IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                    value = minted,
                    subKey = configId,
                )
                _state.update {
                    it.copy(
                        rotateInProgress = false,
                        rotateError = null,
                        hasManagementToken = true,
                        pendingManagementTokenDisplay = minted,
                        managementTokenNotice = response.managementTokenNotice,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(rotateInProgress = false, rotateError = e.message ?: "Rotate failed.") }
            }
        }
    }

    /**
     * Rotate the manifest (panda) token. Old manifest URL 404s after success;
     * we swap the installed addon row to point at the new URL.
     */
    fun rotateManifestUrl() {
        scope.launch {
            val configId = _state.value.configId
            val currentManifest = _state.value.manifestUrl
                ?: integrationSecretStore.get(IntegrationSecretKey.PANDA_TOKEN)?.let {
                    // Fall back to reconstructing the old URL if the state-level
                    // manifestUrl isn't hydrated — the install row drives removal.
                    null
                }
            if (configId.isNullOrBlank()) return@launch
            val mgmt = integrationSecretStore.get(
                IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                subKey = configId,
            )
            if (mgmt.isNullOrBlank()) {
                _state.update { it.copy(rotateError = "No management token on this device. Use the recovery flow first.") }
                return@launch
            }
            _state.update { it.copy(rotateInProgress = true, rotateError = null) }
            try {
                val response = pandaClient.rotateManifest(configId = configId, bearerToken = mgmt)
                val newToken = response.pandaToken
                val newUrl = response.manifestUrl
                if (newToken.isNullOrBlank() || newUrl.isNullOrBlank()) {
                    _state.update { it.copy(rotateInProgress = false, rotateError = "Rotate response missing fields.") }
                    return@launch
                }

                // Swap stored panda token + re-install the addon under the new
                // URL so existing manifest URLs (now 404'ing) are replaced.
                integrationSecretStore.put(IntegrationSecretKey.PANDA_TOKEN, newToken)
                runCatching {
                    addonRepo.getInstalledAddons()
                        .filter {
                            it.manifest.id == "com.torve.panda" ||
                                it.manifestUrl.contains("panda.torve.app")
                        }
                        .forEach { stale -> runCatching { addonRepo.removeAddon(stale.manifestUrl) } }
                }
                val installed = runCatching { addonRepo.installAddon(newUrl) }.getOrNull()
                if (installed != null) {
                    addonRepo.setAddonConfigId(installed.manifestUrl, configId)
                }

                _state.update {
                    it.copy(
                        rotateInProgress = false,
                        rotateError = null,
                        pandaToken = newToken,
                        manifestUrl = newUrl,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(rotateInProgress = false, rotateError = e.message ?: "Rotate failed.") }
            }
        }
    }

    /** Dismiss the "shown once" management-token display card. */
    fun acknowledgeManagementTokenDisplay() {
        _state.update {
            it.copy(
                pendingManagementTokenDisplay = null,
                managementTokenNotice = null,
            )
        }
    }

    fun clearError() {
        _state.update {
            it.copy(
                error = null,
                saveError = null,
                recoveryError = null,
                rotateError = null,
            )
        }
    }
}
