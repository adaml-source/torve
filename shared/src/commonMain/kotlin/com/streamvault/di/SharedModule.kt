package com.streamvault.di

import com.streamvault.data.addon.StremioAddonClient
import com.streamvault.data.addon.StreamRepositoryImpl
import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.metadata.MetadataRepositoryImpl
import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.data.network.HttpClientFactory
import com.streamvault.data.progress.PreferencesRepositoryImpl
import com.streamvault.data.progress.WatchProgressRepositoryImpl
import com.streamvault.data.trakt.TraktClient
import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.platform.DatabaseDriverFactory
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.home.HomeViewModel
import com.streamvault.presentation.search.SearchViewModel
import com.streamvault.presentation.settings.SettingsViewModel
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

    // Stream Repository
    single<StreamRepository> { StreamRepositoryImpl(get(), get()) }

    // Watch Progress
    single<WatchProgressRepository> { WatchProgressRepositoryImpl(get()) }

    // Preferences
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }

    // ViewModels
    factoryOf(::HomeViewModel)
    factoryOf(::SearchViewModel)
    factoryOf(::DetailViewModel)
    single { SettingsViewModel(get(), get(), get()) }
}
