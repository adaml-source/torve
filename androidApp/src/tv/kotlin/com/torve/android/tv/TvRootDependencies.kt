package com.torve.android.tv

internal data class TvRootDependencies(
    val homeViewModel: com.torve.presentation.home.HomeViewModel,
    val accountCoordinator: com.torve.presentation.session.AccountSessionCoordinator,
    val healthCoordinator: com.torve.presentation.providerhealth.ProviderHealthCoordinator,
    val metadataRepo: com.torve.domain.repository.MetadataRepository,
    val ratingsEnricher: com.torve.data.mdblist.RatingsEnricher,
    val preferencesRepository: com.torve.domain.repository.PreferencesRepository,
    val integrationSecretStore: com.torve.domain.integrations.IntegrationSecretStore,
    val watchlistViewModel: com.torve.presentation.watchlist.WatchlistViewModel,
    val watchProgressRepo: com.torve.domain.repository.WatchProgressRepository,
    val watchHistoryRepo: com.torve.domain.repository.WatchHistoryRepository,
    val syncCoordinator: com.torve.android.sync.SyncCoordinator,
    val syncRepository: com.torve.domain.sync.SyncRepository,
    val settingsViewModel: com.torve.presentation.settings.SettingsViewModel,
    val subscriptionViewModel: com.torve.presentation.subscription.SubscriptionViewModel,
    val subscriptionRepository: com.torve.domain.repository.SubscriptionRepository,
    val authClient: com.torve.data.auth.AuthClient,
    val mediaFavoritesRepository: com.torve.domain.repository.MediaFavoritesRepository,
    val transferRecoveryProvider: com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider,
    val transferImportCompletionNotifier: com.torve.presentation.transfer.TransferImportCompletionNotifier,
)

/** Kept separate from the Compose root so cache preparation can start before UI class loading. */
internal fun loadTvRootDependencies(): TvRootDependencies {
    val koin = org.koin.java.KoinJavaComponent.getKoin()
    val home = koin.get<com.torve.presentation.home.HomeViewModel>()
    com.torve.android.debug.StartupMetrics.record("STARTUP home_model_ready")
    return TvRootDependencies(
        homeViewModel = home,
        accountCoordinator = koin.get(),
        healthCoordinator = koin.get(),
        metadataRepo = koin.get(),
        ratingsEnricher = koin.get(),
        preferencesRepository = koin.get(),
        integrationSecretStore = koin.get(),
        watchlistViewModel = koin.get(),
        watchProgressRepo = koin.get(),
        watchHistoryRepo = koin.get(),
        syncCoordinator = koin.get(),
        syncRepository = koin.get(),
        settingsViewModel = koin.get(),
        subscriptionViewModel = koin.get(),
        subscriptionRepository = koin.get(),
        authClient = koin.get(),
        mediaFavoritesRepository = koin.get(),
        transferRecoveryProvider = koin.get(),
        transferImportCompletionNotifier = koin.get(),
    ).also { com.torve.android.debug.StartupMetrics.record("STARTUP root_dependencies_ready") }
}
