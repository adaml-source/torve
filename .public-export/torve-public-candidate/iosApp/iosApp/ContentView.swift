import SwiftUI
import shared

struct ContentView: View {
    @State private var router = AppRouter()

    var body: some View {
        TabView(selection: $router.selectedTab) {
            homeTab
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(AppTab.home)

            moviesTab
                .tabItem { Label("Movies", systemImage: "film") }
                .tag(AppTab.movies)

            tvShowsTab
                .tabItem { Label("TV Shows", systemImage: "tv") }
                .tag(AppTab.tvShows)

            channelsTab
                .tabItem { Label("Channels", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(AppTab.channels)

            watchlistTab
                .tabItem { Label("Watchlist", systemImage: "bookmark.fill") }
                .tag(AppTab.watchlist)

            settingsTab
                .tabItem { Label("Settings", systemImage: "gear") }
                .tag(AppTab.settings)
        }
        .tint(SVColor.amber)
        .environment(router)
    }

    // MARK: - Tabs

    private var homeTab: some View {
        NavigationStack(path: $router.homePath) {
            HomeScreen()
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    private var moviesTab: some View {
        NavigationStack(path: $router.moviesPath) {
            CatalogScreen(mediaType: "movie")
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    private var tvShowsTab: some View {
        NavigationStack(path: $router.tvShowsPath) {
            CatalogScreen(mediaType: "tv")
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    private var channelsTab: some View {
        NavigationStack(path: $router.channelsPath) {
            ChannelsScreen()
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    private var watchlistTab: some View {
        NavigationStack(path: $router.watchlistPath) {
            WatchlistScreen()
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    private var settingsTab: some View {
        NavigationStack(path: $router.settingsPath) {
            SettingsScreen()
                .navigationDestination(for: Route.self) { route in
                    routeDestination(route)
                }
        }
    }

    // MARK: - Route Destination

    @ViewBuilder
    private func routeDestination(_ route: Route) -> some View {
        switch route {
        case .detail(let mediaId, let mediaType):
            DetailScreen(mediaId: mediaId, mediaType: mediaType)
        case .person(let personId):
            PersonScreen(personId: personId)
        case .player(let streamUrl, let title, let fallbackUrl):
            PlayerScreen(streamUrl: streamUrl, title: title, fallbackUrl: fallbackUrl)
        case .search:
            SearchScreen()
        case .seeAll(let title, let category, let mediaType):
            SeeAllScreen(title: title, category: category, mediaType: mediaType)
        case .watchlist:
            WatchlistScreen()
        case .downloads:
            DownloadScreen()
        case .downloadCatalogue:
            DownloadCatalogueScreen()
        case .downloadedShowDetail(let showTitle):
            DownloadedShowDetailScreen(showTitle: showTitle)
        case .calendar:
            CalendarScreen()
        case .discover:
            DiscoverScreen()
        case .moodMatcher:
            MoodMatcherScreen()
        case .stats:
            StatsScreen()
        case .profile:
            ProfileScreen()
        case .profileTab:
            ProfileTabScreen()
        case .provider(let providerId, let providerName):
            CatalogScreen(mediaType: "movie")
                .navigationTitle(providerName)
        case .genreCatalog(let mediaType, _, let genreName):
            CatalogScreen(mediaType: mediaType)
                .navigationTitle(genreName)
        case .setupWizard:
            // Wire the credential-first hub's actions to the root nav so
            // Plex/Jellyfin and Usenet "Set up" buttons land on real
            // destinations rather than no-ops, and "Continue" / "Get
            // Started" pop the wizard off the active tab's nav stack.
            SetupWizardScreen(
                onComplete: { router.popToRoot() },
                onOpenIntegrations: { router.navigate(to: .integrations) },
                onOpenPandaSetup: { router.navigate(to: .pandaSetup) }
            )
        case .login:
            LoginScreen()
        case .pandaSetup:
            PandaSetupScreen()
        case .traktSettings:
            TraktSettingsScreen()
        case .simklSettings:
            SimklSettingsScreen()
        case .addonManager:
            AddonManagerScreen()
        case .integrations:
            IntegrationsScreen()
        case .streamGroups:
            StreamGroupsScreen()
        case .homeLayout:
            HomeLayoutScreen()
        case .ratingSettings:
            RatingSettingsScreen()
        case .regexPatterns:
            RegexPatternsScreen()
        case .mdbListSettings:
            MdbListSettingsScreen()
        case .cardStyleSettings:
            CardStyleSettingsScreen()
        case .streamingServices:
            StreamingServicesScreen()
        case .customSectionEditor:
            CustomSectionEditorScreen()
        case .playbackSettings:
            PlaybackSettingsScreen()
        case .diagnostics:
            DiagnosticsScreen()
        case .privacyPolicy:
            PrivacyPolicyScreen()
        case .backupSync:
            BackupSyncScreen()
        case .account:
            AccountScreen()
        case .devices:
            DevicesScreen()
        case .manageDevices:
            ManageDevicesScreen()
        case .deviceLimitReached:
            DeviceLimitReachedScreen()
        case .legal:
            LegalScreen()
        case .transferSend:
            SecretsTransferSendScreen()
        case .transferReceive:
            SecretsTransferReceiveScreen()
        case .transferDiagnostics:
            TransferDiagnosticsScreen()
        default:
            Text("Not implemented")
        }
    }
}
