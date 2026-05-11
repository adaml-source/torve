package com.torve.presentation.session

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountSettingsRefreshResult
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.account.RemotePlaylistDto
import com.torve.data.account.isXtreamPlaylist
import com.torve.data.addon.AddonSyncService
import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.DeviceListDto
import com.torve.data.device.ManagedDeviceDto
import com.torve.data.subscription.SubscriptionEntitlementCacheKeys
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.PlaylistType
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.security.SecureStorage
import com.torve.presentation.settings.SettingsViewModel
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

internal fun String?.isDeviceLimitRegistrationError(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return "device limit" in normalized ||
        "device cap" in normalized ||
        "active device" in normalized ||
        "activation slot" in normalized ||
        "no activation slots" in normalized ||
        "swap limit" in normalized
}

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

private data class PlaylistSyncResult(
    val added: Int = 0,
    val updated: Int = 0,
    val failed: Int = 0,
) {
    val hasChanges: Boolean
        get() = added > 0 || updated > 0
}

class AccountSessionCoordinator(
    private val authClient: AuthClient,
    private val deviceApi: DeviceApi,
    private val database: TorveDatabase,
    private val secureStorage: SecureStorage,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val integrationSecretStore: com.torve.domain.integrations.IntegrationSecretStore,
    private val accountSettingsApi: AccountSettingsApi,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
    private val prefsRepo: com.torve.domain.repository.PreferencesRepository,
    private val channelRepo: com.torve.domain.repository.ChannelRepository,
    private val addonSyncService: AddonSyncService,
    private val watchlistRepo: WatchlistRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val traktSyncRepo: TraktSyncRepository,
    private val deviceRegistrationNotifier: DeviceRegistrationNotifier,
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
        clearLocalAccountData(reason = "sign_out")
    }

    /**
     * Clear every account-scoped local row and secret on this device.
     *
     * This intentionally does not depend on the current auth user. Some
     * callsites receive session-expired/revoked-device events after
     * [AuthClient] already cleared the active user, so using the current
     * user id here would miss the previous account's cached libraries,
     * IPTV rows, preferences, and credentials.
     */
    suspend fun clearLocalAccountData(reason: String) {
        torveVerboseLog { "[SignOut] Local account cleanup started reason=$reason" }
        integrationSecretStore.clearAllSecrets()
        torveVerboseLog { "[SignOut] Encrypted secret store cleared" }
        runCatching { secureStorage.removeByPrefix("xtream_pwd_") }
        torveVerboseLog { "[SignOut] Xtream secure credential prefix cleared" }
        for (key in integrationSecretStore.legacyPreferenceSecretKeys) {
            prefsRepo.remove(key)
        }
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED)
        prefsRepo.remove(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
        torveVerboseLog { "[SignOut] Legacy preference secrets cleared" }
        purgeAccountScopedDatabaseRows()
        torveVerboseLog { "[SignOut] SQLite account rows cleared" }
        runCatching { channelRepo.clearAll() }
        runCatching { watchlistRepo.clear() }
        runCatching { watchProgressRepo.clearAllProgress() }
        runCatching { watchHistoryRepo.clearAll() }
        runCatching { traktSyncRepo.clearLocalData() }
        torveVerboseLog { "[SignOut] Repository caches cleared" }
        runCatching { addonSyncService.clearSyncStateOnSignOut() }
        torveVerboseLog { "[SignOut] Addon sync metadata cleared" }
        accountSettingsRepository.clearSessionState()
        _state.value = AccountSessionState()
        _restoreProgress.value = RestoreProgress()
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
        torveVerboseLog { "[SignOut] Local account cleanup finished reason=$reason" }
    }

    private fun purgeAccountScopedDatabaseRows() {
        val queries = database.torveQueries
        queries.transaction {
            queries.deleteAllMetadataCache()
            queries.clearAllProgressForAllUsers()
            queries.deleteAllAccountPreferences()
            queries.deleteAllAddonsForAllUsers()
            queries.deleteAllDebridAccountsForAllUsers()
            queries.deleteAllChannelsForAllUsers()
            queries.deleteAllIptvFavoritesForAllUsers()
            queries.clearRecentChannelsForAllUsers()
            queries.deleteAllCategoryConfigsForAllUsers()
            queries.clearHiddenChannels()
            queries.deleteAllEpgChannelsForAllUsers()
            queries.deleteAllEpgProgrammesForAllUsers()
            queries.deleteAllPlaylistsForAllUsers()
            queries.deleteAllDownloadsForAllUsers()
            queries.deleteAllSubscriptionsForAllUsers()
            queries.deleteAllProfilesForAllUsers()
            queries.deleteAllShelfConfigsForAllUsers()
            queries.clearWatchlistForAllUsers()
            queries.clearAllHistoryForAllUsers()
            queries.clearResolveMemory()
            queries.clearTraktRatings()
            queries.clearTraktSyncState()
            queries.clearTraktQueue()
            queries.deleteAllRatings()
            queries.deleteAllCatalogTopItems()
        }
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

    /**
     * Merge new credential keys into an existing integration row server-
     * side. Used to backfill the Panda `management_token` for users who
     * onboarded before that field was persisted at create time —
     * without rewriting the whole row.
     *
     * Returns the typed [com.torve.data.account.PatchCredentialsOutcome]
     * so callers can branch on `RowMissing` (fall back to PUT) and
     * `PremiumRequired` (surface upgrade UX) explicitly.
     */
    /**
     * Expose the user's currently-valid Torve JWT to other components
     * that need to authenticate against Torve-account-bound services
     * directly (e.g. Panda's `/api/v1/configs/me` endpoints). Returns
     * `null` when the user is signed out or the refresh token is dead.
     */
    suspend fun getTorveAccessToken(): String? = authClient.getValidAccessToken()

    suspend fun patchIntegrationCredentials(
        integrationType: String,
        credentials: Map<String, String>,
    ): com.torve.data.account.PatchCredentialsOutcome {
        torveVerboseLog { "[IntegrationSync] PATCH $integrationType keys=${credentials.keys}" }
        val token = authClient.getValidAccessToken()
            ?: return com.torve.data.account.PatchCredentialsOutcome.Error("No access token")
        val outcome = accountSettingsApi.patchIntegrationCredentials(
            accessToken = token,
            integrationType = integrationType,
            credentials = credentials,
        )
        torveVerboseLog { "[IntegrationSync] PATCH $integrationType → $outcome" }
        return outcome
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
            val registrationResult = runCatching {
                deviceApi.registerDevice(token, authClient.currentDeviceRegistration())
            }
            val registrationError = registrationResult.exceptionOrNull()?.message
            if (registrationError == null) {
                // Notify subscribers (in particular SubscriptionViewModel)
                // that the device now has a backend Device row. The very
                // first /me/access-state call on a fresh install races
                // registration and would otherwise pin a stale
                // device_not_registered snapshot until the user manually
                // re-triggers a refresh — this signal is the cure.
                deviceRegistrationNotifier.notifyRegistered(Clock.System.now().toEpochMilliseconds())
            }

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
                    val settingsResult = runCatching {
                        accountSettingsRepository.refreshIfStale(force = true)
                    }.getOrNull()
                    val restoredIntegrations = runCatching {
                        restoreIntegrations(token, forceCredentials = true)
                    }.getOrDefault(0)
                    val traktSynced = runCatching {
                        syncTraktFromAccountIfConnected()
                    }.getOrElse {
                        torveVerboseLog { "[TraktSync] Foreground force refresh FAILED: ${it.message}" }
                        false
                    }
                    val playlistSync = runCatching {
                        syncPlaylistsFromAccount(token)
                    }.getOrElse {
                        torveVerboseLog { "[PlaylistSync] Foreground force refresh FAILED: ${it.message}" }
                        PlaylistSyncResult()
                    }
                    if (settingsResult?.appliedChanges == true || restoredIntegrations > 0 || traktSynced || playlistSync.hasChanges) {
                        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
                    }
                }
            } else {
                backgroundScope.launch {
                    val settingsResult = runCatching {
                        accountSettingsRepository.refreshIfStale(force = false)
                    }.getOrNull()
                    val restoredIntegrations = runCatching {
                        restoreIntegrations(
                            token = token,
                            forceCredentials = settingsResult?.appliedChanges == true,
                        )
                    }.getOrDefault(0)
                    val traktSynced = runCatching {
                        if (restoredIntegrations > 0) syncTraktFromAccountIfConnected() else false
                    }.getOrElse {
                        torveVerboseLog { "[TraktSync] Foreground refresh FAILED: ${it.message}" }
                        false
                    }
                    val playlistSync = runCatching {
                        syncPlaylistsFromAccount(token)
                    }.getOrElse {
                        torveVerboseLog { "[PlaylistSync] Foreground refresh FAILED: ${it.message}" }
                        PlaylistSyncResult()
                    }
                    if (settingsResult?.appliedChanges == true || restoredIntegrations > 0 || traktSynced || playlistSync.hasChanges) {
                        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
                    }
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
            val deviceLimitReached = registrationError.isDeviceLimitRegistrationError()

            _state.update {
                it.copy(
                    deviceLimitReached = deviceLimitReached,
                    deviceLimitMessage = if (deviceLimitReached) {
                        "You have reached your $maxActiveDevices-device limit. Remove an existing device to continue."
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
            val settingsResult = runCatching { accountSettingsRepository.syncAfterSignIn() }.getOrNull()
            val restoredIntegrations = runCatching {
                restoreIntegrations(token, forceCredentials = true)
            }.getOrDefault(0)
            val traktSynced = runCatching {
                syncTraktFromAccountIfConnected()
            }.getOrElse {
                torveVerboseLog { "[TraktSync] Sign-in restore FAILED: ${it.message}" }
                false
            }
            val playlistSync = runCatching {
                syncPlaylistsFromAccount(token)
            }.getOrElse {
                torveVerboseLog { "[PlaylistSync] Sign-in restore FAILED: ${it.message}" }
                PlaylistSyncResult()
            }
            if (settingsResult?.appliedChanges == true || restoredIntegrations > 0 || traktSynced || playlistSync.hasChanges) {
                settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
            }
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
            restoreIntegrations(token, forceCredentials = true)
        }.getOrElse {
            errors++
            torveVerboseLog { "[Restore] Integrations restore FAILED: ${it.message}" }
            0
        }
        runCatching { syncTraktFromAccountIfConnected() }.onFailure {
            errors++
            torveVerboseLog { "[Restore] Trakt sync FAILED: ${it.message}" }
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
    private suspend fun restoreIntegrations(
        token: String,
        forceCredentials: Boolean = false,
    ): Int {
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
                val hasLocalSecret = when (secretKey) {
                    IntegrationSecretKey.TRAKT_TOKENS -> {
                        // Don't trust the raw blob — a stub payload with empty accessToken
                        // can exist from earlier runs. Parse and check the actual token.
                        val access = runCatching {
                            com.torve.data.trakt.auth.TraktTokenStore(
                                integrationSecretStore,
                                kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                            ).read()?.accessToken.orEmpty()
                        }.getOrDefault("")
                        if (access.isBlank()) {
                            // Stale or missing — clear stub so backend fetch rewrites cleanly.
                            integrationSecretStore.remove(IntegrationSecretKey.TRAKT_TOKENS)
                            false
                        } else {
                            true
                        }
                    }
                    IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID -> integrationSecretStore.hasSecret(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID)
                    IntegrationSecretKey.PANDA_TOKEN -> {
                        // Always re-fetch on restore. The synced bundle
                        // {token, manifest_url, config_id, management_token} only
                        // populates the management-token-under-config_id slot via
                        // a fresh fetch — and we can't cheaply detect from the
                        // local store whether that slot is filled, since
                        // PANDA_MANAGEMENT_TOKEN is keyed by a per-config subKey
                        // and IntegrationSecretStore doesn't expose enumeration.
                        // The cost is a single GET per sign-in.
                        false
                    }
                    else -> integrationSecretStore.hasSecret(secretKey)
                }
                if (hasLocalSecret && !forceCredentials) {
                    torveVerboseLog { "[IntegrationRestore] ${integration.integrationType} → local secret already present, skipping credential fetch" }
                    continue
                }
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
                    } else if (secretKey == IntegrationSecretKey.PANDA_TOKEN) {
                        // Panda's synced credential bundle: {token, manifest_url,
                        // config_id, management_token}. Older payloads had only
                        // "token" — the missing-field path is fine (rest stay null).
                        val pandaToken = credsMap["token"].orEmpty()
                        val configId = credsMap["config_id"].orEmpty()
                        val managementToken = credsMap["management_token"].orEmpty()
                        if (pandaToken.isNotBlank()) {
                            integrationSecretStore.put(IntegrationSecretKey.PANDA_TOKEN, pandaToken)
                            // Stash the management token under its config_id subKey
                            // so PandaSetupViewModel can authenticate edit/PATCH calls.
                            // Addon-row config_id is back-filled lazily by PandaSetupViewModel
                            // on first open (it resolves config_id from Panda if missing).
                            if (configId.isNotBlank() && managementToken.isNotBlank()) {
                                integrationSecretStore.put(
                                    key = IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN,
                                    value = managementToken,
                                    subKey = configId,
                                )
                            }
                            restored++
                            torveVerboseLog {
                                "[IntegrationRestore] PANDA_TOKEN → restored OK (token=present config=${configId.isNotBlank()} mgmt=${managementToken.isNotBlank()})"
                            }
                        }
                    } else {
                        val value = credsMap.values.firstOrNull()
                        if (!value.isNullOrBlank()) {
                            integrationSecretStore.put(secretKey, value)
                            when (secretKey) {
                                IntegrationSecretKey.OMDB_API_KEY -> prefsRepo.setString(SettingsViewModel.KEY_OMDB_API_KEY, value)
                                IntegrationSecretKey.MDBLIST_API_KEY -> prefsRepo.setString(SettingsViewModel.KEY_MDBLIST_API_KEY, value)
                                else -> Unit
                            }
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

    private suspend fun syncPlaylistsFromAccount(token: String): PlaylistSyncResult {
        val remotePlaylists = accountSettingsApi.getPlaylists(token)
        if (remotePlaylists.isEmpty()) {
            torveVerboseLog { "[PlaylistSync] No remote playlists found — leaving local catalog unchanged" }
            return PlaylistSyncResult()
        }

        val localById = channelRepo.getPlaylists().associateBy { it.id }
        var added = 0
        var updated = 0
        var failed = 0

        for (remote in remotePlaylists) {
            val playlistId = remote.playlistId.ifBlank { remote.id }.trim()
            if (playlistId.isBlank()) continue
            val local = localById[playlistId]
            try {
                val changed = syncPlaylistFromAccount(token, remote, local, playlistId)
                if (changed) {
                    if (local == null) added++ else updated++
                }
            } catch (e: Exception) {
                failed++
                torveVerboseLog {
                    "[PlaylistSync] FAILED for '$playlistId' (${remote.name}): ${e.message}"
                }
            }
        }

        torveVerboseLog {
            "[PlaylistSync] Completed: added=$added updated=$updated failed=$failed remote=${remotePlaylists.size}"
        }
        return PlaylistSyncResult(added = added, updated = updated, failed = failed)
    }

    private suspend fun syncPlaylistFromAccount(
        token: String,
        remote: RemotePlaylistDto,
        local: ChannelPlaylist?,
        playlistId: String,
    ): Boolean {
        return if (remote.isXtreamPlaylist(playlistId)) {
            syncXtreamPlaylistFromAccount(token, remote, local, playlistId)
        } else {
            syncM3uPlaylistFromAccount(remote, local, playlistId)
        }
    }

    private suspend fun syncM3uPlaylistFromAccount(
        remote: RemotePlaylistDto,
        local: ChannelPlaylist?,
        playlistId: String,
    ): Boolean {
        val remoteUrl = remote.url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val remoteEpgUrl = remote.epgUrl.normalizedRemoteValue()
        if (local == null) {
            channelRepo.addPlaylist(
                name = remote.name,
                url = remoteUrl,
                epgUrl = remoteEpgUrl,
                id = playlistId,
            )
            return true
        }

        val localUrl = local.url.normalizedRemoteValue()
        val localEpgUrl = local.epgUrl.normalizedRemoteValue()
        val typeChanged = local.type != PlaylistType.M3U
        val urlChanged = localUrl != remoteUrl
        val epgChanged = localEpgUrl != remoteEpgUrl
        if (!typeChanged && !urlChanged && !epgChanged) {
            return false
        }

        if (typeChanged) {
            channelRepo.removePlaylist(playlistId)
        }

        if (!typeChanged && !urlChanged && epgChanged) {
            channelRepo.updatePlaylistEpgUrl(playlistId, remoteEpgUrl)
        } else {
            channelRepo.addPlaylist(
                name = remote.name,
                url = remoteUrl,
                epgUrl = remoteEpgUrl,
                id = playlistId,
            )
        }
        return true
    }

    private suspend fun syncXtreamPlaylistFromAccount(
        token: String,
        remote: RemotePlaylistDto,
        local: ChannelPlaylist?,
        playlistId: String,
    ): Boolean {
        val creds = accountSettingsApi.getPlaylistCredentials(token, playlistId)
        val server = remote.server.normalizedServerValue() ?: return false
        val username = creds?.username.normalizedRemoteValue()
            ?: remote.username.normalizedRemoteValue()
            ?: return false
        val password = creds?.password.normalizedRemoteValue() ?: return false

        if (local != null) {
            val sameType = local.type == PlaylistType.XTREAM
            val sameServer = local.server.normalizedServerValue() == server
            val sameUsername = local.username.normalizedRemoteValue() == username
            val samePassword = local.password.normalizedRemoteValue() == password
            if (sameType && sameServer && sameUsername && samePassword) {
                return false
            }
            if (!sameType) {
                channelRepo.removePlaylist(playlistId)
            }
        }

        channelRepo.addXtreamPlaylist(
            name = remote.name,
            server = server,
            username = username,
            password = password,
            id = playlistId,
        )
        return true
    }

    private fun String?.normalizedRemoteValue(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.normalizedServerValue(): String? =
        normalizedRemoteValue()?.trimEnd('/')

    private suspend fun syncTraktFromAccountIfConnected(): Boolean {
        val hasTraktTokens = integrationSecretStore.hasSecret(IntegrationSecretKey.TRAKT_TOKENS)
        if (!hasTraktTokens) {
            return false
        }

        runCatching { watchlistRepo.syncFromTrakt() }
        runCatching { watchProgressRepo.syncFromTrakt() }
        runCatching { watchHistoryRepo.syncFromTrakt() }
        runCatching { traktSyncRepo.syncRatingsFromTrakt() }
        runCatching { traktSyncRepo.flushPendingWrites() }
        torveVerboseLog { "[TraktSync] Synced account-backed Trakt data" }
        return true
    }
}
