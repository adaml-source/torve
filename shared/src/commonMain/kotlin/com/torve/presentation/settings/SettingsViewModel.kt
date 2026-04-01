package com.torve.presentation.settings

import com.torve.data.ai.AiProvider
import com.torve.data.debrid.DebridClient
import com.torve.data.kodi.KodiClient
import com.torve.data.kodi.KodiHost
import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.CardPrefs
import com.torve.domain.model.CardStyle
import com.torve.domain.model.CardStylePreset
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.AutoSourceMode
import com.torve.domain.model.DEFAULT_STREAM_GROUPS
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.HdrMode
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import com.torve.data.ratings.OmdbClient
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.defaultTorveWeights
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.sync.SyncRepository
import com.torve.platform.NetworkMonitor
import com.torve.platform.recommendedMaxQuality
import com.torve.presentation.settings.SettingsRefreshNotifier
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

class SettingsViewModel(
    private val debridClient: DebridClient,
    private val traktClient: TraktClient,
    private val simklClient: SimklClient,
    private val kodiClient: KodiClient,
    private val database: TorveDatabase,
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
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var debridPollJob: Job? = null
    private var traktPollJob: Job? = null

    /**
     * Callback to push an integration credential to the backend.
     * Set by the DI layer after construction to avoid circular dependency.
     * Signature: (integrationType, credentials map, displayIdentifier) -> Unit
     */
    var onIntegrationSaved: (suspend (String, Map<String, String>, String?) -> Unit)? = null

    /** Notifies the TMDB client when the user changes the app language. Set by DI. */
    var onLanguageChanged: ((AppLanguage) -> Unit)? = null

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
        const val KEY_AUTO_SOURCE_MODE = "auto_source_mode"
        const val KEY_ALLOW_4K_AUTO = "allow_4k_auto"
        const val KEY_PREFER_COMPATIBLE_CODECS = "prefer_compatible_codecs"
        const val KEY_TV_TRANSPORT_SKIP_ENABLED = "tv_transport_skip_enabled"
        const val KEY_TV_PROGRESSIVE_SKIP_ENABLED = "tv_progressive_skip_enabled"
        const val KEY_TV_SKIP_RESET_WINDOW_MS = "tv_skip_reset_window_ms"
        const val KEY_TV_EXPLICIT_TIMELINE_SCRUB_ENABLED = "tv_explicit_timeline_scrub_enabled"
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

        /** Mask a secret value for display: show last 4 chars only. */
        fun maskSecret(value: String): String {
            if (value.isBlank()) return ""
            if (value.length <= 4) return "••••"
            return "••••" + value.takeLast(4)
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun debridSecretKey(provider: DebridServiceType): IntegrationSecretKey = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }

    private suspend fun buildDebridSyncCredentials(
        provider: DebridServiceType,
        apiKey: String,
    ): Map<String, String> {
        val credentials = linkedMapOf<String, String>()
        if (apiKey.isNotBlank()) {
            credentials["api_key"] = apiKey
        }
        if (provider == DebridServiceType.REAL_DEBRID) {
            integrationSecretStore.get(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN)
                ?.takeIf { it.isNotBlank() }
                ?.let { credentials["refresh_token"] = it }
            integrationSecretStore.get(IntegrationSecretKey.DEBRID_RD_CLIENT_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { credentials["client_id"] = it }
            integrationSecretStore.get(IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET)
                ?.takeIf { it.isNotBlank() }
                ?.let { credentials["client_secret"] = it }
        }
        return credentials
    }

    init {
        loadSavedSettings()
        scope.launch {
            settingsRefreshNotifier.events.collect {
                refreshSettings()
            }
        }
    }

    /** Re-read all settings from disk/secure store. Call after sync import or logout. */
    fun refreshSettings() {
        loadSavedSettings()
    }

    /**
     * One-time migration: if a secret exists in the plaintext preferences DB
     * but not yet in the encrypted secure store, move it there and delete the pref.
     * After this, the pref key is gone — [loadSavedSettings] reads only from secure store.
     */
    private suspend fun migrateSecretPref(prefKey: String, secretKey: IntegrationSecretKey) {
        if (integrationSecretStore.get(secretKey) != null) return
        val legacy = prefsRepo.getString(prefKey)
        if (!legacy.isNullOrBlank()) {
            integrationSecretStore.put(secretKey, legacy)
            prefsRepo.remove(prefKey)
        }
    }

    private fun loadSavedSettings() {
        scope.launch {
            val provider = prefsRepo.getString(KEY_DEBRID_PROVIDER)?.let {
                try { DebridServiceType.valueOf(it) } catch (_: Exception) { null }
            } ?: DebridServiceType.REAL_DEBRID

            // Load per-provider debrid API keys from secure store only.
            // Legacy plaintext fallbacks are migrated once then removed — never
            // used as ongoing fallbacks (prevents stale keys surviving logout).
            val legacyDebridApiKey = prefsRepo.getString(KEY_DEBRID_API_KEY) ?: ""
            val legacySingleKey = integrationSecretStore.get(IntegrationSecretKey.DEBRID_API_KEY)
                ?: legacyDebridApiKey
            val allDebridKeys = mutableMapOf<DebridServiceType, String>()
            for (p in DebridServiceType.entries) {
                val key = integrationSecretStore.get(debridSecretKey(p))
                if (!key.isNullOrBlank()) allDebridKeys[p] = key
            }
            if (allDebridKeys.isEmpty() && legacySingleKey.isNotBlank()) {
                allDebridKeys[provider] = legacySingleKey
                integrationSecretStore.put(debridSecretKey(provider), legacySingleKey)
                integrationSecretStore.remove(IntegrationSecretKey.DEBRID_API_KEY)
                prefsRepo.remove(KEY_DEBRID_API_KEY)
            }
            val apiKey = allDebridKeys[provider] ?: ""
            println("[SettingsLoad] Debrid provider=$provider apiKey=${if (apiKey.isNotBlank()) "${apiKey.length} chars" else "EMPTY"} providers=${allDebridKeys.keys}")
            // Trakt: secure store is authoritative. Migrate legacy pref keys once.
            val traktTokens = traktTokenStore.read()
            migrateSecretPref(KEY_TRAKT_ACCESS_TOKEN, IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
            migrateSecretPref(KEY_TRAKT_REFRESH_TOKEN, IntegrationSecretKey.TRAKT_REFRESH_TOKEN)
            val traktAccessToken = traktTokens?.accessToken
                ?: integrationSecretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN) ?: ""
            val traktRefreshToken = traktTokens?.refreshToken
                ?: integrationSecretStore.get(IntegrationSecretKey.TRAKT_REFRESH_TOKEN) ?: ""

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
            // Simkl: secure store is authoritative. Migrate legacy pref key once.
            migrateSecretPref(KEY_SIMKL_ACCESS_TOKEN, IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
            val simklAccessToken = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN) ?: ""
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
            onLanguageChanged?.invoke(appLanguage)

            val autoPlayEnabled = prefsRepo.getString(KEY_AUTO_PLAY_ENABLED)?.toBooleanStrictOrNull() ?: true
            val autoPlayNextEpisodeEnabled = prefsRepo.getString(KEY_AUTO_PLAY_NEXT_EPISODE)?.toBooleanStrictOrNull() ?: true
            val autoSourceMode = prefsRepo.getString(KEY_AUTO_SOURCE_MODE)?.let {
                try { AutoSourceMode.valueOf(it) } catch (_: Exception) { null }
            } ?: AutoSourceMode.BALANCED
            val allow4kAuto = prefsRepo.getString(KEY_ALLOW_4K_AUTO)?.toBooleanStrictOrNull() ?: false
            val preferCompatibleCodecs = prefsRepo.getString(KEY_PREFER_COMPATIBLE_CODECS)?.toBooleanStrictOrNull() ?: true
            val tvTransportSkipEnabled =
                prefsRepo.getString(KEY_TV_TRANSPORT_SKIP_ENABLED)?.toBooleanStrictOrNull() ?: true
            val tvProgressiveSkipEnabled =
                prefsRepo.getString(KEY_TV_PROGRESSIVE_SKIP_ENABLED)?.toBooleanStrictOrNull() ?: true
            val tvSkipResetWindowMs = prefsRepo.getString(KEY_TV_SKIP_RESET_WINDOW_MS)
                ?.toIntOrNull()
                ?.coerceIn(600, 4_000)
                ?: 1_500
            val tvExplicitTimelineScrubEnabled =
                prefsRepo.getString(KEY_TV_EXPLICIT_TIMELINE_SCRUB_ENABLED)?.toBooleanStrictOrNull() ?: true
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

            // All integration secrets: secure store is the ONLY source of truth.
            // Legacy pref keys are migrated once on first read, then deleted.
            // This prevents stale credentials from surviving logout.
            migrateSecretPref(KEY_CLAUDE_API_KEY, IntegrationSecretKey.CLAUDE_API_KEY)
            migrateSecretPref(KEY_CHATGPT_API_KEY, IntegrationSecretKey.CHATGPT_API_KEY)
            migrateSecretPref(KEY_GEMINI_API_KEY, IntegrationSecretKey.GEMINI_API_KEY)
            migrateSecretPref(KEY_PERPLEXITY_API_KEY, IntegrationSecretKey.PERPLEXITY_API_KEY)
            migrateSecretPref(KEY_DEEPSEEK_API_KEY, IntegrationSecretKey.DEEPSEEK_API_KEY)
            migrateSecretPref(KEY_OMDB_API_KEY, IntegrationSecretKey.OMDB_API_KEY)
            migrateSecretPref(KEY_MDBLIST_API_KEY, IntegrationSecretKey.MDBLIST_API_KEY)
            migrateSecretPref("jellyfin_api_key", IntegrationSecretKey.JELLYFIN_API_KEY)

            val aiProvider = prefsRepo.getString(KEY_AI_PROVIDER)?.let {
                try { AiProvider.valueOf(it) } catch (_: Exception) { null }
            } ?: AiProvider.CLAUDE
            val claudeApiKey = integrationSecretStore.get(IntegrationSecretKey.CLAUDE_API_KEY) ?: ""
            val chatGptApiKey = integrationSecretStore.get(IntegrationSecretKey.CHATGPT_API_KEY) ?: ""
            val geminiApiKey = integrationSecretStore.get(IntegrationSecretKey.GEMINI_API_KEY) ?: ""
            val perplexityApiKey = integrationSecretStore.get(IntegrationSecretKey.PERPLEXITY_API_KEY) ?: ""
            val deepSeekApiKey = integrationSecretStore.get(IntegrationSecretKey.DEEPSEEK_API_KEY) ?: ""
            val omdbApiKey = integrationSecretStore.get(IntegrationSecretKey.OMDB_API_KEY) ?: ""
            val mdblistApiKey = integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY) ?: ""
            val jellyfinServerUrl = prefsRepo.getString(KEY_JELLYFIN_SERVER_URL) ?: ""
            val jellyfinApiKey = integrationSecretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY) ?: ""
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
                    // Clear runtime-only account-linked state that isn't re-read from stores.
                    // After logout, secrets are empty → connected=false → profiles/users gone.
                    debridUser = if (apiKey.isNotBlank()) it.debridUser else null,
                    debridLoading = false,
                    traktAccessToken = traktAccessToken,
                    traktRefreshToken = traktRefreshToken,
                    traktConnected = traktAccessToken.isNotBlank(),
                    traktUser = if (traktAccessToken.isNotBlank()) it.traktUser else null,
                    traktStats = if (traktAccessToken.isNotBlank()) it.traktStats else null,
                    traktScrobbleEnabled = scrobbleEnabled,
                    traktLastSyncTime = traktLastSyncTime,
                    availabilityLastSyncTime = availabilityLastSyncTime,
                    libraryOverlayLastSyncTime = libraryOverlayLastSyncTime,
                    simklClientId = simklClientId,
                    simklAccessToken = simklAccessToken,
                    simklConnected = simklAccessToken.isNotBlank(),
                    simklUser = if (simklAccessToken.isNotBlank()) it.simklUser else null,
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
                    autoSourceMode = autoSourceMode,
                    allow4kAuto = allow4kAuto,
                    preferCompatibleCodecs = preferCompatibleCodecs,
                    tvTransportSkipEnabled = tvTransportSkipEnabled,
                    tvProgressiveSkipEnabled = tvProgressiveSkipEnabled,
                    tvSkipResetWindowMs = tvSkipResetWindowMs,
                    tvExplicitTimelineScrubEnabled = tvExplicitTimelineScrubEnabled,
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
                    omdbValidationResult = null,
                    aiKeyValidationResult = null,
                    mdblistApiKey = mdblistApiKey,
                    jellyfinServerUrl = jellyfinServerUrl,
                    jellyfinApiKey = jellyfinApiKey,
                    jellyfinProfiles = if (jellyfinApiKey.isNotBlank()) it.jellyfinProfiles else emptyList(),
                    selectedJellyfinUserId = if (jellyfinApiKey.isNotBlank()) it.selectedJellyfinUserId else null,
                    jellyfinStatusMessage = null,
                    plexServerUrl = plexServerUrl,
                    plexAccessToken = plexAccessToken,
                    plexConnected = plexAccessToken.isNotBlank(),
                    plexError = null,
                    regionCode = regionCode,
                    ratingPrefs = ratingPrefs,
                    cardStylePresets = cardStylePresets,
                    globalDefaultPresetId = globalDefaultPresetId,
                )
            }

            // Legacy migration block removed — migrateSecretPref() handles all
            // one-time imports from plaintext prefs → secure store above.

            println("[SettingsLoad] debridConnected=${apiKey.isNotBlank()} traktConnected=${traktAccessToken.isNotBlank()} simklConnected=${simklAccessToken.isNotBlank()}")
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
                // Sync to backend
                runCatching {
                    onIntegrationSaved?.invoke(
                        "DEBRID_API_KEY_${provider.name}",
                        buildDebridSyncCredentials(provider, apiKey),
                        provider.name,
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

    private fun pollDebridDevice(code: com.torve.data.debrid.DeviceCodeInfo) {
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
                    // Sync to backend
                    runCatching {
                        onIntegrationSaved?.invoke(
                            "DEBRID_API_KEY_${provider.name}",
                            buildDebridSyncCredentials(provider, result.apiKey),
                            provider.name,
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

    // Debrid validation dedupe: prevent repeated API calls during restore.
    private var debridValidationRunning = false
    private var debridLastValidatedAt = 0L

    private suspend fun verifyDebridConnection() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (debridValidationRunning) {
            println("[DebridInit] Validation skipped (already running)")
            return
        }
        if (now - debridLastValidatedAt < 30_000L && _state.value.debridConnected) {
            println("[DebridInit] Validation skipped (cooldown, already connected)")
            return
        }
        debridValidationRunning = true
        try {
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
            if (result.success) debridLastValidatedAt = now
        } finally {
            debridValidationRunning = false
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

    private fun pollTraktDevice(code: com.torve.data.trakt.TraktDeviceCode) {
        traktPollJob?.cancel()
        traktPollJob = scope.launch {
            _state.update { it.copy(isPollingTrakt = true) }
            var interval = code.interval.toLong()
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(interval * 1000L)
                when (val result = traktClient.pollDeviceToken(code.deviceCode)) {
                    is com.torve.data.trakt.TraktPollResult.Success -> {
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
                        // Sync to backend — Trakt requires both tokens for restore
                        runCatching {
                            onIntegrationSaved?.invoke(
                                "TRAKT_TOKENS",
                                mapOf(
                                    "access_token" to result.tokens.accessToken,
                                    "refresh_token" to result.tokens.refreshToken,
                                ),
                                "Trakt",
                            )
                        }
                        verifyTraktConnection()
                        initialTraktImport()
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.Pending -> { /* Keep polling */ }
                    is com.torve.data.trakt.TraktPollResult.SlowDown -> { interval += 1 }
                    is com.torve.data.trakt.TraktPollResult.Expired -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Code expired. Try again.") }
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.Denied -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Authorization denied.") }
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.AlreadyUsed -> {
                        _state.update { it.copy(isPollingTrakt = false, traktDeviceCode = null, traktError = "Code already used. Try again.") }
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.Error -> {
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

    // Trakt validation dedupe: prevent multiple concurrent/repeated API calls.
    // Cooldown of 30s between validations. One validation at a time.
    private var traktValidationRunning = false
    private var traktLastValidatedAt = 0L
    private val TRAKT_VALIDATION_COOLDOWN_MS = 30_000L

    private suspend fun verifyTraktConnection() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (traktValidationRunning) {
            println("[TraktInit] Validation skipped (already running)")
            return
        }
        if (now - traktLastValidatedAt < TRAKT_VALIDATION_COOLDOWN_MS && _state.value.traktUser != null) {
            println("[TraktInit] Validation skipped (cooldown active, already validated)")
            return
        }
        traktValidationRunning = true
        println("[TraktInit] Validation started")
        try {
            val user = traktClient.getUser(_state.value.traktAccessToken)
            _state.update { it.copy(traktUser = user, traktConnected = true, traktApiStatus = "Online") }
            traktLastValidatedAt = now
            println("[TraktInit] Validation success")
            loadTraktStats()
        } catch (e: Exception) {
            val is429 = e.message?.contains("429") == true || e.message?.contains("rate") == true
            if (is429) {
                // Don't disconnect on rate limit — tokens are likely valid
                traktLastValidatedAt = now // prevent immediate retry
                _state.update { it.copy(traktApiStatus = "Rate limited — will retry later") }
                println("[TraktInit] Rate limited, cooldown until ${now + TRAKT_VALIDATION_COOLDOWN_MS}")
            } else {
                _state.update {
                    it.copy(traktConnected = false, traktError = e.message, traktApiStatus = "Error")
                }
                println("[TraktInit] Validation failed: ${e.message}")
            }
        } finally {
            traktValidationRunning = false
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

    // ── UI-only input updaters (no persistence) ─────────────────
    // These update the displayed value without writing to the secure store.
    // Persistence happens only on explicit Save/Connect/Test actions.

    /** Update AI API key input — UI state only, does NOT persist. */
    fun updateActiveAiApiKeyInput(key: String) {
        val field = when (_state.value.aiProvider) {
            AiProvider.CLAUDE -> _state.value.copy(claudeApiKey = key)
            AiProvider.CHATGPT -> _state.value.copy(chatGptApiKey = key)
            AiProvider.GEMINI -> _state.value.copy(geminiApiKey = key)
            AiProvider.PERPLEXITY -> _state.value.copy(perplexityApiKey = key)
            AiProvider.DEEPSEEK -> _state.value.copy(deepSeekApiKey = key)
        }
        _state.value = field.copy(aiKeyValidationResult = null)
    }

    /** Save AI API key to secure store, then validate. */
    fun saveAndValidateAiApiKey() {
        setActiveAiApiKey(_state.value.activeAiApiKey)
        validateAiApiKey()
    }

    /** Update OMDB API key input — UI state only, does NOT persist. */
    fun updateOmdbApiKeyInput(key: String) {
        _state.update { it.copy(omdbApiKey = key, omdbValidationResult = null) }
    }

    /** Save OMDB API key to secure store, then validate. */
    fun saveAndValidateOmdbApiKey() {
        setOmdbApiKey(_state.value.omdbApiKey)
        validateOmdbApiKey()
    }

    /** Update Jellyfin API key input — UI state only, does NOT persist. */
    fun updateJellyfinApiKeyInput(key: String) {
        _state.update { it.copy(jellyfinApiKey = key) }
    }

    /** Save Jellyfin credentials to secure store, then test connection. */
    fun saveAndTestJellyfinConnection() {
        setJellyfinApiKey(_state.value.jellyfinApiKey)
        testJellyfinConnection()
    }

    /** Update Plex access token input — UI state only, does NOT persist. */
    fun updatePlexAccessTokenInput(token: String) {
        _state.update { it.copy(plexAccessToken = token) }
    }

    /** Save Plex credentials to secure store, then test connection. */
    fun saveAndConnectPlex() {
        setPlexAccessToken(_state.value.plexAccessToken)
        testPlexConnection()
    }

    fun validateAiApiKey() {
        val provider = _state.value.aiProvider
        val key = _state.value.activeAiApiKey
        if (key.isBlank()) {
            _state.update { it.copy(aiKeyValidationResult = "Enter an API key first") }
            return
        }
        _state.update {
            it.copy(
                aiKeyValidating = false,
                aiKeyValidationResult = "AI validation is unavailable in this build",
            )
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
        scope.launch {
            if (key.isBlank()) {
                integrationSecretStore.remove(IntegrationSecretKey.JELLYFIN_API_KEY)
            } else {
                integrationSecretStore.put(IntegrationSecretKey.JELLYFIN_API_KEY, key)
            }
        }
    }

    private val jellyfinService: com.torve.data.integrations.JellyfinLibraryOverlayService?
        get() = (libraryOverlayService as? com.torve.data.integrations.CompositeLibraryOverlayService)?.jellyfin
            ?: (libraryOverlayService as? com.torve.data.integrations.JellyfinLibraryOverlayService)

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

    private val plexService: com.torve.data.integrations.PlexLibraryOverlayService?
        get() = (libraryOverlayService as? com.torve.data.integrations.CompositeLibraryOverlayService)?.plex

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

    private fun pollSimklDevice(code: com.torve.data.simkl.SimklDeviceCode) {
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
                    // Sync to backend
                    runCatching {
                        onIntegrationSaved?.invoke(
                            "SIMKL_ACCESS_TOKEN",
                            mapOf("access_token" to tokens.accessToken),
                            "Simkl",
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

    fun setAutoSourceMode(mode: AutoSourceMode) {
        _state.update { it.copy(autoSourceMode = mode) }
        scope.launch { prefsRepo.setString(KEY_AUTO_SOURCE_MODE, mode.name) }
    }

    fun setAllow4kAuto(enabled: Boolean) {
        _state.update { it.copy(allow4kAuto = enabled) }
        scope.launch { prefsRepo.setString(KEY_ALLOW_4K_AUTO, enabled.toString()) }
    }

    fun setPreferCompatibleCodecs(enabled: Boolean) {
        _state.update { it.copy(preferCompatibleCodecs = enabled) }
        scope.launch { prefsRepo.setString(KEY_PREFER_COMPATIBLE_CODECS, enabled.toString()) }
    }

    fun setTvTransportSkipEnabled(enabled: Boolean) {
        _state.update { it.copy(tvTransportSkipEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_TV_TRANSPORT_SKIP_ENABLED, enabled.toString()) }
    }

    fun setTvProgressiveSkipEnabled(enabled: Boolean) {
        _state.update { it.copy(tvProgressiveSkipEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_TV_PROGRESSIVE_SKIP_ENABLED, enabled.toString()) }
    }

    fun setTvSkipResetWindowMs(windowMs: Int) {
        val sanitized = windowMs.coerceIn(600, 4_000)
        _state.update { it.copy(tvSkipResetWindowMs = sanitized) }
        scope.launch { prefsRepo.setString(KEY_TV_SKIP_RESET_WINDOW_MS, sanitized.toString()) }
    }

    fun setTvExplicitTimelineScrubEnabled(enabled: Boolean) {
        _state.update { it.copy(tvExplicitTimelineScrubEnabled = enabled) }
        scope.launch { prefsRepo.setString(KEY_TV_EXPLICIT_TIMELINE_SCRUB_ENABLED, enabled.toString()) }
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
            autoSourceMode = s.autoSourceMode,
            allow4kAuto = s.allow4kAuto,
            preferCompatibleCodecs = s.preferCompatibleCodecs,
        )
    }

    fun getCurrentNetworkType(): com.torve.platform.NetworkType {
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
        onLanguageChanged?.invoke(language)
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
                database.torveQueries.deleteAllMetadataCache()
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
