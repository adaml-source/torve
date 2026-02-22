package com.streamvault.presentation.settings

import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.kodi.KodiClient
import com.streamvault.data.kodi.KodiHost
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.trakt.TraktClient
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.StreamQuality
import com.streamvault.domain.repository.PreferencesRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val debridClient: DebridClient,
    private val traktClient: TraktClient,
    private val simklClient: SimklClient,
    private val kodiClient: KodiClient,
    private val database: StreamVaultDatabase,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var debridPollJob: Job? = null
    private var traktPollJob: Job? = null

    companion object {
        const val KEY_DEBRID_PROVIDER = "debrid_provider"
        const val KEY_DEBRID_API_KEY = "debrid_api_key"
        const val KEY_DEBRID_RD_REFRESH = "debrid_rd_refresh_token"
        const val KEY_DEBRID_RD_CLIENT_ID = "debrid_rd_client_id"
        const val KEY_DEBRID_RD_CLIENT_SECRET = "debrid_rd_client_secret"
        const val KEY_DEBRID_RD_EXPIRES_AT = "debrid_rd_expires_at"
        const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
        const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
        const val KEY_TRAKT_ACCESS_TOKEN = "trakt_access_token"
        const val KEY_TRAKT_REFRESH_TOKEN = "trakt_refresh_token"
        const val KEY_MAX_QUALITY = "stream_max_quality"
        const val KEY_MIN_QUALITY = "stream_min_quality"
        const val KEY_MAX_FILE_SIZE_MB = "stream_max_file_size_mb"
        const val KEY_CACHED_ONLY = "stream_cached_only"
        const val KEY_HDR_ENABLED = "stream_hdr_enabled"
        const val KEY_TRAKT_SCROBBLE = "trakt_scrobble_enabled"
        const val KEY_SIMKL_CLIENT_ID = "simkl_client_id"
        const val KEY_SIMKL_ACCESS_TOKEN = "simkl_access_token"
        const val KEY_KODI_HOSTS = "kodi_hosts_json"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
    }

    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            val provider = prefsRepo.getString(KEY_DEBRID_PROVIDER)?.let {
                try { DebridServiceType.valueOf(it) } catch (_: Exception) { null }
            } ?: DebridServiceType.REAL_DEBRID

            val apiKey = prefsRepo.getString(KEY_DEBRID_API_KEY) ?: ""
            val traktClientId = prefsRepo.getString(KEY_TRAKT_CLIENT_ID) ?: ""
            val traktClientSecret = prefsRepo.getString(KEY_TRAKT_CLIENT_SECRET) ?: ""
            val traktAccessToken = prefsRepo.getString(KEY_TRAKT_ACCESS_TOKEN) ?: ""
            val traktRefreshToken = prefsRepo.getString(KEY_TRAKT_REFRESH_TOKEN) ?: ""

            // Set Trakt credentials
            if (traktClientId.isNotBlank()) {
                traktClient.setCredentials(traktClientId, traktClientSecret)
            }

            val maxQuality = prefsRepo.getString(KEY_MAX_QUALITY)?.let {
                try { StreamQuality.valueOf(it) } catch (_: Exception) { null }
            } ?: StreamQuality.REMUX_4K
            val minQuality = prefsRepo.getString(KEY_MIN_QUALITY)?.let {
                try { StreamQuality.valueOf(it) } catch (_: Exception) { null }
            } ?: StreamQuality.SD_480P
            val maxFileSizeMb = prefsRepo.getString(KEY_MAX_FILE_SIZE_MB)?.toIntOrNull()
            val cachedOnly = prefsRepo.getString(KEY_CACHED_ONLY)?.toBooleanStrictOrNull() ?: true
            val hdrEnabled = prefsRepo.getString(KEY_HDR_ENABLED)?.toBooleanStrictOrNull() ?: false
            val scrobbleEnabled = prefsRepo.getString(KEY_TRAKT_SCROBBLE)?.toBooleanStrictOrNull() ?: true
            val simklClientId = prefsRepo.getString(KEY_SIMKL_CLIENT_ID) ?: ""
            val simklAccessToken = prefsRepo.getString(KEY_SIMKL_ACCESS_TOKEN) ?: ""
            if (simklClientId.isNotBlank()) simklClient.setClientId(simklClientId)

            val kodiHosts = prefsRepo.getString(KEY_KODI_HOSTS)?.let { json ->
                try {
                    Json.decodeFromString<List<KodiHostJson>>(json).map {
                        KodiHost(name = it.name, ip = it.ip, port = it.port)
                    }
                } catch (_: Exception) { emptyList() }
            } ?: emptyList()

            val themeMode = prefsRepo.getString(KEY_THEME_MODE)?.let {
                try { ThemeMode.valueOf(it) } catch (_: Exception) { null }
            } ?: ThemeMode.SYSTEM
            val appLanguage = prefsRepo.getString(KEY_APP_LANGUAGE)?.let {
                try { AppLanguage.valueOf(it) } catch (_: Exception) { null }
            } ?: AppLanguage.ENGLISH

            _state.update {
                it.copy(
                    debridProvider = provider,
                    debridApiKey = apiKey,
                    debridConnected = apiKey.isNotBlank(),
                    traktClientId = traktClientId,
                    traktClientSecret = traktClientSecret,
                    traktAccessToken = traktAccessToken,
                    traktRefreshToken = traktRefreshToken,
                    traktConnected = traktAccessToken.isNotBlank(),
                    traktScrobbleEnabled = scrobbleEnabled,
                    simklClientId = simklClientId,
                    simklAccessToken = simklAccessToken,
                    simklConnected = simklAccessToken.isNotBlank(),
                    maxQuality = maxQuality,
                    minQuality = minQuality,
                    maxFileSizeMb = maxFileSizeMb,
                    cachedOnly = cachedOnly,
                    hdrEnabled = hdrEnabled,
                    kodiHosts = kodiHosts,
                    themeMode = themeMode,
                    appLanguage = appLanguage,
                )
            }

            // Verify stored credentials
            if (apiKey.isNotBlank()) {
                verifyDebridConnection()
            }
            if (traktAccessToken.isNotBlank()) {
                verifyTraktConnection()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Debrid
    // -------------------------------------------------------------------------

    fun setDebridProvider(provider: DebridServiceType) {
        _state.update { it.copy(debridProvider = provider) }
        scope.launch { prefsRepo.setString(KEY_DEBRID_PROVIDER, provider.name) }
    }

    fun setDebridApiKey(apiKey: String) {
        _state.update { it.copy(debridApiKey = apiKey) }
    }

    fun connectDebridWithApiKey() {
        val apiKey = _state.value.debridApiKey
        if (apiKey.isBlank()) return

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            val result = debridClient.verifyApiKey(_state.value.debridProvider, apiKey)
            if (result.success) {
                prefsRepo.setString(KEY_DEBRID_API_KEY, apiKey)
                _state.update {
                    it.copy(
                        debridConnected = true,
                        debridUser = result.user,
                        debridLoading = false,
                    )
                }
            } else {
                _state.update {
                    it.copy(debridLoading = false, debridError = result.error)
                }
            }
        }
    }

    fun startDebridDeviceAuth() {
        val provider = _state.value.debridProvider
        if (!debridClient.supportsDeviceAuth(provider)) return

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            try {
                val code = debridClient.getDeviceCode(provider)
                _state.update { it.copy(debridDeviceCode = code, debridLoading = false) }
                if (code != null) pollDebridDevice(code)
            } catch (e: Exception) {
                _state.update {
                    it.copy(debridLoading = false, debridError = e.message)
                }
            }
        }
    }

    private fun pollDebridDevice(code: com.streamvault.data.debrid.DeviceCodeInfo) {
        debridPollJob?.cancel()
        debridPollJob = scope.launch {
            _state.update { it.copy(isPollingDebrid = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val result = debridClient.pollDeviceAuth(
                    _state.value.debridProvider,
                    code.deviceCode,
                    code.userCode,
                )
                if (result.done && result.apiKey != null) {
                    prefsRepo.setString(KEY_DEBRID_API_KEY, result.apiKey)
                    result.oauthTokens?.let { tokens ->
                        prefsRepo.setString(KEY_DEBRID_RD_REFRESH, tokens.refreshToken)
                        prefsRepo.setString(KEY_DEBRID_RD_CLIENT_ID, tokens.clientId)
                        prefsRepo.setString(KEY_DEBRID_RD_CLIENT_SECRET, tokens.clientSecret)
                        prefsRepo.setString(KEY_DEBRID_RD_EXPIRES_AT, tokens.expiresAt.toString())
                    }
                    _state.update {
                        it.copy(
                            debridApiKey = result.apiKey,
                            debridConnected = true,
                            debridDeviceCode = null,
                            isPollingDebrid = false,
                        )
                    }
                    verifyDebridConnection()
                    return@launch
                }
            }
            _state.update {
                it.copy(isPollingDebrid = false, debridError = "Device auth timed out")
            }
        }
    }

    private suspend fun verifyDebridConnection() {
        val result = debridClient.verifyApiKey(
            _state.value.debridProvider,
            _state.value.debridApiKey,
        )
        _state.update {
            it.copy(
                debridUser = result.user,
                debridConnected = result.success,
                debridError = if (!result.success) result.error else null,
            )
        }
    }

    fun disconnectDebrid() {
        debridPollJob?.cancel()
        scope.launch {
            prefsRepo.remove(KEY_DEBRID_API_KEY)
            prefsRepo.remove(KEY_DEBRID_RD_REFRESH)
            prefsRepo.remove(KEY_DEBRID_RD_CLIENT_ID)
            prefsRepo.remove(KEY_DEBRID_RD_CLIENT_SECRET)
            prefsRepo.remove(KEY_DEBRID_RD_EXPIRES_AT)
        }
        _state.update {
            it.copy(
                debridApiKey = "",
                debridConnected = false,
                debridUser = null,
                debridDeviceCode = null,
                isPollingDebrid = false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Trakt
    // -------------------------------------------------------------------------

    fun setTraktCredentials(clientId: String, clientSecret: String) {
        traktClient.setCredentials(clientId, clientSecret)
        _state.update { it.copy(traktClientId = clientId, traktClientSecret = clientSecret) }
        scope.launch {
            prefsRepo.setString(KEY_TRAKT_CLIENT_ID, clientId)
            prefsRepo.setString(KEY_TRAKT_CLIENT_SECRET, clientSecret)
        }
    }

    fun startTraktDeviceAuth() {
        if (_state.value.traktClientId.isBlank()) {
            _state.update { it.copy(traktError = "Set Trakt Client ID first") }
            return
        }

        scope.launch {
            _state.update { it.copy(traktLoading = true, traktError = null) }
            try {
                val code = traktClient.getDeviceCode()
                _state.update { it.copy(traktDeviceCode = code, traktLoading = false) }
                pollTraktDevice(code)
            } catch (e: Exception) {
                _state.update { it.copy(traktLoading = false, traktError = e.message) }
            }
        }
    }

    private fun pollTraktDevice(code: com.streamvault.data.trakt.TraktDeviceCode) {
        traktPollJob?.cancel()
        traktPollJob = scope.launch {
            _state.update { it.copy(isPollingTrakt = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val tokens = traktClient.pollDeviceToken(code.deviceCode)
                if (tokens != null) {
                    prefsRepo.setString(KEY_TRAKT_ACCESS_TOKEN, tokens.accessToken)
                    prefsRepo.setString(KEY_TRAKT_REFRESH_TOKEN, tokens.refreshToken)
                    _state.update {
                        it.copy(
                            traktAccessToken = tokens.accessToken,
                            traktRefreshToken = tokens.refreshToken,
                            traktConnected = true,
                            traktDeviceCode = null,
                            isPollingTrakt = false,
                        )
                    }
                    verifyTraktConnection()
                    return@launch
                }
            }
            _state.update {
                it.copy(isPollingTrakt = false, traktError = "Device auth timed out")
            }
        }
    }

    private suspend fun verifyTraktConnection() {
        try {
            val user = traktClient.getUser(_state.value.traktAccessToken)
            _state.update { it.copy(traktUser = user, traktConnected = true, traktApiStatus = "Online") }
            // Also load stats
            loadTraktStats()
        } catch (e: Exception) {
            _state.update {
                it.copy(traktConnected = false, traktError = e.message, traktApiStatus = "Error")
            }
        }
    }

    fun setTraktScrobbleEnabled(enabled: Boolean) {
        _state.update { it.copy(traktScrobbleEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_TRAKT_SCROBBLE, enabled.toString()) }
    }

    fun checkTraktApiStatus() {
        scope.launch {
            try {
                val token = _state.value.traktAccessToken
                if (token.isBlank()) {
                    _state.update { it.copy(traktApiStatus = "Not connected") }
                    return@launch
                }
                traktClient.getUser(token)
                _state.update { it.copy(traktApiStatus = "Online") }
            } catch (e: Exception) {
                _state.update { it.copy(traktApiStatus = "Error: ${e.message}") }
            }
        }
    }

    fun loadTraktStats() {
        scope.launch {
            try {
                val token = _state.value.traktAccessToken
                if (token.isBlank()) return@launch
                val stats = traktClient.getStats(token)
                _state.update { it.copy(traktStats = stats) }
            } catch (_: Exception) {
                // Stats are optional
            }
        }
    }

    fun disconnectTrakt() {
        traktPollJob?.cancel()
        val token = _state.value.traktAccessToken
        scope.launch {
            if (token.isNotBlank()) traktClient.revokeToken(token)
            prefsRepo.remove(KEY_TRAKT_ACCESS_TOKEN)
            prefsRepo.remove(KEY_TRAKT_REFRESH_TOKEN)
        }
        _state.update {
            it.copy(
                traktAccessToken = "",
                traktRefreshToken = "",
                traktConnected = false,
                traktUser = null,
                traktDeviceCode = null,
                isPollingTrakt = false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // SIMKL
    // -------------------------------------------------------------------------

    fun setSimklClientId(id: String) {
        simklClient.setClientId(id)
        _state.update { it.copy(simklClientId = id) }
        scope.launch { prefsRepo.setString(KEY_SIMKL_CLIENT_ID, id) }
    }

    fun startSimklDeviceAuth() {
        if (_state.value.simklClientId.isBlank()) {
            _state.update { it.copy(simklError = "Set SIMKL Client ID first") }
            return
        }

        scope.launch {
            _state.update { it.copy(simklLoading = true, simklError = null) }
            try {
                val code = simklClient.getDeviceCode()
                _state.update { it.copy(simklDeviceCode = code, simklLoading = false) }
                pollSimklDevice(code)
            } catch (e: Exception) {
                _state.update { it.copy(simklLoading = false, simklError = e.message) }
            }
        }
    }

    private fun pollSimklDevice(code: com.streamvault.data.simkl.SimklDeviceCode) {
        scope.launch {
            _state.update { it.copy(isPollingSimkl = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val tokens = simklClient.pollDeviceToken(code.userCode)
                if (tokens != null) {
                    prefsRepo.setString(KEY_SIMKL_ACCESS_TOKEN, tokens.accessToken)
                    _state.update {
                        it.copy(
                            simklAccessToken = tokens.accessToken,
                            simklConnected = true,
                            simklDeviceCode = null,
                            isPollingSimkl = false,
                        )
                    }
                    // Verify by fetching user
                    try {
                        val user = simklClient.getUser(tokens.accessToken)
                        _state.update { it.copy(simklUser = user) }
                    } catch (_: Exception) { }
                    return@launch
                }
            }
            _state.update { it.copy(isPollingSimkl = false, simklError = "Device auth timed out") }
        }
    }

    fun disconnectSimkl() {
        scope.launch {
            prefsRepo.remove(KEY_SIMKL_ACCESS_TOKEN)
        }
        _state.update {
            it.copy(
                simklAccessToken = "",
                simklConnected = false,
                simklUser = null,
                simklDeviceCode = null,
                isPollingSimkl = false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Stream Quality & Size Restrictions
    // -------------------------------------------------------------------------

    fun setMaxQuality(quality: StreamQuality) {
        _state.update { it.copy(maxQuality = quality) }
        scope.launch { prefsRepo.setString(KEY_MAX_QUALITY, quality.name) }
    }

    fun setMinQuality(quality: StreamQuality) {
        _state.update { it.copy(minQuality = quality) }
        scope.launch { prefsRepo.setString(KEY_MIN_QUALITY, quality.name) }
    }

    fun setMaxFileSizeMb(sizeMb: Int?) {
        _state.update { it.copy(maxFileSizeMb = sizeMb) }
        scope.launch {
            if (sizeMb != null) {
                prefsRepo.setString(KEY_MAX_FILE_SIZE_MB, sizeMb.toString())
            } else {
                prefsRepo.remove(KEY_MAX_FILE_SIZE_MB)
            }
        }
    }

    fun setCachedOnly(enabled: Boolean) {
        _state.update { it.copy(cachedOnly = enabled) }
        scope.launch { prefsRepo.setString(KEY_CACHED_ONLY, enabled.toString()) }
    }

    fun setHdrEnabled(enabled: Boolean) {
        _state.update { it.copy(hdrEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_HDR_ENABLED, enabled.toString()) }
    }

    fun buildStreamPreferences(): com.streamvault.domain.model.StreamPreferences {
        val s = _state.value
        return com.streamvault.domain.model.StreamPreferences(
            maxQuality = s.maxQuality,
            minQuality = s.minQuality,
            hdrEnabled = s.hdrEnabled,
            cachedOnly = s.cachedOnly,
            maxFileSizeBytes = s.maxFileSizeMb?.let { it.toLong() * 1024 * 1024 },
        )
    }

    // -------------------------------------------------------------------------
    // Getters for other ViewModels
    // -------------------------------------------------------------------------

    fun getDebridProvider(): DebridServiceType = _state.value.debridProvider
    fun getDebridApiKey(): String = _state.value.debridApiKey
    fun isDebridConnected(): Boolean = _state.value.debridConnected
    fun getTraktAccessToken(): String = _state.value.traktAccessToken
    fun isTraktConnected(): Boolean = _state.value.traktConnected

    // -------------------------------------------------------------------------
    // Kodi
    // -------------------------------------------------------------------------

    fun addKodiHost(name: String, ip: String, port: Int) {
        val host = KodiHost(name = name, ip = ip, port = port)
        val updated = _state.value.kodiHosts + host
        _state.update { it.copy(kodiHosts = updated) }
        saveKodiHosts(updated)
    }

    fun removeKodiHost(host: KodiHost) {
        val updated = _state.value.kodiHosts.filter { it != host }
        _state.update { it.copy(kodiHosts = updated) }
        saveKodiHosts(updated)
    }

    fun testKodiHost(host: KodiHost) {
        scope.launch {
            val key = "${host.ip}:${host.port}"
            _state.update { it.copy(kodiTestResult = it.kodiTestResult + (key to null)) }
            val result = kodiClient.ping(host)
            _state.update { it.copy(kodiTestResult = it.kodiTestResult + (key to result)) }
        }
    }

    private fun saveKodiHosts(hosts: List<KodiHost>) {
        scope.launch {
            val json = Json.encodeToString(hosts.map { KodiHostJson(it.name, it.ip, it.port) })
            prefsRepo.setString(KEY_KODI_HOSTS, json)
        }
    }

    // -------------------------------------------------------------------------
    // Theme & Language
    // -------------------------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        scope.launch { prefsRepo.setString(KEY_THEME_MODE, mode.name) }
    }

    fun setAppLanguage(language: AppLanguage) {
        _state.update { it.copy(appLanguage = language) }
        scope.launch { prefsRepo.setString(KEY_APP_LANGUAGE, language.name) }
    }

    // -------------------------------------------------------------------------
    // Clear Cache
    // -------------------------------------------------------------------------

    fun clearCache() {
        scope.launch {
            try {
                database.streamVaultQueries.deleteAllMetadataCache()
                _state.update { it.copy(cacheCleared = true) }
                delay(2000)
                _state.update { it.copy(cacheCleared = false) }
            } catch (_: Exception) { }
        }
    }
}

@kotlinx.serialization.Serializable
private data class KodiHostJson(val name: String, val ip: String, val port: Int)
