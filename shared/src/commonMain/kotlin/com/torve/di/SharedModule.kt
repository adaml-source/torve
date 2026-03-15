package com.torve.di

import com.torve.data.ai.AiSuggestClient
import com.torve.data.ai.KeywordSearchService
import com.torve.data.addon.AddonRepositoryImpl
import com.torve.data.availability.AvailabilityRepositoryImpl
import com.torve.data.availability.TmdbAvailabilityProvider
import com.torve.data.addon.CatalogAggregator
import com.torve.data.addon.StreamAggregator
import com.torve.data.addon.StreamScorer
import com.torve.data.addon.StreamSelector
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.SubtitleAggregator
import com.torve.data.addon.StreamRepositoryImpl
import com.torve.data.auth.AuthClient
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
import com.torve.presentation.profile.ProfileViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.setup.SetupWizardViewModel
import com.torve.presentation.discover.DiscoverViewModel
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.mood.MoodMatcherViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.stats.StatsViewModel
import com.torve.presentation.device.DeviceGovernanceViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val sharedModule = module {
    // Network
    single { HttpClientFactory.create() }
    single { HttpClientFactory.json }

    // Database
    single { get<DatabaseDriverFactory>().createDriver() }
    single { TorveDatabase(get()) }

    // TMDB
    singleOf(::TmdbApiClient)
    single<MetadataRepository> { MetadataRepositoryImpl(get()) }
    single<AvailabilityProvider> { TmdbAvailabilityProvider(get()) }
    single<AvailabilityRepository> { AvailabilityRepositoryImpl(get(), get(), get()) }

    // AI Suggest
    single { AiSuggestClient(get()) }
    single { KeywordSearchService(get(), get()) }

    // Stremio Addons
    single { StremioAddonClient(get(), get()) }

    // Debrid
    single {
        val client = DebridClient(get(), get())
        val secretStore: com.torve.domain.integrations.IntegrationSecretStore = get()
        client.rdTokenRefresher = com.torve.data.debrid.RdTokenRefresher {
            val refreshToken = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN) ?: return@RdTokenRefresher null
            val clientId = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_ID) ?: return@RdTokenRefresher null
            val clientSecret = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET) ?: return@RdTokenRefresher null
            try {
                val tokens = client.rdRefreshAccessToken(refreshToken, clientId, clientSecret)
                // Persist new tokens
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, tokens.accessToken)
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, tokens.refreshToken)
                tokens.accessToken
            } catch (e: Exception) {
                println("TORVE_RD: token refresh failed: ${e.message}")
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
    factory { TraktScrobbler(get()) }

    // SIMKL
    single { SimklClient(get()) }

    // MDBList
    single { MdbListApi(get()) }
    single { MdbListRepository(get(), get()) }
    single { RatingsCacheRepository(get()) }

    // OMDB + Ratings
    single { OmdbClient(get(), get()) }
    single { RatingsEnricher(get(), get(), get(), get(), get()) }

    // Kodi
    single { KodiClient(get()) }

    // Auth & Entitlements
    single {
        val baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL }
        AuthClient(
            prefsRepo = get(),
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
    single<StreamRepository> { StreamRepositoryImpl(get(), get(), get()) }

    // Watch Progress
    single<WatchProgressRepository> { WatchProgressRepositoryImpl(get(), get(), get(), get()) }

    // Preferences
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }

    // Addon Repository
    single<AddonRepository> { AddonRepositoryImpl(get(), get(), get()) }

    // Channel Repository
    single<ChannelRepository> { ChannelRepositoryImpl(get(), get(), get(), get(), get()) }

    // Download Repository
    single<DownloadRepository> { DownloadRepositoryImpl(get()) }

    // Bulk Download Manager
    single { BulkDownloadManager(get(), get(), get(), get()) }

    // Download Catalogue
    single { DownloadCatalogueBuilder() }

    // Profile Repository
    single<ProfileRepository> { ProfileRepositoryImpl(get()) }

    // Shelf Config Repository
    single<ShelfConfigRepository> { ShelfConfigRepositoryImpl(get()) }

    // Subscription
    single { RebateCodeApi(get()) }
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get(), get(), get(), get()) }

    // Watchlist Repository
    single<WatchlistRepository> { WatchlistRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }

    // Watch History Repository
    single<WatchHistoryRepository> { WatchHistoryRepositoryImpl(get(), get(), get(), get(), get(), get()) }

    // Sync Repository
    single<SyncRepository> { SyncRepositoryImpl(get(), get(), get(), get()) }

    // Use Cases
    factory { GetRecommendationsUseCase(get(), get()) }
    factory { MoodMatcher(get()) }

    // ViewModels — singletons share state across screens (e.g. TvRoot ↔ TvIptvScreen).
    // The database is pre-warmed on a background thread in TorveApp.onCreate so the
    // first koinInject() doesn't block the main thread with schema DDL.
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SearchViewModel(get(), get()) }
    factory { DetailViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::PersonViewModel)
    single { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::AddonViewModel)
    single { ChannelsViewModel(get(), get(), get()) }
    factory { CalendarViewModel(get(), get()) }
    factoryOf(::DownloadViewModel)
    factory { DownloadCatalogueViewModel(get(), get(), get(), get()) }
    factoryOf(::ProfileViewModel)
    factoryOf(::SubscriptionViewModel)
    factoryOf(::DeviceGovernanceViewModel)
    factory { SetupWizardViewModel(get(), get(), get(), get(), get()) }
    single { WatchlistViewModel(get(), get()) }
    factory { DiscoverViewModel() }
    factoryOf(::MoodMatcherViewModel)
    factoryOf(::MdbListViewModel)
    factoryOf(::StatsViewModel)
    factory { SeeAllViewModel(get(), get(), get(), get()) }
}
