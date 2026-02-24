package com.streamvault.presentation.settings

import com.streamvault.data.ai.AiProvider
import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.kodi.KodiClient
import com.streamvault.data.kodi.KodiHost
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.trakt.TraktClient
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.CodecPreference
import com.streamvault.domain.model.DEFAULT_STREAM_GROUPS
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.HdrMode
import com.streamvault.domain.model.RegexPattern
import com.streamvault.domain.model.StreamGroup
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.StreamQuality
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.sync.SyncRepository
import com.streamvault.platform.NetworkMonitor
import com.streamvault.platform.recommendedMaxQuality
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
    private val syncRepo: SyncRepository,
    private val networkMonitor: NetworkMonitor,
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
        const val KEY_AUTO_PLAY_ENABLED = "auto_play_enabled"
        const val KEY_CODEC_PREFERENCE = "codec_preference"
        const val KEY_HDR_MODE = "hdr_mode"
        const val KEY_AUTO_PLAY_NEXT_EPISODE = "auto_play_next_episode"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
        const val KEY_REGEX_PATTERNS = "regex_patterns"
        const val KEY_STREAM_GROUPS = "stream_groups"
        const val KEY_DEDUPE_RESULTS = "dedupe_results"
        const val KEY_CLAUDE_API_KEY = "claude_api_key"
        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_CHATGPT_API_KEY = "chatgpt_api_key"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_PERPLEXITY_API_KEY = "perplexity_api_key"
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            val provider = prefsRepo.getString(KEY_DEBRID_PROVIDER)?.let {
                try { DebridServiceType.valueOf(it) } catch (_: Exception) { null }
            } ?: DebridServiceType.REAL_DEBRID

            val apiKey = prefsRepo.getString(KEY_DEBRID_API_KEY) ?: ""
            val traktAccessToken = prefsRepo.getString(KEY_TRAKT_ACCESS_TOKEN) ?: ""
            val traktRefreshToken = prefsRepo.getString(KEY_TRAKT_REFRESH_TOKEN) ?: ""

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

            val autoPlayEnabled = prefsRepo.getString(KEY_AUTO_PLAY_ENABLED)?.toBooleanStrictOrNull() ?: true
            val autoPlayNextEpisodeEnabled = prefsRepo.getString(KEY_AUTO_PLAY_NEXT_EPISODE)?.toBooleanStrictOrNull() ?: true
            val lastSyncTime = prefsRepo.getString(KEY_LAST_SYNC_TIME)?.toLongOrNull()
            val codecPreference = prefsRepo.getString(KEY_CODEC_PREFERENCE)?.let {
                try { CodecPreference.valueOf(it) } catch (_: Exception) { null }
            } ?: CodecPreference.HEVC_PREFERRED
            val hdrMode = prefsRepo.getString(KEY_HDR_MODE)?.let {
                try { HdrMode.valueOf(it) } catch (_: Exception) { null }
            } ?: HdrMode.AUTO

            val regexPatterns = prefsRepo.getString(KEY_REGEX_PATTERNS)?.let {
                try { jsonParser.decodeFromString<List<RegexPattern>>(it) } catch (_: Exception) { emptyList() }
            } ?: emptyList()
            val streamGroups = prefsRepo.getString(KEY_STREAM_GROUPS)?.let {
                try { jsonParser.decodeFromString<List<StreamGroup>>(it) } catch (_: Exception) { DEFAULT_STREAM_GROUPS }
            } ?: DEFAULT_STREAM_GROUPS
            val dedupeResults = prefsRepo.getString(KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true

            val claudeApiKey = prefsRepo.getString(KEY_CLAUDE_API_KEY) ?: ""
            val aiProvider = prefsRepo.getString(KEY_AI_PROVIDER)?.let {
                try { AiProvider.valueOf(it) } catch (_: Exception) { null }
            } ?: AiProvider.CLAUDE
            val chatGptApiKey = prefsRepo.getString(KEY_CHATGPT_API_KEY) ?: ""
            val geminiApiKey = prefsRepo.getString(KEY_GEMINI_API_KEY) ?: ""
            val perplexityApiKey = prefsRepo.getString(KEY_PERPLEXITY_API_KEY) ?: ""
            val deepSeekApiKey = prefsRepo.getString(KEY_DEEPSEEK_API_KEY) ?: ""

            _state.update {
                it.copy(
                    debridProvider = provider,
                    debridApiKey = apiKey,
                    debridConnected = apiKey.isNotBlank(),
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
                    autoPlayEnabled = autoPlayEnabled,
                    autoPlayNextEpisodeEnabled = autoPlayNextEpisodeEnabled,
                    codecPreference = codecPreference,
                    hdrMode = hdrMode,
                    lastSyncTime = lastSyncTime,
                    regexPatterns = regexPatterns,
                    streamGroups = streamGroups,
                    dedupeResults = dedupeResults,
                    aiProvider = aiProvider,
                    claudeApiKey = claudeApiKey,
                    chatGptApiKey = chatGptApiKey,
                    geminiApiKey = geminiApiKey,
                    perplexityApiKey = perplexityApiKey,
                    deepSeekApiKey = deepSeekApiKey,
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

    fun startTraktDeviceAuth() {
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
            var interval = code.interval.toLong()
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(interval * 1000L)
                when (val result = traktClient.pollDeviceToken(code.deviceCode)) {
                    is com.streamvault.data.trakt.TraktPollResult.Success -> {
                        prefsRepo.setString(KEY_TRAKT_ACCESS_TOKEN, result.tokens.accessToken)
                        prefsRepo.setString(KEY_TRAKT_REFRESH_TOKEN, result.tokens.refreshToken)
                        _state.update {
                            it.copy(
                                traktAccessToken = result.tokens.accessToken,
                                traktRefreshToken = result.tokens.refreshToken,
                                traktConnected = true,
                                traktDeviceCode = null,
                                isPollingTrakt = false,
                            )
                        }
                        verifyTraktConnection()
                        return@launch
                    }
                    is com.streamvault.data.trakt.TraktPollResult.Pending -> { /* Keep polling */ }
                    is com.streamvault.data.trakt.TraktPollResult.SlowDown -> { interval += 1 }
                    is com.streamvault.data.trakt.TraktPollResult.Expired -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Code expired. Try again.") }
                        return@launch
                    }
                    is com.streamvault.data.trakt.TraktPollResult.Denied -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Authorization denied.") }
                        return@launch
                    }
                    is com.streamvault.data.trakt.TraktPollResult.AlreadyUsed -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Code already used. Try again.") }
                        return@launch
                    }
                    is com.streamvault.data.trakt.TraktPollResult.Error -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = result.message) }
                        return@launch
                    }
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

    // -------------------------------------------------------------------------
    // AI Provider
    // -------------------------------------------------------------------------

    fun setAiProvider(provider: AiProvider) {
        _state.update { it.copy(aiProvider = provider) }
        scope.launch { prefsRepo.setString(KEY_AI_PROVIDER, provider.name) }
    }

    fun setClaudeApiKey(key: String) {
        _state.update { it.copy(claudeApiKey = key) }
        scope.launch { prefsRepo.setString(KEY_CLAUDE_API_KEY, key) }
    }

    fun setChatGptApiKey(key: String) {
        _state.update { it.copy(chatGptApiKey = key) }
        scope.launch { prefsRepo.setString(KEY_CHATGPT_API_KEY, key) }
    }

    fun setGeminiApiKey(key: String) {
        _state.update { it.copy(geminiApiKey = key) }
        scope.launch { prefsRepo.setString(KEY_GEMINI_API_KEY, key) }
    }

    fun setPerplexityApiKey(key: String) {
        _state.update { it.copy(perplexityApiKey = key) }
        scope.launch { prefsRepo.setString(KEY_PERPLEXITY_API_KEY, key) }
    }

    fun setDeepSeekApiKey(key: String) {
        _state.update { it.copy(deepSeekApiKey = key) }
        scope.launch { prefsRepo.setString(KEY_DEEPSEEK_API_KEY, key) }
    }

    fun setActiveAiApiKey(key: String) {
        when (_state.value.aiProvider) {
            AiProvider.CLAUDE -> setClaudeApiKey(key)
            AiProvider.CHATGPT -> setChatGptApiKey(key)
            AiProvider.GEMINI -> setGeminiApiKey(key)
            AiProvider.PERPLEXITY -> setPerplexityApiKey(key)
            AiProvider.DEEPSEEK -> setDeepSeekApiKey(key)
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

    fun setAutoPlayEnabled(enabled: Boolean) {
        _state.update { it.copy(autoPlayEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_AUTO_PLAY_ENABLED, enabled.toString()) }
    }

    fun setCodecPreference(pref: CodecPreference) {
        _state.update { it.copy(codecPreference = pref) }
        scope.launch { prefsRepo.setString(KEY_CODEC_PREFERENCE, pref.name) }
    }

    fun setHdrMode(mode: HdrMode) {
        _state.update { it.copy(hdrMode = mode) }
        scope.launch { prefsRepo.setString(KEY_HDR_MODE, mode.name) }
    }

    fun setAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        _state.update { it.copy(autoPlayNextEpisodeEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_AUTO_PLAY_NEXT_EPISODE, enabled.toString()) }
    }

    fun buildStreamPreferences(): StreamPreferences {
        val s = _state.value
        // Network-aware: cap quality on cellular
        val effectiveMaxQuality = networkMonitor.recommendedMaxQuality(s.maxQuality)
        return StreamPreferences(
            preferredQuality = effectiveMaxQuality,
            maxQuality = effectiveMaxQuality,
            minQuality = s.minQuality,
            hdrEnabled = s.hdrEnabled,
            cachedOnly = s.cachedOnly,
            maxFileSizeBytes = s.maxFileSizeMb?.let { it.toLong() * 1024 * 1024 },
            autoPlayEnabled = s.autoPlayEnabled,
            autoPlayNextEpisodeEnabled = s.autoPlayNextEpisodeEnabled,
            codecPreference = s.codecPreference,
            hdrMode = s.hdrMode,
        )
    }

    fun getCurrentNetworkType(): com.streamvault.platform.NetworkType {
        return networkMonitor.currentNetworkType()
    }

    // -------------------------------------------------------------------------
    // Getters for other ViewModels
    // -------------------------------------------------------------------------

    fun getDebridProvider(): DebridServiceType = _state.value.debridProvider
    fun getDebridApiKey(): String = _state.value.debridApiKey
    fun isDebridConnected(): Boolean = _state.value.debridConnected
    fun getTraktAccessToken(): String = _state.value.traktAccessToken
    fun isTraktConnected(): Boolean = _state.value.traktConnected

    fun getDebridAccounts(): Map<DebridServiceType, String> {
        val s = _state.value
        return if (s.debridConnected && s.debridApiKey.isNotBlank()) {
            mapOf(s.debridProvider to s.debridApiKey)
        } else {
            emptyMap()
        }
    }

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

    fun setDedupeResultsEnabled(enabled: Boolean) {
        _state.update { it.copy(dedupeResults = enabled) }
        scope.launch { prefsRepo.setString(KEY_DEDUPE_RESULTS, enabled.toString()) }
    }

    // -------------------------------------------------------------------------
    // Backup & Sync
    // -------------------------------------------------------------------------

    fun exportBackup(onResult: (String) -> Unit) {
        scope.launch {
            _state.update { it.copy(isSyncing = true, syncError = null, syncSuccess = null) }
            try {
                val jsonStr = syncRepo.exportToJson()
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                prefsRepo.setString(KEY_LAST_SYNC_TIME, now.toString())
                _state.update { it.copy(isSyncing = false, lastSyncTime = now, syncSuccess = "Backup exported") }
                onResult(jsonStr)
                delay(3000)
                _state.update { it.copy(syncSuccess = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isSyncing = false, syncError = e.message ?: "Export failed") }
                delay(3000)
                _state.update { it.copy(syncError = null) }
            }
        }
    }

    fun importBackup(jsonStr: String) {
        scope.launch {
            _state.update { it.copy(isSyncing = true, syncError = null, syncSuccess = null) }
            try {
                val result = syncRepo.importFromJson(jsonStr)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                prefsRepo.setString(KEY_LAST_SYNC_TIME, now.toString())
                val msg = buildString {
                    append("Imported: ")
                    val parts = mutableListOf<String>()
                    if (result.addonsImported > 0) parts += "${result.addonsImported} addons"
                    if (result.preferencesImported > 0) parts += "${result.preferencesImported} preferences"
                    if (result.progressImported > 0) parts += "${result.progressImported} progress entries"
                    if (result.playlistsImported > 0) parts += "${result.playlistsImported} playlists"
                    if (result.favoritesImported > 0) parts += "${result.favoritesImported} favorites"
                    if (parts.isEmpty()) append("no new data")
                    else append(parts.joinToString(", "))
                    if (result.conflicts > 0) append(" (${result.conflicts} kept local)")
                }
                _state.update { it.copy(isSyncing = false, lastSyncTime = now, syncSuccess = msg) }
                // Reload settings to pick up imported preferences
                loadSavedSettings()
                delay(5000)
                _state.update { it.copy(syncSuccess = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isSyncing = false, syncError = e.message ?: "Import failed") }
                delay(3000)
                _state.update { it.copy(syncError = null) }
            }
        }
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

    // -------------------------------------------------------------------------
    // Regex Patterns
    // -------------------------------------------------------------------------

    fun addRegexPattern(label: String = "", pattern: String = "") {
        val updated = _state.value.regexPatterns + RegexPattern(label, pattern)
        _state.update { it.copy(regexPatterns = updated) }
        saveRegexPatterns(updated)
    }

    fun updateRegexPattern(index: Int, pattern: RegexPattern) {
        val updated = _state.value.regexPatterns.toMutableList().also { it[index] = pattern }
        _state.update { it.copy(regexPatterns = updated) }
        saveRegexPatterns(updated)
    }

    fun removeRegexPattern(index: Int) {
        val updated = _state.value.regexPatterns.toMutableList().also { it.removeAt(index) }
        _state.update { it.copy(regexPatterns = updated) }
        saveRegexPatterns(updated)
    }

    fun removeRegexPatternByValue(patternValue: String) {
        val updated = _state.value.regexPatterns.filter { it.pattern != patternValue }
        _state.update { it.copy(regexPatterns = updated) }
        saveRegexPatterns(updated)
    }

    fun toggleRegexPattern(index: Int) {
        val current = _state.value.regexPatterns[index]
        updateRegexPattern(index, current.copy(enabled = !current.enabled))
    }

    private fun saveRegexPatterns(patterns: List<RegexPattern>) {
        scope.launch {
            prefsRepo.setString(KEY_REGEX_PATTERNS, jsonParser.encodeToString(patterns))
        }
    }

    // -------------------------------------------------------------------------
    // Stream Groups
    // -------------------------------------------------------------------------

    fun addStreamGroup(name: String = "", matchPattern: String = "", priority: Int = 99) {
        val updated = _state.value.streamGroups + StreamGroup(name, matchPattern, priority)
        _state.update { it.copy(streamGroups = updated) }
        saveStreamGroups(updated)
    }

    fun updateStreamGroup(index: Int, group: StreamGroup) {
        val updated = _state.value.streamGroups.toMutableList().also { it[index] = group }
        _state.update { it.copy(streamGroups = updated) }
        saveStreamGroups(updated)
    }

    fun removeStreamGroup(index: Int) {
        val updated = _state.value.streamGroups.toMutableList().also { it.removeAt(index) }
        _state.update { it.copy(streamGroups = updated) }
        saveStreamGroups(updated)
    }

    fun toggleStreamGroup(index: Int) {
        val current = _state.value.streamGroups[index]
        updateStreamGroup(index, current.copy(enabled = !current.enabled))
    }

    fun resetStreamGroups() {
        _state.update { it.copy(streamGroups = DEFAULT_STREAM_GROUPS) }
        saveStreamGroups(DEFAULT_STREAM_GROUPS)
    }

    private fun saveStreamGroups(groups: List<StreamGroup>) {
        scope.launch {
            prefsRepo.setString(KEY_STREAM_GROUPS, jsonParser.encodeToString(groups))
        }
    }
}

@kotlinx.serialization.Serializable
private data class KodiHostJson(val name: String, val ip: String, val port: Int)
