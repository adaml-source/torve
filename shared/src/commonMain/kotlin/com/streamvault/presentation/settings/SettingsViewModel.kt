package com.streamvault.presentation.settings

import com.streamvault.data.ai.AiProvider
import com.streamvault.data.ai.AiSuggestClient
import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.kodi.KodiClient
import com.streamvault.data.kodi.KodiHost
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.trakt.TraktClient
import com.streamvault.data.trakt.auth.TraktTokenStore
import com.streamvault.data.trakt.repo.TraktSyncRepository
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.integrations.LibraryOverlayService
import com.streamvault.domain.model.CardPrefs
import com.streamvault.domain.model.CardStyle
import com.streamvault.domain.model.CardStylePreset
import com.streamvault.domain.model.HomeSectionConfig
import com.streamvault.domain.model.CodecPreference
import com.streamvault.domain.model.DEFAULT_STREAM_GROUPS
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.HdrMode
import com.streamvault.domain.model.RegexPattern
import com.streamvault.domain.model.StreamGroup
import com.streamvault.data.ratings.OmdbClient
import com.streamvault.domain.model.RatingDisplayPrefs
import com.streamvault.domain.model.RatingSource
import com.streamvault.domain.model.defaultTorveWeights
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.StreamQuality
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.domain.repository.WatchlistRepository
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
import kotlinx.datetime.Clock
import kotlin.random.Random

class SettingsViewModel(
    private val debridClient: DebridClient,
    private val traktClient: TraktClient,
    private val simklClient: SimklClient,
    private val kodiClient: KodiClient,
    private val database: StreamVaultDatabase,
    private val prefsRepo: PreferencesRepository,
    private val syncRepo: SyncRepository,
    private val networkMonitor: NetworkMonitor,
    private val traktTokenStore: TraktTokenStore,
    private val traktSyncRepo: TraktSyncRepository,
    private val watchlistRepo: WatchlistRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val integrationSecretStore: IntegrationSecretStore,
    private val libraryOverlayService: LibraryOverlayService,
    private val omdbClient: OmdbClient,
    private val aiSuggestClient: AiSuggestClient,
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
        const val KEY_TRAKT_LAST_SYNC_TIME = "trakt_last_sync_time"
        const val KEY_AVAILABILITY_LAST_SYNC_TIME = "availability_last_sync_time"
        const val KEY_LIBRARY_OVERLAY_LAST_SYNC_TIME = "library_overlay_last_sync_time"
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
        const val KEY_OMDB_API_KEY = "omdb_api_key"
        const val KEY_MDBLIST_API_KEY = "mdblist_api_key"
        const val KEY_JELLYFIN_SERVER_URL = "jellyfin_server_url"
        const val KEY_PLEX_SERVER_URL = "plex_server_url"
        const val KEY_REGION_CODE = "content_region_code"
        const val KEY_RATING_PREFS = "rating_display_prefs"
        const val KEY_CARD_PREFS = "card_prefs"
        const val KEY_CARD_STYLE_PRESETS = "card_style_presets"
        const val KEY_CARD_DEFAULT_PRESET_ID = "card_style_default_preset_id"
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun debridSecretKey(provider: DebridServiceType): IntegrationSecretKey = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }

    init {
        loadSavedSettings()
    }

    /** Re-read all settings from disk/secure store. Call after sync import. */
    fun refreshSettings() {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            val provider = prefsRepo.getString(KEY_DEBRID_PROVIDER)?.let {
                try { DebridServiceType.valueOf(it) } catch (_: Exception) { null }
            } ?: DebridServiceType.REAL_DEBRID

            val legacyDebridApiKey = prefsRepo.getString(KEY_DEBRID_API_KEY) ?: ""
            val legacySingleKey = integrationSecretStore.get(IntegrationSecretKey.DEBRID_API_KEY)
                ?: legacyDebridApiKey

            // Load per-provider API keys
            val allDebridKeys = mutableMapOf<DebridServiceType, String>()
            for (p in DebridServiceType.entries) {
                val key = integrationSecretStore.get(debridSecretKey(p))
                if (!key.isNullOrBlank()) allDebridKeys[p] = key
            }
            // Migrate legacy single key → per-provider slot
            if (allDebridKeys.isEmpty() && legacySingleKey.isNotBlank()) {
                allDebridKeys[provider] = legacySingleKey
                integrationSecretStore.put(debridSecretKey(provider), legacySingleKey)
                integrationSecretStore.remove(IntegrationSecretKey.DEBRID_API_KEY)
                prefsRepo.remove(KEY_DEBRID_API_KEY)
            }
            val apiKey = allDebridKeys[provider] ?: ""
            val legacyTraktAccessToken = prefsRepo.getString(KEY_TRAKT_ACCESS_TOKEN) ?: ""
            val legacyTraktRefreshToken = prefsRepo.getString(KEY_TRAKT_REFRESH_TOKEN) ?: ""
            val traktTokens = traktTokenStore.read()
            val traktAccessToken = traktTokens?.accessToken ?: legacyTraktAccessToken
            val traktRefreshToken = traktTokens?.refreshToken ?: legacyTraktRefreshToken

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
            val legacySimklAccessToken = prefsRepo.getString(KEY_SIMKL_ACCESS_TOKEN) ?: ""
            val simklAccessToken = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                ?: legacySimklAccessToken
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
            val traktLastSyncTime = prefsRepo.getString(KEY_TRAKT_LAST_SYNC_TIME)?.toLongOrNull()
            val availabilityLastSyncTime = prefsRepo.getString(KEY_AVAILABILITY_LAST_SYNC_TIME)?.toLongOrNull()
            val libraryOverlayLastSyncTime = prefsRepo.getString(KEY_LIBRARY_OVERLAY_LAST_SYNC_TIME)?.toLongOrNull()
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

            val legacyClaudeApiKey = prefsRepo.getString(KEY_CLAUDE_API_KEY) ?: ""
            val claudeApiKey = integrationSecretStore.get(IntegrationSecretKey.CLAUDE_API_KEY)
                ?: legacyClaudeApiKey
            val aiProvider = prefsRepo.getString(KEY_AI_PROVIDER)?.let {
                try { AiProvider.valueOf(it) } catch (_: Exception) { null }
            } ?: AiProvider.CLAUDE
            val legacyChatGptApiKey = prefsRepo.getString(KEY_CHATGPT_API_KEY) ?: ""
            val chatGptApiKey = integrationSecretStore.get(IntegrationSecretKey.CHATGPT_API_KEY)
                ?: legacyChatGptApiKey
            val legacyGeminiApiKey = prefsRepo.getString(KEY_GEMINI_API_KEY) ?: ""
            val geminiApiKey = integrationSecretStore.get(IntegrationSecretKey.GEMINI_API_KEY)
                ?: legacyGeminiApiKey
            val legacyPerplexityApiKey = prefsRepo.getString(KEY_PERPLEXITY_API_KEY) ?: ""
            val perplexityApiKey = integrationSecretStore.get(IntegrationSecretKey.PERPLEXITY_API_KEY)
                ?: legacyPerplexityApiKey
            val legacyDeepSeekApiKey = prefsRepo.getString(KEY_DEEPSEEK_API_KEY) ?: ""
            val deepSeekApiKey = integrationSecretStore.get(IntegrationSecretKey.DEEPSEEK_API_KEY)
                ?: legacyDeepSeekApiKey
            val omdbApiKey = integrationSecretStore.get(IntegrationSecretKey.OMDB_API_KEY)
                ?: prefsRepo.getString(KEY_OMDB_API_KEY) ?: ""
            val legacyMdblistApiKey = prefsRepo.getString(KEY_MDBLIST_API_KEY) ?: ""
            val mdblistApiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: legacyMdblistApiKey
            val jellyfinServerUrl = prefsRepo.getString(KEY_JELLYFIN_SERVER_URL) ?: ""
            val legacyJellyfinApiKey = prefsRepo.getString("jellyfin_api_key") ?: ""
            val plexServerUrl = prefsRepo.getString(KEY_PLEX_SERVER_URL) ?: ""
            val plexAccessToken = integrationSecretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN) ?: ""
            val regionCode = prefsRepo.getString(KEY_REGION_CODE)?.uppercase()?.takeIf { it.length == 2 } ?: "US"
            val ratingPrefs = prefsRepo.getString(KEY_RATING_PREFS)?.let {
                try { jsonParser.decodeFromString<RatingDisplayPrefs>(it) } catch (_: Exception) { null }
            }?.let(::sanitizeRatingPrefs) ?: RatingDisplayPrefs()
            val legacyCardPrefs = prefsRepo.getString(KEY_CARD_PREFS)?.let {
                try { jsonParser.decodeFromString<CardPrefs>(it) } catch (_: Exception) { null }
            } ?: CardPrefs()

            val (cardStylePresets, globalDefaultPresetId) = loadCardStylePresets(
                legacyCardPrefs = legacyCardPrefs,
                ratingPrefs = ratingPrefs,
            )

            _state.update {
                it.copy(
                    debridProvider = provider,
                    debridApiKey = apiKey,
                    debridConnected = apiKey.isNotBlank(),
                    connectedDebridProviders = allDebridKeys,
                    traktAccessToken = traktAccessToken,
                    traktRefreshToken = traktRefreshToken,
                    traktConnected = traktAccessToken.isNotBlank(),
                    traktScrobbleEnabled = scrobbleEnabled,
                    traktLastSyncTime = traktLastSyncTime,
                    availabilityLastSyncTime = availabilityLastSyncTime,
                    libraryOverlayLastSyncTime = libraryOverlayLastSyncTime,
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
                    omdbApiKey = omdbApiKey,
                    mdblistApiKey = mdblistApiKey,
                    jellyfinServerUrl = jellyfinServerUrl,
                    jellyfinApiKey = "",
                    plexServerUrl = plexServerUrl,
                    plexAccessToken = plexAccessToken,
                    plexConnected = plexAccessToken.isNotBlank(),
                    regionCode = regionCode,
                    ratingPrefs = ratingPrefs,
                    cardStylePresets = cardStylePresets,
                    globalDefaultPresetId = globalDefaultPresetId,
                )
            }

            // Migrate legacy keys to IntegrationSecretStore
            if (legacyJellyfinApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.JELLYFIN_API_KEY, legacyJellyfinApiKey)
                prefsRepo.remove("jellyfin_api_key")
            }
            // OMDB: migrate from prefs to secure store if not yet there
            val legacyOmdbApiKey = prefsRepo.getString(KEY_OMDB_API_KEY) ?: ""
            if (legacyOmdbApiKey.isNotBlank() && integrationSecretStore.get(IntegrationSecretKey.OMDB_API_KEY) == null) {
                integrationSecretStore.put(IntegrationSecretKey.OMDB_API_KEY, legacyOmdbApiKey)
                prefsRepo.remove(KEY_OMDB_API_KEY)
            }
            // legacy debrid key migration handled above
            if (legacyTraktAccessToken.isNotBlank() && traktTokens == null) {
                traktTokenStore.write(
                    com.streamvault.data.trakt.TraktTokens(
                        accessToken = legacyTraktAccessToken,
                        refreshToken = legacyTraktRefreshToken,
                        expiresIn = 0,
                        createdAt = 0L,
                    ),
                )
                prefsRepo.remove(KEY_TRAKT_ACCESS_TOKEN)
                prefsRepo.remove(KEY_TRAKT_REFRESH_TOKEN)
            }
            if (legacySimklAccessToken.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, legacySimklAccessToken)
                prefsRepo.remove(KEY_SIMKL_ACCESS_TOKEN)
            }
            if (legacyClaudeApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.CLAUDE_API_KEY, legacyClaudeApiKey)
                prefsRepo.remove(KEY_CLAUDE_API_KEY)
            }
            if (legacyChatGptApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.CHATGPT_API_KEY, legacyChatGptApiKey)
                prefsRepo.remove(KEY_CHATGPT_API_KEY)
            }
            if (legacyGeminiApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.GEMINI_API_KEY, legacyGeminiApiKey)
                prefsRepo.remove(KEY_GEMINI_API_KEY)
            }
            if (legacyPerplexityApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.PERPLEXITY_API_KEY, legacyPerplexityApiKey)
                prefsRepo.remove(KEY_PERPLEXITY_API_KEY)
            }
            if (legacyDeepSeekApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.DEEPSEEK_API_KEY, legacyDeepSeekApiKey)
                prefsRepo.remove(KEY_DEEPSEEK_API_KEY)
            }
            if (legacyMdblistApiKey.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.MDBLIST_API_KEY, legacyMdblistApiKey)
                prefsRepo.remove(KEY_MDBLIST_API_KEY)
            }
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
        debridPollJob?.cancel()
        scope.launch {
            prefsRepo.setString(KEY_DEBRID_PROVIDER, provider.name)
            // Load the target provider's stored key
            val storedKey = integrationSecretStore.get(debridSecretKey(provider)) ?: ""
            _state.update {
                it.copy(
                    debridProvider = provider,
                    debridApiKey = storedKey,
                    debridConnected = storedKey.isNotBlank(),
                    debridUser = null,
                    debridDeviceCode = null,
                    isPollingDebrid = false,
                    debridError = null,
                )
            }
            if (storedKey.isNotBlank()) verifyDebridConnection()
        }
    }

    fun setDebridApiKey(apiKey: String) {
        _state.update { it.copy(debridApiKey = apiKey) }
    }

    fun connectDebridWithApiKey() {
        val apiKey = _state.value.debridApiKey
        if (apiKey.isBlank()) return
        val provider = _state.value.debridProvider

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            val result = debridClient.verifyApiKey(provider, apiKey)
            if (result.success) {
                integrationSecretStore.put(debridSecretKey(provider), apiKey)
                val updated = _state.value.connectedDebridProviders.toMutableMap()
                updated[provider] = apiKey
                _state.update {
                    it.copy(
                        debridConnected = true,
                        debridUser = result.user,
                        debridLoading = false,
                        connectedDebridProviders = updated,
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
        val provider = _state.value.debridProvider
        debridPollJob = scope.launch {
            _state.update { it.copy(isPollingDebrid = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val result = debridClient.pollDeviceAuth(
                    provider,
                    code.deviceCode,
                    code.userCode,
                )
                if (result.done && result.apiKey != null) {
                    integrationSecretStore.put(debridSecretKey(provider), result.apiKey)
                    result.oauthTokens?.let { tokens ->
                        integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, tokens.refreshToken)
                        integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_CLIENT_ID, tokens.clientId)
                        integrationSecretStore.put(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET, tokens.clientSecret)
                        prefsRepo.setString(KEY_DEBRID_RD_EXPIRES_AT, tokens.expiresAt.toString())
                    }
                    val updated = _state.value.connectedDebridProviders.toMutableMap()
                    updated[provider] = result.apiKey
                    _state.update {
                        it.copy(
                            debridApiKey = result.apiKey,
                            debridConnected = true,
                            debridDeviceCode = null,
                            isPollingDebrid = false,
                            connectedDebridProviders = updated,
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
        val provider = _state.value.debridProvider
        scope.launch {
            integrationSecretStore.remove(debridSecretKey(provider))
            if (provider == DebridServiceType.REAL_DEBRID) {
                integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN)
                integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_CLIENT_ID)
                integrationSecretStore.remove(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET)
                prefsRepo.remove(KEY_DEBRID_RD_EXPIRES_AT)
            }
        }
        val updated = _state.value.connectedDebridProviders.toMutableMap()
        updated.remove(provider)
        _state.update {
            it.copy(
                debridApiKey = "",
                debridConnected = false,
                debridUser = null,
                debridDeviceCode = null,
                isPollingDebrid = false,
                connectedDebridProviders = updated,
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
                        traktTokenStore.write(result.tokens)
                        prefsRepo.remove(KEY_TRAKT_ACCESS_TOKEN)
                        prefsRepo.remove(KEY_TRAKT_REFRESH_TOKEN)
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
                        initialTraktImport()
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
            traktTokenStore.clear()
            prefsRepo.remove(KEY_TRAKT_ACCESS_TOKEN)
            prefsRepo.remove(KEY_TRAKT_REFRESH_TOKEN)
            clearTraktCache()
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

    fun syncTraktNow() {
        scope.launch {
            _state.update { it.copy(traktSyncing = true, traktSyncSuccess = false) }
            try {
                initialTraktImport()
                checkTraktApiStatus()
                _state.update { it.copy(traktSyncing = false, traktSyncSuccess = true) }
                delay(3000)
                _state.update { it.copy(traktSyncSuccess = false) }
            } catch (_: Exception) {
                _state.update { it.copy(traktSyncing = false) }
            }
        }
    }

    private suspend fun initialTraktImport() {
        runCatching { watchlistRepo.syncFromTrakt() }
        runCatching { watchProgressRepo.syncFromTrakt() }
        runCatching { watchHistoryRepo.syncFromTrakt() }
        runCatching { traktSyncRepo.syncRatingsFromTrakt() }
        runCatching { traktSyncRepo.flushPendingWrites() }
        val now = Clock.System.now().toEpochMilliseconds()
        prefsRepo.setString(KEY_TRAKT_LAST_SYNC_TIME, now.toString())
        _state.update { it.copy(traktLastSyncTime = now) }
    }

    private suspend fun clearTraktCache() {
        runCatching { watchlistRepo.clear() }
        runCatching { watchProgressRepo.clearAllProgress() }
        runCatching { watchHistoryRepo.clearAll() }
        runCatching { traktSyncRepo.clearLocalData() }
        prefsRepo.remove(KEY_TRAKT_LAST_SYNC_TIME)
        _state.update { it.copy(traktLastSyncTime = null) }
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
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.CLAUDE_API_KEY, key)
            prefsRepo.remove(KEY_CLAUDE_API_KEY)
        }
    }

    fun setChatGptApiKey(key: String) {
        _state.update { it.copy(chatGptApiKey = key) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.CHATGPT_API_KEY, key)
            prefsRepo.remove(KEY_CHATGPT_API_KEY)
        }
    }

    fun setGeminiApiKey(key: String) {
        _state.update { it.copy(geminiApiKey = key) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.GEMINI_API_KEY, key)
            prefsRepo.remove(KEY_GEMINI_API_KEY)
        }
    }

    fun setPerplexityApiKey(key: String) {
        _state.update { it.copy(perplexityApiKey = key) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.PERPLEXITY_API_KEY, key)
            prefsRepo.remove(KEY_PERPLEXITY_API_KEY)
        }
    }

    fun setDeepSeekApiKey(key: String) {
        _state.update { it.copy(deepSeekApiKey = key) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.DEEPSEEK_API_KEY, key)
            prefsRepo.remove(KEY_DEEPSEEK_API_KEY)
        }
    }

    fun setActiveAiApiKey(key: String) {
        _state.update { it.copy(aiKeyValidationResult = null) }
        when (_state.value.aiProvider) {
            AiProvider.CLAUDE -> setClaudeApiKey(key)
            AiProvider.CHATGPT -> setChatGptApiKey(key)
            AiProvider.GEMINI -> setGeminiApiKey(key)
            AiProvider.PERPLEXITY -> setPerplexityApiKey(key)
            AiProvider.DEEPSEEK -> setDeepSeekApiKey(key)
        }
    }

    fun validateAiApiKey() {
        val provider = _state.value.aiProvider
        val key = _state.value.activeAiApiKey
        if (key.isBlank()) {
            _state.update { it.copy(aiKeyValidationResult = "Enter an API key first") }
            return
        }
        _state.update { it.copy(aiKeyValidating = true, aiKeyValidationResult = null) }
        scope.launch {
            try {
                aiSuggestClient.suggest(provider, key, "best sci-fi movies")
                _state.update { it.copy(aiKeyValidating = false, aiKeyValidationResult = "valid") }
            } catch (e: Exception) {
                val msg = e.message?.take(120) ?: "Validation failed"
                _state.update { it.copy(aiKeyValidating = false, aiKeyValidationResult = msg) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // OMDB
    // -------------------------------------------------------------------------

    fun setOmdbApiKey(key: String) {
        _state.update { it.copy(omdbApiKey = key, omdbValidationResult = null) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.OMDB_API_KEY, key)
            prefsRepo.remove(KEY_OMDB_API_KEY)
            // Also persist to preferences so OmdbClient can read it
            prefsRepo.setString(OmdbClient.KEY_OMDB_API_KEY, key)
        }
    }

    fun validateOmdbApiKey() {
        val key = _state.value.omdbApiKey
        if (key.isBlank()) {
            _state.update { it.copy(omdbValidationResult = "Enter an API key first") }
            return
        }
        _state.update { it.copy(omdbValidating = true, omdbValidationResult = null) }
        scope.launch {
            // Test with a well-known IMDb ID (The Shawshank Redemption)
            val result = omdbClient.fetchRatings("tt0111161")
            _state.update {
                it.copy(
                    omdbValidating = false,
                    omdbValidationResult = if (result != null) "valid" else "invalid",
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // MDBList
    // -------------------------------------------------------------------------

    fun setMdblistApiKey(key: String) {
        _state.update { it.copy(mdblistApiKey = key) }
        scope.launch {
            integrationSecretStore.put(IntegrationSecretKey.MDBLIST_API_KEY, key)
            prefsRepo.remove(KEY_MDBLIST_API_KEY)
        }
    }

    fun setJellyfinServerUrl(url: String) {
        _state.update { it.copy(jellyfinServerUrl = url) }
        scope.launch { prefsRepo.setString(KEY_JELLYFIN_SERVER_URL, url) }
    }

    fun setJellyfinApiKey(key: String) {
        _state.update { it.copy(jellyfinApiKey = key) }
    }

    private val jellyfinService: com.streamvault.data.integrations.JellyfinLibraryOverlayService?
        get() = (libraryOverlayService as? com.streamvault.data.integrations.CompositeLibraryOverlayService)?.jellyfin
            ?: (libraryOverlayService as? com.streamvault.data.integrations.JellyfinLibraryOverlayService)

    fun testJellyfinConnection() {
        val server = _state.value.jellyfinServerUrl
        val key = _state.value.jellyfinApiKey
        val service = jellyfinService ?: return
        scope.launch {
            val ok = service.testConnection(serverUrl = server, apiKey = key)
            _state.update {
                it.copy(
                    jellyfinStatusMessage = if (ok) "Connection successful" else "Connection failed",
                )
            }
            if (ok) {
                prefsRepo.setString(KEY_LIBRARY_OVERLAY_LAST_SYNC_TIME, Clock.System.now().toEpochMilliseconds().toString())
                _state.update { s -> s.copy(libraryOverlayLastSyncTime = Clock.System.now().toEpochMilliseconds()) }
                loadJellyfinProfiles()
            }
        }
    }

    fun loadJellyfinProfiles() {
        val service = jellyfinService ?: return
        scope.launch {
            val profiles = service.getUserProfiles()
            val selectedId = service.getSelectedUserId()
            _state.update { it.copy(jellyfinProfiles = profiles, selectedJellyfinUserId = selectedId) }
        }
    }

    fun selectJellyfinProfile(userId: String?) {
        val service = jellyfinService ?: return
        _state.update { it.copy(selectedJellyfinUserId = userId) }
        scope.launch { service.setSelectedUserId(userId) }
    }

    // -------------------------------------------------------------------------
    // Plex
    // -------------------------------------------------------------------------

    fun setPlexServerUrl(url: String) {
        _state.update { it.copy(plexServerUrl = url) }
        scope.launch { prefsRepo.setString(KEY_PLEX_SERVER_URL, url) }
    }

    fun setPlexAccessToken(token: String) {
        _state.update { it.copy(plexAccessToken = token) }
        scope.launch {
            if (token.isBlank()) {
                integrationSecretStore.remove(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
            } else {
                integrationSecretStore.put(IntegrationSecretKey.PLEX_ACCESS_TOKEN, token)
            }
        }
    }

    private val plexService: com.streamvault.data.integrations.PlexLibraryOverlayService?
        get() = (libraryOverlayService as? com.streamvault.data.integrations.CompositeLibraryOverlayService)?.plex

    fun testPlexConnection() {
        val url = _state.value.plexServerUrl
        val token = _state.value.plexAccessToken
        if (url.isBlank() || token.isBlank()) {
            _state.update { it.copy(plexError = "Server URL and access token are required") }
            return
        }
        val service = plexService ?: run {
            _state.update { it.copy(plexError = "Plex service not available") }
            return
        }
        _state.update { it.copy(plexLoading = true, plexError = null) }
        scope.launch {
            val success = service.testConnection(serverUrl = url, apiKey = token)
            _state.update {
                it.copy(
                    plexLoading = false,
                    plexConnected = success,
                    plexError = if (success) null else "Could not connect. Check URL and token.",
                )
            }
        }
    }

    fun disconnectPlex() {
        scope.launch {
            integrationSecretStore.remove(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
            prefsRepo.setString(KEY_PLEX_SERVER_URL, "")
        }
        _state.update {
            it.copy(
                plexServerUrl = "",
                plexAccessToken = "",
                plexConnected = false,
                plexError = null,
            )
        }
    }

    fun setRegionCode(value: String) {
        val normalized = value.trim().uppercase().take(2)
        _state.update { it.copy(regionCode = normalized) }
        if (normalized.length == 2) {
            scope.launch { prefsRepo.setString(KEY_REGION_CODE, normalized) }
        }
    }

    // -------------------------------------------------------------------------
    // Ratings
    // -------------------------------------------------------------------------

    fun updateRatingPrefs(prefs: RatingDisplayPrefs) {
        val sanitized = sanitizeRatingPrefs(prefs)
        _state.update { it.copy(ratingPrefs = sanitized) }
        scope.launch { prefsRepo.setString(KEY_RATING_PREFS, jsonParser.encodeToString(sanitized)) }
        val defaultId = _state.value.globalDefaultPresetId
        val defaultPreset = _state.value.cardStylePresets.firstOrNull { it.presetId == defaultId }
        if (defaultPreset != null) {
            updateCardStylePreset(defaultPreset.presetId, defaultPreset.cardStyle.copy(ratingPrefs = sanitized))
        }
    }

    // -------------------------------------------------------------------------
    // Card Style
    // -------------------------------------------------------------------------

    fun setDefaultCardStylePreset(presetId: String) {
        _state.update { it.copy(globalDefaultPresetId = presetId) }
        scope.launch { prefsRepo.setString(KEY_CARD_DEFAULT_PRESET_ID, presetId) }
    }

    fun updateCardStylePreset(presetId: String, style: CardStyle) {
        val updated = _state.value.cardStylePresets.map { preset ->
            if (preset.presetId == presetId) {
                preset.copy(cardStyle = style, updatedAt = nowMs())
            } else preset
        }
        _state.update { it.copy(cardStylePresets = updated) }
        saveCardStylePresets(updated)
    }

    fun createCardStylePreset(name: String, style: CardStyle, isBuiltIn: Boolean = false): String {
        val id = generatePresetId()
        val now = nowMs()
        val preset = CardStylePreset(
            presetId = id,
            name = name,
            cardStyle = style,
            isBuiltIn = isBuiltIn,
            createdAt = now,
            updatedAt = now,
        )
        val updated = _state.value.cardStylePresets + preset
        _state.update { it.copy(cardStylePresets = updated) }
        saveCardStylePresets(updated)
        return id
    }

    fun duplicateCardStylePreset(presetId: String): String? {
        val preset = _state.value.cardStylePresets.firstOrNull { it.presetId == presetId } ?: return null
        val name = "Copy of ${preset.name}"
        return createCardStylePreset(name, preset.cardStyle.copy())
    }

    fun renameCardStylePreset(presetId: String, name: String) {
        val updated = _state.value.cardStylePresets.map { preset ->
            if (preset.presetId == presetId) preset.copy(name = name, updatedAt = nowMs()) else preset
        }
        _state.update { it.copy(cardStylePresets = updated) }
        saveCardStylePresets(updated)
    }

    fun deleteCardStylePreset(presetId: String) {
        val current = _state.value
        val preset = current.cardStylePresets.firstOrNull { it.presetId == presetId } ?: return
        if (preset.isBuiltIn) return
        if (current.globalDefaultPresetId == presetId) return
        val updated = current.cardStylePresets.filterNot { it.presetId == presetId }
        _state.update { it.copy(cardStylePresets = updated) }
        saveCardStylePresets(updated)
        // Clean up section configs that reference the deleted preset
        scope.launch {
            val saved = try { prefsRepo.getString("home_section_configs") } catch (_: Exception) { null }
            if (saved != null) {
                try {
                    val configs = jsonParser.decodeFromString<List<HomeSectionConfig>>(saved)
                    val cleaned = configs.map { cfg ->
                        if (cfg.presetId == presetId) cfg.copy(presetId = "default") else cfg
                    }
                    prefsRepo.setString("home_section_configs", jsonParser.encodeToString(cleaned))
                } catch (_: Exception) { }
            }
        }
    }

    private fun saveCardStylePresets(presets: List<CardStylePreset>) {
        scope.launch { prefsRepo.setString(KEY_CARD_STYLE_PRESETS, jsonParser.encodeToString(presets)) }
    }

    fun resetAppearanceSettings() {
        val now = nowMs()
        val ratingDefaults = RatingDisplayPrefs()
        val defaultPreset = CardStylePreset(
            presetId = "default",
            name = "Default",
            cardStyle = CardStyle(ratingPrefs = ratingDefaults),
            isBuiltIn = true,
            createdAt = now,
            updatedAt = now,
        )
        val presets = listOf(defaultPreset)
        _state.update {
            it.copy(
                ratingPrefs = ratingDefaults,
                cardStylePresets = presets,
                globalDefaultPresetId = defaultPreset.presetId,
            )
        }
        scope.launch {
            prefsRepo.setString(KEY_RATING_PREFS, jsonParser.encodeToString(ratingDefaults))
            prefsRepo.setString(KEY_CARD_STYLE_PRESETS, jsonParser.encodeToString(presets))
            prefsRepo.setString(KEY_CARD_DEFAULT_PRESET_ID, defaultPreset.presetId)
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
                    integrationSecretStore.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, tokens.accessToken)
                    prefsRepo.remove(KEY_SIMKL_ACCESS_TOKEN)
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
            integrationSecretStore.remove(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
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
        return _state.value.connectedDebridProviders
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

    private suspend fun loadCardStylePresets(
        legacyCardPrefs: CardPrefs,
        ratingPrefs: RatingDisplayPrefs,
    ): Pair<List<CardStylePreset>, String?> {
        val storedPresets = prefsRepo.getString(KEY_CARD_STYLE_PRESETS)?.let { json ->
            try { jsonParser.decodeFromString<List<CardStylePreset>>(json) } catch (_: Exception) { null }
        }
        val storedDefaultId = prefsRepo.getString(KEY_CARD_DEFAULT_PRESET_ID)

        if (!storedPresets.isNullOrEmpty()) {
            val defaultId = storedDefaultId?.takeIf { id -> storedPresets.any { it.presetId == id } }
                ?: storedPresets.first().presetId
            return storedPresets to defaultId
        }

        val now = nowMs()
        val baseStyle = CardStyle(
            size = legacyCardPrefs.size,
            hover = legacyCardPrefs.hover,
            watched = legacyCardPrefs.watched,
            appearance = legacyCardPrefs.appearance,
            ratingPrefs = ratingPrefs,
        )
        val defaultPreset = CardStylePreset(
            presetId = "default",
            name = "Default",
            cardStyle = baseStyle,
            isBuiltIn = true,
            createdAt = now,
            updatedAt = now,
        )
        val presets = listOf(defaultPreset)
        scope.launch {
            prefsRepo.setString(KEY_CARD_STYLE_PRESETS, jsonParser.encodeToString(presets))
            prefsRepo.setString(KEY_CARD_DEFAULT_PRESET_ID, defaultPreset.presetId)
        }
        return presets to defaultPreset.presetId
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun generatePresetId(): String {
        val randomPart = Random.nextInt(1000, 9999)
        return "preset_${nowMs()}_$randomPart"
    }

    private fun sanitizeRatingPrefs(prefs: RatingDisplayPrefs): RatingDisplayPrefs {
        val allSources = RatingSource.entries
        val enabled = prefs.enabledProviders
            .filter { it in allSources }
            .distinct()
        val ordered = (prefs.providerOrder.filter { it in allSources } + allSources)
            .distinct()
        val weights = (defaultTorveWeights() + prefs.torveWeights)
            .filterKeys { it in allSources && it != RatingSource.TORVE }
            .mapValues { (_, weight) -> weight.coerceIn(0, 100) }

        return prefs.copy(
            enabledProviders = enabled,
            providerOrder = ordered,
            maxRatingsOnCard = prefs.maxRatingsOnCard.coerceIn(1, 9),
            torveWeights = weights,
        )
    }
}

@kotlinx.serialization.Serializable
private data class KodiHostJson(val name: String, val ip: String, val port: Int)
