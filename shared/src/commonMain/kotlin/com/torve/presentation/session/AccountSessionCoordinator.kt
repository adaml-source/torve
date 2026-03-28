package com.torve.presentation.session

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountSettingsRefreshResult
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.account.isXtreamPlaylist
import com.torve.data.addon.AddonSyncService
import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.DeviceListDto
import com.torve.data.device.ManagedDeviceDto
import com.torve.data.subscription.SubscriptionEntitlementCacheKeys
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.platform.torveVerboseLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class AccountSessionState(
    val isBootstrapping: Boolean = false,
    val deviceLimitReached: Boolean = false,
    val deviceLimitMessage: String? = null,
    val activeDevices: List<ManagedDeviceDto> = emptyList(),
    val lastError: String? = null,
)

data class AccountSessionBootstrapResult(
    val isReady: Boolean,
    val deviceLimitReached: Boolean = false,
    val activeDevices: List<ManagedDeviceDto> = emptyList(),
    val error: String? = null,
    val accessState: AccessStateDto? = null,
    val settingsResult: AccountSettingsRefreshResult? = null,
)

// ── Post-login restore progress (user-visible) ─────────────

enum class RestorePhase {
    IDLE,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
}

data class RestoreProgress(
    val phase: RestorePhase = RestorePhase.IDLE,
    val message: String = "",
    val totalPlaylists: Int = 0,
    val restoredPlaylists: Int = 0,
    val currentPlaylistName: String? = null,
    val errorCount: Int = 0,
    val integrationsRestored: Int = 0,
    /** True while heavy import is running — UI should show blocking overlay. */
    val isImporting: Boolean = false,
)

class AccountSessionCoordinator(
    private val authClient: AuthClient,
    private val deviceApi: DeviceApi,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val integrationSecretStore: com.torve.domain.integrations.IntegrationSecretStore,
    private val accountSettingsApi: AccountSettingsApi,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val prefsRepo: com.torve.domain.repository.PreferencesRepository,
    private val channelRepo: com.torve.domain.repository.ChannelRepository,
    private val addonSyncService: AddonSyncService,
) {
    companion object {
        private const val DEFAULT_MAX_ACTIVE_DEVICES = 5
    }

    // Use IO dispatcher for background restore — heavy network + disk work
    // must not compete with Compose rendering on the Default (CPU) pool.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AccountSessionState())
    val state: StateFlow<AccountSessionState> = _state.asStateFlow()

    /** Observable restore progress — UI can show non-blocking status. */
    private val _restoreProgress = MutableStateFlow(RestoreProgress())
    val restoreProgress: StateFlow<RestoreProgress> = _restoreProgress.asStateFlow()

    /**
     * Restore an existing session on cold start.
     * Does NOT re-import playlists/integrations if local data already exists.
     * Full restore only runs after fresh sign-in (when local data was cleared by sign-out).
     */
    suspend fun restoreSession(): Boolean {
        torveVerboseLog { "AUTH_BOOTSTRAP restore_session_start" }
        val restored = authClient.restoreSession()
        if (!restored) {
            torveVerboseLog { "AUTH_BOOTSTRAP restore_session_result restored=false" }
            accountSettingsRepository.clearSessionState()
            return false
        }
        // On cold start with existing session: lightweight bootstrap only.
        // Playlists/channels are already in local SQLite from the last session.
        val result = bootstrap(forceSettingsRefresh = false)
        torveVerboseLog {
            "AUTH_BOOTSTRAP restore_session_result restored=true isReady=${result.isReady} deviceLimitReached=${result.deviceLimitReached} error=${result.error}"
        }
        return result.isReady
    }

    /**
     * Fast sign-in: register device only, return immediately, then kick off
     * heavy restore (integrations, playlists, settings) in the background.
     */
    suspend fun bootstrapAfterSignIn(): AccountSessionBootstrapResult {
        return bootstrap(forceSettingsRefresh = true)
    }

    suspend fun onAppForeground(): AccountSessionBootstrapResult {
        return bootstrap(forceSettingsRefresh = false)
    }

    suspend fun onSettingsOpened(): AccountSessionBootstrapResult {
        return bootstrap(forceSettingsRefresh = false, forceStaleRefresh = true)
    }

    /**
     * Full teardown of session state.
     */
    suspend fun signOut() {
        torveVerboseLog { "[SignOut] Playlist/credential cleanup started" }
        integrationSecretStore.clearAllSecrets()
        torveVerboseLog { "[SignOut] Encrypted secret store cleared" }
        for (key in integrationSecretStore.legacyPreferenceSecretKeys) {
            prefsRepo.remove(key)
        }
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
        torveVerboseLog { "[SignOut] Legacy preference secrets cleared" }
        runCatching { channelRepo.clearAll() }
        torveVerboseLog { "[SignOut] SQLite playlists/channels/favorites/recents cleared" }
        runCatching { addonSyncService.clearSyncStateOnSignOut() }
        torveVerboseLog { "[SignOut] Addon sync metadata cleared" }
        accountSettingsRepository.clearSessionState()
        _state.value = AccountSessionState()
        _restoreProgress.value = RestoreProgress()
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
        torveVerboseLog { "[SignOut] Playlist/credential cleanup finished" }
    }

    /**
     * Save an integration credential to the backend (ACCOUNT mode only).
     */
    suspend fun saveIntegrationToBackend(
        integrationType: String,
        credentials: Map<String, String>,
        displayIdentifier: String? = null,
        config: Map<String, String> = emptyMap(),
    ): Boolean {
        torveVerboseLog { "[IntegrationSync] Saving $integrationType to backend (label=$displayIdentifier)" }
        val token = authClient.getValidAccessToken()
        if (token == null) {
            torveVerboseLog { "[IntegrationSync] FAILED: no valid access token" }
            return false
        }
        val ok = accountSettingsApi.saveIntegration(
            accessToken = token,
            integrationType = integrationType,
            request = com.torve.data.account.SaveIntegrationRequest(
                integrationType = integrationType,
                storageMode = "account",
                credentials = credentials,
                displayIdentifier = displayIdentifier,
                config = config,
            ),
        )
        torveVerboseLog { "[IntegrationSync] $integrationType → ${if (ok) "OK" else "FAILED"}" }
        return ok
    }

    suspend fun savePlaylistToBackend(
        playlistId: String,
        name: String,
        url: String? = null,
        epgUrl: String? = null,
        playlistType: String = "m3u",
        server: String? = null,
        username: String? = null,
        password: String? = null,
    ): Boolean {
        val token = authClient.getValidAccessToken() ?: return false
        return accountSettingsApi.savePlaylist(
            accessToken = token,
            playlistId = playlistId,
            request = com.torve.data.account.SavePlaylistRequest(
                playlistId = playlistId,
                name = name,
                url = url,
                epgUrl = epgUrl,
                playlistType = playlistType,
                server = server,
                username = username,
                password = password,
            ),
        )
    }

    suspend fun deletePlaylistFromBackend(playlistId: String): Boolean {
        val token = authClient.getValidAccessToken() ?: return false
        return accountSettingsApi.deletePlaylist(token, playlistId)
    }

    fun clearLastError() {
        _state.update { it.copy(lastError = null, deviceLimitMessage = null) }
    }

    /** Dismiss the restore progress banner. */
    fun dismissRestoreProgress() {
        _restoreProgress.value = RestoreProgress()
    }

    // ── Bootstrap: fast critical path + deferred background restore ──

    private suspend fun bootstrap(
        forceSettingsRefresh: Boolean,
        forceStaleRefresh: Boolean = false,
    ): AccountSessionBootstrapResult {
        val token = authClient.getValidAccessToken()
            ?: return AccountSessionBootstrapResult(isReady = false)
        torveVerboseLog {
            "AUTH_BOOTSTRAP bootstrap_start forceSettingsRefresh=$forceSettingsRefresh forceStaleRefresh=$forceStaleRefresh"
        }
        _state.update { it.copy(isBootstrapping = true, lastError = null) }

        return runCatching {
            // ── Phase A: Critical path (fast) ─────────────────────
            torveVerboseLog { "[Login] Phase A: registering device..." }
            val registrationError = runCatching {
                deviceApi.registerDevice(token, authClient.currentDeviceRegistration())
            }.exceptionOrNull()?.message

            torveVerboseLog { "[Login] Phase A: device registered, entering app" }
            _state.update { it.copy(isBootstrapping = false) }

            // ── Phase B: Background restore (deferred) ────────────
            if (forceSettingsRefresh) {
                torveVerboseLog { "[Login] Phase B: launching background restore (deferred 5s for home screen)" }
                backgroundScope.launch {
                    runCatching { addonSyncService.syncAfterSignIn() }
                }
                backgroundScope.launch {
                    // Wait for home screen to fully load posters before starting heavy
                    // network/import work. Prevents ANR and incomplete poster loading
                    // from restore competing with Coil image decoding.
                    kotlinx.coroutines.delay(5000)
                    backgroundRestore(token)
                }
            } else if (forceStaleRefresh) {
                backgroundScope.launch {
                    runCatching { accountSettingsRepository.refreshIfStale(force = true) }
                }
            }
            backgroundScope.launch {
                runCatching {
                    addonSyncService.syncIfStale(
                        reason = if (forceStaleRefresh) "settings_opened" else "foreground",
                        force = false,
                    )
                }
            }

            // Fetch devices in background too (not critical for app entry)
            val deviceList = runCatching { deviceApi.getDevices(token) }
                .getOrElse { DeviceListDto(emptyList(), 0, DEFAULT_MAX_ACTIVE_DEVICES, 0) }
            val maxActiveDevices = deviceList.max_active.takeIf { it > 0 } ?: DEFAULT_MAX_ACTIVE_DEVICES
            val deviceLimitReached = deviceList.active_count >= maxActiveDevices

            _state.update {
                it.copy(
                    deviceLimitReached = deviceLimitReached,
                    deviceLimitMessage = if (deviceLimitReached) {
                        "You have reached your 5-device limit. Remove an existing device to continue."
                    } else null,
                    activeDevices = deviceList.devices,
                    lastError = registrationError,
                )
            }

            AccountSessionBootstrapResult(
                isReady = !deviceLimitReached && registrationError == null,
                deviceLimitReached = deviceLimitReached,
                activeDevices = deviceList.devices,
                error = registrationError,
            ).also { result ->
                torveVerboseLog {
                    "AUTH_BOOTSTRAP bootstrap_result isReady=${result.isReady} deviceLimitReached=${result.deviceLimitReached} activeDevices=${result.activeDevices.size} error=${result.error}"
                }
            }
        }.getOrElse { error ->
            _state.update {
                it.copy(
                    isBootstrapping = false,
                    lastError = error.message ?: "Failed to refresh device session.",
                )
            }
            AccountSessionBootstrapResult(
                isReady = false,
                error = error.message ?: "Failed to refresh device session.",
            ).also { result ->
                torveVerboseLog {
                    "AUTH_BOOTSTRAP bootstrap_failure error=${result.error}"
                }
            }
        }
    }

    // ── Background restore pipeline ─────────────────────────────

    private suspend fun backgroundRestore(token: String) {
        // Check if local data already exists — skip heavy restore if so.
        // This prevents re-importing playlists on every app restart.
        val localPlaylists = runCatching { channelRepo.getPlaylists() }.getOrElse { emptyList() }
        val hasLocalData = localPlaylists.isNotEmpty()
        if (hasLocalData) {
            torveVerboseLog { "[Restore] Local data exists (${localPlaylists.size} playlists) — skipping heavy restore" }
            // Still sync settings (lightweight)
            runCatching { accountSettingsRepository.syncAfterSignIn() }
            settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
            return
        }

        val startMs = Clock.System.now().toEpochMilliseconds()
        _restoreProgress.value = RestoreProgress(
            phase = RestorePhase.RUNNING,
            message = "Restoring your account data…",
            isImporting = true,
        )
        torveVerboseLog { "[Restore] Background restore started (no local data — full restore)" }
        var errors = 0

        // Step 1: Account settings
        _restoreProgress.update { it.copy(message = "Syncing settings…") }
        runCatching {
            accountSettingsRepository.syncAfterSignIn()
            torveVerboseLog { "[Restore] Account settings synced" }
        }.onFailure { e ->
            errors++
            torveVerboseLog { "[Restore] Account settings FAILED: ${e.message}" }
        }

        // Step 2: Integrations
        _restoreProgress.update { it.copy(message = "Restoring integrations…") }
        val integrationsRestored = runCatching {
            restoreIntegrations(token)
        }.getOrElse {
            errors++
            torveVerboseLog { "[Restore] Integrations restore FAILED: ${it.message}" }
            0
        }
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())

        // Step 3: Playlists (heavy — channels import)
        _restoreProgress.update { it.copy(message = "Restoring playlists…") }
        val (playlistsRestored, playlistsFailed) = runCatching {
            restorePlaylists(token)
        }.getOrElse {
            errors++
            torveVerboseLog { "[Restore] Playlist restore FAILED: ${it.message}" }
            0 to 0
        }
        errors += playlistsFailed
        // Notify ChannelsViewModel to reload playlists from DB
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())

        // Done
        val elapsed = (Clock.System.now().toEpochMilliseconds() - startMs) / 1000
        val phase = if (errors > 0) RestorePhase.COMPLETED_WITH_ERRORS else RestorePhase.COMPLETED
        val summary = if (errors > 0) {
            "Restore completed with $errors error(s)"
        } else {
            "Account restored"
        }
        _restoreProgress.value = RestoreProgress(
            phase = phase,
            message = summary,
            integrationsRestored = integrationsRestored,
            totalPlaylists = playlistsRestored + playlistsFailed,
            restoredPlaylists = playlistsRestored,
            errorCount = errors,
            isImporting = false,
        )
        torveVerboseLog { "[Restore] Completed in ${elapsed}s: $integrationsRestored integrations, $playlistsRestored playlists, $errors errors" }
    }

    // ── Integration restore ─────────────────────────────────────

    /** Returns number of integrations restored. */
    private suspend fun restoreIntegrations(token: String): Int {
        val integrations = accountSettingsApi.getIntegrations(token)
        torveVerboseLog { "[IntegrationRestore] Found ${integrations.size} integrations on backend" }
        var restored = 0
        for (integration in integrations) {
            val secretKey = runCatching {
                IntegrationSecretKey.valueOf(integration.integrationType)
            }.getOrNull()
            if (secretKey == null) {
                torveVerboseLog { "[IntegrationRestore] Skipping unknown type: ${integration.integrationType}" }
                continue
            }

            val mode = when (integration.storageMode) {
                "account" -> IntegrationStorageMode.ACCOUNT
                "device_only" -> IntegrationStorageMode.DEVICE_ONLY
                else -> IntegrationStorageMode.DEVICE_ONLY
            }
            integrationSecretStore.setStorageMode(secretKey, mode)
            if (secretKey == IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID) {
                integrationSecretStore.setStorageMode(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, mode)
                integrationSecretStore.setStorageMode(IntegrationSecretKey.DEBRID_RD_CLIENT_ID, mode)
                integrationSecretStore.setStorageMode(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET, mode)
            }

            if (mode == IntegrationStorageMode.ACCOUNT && integration.hasCredentials) {
                torveVerboseLog { "[IntegrationRestore] Fetching credentials for ${integration.integrationType}..." }
                val credsMap = accountSettingsApi.getIntegrationCredentials(
                    accessToken = token,
                    integrationType = integration.integrationType,
                )
                if (credsMap != null && credsMap.isNotEmpty()) {
                    if (secretKey == IntegrationSecretKey.TRAKT_TOKENS) {
                        val accessTok = credsMap["access_token"] ?: ""
                        val refreshTok = credsMap["refresh_token"] ?: ""
                        if (accessTok.isNotBlank()) {
                            val traktTokenStore = com.torve.data.trakt.auth.TraktTokenStore(
                                integrationSecretStore,
                                kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                            )
                            traktTokenStore.write(
                                com.torve.data.trakt.TraktTokens(
                                    accessToken = accessTok,
                                    refreshToken = refreshTok,
                                    expiresIn = 0,
                                    createdAt = 0L,
                                ),
                            )
                            restored++
                            torveVerboseLog { "[IntegrationRestore] TRAKT_TOKENS → restored OK (access+refresh)" }
                        }
                    } else if (secretKey == IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID) {
                        val apiKey = credsMap["api_key"].orEmpty()
                        val refreshToken = credsMap["refresh_token"].orEmpty()
                        val clientId = credsMap["client_id"].orEmpty()
                        val clientSecret = credsMap["client_secret"].orEmpty()
                        if (apiKey.isNotBlank()) {
                            integrationSecretStore.put(secretKey, apiKey)
                            if (refreshToken.isNotBlank()) {
                                integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, refreshToken)
                            }
                            if (clientId.isNotBlank()) {
                                integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_CLIENT_ID, clientId)
                            }
                            if (clientSecret.isNotBlank()) {
                                integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET, clientSecret)
                            }
                            restored++
                            torveVerboseLog {
                                "[IntegrationRestore] ${integration.integrationType} â†’ restored OK (api_key=${apiKey.isNotBlank()} refresh=${refreshToken.isNotBlank()} client=${clientId.isNotBlank()} secret=${clientSecret.isNotBlank()})"
                            }
                        }
                    } else {
                        val value = credsMap.values.firstOrNull()
                        if (!value.isNullOrBlank()) {
                            integrationSecretStore.put(secretKey, value)
                            restored++
                            torveVerboseLog { "[IntegrationRestore] ${integration.integrationType} → restored OK" }
                        }
                    }
                } else {
                    torveVerboseLog { "[IntegrationRestore] ${integration.integrationType} → credentials empty" }
                }
            }
        }
        torveVerboseLog { "[IntegrationRestore] Done: $restored/${integrations.size}" }
        return restored
    }

    // ── Playlist restore ────────────────────────────────────────

    /** Returns (restored, failed) counts. */
    private suspend fun restorePlaylists(token: String): Pair<Int, Int> {
        val remotePlaylists = accountSettingsApi.getPlaylists(token)
        torveVerboseLog { "[PlaylistRestore] Remote playlist count: ${remotePlaylists.size}" }
        _restoreProgress.update { it.copy(totalPlaylists = remotePlaylists.size) }
        var restored = 0
        var failed = 0
        for ((index, remote) in remotePlaylists.withIndex()) {
            val pid = remote.playlistId.ifBlank { remote.id }
            val xtreamPlaylist = remote.isXtreamPlaylist(pid)
            _restoreProgress.update {
                it.copy(
                    message = "Importing playlist ${index + 1} of ${remotePlaylists.size}…",
                    currentPlaylistName = remote.name,
                    restoredPlaylists = restored,
                )
            }
            torveVerboseLog {
                "[PlaylistRestore] Restoring '${remote.name}' (type=${remote.playlistType}, id=$pid, xtream=$xtreamPlaylist, hasServer=${!remote.server.isNullOrBlank()})"
            }
            try {
                if (xtreamPlaylist) {
                    val creds = accountSettingsApi.getPlaylistCredentials(token, pid)
                    val resolvedUsername = creds?.username?.takeIf { it.isNotBlank() } ?: remote.username.orEmpty()
                    val password = creds?.password?.takeIf { it.isNotBlank() }.orEmpty()
                    val server = remote.server?.trim().orEmpty()
                    if (server.isBlank()) {
                        failed++
                        torveVerboseLog { "[PlaylistRestore]   FAILED: missing Xtream server" }
                        continue
                    }
                    if (password.isBlank()) {
                        failed++
                        torveVerboseLog { "[PlaylistRestore]   FAILED: missing Xtream credentials" }
                        continue
                    }
                    torveVerboseLog { "[PlaylistRestore]   Credentials fetched OK" }
                    channelRepo.addXtreamPlaylist(
                        name = remote.name,
                        server = server,
                        username = resolvedUsername,
                        password = password,
                        id = pid,
                    )
                    torveVerboseLog { "[PlaylistRestore]   Xtream import OK" }
                } else if (!remote.url.isNullOrBlank()) {
                    channelRepo.addPlaylist(
                        name = remote.name,
                        url = remote.url,
                        epgUrl = remote.epgUrl,
                        id = pid,
                    )
                    torveVerboseLog { "[PlaylistRestore]   M3U import OK" }
                } else {
                    torveVerboseLog { "[PlaylistRestore]   Skipped — no url or server" }
                }
                restored++
            } catch (e: Exception) {
                failed++
                torveVerboseLog { "[PlaylistRestore]   FAILED: ${e.message}" }
            }
        }
        torveVerboseLog { "[PlaylistRestore] Done: $restored restored, $failed failed" }
        return restored to failed
    }
}
