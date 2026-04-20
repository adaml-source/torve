package com.torve.di

import com.torve.data.addon.AddonRepositoryImpl
import com.torve.data.account.AccountAwarePreferencesRepository
import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.account.AccountSettingsRepositoryImpl
import com.torve.data.acceleration.AccelerationApi
import com.torve.data.acceleration.AccelerationInventorySyncService
import com.torve.data.availability.AvailabilityRepositoryImpl
import com.torve.data.availability.TmdbAvailabilityProvider
import com.torve.data.addon.AddonSyncService
import com.torve.data.addon.CatalogAggregator
import com.torve.data.addon.StreamAggregator
import com.torve.data.addon.StreamScorer
import com.torve.data.addon.StreamSelector
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.SubtitleAggregator
import com.torve.data.addon.StreamRepositoryImpl
import com.torve.data.auth.AuthClient
import com.torve.data.auth.UserIdProvider
import com.torve.data.ai.AiSuggestClient
import com.torve.data.ai.KeywordSearchService
import com.torve.data.contentpolicy.AddonPolicyRepository
import com.torve.data.contentpolicy.ContentPolicyApi
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentChannelProvider
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.contentpolicy.ContentPolicyRepositoryImpl
import com.torve.data.contentpolicy.MutableContentChannelProvider
import com.torve.data.device.DeviceApi
import com.torve.data.entitlement.EntitlementApi
import com.torve.data.debrid.DebridClient
import com.torve.data.download.BulkDownloadManager
import com.torve.data.download.DownloadCatalogueBuilder
import com.torve.data.download.DownloadRepositoryImpl
import com.torve.data.integrations.CompositeLibraryOverlayService
import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.data.integrations.PlexLibraryOverlayService
import com.torve.data.profile.ProfileRepositoryImpl
import com.torve.data.shelf.ShelfConfigRepositoryImpl
import com.torve.data.kodi.KodiClient
import com.torve.data.pairing.PairingApi
import com.torve.data.channels.CatchupResolver
import com.torve.data.channels.EpgParser
import com.torve.data.channels.ChannelRepositoryImpl
import com.torve.data.channels.M3uParser
import com.torve.data.channels.XtreamClient
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.MdbListRepository
import com.torve.data.mdblist.RatingsCacheRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.ratings.OmdbClient
import com.torve.data.simkl.SimklClient
import com.torve.data.metadata.MetadataRepositoryImpl
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.network.HttpClientFactory
import com.torve.data.progress.PreferencesRepositoryImpl
import com.torve.data.progress.WatchProgressRepositoryImpl
import com.torve.data.subscription.RebateCodeApi
import com.torve.data.subscription.SubscriptionRepositoryImpl
import com.torve.data.history.WatchHistoryRepositoryImpl
import com.torve.data.sync.SyncRepositoryImpl
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.data.trakt.repo.TraktSyncRepositoryImpl
import com.torve.data.watchlist.WatchlistRepositoryImpl
import com.torve.domain.recommendation.GetRecommendationsUseCase
import com.torve.domain.recommendation.MoodMatcher
import com.torve.data.trakt.TraktClient
import com.torve.domain.integrations.AvailabilityProvider
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.presentation.player.TraktScrobbler
import com.torve.db.TorveDatabase
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.AvailabilityRepository
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.ProfileRepository
import com.torve.domain.repository.ShelfConfigRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.sync.SyncRepository
import com.torve.platform.DatabaseDriverFactory
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.calendar.CalendarViewModel
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.contentpolicy.SensitiveMaterialSettingsViewModel
import com.torve.presentation.profile.ProfileViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.setup.SetupWizardViewModel
import com.torve.presentation.discover.DiscoverViewModel
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.mood.MoodMatcherViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.stats.StatsViewModel
import com.torve.presentation.device.DeviceGovernanceViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import com.torve.platform.torveVerboseLog
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val sharedModule = module {
    // Network
    single { HttpClientFactory.create() }
    single(named("tmdbHttpClient")) { HttpClientFactory.createTmdb() }
    single { HttpClientFactory.json }

    // Database
    single { get<DatabaseDriverFactory>().createDriver() }
    single { TorveDatabase(get()) }

    // TMDB
    single {
        val localSettings = get<DeviceLocalSettingsRepository>()
        val rawLang = kotlinx.coroutines.runBlocking { localSettings.getString("app_language") }
        val initialLang = rawLang?.let { name ->
            when (name.uppercase()) {
                "GERMAN" -> "de"; "SPANISH" -> "es"; "FRENCH" -> "fr"
                "ITALIAN" -> "it"; "PORTUGUESE" -> "pt"; "TURKISH" -> "tr"
                else -> null
            }
        }
        torveVerboseLog { "TMDB_INIT rawLang=$rawLang initialLang=$initialLang" }
        TmdbApiClient(get(named("tmdbHttpClient"))).also {
            it.contentLanguage = initialLang
        }
    }
    single<MetadataRepository> { MetadataRepositoryImpl(get()) }
    single<AvailabilityProvider> { TmdbAvailabilityProvider(get()) }
    single<AvailabilityRepository> { AvailabilityRepositoryImpl(get(), get(), get()) }

    // AI
    single { AiSuggestClient(get()) }
    single { KeywordSearchService(get(), get()) }

    // Stremio Addons
    single { StremioAddonClient(get(), get()) }

    single {
        AccelerationApi(
            httpClient = get(),
            authClient = get(),
            json = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            channelProvider = get(),
        )
    }

    // Debrid
    single {
        val client = DebridClient(get(), get(), get())
        val secretStore: com.torve.domain.integrations.IntegrationSecretStore = get()
        val settingsRefreshNotifier: com.torve.presentation.settings.SettingsRefreshNotifier = get()
        client.rdTokenRefresher = com.torve.data.debrid.RdTokenRefresher {
            val refreshToken = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN) ?: return@RdTokenRefresher null
            val clientId = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_ID) ?: return@RdTokenRefresher null
            val clientSecret = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET) ?: return@RdTokenRefresher null
            try {
                val tokens = client.rdRefreshAccessToken(refreshToken, clientId, clientSecret)
                // Persist new tokens
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, tokens.accessToken)
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, tokens.refreshToken)
                settingsRefreshNotifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                tokens.accessToken
            } catch (e: Exception) {
                torveVerboseLog { "TORVE_RD: token refresh failed: ${e.message}" }
                null
            }
        }
        client
    }

    // Library Overlay (Jellyfin + Plex → composite router)
    single { JellyfinLibraryOverlayService(get(), get(), get()) }
    single { PlexLibraryOverlayService(get(), get(), get()) }
    single<LibraryOverlayService> { CompositeLibraryOverlayService(get(), get(), get(), get()) }

    // Trakt
    single { TraktClient(get(), get()) }
    single { TraktTokenStore(get(), get()) }
    single { TraktAuthorizedApi(get(), get()) }
    single<TraktSyncRepository> { TraktSyncRepositoryImpl(get(), get(), get(), get()) }
    factory { TraktScrobbler(get(), get()) }

    // SIMKL
    single { SimklClient(get()) }

    // MDBList
    single { MdbListApi(get()) }
    single { MdbListRepository(get(), get()) }
    single { RatingsCacheRepository(get()) }

    // OMDB + Ratings
    single { OmdbClient(get(), get(), get()) }
    single { RatingsEnricher(get(), get(), get(), get(), get()) }

    // Kodi
    single { KodiClient(get()) }

    // Auth & Entitlements
    single {
        val baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL }
        AuthClient(
            localSettingsRepository = get(),
            secureStorage = get(),
            httpClient = get(),
            baseUrlProvider = baseUrlProvider,
            deviceRegistrationProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceRegistration() },
        )
    }
    single {
        EntitlementApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
        )
    }
    single {
        DeviceApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            currentInstallationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }

    // Parsers
    single { M3uParser() }
    single { EpgParser() }
    single { CatchupResolver() }

    // Xtream Codes
    single { XtreamClient(get(), get()) }

    // Scoring & Aggregation
    single { StreamScorer() }
    single { StreamSelector(get()) }
    single { CatalogAggregator(get()) }
    single { SubtitleAggregator(get()) }
    single { StreamAggregator(get(), get(), get()) }

    // Stream Repository
    single<StreamRepository> { StreamRepositoryImpl(get(), get(), get(), get()) }
    single { AccelerationInventorySyncService(get(), get(), get()) }

    // User ID provider for DB scoping. Uses a lazy AuthClient lookup so it can be
    // wired into PreferencesRepositoryImpl without forming a circular dependency
    // (AuthClient itself depends on DeviceLocalSettingsRepository).
    single { UserIdProvider { get() } }

    // Watch Progress
    single<WatchProgressRepository> { WatchProgressRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }

    // Preferences / Settings sync
    single<DeviceLocalSettingsRepository> { PreferencesRepositoryImpl(get(), get()) }
    single { MutableContentChannelProvider() }
    single<ContentChannelProvider> { get<MutableContentChannelProvider>() }
    single { AccountSettingsApi(get(), baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL }, channelProvider = get()) }
    single<AccountSettingsRepository> { AccountSettingsRepositoryImpl(get(), get(), get(), get()) }
    single<PreferencesRepository> { AccountAwarePreferencesRepository(get(), get()) }
    single { SettingsRefreshNotifier() }
    single { AddonPolicyRepository(get(), get()) }
    single {
        ContentPolicyApi(
            httpClient = get(),
            authClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            channelProvider = get(),
        )
    }
    single {
        ContentPolicyCacheInvalidationCoordinator(
            database = get(),
            ratingsEnricher = get(),
            prefsRepo = get(),
            settingsRefreshNotifier = get(),
            addonPolicyRepository = get(),
        )
    }
    single<ContentPolicyRepository> {
        ContentPolicyRepositoryImpl(
            api = get(),
            authClient = get(),
            prefsRepo = get(),
            json = get(),
            channelProvider = get(),
            invalidationCoordinator = get(),
        )
    }
    single { ContentPolicyFilter() }

    // Addon Repository
    single<AddonRepository> { AddonRepositoryImpl(get(), get(), get()) }
    single {
        AddonSyncService(
            accessTokenProvider = { get<AuthClient>().getValidAccessToken() },
            addonRepo = get(),
            accountSettingsApi = get(),
            prefsRepo = get(),
            settingsRefreshNotifier = get(),
            json = get(),
            addonPolicyRepository = get(),
        )
    }

    // Channel Repository
    single<ChannelRepository> { ChannelRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }

    // Download Repository
    single<DownloadRepository> { DownloadRepositoryImpl(get(), get()) }

    // Bulk Download Manager
    single { BulkDownloadManager(get(), get(), get(), get()) }

    // Download Catalogue
    single { DownloadCatalogueBuilder() }

    // Profile Repository
    single<ProfileRepository> { ProfileRepositoryImpl(get(), get()) }

    // Shelf Config Repository
    single<ShelfConfigRepository> { ShelfConfigRepositoryImpl(get(), get()) }

    // Subscription
    single { RebateCodeApi(get()) }
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get(), get(), get(), get(), get()) }

    // Watchlist Repository
    single<WatchlistRepository> { WatchlistRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Watch History Repository
    single<WatchHistoryRepository> { WatchHistoryRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }

    // Sync Repository
    single<SyncRepository> { SyncRepositoryImpl(get(), get(), get(), get()) }
    single { PairingApi(get(), baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL }) }
    single { AccountSessionCoordinator(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // Use Cases
    factory { GetRecommendationsUseCase(get(), get()) }
    factory { MoodMatcher(get()) }

    // ViewModels — singletons share state across screens (e.g. TvRoot ↔ TvIptvScreen).
    // The database is pre-warmed on a background thread in TorveApp.onCreate so the
    // first koinInject() doesn't block the main thread with schema DDL.
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SearchViewModel(get(), get(), get(), get(), get()) }
    factory { DetailViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::PersonViewModel)
    single {
        SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()).also { vm ->
            // Wire integration save callback — breaks circular dep by using lazy resolution.
            vm.onIntegrationSaved = { type, credential, label ->
                get<AccountSessionCoordinator>().saveIntegrationToBackend(type, credential, label)
            }
            // Sync app language → TMDB content language.
            val tmdb = get<TmdbApiClient>()
            vm.onLanguageChanged = { language ->
                tmdb.contentLanguage = language.code.takeIf { it != "en" }
            }
            // Initial language is set from prefs in the TmdbApiClient single{} block.
            // The SettingsViewModel init loads it async and will call onLanguageChanged.
        }
    }
    factory { AddonViewModel(get(), get(), get(), addonPolicyRepository = get()) }
    single { com.torve.data.panda.PandaApiClient(get(), get()) }
    factory { com.torve.presentation.panda.PandaSetupViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { ChannelsViewModel(get(), get(), get(), backgroundDispatcher = kotlinx.coroutines.Dispatchers.IO, playlistBackup = get(), settingsRefreshNotifier = get()) }
    factory { CalendarViewModel(get(), get()) }
    factory { DownloadViewModel(get(), contentPolicyRepository = get(), contentPolicyFilter = ContentPolicyFilter()) }
    factory { DownloadCatalogueViewModel(get(), get(), get(), get(), contentPolicyRepository = get(), contentPolicyFilter = ContentPolicyFilter()) }
    factoryOf(::ProfileViewModel)
    // Singleton so NavGraph, PaywallScreen, and all other screens share
    // the same premium state — avoids stale locks after purchase/login.
    single { SubscriptionViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::DeviceGovernanceViewModel)
    factory { SetupWizardViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { WatchlistViewModel(get(), get(), get(), get(), get()) }
    factory { DiscoverViewModel() }
    factoryOf(::MoodMatcherViewModel)
    factoryOf(::MdbListViewModel)
    factoryOf(::StatsViewModel)
    factory {
        SeeAllViewModel(
            metadataRepo = get(),
            watchHistoryRepo = get(),
            watchlistRepo = get(),
            prefsRepo = get(),
            watchProgressRepo = get(),
            contentPolicyRepository = get(),
            contentPolicyFilter = get(),
            invalidationCoordinator = get(),
            ratingsEnricher = getOrNull(),
            integrationSecretStore = getOrNull(),
        )
    }
    factory { SensitiveMaterialSettingsViewModel(get()) }
    factory { com.torve.presentation.jellyfin.JellyfinBrowserViewModel(get()) }
}
