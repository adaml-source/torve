package com.torve.presentation.session

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountApiException
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
import com.torve.data.device.resolvedActiveDeviceCount
import com.torve.data.device.resolvedDeviceLimit
import com.torve.data.device.resolvedDeviceLimitActiveDevices
import com.torve.data.security.TrustSignalsApi
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
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.security.SecureStorage
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.integrations.setTorBoxCredentialStorageMode
import com.torve.presentation.integrations.syncTorBoxCredentialPair
import com.torve.platform.torveVerboseLog
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class AccountSessionState(
    val isBootstrapping: Boolean = false,
    val isSigningOut: Boolean = false,
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

internal fun integrationSecretKeyForRestore(integrationType: String): IntegrationSecretKey? {
    val normalized = integrationType.trim()
    IntegrationSecretKey.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        ?.let { return it }
    return when (normalized.lowercase()) {
        "real_debrid", "realdebrid", "rd" -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        "all_debrid", "alldebrid", "ad" -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        "premiumize", "pm" -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        "torbox", "tb" -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
        "panda_download_client_api_key",
        "torbox_download_client",
        "torbox_download_client_api_key" -> IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY
        "trakt" -> IntegrationSecretKey.TRAKT_TOKENS
        "simkl" -> IntegrationSecretKey.SIMKL_ACCESS_TOKEN
        "plex" -> IntegrationSecretKey.PLEX_ACCESS_TOKEN
        "jellyfin" -> IntegrationSecretKey.JELLYFIN_API_KEY
        "omdb" -> IntegrationSecretKey.OMDB_API_KEY
        "mdblist", "mdb_list" -> IntegrationSecretKey.MDBLIST_API_KEY
        "panda", "panda_token" -> IntegrationSecretKey.PANDA_TOKEN
        else -> null
    }
}

private fun restoredSingleCredentialValue(credentials: Map<String, String>): String? {
    return credentials["api_key"]
        ?: credentials["apiKey"]
        ?: credentials["access_token"]
        ?: credentials["accessToken"]
        ?: credentials["token"]
        ?: credentials.values.firstOrNull()
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
    private val mediaFavoritesRepository: MediaFavoritesRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val traktSyncRepo: TraktSyncRepository,
    private val deviceRegistrationNotifier: DeviceRegistrationNotifier,
    private val trustSignalsApi: TrustSignalsApi? = null,
) {
    // Use IO dispatcher for background restore — heavy network + disk work
    // must not compete with Compose rendering on the Default (CPU) pool.
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var signInRestoreJob: Job? = null

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
     * Explicit account activation used after device-to-device credential import
     * or a manual "refresh account data" action. This is intentionally different
     * from content cache workers: first restore backend account state and
     * integration credentials, then callers can warm catalog caches once.
     */
    suspend fun refreshAccountDataAfterCredentialTransfer(
        initialMessage: String = "Activating imported credentials...",
    ): AccountSessionBootstrapResult {
        val token = authClient.getValidAccessToken()
            ?: return AccountSessionBootstrapResult(
                isReady = false,
                error = "Sign in required to refresh account data.",
            )

        signInRestoreJob?.cancel()
        signInRestoreJob = null
        _state.update { it.copy(isBootstrapping = true, lastError = null) }
        _restoreProgress.value = RestoreProgress(
            phase = RestorePhase.RUNNING,
            message = initialMessage,
            isImporting = true,
        )

        return withContext(ioDispatcher) {
            var errors = 0
            postTrustSignal(token, "credential_transfer")

            _restoreProgress.update { it.copy(message = "Syncing account settings...") }
            val settingsResult = runCatching {
                accountSettingsRepository.syncAfterSignIn()
            }.onFailure { error ->
                errors++
                torveVerboseLog { "[AccountRefresh] Account settings FAILED: ${error.message}" }
            }.getOrNull()

            _restoreProgress.update { it.copy(message = "Restoring integrations...") }
            val restoredIntegrations = runCatching {
                restoreIntegrations(token, forceCredentials = true)
            }.getOrElse { error ->
                errors++
                torveVerboseLog { "[AccountRefresh] Integration restore FAILED: ${error.message}" }
                0
            }

            _restoreProgress.update { it.copy(message = "Syncing source providers...") }
            runCatching {
                addonSyncService.syncAfterSignIn()
            }.onFailure { error ->
                errors++
                torveVerboseLog { "[AccountRefresh] Addon sync FAILED: ${DiagnosticsRedactor.redact(error.message)}" }
            }

            _restoreProgress.update { it.copy(message = "Syncing Trakt...") }
            val traktSynced = runCatching {
                syncTraktFromAccountIfConnected()
            }.getOrElse { error ->
                errors++
                torveVerboseLog { "[AccountRefresh] Trakt sync FAILED: ${DiagnosticsRedactor.redact(error.message)}" }
                false
            }

            _restoreProgress.update { it.copy(message = "Syncing playlists...") }
            val playlistSync = runCatching {
                syncPlaylistsFromAccount(token)
            }.getOrElse { error ->
                errors++
                torveVerboseLog { "[AccountRefresh] Playlist sync FAILED: ${DiagnosticsRedactor.redact(error.message)}" }
                PlaylistSyncResult()
            }

            mediaFavoritesRepository.refresh(force = true)

            val now = Clock.System.now().toEpochMilliseconds()
            settingsRefreshNotifier.notifyRefresh(now)
            val phase = if (errors > 0) RestorePhase.COMPLETED_WITH_ERRORS else RestorePhase.COMPLETED
            _restoreProgress.value = RestoreProgress(
                phase = phase,
                message = if (errors > 0) {
                    "Account refresh completed with $errors issue${if (errors == 1) "" else "s"}"
                } else {
                    "Account refresh complete"
                },
                errorCount = errors,
                integrationsRestored = restoredIntegrations,
                isImporting = false,
            )
            _state.update {
                it.copy(
                    isBootstrapping = false,
                    lastError = if (errors > 0) "Account refresh completed with issues." else null,
                )
            }

            AccountSessionBootstrapResult(
                isReady = errors == 0 || settingsResult != null || restoredIntegrations > 0 || traktSynced || playlistSync.hasChanges,
                error = if (errors > 0) "Account refresh completed with issues." else null,
                settingsResult = settingsResult,
            )
        }
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
        signInRestoreJob?.cancel()
        signInRestoreJob = null
        _state.update {
            it.copy(
                isBootstrapping = false,
                isSigningOut = true,
                lastError = null,
            )
        }
        _restoreProgress.value = RestoreProgress(
            phase = RestorePhase.RUNNING,
            message = "Signing out...",
            isImporting = true,
        )
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
        withContext(ioDispatcher) {
            try {
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
                runCatching { mediaFavoritesRepository.clearSessionState() }
                runCatching { watchProgressRepo.clearAllProgress() }
                runCatching { watchHistoryRepo.clearAll() }
                runCatching { traktSyncRepo.clearLocalData() }
                torveVerboseLog { "[SignOut] Repository caches cleared" }
                runCatching { addonSyncService.clearSyncStateOnSignOut() }
                torveVerboseLog { "[SignOut] Addon sync metadata cleared" }
                accountSettingsRepository.clearSessionState()
                torveVerboseLog { "[SignOut] Local account cleanup finished reason=$reason" }
            } finally {
                _state.value = AccountSessionState()
                _restoreProgress.value = RestoreProgress()
                settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
            }
        }
    }

    private fun purgeAccountScopedDatabaseRows() {
        val queries = database.torveQueries
        queries.transaction {
            // Keep public discovery caches (TMDB metadata/catalog tops) intact:
            // signed-out users can still browse Movies and TV Shows.
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
            queries.clearAllWatchSessionsForAllUsers()
            queries.clearResolveMemory()
            queries.clearTraktRatings()
            queries.clearTraktSyncState()
            queries.clearTraktQueue()
            queries.deleteAllRatings()
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
     * `PremiumRequired` as historical compatibility only.
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
        val normalizedType = playlistType.trim().lowercase().ifBlank { "m3u" }
        val normalizedEpgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        return accountSettingsApi.savePlaylist(
            accessToken = token,
            playlistId = playlistId,
            request = com.torve.data.account.SavePlaylistRequest(
                playlistId = playlistId,
                name = name,
                url = url,
                epgUrl = normalizedEpgUrl,
                playlistType = normalizedType,
                server = server,
                username = username,
                password = password,
            ),
        )
    }

    suspend fun validatePlaylistEpgUrl(epgUrl: String): com.torve.data.account.EpgValidationResponse {
        val normalizedUrl = epgUrl.trim()
        if (normalizedUrl.isBlank()) {
            return com.torve.data.account.EpgValidationResponse(
                success = false,
                status = "empty",
                message = "Enter an EPG URL to check.",
            )
        }
        val token = authClient.getValidAccessToken()
            ?: return com.torve.data.account.EpgValidationResponse(
                success = false,
                status = "signed_out",
                message = "Sign in to check EPG URLs.",
            )
        return accountSettingsApi.validateEpg(token, normalizedUrl)
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

            val accessState = runCatching { deviceApi.getAccessState(token) }.getOrNull()
            postTrustSignal(
                token = token,
                eventType = if (forceSettingsRefresh) "login" else "foreground",
            )

            torveVerboseLog { "[Login] Phase A: device registered, entering app" }
            _state.update { it.copy(isBootstrapping = false) }

            // ── Phase B: Background restore (deferred) ────────────
            if (forceSettingsRefresh) {
                torveVerboseLog { "[Login] Phase B: launching account restore immediately after sign-in" }
                if (signInRestoreJob?.isActive == true) {
                    torveVerboseLog { "[Login] Phase B: sign-in restore already running; skipping duplicate launch" }
                } else {
                    _restoreProgress.value = RestoreProgress(
                        phase = RestorePhase.RUNNING,
                        message = "Syncing account settings",
                        isImporting = true,
                    )
                    signInRestoreJob = backgroundScope.launch {
                        runCatching { addonSyncService.syncAfterSignIn() }
                        backgroundRestore(token)
                    }
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
                        torveVerboseLog { "[TraktSync] Foreground force refresh FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
                        false
                    }
                    val playlistSync = runCatching {
                        syncPlaylistsFromAccount(token)
                    }.getOrElse {
                        torveVerboseLog { "[PlaylistSync] Foreground force refresh FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
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
                        torveVerboseLog { "[TraktSync] Foreground refresh FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
                        false
                    }
                    val playlistSync = runCatching {
                        syncPlaylistsFromAccount(token)
                    }.getOrElse {
                        torveVerboseLog { "[PlaylistSync] Foreground refresh FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
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

            // Fetch devices too: the screen needs the list, while the
            // effective cap comes from access-state when available.
            val deviceList = runCatching { deviceApi.getDevices(token) }
                .getOrElse {
                    DeviceListDto(
                        devices = accessState?.resolvedDeviceLimitActiveDevices().orEmpty(),
                        active_count = accessState?.resolvedActiveDeviceCount() ?: 0,
                        max_active = 0,
                        swaps_remaining = 0,
                    )
                }
            val maxActiveDevices = accessState?.resolvedDeviceLimit()
                ?: deviceList.max_active.takeIf { it > 0 }
            val deviceLimitReached = registrationError.isDeviceLimitRegistrationError()
            val deviceLimitMessage = if (deviceLimitReached) {
                maxActiveDevices?.let {
                    "You have reached your $it-device limit. Remove an existing device to continue."
                } ?: registrationError ?: "Device limit reached. Remove an existing device to continue."
            } else {
                null
            }
            val activeDevices = accessState?.resolvedDeviceLimitActiveDevices()
                ?.takeIf { it.isNotEmpty() }
                ?: deviceList.devices

            _state.update {
                it.copy(
                    deviceLimitReached = deviceLimitReached,
                    deviceLimitMessage = deviceLimitMessage,
                    activeDevices = activeDevices,
                    lastError = registrationError,
                )
            }

            AccountSessionBootstrapResult(
                isReady = !deviceLimitReached && registrationError == null,
                deviceLimitReached = deviceLimitReached,
                activeDevices = activeDevices,
                error = registrationError,
                accessState = accessState,
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
            _restoreProgress.value = RestoreProgress(
                phase = RestorePhase.RUNNING,
                message = "Syncing account settings",
                isImporting = true,
            )
            torveVerboseLog { "[Restore] Local data exists (${localPlaylists.size} playlists) — skipping heavy restore" }
            val settingsResult = runCatching { accountSettingsRepository.syncAfterSignIn() }.getOrNull()
            val restoredIntegrations = runCatching {
                restoreIntegrations(token, forceCredentials = true)
            }.getOrDefault(0)
            val traktSynced = runCatching {
                syncTraktFromAccountIfConnected()
            }.getOrElse {
                torveVerboseLog { "[TraktSync] Sign-in restore FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
                false
            }
            val playlistSync = runCatching {
                syncPlaylistsFromAccount(token)
            }.getOrElse {
                torveVerboseLog { "[PlaylistSync] Sign-in restore FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
                PlaylistSyncResult()
            }
            if (settingsResult?.appliedChanges == true || restoredIntegrations > 0 || traktSynced || playlistSync.hasChanges) {
                settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
            }
            _restoreProgress.value = RestoreProgress(
                phase = RestorePhase.COMPLETED,
                message = "Account sync complete",
                integrationsRestored = restoredIntegrations,
                isImporting = false,
            )
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
            torveVerboseLog { "[Restore] Playlist restore FAILED: ${DiagnosticsRedactor.redact(it.message)}" }
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
        val integrations = withAccountApiAuthRetry(token) { freshToken ->
            accountSettingsApi.getIntegrations(freshToken)
        }
        torveVerboseLog { "[IntegrationRestore] Found ${integrations.size} integrations on backend" }
        var restored = 0
        for (integration in integrations) {
            val secretKey = integrationSecretKeyForRestore(integration.integrationType)
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
            if (secretKey == IntegrationSecretKey.DEBRID_API_KEY_TORBOX ||
                secretKey == IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY
            ) {
                integrationSecretStore.setTorBoxCredentialStorageMode(mode)
            }
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
                    IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
                    IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY -> {
                        integrationSecretStore.syncTorBoxCredentialPair()
                    }
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
                        val accessTok = credsMap["access_token"]
                            ?: credsMap["accessToken"]
                            ?: credsMap["access"]
                            ?: credsMap["token"]
                            ?: ""
                        val refreshTok = credsMap["refresh_token"]
                            ?: credsMap["refreshToken"]
                            ?: credsMap["refresh"]
                            ?: ""
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
                        val value = restoredSingleCredentialValue(credsMap)
                        if (!value.isNullOrBlank()) {
                            if (secretKey == IntegrationSecretKey.DEBRID_API_KEY_TORBOX ||
                                secretKey == IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY
                            ) {
                                integrationSecretStore.syncTorBoxCredentialPair(value)
                            } else {
                                integrationSecretStore.put(secretKey, value)
                            }
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
        integrationSecretStore.syncTorBoxCredentialPair()
        restored += ensureUnifiedTraktTokensAfterRestore()
        torveVerboseLog { "[IntegrationRestore] Done: $restored/${integrations.size}" }
        return restored
    }

    private suspend fun ensureUnifiedTraktTokensAfterRestore(): Int {
        val traktTokenStore = com.torve.data.trakt.auth.TraktTokenStore(
            integrationSecretStore,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        if (!traktTokenStore.read()?.accessToken.isNullOrBlank()) {
            return 0
        }
        val accessToken = integrationSecretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return 0
        val refreshToken = integrationSecretStore.get(IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return 0
        traktTokenStore.write(
            com.torve.data.trakt.TraktTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 0,
                createdAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            ),
        )
        torveVerboseLog { "[IntegrationRestore] TRAKT_TOKENS synthesized from legacy access/refresh secrets" }
        return 1
    }

    // ── Playlist restore ────────────────────────────────────────

    /** Returns (restored, failed) counts. */
    private suspend fun restorePlaylists(token: String): Pair<Int, Int> {
        val remotePlaylists = withAccountApiAuthRetry(token) { freshToken ->
            accountSettingsApi.getPlaylists(freshToken)
        }
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
                    channelRepo.saveXtreamPlaylistConfig(
                        name = remote.name,
                        server = server,
                        username = resolvedUsername,
                        password = password,
                        id = pid,
                    )
                    torveVerboseLog { "[PlaylistRestore]   Xtream import OK" }
                } else if (!remote.url.isNullOrBlank()) {
                    channelRepo.saveM3uPlaylistConfig(
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
                torveVerboseLog { "[PlaylistRestore]   FAILED: ${DiagnosticsRedactor.redact(e.message)}" }
            }
        }
        torveVerboseLog { "[PlaylistRestore] Done: $restored restored, $failed failed" }
        return restored to failed
    }

    private suspend fun syncPlaylistsFromAccount(token: String): PlaylistSyncResult {
        val remotePlaylists = withAccountApiAuthRetry(token) { freshToken ->
            accountSettingsApi.getPlaylists(freshToken)
        }
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
            channelRepo.saveM3uPlaylistConfig(
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
            channelRepo.saveM3uPlaylistConfig(
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
            if (sameType && sameServer && sameUsername && samePassword) return false
            if (!sameType) {
                channelRepo.removePlaylist(playlistId)
            }
        }

        channelRepo.saveXtreamPlaylistConfig(
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

    private suspend fun <T> withAccountApiAuthRetry(
        token: String,
        block: suspend (String) -> T,
    ): T {
        return try {
            block(token)
        } catch (e: AccountApiException) {
            if (e.statusCode != 401) throw e
            val refreshed = authClient.refreshTokens()
            val retryToken = if (refreshed.success) authClient.getValidAccessToken() else null
            if (retryToken.isNullOrBlank()) {
                runCatching { authClient.logout() }
                throw AccountApiException(
                    statusCode = 401,
                    endpoint = e.endpoint,
                )
            }
            block(retryToken)
        }
    }

    private fun String?.normalizedServerValue(): String? =
        normalizedRemoteValue()?.trimEnd('/')

    private fun postTrustSignal(token: String, eventType: String) {
        backgroundScope.launch {
            val posted = trustSignalsApi?.postCurrentTrustSignal(
                accessToken = token,
                eventType = eventType,
            ) ?: return@launch
            if (!posted) {
                torveVerboseLog { "CLIENT_TRUST post_skipped_or_failed event=$eventType" }
            }
        }
    }

    private suspend fun syncTraktFromAccountIfConnected(): Boolean {
        ensureUnifiedTraktTokensAfterRestore()
        val traktTokenStore = com.torve.data.trakt.auth.TraktTokenStore(
            integrationSecretStore,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        val tokens = traktTokenStore.read()
        val accessToken = tokens?.accessToken?.takeIf { it.isNotBlank() }
            ?: integrationSecretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
        if (accessToken.isNullOrBlank()) {
            torveVerboseLog { "[TraktSync] Skipped account sync: no local Trakt access token" }
            return false
        }

        runCatching { watchlistRepo.syncFromTrakt() }
        runCatching { watchProgressRepo.syncFromTrakt() }
        runCatching { watchHistoryRepo.syncFromTrakt() }
        runCatching { traktSyncRepo.syncRatingsFromTrakt() }
        runCatching { traktSyncRepo.flushPendingWrites() }
        val now = Clock.System.now().toEpochMilliseconds()
        prefsRepo.setString(SettingsViewModel.KEY_TRAKT_LAST_SYNC_TIME, now.toString())
        settingsRefreshNotifier.notifyRefresh(now)
        torveVerboseLog { "[TraktSync] Synced account-backed Trakt data" }
        return true
    }
}
