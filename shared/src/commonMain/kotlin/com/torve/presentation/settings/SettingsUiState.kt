package com.torve.presentation.settings

import com.torve.data.debrid.DebridUser
import com.torve.data.debrid.DeviceCodeInfo
import com.torve.data.kodi.KodiHost
import com.torve.data.simkl.SimklDeviceCode
import com.torve.data.simkl.SimklUser
import com.torve.data.trakt.TraktDeviceCode
import com.torve.data.trakt.TraktStats
import com.torve.data.trakt.TraktUser
import com.torve.domain.model.CodecPreference
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.HdrMode
import com.torve.domain.model.AutoSourceMode
import com.torve.data.ai.AiProvider
import com.torve.domain.model.CardStylePreset
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import com.torve.domain.model.StreamQuality

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    TURKISH("tr", "Türkçe"),
}

data class SettingsUiState(
    // Debrid
    val debridProvider: DebridServiceType = DebridServiceType.REAL_DEBRID,
    val debridApiKey: String = "",
    val debridUser: DebridUser? = null,
    val debridConnected: Boolean = false,
    val debridError: String? = null,
    val debridLoading: Boolean = false,
    // Debrid device auth
    val debridDeviceCode: DeviceCodeInfo? = null,
    val isPollingDebrid: Boolean = false,
    // All connected debrid providers (provider → apiKey)
    val connectedDebridProviders: Map<DebridServiceType, String> = emptyMap(),
    // Trakt
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val traktUser: TraktUser? = null,
    val traktConnected: Boolean = false,
    val traktError: String? = null,
    val traktLoading: Boolean = false,
    val traktSyncing: Boolean = false,
    val traktSyncSuccess: Boolean = false,
    // Trakt device auth
    val traktDeviceCode: TraktDeviceCode? = null,
    val isPollingTrakt: Boolean = false,
    // Trakt enhanced
    val traktStats: TraktStats? = null,
    val traktScrobbleEnabled: Boolean = true,
    val traktApiStatus: String? = null,
    val traktLastSyncTime: Long? = null,
    val availabilityLastSyncTime: Long? = null,
    val libraryOverlayLastSyncTime: Long? = null,
    // SIMKL
    val simklClientId: String = "",
    val simklAccessToken: String = "",
    val simklUser: SimklUser? = null,
    val simklConnected: Boolean = false,
    val simklError: String? = null,
    val simklLoading: Boolean = false,
    val simklDeviceCode: SimklDeviceCode? = null,
    val isPollingSimkl: Boolean = false,
    // AI Provider
    val aiProvider: AiProvider = AiProvider.CLAUDE,
    val claudeApiKey: String = "",
    val chatGptApiKey: String = "",
    val geminiApiKey: String = "",
    val perplexityApiKey: String = "",
    val deepSeekApiKey: String = "",
    val aiKeyValidating: Boolean = false,
    val aiKeyValidationResult: String? = null, // "valid", "invalid", or error message
    // Stream quality & size restrictions
    val maxQuality: StreamQuality = StreamQuality.REMUX_4K,
    val minQuality: StreamQuality = StreamQuality.SD_480P,
    val maxFileSizeMb: Int? = null,
    val cachedOnly: Boolean = true,
    val hdrEnabled: Boolean = false,
    // Playback
    val autoPlayEnabled: Boolean = true,
    val codecPreference: CodecPreference = CodecPreference.HEVC_PREFERRED,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val autoPlayNextEpisodeEnabled: Boolean = true,
    val autoSourceMode: AutoSourceMode = AutoSourceMode.BALANCED,
    val allow4kAuto: Boolean = false,
    val preferCompatibleCodecs: Boolean = true,
    val tvTransportSkipEnabled: Boolean = true,
    val tvProgressiveSkipEnabled: Boolean = true,
    val tvSkipResetWindowMs: Int = 1500,
    val tvExplicitTimelineScrubEnabled: Boolean = true,
    // Kodi
    val kodiHosts: List<KodiHost> = emptyList(),
    val kodiTestResult: Map<String, Boolean?> = emptyMap(),
    // Theme & Language
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    // Cache
    val cacheCleared: Boolean = false,
    // Sync / Backup
    val lastSyncTime: Long? = null,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val syncSuccess: String? = null,
    // Regex Patterns
    val regexPatterns: List<RegexPattern> = emptyList(),
    // Stream Groups
    val streamGroups: List<StreamGroup> = emptyList(),
    // Browse
    val dedupeResults: Boolean = true,
    // OMDB
    val omdbApiKey: String = "",
    val omdbValidating: Boolean = false,
    val omdbValidationResult: String? = null, // "valid", "invalid", or error message
    // MDBList
    val mdblistApiKey: String = "",
    // Integrations — Jellyfin
    val jellyfinServerUrl: String = "",
    val jellyfinApiKey: String = "",
    val jellyfinStatusMessage: String? = null,
    val jellyfinProfiles: List<com.torve.data.integrations.JellyfinProfile> = emptyList(),
    val selectedJellyfinUserId: String? = null,
    // Integrations — Plex
    val plexServerUrl: String = "",
    val plexAccessToken: String = "",
    val plexConnected: Boolean = false,
    val plexLoading: Boolean = false,
    val plexError: String? = null,
    // Region / availability
    val regionCode: String = "US",
    // Ratings
    val ratingPrefs: RatingDisplayPrefs = RatingDisplayPrefs(),
    // Card Style Presets
    val cardStylePresets: List<CardStylePreset> = emptyList(),
    val globalDefaultPresetId: String? = null,
) {
    val activeAiApiKey: String get() = when (aiProvider) {
        AiProvider.CLAUDE -> claudeApiKey
        AiProvider.CHATGPT -> chatGptApiKey
        AiProvider.GEMINI -> geminiApiKey
        AiProvider.PERPLEXITY -> perplexityApiKey
        AiProvider.DEEPSEEK -> deepSeekApiKey
    }
}
