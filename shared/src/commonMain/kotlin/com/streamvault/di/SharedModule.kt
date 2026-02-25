package com.streamvault.di

import com.streamvault.data.ai.AiSuggestClient
import com.streamvault.data.ai.KeywordSearchService
import com.streamvault.data.addon.AddonRepositoryImpl
import com.streamvault.data.addon.CatalogAggregator
import com.streamvault.data.addon.StreamAggregator
import com.streamvault.data.addon.StreamScorer
import com.streamvault.data.addon.StremioAddonClient
import com.streamvault.data.addon.SubtitleAggregator
import com.streamvault.data.addon.StreamRepositoryImpl
import com.streamvault.data.auth.AuthClient
import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.download.DownloadCatalogueBuilder
import com.streamvault.data.download.DownloadRepositoryImpl
import com.streamvault.data.profile.ProfileRepositoryImpl
import com.streamvault.data.shelf.ShelfConfigRepositoryImpl
import com.streamvault.data.kodi.KodiClient
import com.streamvault.data.iptv.CatchupResolver
import com.streamvault.data.iptv.EpgParser
import com.streamvault.data.iptv.IptvRepositoryImpl
import com.streamvault.data.iptv.M3uParser
import com.streamvault.data.iptv.XtreamClient
import com.streamvault.data.mdblist.MdbListApi
import com.streamvault.data.mdblist.MdbListRepository
import com.streamvault.data.mdblist.RatingsEnricher
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.metadata.MetadataRepositoryImpl
import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.network.HttpClientFactory
import com.streamvault.data.progress.PreferencesRepositoryImpl
import com.streamvault.data.progress.WatchProgressRepositoryImpl
import com.streamvault.data.subscription.SubscriptionRepositoryImpl
import com.streamvault.data.history.WatchHistoryRepositoryImpl
import com.streamvault.data.sync.SyncRepositoryImpl
import com.streamvault.data.watchlist.WatchlistRepositoryImpl
import com.streamvault.domain.recommendation.GetRecommendationsUseCase
import com.streamvault.domain.recommendation.MoodMatcher
import com.streamvault.data.trakt.TraktClient
import com.streamvault.presentation.player.TraktScrobbler
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.DownloadRepository
import com.streamvault.domain.repository.IptvRepository
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.ProfileRepository
import com.streamvault.domain.repository.ShelfConfigRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.SubscriptionRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.domain.repository.WatchlistRepository
import com.streamvault.domain.sync.SyncRepository
import com.streamvault.platform.DatabaseDriverFactory
import com.streamvault.presentation.addon.AddonViewModel
import com.streamvault.presentation.calendar.CalendarViewModel
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.detail.PersonViewModel
import com.streamvault.presentation.download.DownloadCatalogueViewModel
import com.streamvault.presentation.download.DownloadViewModel
import com.streamvault.presentation.home.HomeViewModel
import com.streamvault.presentation.profile.ProfileViewModel
import com.streamvault.presentation.iptv.IptvViewModel
import com.streamvault.presentation.search.SearchViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.setup.SetupWizardViewModel
import com.streamvault.presentation.discover.DiscoverViewModel
import com.streamvault.presentation.mdblist.MdbListViewModel
import com.streamvault.presentation.mood.MoodMatcherViewModel
import com.streamvault.presentation.seeall.SeeAllViewModel
import com.streamvault.presentation.stats.StatsViewModel
import com.streamvault.presentation.subscription.SubscriptionViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val sharedModule = module {
    // Network
    single { HttpClientFactory.create() }
    single { HttpClientFactory.json }

    // Database
    single { get<DatabaseDriverFactory>().createDriver() }
    single { StreamVaultDatabase(get()) }

    // TMDB
    singleOf(::TmdbApiClient)
    single<MetadataRepository> { MetadataRepositoryImpl(get()) }

    // AI Suggest
    single { AiSuggestClient(get()) }
    single { KeywordSearchService(get(), get()) }

    // Stremio Addons
    single { StremioAddonClient(get(), get()) }

    // Debrid
    single { DebridClient(get(), get()) }

    // Trakt
    single { TraktClient(get(), get()) }
    factory { TraktScrobbler(get()) }

    // SIMKL
    single { SimklClient(get()) }

    // MDBList
    single { MdbListApi(get()) }
    single { MdbListRepository(get(), get()) }
    single { RatingsEnricher(get()) }

    // Kodi
    single { KodiClient(get()) }

    // Auth
    single { AuthClient(get()) }

    // Parsers
    single { M3uParser() }
    single { EpgParser() }
    single { CatchupResolver() }

    // Xtream Codes
    single { XtreamClient(get(), get()) }

    // Scoring & Aggregation
    single { StreamScorer() }
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

    // IPTV Repository
    single<IptvRepository> { IptvRepositoryImpl(get(), get(), get(), get(), get()) }

    // Download Repository
    single<DownloadRepository> { DownloadRepositoryImpl(get()) }

    // Download Catalogue
    single { DownloadCatalogueBuilder() }

    // Profile Repository
    single<ProfileRepository> { ProfileRepositoryImpl(get()) }

    // Shelf Config Repository
    single<ShelfConfigRepository> { ShelfConfigRepositoryImpl(get()) }

    // Subscription Repository
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get()) }

    // Watchlist Repository
    single<WatchlistRepository> { WatchlistRepositoryImpl(get(), get(), get(), get(), get()) }

    // Watch History Repository
    single<WatchHistoryRepository> { WatchHistoryRepositoryImpl(get(), get(), get(), get()) }

    // Sync Repository
    single<SyncRepository> { SyncRepositoryImpl(get(), get()) }

    // Use Cases
    factory { GetRecommendationsUseCase(get(), get()) }
    factory { MoodMatcher(get()) }

    // ViewModels
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SearchViewModel(get(), get()) }
    factory { DetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::PersonViewModel)
    single { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    factoryOf(::AddonViewModel)
    single { IptvViewModel(get(), get(), get()) }
    factory { CalendarViewModel(get(), get()) }
    factoryOf(::DownloadViewModel)
    factory { DownloadCatalogueViewModel(get(), get(), get(), get()) }
    factoryOf(::ProfileViewModel)
    factoryOf(::SubscriptionViewModel)
    factory { SetupWizardViewModel(get(), get(), get()) }
    single { WatchlistViewModel(get(), get()) }
    factory { DiscoverViewModel() }
    factoryOf(::MoodMatcherViewModel)
    factoryOf(::MdbListViewModel)
    factoryOf(::StatsViewModel)
    factory { SeeAllViewModel(get(), get(), get(), get()) }
}
