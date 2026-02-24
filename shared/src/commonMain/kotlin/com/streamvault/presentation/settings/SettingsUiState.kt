package com.streamvault.presentation.settings

import com.streamvault.data.debrid.DebridUser
import com.streamvault.data.debrid.DeviceCodeInfo
import com.streamvault.data.kodi.KodiHost
import com.streamvault.data.simkl.SimklDeviceCode
import com.streamvault.data.simkl.SimklUser
import com.streamvault.data.trakt.TraktDeviceCode
import com.streamvault.data.trakt.TraktStats
import com.streamvault.data.trakt.TraktUser
import com.streamvault.domain.model.CodecPreference
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.HdrMode
import com.streamvault.data.ai.AiProvider
import com.streamvault.domain.model.RegexPattern
import com.streamvault.domain.model.StreamGroup
import com.streamvault.domain.model.StreamQuality

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
    // Trakt
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val traktUser: TraktUser? = null,
    val traktConnected: Boolean = false,
    val traktError: String? = null,
    val traktLoading: Boolean = false,
    // Trakt device auth
    val traktDeviceCode: TraktDeviceCode? = null,
    val isPollingTrakt: Boolean = false,
    // Trakt enhanced
    val traktStats: TraktStats? = null,
    val traktScrobbleEnabled: Boolean = true,
    val traktApiStatus: String? = null,
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
) {
    val activeAiApiKey: String get() = when (aiProvider) {
        AiProvider.CLAUDE -> claudeApiKey
        AiProvider.CHATGPT -> chatGptApiKey
        AiProvider.GEMINI -> geminiApiKey
        AiProvider.PERPLEXITY -> perplexityApiKey
        AiProvider.DEEPSEEK -> deepSeekApiKey
    }
}
