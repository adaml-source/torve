package com.torve.di

import com.torve.data.addon.AddonRepositoryImpl
import com.torve.data.account.AccountAwarePreferencesRepository
import com.torve.data.account.AccountSettingsApi
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.account.AccountSettingsRepositoryImpl
import com.torve.data.account.MediaFavoritesApi
import com.torve.data.account.MediaFavoritesRemoteDataSource
import com.torve.data.account.MediaFavoritesRepositoryImpl
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
import com.torve.data.beta.BetaProgramApi
import com.torve.data.beta.BetaProgramRepositoryImpl
import com.torve.data.ai.AiSuggestClient
import com.torve.data.ai.KeywordSearchService
import com.torve.data.billing.BillingApi
import com.torve.data.billing.KtorBillingApi
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
import com.torve.data.stats.WatchSessionRecorder
import com.torve.data.stats.WatchStatsRepositoryImpl
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
import com.torve.data.trakt.PersistedTraktAuthScopeProvider
import com.torve.data.trakt.TraktAuthScopeProvider
import com.torve.data.trakt.TraktClient
import com.torve.domain.integrations.AvailabilityProvider
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.presentation.player.TraktScrobbler
import com.torve.db.TorveDatabase
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.AvailabilityRepository
import com.torve.domain.repository.BetaProgramRepository
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.ProfileRepository
import com.torve.domain.repository.ShelfConfigRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.stats.WatchStatsEngine
import com.torve.domain.stats.WatchStatsRepository
import com.torve.domain.sync.SyncRepository
import com.torve.platform.DatabaseDriverFactory
import com.torve.presentation.addon.AddonViewModel
import com.torve.presentation.calendar.CalendarViewModel
import com.torve.presentation.beta.BetaProgramViewModel
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
import com.torve.presentation.session.DeviceRegistrationNotifier
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.setup.SetupWizardViewModel
import com.torve.presentation.discover.DiscoverViewModel
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.mood.MoodMatcherViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.stats.StatsViewModel
import com.torve.presentation.stats.WatchStatsViewModel
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
            val refreshToken = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN)
            val clientId = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_ID)
            val clientSecret = secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET)
            torveVerboseLog { "TORVE_RD: refresh attempt hasRefreshToken=${refreshToken != null} hasClientId=${clientId != null} hasClientSecret=${clientSecret != null}" }
            if (refreshToken == null || clientId == null || clientSecret == null) {
                torveVerboseLog { "TORVE_RD: refresh aborted missing credentials, user must reconnect RD" }
                return@RdTokenRefresher null
            }
            try {
                val tokens = client.rdRefreshAccessToken(refreshToken, clientId, clientSecret)
                torveVerboseLog { "TORVE_RD: refresh succeeded newTokenLength=${tokens.accessToken.length}" }
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, tokens.accessToken)
                secretStore.put(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN, tokens.refreshToken)
                settingsRefreshNotifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                tokens.accessToken
            } catch (e: Exception) {
                torveVerboseLog { "TORVE_RD: refresh failed ${e.message}" }
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
    single<TraktAuthScopeProvider> { PersistedTraktAuthScopeProvider(get()) }
    single { TraktClient(get(), get(), authScopeProvider = get()) }
    single { TraktTokenStore(get(), get()) }
    single { TraktAuthorizedApi(get(), get(), authScopeProvider = get()) }
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

    // Catalog top-cache: pre-paginated top-1000 per genre (movie/tv).
    single { com.torve.data.catalog.CatalogTopCacheRepository(get(), get()) }
    // Worker default = no poster prefetch. Desktop replaces this single
    // with a prefetcher-wired version in its module so disk-image cache
    // is pre-warmed; mobile platforms without an equivalent image cache
    // get the metadata-only behavior.
    single { com.torve.data.catalog.CatalogTopCacheWorker(get(), posterPrefetcher = null) }

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
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single<BillingApi> {
        KtorBillingApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single {
        DeviceApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            currentInstallationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single {
        BetaProgramApi(
            httpClient = get(),
            authClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single {
        com.torve.data.security.TrustSignalsApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single {
        com.torve.data.telemetry.StreamPathTelemetryApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            accessTokenProvider = { get<com.torve.data.auth.AuthClient>().getValidAccessToken() },
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
    single { com.torve.data.subtitles.OpenSubtitlesClient(get(), get()) }
    single { StreamAggregator(get(), get(), get()) }

    // Stream Repository
    single<StreamRepository> {
        StreamRepositoryImpl(
            debridClient = get(),
            streamAggregator = get(),
            database = get(),
            accelerationApi = get(),
            httpClient = get(),
            telemetry = get(),
            preferencesRepository = get(),
            subscriptionRepository = get(),
        )
    }
    single { AccelerationInventorySyncService(get(), get(), get()) }

    // User ID provider for DB scoping. Uses a lazy AuthClient lookup so it can be
    // wired into PreferencesRepositoryImpl without forming a circular dependency
    // (AuthClient itself depends on DeviceLocalSettingsRepository).
    single { UserIdProvider { get() } }

    // Watch Progress
    single<WatchProgressRepository> { WatchProgressRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }
    single { WatchStatsEngine() }
    single<WatchStatsRepository> { WatchStatsRepositoryImpl(get(), get(), get(), get(), get()) }
    single { WatchSessionRecorder(get(), currentUserId = { get<UserIdProvider>().currentUserId() }) }

    // Preferences / Settings sync
    single<DeviceLocalSettingsRepository> { PreferencesRepositoryImpl(get(), get()) }
    single { MutableContentChannelProvider() }
    single<ContentChannelProvider> { get<MutableContentChannelProvider>() }
    single {
        AccountSettingsApi(
            get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            channelProvider = get(),
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single<AccountSettingsRepository> { AccountSettingsRepositoryImpl(get(), get(), get(), get()) }
    single<MediaFavoritesRemoteDataSource> {
        MediaFavoritesApi(
            get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            channelProvider = get(),
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single<MediaFavoritesRepository> { MediaFavoritesRepositoryImpl(get(), get(), get(), get()) }
    single<PreferencesRepository> { AccountAwarePreferencesRepository(get(), get(), get()) }
    single { SettingsRefreshNotifier() }
    single { DeviceRegistrationNotifier() }
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
    single<AddonRepository> { AddonRepositoryImpl(get(), get(), get(), get()) }
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
    single<BetaProgramRepository> { BetaProgramRepositoryImpl(get(), get(), get()) }

    // Watchlist Repository
    single<WatchlistRepository> { WatchlistRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Watch History Repository
    single<WatchHistoryRepository> { WatchHistoryRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Sync Repository
    single<SyncRepository> { SyncRepositoryImpl(get(), get(), get(), get()) }
    single {
        PairingApi(
            get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }

    // Cross-platform Newznab + TorBox usenet plumbing for the shared
    // Sports surface (and future Adult / NZB-Movies surfaces). Both
    // are stateless, thread-safe wrappers around the shared Ktor
    // HttpClient — single() is fine.
    single { com.torve.data.usenet.NewznabClient(httpClient = get()) }
    single { com.torve.data.usenet.TorBoxUsenetClient(httpClient = get()) }
    factory {
        com.torve.presentation.pairing.TvPairingSignInViewModel(
            pairingApi = get(),
            authClient = get(),
        )
    }
    single { AccountSessionCoordinator(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // Provider-health primitives. Repository hydrates from prefs on first
    // load; the coordinator holds in-memory checker registrations and is
    // populated per platform (desktop/Android wire their own checkers).
    single<com.torve.domain.providerhealth.ProviderHealthRepository> {
        com.torve.data.providerhealth.PrefsBackedProviderHealthRepository(get())
    }
    single { com.torve.presentation.providerhealth.ProviderHealthCoordinator(get()) }
    // Auto-rerun every checker when SettingsRefreshNotifier ticks
    // (Panda save success, Debrid token rotation, etc.). Both Desktop
    // and Android init launch a single long-running collector.
    single {
        com.torve.presentation.providerhealth.ProviderHealthRefreshOnSettings(
            notifier = get(),
            coordinator = get(),
        )
    }
    single { com.torve.presentation.setup.SetupIntentsViewModel(get()) }

    // ── Credential-first setup wizard (Prompt 7B) ─────────────────
    // The four IntentValidators delegate to existing clients/state
    // singletons so the wizard's verdict matches what the rest of the
    // app sees. Validators are bound as singles so platform code can
    // resolve them individually (e.g. Debrid validator gets re-run by
    // the recovery card after a transfer-receive lands).
    single<com.torve.presentation.setup.IntentValidator>(
        qualifier = named("setup.validator.debrid"),
    ) {
        com.torve.presentation.setup.DebridIntentValidator(
            providerSource = providerSource@{
                // Pick the first debrid provider the user has a key
                // on file for. Mirrors the resolution order in
                // SettingsViewModel.connectDebrid.
                val store = get<com.torve.domain.integrations.IntegrationSecretStore>()
                val prefs = get<com.torve.domain.repository.PreferencesRepository>()
                val configured = com.torve.domain.model.DebridServiceType.entries
                    .firstOrNull { provider ->
                        val key = store.get(debridSecretKey(provider))
                        !key.isNullOrBlank()
                    }
                if (configured != null) return@providerSource configured
                // Fall back to legacy `debrid_provider` pref string when
                // a key only lives in the legacy slot.
                runCatching {
                    prefs.getString("debrid_provider")
                        ?.let { com.torve.domain.model.DebridServiceType.valueOf(it) }
                }.getOrNull()
            },
            apiKeySource = {
                val store = get<com.torve.domain.integrations.IntegrationSecretStore>()
                val prefs = get<com.torve.domain.repository.PreferencesRepository>()
                val configured = com.torve.domain.model.DebridServiceType.entries
                    .firstNotNullOfOrNull { provider ->
                        store.get(debridSecretKey(provider))?.takeIf { it.isNotBlank() }
                    }
                configured
                    ?: runCatching { prefs.getString("debrid_api_key") }.getOrNull()
            },
            debridClient = get(),
        )
    }
    single<com.torve.presentation.setup.IntentValidator>(
        qualifier = named("setup.validator.iptv"),
    ) {
        com.torve.presentation.setup.IptvIntentValidator(
            stateSource = { get<ChannelsViewModel>().state.value },
        )
    }
    single<com.torve.presentation.setup.IntentValidator>(
        qualifier = named("setup.validator.plex_jellyfin"),
    ) {
        com.torve.presentation.setup.PlexJellyfinIntentValidator(
            serverUrlSource = {
                val prefs = get<com.torve.domain.repository.PreferencesRepository>()
                val plex = runCatching { prefs.getString("plex_server_url") }.getOrNull()
                if (!plex.isNullOrBlank()) plex
                else runCatching { prefs.getString("jellyfin_server_url") }.getOrNull()
            },
            tokenSource = {
                val store = get<com.torve.domain.integrations.IntegrationSecretStore>()
                val plexToken = runCatching {
                    store.get(com.torve.domain.integrations.IntegrationSecretKey.PLEX_ACCESS_TOKEN)
                }.getOrNull()
                if (!plexToken.isNullOrBlank()) plexToken
                else runCatching {
                    store.get(com.torve.domain.integrations.IntegrationSecretKey.JELLYFIN_API_KEY)
                }.getOrNull()
            },
            service = get(),
        )
    }
    single<com.torve.presentation.setup.IntentValidator>(
        qualifier = named("setup.validator.usenet"),
    ) {
        com.torve.presentation.setup.UsenetIntentValidator(
            stateSource = { get<com.torve.presentation.panda.PandaConfigStateStore>().current },
        )
    }
    single {
        com.torve.presentation.setup.SetupWizardCoordinator(
            prefs = get(),
            validators = mapOf(
                com.torve.presentation.setup.SetupIntent.DEBRID to
                    get<com.torve.presentation.setup.IntentValidator>(
                        qualifier = named("setup.validator.debrid"),
                    ),
                com.torve.presentation.setup.SetupIntent.IPTV to
                    get<com.torve.presentation.setup.IntentValidator>(
                        qualifier = named("setup.validator.iptv"),
                    ),
                com.torve.presentation.setup.SetupIntent.PLEX_JELLYFIN to
                    get<com.torve.presentation.setup.IntentValidator>(
                        qualifier = named("setup.validator.plex_jellyfin"),
                    ),
                com.torve.presentation.setup.SetupIntent.USENET to
                    get<com.torve.presentation.setup.IntentValidator>(
                        qualifier = named("setup.validator.usenet"),
                    ),
            ),
            healthRows = get<com.torve.domain.providerhealth.ProviderHealthRepository>().entries,
        )
    }

    // Phase 3 Sub-pass 1 — credential-transfer apply path. The
    // protocol and crypto engine binding lives per-platform (desktop
    // wires JvmTransferCryptoEngine + SecretsTransferProtocol).
    single { com.torve.domain.transfer.ConsumedNonceStore(prefs = get()) }
    single<com.torve.domain.transfer.ConfigKeyAllowlist> {
        com.torve.domain.transfer.DefaultConfigKeyAllowlist()
    }
    single {
        com.torve.domain.transfer.SecretsTransferApplier(
            secretStore = get(),
            nonceStore = get(),
            prefsRepo = get(),
            configKeyAllowlist = get(),
            channelRepo = get(),
        )
    }
    // Phase 3 — backend relay client for credential transfer. Binding
    // here is platform-agnostic; if the backend lacks `/api/v1/transfer/*`,
    // the client returns Unavailable and the UI degrades to manual paste.
    single<com.torve.data.transfer.TransferRelayApi> {
        com.torve.data.transfer.KtorTransferRelayApi(
            httpClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
        )
    }

    // Cross-platform sender + receiver VMs for credential transfer.
    // Both delegate platform-specific X25519 keypair gen + AEAD to the
    // injected SecretsTransferProtocol (engine bound per platform).
    //
    // Diagnostics + telemetry: the singletons below are shared between
    // both VMs and the diagnostics screen so the "last attempt" pill is
    // the same value the VMs reported.
    single { com.torve.presentation.transfer.TransferAttemptTracker() }
    // Cross-platform "an import just landed" broadcast. Receiver VM
    // emits on Success; recovery surfaces observe to recompute.
    single { com.torve.presentation.transfer.TransferImportCompletionNotifier() }
    // Recovery-card state for the "Restore setup from another device"
    // card surfaced on every platform's Settings entry.
    single {
        com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider(
            secretStore = get(),
        )
    }
    factory {
        // Platform tag — read from DeviceIdProvider so we don't need an
        // expect/actual just for a string.
        val platformTag = runCatching { get<com.torve.domain.device.DeviceIdProvider>().getPlatform() }
            .getOrElse { "unknown" }
        com.torve.presentation.transfer.SecretsTransferSenderViewModel(
            protocol = get(),
            secretStore = get(),
            deviceIdProvider = get(),
            prefsRepo = get(),
            configKeyAllowlist = get(),
            relayApi = get(),
            accessTokenProvider = { get<com.torve.data.auth.AuthClient>().getValidAccessToken() },
            telemetry = get(),
            attemptTracker = get(),
            platform = platformTag,
            channelRepo = get(),
        )
    }
    factory {
        val platformTag = runCatching { get<com.torve.domain.device.DeviceIdProvider>().getPlatform() }
            .getOrElse { "unknown" }
        com.torve.presentation.transfer.SecretsTransferReceiverViewModel(
            protocol = get(),
            applier = get(),
            nonceStore = get(),
            relayApi = get(),
            accessTokenProvider = { get<com.torve.data.auth.AuthClient>().getValidAccessToken() },
            deviceIdProvider = get(),
            telemetry = get(),
            attemptTracker = get(),
            completionNotifier = get(),
            platform = platformTag,
        )
    }
    factory {
        com.torve.presentation.transfer.TransferDiagnosticsCollector(
            cryptoEngine = getOrNull(),
            authClient = getOrNull(),
            relayApi = getOrNull(),
            tracker = get(),
        )
    }

    // Source-availability primitives — Phase 3 Slice A + Prompt 8.
    // Real providers covering every "owned" playback path the user can
    // launch from a search result. Aggregator fans out in parallel and
    // caches results in-process so re-renders stay cheap.
    single { com.torve.data.sourceavailability.LocalDownloadSourceAvailabilityProvider(get()) }
    single { com.torve.data.sourceavailability.PlexSourceAvailabilityProvider(get(), get(), get()) }
    single { com.torve.data.sourceavailability.JellyfinSourceAvailabilityProvider(get(), get(), get()) }
    // Shared resolver: tmdbId → imdb id. Cached per-process by
    // MetadataRepository — calls TMDB only when the imdb id isn't in
    // the local catalog. Returns null on any failure (the providers
    // treat null as UNCONFIGURED-silent).
    single<suspend (Int, com.torve.domain.model.MediaType) -> String?>(
        qualifier = named("availability.tmdb_to_imdb"),
    ) {
        val metadata = get<com.torve.domain.repository.MetadataRepository>()
        ({ tmdbId, mediaType ->
            val type = if (mediaType == com.torve.domain.model.MediaType.SERIES) "tv" else "movie"
            runCatching { metadata.getDetail(type, tmdbId) }.getOrNull()?.imdbId
        })
    }
    single<suspend (Int, com.torve.domain.model.MediaType) -> String?>(
        qualifier = named("availability.tmdb_to_title"),
    ) {
        val metadata = get<com.torve.domain.repository.MetadataRepository>()
        ({ tmdbId, mediaType ->
            val type = if (mediaType == com.torve.domain.model.MediaType.SERIES) "tv" else "movie"
            runCatching { metadata.getDetail(type, tmdbId) }.getOrNull()?.title
        })
    }
    single {
        com.torve.data.sourceavailability.DebridCacheSourceAvailabilityProvider(
            streamRepository = get(),
            secretStore = get(),
            tmdbToImdbResolver = get(qualifier = named("availability.tmdb_to_imdb")),
        )
    }
    single {
        com.torve.data.sourceavailability.StremioAddonSourceAvailabilityProvider(
            addonRepository = get(),
            streamRepository = get(),
            tmdbToImdbResolver = get(qualifier = named("availability.tmdb_to_imdb")),
        )
    }
    single {
        com.torve.data.sourceavailability.UsenetReadySourceAvailabilityProvider(
            streamRepository = get(),
            pandaStateSource = { get<com.torve.presentation.panda.PandaConfigStateStore>().current },
            tmdbToImdbResolver = get(qualifier = named("availability.tmdb_to_imdb")),
        )
    }
    single {
        com.torve.data.sourceavailability.IptvLiveSourceAvailabilityProvider(
            channelsStateSource = { get<ChannelsViewModel>().state.value },
            titleSource = get(qualifier = named("availability.tmdb_to_title")),
        )
    }
    single {
        com.torve.data.sourceavailability.WatchHistorySourceAvailabilityProvider(
            repository = get(),
        )
    }
    single<com.torve.domain.sourceavailability.SourceAvailabilityAggregator> {
        com.torve.data.sourceavailability.DefaultSourceAvailabilityAggregator(
            providers = listOf(
                get<com.torve.data.sourceavailability.LocalDownloadSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.PlexSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.JellyfinSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.DebridCacheSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.StremioAddonSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.UsenetReadySourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.IptvLiveSourceAvailabilityProvider>(),
                get<com.torve.data.sourceavailability.WatchHistorySourceAvailabilityProvider>(),
            ),
        )
    }

    // ── IPTV recording / EPG correction (Prompt 10 / 10B) ───────
    // Repository binding lives per-platform (desktop uses
    // FileBackedRecordingRepository); shared keeps the scheduler
    // single + presentation VMs + the EPG correction repo.
    single {
        com.torve.data.recording.EpgCorrectionRepository(prefs = get())
    }
    single {
        com.torve.domain.recording.RecordingScheduler(repository = get())
    }
    single {
        com.torve.presentation.recording.RecordingsViewModel(
            scheduler = get(),
            repository = get(),
            // Starter is platform-supplied; default no-op when no
            // RecordingStarter single is registered (e.g. on iOS
            // before recording lands there).
            starter = getOrNull()
                ?: object : com.torve.presentation.recording.RecordingStarter {},
        )
    }
    single {
        com.torve.presentation.recording.EpgCorrectionViewModel(
            repository = get(),
        )
    }

    // ── LAN hub registry (Prompt 9 / 9B) ─────────────────────────
    // Backend-assisted discovery + secret fetch. Shared between desktop
    // (publisher) and TV / mobile (consumer). Defensive: every call
    // degrades gracefully on a missing backend.
    single {
        com.torve.data.lanlibrary.LanHubRegistryApi(
            httpClient = get(),
            authClient = get<com.torve.data.auth.AuthClient>(),
        )
    }
    single {
        com.torve.presentation.lanlibrary.LanHubDiscovery(
            registry = get(),
        )
    }
    single {
        com.torve.data.lanlibrary.LanLibraryHttpClient(httpClient = get())
    }
    single {
        com.torve.presentation.lanlibrary.LanLibraryConsumer(
            registry = get(),
            httpClient = get(),
        )
    }

    // ── TV-Home outcome aggregator + one-click router (Prompt 11B) ──
    // Consumed by the Android TV layer. Pure presentation — no Compose
    // deps so the wiring stays unit-testable.
    single {
        com.torve.presentation.tvhome.TvHomeOutcomeViewModel(
            homeViewModel = get(),
            availabilityAggregator = get(),
            lanLibraryConsumer = get(),
            providerHealthCoordinator = get(),
            channelsViewModel = get(),
        )
    }
    single {
        com.torve.presentation.tvhome.TvHomePlaybackRouter(
            downloadRepository = get(),
        )
    }

    // Use Cases
    factory { GetRecommendationsUseCase(get(), get()) }
    factory { MoodMatcher(get()) }

    // ViewModels — singletons share state across screens (e.g. TvRoot ↔ TvIptvScreen).
    // The database is pre-warmed on a background thread in TorveApp.onCreate so the
    // first koinInject() doesn't block the main thread with schema DDL.
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SearchViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factory {
        DetailViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(),
            usenetWarmCoordinator = get(),
            usenetResolveCoordinator = get(),
            usenetJobPoller = get(),
            telemetry = get(),
            watchStateRemoteSource = getOrNull(),
            watchSessionRecorder = get(),
        )
    }
    factoryOf(::PersonViewModel)
    single {
        SettingsViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(),
        ).also { vm ->
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
    factory {
        AddonViewModel(
            get(),
            get(),
            get(),
            addonPolicyRepository = get(),
            pandaClient = get(),
            integrationSecretStore = get(),
        )
    }
    single { com.torve.data.panda.PandaApiClient(get(), get()) }
    // Singleton mirror so provider-health checkers can read the Panda
    // wizard's current config without holding a (factory-bound) VM
    // reference. The VM publishes its `_state` here on every update.
    single { com.torve.presentation.panda.PandaConfigStateStore() }
    factory {
        com.torve.presentation.panda.PandaSetupViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(),
            configStateStore = get(),
        )
    }
    // NzbDAV / Usenet-resolver data layer. Talks only to the Torve backend;
    // never speaks raw NzbDAV/WebDAV/SABnzbd. Settings VM + warm/resolve
    // coordinators land in later prompts and inject UsenetRepository.
    single {
        com.torve.data.usenet.UsenetApi(
            httpClient = get(),
            authClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single {
        com.torve.data.support.SupportApi(
            httpClient = get(),
            authClient = get(),
            baseUrlProvider = { com.torve.data.auth.AuthClient.DEFAULT_BASE_URL },
            installationIdProvider = { get<com.torve.domain.device.DeviceIdProvider>().getDeviceId() },
        )
    }
    single<com.torve.data.usenet.UsenetRepository> {
        com.torve.data.usenet.UsenetRepositoryImpl(
            api = get(),
            secretStore = get(),
        )
    }
    // Analytics surface. Default is a no-op sink — feature code can emit
    // safely and a real backend integration can swap this without VM or
    // coordinator changes. See com.torve.domain.telemetry.TelemetryEmitter.
    //
    // Prompt 12 hardening: every selected sink is wrapped in
    // [RedactingTelemetryEmitter] so a future production sink inherits
    // redaction without per-call-site care. The platform module overrides
    // this binding when it can read TORVE_TELEMETRY_SINK from the env;
    // otherwise we default to the wrapped NoOp.
    single<com.torve.domain.telemetry.TelemetryEmitter> {
        com.torve.domain.telemetry.RedactingTelemetryEmitter(
            com.torve.domain.telemetry.CompositeTelemetryEmitter(
                listOf(
                    com.torve.domain.telemetry.NoOpTelemetryEmitter(),
                    com.torve.data.telemetry.StreamPathBackendTelemetryEmitter(get<com.torve.data.telemetry.StreamPathTelemetryApi>()),
                ),
            ),
        )
    }
    factory {
        com.torve.presentation.usenet.NzbdavSetupViewModel(
            repository = get(),
            secretStore = get(),
        )
    }
    single { com.torve.domain.streams.UsenetWarmCoordinator(repository = get(), telemetry = get()) }
    single { com.torve.domain.streams.UsenetResolveCoordinator(repository = get(), telemetry = get()) }
    single { com.torve.domain.streams.UsenetJobPoller(repository = get(), telemetry = get()) }
    single {
        ChannelsViewModel(
            channelRepo = get(),
            prefsRepo = get(),
            localSettingsRepo = get(),
            catchupResolver = get(),
            backgroundDispatcher = com.torve.util.ioDispatcher,
            playlistBackup = get(),
            settingsRefreshNotifier = get(),
            // Prompt 10C: apply persisted EPG corrections at guide
            // build time + emit health to drive the stale banner.
            epgCorrectionRepository = get(),
            epgCorrectionViewModel = get(),
        )
    }
    factory {
        CalendarViewModel(
            traktApi = get(),
            tokenStore = get(),
            prefsRepo = get(),
        )
    }
    factory { DownloadViewModel(get(), contentPolicyRepository = get(), contentPolicyFilter = ContentPolicyFilter()) }
    factory { DownloadCatalogueViewModel(get(), get(), get(), get(), contentPolicyRepository = get(), contentPolicyFilter = ContentPolicyFilter()) }
    factoryOf(::ProfileViewModel)
    // Singleton so NavGraph, PaywallScreen, and all other screens share
    // the same premium state — avoids stale locks after purchase/login.
    single {
        SubscriptionViewModel(
            subscriptionRepo = get(),
            rebateCodeApi = get(),
            deviceIdProvider = get(),
            authClient = get(),
            entitlementApi = get(),
            billingApi = get(),
            prefsRepo = get(),
            strings = get(),
            deviceRegistrationNotifier = get(),
        )
    }
    factoryOf(::DeviceGovernanceViewModel)
    factory { SetupWizardViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { WatchlistViewModel(get(), get(), get(), get(), get()) }
    factory { DiscoverViewModel() }
    factoryOf(::MoodMatcherViewModel)
    factoryOf(::MdbListViewModel)
    factoryOf(::StatsViewModel)
    factoryOf(::WatchStatsViewModel)
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
            traktApi = getOrNull(),
        )
    }
    single { BetaProgramViewModel(get(), get()) }
    factory { SensitiveMaterialSettingsViewModel(get()) }
    factory { com.torve.presentation.jellyfin.JellyfinBrowserViewModel(get()) }
}

/**
 * Per-provider secret key. Same mapping as DesktopProviderHealthInit /
 * AndroidProviderHealthInit; lifted here so the DebridIntentValidator
 * single can resolve a provider without pulling in either platform init.
 */
private fun debridSecretKey(
    provider: com.torve.domain.model.DebridServiceType,
): com.torve.domain.integrations.IntegrationSecretKey = when (provider) {
    com.torve.domain.model.DebridServiceType.REAL_DEBRID ->
        com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
    com.torve.domain.model.DebridServiceType.ALL_DEBRID ->
        com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
    com.torve.domain.model.DebridServiceType.PREMIUMIZE ->
        com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
    com.torve.domain.model.DebridServiceType.TORBOX ->
        com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_TORBOX
}
