# StreamVault — Full Technical Development Plan
## KMP + Native UI Architecture

---

# TABLE OF CONTENTS

1. Architecture Overview
2. Project Structure & Module Map
3. Kotlin Multiplatform (KMP) Shared Module
4. iOS / tvOS / macOS — SwiftUI Layer
5. Android / Android TV / Fire TV — Jetpack Compose Layer
6. Video Player Integration (libmpv)
7. Stremio Addon Engine — Deep Dive
8. Debrid Service Integration — Deep Dive
9. TMDB & Metadata Pipeline
10. Trakt Integration
11. Data Layer & Persistence
12. Networking & Caching Strategy
13. Cloud Sync Architecture
14. IPTV / M3U Engine
15. App Store Compliance — Technical Implementation
16. CI/CD & Build Pipeline
17. Testing Strategy
18. Detailed Sprint Plan (24 Weeks)
19. Dependency List & Versions
20. File-by-File Module Breakdown

---

# 1. ARCHITECTURE OVERVIEW

```
┌─────────────────────────────────────────────────────────────────┐
│                         PRESENTATION LAYER                       │
│                                                                   │
│   ┌──────────────────────┐     ┌──────────────────────┐          │
│   │     iOS / tvOS       │     │      Android / TV     │          │
│   │     SwiftUI          │     │    Jetpack Compose     │          │
│   │                      │     │                        │          │
│   │  • HomeScreen        │     │  • HomeScreen          │          │
│   │  • SearchView        │     │  • SearchScreen        │          │
│   │  • DetailView        │     │  • DetailScreen        │          │
│   │  • PlayerView        │     │  • PlayerScreen        │          │
│   │  • SettingsView      │     │  • SettingsScreen      │          │
│   │  • AddonConfigView   │     │  • AddonConfigScreen   │          │
│   └──────────┬───────────┘     └──────────┬─────────────┘          │
│              │                             │                       │
│              └──────────┬──────────────────┘                       │
│                         │                                          │
├─────────────────────────┼──────────────────────────────────────────┤
│                         │     SHARED LAYER (KMP)                   │
│                         ▼                                          │
│   ┌─────────────────────────────────────────────────────────┐     │
│   │                   ViewModels / State                     │     │
│   │   HomeViewModel · SearchViewModel · DetailViewModel      │     │
│   │   PlayerViewModel · SettingsViewModel · AddonViewModel   │     │
│   └─────────────────────┬───────────────────────────────────┘     │
│                         │                                          │
│   ┌─────────────────────▼───────────────────────────────────┐     │
│   │                   USE CASES / DOMAIN                     │     │
│   │                                                          │     │
│   │  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │     │
│   │  │ Browse       │ │ Resolve      │ │ Track          │  │     │
│   │  │ Catalogs     │ │ Streams      │ │ Progress       │  │     │
│   │  └──────────────┘ └──────────────┘ └────────────────┘  │     │
│   │  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │     │
│   │  │ Search       │ │ Manage       │ │ Sync           │  │     │
│   │  │ Content      │ │ Addons       │ │ Watchlist      │  │     │
│   │  └──────────────┘ └──────────────┘ └────────────────┘  │     │
│   └─────────────────────┬───────────────────────────────────┘     │
│                         │                                          │
│   ┌─────────────────────▼───────────────────────────────────┐     │
│   │                   DATA / REPOSITORY LAYER                │     │
│   │                                                          │     │
│   │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │     │
│   │  │ Addon    │ │ Debrid   │ │ TMDB     │ │ Trakt    │  │     │
│   │  │ Repo     │ │ Repo     │ │ Repo     │ │ Repo     │  │     │
│   │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │     │
│   │  ┌──────────┐ ┌──────────┐ ┌──────────┐              │     │
│   │  │ Watch    │ │ Settings │ │ IPTV     │              │     │
│   │  │ History  │ │ Repo     │ │ Repo     │              │     │
│   │  └──────────┘ └──────────┘ └──────────┘              │     │
│   └─────────────────────┬───────────────────────────────────┘     │
│                         │                                          │
│   ┌─────────────────────▼───────────────────────────────────┐     │
│   │                   INFRASTRUCTURE                         │     │
│   │                                                          │     │
│   │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │     │
│   │  │ Ktor     │ │ SQLDelight│ │ DataStore│ │ Platform │  │     │
│   │  │ HTTP     │ │ Database │ │ Prefs    │ │ APIs     │  │     │
│   │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │     │
│   └─────────────────────────────────────────────────────────┘     │
│                                                                    │
├────────────────────────────────────────────────────────────────────┤
│                      PLATFORM LAYER (NATIVE)                       │
│                                                                    │
│   ┌──────────────────────┐     ┌──────────────────────┐           │
│   │   iOS Native Bridge  │     │  Android Native Bridge│           │
│   │                      │     │                        │           │
│   │  • MPVPlayerWrapper  │     │  • MPVPlayerWrapper    │           │
│   │  • iCloudSync        │     │  • FirebaseSync        │           │
│   │  • AirPlayManager    │     │  • CastManager         │           │
│   │  • PiPController     │     │  • PiPController       │           │
│   │  • KeychainStorage   │     │  • KeystoreStorage     │           │
│   └──────────────────────┘     └──────────────────────┘           │
└────────────────────────────────────────────────────────────────────┘
```

### Design Principles

1. **Shared logic, native UI** — All business logic, networking, data models, and state management in KMP. All UI in platform-native frameworks.
2. **Clean Architecture** — Domain layer has zero framework dependencies. Data layer implements repository interfaces. Presentation consumes ViewModels.
3. **Unidirectional data flow** — ViewModels emit state, UI renders state, user actions dispatch intents to ViewModels.
4. **Offline-first** — Cache everything. App should browse catalogs and show metadata even without network.
5. **Plugin architecture** — Addons and debrid services are pluggable. Adding a new debrid service = implementing one interface.

---

# 2. PROJECT STRUCTURE & MODULE MAP

```
streamvault/
├── build.gradle.kts                          # Root build config
├── settings.gradle.kts                       # Module declarations
├── gradle.properties                         # KMP config flags
│
├── shared/                                   # KMP Shared Module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/streamvault/
│       │   ├── di/                           # Dependency injection
│       │   │   └── SharedModule.kt
│       │   │
│       │   ├── domain/                       # Pure Kotlin domain
│       │   │   ├── model/                    # Data models
│       │   │   │   ├── MediaItem.kt          # Movie/Show/Episode
│       │   │   │   ├── StreamSource.kt       # Resolved stream
│       │   │   │   ├── Addon.kt              # Addon manifest
│       │   │   │   ├── Catalog.kt            # Content catalog
│       │   │   │   ├── DebridAccount.kt      # Debrid service info
│       │   │   │   ├── WatchProgress.kt      # Playback position
│       │   │   │   ├── UserPreferences.kt    # Settings model
│       │   │   │   └── IptvChannel.kt        # IPTV channel
│       │   │   │
│       │   │   ├── usecase/                  # Business logic
│       │   │   │   ├── BrowseCatalogsUseCase.kt
│       │   │   │   ├── SearchContentUseCase.kt
│       │   │   │   ├── ResolveStreamUseCase.kt
│       │   │   │   ├── GetMediaDetailUseCase.kt
│       │   │   │   ├── TrackProgressUseCase.kt
│       │   │   │   ├── SyncWatchlistUseCase.kt
│       │   │   │   ├── ManageAddonsUseCase.kt
│       │   │   │   ├── AutoSelectStreamUseCase.kt
│       │   │   │   └── GetRecommendationsUseCase.kt
│       │   │   │
│       │   │   └── repository/               # Interfaces only
│       │   │       ├── AddonRepository.kt
│       │   │       ├── DebridRepository.kt
│       │   │       ├── MetadataRepository.kt
│       │   │       ├── TraktRepository.kt
│       │   │       ├── WatchHistoryRepository.kt
│       │   │       ├── SettingsRepository.kt
│       │   │       └── IptvRepository.kt
│       │   │
│       │   ├── data/                         # Repository implementations
│       │   │   ├── addon/
│       │   │   │   ├── AddonRepositoryImpl.kt
│       │   │   │   ├── AddonManifestParser.kt
│       │   │   │   ├── StremioAddonClient.kt # Stremio protocol client
│       │   │   │   ├── CatalogAggregator.kt  # Merge catalogs from N addons
│       │   │   │   └── StreamAggregator.kt   # Merge streams from N addons
│       │   │   │
│       │   │   ├── debrid/
│       │   │   │   ├── DebridRepositoryImpl.kt
│       │   │   │   ├── DebridService.kt      # Interface
│       │   │   │   ├── RealDebridService.kt
│       │   │   │   ├── AllDebridService.kt
│       │   │   │   ├── PremiumizeService.kt
│       │   │   │   ├── TorBoxService.kt
│       │   │   │   └── DebridStreamResolver.kt
│       │   │   │
│       │   │   ├── metadata/
│       │   │   │   ├── MetadataRepositoryImpl.kt
│       │   │   │   ├── TmdbApiClient.kt
│       │   │   │   ├── TmdbModels.kt
│       │   │   │   └── MetadataCache.kt
│       │   │   │
│       │   │   ├── trakt/
│       │   │   │   ├── TraktRepositoryImpl.kt
│       │   │   │   ├── TraktApiClient.kt
│       │   │   │   ├── TraktModels.kt
│       │   │   │   └── TraktScrobbler.kt
│       │   │   │
│       │   │   ├── watchhistory/
│       │   │   │   ├── WatchHistoryRepositoryImpl.kt
│       │   │   │   └── WatchProgressTracker.kt
│       │   │   │
│       │   │   ├── iptv/
│       │   │   │   ├── IptvRepositoryImpl.kt
│       │   │   │   ├── M3uParser.kt
│       │   │   │   ├── EpgParser.kt          # XMLTV EPG
│       │   │   │   └── IptvModels.kt
│       │   │   │
│       │   │   └── settings/
│       │   │       └── SettingsRepositoryImpl.kt
│       │   │
│       │   ├── presentation/                 # Shared ViewModels
│       │   │   ├── home/
│       │   │   │   ├── HomeViewModel.kt
│       │   │   │   └── HomeUiState.kt
│       │   │   ├── search/
│       │   │   │   ├── SearchViewModel.kt
│       │   │   │   └── SearchUiState.kt
│       │   │   ├── detail/
│       │   │   │   ├── DetailViewModel.kt
│       │   │   │   └── DetailUiState.kt
│       │   │   ├── player/
│       │   │   │   ├── PlayerViewModel.kt
│       │   │   │   └── PlayerUiState.kt
│       │   │   ├── addons/
│       │   │   │   ├── AddonViewModel.kt
│       │   │   │   └── AddonUiState.kt
│       │   │   └── settings/
│       │   │       ├── SettingsViewModel.kt
│       │   │       └── SettingsUiState.kt
│       │   │
│       │   └── util/
│       │       ├── CoroutineDispatchers.kt
│       │       ├── Result.kt                 # Sealed result wrapper
│       │       ├── DateTimeUtil.kt
│       │       └── UrlUtil.kt
│       │
│       ├── androidMain/kotlin/com/streamvault/
│       │   ├── di/AndroidModule.kt
│       │   └── platform/
│       │       ├── DatabaseDriverFactory.kt
│       │       ├── DataStoreFactory.kt
│       │       └── PlatformContext.kt
│       │
│       ├── iosMain/kotlin/com/streamvault/
│       │   ├── di/IosModule.kt
│       │   └── platform/
│       │       ├── DatabaseDriverFactory.kt
│       │       ├── DataStoreFactory.kt
│       │       └── PlatformContext.kt
│       │
│       └── commonTest/                       # Shared tests
│           └── kotlin/com/streamvault/
│               ├── addon/AddonEngineTest.kt
│               ├── debrid/DebridResolverTest.kt
│               └── metadata/TmdbClientTest.kt
│
├── androidApp/                               # Android Application
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/streamvault/android/
│           ├── StreamVaultApp.kt             # Application class
│           ├── MainActivity.kt
│           ├── di/AndroidAppModule.kt
│           │
│           ├── ui/
│           │   ├── theme/
│           │   │   ├── Theme.kt
│           │   │   ├── Color.kt
│           │   │   ├── Typography.kt
│           │   │   └── TvTheme.kt            # Android TV theme
│           │   │
│           │   ├── navigation/
│           │   │   ├── NavGraph.kt
│           │   │   └── TvNavGraph.kt
│           │   │
│           │   ├── home/
│           │   │   ├── HomeScreen.kt
│           │   │   ├── CatalogShelf.kt
│           │   │   ├── MediaCard.kt
│           │   │   ├── ContinueWatchingRow.kt
│           │   │   └── TvHomeScreen.kt       # TV-specific layout
│           │   │
│           │   ├── search/
│           │   │   ├── SearchScreen.kt
│           │   │   └── TvSearchScreen.kt
│           │   │
│           │   ├── detail/
│           │   │   ├── DetailScreen.kt
│           │   │   ├── StreamPickerSheet.kt
│           │   │   ├── SeasonSelector.kt
│           │   │   └── TvDetailScreen.kt
│           │   │
│           │   ├── player/
│           │   │   ├── PlayerScreen.kt
│           │   │   ├── PlayerControls.kt
│           │   │   ├── SubtitleOverlay.kt
│           │   │   └── TvPlayerControls.kt
│           │   │
│           │   ├── addons/
│           │   │   ├── AddonScreen.kt
│           │   │   └── AddonConfigSheet.kt
│           │   │
│           │   └── settings/
│           │       ├── SettingsScreen.kt
│           │       ├── DebridConfigScreen.kt
│           │       ├── TraktLoginScreen.kt
│           │       └── SetupWizard.kt
│           │
│           └── player/
│               ├── MpvPlayerWrapper.kt       # libmpv JNI bridge
│               ├── MpvLibrary.kt             # Native library loader
│               ├── CastManager.kt            # Google Cast
│               └── PipController.kt
│
├── iosApp/                                   # iOS Application (Xcode project)
│   ├── StreamVault.xcodeproj/
│   ├── StreamVault/
│   │   ├── App/
│   │   │   ├── StreamVaultApp.swift
│   │   │   └── AppDelegate.swift
│   │   │
│   │   ├── DI/
│   │   │   └── IosAppModule.swift
│   │   │
│   │   ├── UI/
│   │   │   ├── Theme/
│   │   │   │   ├── AppTheme.swift
│   │   │   │   ├── Colors.swift
│   │   │   │   └── Typography.swift
│   │   │   │
│   │   │   ├── Navigation/
│   │   │   │   ├── AppNavigation.swift
│   │   │   │   └── TvNavigation.swift        # tvOS tab bar
│   │   │   │
│   │   │   ├── Home/
│   │   │   │   ├── HomeView.swift
│   │   │   │   ├── CatalogShelf.swift
│   │   │   │   ├── MediaCard.swift
│   │   │   │   ├── ContinueWatchingRow.swift
│   │   │   │   └── TvHomeView.swift          # tvOS variant
│   │   │   │
│   │   │   ├── Search/
│   │   │   │   ├── SearchView.swift
│   │   │   │   └── TvSearchView.swift
│   │   │   │
│   │   │   ├── Detail/
│   │   │   │   ├── DetailView.swift
│   │   │   │   ├── StreamPickerSheet.swift
│   │   │   │   ├── SeasonSelector.swift
│   │   │   │   └── TvDetailView.swift
│   │   │   │
│   │   │   ├── Player/
│   │   │   │   ├── PlayerView.swift
│   │   │   │   ├── PlayerControls.swift
│   │   │   │   ├── SubtitleOverlay.swift
│   │   │   │   └── TvPlayerControls.swift
│   │   │   │
│   │   │   ├── Addons/
│   │   │   │   ├── AddonView.swift
│   │   │   │   └── AddonConfigSheet.swift
│   │   │   │
│   │   │   └── Settings/
│   │   │       ├── SettingsView.swift
│   │   │       ├── DebridConfigView.swift
│   │   │       ├── TraktLoginView.swift
│   │   │       └── SetupWizard.swift
│   │   │
│   │   └── Player/
│   │       ├── MpvPlayerWrapper.swift        # libmpv C bridge
│   │       ├── MpvView.swift                 # Metal/OpenGL render view
│   │       ├── AirPlayManager.swift
│   │       └── PipController.swift
│   │
│   └── StreamVault-tvOS/                     # tvOS target
│       └── TvApp.swift
│
├── libs/                                     # Pre-built native libraries
│   ├── mpv/
│   │   ├── android/
│   │   │   ├── arm64-v8a/libmpv.so
│   │   │   └── x86_64/libmpv.so
│   │   └── ios/
│   │       └── MPVKit.xcframework/
│   └── README.md
│
└── gradle/
    ├── libs.versions.toml                    # Version catalog
    └── wrapper/
```

---

# 3. KOTLIN MULTIPLATFORM (KMP) SHARED MODULE — DEEP DIVE

## 3.1 Core Domain Models

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/model/MediaItem.kt

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { MOVIE, SERIES, EPISODE, LIVE }

@Serializable
data class MediaItem(
    val id: String,                    // IMDB ID (tt1234567) or TMDB ID
    val tmdbId: Int?,
    val imdbId: String?,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,               // IMDB/TMDB rating
    val runtime: Int?,                 // minutes
    val genres: List<String>,
    val cast: List<CastMember>,
    val seasonNumber: Int? = null,     // for episodes
    val episodeNumber: Int? = null,
    val showTitle: String? = null,     // parent show title for episodes
    val releaseDate: String? = null,
    val status: String? = null,        // "Released", "Returning Series", etc.
    val trailerUrl: String? = null,
)

@Serializable
data class CastMember(
    val name: String,
    val character: String?,
    val profileUrl: String?,
)

@Serializable
data class Season(
    val seasonNumber: Int,
    val episodeCount: Int,
    val name: String?,
    val posterUrl: String?,
    val episodes: List<MediaItem>,
)
```

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/model/StreamSource.kt

@Serializable
data class StreamSource(
    val addonName: String,             // Which addon provided this
    val title: String?,                // Display title (e.g., "1080p BluRay REMUX")
    val url: String?,                  // Direct stream URL (after debrid resolution)
    val infoHash: String?,             // Torrent hash (pre-resolution)
    val fileIndex: Int?,               // File index within torrent
    val quality: StreamQuality,
    val size: Long?,                   // File size in bytes
    val codec: String?,                // "HEVC", "AVC", "AV1"
    val audioCodec: String?,           // "DTS-HD MA", "TrueHD Atmos", "AAC"
    val seeds: Int?,                   // Seed count (if torrent)
    val behaviorHints: Map<String, String>?, // Stremio behavior hints
    val debridService: String? = null, // Which debrid resolved this
    val isDebridCached: Boolean = false,
)

enum class StreamQuality {
    REMUX_4K, UHD_4K, QHD_1440P, FHD_1080P, HD_720P, SD_480P, CAM, UNKNOWN;

    companion object {
        fun fromString(s: String): StreamQuality = when {
            s.contains("remux", true) && s.contains("2160", true) -> REMUX_4K
            s.contains("2160") || s.contains("4k", true) || s.contains("uhd", true) -> UHD_4K
            s.contains("1440") -> QHD_1440P
            s.contains("1080") -> FHD_1080P
            s.contains("720") -> HD_720P
            s.contains("480") || s.contains("sd", true) -> SD_480P
            s.contains("cam", true) || s.contains("ts", true) -> CAM
            else -> UNKNOWN
        }
    }
}
```

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/model/Addon.kt

@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val resources: List<String>,       // ["catalog", "stream", "meta", "subtitles"]
    val types: List<String>,           // ["movie", "series"]
    val catalogs: List<AddonCatalog>,
    val idPrefixes: List<String>?,     // ["tt"] for IMDB
    val behaviorHints: Map<String, Any>?,
)

@Serializable
data class AddonCatalog(
    val type: String,                  // "movie", "series"
    val id: String,                    // "top", "trending", etc.
    val name: String?,                 // Display name
    val extra: List<AddonExtra>?,      // Supported filters
)

@Serializable
data class AddonExtra(
    val name: String,                  // "genre", "search", "skip"
    val isRequired: Boolean = false,
    val options: List<String>?,
)

data class InstalledAddon(
    val manifestUrl: String,           // Full addon URL with config
    val manifest: AddonManifest,
    val isEnabled: Boolean = true,
    val priority: Int = 0,             // User-defined ordering
    val installedAt: Long,
)
```

## 3.2 Repository Interfaces

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/repository/AddonRepository.kt

interface AddonRepository {
    // Addon management
    suspend fun installAddon(url: String): Result<InstalledAddon>
    suspend fun removeAddon(id: String)
    suspend fun getInstalledAddons(): List<InstalledAddon>
    suspend fun toggleAddon(id: String, enabled: Boolean)
    suspend fun reorderAddons(orderedIds: List<String>)

    // Content fetching
    suspend fun getCatalogs(type: String): List<CatalogResult>
    suspend fun getCatalogItems(
        addonId: String,
        type: String,
        catalogId: String,
        extra: Map<String, String> = emptyMap()
    ): List<MediaItem>

    // Stream resolution
    suspend fun getStreams(type: String, id: String): List<StreamSource>

    // Subtitles
    suspend fun getSubtitles(type: String, id: String): List<SubtitleSource>

    // Meta enrichment
    suspend fun getMeta(type: String, id: String): MediaItem?
}
```

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/repository/DebridRepository.kt

interface DebridRepository {
    // Account
    suspend fun authenticate(service: DebridServiceType, apiKey: String): Result<DebridAccount>
    suspend fun getActiveAccounts(): List<DebridAccount>
    suspend fun removeAccount(service: DebridServiceType)

    // Stream resolution
    suspend fun resolveStream(
        source: StreamSource,
        preferredService: DebridServiceType? = null
    ): Result<ResolvedStream>

    // Cache checking (batch)
    suspend fun checkCache(
        hashes: List<String>,
        service: DebridServiceType
    ): Map<String, Boolean>

    // Auto-select best stream
    suspend fun resolveBestStream(
        sources: List<StreamSource>,
        preferences: StreamPreferences
    ): Result<ResolvedStream>
}

data class ResolvedStream(
    val url: String,                   // Direct HTTP(S) playback URL
    val service: DebridServiceType,
    val fileName: String?,
    val fileSize: Long?,
    val mimeType: String?,
)

enum class DebridServiceType { REAL_DEBRID, ALL_DEBRID, PREMIUMIZE, TORBOX }

data class StreamPreferences(
    val preferredQuality: StreamQuality = StreamQuality.FHD_1080P,
    val maxQuality: StreamQuality = StreamQuality.REMUX_4K,
    val minQuality: StreamQuality = StreamQuality.HD_720P,
    val preferHdr: Boolean = true,
    val preferDolbyVision: Boolean = true,
    val preferLosslessAudio: Boolean = true,
    val maxFileSizeGb: Double? = null,
    val preferredDebrid: DebridServiceType? = null,
    val onlyCached: Boolean = true,    // Only show debrid-cached results
)
```

## 3.3 Stremio Addon Client Implementation

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/addon/StremioAddonClient.kt

class StremioAddonClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    // Parse addon manifest from URL
    // URL format: https://addon-host.com/manifest.json
    // or with config: https://addon-host.com/{config}/manifest.json
    suspend fun getManifest(baseUrl: String): AddonManifest {
        val manifestUrl = if (baseUrl.endsWith("/manifest.json")) {
            baseUrl
        } else {
            "${baseUrl.trimEnd('/')}/manifest.json"
        }
        val response: String = httpClient.get(manifestUrl).body()
        return json.decodeFromString(response)
    }

    // Fetch catalog items
    // GET {baseUrl}/catalog/{type}/{id}.json
    // GET {baseUrl}/catalog/{type}/{id}/{extra}.json
    suspend fun getCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        extra: Map<String, String> = emptyMap()
    ): List<StremioMetaPreview> {
        val extraPath = if (extra.isNotEmpty()) {
            "/" + extra.entries.joinToString("&") { "${it.key}=${it.value}" }
        } else ""

        val url = "${baseUrl.trimEnd('/')}/catalog/$type/$catalogId$extraPath.json"
        val response: StremioResponse<StremioMetasResponse> = httpClient.get(url).body()
        return response.metas ?: emptyList()
    }

    // Fetch streams for a media item
    // GET {baseUrl}/stream/{type}/{id}.json
    suspend fun getStreams(
        baseUrl: String,
        type: String,
        id: String
    ): List<StremioStream> {
        val url = "${baseUrl.trimEnd('/')}/stream/$type/$id.json"
        return try {
            val response: StremioStreamsResponse = httpClient.get(url).body()
            response.streams ?: emptyList()
        } catch (e: Exception) {
            emptyList() // Gracefully handle addon failures
        }
    }

    // Fetch subtitles
    // GET {baseUrl}/subtitles/{type}/{id}.json
    suspend fun getSubtitles(
        baseUrl: String,
        type: String,
        id: String
    ): List<StremioSubtitle> {
        val url = "${baseUrl.trimEnd('/')}/subtitles/$type/$id.json"
        return try {
            val response: StremioSubtitlesResponse = httpClient.get(url).body()
            response.subtitles ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// Stremio protocol response models
@Serializable
data class StremioMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val links: List<StremioLink>? = null,
)

@Serializable
data class StremioStream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val behaviorHints: JsonObject? = null,
)
```

## 3.4 Stream Aggregator — The Core Engine

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/addon/StreamAggregator.kt

class StreamAggregator(
    private val addonClient: StremioAddonClient,
    private val addonRepository: AddonRepository,
    private val debridRepository: DebridRepository,
) {
    /**
     * The main stream resolution pipeline:
     * 1. Fan out to all installed stream addons in parallel
     * 2. Collect and merge results
     * 3. Deduplicate by info hash
     * 4. Check debrid cache for all hashes (batch)
     * 5. Filter: only cached results (configurable)
     * 6. Sort by quality, size, seeds
     * 7. Return unified list
     */
    suspend fun resolveStreams(
        type: String,
        id: String,
        preferences: StreamPreferences
    ): List<StreamSource> = coroutineScope {

        val addons = addonRepository.getInstalledAddons()
            .filter { it.isEnabled }
            .filter { "stream" in it.manifest.resources }
            .filter { type in it.manifest.types }

        // 1. Fan out requests to all addons in parallel
        val rawStreams: List<StreamSource> = addons.map { addon ->
            async {
                try {
                    withTimeout(10_000) { // 10s timeout per addon
                        val baseUrl = addon.manifestUrl
                            .removeSuffix("/manifest.json")
                            .removeSuffix("/")
                        addonClient.getStreams(baseUrl, type, id)
                            .map { it.toStreamSource(addon.manifest.name) }
                    }
                } catch (e: Exception) {
                    emptyList() // Skip failed addons
                }
            }
        }.awaitAll().flatten()

        // 2. Deduplicate by info hash
        val uniqueStreams = rawStreams
            .distinctBy { it.infoHash ?: it.url ?: it.hashCode() }

        // 3. Batch check debrid cache
        val hashesNeedingCheck = uniqueStreams
            .mapNotNull { it.infoHash }
            .distinct()

        val cacheStatus: Map<String, Boolean> = if (hashesNeedingCheck.isNotEmpty()) {
            val accounts = debridRepository.getActiveAccounts()
            accounts.flatMap { account ->
                try {
                    debridRepository.checkCache(hashesNeedingCheck, account.service)
                        .entries.map { it.key to it.value }
                } catch (e: Exception) {
                    emptyList()
                }
            }.toMap()
        } else emptyMap()

        // 4. Mark cached status and filter
        val enrichedStreams = uniqueStreams.map { stream ->
            if (stream.infoHash != null) {
                stream.copy(isDebridCached = cacheStatus[stream.infoHash] == true)
            } else stream
        }

        // 5. Filter and sort
        enrichedStreams
            .filter { stream ->
                if (preferences.onlyCached && stream.infoHash != null) {
                    stream.isDebridCached
                } else true
            }
            .filter { it.quality >= preferences.minQuality }
            .sortedWith(
                compareByDescending<StreamSource> { it.isDebridCached }
                    .thenByDescending { it.quality }
                    .thenByDescending { it.seeds ?: 0 }
            )
    }
}
```

## 3.5 Real-Debrid Client Implementation

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/debrid/RealDebridService.kt

class RealDebridService(
    private val httpClient: HttpClient,
) : DebridService {

    private val baseUrl = "https://api.real-debrid.com/rest/1.0"
    private var apiToken: String? = null

    override suspend fun authenticate(apiKey: String): DebridAccount {
        apiToken = apiKey
        val user: RdUserResponse = httpClient.get("$baseUrl/user") {
            header("Authorization", "Bearer $apiKey")
        }.body()
        return DebridAccount(
            service = DebridServiceType.REAL_DEBRID,
            username = user.username,
            email = user.email,
            premiumUntil = user.expiration,
            apiKey = apiKey,
        )
    }

    override suspend fun checkCache(hashes: List<String>): Map<String, Boolean> {
        // RD supports batch cache check: /torrents/instantAvailability/{hash1}/{hash2}/...
        // Max 100 hashes per request
        val results = mutableMapOf<String, Boolean>()

        hashes.chunked(100).forEach { chunk ->
            val hashPath = chunk.joinToString("/")
            val response: JsonObject = httpClient.get(
                "$baseUrl/torrents/instantAvailability/$hashPath"
            ) {
                header("Authorization", "Bearer $apiToken")
            }.body()

            chunk.forEach { hash ->
                val hashData = response[hash.lowercase()]?.jsonObject
                val rdEntries = hashData?.get("rd")?.jsonArray
                results[hash] = !rdEntries.isNullOrEmpty()
            }
        }

        return results
    }

    override suspend fun addMagnet(magnetOrHash: String): String {
        val magnet = if (magnetOrHash.startsWith("magnet:")) {
            magnetOrHash
        } else {
            "magnet:?xt=urn:btih:$magnetOrHash"
        }

        val response: RdAddMagnetResponse = httpClient.submitForm(
            "$baseUrl/torrents/addMagnet",
            formParameters = Parameters.build { append("magnet", magnet) }
        ) {
            header("Authorization", "Bearer $apiToken")
        }.body()

        return response.id
    }

    override suspend fun selectFiles(torrentId: String, fileIds: List<Int>) {
        httpClient.submitForm(
            "$baseUrl/torrents/selectFiles/$torrentId",
            formParameters = Parameters.build {
                append("files", fileIds.joinToString(","))
            }
        ) {
            header("Authorization", "Bearer $apiToken")
        }
    }

    override suspend fun getTorrentInfo(torrentId: String): RdTorrentInfo {
        return httpClient.get("$baseUrl/torrents/info/$torrentId") {
            header("Authorization", "Bearer $apiToken")
        }.body()
    }

    override suspend fun getDownloadLink(link: String): String {
        val response: RdUnrestrictResponse = httpClient.submitForm(
            "$baseUrl/unrestrict/link",
            formParameters = Parameters.build { append("link", link) }
        ) {
            header("Authorization", "Bearer $apiToken")
        }.body()

        return response.download
    }

    /**
     * Full resolution pipeline for a cached torrent:
     * 1. Add magnet to RD
     * 2. Select the video file
     * 3. Get torrent info (with download links)
     * 4. Unrestrict the download link
     * 5. Return direct HTTP URL for playback
     */
    override suspend fun resolveStream(infoHash: String, fileIndex: Int?): ResolvedStream {
        // Step 1: Add magnet
        val torrentId = addMagnet(infoHash)

        // Step 2: Get file list and select best video file
        val info = getTorrentInfo(torrentId)
        val videoFile = if (fileIndex != null) {
            info.files.find { it.id == fileIndex }
        } else {
            info.files
                .filter { isVideoFile(it.path) }
                .maxByOrNull { it.bytes }
        } ?: throw Exception("No video file found")

        selectFiles(torrentId, listOf(videoFile.id))

        // Step 3: Wait for file selection to process, then get links
        val updatedInfo = getTorrentInfo(torrentId)
        val downloadLink = updatedInfo.links.firstOrNull()
            ?: throw Exception("No download link available")

        // Step 4: Unrestrict to get direct URL
        val directUrl = getDownloadLink(downloadLink)

        return ResolvedStream(
            url = directUrl,
            service = DebridServiceType.REAL_DEBRID,
            fileName = videoFile.path.substringAfterLast("/"),
            fileSize = videoFile.bytes,
            mimeType = null,
        )
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast(".").lowercase()
        return ext in setOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")
    }
}
```

## 3.6 ViewModels (Shared)

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/presentation/home/HomeViewModel.kt

class HomeViewModel(
    private val browseCatalogs: BrowseCatalogsUseCase,
    private val watchHistory: WatchHistoryRepository,
    private val traktRepo: TraktRepository,
    private val metadataRepo: MetadataRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        loadHomeScreen()
    }

    private fun loadHomeScreen() {
        viewModelScope.launch {
            // Load continue watching
            launch {
                val inProgress = watchHistory.getInProgress()
                _state.update { it.copy(continueWatching = inProgress) }
            }

            // Load Trakt watchlist
            launch {
                val watchlist = traktRepo.getWatchlist()
                _state.update { it.copy(watchlist = watchlist) }
            }

            // Load TMDB trending
            launch {
                val trending = metadataRepo.getTrending("movie")
                _state.update { it.copy(trendingMovies = trending) }
            }

            launch {
                val trending = metadataRepo.getTrending("tv")
                _state.update { it.copy(trendingSeries = trending) }
            }

            // Load addon catalogs
            launch {
                val catalogs = browseCatalogs.execute()
                _state.update { it.copy(
                    addonCatalogs = catalogs,
                    isLoading = false
                ) }
            }
        }
    }

    fun refresh() { loadHomeScreen() }
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val continueWatching: List<WatchProgress> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
    val trendingMovies: List<MediaItem> = emptyList(),
    val trendingSeries: List<MediaItem> = emptyList(),
    val addonCatalogs: List<CatalogResult> = emptyList(),
    val error: String? = null,
)
```

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/presentation/detail/DetailViewModel.kt

class DetailViewModel(
    private val getDetail: GetMediaDetailUseCase,
    private val resolveStreams: ResolveStreamUseCase,
    private val debridRepo: DebridRepository,
    private val traktRepo: TraktRepository,
    private val watchHistory: WatchHistoryRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    fun loadDetail(type: String, id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            // Load metadata
            val detail = getDetail.execute(type, id)
            _state.update { it.copy(
                mediaItem = detail,
                isLoading = false,
            ) }

            // Load seasons if series
            if (type == "series") {
                val seasons = getDetail.getSeasons(id)
                _state.update { it.copy(seasons = seasons) }
            }

            // Check watch progress
            val progress = watchHistory.getProgress(id)
            _state.update { it.copy(watchProgress = progress) }
        }
    }

    fun resolveStreams(type: String, id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isResolvingStreams = true, streams = emptyList()) }

            val prefs = settingsRepo.getStreamPreferences()
            val streams = resolveStreams.execute(type, id, prefs)

            _state.update { it.copy(
                streams = streams,
                isResolvingStreams = false,
            ) }
        }
    }

    fun playStream(stream: StreamSource) {
        viewModelScope.launch {
            _state.update { it.copy(isResolvingPlayback = true) }

            val resolved = if (stream.infoHash != null) {
                debridRepo.resolveStream(stream)
            } else {
                Result.success(ResolvedStream(
                    url = stream.url!!,
                    service = DebridServiceType.REAL_DEBRID,
                    fileName = null,
                    fileSize = stream.size,
                    mimeType = null,
                ))
            }

            resolved.fold(
                onSuccess = { resolvedStream ->
                    _state.update { it.copy(
                        playbackUrl = resolvedStream.url,
                        isResolvingPlayback = false,
                    ) }
                },
                onFailure = { error ->
                    _state.update { it.copy(
                        error = error.message,
                        isResolvingPlayback = false,
                    ) }
                }
            )
        }
    }

    fun autoPlay(type: String, id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isResolvingPlayback = true) }
            val prefs = settingsRepo.getStreamPreferences()
            val result = debridRepo.resolveBestStream(
                resolveStreams.execute(type, id, prefs),
                prefs
            )
            result.fold(
                onSuccess = { _state.update { it.copy(playbackUrl = it.playbackUrl, isResolvingPlayback = false) } },
                onFailure = { _state.update { it.copy(error = it.error, isResolvingPlayback = false) } }
            )
        }
    }
}

data class DetailUiState(
    val isLoading: Boolean = true,
    val mediaItem: MediaItem? = null,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int = 1,
    val streams: List<StreamSource> = emptyList(),
    val isResolvingStreams: Boolean = false,
    val isResolvingPlayback: Boolean = false,
    val playbackUrl: String? = null,
    val watchProgress: WatchProgress? = null,
    val error: String? = null,
)
```

---

# 4. iOS / tvOS / macOS — SWIFTUI LAYER

## 4.1 Consuming KMP ViewModels from Swift

```swift
// iosApp/StreamVault/UI/Home/HomeView.swift

import SwiftUI
import Shared  // KMP framework

struct HomeView: View {
    @StateObject private var viewModel = HomeViewModelWrapper()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                // Continue Watching
                if !viewModel.state.continueWatching.isEmpty {
                    CatalogShelf(
                        title: "Continue Watching",
                        items: viewModel.state.continueWatching.map { $0.mediaItem },
                        style: .landscape
                    )
                }

                // Trending Movies
                if !viewModel.state.trendingMovies.isEmpty {
                    CatalogShelf(
                        title: "Trending Movies",
                        items: viewModel.state.trendingMovies,
                        style: .poster
                    )
                }

                // Trending Series
                if !viewModel.state.trendingSeries.isEmpty {
                    CatalogShelf(
                        title: "Trending Series",
                        items: viewModel.state.trendingSeries,
                        style: .poster
                    )
                }

                // Addon Catalogs
                ForEach(viewModel.state.addonCatalogs, id: \.id) { catalog in
                    CatalogShelf(
                        title: catalog.name,
                        items: catalog.items,
                        style: .poster
                    )
                }
            }
            .padding(.horizontal)
        }
        .refreshable { viewModel.refresh() }
        .navigationTitle("StreamVault")
    }
}

// Wrapper to bridge KMP ViewModel → SwiftUI ObservableObject
class HomeViewModelWrapper: ObservableObject {
    private let vm: HomeViewModel
    @Published var state = HomeUiState()

    init() {
        vm = SharedModule().homeViewModel
        // Collect Kotlin StateFlow into Swift @Published
        FlowCollector(flow: vm.state) { [weak self] newState in
            DispatchQueue.main.async {
                self?.state = newState as! HomeUiState
            }
        }
    }

    func refresh() { vm.refresh() }
}
```

## 4.2 Media Card Component

```swift
// iosApp/StreamVault/UI/Home/MediaCard.swift

struct MediaCard: View {
    let item: MediaItem
    let style: CardStyle

    enum CardStyle {
        case poster    // 2:3 portrait
        case landscape // 16:9 backdrop
        case wide      // 4:3 wider
    }

    var body: some View {
        NavigationLink(destination: DetailView(type: item.type.name, id: item.id)) {
            ZStack(alignment: .bottomLeading) {
                // Poster image
                AsyncImage(url: URL(string: imageUrl)) { image in
                    image.resizable()
                        .aspectRatio(aspectRatio, contentMode: .fill)
                } placeholder: {
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                        .aspectRatio(aspectRatio, contentMode: .fill)
                        .overlay(ProgressView())
                }

                // Gradient overlay
                LinearGradient(
                    colors: [.clear, .black.opacity(0.7)],
                    startPoint: .center,
                    endPoint: .bottom
                )

                // Title + rating
                VStack(alignment: .leading, spacing: 4) {
                    if let rating = item.rating {
                        HStack(spacing: 4) {
                            Image(systemName: "star.fill")
                                .foregroundColor(.yellow)
                                .font(.caption2)
                            Text(String(format: "%.1f", rating))
                                .font(.caption2)
                                .fontWeight(.bold)
                        }
                    }
                    Text(item.title)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .lineLimit(2)
                }
                .foregroundColor(.white)
                .padding(8)
            }
            .frame(width: cardWidth)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    private var imageUrl: String {
        switch style {
        case .poster: return item.posterUrl ?? ""
        case .landscape, .wide: return item.backdropUrl ?? item.posterUrl ?? ""
        }
    }

    private var aspectRatio: CGFloat {
        switch style {
        case .poster: return 2/3
        case .landscape: return 16/9
        case .wide: return 4/3
        }
    }

    private var cardWidth: CGFloat {
        switch style {
        case .poster: return 130
        case .landscape: return 240
        case .wide: return 180
        }
    }
}
```

## 4.3 Stream Picker

```swift
// iosApp/StreamVault/UI/Detail/StreamPickerSheet.swift

struct StreamPickerSheet: View {
    let streams: [StreamSource]
    let isLoading: Bool
    let onSelect: (StreamSource) -> Void
    let onAutoPlay: () -> Void

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Auto-play button
                Button(action: onAutoPlay) {
                    HStack {
                        Image(systemName: "play.fill")
                        Text("Auto-Play Best Stream")
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(12)
                }
                .padding()

                Divider()

                if isLoading {
                    ProgressView("Resolving streams...")
                        .padding(.top, 40)
                } else if streams.isEmpty {
                    Text("No streams found")
                        .foregroundColor(.secondary)
                        .padding(.top, 40)
                } else {
                    List(streams, id: \.hashCode) { stream in
                        StreamRow(stream: stream)
                            .onTapGesture { onSelect(stream) }
                    }
                }
            }
            .navigationTitle("Select Stream")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct StreamRow: View {
    let stream: StreamSource

    var body: some View {
        HStack(spacing: 12) {
            // Quality badge
            Text(stream.quality.name)
                .font(.caption)
                .fontWeight(.bold)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(qualityColor.opacity(0.2))
                .foregroundColor(qualityColor)
                .cornerRadius(6)

            VStack(alignment: .leading, spacing: 2) {
                // Title/filename
                Text(stream.title ?? "Unknown")
                    .font(.subheadline)
                    .lineLimit(1)

                // Metadata row
                HStack(spacing: 8) {
                    if let size = stream.size {
                        Text(formatBytes(size))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    if let codec = stream.codec {
                        Text(codec)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    if let audio = stream.audioCodec {
                        Text(audio)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()

            // Cached indicator
            if stream.isDebridCached {
                Image(systemName: "bolt.fill")
                    .foregroundColor(.green)
                    .font(.caption)
            }

            // Source addon
            Text(stream.addonName)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var qualityColor: Color {
        switch stream.quality {
        case .remux4K, .uhd4K: return .purple
        case .fhd1080P: return .blue
        case .hd720P: return .green
        case .sd480P: return .orange
        default: return .gray
        }
    }
}
```

---

# 5. ANDROID / ANDROID TV — JETPACK COMPOSE LAYER

## 5.1 Home Screen (Phone)

```kotlin
// androidApp/src/main/kotlin/.../ui/home/HomeScreen.kt

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Continue Watching
        if (state.continueWatching.isNotEmpty()) {
            item {
                CatalogShelf(
                    title = "Continue Watching",
                    items = state.continueWatching.map { it.mediaItem },
                    cardStyle = CardStyle.LANDSCAPE,
                    onItemClick = onMediaClick,
                )
            }
        }

        // Trending Movies
        if (state.trendingMovies.isNotEmpty()) {
            item {
                CatalogShelf(
                    title = "Trending Movies",
                    items = state.trendingMovies,
                    cardStyle = CardStyle.POSTER,
                    onItemClick = onMediaClick,
                )
            }
        }

        // Trending Series
        if (state.trendingSeries.isNotEmpty()) {
            item {
                CatalogShelf(
                    title = "Trending Series",
                    items = state.trendingSeries,
                    cardStyle = CardStyle.POSTER,
                    onItemClick = onMediaClick,
                )
            }
        }

        // Addon Catalogs
        items(state.addonCatalogs, key = { it.id }) { catalog ->
            CatalogShelf(
                title = catalog.name,
                items = catalog.items,
                cardStyle = CardStyle.POSTER,
                onItemClick = onMediaClick,
            )
        }
    }
}
```

## 5.2 Android TV Home (Leanback Compose)

```kotlin
// androidApp/src/main/kotlin/.../ui/home/TvHomeScreen.kt

@Composable
fun TvHomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hero banner at top
    val featuredItem = state.trendingMovies.firstOrNull()

    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Featured hero
        featuredItem?.let { item ->
            item {
                FeaturedHeroBanner(
                    item = item,
                    onClick = { onMediaClick(item) }
                )
            }
        }

        // Continue Watching
        if (state.continueWatching.isNotEmpty()) {
            item {
                TvCatalogRow(
                    title = "Continue Watching",
                    items = state.continueWatching.map { it.mediaItem },
                    onItemClick = onMediaClick,
                    onItemFocused = { /* update hero banner */ },
                )
            }
        }

        // Catalog rows
        items(state.addonCatalogs, key = { it.id }) { catalog ->
            TvCatalogRow(
                title = catalog.name,
                items = catalog.items,
                onItemClick = onMediaClick,
                onItemFocused = {},
            )
        }
    }
}

@Composable
fun TvCatalogRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                TvMediaCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onFocused = { onItemFocused(item) },
                )
            }
        }
    }
}
```

---

# 6. VIDEO PLAYER INTEGRATION (libmpv)

## 6.1 MPV on iOS (Swift + C Bridge)

```swift
// iosApp/StreamVault/Player/MpvPlayerWrapper.swift

import Foundation
import MPVKit  // Pre-built xcframework

class MpvPlayerWrapper: ObservableObject {
    private var mpv: OpaquePointer?
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var isBuffering = false

    init() {
        mpv = mpv_create()
        guard mpv != nil else { fatalError("Failed to create MPV instance") }

        // Core options
        setOption("vo", "gpu-next")            // Modern GPU renderer
        setOption("gpu-api", "vulkan")         // Vulkan on iOS (or "opengl" fallback)
        setOption("hwdec", "videotoolbox")     // Apple hardware decoding
        setOption("hwdec-codecs", "all")       // Decode everything in hardware

        // HDR / Dolby Vision
        setOption("target-colorspace-hint", "yes")
        setOption("tone-mapping", "bt.2390")

        // Audio passthrough for AirPlay / HDMI
        setOption("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
        setOption("audio-channels", "auto-safe")

        // Subtitles
        setOption("sub-auto", "fuzzy")
        setOption("sub-font-size", "42")
        setOption("sub-border-size", "2")

        // Network / streaming
        setOption("cache", "yes")
        setOption("cache-secs", "120")          // 2 min buffer
        setOption("demuxer-max-bytes", "150MiB")
        setOption("demuxer-readahead-secs", "60")

        // Initialize
        mpv_initialize(mpv)

        // Start property observation
        observeProperty("time-pos", MPV_FORMAT_DOUBLE) { [weak self] value in
            self?.currentTime = value as? Double ?? 0
        }
        observeProperty("duration", MPV_FORMAT_DOUBLE) { [weak self] value in
            self?.duration = value as? Double ?? 0
        }
        observeProperty("pause", MPV_FORMAT_FLAG) { [weak self] value in
            self?.isPlaying = !(value as? Bool ?? true)
        }
        observeProperty("paused-for-cache", MPV_FORMAT_FLAG) { [weak self] value in
            self?.isBuffering = (value as? Bool ?? false)
        }
    }

    func loadUrl(_ url: String) {
        command(["loadfile", url])
    }

    func play() { setProperty("pause", false) }
    func pause() { setProperty("pause", true) }
    func seek(to seconds: Double) { command(["seek", "\(seconds)", "absolute"]) }
    func seekRelative(_ seconds: Double) { command(["seek", "\(seconds)", "relative"]) }

    func setSubtitleTrack(_ index: Int) { setProperty("sid", index) }
    func setAudioTrack(_ index: Int) { setProperty("aid", index) }
    func setPlaybackSpeed(_ speed: Double) { setProperty("speed", speed) }

    func getAudioTracks() -> [TrackInfo] { /* query mpv track-list */ }
    func getSubtitleTracks() -> [TrackInfo] { /* query mpv track-list */ }

    // Metal render view for SwiftUI
    func createRenderView() -> MpvMetalView {
        return MpvMetalView(mpv: mpv!)
    }

    private func setOption(_ name: String, _ value: String) {
        mpv_set_option_string(mpv, name, value)
    }

    private func setProperty(_ name: String, _ value: Any) {
        // Type-specific mpv_set_property calls
    }

    private func command(_ args: [String]) {
        var cArgs = args.map { strdup($0) }
        cArgs.append(nil)
        mpv_command(mpv, &cArgs)
        cArgs.compactMap { $0 }.forEach { free($0) }
    }

    private func observeProperty(_ name: String, _ format: mpv_format, handler: @escaping (Any?) -> Void) {
        // Register mpv property observer
    }

    deinit {
        mpv_terminate_destroy(mpv)
    }
}
```

## 6.2 MPV on Android (JNI Bridge)

```kotlin
// androidApp/src/main/kotlin/.../player/MpvPlayerWrapper.kt

class MpvPlayerWrapper(private val context: Context) {

    private var mpvHandle: Long = 0
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        System.loadLibrary("mpv")
        mpvHandle = nativeCreate()

        // Same options as iOS
        nativeSetOption(mpvHandle, "vo", "gpu-next")
        nativeSetOption(mpvHandle, "gpu-api", "opengl")    // OpenGL ES on Android
        nativeSetOption(mpvHandle, "hwdec", "mediacodec")  // Android HW decoding
        nativeSetOption(mpvHandle, "hwdec-codecs", "all")

        // HDR
        nativeSetOption(mpvHandle, "target-colorspace-hint", "yes")

        // Audio passthrough
        nativeSetOption(mpvHandle, "audio-spdif", "ac3,eac3,dts,dts-hd,truehd")

        // Network buffering
        nativeSetOption(mpvHandle, "cache", "yes")
        nativeSetOption(mpvHandle, "cache-secs", "120")
        nativeSetOption(mpvHandle, "demuxer-max-bytes", "150MiB")

        nativeInitialize(mpvHandle)
    }

    fun loadUrl(url: String) = nativeCommand(mpvHandle, arrayOf("loadfile", url))
    fun play() = nativeSetPropertyBool(mpvHandle, "pause", false)
    fun pause() = nativeSetPropertyBool(mpvHandle, "pause", true)
    fun seek(seconds: Double) = nativeCommand(mpvHandle, arrayOf("seek", "$seconds", "absolute"))

    fun attachSurface(surface: Surface) = nativeAttachSurface(mpvHandle, surface)
    fun detachSurface() = nativeDetachSurface(mpvHandle)

    // JNI native methods
    private external fun nativeCreate(): Long
    private external fun nativeInitialize(handle: Long)
    private external fun nativeSetOption(handle: Long, name: String, value: String)
    private external fun nativeCommand(handle: Long, args: Array<String>)
    private external fun nativeSetPropertyBool(handle: Long, name: String, value: Boolean)
    private external fun nativeAttachSurface(handle: Long, surface: Surface)
    private external fun nativeDetachSurface(handle: Long)
    private external fun nativeDestroy(handle: Long)
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isBuffering: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
)
```

## 6.3 Pre-built MPV Libraries

For iOS, use **MPVKit** — a community-maintained xcframework:
- Repository: https://github.com/nicklama/mpv-ios-build (or similar)
- Includes: libmpv, ffmpeg, libass (subtitles), all codecs
- Dolby Vision requires specific ffmpeg build flags

For Android, use **mpv-android** libs:
- Repository: https://github.com/mpv-android/mpv-android
- Pre-built .so files for arm64-v8a, x86_64
- Build script: https://github.com/nicklama/mpv-android-build

**Build from source if you need:**
- Custom codec support (AV1 hardware decode on newer chips)
- Dolby Vision Profile 5/8 handling
- Specific ffmpeg patches

---

# 7. DATA LAYER & PERSISTENCE

## 7.1 SQLDelight Schema

```sql
-- shared/src/commonMain/sqldelight/com/streamvault/db/StreamVault.sq

-- Installed addons
CREATE TABLE addon (
    manifest_url TEXT NOT NULL PRIMARY KEY,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    description TEXT,
    logo TEXT,
    manifest_json TEXT NOT NULL,  -- Full manifest as JSON
    is_enabled INTEGER NOT NULL DEFAULT 1,
    priority INTEGER NOT NULL DEFAULT 0,
    installed_at INTEGER NOT NULL
);

getAllAddons:
SELECT * FROM addon WHERE is_enabled = 1 ORDER BY priority ASC;

insertAddon:
INSERT OR REPLACE INTO addon VALUES ?;

deleteAddon:
DELETE FROM addon WHERE manifest_url = ?;

-- Watch history / progress
CREATE TABLE watch_progress (
    media_id TEXT NOT NULL PRIMARY KEY,
    media_type TEXT NOT NULL,
    title TEXT NOT NULL,
    poster_url TEXT,
    backdrop_url TEXT,
    position_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    season_number INTEGER,
    episode_number INTEGER,
    show_title TEXT,
    updated_at INTEGER NOT NULL
);

getInProgress:
SELECT * FROM watch_progress
WHERE position_ms > 0
AND CAST(position_ms AS REAL) / duration_ms < 0.9
ORDER BY updated_at DESC
LIMIT 20;

upsertProgress:
INSERT OR REPLACE INTO watch_progress VALUES ?;

-- Metadata cache
CREATE TABLE metadata_cache (
    id TEXT NOT NULL PRIMARY KEY,
    type TEXT NOT NULL,
    json_data TEXT NOT NULL,
    cached_at INTEGER NOT NULL
);

getCachedMeta:
SELECT json_data FROM metadata_cache
WHERE id = ? AND cached_at > ?;  -- TTL check

insertCachedMeta:
INSERT OR REPLACE INTO metadata_cache VALUES ?;

-- Debrid accounts (encrypted API keys stored in Keychain/Keystore)
CREATE TABLE debrid_account (
    service TEXT NOT NULL PRIMARY KEY,
    username TEXT,
    email TEXT,
    premium_until TEXT,
    is_active INTEGER NOT NULL DEFAULT 1
);

-- IPTV playlists
CREATE TABLE iptv_playlist (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    epg_url TEXT,
    last_updated INTEGER
);

-- User preferences (key-value for flexibility)
CREATE TABLE preference (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);
```

---

# 8. DEPENDENCY VERSIONS (gradle/libs.versions.toml)

```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.0"
kmp = "2.1.0"
ktor = "3.0.3"
sqldelight = "2.0.2"
kotlinx-coroutines = "1.9.0"
kotlinx-serialization = "1.7.3"
kotlinx-datetime = "0.6.1"
koin = "4.0.2"
compose-bom = "2024.12.01"
compose-tv = "1.0.0-beta01"
lifecycle = "2.8.7"
coil = "3.0.4"
datastore = "1.1.1"

[libraries]
# KMP
ktor-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-logging = { module = "io.ktor:ktor-client-logging", version.ref = "ktor" }

sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }

kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }

koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }

# Android
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-tv-material = { module = "androidx.tv:tv-material", version.ref = "compose-tv" }
compose-tv-foundation = { module = "androidx.tv:tv-foundation", version.ref = "compose-tv" }
lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
lifecycle-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
datastore = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

---

# 9. DETAILED SPRINT PLAN (24 WEEKS)

## PHASE 0 — Foundation (Weeks 1–4)

### Sprint 1 (Week 1–2): Project Setup & TMDB
- [ ] Initialize KMP project with Gradle version catalog
- [ ] Configure shared module with commonMain/androidMain/iosMain
- [ ] Set up Ktor HTTP client with JSON serialization
- [ ] Implement TmdbApiClient (trending, search, detail, seasons)
- [ ] Create domain models: MediaItem, Season, CastMember
- [ ] Write unit tests for TMDB client
- [ ] Set up SQLDelight with metadata_cache table
- [ ] Basic Android app shell with Jetpack Compose navigation
- [ ] Basic iOS app shell with SwiftUI navigation

### Sprint 2 (Week 3–4): Browsing UI & Player Proof-of-Concept
- [ ] Home screen with TMDB trending shelves (Android + iOS)
- [ ] Search screen with TMDB search (Android + iOS)
- [ ] Detail screen with metadata display (Android + iOS)
- [ ] **CRITICAL: MPV integration proof-of-concept**
  - [ ] Build/obtain libmpv for iOS (MPVKit xcframework)
  - [ ] Build/obtain libmpv for Android (arm64 .so)
  - [ ] Play a test HTTP video URL on iOS
  - [ ] Play a test HTTP video URL on Android
  - [ ] Verify hardware decoding works
- [ ] Basic player UI with play/pause/seek

## PHASE 1 — Core Engine (Weeks 5–10)

### Sprint 3 (Week 5–6): Stremio Addon Engine
- [ ] StremioAddonClient: manifest parsing
- [ ] StremioAddonClient: catalog fetching
- [ ] StremioAddonClient: stream fetching
- [ ] StremioAddonClient: subtitle fetching
- [ ] AddonRepository: install/remove/list/toggle
- [ ] CatalogAggregator: merge catalogs from multiple addons
- [ ] StreamAggregator: fan-out stream resolution
- [ ] Addon management UI (add by URL, toggle, reorder)
- [ ] SQLDelight addon table
- [ ] Unit tests for addon engine

### Sprint 4 (Week 7–8): Debrid Integration
- [ ] DebridService interface
- [ ] RealDebridService: auth, cache check, resolve
- [ ] AllDebridService: auth, cache check, resolve
- [ ] DebridStreamResolver: batch cache check + resolution pipeline
- [ ] Stream picker UI (quality badges, size, codec info, cached indicator)
- [ ] AutoSelectStreamUseCase: pick best stream based on preferences
- [ ] Debrid account management UI (add API key, show status)
- [ ] Secure API key storage (Keychain on iOS, Keystore on Android)
- [ ] Integration tests with Real-Debrid sandbox

### Sprint 5 (Week 9–10): Tracking & Progress
- [ ] Trakt API client: OAuth2 flow, scrobbling, watchlist, history
- [ ] TraktScrobbler: auto-report watching/paused/stopped
- [ ] WatchProgressTracker: save position to local DB
- [ ] Continue Watching shelf on home screen
- [ ] Up Next logic (next episode auto-queue)
- [ ] Trakt watchlist sync to home screen shelf
- [ ] Cross-device sync foundation (iCloud for iOS, DataStore for Android)
- [ ] SQLDelight watch_progress table

## PHASE 2 — Polish & Platform (Weeks 11–16)

### Sprint 6 (Week 11–12): Player Polish
- [ ] Full player controls (speed, audio track, subtitle track)
- [ ] Subtitle rendering with styling options (size, color, position)
- [ ] HDR/Dolby Vision validation on real content
- [ ] DTS/TrueHD passthrough testing (AirPlay + HDMI)
- [ ] Picture-in-Picture (iOS + Android)
- [ ] Background audio continuation
- [ ] Player gestures (swipe seek, volume, brightness on mobile)
- [ ] Resume from last position

### Sprint 7 (Week 13–14): TV Platforms
- [ ] Apple TV (tvOS) UI adaptation
  - [ ] Focus-based navigation
  - [ ] Siri Remote gestures in player
  - [ ] Top Shelf extension
- [ ] Android TV / Fire TV UI adaptation
  - [ ] D-pad navigation
  - [ ] Leanback Compose catalog rows
  - [ ] Channel/program data for Android TV home
- [ ] TV player controls (simplified, remote-friendly)
- [ ] Test on physical devices: Apple TV 4K, Fire TV Stick 4K, Chromecast w/ Google TV

### Sprint 8 (Week 15–16): Setup Wizard & UX Polish
- [ ] First-run setup wizard:
  1. Welcome screen
  2. Connect Trakt (optional)
  3. Connect debrid service (API key input)
  4. Auto-suggest popular addons (Torrentio, MediaFusion, etc.)
  5. Confirm and go to home screen
- [ ] AirPlay output support
- [ ] Chromecast / Google Cast output support
- [ ] Error handling and retry UX throughout app
- [ ] Loading states, empty states, error states for all screens
- [ ] Dark/OLED theme finalization
- [ ] App icon and branding

## PHASE 3 — Beta (Weeks 17–20)

### Sprint 9 (Week 17–18): Quality & Performance
- [ ] Memory profiling (image caching, player memory)
- [ ] Startup time optimization (< 2 seconds to home screen)
- [ ] Catalog loading optimization (progressive rendering)
- [ ] Stream resolution time optimization (< 3 seconds average)
- [ ] Crash reporting integration (Sentry or Firebase Crashlytics)
- [ ] Analytics integration (PostHog)
- [ ] Accessibility audit (VoiceOver on iOS, TalkBack on Android)
- [ ] Localization framework setup (English, German, Spanish, French)

### Sprint 10 (Week 19–20): Beta Distribution
- [ ] TestFlight setup and closed beta (50-100 testers)
- [ ] Google Play internal/closed beta track
- [ ] Community Discord server launch
- [ ] Beta feedback collection system
- [ ] Bug triage and critical fix cycle
- [ ] App Store listing draft (screenshots, description, keywords)
- [ ] Privacy policy and terms of service
- [ ] App Store review preparation (compliance review)

## PHASE 4 — Launch (Weeks 21–24)

### Sprint 11 (Week 21–22): Launch Prep
- [ ] Final round of beta fixes
- [ ] App Store screenshot creation (iPhone, iPad, Apple TV)
- [ ] Google Play screenshot creation (phone, tablet, TV)
- [ ] Marketing landing page (streamvault.app or similar)
- [ ] App Store submission (allow 1-2 weeks for review)
- [ ] Google Play submission

### Sprint 12 (Week 23–24): Launch & Post-Launch
- [ ] App Store approval monitoring
- [ ] Launch day: Reddit posts, Discord announcement, Twitter/X
- [ ] Monitor crash reports and reviews
- [ ] Hotfix cycle for launch issues
- [ ] Begin planning v1.1 (IPTV, profiles, recommendations)

---

# 10. CI/CD & BUILD PIPELINE

```yaml
# .github/workflows/build.yml (simplified)

name: Build & Test

on:
  push:
    branches: [main, develop]
  pull_request:

jobs:
  shared-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17' }
      - run: ./gradlew :shared:allTests

  android-build:
    runs-on: ubuntu-latest
    needs: shared-tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17' }
      - run: ./gradlew :androidApp:assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: android-apk
          path: androidApp/build/outputs/apk/release/

  ios-build:
    runs-on: macos-14
    needs: shared-tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17' }
      - run: ./gradlew :shared:linkReleaseFrameworkIosArm64
      - run: xcodebuild -project iosApp/StreamVault.xcodeproj
             -scheme StreamVault -sdk iphoneos
             -configuration Release archive
```

---

# 11. QUICK REFERENCE — WHAT TO BUILD FIRST

**Week 1 priorities (in order):**

1. Get MPV playing a video on iOS — this is your highest-risk item
2. Get MPV playing a video on Android — same
3. TMDB browsing working in shared KMP module
4. Basic home screen rendering on both platforms

If MPV works on both platforms in week 1, the rest is execution. If it doesn't, you need to evaluate alternatives (AVPlayer + ExoPlayer, or VLCKit) before going further.

**The 3 things that make or break this app:**
1. Player quality (MPV)
2. Stream resolution speed (addon engine + debrid)
3. Setup simplicity (wizard)

Everything else is polish. Nail those three.
