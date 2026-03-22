package com.torve.presentation.session

import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountSettingsRefreshResult
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.auth.AuthClient
import com.torve.data.device.AccessStateDto
import com.torve.data.device.DeviceApi
import com.torve.data.device.DeviceListDto
import com.torve.data.device.ManagedDeviceDto
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.presentation.settings.SettingsRefreshNotifier
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
) {
    companion object {
        private const val DEFAULT_MAX_ACTIVE_DEVICES = 5
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AccountSessionState())
    val state: StateFlow<AccountSessionState> = _state.asStateFlow()

    /** Observable restore progress — UI can show non-blocking status. */
    private val _restoreProgress = MutableStateFlow(RestoreProgress())
    val restoreProgress: StateFlow<RestoreProgress> = _restoreProgress.asStateFlow()

    suspend fun restoreSession(): Boolean {
        val restored = authClient.restoreSession()
        if (!restored) {
            accountSettingsRepository.clearSessionState()
            return false
        }
        bootstrapAfterSignIn()
        return true
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
        println("[SignOut] Playlist/credential cleanup started")
        integrationSecretStore.clearAllSecrets()
        println("[SignOut] Encrypted secret store cleared")
        for (key in integrationSecretStore.legacyPreferenceSecretKeys) {
            prefsRepo.remove(key)
        }
        println("[SignOut] Legacy preference secrets cleared")
        runCatching { channelRepo.clearAll() }
        println("[SignOut] SQLite playlists/channels/favorites/recents cleared")
        accountSettingsRepository.clearSessionState()
        _state.value = AccountSessionState()
        _restoreProgress.value = RestoreProgress()
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
        println("[SignOut] Playlist/credential cleanup finished")
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
        println("[IntegrationSync] Saving $integrationType to backend (label=$displayIdentifier)")
        val token = authClient.getValidAccessToken()
        if (token == null) {
            println("[IntegrationSync] FAILED: no valid access token")
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
        println("[IntegrationSync] $integrationType → ${if (ok) "OK" else "FAILED"}")
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
        _state.update { it.copy(isBootstrapping = true, lastError = null) }

        return runCatching {
            // ── Phase A: Critical path (fast) ─────────────────────
            println("[Login] Phase A: registering device...")
            val registrationError = runCatching {
                deviceApi.registerDevice(token, authClient.currentDeviceRegistration())
            }.exceptionOrNull()?.message

            println("[Login] Phase A: device registered, entering app")
            _state.update { it.copy(isBootstrapping = false) }

            // ── Phase B: Background restore (deferred) ────────────
            if (forceSettingsRefresh) {
                println("[Login] Phase B: launching background restore")
                backgroundScope.launch {
                    backgroundRestore(token)
                }
            } else if (forceStaleRefresh) {
                backgroundScope.launch {
                    runCatching { accountSettingsRepository.refreshIfStale(force = true) }
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
            )
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
            )
        }
    }

    // ── Background restore pipeline ─────────────────────────────

    private suspend fun backgroundRestore(token: String) {
        val startMs = Clock.System.now().toEpochMilliseconds()
        _restoreProgress.value = RestoreProgress(
            phase = RestorePhase.RUNNING,
            message = "Restoring your account data…",
        )
        println("[Restore] Background restore started")
        var errors = 0

        // Step 1: Account settings
        _restoreProgress.update { it.copy(message = "Syncing settings…") }
        runCatching {
            accountSettingsRepository.syncAfterSignIn()
            println("[Restore] Account settings synced")
        }.onFailure { e ->
            errors++
            println("[Restore] Account settings FAILED: ${e.message}")
        }

        // Step 2: Integrations
        _restoreProgress.update { it.copy(message = "Restoring integrations…") }
        val integrationsRestored = runCatching {
            restoreIntegrations(token)
        }.getOrElse {
            errors++
            println("[Restore] Integrations restore FAILED: ${it.message}")
            0
        }
        // Refresh SettingsViewModel so restored integrations show as connected
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())

        // Step 3: Playlists
        _restoreProgress.update { it.copy(message = "Restoring playlists…") }
        val (playlistsRestored, playlistsFailed) = runCatching {
            restorePlaylists(token)
        }.getOrElse {
            errors++
            println("[Restore] Playlist restore FAILED: ${it.message}")
            0 to 0
        }
        errors += playlistsFailed

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
        )
        println("[Restore] Completed in ${elapsed}s: $integrationsRestored integrations, $playlistsRestored playlists, $errors errors")
    }

    // ── Integration restore ─────────────────────────────────────

    /** Returns number of integrations restored. */
    private suspend fun restoreIntegrations(token: String): Int {
        val integrations = accountSettingsApi.getIntegrations(token)
        println("[IntegrationRestore] Found ${integrations.size} integrations on backend")
        var restored = 0
        for (integration in integrations) {
            val secretKey = runCatching {
                IntegrationSecretKey.valueOf(integration.integrationType)
            }.getOrNull()
            if (secretKey == null) {
                println("[IntegrationRestore] Skipping unknown type: ${integration.integrationType}")
                continue
            }

            val mode = when (integration.storageMode) {
                "account" -> IntegrationStorageMode.ACCOUNT
                "device_only" -> IntegrationStorageMode.DEVICE_ONLY
                else -> IntegrationStorageMode.DEVICE_ONLY
            }
            integrationSecretStore.setStorageMode(secretKey, mode)

            if (mode == IntegrationStorageMode.ACCOUNT && integration.hasCredentials) {
                println("[IntegrationRestore] Fetching credentials for ${integration.integrationType}...")
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
                            println("[IntegrationRestore] TRAKT_TOKENS → restored OK (access+refresh)")
                        }
                    } else {
                        val value = credsMap.values.firstOrNull()
                        if (!value.isNullOrBlank()) {
                            integrationSecretStore.put(secretKey, value)
                            restored++
                            println("[IntegrationRestore] ${integration.integrationType} → restored OK")
                        }
                    }
                } else {
                    println("[IntegrationRestore] ${integration.integrationType} → credentials empty")
                }
            }
        }
        println("[IntegrationRestore] Done: $restored/${integrations.size}")
        return restored
    }

    // ── Playlist restore ────────────────────────────────────────

    /** Returns (restored, failed) counts. */
    private suspend fun restorePlaylists(token: String): Pair<Int, Int> {
        val remotePlaylists = accountSettingsApi.getPlaylists(token)
        println("[PlaylistRestore] Remote playlist count: ${remotePlaylists.size}")
        _restoreProgress.update { it.copy(totalPlaylists = remotePlaylists.size) }
        var restored = 0
        var failed = 0
        for ((index, remote) in remotePlaylists.withIndex()) {
            val pid = remote.playlistId.ifBlank { remote.id }
            _restoreProgress.update {
                it.copy(
                    message = "Importing playlist ${index + 1} of ${remotePlaylists.size}…",
                    currentPlaylistName = remote.name,
                    restoredPlaylists = restored,
                )
            }
            println("[PlaylistRestore] Restoring '${remote.name}' (type=${remote.playlistType}, id=$pid)")
            try {
                if (remote.playlistType == "xtream" && remote.server != null) {
                    var password = ""
                    if (remote.hasPassword) {
                        val creds = accountSettingsApi.getPlaylistCredentials(token, pid)
                        if (creds?.password != null) {
                            password = creds.password
                            println("[PlaylistRestore]   Credentials fetched OK")
                        } else {
                            println("[PlaylistRestore]   Credentials fetch failed")
                        }
                    }
                    channelRepo.addXtreamPlaylist(
                        name = remote.name,
                        server = remote.server,
                        username = remote.username ?: "",
                        password = password,
                        id = pid,
                    )
                    println("[PlaylistRestore]   Xtream import OK")
                } else if (!remote.url.isNullOrBlank()) {
                    channelRepo.addPlaylist(
                        name = remote.name,
                        url = remote.url,
                        epgUrl = remote.epgUrl,
                        id = pid,
                    )
                    println("[PlaylistRestore]   M3U import OK")
                } else {
                    println("[PlaylistRestore]   Skipped — no url or server")
                }
                restored++
            } catch (e: Exception) {
                failed++
                println("[PlaylistRestore]   FAILED: ${e.message}")
            }
        }
        println("[PlaylistRestore] Done: $restored restored, $failed failed")
        return restored to failed
    }
}
