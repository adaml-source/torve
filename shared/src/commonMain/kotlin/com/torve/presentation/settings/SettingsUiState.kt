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
import com.torve.domain.player.DesktopPlaybackHotkeys

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
    // True iff the user has at least one enabled addon that declares the
    // "stream" resource — i.e. somewhere a stream URL can come from. Separate
    // from [debridConnected] because Panda (or any future cloud-debrid addon)
    // resolves streams server-side and the app doesn't need a local debrid
    // key for playback in that case.
    val hasStreamAddon: Boolean = false,
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
    // Desktop playback
    val seekStepSeconds: Int = 10,
    val subtitlesEnabledByDefault: Boolean = false,
    val preferredSubtitleLanguage: String = "",
    val preferredAudioLanguage: String = "",
    val rememberVolume: Boolean = true,
    val lastVolume: Int = 100,
    val desktopPlaybackHotkeys: DesktopPlaybackHotkeys = DesktopPlaybackHotkeys(),
    val movieDownloadPath: String = "",
    val showDownloadPath: String = "",
    /**
     * Optional surface-specific download folders for NZB-sourced content.
     * Empty means "block downloads from this surface; prompt the user to
     * pick a folder before queuing". A separate folder per surface lets
     * users keep adult content out of the main Movies library and route
     * sports recordings somewhere distinct.
     */
    val adultDownloadPath: String = "",
    val sportsDownloadPath: String = "",
    /** Live TV recordings folder. Required to be non-empty before the
     *  record button does anything; UI shows a notice when blank. */
    val recordingDownloadPath: String = "",
    val downloadScanFolders: List<String> = emptyList(),
    // Kodi
    val kodiHosts: List<KodiHost> = emptyList(),
    val kodiTestResult: Map<String, Boolean?> = emptyMap(),
    // Theme & Language
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    // Home layout source ("SHARED_WITH_MOBILE" | "DESKTOP_OWN") — desktop-only toggle.
    val homeLayoutSource: String = "SHARED_WITH_MOBILE",
    // LAN library serving (desktop-only). Off by default — server stays
    // unbound until the user opts in. When `lanServingBindToLan` is also
    // true the server binds to the wildcard address so peer devices on
    // the same LAN can pull `/local/manifest` and stream files; otherwise
    // it serves loopback only.
    val lanServingEnabled: Boolean = false,
    val lanServingBindToLan: Boolean = false,
    // Mobile-only: when true (default), LAN-stream playback is refused
    // on cellular networks. Surfaced so the user can flip it on a metered
    // plan; the route chooser reads this via PlaybackRoutePreference.of(...)
    // mobile-data guard.
    val lanPlaybackWifiOnly: Boolean = true,
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
    // OpenSubtitles
    val opensubtitlesApiKey: String = "",
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

    /**
     * True when playback can be attempted: either the user has a local debrid
     * key (torrent/hoster unrestrict path) or at least one installed addon
     * that produces streams (Panda serving direct / cloud-debrid URLs).
     *
     * Use this to gate play buttons. Use [debridConnected] when specifically
     * checking whether the local unrestrict / bulk-download path is available.
     */
    val canPlayStreams: Boolean get() = debridConnected || hasStreamAddon
}
