package com.streamvault.di

import com.streamvault.data.addon.AddonRepositoryImpl
import com.streamvault.data.addon.CatalogAggregator
import com.streamvault.data.addon.StreamAggregator
import com.streamvault.data.addon.StremioAddonClient
import com.streamvault.data.addon.StreamRepositoryImpl
import com.streamvault.data.auth.AuthClient
import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.download.DownloadRepositoryImpl
import com.streamvault.data.kodi.KodiClient
import com.streamvault.data.iptv.EpgParser
import com.streamvault.data.iptv.IptvRepositoryImpl
import com.streamvault.data.iptv.M3uParser
import com.streamvault.data.iptv.XtreamClient
import com.streamvault.data.simkl.SimklClient
import com.streamvault.data.metadata.MetadataRepositoryImpl
import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.network.HttpClientFactory
import com.streamvault.data.progress.PreferencesRepositoryImpl
import com.streamvault.data.progress.WatchProgressRepositoryImpl
import com.streamvault.data.subscription.SubscriptionRepositoryImpl
import com.streamvault.data.trakt.TraktClient
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.DownloadRepository
import com.streamvault.domain.repository.IptvRepository
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.SubscriptionRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.platform.DatabaseDriverFactory
import com.streamvault.presentation.addon.AddonViewModel
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.detail.PersonViewModel
import com.streamvault.presentation.download.DownloadViewModel
import com.streamvault.presentation.home.HomeViewModel
import com.streamvault.presentation.iptv.IptvViewModel
import com.streamvault.presentation.search.SearchViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.setup.SetupWizardViewModel
import com.streamvault.presentation.subscription.SubscriptionViewModel
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

    // Stremio Addons
    single { StremioAddonClient(get(), get()) }

    // Debrid
    single { DebridClient(get(), get()) }

    // Trakt
    single { TraktClient(get(), get()) }

    // SIMKL
    single { SimklClient(get()) }

    // Kodi
    single { KodiClient(get()) }

    // Auth
    single { AuthClient(get()) }

    // Parsers
    single { M3uParser() }
    single { EpgParser() }

    // Xtream Codes
    single { XtreamClient(get(), get()) }

    // Aggregators
    single { CatalogAggregator(get()) }
    single { StreamAggregator(get(), get()) }

    // Stream Repository
    single<StreamRepository> { StreamRepositoryImpl(get(), get()) }

    // Watch Progress
    single<WatchProgressRepository> { WatchProgressRepositoryImpl(get()) }

    // Preferences
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }

    // Addon Repository
    single<AddonRepository> { AddonRepositoryImpl(get(), get(), get()) }

    // IPTV Repository
    single<IptvRepository> { IptvRepositoryImpl(get(), get(), get(), get(), get()) }

    // Download Repository
    single<DownloadRepository> { DownloadRepositoryImpl(get()) }

    // Subscription Repository
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get()) }

    // ViewModels
    factoryOf(::HomeViewModel)
    factoryOf(::SearchViewModel)
    factory { DetailViewModel(get(), get(), get(), get(), get()) }
    factoryOf(::PersonViewModel)
    single { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
    factoryOf(::AddonViewModel)
    factory { IptvViewModel(get(), get()) }
    factoryOf(::DownloadViewModel)
    factoryOf(::SubscriptionViewModel)
    factory { SetupWizardViewModel(get(), get(), get()) }
}
