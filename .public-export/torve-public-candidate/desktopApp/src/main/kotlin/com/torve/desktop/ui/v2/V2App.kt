package com.torve.desktop.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.download.DesktopDownloadManager
import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.playback.DesktopPlayerPhase
import com.torve.desktop.playback.toDesktopPlaybackRequest
import com.torve.desktop.vlc.VlcPlaybackEngine
import com.torve.desktop.search.DesktopSearchController
import com.torve.desktop.ui.detail.DesktopDetailControllerKey
import com.torve.desktop.ui.detail.DesktopFullDetailRoute
import com.torve.desktop.ui.detail.DesktopPlaybackOrigin
import com.torve.desktop.ui.detail.DesktopPlaybackOriginSurface
import com.torve.desktop.ui.playback.DesktopDockVisualState
import com.torve.desktop.ui.playback.DesktopPlaybackDock
import com.torve.desktop.ui.playback.DesktopSourcePickerOverlay
import com.torve.desktop.vlc.VlcComposePlayerSurface
import com.torve.desktop.ui.playback.desktopDockVisualState
import com.torve.desktop.ui.shell.DesktopFloatingUserBadge
import com.torve.desktop.ui.shell.DesktopSourcePickerRoute
import com.torve.desktop.ui.shell.matches
import com.torve.desktop.ui.shell.playbackSourceSurfaceLabel
import com.torve.desktop.ui.shell.restorePlaybackOrigin
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.rememberBackdropLuminance
import com.torve.desktop.ui.v2.detail.V2DetailPage
import com.torve.desktop.ui.v2.home.V2HomePage
import com.torve.desktop.ui.v2.library.V2LibraryPage
import com.torve.desktop.ui.v2.live.V2LivePage
import com.torve.desktop.ui.v2.movies.V2MoviesPage
import com.torve.desktop.ui.v2.nav.V2Destination
import com.torve.desktop.ui.v2.nav.V2NavRail
import com.torve.desktop.ui.v2.person.V2PersonPage
import com.torve.desktop.ui.v2.recording.recordingFailureNotification
import com.torve.desktop.ui.v2.recording.recordingFolderValidationError
import com.torve.desktop.ui.v2.search.V2SearchPage
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
import com.torve.desktop.ui.v2.seeall.V2SeeAllPage
import com.torve.desktop.ui.v2.settings.V2SettingsPage
import com.torve.desktop.ui.v2.shows.V2ShowsPage
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.toMediaItem
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.launch

private sealed interface V2DetailStackEntry {
    data class Detail(val route: DesktopFullDetailRoute) : V2DetailStackEntry
    data class Person(val personId: Int) : V2DetailStackEntry
}

@Composable
fun V2App(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    playerController: DesktopPlayerController,
    windowState: androidx.compose.ui.window.WindowState? = null,
    libraryDetailController: DesktopSearchController,
    searchController: DesktopSearchController,
    homeViewModel: HomeViewModel,
    searchViewModel: SearchViewModel,
    settingsViewModel: SettingsViewModel,
    pandaSetupViewModel: PandaSetupViewModel,
    channelsViewModel: ChannelsViewModel,
    channelRepository: ChannelRepository,
    addonRepository: AddonRepository,
    watchlistViewModel: WatchlistViewModel,
    mediaFavoritesRepository: MediaFavoritesRepository,
    accountSessionCoordinator: AccountSessionCoordinator,
    downloadViewModel: DownloadViewModel,
    downloadCatalogueViewModel: DownloadCatalogueViewModel,
    downloadManager: DesktopDownloadManager,
    moviesCatalogViewModel: CatalogViewModel,
    tvShowsCatalogViewModel: CatalogViewModel,
    metadataRepository: MetadataRepository,
    seeAllViewModel: SeeAllViewModel,
    deviceGovernanceViewModel: com.torve.presentation.device.DeviceGovernanceViewModel,
    castController: com.torve.desktop.cast.DesktopCastController? = null,
    tray: com.torve.desktop.tray.DesktopSystemTray? = null,
    statsViewModel: com.torve.presentation.stats.StatsViewModel? = null,
    setupRecoveryMessage: String? = null,
    setupIntentsViewModel: com.torve.presentation.setup.SetupIntentsViewModel? = null,
    onboardingDeepLink: com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink? = null,
    providerHealthInit: com.torve.desktop.providerhealth.DesktopProviderHealthInit? = null,
) {
    val colors = TorveDesktopThemeTokens.colors

    // One-shot: hydrate the provider-health repository and register the
    // desktop's set of checkers with the shared coordinator.
    androidx.compose.runtime.LaunchedEffect(providerHealthInit, playerController) {
        providerHealthInit?.start()
        providerHealthInit?.startPlaybackBridge(playerController)
    }

    // In-app alert popup. Tray balloons are unreliable when the main
    // window is fullscreen on Windows, so desktopNotify() also pushes
    // the message to desktopAlertFlow which we render here as a
    // guaranteed-visible AlertDialog.
    val pendingAlert by com.torve.desktop.desktopAlertFlow.collectAsState()
    pendingAlert?.let { alert ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.torve.desktop.desktopAlertDismiss() },
            title = { androidx.compose.material3.Text(alert.title) },
            text = { androidx.compose.material3.Text(alert.body) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { com.torve.desktop.desktopAlertDismiss() },
                ) { androidx.compose.material3.Text(ds("OK")) }
            },
        )
    }

    // Scroll states per destination
    val homeScroll = rememberScrollState()
    val moviesScroll = rememberScrollState()
    val showsScroll = rememberScrollState()
    val searchScroll = rememberScrollState()
    val libraryScroll = rememberScrollState()

    // Navigation state
    var destination by remember { mutableStateOf(V2Destination.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Adult-mode toggle - surfaces a dedicated nav rail entry. Persisted
    // locally via AdultModePreferences so it survives across launches
    // independently of the server-driven content policy state.
    var adultModeEnabled by remember {
        mutableStateOf(com.torve.desktop.adult.AdultModePreferences.isEnabled())
    }
    var aiSearchOpen by remember { mutableStateOf(false) }
    // Compatibility overlay state for legacy call sites. Product features
    // are no longer gated on paid state.
    var premiumGateReason by remember { mutableStateOf<String?>(null) }
    val hasPremium = true
    fun premiumGuard(reason: String, action: () -> Unit) {
        action()
    }
    // Re-read the persisted toggle whenever the user closes Settings so
    // the nav rail surfaces / hides the entry without a restart.
    LaunchedEffect(settingsOpen) {
        if (!settingsOpen) {
            adultModeEnabled = com.torve.desktop.adult.AdultModePreferences.isEnabled()
        }
    }
    var pandaSetupOpen by remember { mutableStateOf(false) }
    var manageDevicesOpen by remember { mutableStateOf(false) }
    var deviceLimitReachedOpen by remember { mutableStateOf(false) }
    // Prompt 10B: My Recordings page reachable from Settings.
    var recordingsPageOpen by remember { mutableStateOf(false) }

    // Consume any deep-link the credential-first onboarding hub queued
    // (Plex/Jellyfin / Usenet "Set up" buttons). One-shot: the holder
    // returns the target exactly once, then nulls itself out. The
    // category name flows into V2SettingsPage so Integrations opens
    // directly on the Plex/Jellyfin section instead of the SOURCES
    // overview.
    var settingsLandingCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(onboardingDeepLink) {
        when (onboardingDeepLink?.consume()) {
            com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink.Target.Integrations -> {
                settingsLandingCategory = "INTEGRATIONS"
                settingsOpen = true
            }
            com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink.Target.PandaSetup -> {
                pandaSetupOpen = true
            }
            null -> Unit
        }
    }
    var statsOpen by remember { mutableStateOf(false) }
    var accessMenuExpanded by remember { mutableStateOf(false) }
    var liveTvNavigationPending by remember { mutableStateOf(false) }
    var pipMode by remember { mutableStateOf(false) }
    // Command palette (Cmd+K). Declared here so handleShortcut below can
    // capture it; the overlay is rendered way down in the root Box.
    var commandPaletteOpen by remember { mutableStateOf(false) }
    var keyboardHelpOpen by remember { mutableStateOf(false) }

    // Detail / playback state
    var detailStack by remember { mutableStateOf<List<V2DetailStackEntry>>(emptyList()) }
    var sourcePickerRoute by remember { mutableStateOf<DesktopSourcePickerRoute?>(null) }
    var playbackOrigin by remember { mutableStateOf<DesktopPlaybackOrigin?>(null) }
    var dockDismissed by remember { mutableStateOf(false) }
    // Source picker is opened for download intent (keeps the embedded player from flashing).
    var downloadIntent by remember { mutableStateOf(false) }

    // Shared NZB poster cache - both movies and TV go through the
    // same TMDB lookup cache. Held at this level so all NZB-driven
    // surfaces benefit from a hit on either type.
    val nzbPosterCache = remember {
        com.torve.desktop.adult.NzbPosterCache(
            org.koin.java.KoinJavaComponent.get<com.torve.data.metadata.TmdbApiClient>(
                com.torve.data.metadata.TmdbApiClient::class.java,
            ),
        )
    }

    // Shared "Latest on Usenet" catalog. Owned at this level so the
    // Movies tab shelf and the see-all page share the same cached
    // matched releases. The service is cheap when no indexer is wired.
    val nzbCatalogService = remember {
        com.torve.desktop.adult.NzbCatalogService(
            newznab = com.torve.desktop.adult.NewznabClient(),
            posterCache = nzbPosterCache,
            ratingsEnricher = org.koin.java.KoinJavaComponent.get<com.torve.data.mdblist.RatingsEnricher>(
                com.torve.data.mdblist.RatingsEnricher::class.java,
            ),
            // Lambda so the lookup happens lazily - gives the user time
            // to enter a key in Settings without restarting.
            mdbListApiKey = {
                kotlinx.coroutines.runBlocking {
                    runCatching {
                        org.koin.java.KoinJavaComponent.get<com.torve.domain.integrations.IntegrationSecretStore>(
                            com.torve.domain.integrations.IntegrationSecretStore::class.java,
                        ).get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY).orEmpty()
                    }.getOrDefault("")
                }
            },
        )
    }

    // TV-show counterpart to nzbCatalogService - separate state, same
    // poster cache + ratings enricher.
    val nzbTvCatalogService = remember {
        com.torve.desktop.adult.NzbTvCatalogService(
            newznab = com.torve.desktop.adult.NewznabClient(),
            posterCache = nzbPosterCache,
            ratingsEnricher = org.koin.java.KoinJavaComponent.get<com.torve.data.mdblist.RatingsEnricher>(
                com.torve.data.mdblist.RatingsEnricher::class.java,
            ),
            mdbListApiKey = {
                kotlinx.coroutines.runBlocking {
                    runCatching {
                        org.koin.java.KoinJavaComponent.get<com.torve.domain.integrations.IntegrationSecretStore>(
                            com.torve.domain.integrations.IntegrationSecretStore::class.java,
                        ).get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY).orEmpty()
                    }.getOrDefault("")
                }
            },
        )
    }

    // Person page state
    val personViewModel = remember {
        PersonViewModel(
            metadataRepo = metadataRepository,
            ratingsEnricher = org.koin.java.KoinJavaComponent.get<com.torve.data.mdblist.RatingsEnricher>(
                com.torve.data.mdblist.RatingsEnricher::class.java,
            ),
            prefsRepo = org.koin.java.KoinJavaComponent.get<com.torve.domain.repository.PreferencesRepository>(
                com.torve.domain.repository.PreferencesRepository::class.java,
            ),
            integrationSecretStore = org.koin.java.KoinJavaComponent.get<com.torve.domain.integrations.IntegrationSecretStore>(
                com.torve.domain.integrations.IntegrationSecretStore::class.java,
            ),
        )
    }

    // See-all page state
    var seeAllRoute by remember { mutableStateOf<SeeAllRequest?>(null) }

    // Collected states
    val homeState by homeViewModel.state.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val channelsState by channelsViewModel.state.collectAsState()
    val playerState by playerController.state.collectAsState()
    val searchDetailState by searchController.state.collectAsState()
    val libraryDetailState by libraryDetailController.state.collectAsState()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val mediaFavoritesState by mediaFavoritesRepository.state.collectAsState()
    val downloadState by downloadViewModel.state.collectAsState()
    val downloadCatalogueState by downloadCatalogueViewModel.state.collectAsState()
    val desktopDownloadState by downloadManager.state.collectAsState()
    val moviesCatalogState by moviesCatalogViewModel.state.collectAsState()
    val tvShowsCatalogState by tvShowsCatalogViewModel.state.collectAsState()
    val deviceGovernanceState by deviceGovernanceViewModel.state.collectAsState()
    val homeSectionConfigs by homeViewModel.sectionConfigs.collectAsState()
    val homeEnabledServiceIds by homeViewModel.enabledServiceIds.collectAsState()
    val homeProviderLogos by homeViewModel.providerLogos.collectAsState()

    // Fetch access-state once per signed-in session so the device-limit overlay
    // can fire when the backend reports `device_cap_reached`.
    LaunchedEffect(authState.user?.id) {
        if (authState.user != null) {
            deviceGovernanceViewModel.fetchAccessState()
            mediaFavoritesRepository.refresh(force = true)
        }
    }

    // EPG prefetch - keep the live guide fresh without the user having to
    // open settings to refresh. Runs every 30 min while the app is open and
    // a playlist is selected. Idle-safe: skips when refresh is already in
    // flight (ensureEpgLoaded checks `epgRefreshJob?.isActive`).
    LaunchedEffect(channelsState.selectedPlaylistId) {
        while (channelsState.selectedPlaylistId != null) {
            kotlinx.coroutines.delay(30 * 60 * 1000L)
            channelsViewModel.ensureEpgLoaded(forceRefresh = false)
        }
    }

    LaunchedEffect(deviceGovernanceState.showDeviceLimitReached) {
        if (deviceGovernanceState.showDeviceLimitReached) {
            deviceLimitReachedOpen = true
            settingsOpen = false
            pandaSetupOpen = false
            manageDevicesOpen = false
            detailStack = emptyList()
            seeAllRoute = null
        }
    }
    val signedInDisplayName = authState.user?.displayName?.trim().orEmpty()
    val signedInEmail = authState.user?.email?.trim().orEmpty()
    val signedInPrimaryLabel = signedInDisplayName.ifBlank {
        signedInEmail.ifBlank { "Signed in" }
    }
    val signedInSecondaryLabel = signedInEmail.takeIf {
        it.isNotBlank() && !it.equals(signedInPrimaryLabel, ignoreCase = true)
    }
    val liveTvPlaylistName = channelsState.playlists
        .firstOrNull { it.id == channelsState.selectedPlaylistId }
        ?.name
        ?: channelsState.playlists.firstOrNull()?.name
    val liveTvSyncBlocking = remember(
        channelsState.playlists,
        channelsState.selectedPlaylistId,
        channelsState.isLoading,
        channelsState.isLoadingChannels,
        channelsState.categories,
        channelsState.channels,
        channelsState.groupedChannels,
        channelsState.error,
    ) {
        channelsState.playlists.isNotEmpty() && (
            channelsState.isLoading ||
                channelsState.isLoadingChannels ||
                channelsState.selectedPlaylistId == null ||
                (
                    channelsState.error == null &&
                        channelsState.categories.isEmpty() &&
                        channelsState.channels.isEmpty() &&
                        channelsState.groupedChannels.isEmpty()
                    )
            )
    }

    // Derive active hero backdrop URL for adaptive nav rail luminance
    val activeHeroBackdropUrl: String? = when {
        settingsOpen -> null
        detailStack.lastOrNull() is V2DetailStackEntry.Person -> personViewModel.state.collectAsState().value.credits.firstOrNull { !it.backdropUrl.isNullOrBlank() }?.backdropUrl
        detailStack.lastOrNull() is V2DetailStackEntry.Detail -> {
            val route = (detailStack.last() as V2DetailStackEntry.Detail).route
            val rs = when (route.controllerKey) { DesktopDetailControllerKey.LIBRARY -> libraryDetailState; DesktopDetailControllerKey.SEARCH -> searchDetailState }
            rs.detailItem?.backdropUrl ?: rs.detailItem?.posterUrl
        }
        else -> when (destination) {
            V2Destination.HOME -> homeState.continueWatching.firstOrNull()?.backdropUrl ?: homeState.recommendedItems.firstOrNull()?.item?.backdropUrl ?: homeState.watchlistItems.firstOrNull()?.backdropUrl
            V2Destination.MOVIES -> moviesCatalogState.trendingItems.firstOrNull()?.backdropUrl
            V2Destination.TV_SHOWS -> tvShowsCatalogState.trendingItems.firstOrNull()?.backdropUrl
            V2Destination.LIBRARY -> homeState.continueWatching.firstOrNull()?.backdropUrl ?: homeState.watchlistItems.firstOrNull()?.backdropUrl
            else -> null
        }
    }
    val navRailLuminance = rememberBackdropLuminance(activeHeroBackdropUrl)

    // Map V2Destination → old DesktopDestination for playback origin
    fun v2DestToOld(): com.torve.desktop.ui.navigation.DesktopDestination = when (destination) {
        V2Destination.HOME -> com.torve.desktop.ui.navigation.DesktopDestination.HOME
        V2Destination.MOVIES -> com.torve.desktop.ui.navigation.DesktopDestination.MOVIES
        V2Destination.TV_SHOWS -> com.torve.desktop.ui.navigation.DesktopDestination.TV_SHOWS
        V2Destination.SEARCH -> com.torve.desktop.ui.navigation.DesktopDestination.SEARCH
        V2Destination.LIBRARY -> com.torve.desktop.ui.navigation.DesktopDestination.LIBRARY
        V2Destination.LIVE_TV -> com.torve.desktop.ui.navigation.DesktopDestination.LIVE_TV
        V2Destination.RECORDINGS -> com.torve.desktop.ui.navigation.DesktopDestination.LIBRARY
        V2Destination.ADULT -> com.torve.desktop.ui.navigation.DesktopDestination.MOVIES
        V2Destination.SPORTS -> com.torve.desktop.ui.navigation.DesktopDestination.MOVIES
    }

    fun detailControllerFor(key: DesktopDetailControllerKey) = when (key) {
        DesktopDetailControllerKey.LIBRARY -> libraryDetailController
        DesktopDetailControllerKey.SEARCH -> searchController
    }

    fun captureOrigin(item: MediaItem, surface: DesktopPlaybackOriginSurface, controllerKey: DesktopDetailControllerKey = DesktopDetailControllerKey.LIBRARY, sn: Int? = null, ep: Int? = null): DesktopPlaybackOrigin {
        return DesktopPlaybackOrigin(destination = v2DestToOld(), controllerKey = controllerKey, item = item, itemId = item.id, surface = surface, seasonNumber = sn, episodeNumber = ep).also {
            playbackOrigin = it; dockDismissed = false
        }
    }

    fun restoreOrigin(origin: DesktopPlaybackOrigin) {
        restorePlaybackOrigin(
            origin,
            libraryDetailController,
            libraryDetailState,
            searchController,
            searchDetailState,
            { detailStack = listOf(V2DetailStackEntry.Detail(it)) },
            { detailStack = emptyList() },
        )
    }

    fun clearPlaybackRestoreTarget() {
        playbackOrigin = null
        sourcePickerRoute = null
        downloadIntent = false
    }

    /**
     * TorBox-resolve [hint] and hand the resulting stream URL to the
     * pre-resolved playback path. Carries the full [item] through so
     * the player chrome renders the show's TMDB logo, ratings, and
     * the proper TV / movie type badge instead of plain text. For TV,
     * parses the NZB filename to surface season + episode in the
     * top-left chrome.
     */
    fun playNzbHint(item: MediaItem, hint: com.torve.desktop.adult.NewznabItem) {
        val pandaState = pandaSetupViewModel.state.value
        val torboxKey = if (pandaState.downloadClient == "torbox") {
            pandaState.downloadClientApiKey.takeIf {
                it.isNotBlank() && !it.contains("redact", ignoreCase = true)
            }.orEmpty()
        } else ""
        if (torboxKey.isBlank()) {
            premiumGateReason = "TorBox isn't configured. Open Manage Panda → Download client → TorBox to enable usenet playback."
            return
        }
        val isSeries = item.type == com.torve.domain.model.MediaType.SERIES
        val sourceSurface = if (isSeries) "shows" else "movies"
        // For TV, build the episode context now so it's pre-baked into
        // the synthetic session. Falls back to season/episode parsed
        // from the NZB filename when the upstream MediaItem doesn't
        // already encode it.
        val episodeContext = if (isSeries) {
            val parsed = com.torve.desktop.adult.NzbTvReleaseTitleParser.parse(hint.title)
            val sn = parsed?.seasonNumber
            val ep = parsed?.episodeNumber
            if (sn != null && ep != null) {
                com.torve.desktop.playback.DesktopPlaybackEpisodeContext(
                    seasonNumber = sn,
                    episodeNumber = ep,
                    episodeTitle = null,
                    mode = com.torve.desktop.playback.DesktopEpisodeResolutionMode.REQUESTED,
                )
            } else null
        } else null
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val torbox = com.torve.desktop.adult.TorBoxUsenetClient()
            val res = torbox.resolve(hint.nzbUrl, torboxKey) { _ -> }
            res.onSuccess { resolved ->
                // Best-effort logo fetch - TMDB's /tv/{id}/images
                // endpoint returns the show's branded title art. If
                // the MediaItem already carries one (because the
                // detail page hydrated it), we reuse that and skip
                // the call.
                val enriched = if (item.tmdbId != null && item.logoUrl.isNullOrBlank()) {
                    runCatching {
                        val tmdb = org.koin.java.KoinJavaComponent.get<com.torve.data.metadata.TmdbApiClient>(
                            com.torve.data.metadata.TmdbApiClient::class.java,
                        )
                        val typePath = if (isSeries) "tv" else "movie"
                        val imgs = tmdb.getImages(typePath, item.tmdbId!!)
                        val logoPath = imgs.logos.firstOrNull()?.filePath
                        if (logoPath != null) {
                            item.copy(logoUrl = "https://image.tmdb.org/t/p/w500$logoPath")
                        } else item
                    }.getOrDefault(item)
                } else item
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playerController.openPreResolvedStream(
                        streamUrl = resolved.streamUrl,
                        title = item.title,
                        sizeBytes = resolved.sizeBytes,
                        sourceSurface = sourceSurface,
                        mediaItem = enriched,
                        episodeContext = episodeContext,
                    )
                }
            }
        }
    }

    /** Returns true if a stashed NZB hint matched this play request and
     *  has been dispatched. The caller should bail out of any
     *  conventional playback path when this returns true. */
    fun maybePlayNzbHint(item: MediaItem, sn: Int?, ep: Int?): Boolean {
        val tmdbId = item.tmdbId ?: return false
        val isSeries = item.type == com.torve.domain.model.MediaType.SERIES
        val hint: com.torve.desktop.adult.NewznabItem? = if (isSeries) {
            com.torve.desktop.adult.NzbPlaybackHints.findTvEpisode(tmdbId, sn, ep)
                ?: nzbTvCatalogService.findReleaseFor(tmdbId, sn, ep)
        } else {
            com.torve.desktop.adult.NzbPlaybackHints.peek(tmdbId)
                ?: nzbCatalogService.findReleaseFor(tmdbId)?.nzb
        }
        if (hint == null) {
            println("TORVE NZB ┃ no hint for tmdb=$tmdbId series=$isSeries sn=$sn ep=$ep")
            return false
        }
        println("TORVE NZB ┃ playing hint title='${hint.title.take(80)}' tmdb=$tmdbId series=$isSeries sn=$sn ep=$ep")
        playNzbHint(item, hint)
        return true
    }

    fun launchPlayback(item: MediaItem, surface: DesktopPlaybackOriginSurface = DesktopPlaybackOriginSurface.ROW, controllerKey: DesktopDetailControllerKey = DesktopDetailControllerKey.LIBRARY, sn: Int? = null, ep: Int? = null) {
        captureOrigin(item, surface, controllerKey, sn, ep)
        // NZB short-circuit (movies + TV).
        if (maybePlayNzbHint(item, sn, ep)) return
        sourcePickerRoute = null
        playerController.open(item.toDesktopPlaybackRequest(sourceSurface = playbackSourceSurfaceLabel(v2DestToOld(), surface), seasonNumber = sn, episodeNumber = ep))
        playerController.play()
    }

    fun prepareSourcePicker(
        item: MediaItem,
        surface: DesktopPlaybackOriginSurface = DesktopPlaybackOriginSurface.ROW,
        controllerKey: DesktopDetailControllerKey = DesktopDetailControllerKey.LIBRARY,
        sn: Int? = null,
        ep: Int? = null,
        forDownload: Boolean = false,
    ) {
        // For non-download intents, prefer the NZB hint so the user
        // never lands on the empty source picker for items they
        // explicitly clicked from "Latest on Usenet". Download intent
        // still goes through the standard picker (we'd need separate
        // download wiring for NZB sources).
        val origin = captureOrigin(item, surface, controllerKey, sn, ep)
        if (!forDownload && maybePlayNzbHint(item, sn, ep)) return
        val request = item.toDesktopPlaybackRequest(sourceSurface = playbackSourceSurfaceLabel(v2DestToOld(), surface), seasonNumber = sn, episodeNumber = ep)
        sourcePickerRoute = DesktopSourcePickerRoute(request = request, origin = origin)
        downloadIntent = forDownload
        if (request.matches(playerState.currentRequest)) {
            if (playerState.preparedSession == null && playerState.phase != DesktopPlayerPhase.RESOLVING) playerController.prepareCurrentRequest()
        } else {
            playerController.prepareRequest(request)
        }
    }

    fun openFullDetail(item: MediaItem, controllerKey: DesktopDetailControllerKey = DesktopDetailControllerKey.LIBRARY) {
        detailControllerFor(controllerKey).selectResult(item)
        detailStack = listOf(V2DetailStackEntry.Detail(DesktopFullDetailRoute(destination = v2DestToOld(), controllerKey = controllerKey, item = item)))
    }

    fun pushFullDetail(item: MediaItem, controllerKey: DesktopDetailControllerKey = DesktopDetailControllerKey.LIBRARY) {
        detailControllerFor(controllerKey).selectResult(item)
        detailStack = detailStack + V2DetailStackEntry.Detail(DesktopFullDetailRoute(destination = v2DestToOld(), controllerKey = controllerKey, item = item))
    }

    fun pushPersonDetail(personId: Int) {
        detailStack = detailStack + V2DetailStackEntry.Person(personId)
    }

    fun popDetailStack() {
        if (detailStack.isNotEmpty()) {
            val nextStack = detailStack.dropLast(1)
            detailStack = nextStack
            (nextStack.lastOrNull() as? V2DetailStackEntry.Detail)?.route?.let { route ->
                detailControllerFor(route.controllerKey).selectResult(route.item)
            }
        }
    }

    // Push top Continue-Watching items into the tray menu and handle clicks
    // by routing back through launchPlayback. No-op when the tray is null
    // (platform unsupported).
    LaunchedEffect(tray, homeState.continueWatching) {
        if (tray == null) return@LaunchedEffect
        tray.setContinueWatching(
            homeState.continueWatching.take(5).map { wp ->
                val episodeTag = if (wp.seasonNumber != null && wp.episodeNumber != null) {
                    " - S${wp.seasonNumber}E${wp.episodeNumber}"
                } else ""
                com.torve.desktop.tray.TrayContinueWatchingItem(
                    mediaId = wp.mediaId,
                    label = (wp.showTitle ?: wp.title) + episodeTag,
                    seasonNumber = wp.seasonNumber,
                    episodeNumber = wp.episodeNumber,
                )
            },
        )
        tray.onPlayContinueWatching = { item ->
            val match = homeState.continueWatching.firstOrNull { it.mediaId == item.mediaId }
            val mediaItem = if (match != null) {
                MediaItem(
                    id = match.mediaId,
                    title = match.showTitle ?: match.title,
                    type = match.mediaType,
                    posterUrl = match.posterUrl,
                    backdropUrl = match.backdropUrl,
                )
            } else {
                MediaItem(
                    id = item.mediaId,
                    title = item.label,
                    type = com.torve.domain.model.MediaType.SERIES,
                )
            }
            launchPlayback(mediaItem, sn = item.seasonNumber, ep = item.episodeNumber)
        }
    }

    // Determine if the embedded player should be shown
    val vlcEngine = playerController.playbackEngine as? VlcPlaybackEngine
    val mpvEngine = playerController.playbackEngine as? com.torve.desktop.mpv.MpvPlaybackEngine
    val isEmbeddedActive = !downloadIntent && playerController.isEmbedded &&
        playerState.phase in setOf(
            DesktopPlayerPhase.RESOLVING,
            DesktopPlayerPhase.OPENING,
            DesktopPlayerPhase.BUFFERING,
            DesktopPlayerPhase.PLAYING,
            DesktopPlayerPhase.PAUSED,
        )
    val isLiveTvPlayback = playerState.currentRequest?.sourceSurface == "live_tv" ||
        playerState.currentRequest?.mediaId?.startsWith("live:") == true ||
        playerState.preparedSession?.request?.sourceSurface == "live_tv"
    val playerTitle = playerState.currentRequest?.title
        ?: playerState.preparedSession?.mediaItem?.title ?: "Playback"
    val playerSubtitle = playerState.preparedSession?.episodeContext?.label

    // ── Next-episode autoplay ───────────────────────────────────
    // Triggered when the VLC session reports `isEnded == true` and the
    // finished playback had a series episode context with a computable next.
    val vlcSessionStateFlow = vlcEngine?.sessionState
    val vlcSessionState by (
        vlcSessionStateFlow
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.torve.desktop.vlc.VlcSessionState()) }
        ).collectAsState()
    var nextEpisodeCandidate by remember {
        mutableStateOf<com.torve.desktop.ui.v2.playback.NextEpisode?>(null)
    }
    var nextEpisodeMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    var nextEpisodePromptVisible by remember { mutableStateOf(false) }

    LaunchedEffect(vlcSessionState.isEnded, playerState.preparedSession?.request?.mediaId) {
        val session = playerState.preparedSession
        val ep = session?.episodeContext
        if (vlcSessionState.isEnded && session != null && ep != null) {
            val next = com.torve.desktop.ui.v2.playback.resolveNextEpisode(
                mediaItem = session.mediaItem,
                currentSeason = ep.seasonNumber,
                currentEpisode = ep.episodeNumber,
            )
            if (next != null) {
                nextEpisodeCandidate = next
                nextEpisodeMediaItem = session.mediaItem
                nextEpisodePromptVisible = true
            }
        } else if (!vlcSessionState.isEnded) {
            nextEpisodePromptVisible = false
        }
    }

    fun switchLiveChannel(direction: Int) {
        if (!isLiveTvPlayback) return
        val channel = channelsViewModel.selectAdjacentChannel(direction) ?: return
        channelsViewModel.recordChannelViewed(channel)
        clearPlaybackRestoreTarget()
        playerController.playDirectStream(
            title = channel.name,
            url = channel.url,
            artworkUrl = channel.tvgLogo,
            sourceSurface = "live_tv",
        )
    }

    LaunchedEffect(playerController) { playerController.probeRuntime() }
    LaunchedEffect(playerState.phase, playerState.currentRequest?.mediaId) {
        // Reset the dismiss flag when the underlying state is neither RESUMABLE
        // nor FAILED - those are the two states where the user is allowed to
        // dismiss the dock. Resetting here keeps a fresh dock visible the next
        // time playback transitions into a dismissable state.
        val undismissed = desktopDockVisualState(playerState, dismissed = false)
        if (undismissed != DesktopDockVisualState.RESUMABLE &&
            undismissed != DesktopDockVisualState.FAILED) {
            dockDismissed = false
        }
        // Only close the source picker once playback is actually under way.
        // RESOLVING/OPENING/BUFFERING are EXPECTED states while the picker is
        // up - `prepareSourcePicker` itself transitions to RESOLVING, so
        // closing on those phases swallowed the user's first click.
        if (playerState.phase in setOf(DesktopPlayerPhase.PLAYING, DesktopPlayerPhase.PAUSED)) {
            sourcePickerRoute = null
        }
        if (playerState.phase in setOf(DesktopPlayerPhase.RESOLUTION_FAILED, DesktopPlayerPhase.RUNTIME_ERROR)) playbackOrigin?.let(::restoreOrigin)
    }
    LaunchedEffect(liveTvNavigationPending, liveTvSyncBlocking, channelsState.error) {
        if (!liveTvNavigationPending) return@LaunchedEffect
        if (channelsState.error != null || !liveTvSyncBlocking) {
            settingsOpen = false
            detailStack = emptyList()
            seeAllRoute = null
            destination = V2Destination.LIVE_TV
            liveTvNavigationPending = false
            return@LaunchedEffect
        }
        // Safety timeout: if the playlist DB is genuinely empty and a
        // background refresh fails silently (no error, no data), the sync
        // overlay would otherwise spin forever. After 8s we proceed to the
        // Live TV page anyway - V2LivePage shows "No channels loaded"
        // which is far more actionable than an infinite spinner.
        kotlinx.coroutines.delay(8000)
        if (liveTvNavigationPending) {
            println("V2App: liveTvNavigationPending overlay timed out - proceeding without data")
            settingsOpen = false
            detailStack = emptyList()
            seeAllRoute = null
            destination = V2Destination.LIVE_TV
            liveTvNavigationPending = false
        }
    }

    val desktopStrings = androidx.compose.runtime.remember(settingsState.appLanguage) {
        com.torve.desktop.ui.l10n.desktopStringsFor(settingsState.appLanguage)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.torve.desktop.ui.v2.components.LocalRatingDisplayPrefs provides settingsState.ratingPrefs,
        com.torve.desktop.ui.l10n.LocalDesktopStrings provides desktopStrings,
    ) {
    val rootFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    fun handleShortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        // Only act on key-down, and leave shortcuts to the player when it owns the content.
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
        if (isEmbeddedActive && !pipMode) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        if (!ctrl) return false
        val k = event.key
        val next: V2Destination? = when (k) {
            androidx.compose.ui.input.key.Key.One -> V2Destination.HOME
            androidx.compose.ui.input.key.Key.Two -> V2Destination.MOVIES
            androidx.compose.ui.input.key.Key.Three -> V2Destination.TV_SHOWS
            androidx.compose.ui.input.key.Key.Four -> V2Destination.SEARCH
            androidx.compose.ui.input.key.Key.Five -> V2Destination.LIBRARY
            androidx.compose.ui.input.key.Key.Six -> V2Destination.LIVE_TV
            else -> null
        }
        if (next != null) {
            settingsOpen = false; detailStack = emptyList(); seeAllRoute = null
            manageDevicesOpen = false; deviceLimitReachedOpen = false
            destination = next
            com.torve.desktop.diagnostics.SentryBootstrap.breadcrumb(
                category = "nav",
                message = "shortcut → ${next.name}",
            )
            return true
        }
        if (k == androidx.compose.ui.input.key.Key.K) {
            // Cmd+K → command palette. Power-user surface for both
            // navigation and one-shot actions.
            commandPaletteOpen = true
            return true
        }
        if (k == androidx.compose.ui.input.key.Key.Slash) {
            // Cmd+/ → keyboard shortcut reference. Discoverability for
            // every other shortcut in this list.
            keyboardHelpOpen = true
            return true
        }
        if (k == androidx.compose.ui.input.key.Key.Comma) {
            settingsOpen = true; detailStack = emptyList(); seeAllRoute = null
            return true
        }
        if (k == androidx.compose.ui.input.key.Key.W) {
            // Close topmost overlay.
            when {
                detailStack.isNotEmpty() -> { popDetailStack(); return true }
                seeAllRoute != null -> { seeAllRoute = null; return true }
                manageDevicesOpen -> { manageDevicesOpen = false; return true }
                settingsOpen -> { settingsOpen = false; return true }
            }
        }
        return false
    }

    // Update checker - kicks off once on first composition. State is held
    // on the global UpdateChecker singleton so the File menu's "Check for
    // updates..." item shares the same instance and result.
    val updateState by (com.torve.desktop.globalUpdateChecker?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(com.torve.desktop.updates.UpdateChecker.Result.UpToDate))
        .collectAsState()
    var updateBannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (com.torve.desktop.updates.UpdateCheckerPreferences.isAutoCheckEnabled()) {
            com.torve.desktop.globalUpdateChecker?.takeIf { it.isEnabled }?.check()
        }
    }

    // Local library TMDB enrichment - fires once on first composition,
    // then again whenever a folder is added (state.folders.size changes).
    // The repository handles its own change detection (skips entries
    // matched within the last 24h), so we don't need to debounce here.
    val localLibraryState by com.torve.desktop.globalLocalLibrary.state.collectAsState()
    LaunchedEffect(localLibraryState.folders.size, localLibraryState.entries.size) {
        com.torve.desktop.globalLocalLibrary.enrichAllNeeded(metadataRepository)
        // Once series matches are settled, fetch episode names per
        // season so the library shows "S01E01 - Pilot" instead of
        // just the marker. Cheap when there's nothing new to fetch.
        com.torve.desktop.globalLocalLibrary.enrichEpisodeTitles(metadataRepository)
    }

    // (commandPaletteOpen is declared above with the other nav state so
    // handleShortcut can capture it.)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.shellBackground)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(::handleShortcut),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // ── Embedded player mode: video surface owns the content area ──
                // In PiP mode we fall through to the shell and float the video
                // as a small overlay so the user can keep browsing.
                if (isEmbeddedActive && !pipMode && mpvEngine != null) {
                    // MPV path - chrome around the canvas (heavyweight z-order
                    // prevents Compose overlay; mpv's OSC handles in-frame).
                    var showMpvSubtitleSearch by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize()) {
                        com.torve.desktop.mpv.MpvPlayerShell(
                            engine = mpvEngine,
                            playerController = playerController,
                            title = playerTitle,
                            subtitle = playerSubtitle,
                            onClose = {
                                playerController.close()
                                playbackOrigin?.let(::restoreOrigin)
                            },
                            onStop = playerController::stop,
                            initialVolume = if (settingsState.rememberVolume) settingsState.lastVolume else null,
                            onVolumeChanged = playerController::onVolumeChanged,
                            preferredAudioLanguage = settingsState.preferredAudioLanguage.takeIf { it.isNotBlank() },
                            preferredSubtitleLanguage = settingsState.preferredSubtitleLanguage.takeIf { it.isNotBlank() },
                            windowState = windowState,
                            seekStepMs = playerController.getSeekStepMs(),
                            onSearchOnlineSubtitles = { showMpvSubtitleSearch = true },
                            channelNavigationEnabled = isLiveTvPlayback,
                            onPreviousChannel = { switchLiveChannel(-1) },
                            onNextChannel = { switchLiveChannel(1) },
                            hotkeys = settingsState.desktopPlaybackHotkeys,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (showMpvSubtitleSearch) {
                            com.torve.desktop.ui.playback.SubtitleSearchOverlay(
                                playerController = playerController,
                                onPickSubtitle = { sub ->
                                    kotlinx.coroutines.runBlocking {
                                        runCatching { mpvEngine.addSubtitle(sub.url) }
                                    }
                                },
                                onDismiss = { showMpvSubtitleSearch = false },
                            )
                        }
                    }
                } else if (isEmbeddedActive && !pipMode && vlcEngine != null) {
                    var showSubtitleSearch by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize()) {
                        // Record button visibility: only on live TV. The
                        // recordingsVm is bound via Koin; we look up by
                        // streamUrl since live recordings use the channel
                        // url as the recording key.
                        val recordingsVm = remember {
                            org.koin.mp.KoinPlatform.getKoin()
                                .get<com.torve.presentation.recording.RecordingsViewModel>()
                        }
                        val recordingsState by recordingsVm.state.collectAsState()
                        val playerPrefsRepo = remember {
                            org.koin.mp.KoinPlatform.getKoin()
                                .get<com.torve.domain.repository.PreferencesRepository>()
                        }
                        var playerRecordingPrefs by remember {
                            mutableStateOf(com.torve.domain.recording.RecordingPreferences())
                        }
                        LaunchedEffect(Unit) {
                            playerRecordingPrefs = com.torve.domain.recording.RecordingPreferences
                                .load(playerPrefsRepo)
                        }
                        // For live TV, the resolvedUrl on the prepared session
                        // IS the channel stream URL (which is what the
                        // recordings VM keys live recordings by).
                        val currentLiveStreamUrl: String? = playerState.preparedSession?.resolvedUrl
                        val isCurrentlyRecording = isLiveTvPlayback &&
                            currentLiveStreamUrl != null &&
                            recordingsState.active.any { it.streamUrl == currentLiveStreamUrl }
                        var knownPlayerRecordingFailureIds by remember {
                            mutableStateOf(recordingsState.failed.map { it.id }.toSet())
                        }
                        LaunchedEffect(recordingsState.failed, currentLiveStreamUrl) {
                            val failedIds = recordingsState.failed.map { it.id }.toSet()
                            recordingsState.failed
                                .filter { row ->
                                    row.id !in knownPlayerRecordingFailureIds &&
                                        row.status == com.torve.domain.recording.RecordingStatus.FAILED &&
                                        row.streamUrl == currentLiveStreamUrl
                                }
                                .forEach { row -> downloadManager.publishEvent(recordingFailureNotification(row)) }
                            knownPlayerRecordingFailureIds = failedIds
                        }
                        val onToggleRecord: (() -> Unit)? = if (isLiveTvPlayback && currentLiveStreamUrl != null) {
                            {
                                val active = recordingsState.active
                                    .firstOrNull { it.streamUrl == currentLiveStreamUrl }
                                if (active != null) {
                                    recordingsVm.cancel(active.id)
                                } else if (recordingFolderValidationError(settingsState.recordingDownloadPath) != null) {
                                    val error = recordingFolderValidationError(settingsState.recordingDownloadPath)
                                        ?: "Set a Recordings Folder under Settings > Preferences > Downloads first."
                                    downloadManager.publishEvent("Recording disabled: $error")
                                    com.torve.desktop.desktopNotify(
                                        "Recording disabled",
                                        error,
                                    )
                                } else {
                                    val now = System.currentTimeMillis()
                                    val liveChannel = channelsState.selectedChannel
                                        ?.takeIf { it.url == currentLiveStreamUrl }
                                        ?: (channelsState.categoryChannels + channelsState.channels)
                                            .firstOrNull { it.channel.url == currentLiveStreamUrl }
                                            ?.channel
                                        ?: com.torve.domain.model.Channel(
                                            name = playerTitle,
                                            url = currentLiveStreamUrl,
                                            playlistId = channelsState.selectedPlaylistId.orEmpty(),
                                        )
                                    val liveProgrammes = if (channelsState.selectedChannel?.url == currentLiveStreamUrl) {
                                        channelsState.programmes
                                    } else {
                                        emptyList()
                                    }
                                    val metadata = com.torve.domain.recording.EpgRecordingMetadataResolver.live(
                                        channel = liveChannel,
                                        programmes = liveProgrammes,
                                        nowMs = now,
                                    )
                                    recordingsVm.schedule(
                                        playlistId = channelsState.selectedPlaylistId.orEmpty(),
                                        channelId = liveChannel.url,
                                        channelName = liveChannel.name,
                                        streamUrl = currentLiveStreamUrl,
                                        programmeTitle = "Live recording — $playerTitle",
                                        programmeDescription = metadata.programmeDescription,
                                        startMs = now,
                                        endMs = now + playerRecordingPrefs.defaultDurationMs,
                                        metadata = metadata,
                                    )
                                    downloadManager.publishEvent("Recording started: $playerTitle")
                                }
                            }
                        } else null

                        VlcComposePlayerSurface(
                            engine = vlcEngine,
                            title = playerTitle,
                            subtitle = playerSubtitle,
                            onClose = {
                                playerController.close()
                                playbackOrigin?.let(::restoreOrigin)
                            },
                            onStop = playerController::stop,
                            channelNavigationEnabled = isLiveTvPlayback,
                            onPreviousChannel = { switchLiveChannel(-1) },
                            onNextChannel = { switchLiveChannel(1) },
                            isCurrentlyRecording = isCurrentlyRecording,
                            onToggleRecord = onToggleRecord,
                            windowState = windowState,
                            seekStepMs = playerController.getSeekStepMs(),
                            initialVolume = if (settingsState.rememberVolume) settingsState.lastVolume else null,
                            preferredAudioLanguage = settingsState.preferredAudioLanguage.takeIf { it.isNotBlank() },
                            preferredSubtitleLanguage = settingsState.preferredSubtitleLanguage.takeIf { it.isNotBlank() },
                            subtitlesEnabledByDefault = settingsState.subtitlesEnabledByDefault,
                            onVolumeChanged = playerController::onVolumeChanged,
                            onMinimizeToPip = { pipMode = true },
                            onSearchOnlineSubtitles = { showSubtitleSearch = true },
                            castController = castController,
                            hotkeys = settingsState.desktopPlaybackHotkeys,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Panda/addon "still warming up" overlay. Stays mounted
                        // over the player surface so the user isn't bounced
                        // back to detail mid-poll. Cancel calls into the
                        // controller, which tears the resolution job down and
                        // restores the origin route.
                        playerState.preparingStream?.let { preparing ->
                            com.torve.desktop.ui.playback.DesktopPreparingOverlay(
                                state = preparing,
                                onCancel = {
                                    playerController.cancelPreparing()
                                    playbackOrigin?.let(::restoreOrigin)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (showSubtitleSearch) {
                            com.torve.desktop.ui.playback.SubtitleSearchOverlay(
                                playerController = playerController,
                                onPickSubtitle = { sub ->
                                    vlcEngine?.session?.let { session ->
                                        kotlinx.coroutines.runBlocking {
                                            runCatching {
                                                session.addSubtitleFile(sub.url)
                                                session.refreshTracks()
                                            }
                                        }
                                    }
                                },
                                onDismiss = { showSubtitleSearch = false },
                            )
                        }
                    }
                } else {
                    // ── Normal shell content ──
                    val activeDetail = detailStack.lastOrNull()
                    val seeAll = seeAllRoute
                    when {
                        deviceLimitReachedOpen -> com.torve.desktop.ui.v2.device.V2DeviceLimitReachedScreen(
                            viewModel = deviceGovernanceViewModel,
                            onDismiss = {
                                deviceLimitReachedOpen = false
                                deviceGovernanceViewModel.dismissDeviceLimitReached()
                            },
                            onActivated = {
                                deviceLimitReachedOpen = false
                                deviceGovernanceViewModel.dismissDeviceLimitReached()
                            },
                        )

                        manageDevicesOpen -> com.torve.desktop.ui.v2.device.V2ManageDevicesScreen(
                            viewModel = deviceGovernanceViewModel,
                            onBack = { manageDevicesOpen = false },
                        )

                        statsOpen && statsViewModel != null -> com.torve.desktop.ui.v2.stats.V2StatsPage(
                            viewModel = statsViewModel,
                            onBack = { statsOpen = false },
                        )

                        recordingsPageOpen -> com.torve.desktop.ui.v2.recording.V2RecordingsPage(
                            onBack = { recordingsPageOpen = false },
                            onPlayLocal = { recording ->
                                val path = recording.filePath
                                if (!path.isNullOrBlank()) {
                                    clearPlaybackRestoreTarget()
                                    playerController.playDirectStream(
                                        title = recording.programmeTitle,
                                        url = "file://$path",
                                        artworkUrl = null,
                                        sourceSurface = "iptv_recording",
                                    )
                                    recordingsPageOpen = false
                                }
                            },
                        )

                        pandaSetupOpen -> com.torve.desktop.ui.panda.DesktopPandaSetupScreen(
                            viewModel = pandaSetupViewModel,
                            onBack = { pandaSetupOpen = false },
                            onComplete = { pandaSetupOpen = false },
                        )

                        settingsOpen -> V2SettingsPage(
                            authState = authState, authController = authController,
                            settingsState = settingsState, settingsViewModel = settingsViewModel,
                            accountSessionCoordinator = accountSessionCoordinator,
                            channelsState = channelsState, channelsViewModel = channelsViewModel,
                            addonRepository = addonRepository,
                            homeViewModel = homeViewModel,
                            setupIntentsViewModel = setupIntentsViewModel,
                            onOpenPandaSetup = { pandaSetupOpen = true },
                            onOpenManageDevices = {
                                settingsOpen = false
                                manageDevicesOpen = true
                            },
                            onOpenStats = {
                                settingsOpen = false
                                statsOpen = true
                            },
                            onOpenRecordings = {
                                settingsOpen = false
                                recordingsPageOpen = true
                            },
                            initialCategoryName = settingsLandingCategory,
                        )

                        activeDetail is V2DetailStackEntry.Person -> {
                            V2PersonPage(
                                personId = activeDetail.personId,
                                personViewModel = personViewModel,
                                onBack = { popDetailStack() },
                                onOpenDetail = { item ->
                                    pushFullDetail(item)
                                },
                            )
                        }

                        activeDetail is V2DetailStackEntry.Detail -> {
                            val route = activeDetail.route
                            val routeState = when (route.controllerKey) {
                                DesktopDetailControllerKey.LIBRARY -> libraryDetailState
                                DesktopDetailControllerKey.SEARCH -> searchDetailState
                            }
                            V2DetailPage(
                                detailState = routeState, watchlistState = watchlistState, favoriteKeys = mediaFavoritesState.favoriteKeys, playerState = playerState,
                                onBack = { popDetailStack() },
                                onPlay = { item ->
                                    val isSeries = item.type == com.torve.domain.model.MediaType.SERIES
                                    val sn = if (isSeries) routeState.selectedSeasonNumber else null
                                    val ep = if (isSeries) routeState.selectedEpisodeNumber else null
                                    launchPlayback(item, DesktopPlaybackOriginSurface.FULL_DETAIL, route.controllerKey, sn = sn, ep = ep)
                                },
                                onChooseSource = { item ->
                                    val isSeries = item.type == com.torve.domain.model.MediaType.SERIES
                                    val sn = if (isSeries) routeState.selectedSeasonNumber else null
                                    val ep = if (isSeries) routeState.selectedEpisodeNumber else null
                                    prepareSourcePicker(item, DesktopPlaybackOriginSurface.FULL_DETAIL, route.controllerKey, sn = sn, ep = ep)
                                },
                                onPlayEpisode = { item, season, episode ->
                                    launchPlayback(item, DesktopPlaybackOriginSurface.FULL_DETAIL, route.controllerKey, sn = season, ep = episode)
                                },
                                onChooseEpisodeSource = { item, season, episode ->
                                    prepareSourcePicker(item, DesktopPlaybackOriginSurface.FULL_DETAIL, route.controllerKey, sn = season, ep = episode)
                                },
                                canDownloadMovies = settingsState.movieDownloadPath.isNotBlank(),
                                canDownloadShows = settingsState.showDownloadPath.isNotBlank(),
                                onDownloadMovie = { item ->
                                    if (settingsState.movieDownloadPath.isBlank()) {
                                        downloadManager.publishEvent("Set a movie download folder in Settings first.")
                                    } else {
                                        prepareSourcePicker(item, DesktopPlaybackOriginSurface.FULL_DETAIL, route.controllerKey, forDownload = true)
                                    }
                                },
                                onDownloadEpisode = { item, seasonNumber, episodeNumber ->
                                    if (settingsState.showDownloadPath.isBlank()) {
                                        downloadManager.publishEvent("Set a show download folder in Settings first.")
                                    } else {
                                        downloadManager.queueEpisodeDownload(item, seasonNumber, episodeNumber)
                                    }
                                },
                                onDownloadSeason = { item, seasonNumber, episodeCount ->
                                    if (settingsState.showDownloadPath.isBlank()) {
                                        downloadManager.publishEvent("Set a show download folder in Settings first.")
                                    } else {
                                        downloadManager.queueSeasonDownload(item, seasonNumber, episodeCount)
                                    }
                                },
                                onDownloadAll = { item ->
                                    if (settingsState.showDownloadPath.isBlank()) {
                                        downloadManager.publishEvent("Set a show download folder in Settings first.")
                                    } else {
                                        downloadManager.queueAllEpisodesDownload(item)
                                    }
                                },
                                onToggleWatchlist = { item -> watchlistViewModel.toggleWatchlist(item) },
                                onToggleFavorite = { item -> mediaFavoritesRepository.toggleFavorite(item) },
                                onSelectSeason = { detailControllerFor(route.controllerKey).selectSeason(it) },
                                onSelectEpisode = { episode -> detailControllerFor(route.controllerKey).selectEpisode(episode.episodeNumber) },
                                onOpenRelated = { pushFullDetail(it, route.controllerKey) },
                                onOpenPerson = { id -> pushPersonDetail(id) },
                                windowState = windowState,
                            )
                        }

                        seeAll != null && seeAll.sectionId == "LATEST_ON_USENET" -> {
                            // Dedicated NZB grid with year / language / genre / search filters.
                            val pandaState by pandaSetupViewModel.state.collectAsState()
                            val nzb = resolveActiveNzbIndexer(pandaState)
                            com.torve.desktop.ui.v2.nzbmovies.V2NzbSeeAllPage(
                                catalogService = nzbCatalogService,
                                indexerType = nzb.type,
                                indexerUrl = nzb.url,
                                indexerApiKey = nzb.apiKey,
                                onBack = { seeAllRoute = null },
                                onOpenDetail = { item -> openFullDetail(item) },
                            )
                        }

                        seeAll != null && seeAll.sectionId == "LATEST_ON_USENET_TV" -> {
                            val pandaState by pandaSetupViewModel.state.collectAsState()
                            val nzb = resolveActiveNzbIndexer(pandaState)
                            com.torve.desktop.ui.v2.nzbmovies.V2NzbTvSeeAllPage(
                                catalogService = nzbTvCatalogService,
                                indexerType = nzb.type,
                                indexerUrl = nzb.url,
                                indexerApiKey = nzb.apiKey,
                                onBack = { seeAllRoute = null },
                                onOpenDetail = { item -> openFullDetail(item) },
                            )
                        }

                        seeAll != null -> {
                            V2SeeAllPage(
                                request = seeAll,
                                viewModel = seeAllViewModel,
                                metadataRepository = metadataRepository,
                                onBack = { seeAllRoute = null },
                                onPlay = { item -> launchPlayback(item) },
                                onOpenDetail = { item -> openFullDetail(item) },
                                onOpenPerson = { id -> pushPersonDetail(id) },
                            )
                        }

                        else -> when (destination) {
                            V2Destination.HOME -> {
                                val cardStyleResolver = remember(homeSectionConfigs, settingsState.cardStylePresets, settingsState.globalDefaultPresetId) {
                                    val configBySection = homeSectionConfigs.associateBy { it.section }
                                    val presets = settingsState.cardStylePresets
                                    val defaultId = settingsState.globalDefaultPresetId
                                    val resolver: (com.torve.domain.model.HomeSection?) -> com.torve.domain.model.CardStyle = { section ->
                                        val sectionPresetId = section?.let { configBySection[it]?.presetId }
                                        com.torve.domain.model.resolveCardStyle(presets, sectionPresetId, defaultId)
                                    }
                                    resolver
                                }
                                V2HomePage(
                                    homeState, homeScroll,
                                    onPlay = { launchPlayback(it) },
                                    onOpenDetail = { openFullDetail(it) },
                                    onSeeAll = { req -> seeAllRoute = req },
                                    sectionConfigs = homeSectionConfigs,
                                    enabledServiceIds = homeEnabledServiceIds,
                                    providerLogos = homeProviderLogos,
                                    cardStyleFor = cardStyleResolver,
                                    onOpenPerson = { id -> pushPersonDetail(id) },
                                    // Zero-source empty-state CTAs (Fix B
                                    // follow-up). Both currently route to
                                    // the same place — Settings, where
                                    // Integrations + Panda live. Once the
                                    // settings shell grows section-deep
                                    // links these can split into more
                                    // specific destinations.
                                    onSetUpSources = { settingsOpen = true },
                                    onConnectTrakt = { settingsOpen = true },
                                )
                            }
                            V2Destination.MOVIES -> {
                                val pandaState by pandaSetupViewModel.state.collectAsState()
                                val nzb = resolveActiveNzbIndexer(pandaState)
                                V2MoviesPage(
                                    catalogViewModel = moviesCatalogViewModel,
                                    metadataRepository = metadataRepository,
                                    scrollState = moviesScroll,
                                    onPlay = { launchPlayback(it) },
                                    onOpenDetail = { openFullDetail(it) },
                                    onSeeAll = { req -> seeAllRoute = req },
                                    nzbCatalogService = nzbCatalogService,
                                    nzbIndexerType = nzb.type,
                                    nzbIndexerUrl = nzb.url,
                                    nzbIndexerApiKey = nzb.apiKey,
                                    aiProvider = settingsState.aiProvider,
                                    aiApiKey = settingsState.activeAiApiKey,
                                    onOpenAiProviderSettings = {
                                        settingsLandingCategory = "INTEGRATIONS"
                                        settingsOpen = true
                                    },
                                    keywordSearch = {
                                        org.koin.java.KoinJavaComponent.get(
                                            com.torve.data.ai.KeywordSearchService::class.java,
                                        )
                                    },
                                    tmdb = {
                                        org.koin.java.KoinJavaComponent.get(
                                            com.torve.data.metadata.TmdbApiClient::class.java,
                                        )
                                    },
                                )
                            }
                            V2Destination.TV_SHOWS -> {
                                val pandaState by pandaSetupViewModel.state.collectAsState()
                                val nzb = resolveActiveNzbIndexer(pandaState)
                                V2ShowsPage(
                                    catalogViewModel = tvShowsCatalogViewModel,
                                    metadataRepository = metadataRepository,
                                    scrollState = showsScroll,
                                    onPlay = { launchPlayback(it) },
                                    onOpenDetail = { openFullDetail(it) },
                                    onSeeAll = { req -> seeAllRoute = req },
                                    upcomingSchedule = homeState.upcomingSchedule,
                                    nzbTvCatalogService = nzbTvCatalogService,
                                    nzbIndexerType = nzb.type,
                                    nzbIndexerUrl = nzb.url,
                                    nzbIndexerApiKey = nzb.apiKey,
                                    aiProvider = settingsState.aiProvider,
                                    aiApiKey = settingsState.activeAiApiKey,
                                    onOpenAiProviderSettings = {
                                        settingsLandingCategory = "INTEGRATIONS"
                                        settingsOpen = true
                                    },
                                    keywordSearch = {
                                        org.koin.java.KoinJavaComponent.get(
                                            com.torve.data.ai.KeywordSearchService::class.java,
                                        )
                                    },
                                    tmdb = {
                                        org.koin.java.KoinJavaComponent.get(
                                            com.torve.data.metadata.TmdbApiClient::class.java,
                                        )
                                    },
                                )
                            }
                            V2Destination.SEARCH -> V2SearchPage(
                                searchState = searchState,
                                onQueryChange = { if (it.isBlank()) searchViewModel.clearSearch() else searchViewModel.updateQuery(it) },
                                onApplyFilter = { searchViewModel.applyFilter(searchState.filter.copy(mediaType = it)) },
                                onPlay = { launchPlayback(it, controllerKey = DesktopDetailControllerKey.SEARCH) },
                                onOpenDetail = { openFullDetail(it, DesktopDetailControllerKey.SEARCH) },
                                aiProvider = settingsState.aiProvider,
                                aiApiKey = settingsState.activeAiApiKey,
                                onOpenAiProviderSettings = {
                                    // Open Settings overlay landing on the
                                    // AI provider section (Integrations).
                                    settingsLandingCategory = "INTEGRATIONS"
                                    settingsOpen = true
                                },
                                keywordSearch = {
                                    org.koin.java.KoinJavaComponent.get(
                                        com.torve.data.ai.KeywordSearchService::class.java,
                                    )
                                },
                                tmdb = {
                                    org.koin.java.KoinJavaComponent.get(
                                        com.torve.data.metadata.TmdbApiClient::class.java,
                                    )
                                },
                            )
                            V2Destination.LIBRARY -> V2LibraryPage(
                                homeState = homeState,
                                downloadState = downloadState,
                                downloadCatalogueState = downloadCatalogueState,
                                desktopDownloadState = desktopDownloadState,
                                favoriteItems = mediaFavoritesState.items.map { it.toMediaItem() },
                                scrollState = libraryScroll,
                                onPlay = { launchPlayback(it) },
                                onOpenDetail = { openFullDetail(it) },
                                onDeleteDownloadGroup = downloadCatalogueViewModel::deleteGroup,
                                onDeleteDownloadSeason = downloadCatalogueViewModel::deleteSeason,
                                onDeleteDownloadEpisode = downloadCatalogueViewModel::deleteEpisode,
                                onRefreshStoredMedia = {
                                    downloadViewModel.loadDownloads()
                                    downloadCatalogueViewModel.loadCatalogue()
                                    downloadManager.refreshLocalMediaAsync()
                                },
                                onSeeAll = { req -> seeAllRoute = req },
                                onPlayFile = { filePath, title, posterUrl ->
                                    clearPlaybackRestoreTarget()
                                    playerController.playLocalFile(
                                        title = title,
                                        absolutePath = filePath,
                                        artworkUrl = posterUrl,
                                    )
                                },
                                localLibrary = com.torve.desktop.globalLocalLibrary,
                                channelRepository = channelRepository,
                                onPlayVodChannel = { channel ->
                                    clearPlaybackRestoreTarget()
                                    playerController.playDirectStream(
                                        title = channel.name,
                                        url = channel.url,
                                        artworkUrl = channel.tvgLogo,
                                        sourceSurface = "iptv_vod",
                                    )
                                },
                            )
                            V2Destination.LIVE_TV -> V2LivePage(
                                channelsState = channelsState,
                                channelsViewModel = channelsViewModel,
                                playerController = playerController,
                                onPremiumBlocked = {},
                                onDirectPlaybackStarted = { clearPlaybackRestoreTarget() },
                                onRecordingEvent = downloadManager::publishEvent,
                            )
                            V2Destination.RECORDINGS -> com.torve.desktop.ui.v2.recording.V2RecordingsPage(
                                onBack = { destination = V2Destination.HOME },
                                onPlayLocal = { recording ->
                                    val path = recording.filePath
                                    if (!path.isNullOrBlank()) {
                                        clearPlaybackRestoreTarget()
                                        playerController.playDirectStream(
                                            title = recording.programmeTitle,
                                            url = "file://$path",
                                            artworkUrl = null,
                                            sourceSurface = "iptv_recording",
                                        )
                                    }
                                },
                            )
                            V2Destination.SPORTS -> {
                                // Same indexer + TorBox plumbing as Adult - read
                                // straight from Panda config, no separate setup.
                                val pandaState by pandaSetupViewModel.state.collectAsState()
                                val nzbdavVmSports: com.torve.presentation.usenet.NzbdavSetupViewModel = remember {
                                    org.koin.java.KoinJavaComponent.get(
                                        com.torve.presentation.usenet.NzbdavSetupViewModel::class.java,
                                    )
                                }
                                val nzbdavStateSports by nzbdavVmSports.state.collectAsState()
                                val nzbdavConfiguredSports = nzbdavStateSports.status.let {
                                    it !is com.torve.presentation.usenet.NzbdavStatus.NotConfigured &&
                                        it !is com.torve.presentation.usenet.NzbdavStatus.ConnectionFailed
                                }
                                fun String.isUsableSecret(): Boolean =
                                    isNotBlank() && !contains("redact", ignoreCase = true)
                                val activeIndexer = pandaState.nzbIndexers
                                    .firstOrNull { it.apiKey.isUsableSecret() && it.type != "none" }
                                val sIndexerType: String
                                val sIndexerUrl: String
                                val sIndexerKey: String
                                if (activeIndexer != null) {
                                    sIndexerType = activeIndexer.type
                                    sIndexerUrl = com.torve.desktop.adult.IndexerUrlResolver
                                        .resolve(activeIndexer.type, activeIndexer.url)
                                    sIndexerKey = activeIndexer.apiKey
                                } else if (pandaState.nzbIndexerApiKey.isUsableSecret() &&
                                    pandaState.nzbIndexer != "none") {
                                    sIndexerType = pandaState.nzbIndexer
                                    sIndexerUrl = com.torve.desktop.adult.IndexerUrlResolver
                                        .resolve(pandaState.nzbIndexer, pandaState.nzbIndexerUrl)
                                    sIndexerKey = pandaState.nzbIndexerApiKey
                                } else {
                                    sIndexerType = ""
                                    sIndexerUrl = ""
                                    sIndexerKey = ""
                                }
                                val sSecretsRedacted = sIndexerKey.isBlank() && (
                                    pandaState.nzbIndexers.any { it.type != "none" } ||
                                    pandaState.nzbIndexer != "none"
                                )
                                com.torve.desktop.ui.v2.sports.V2SportsPage(
                                    newznab = org.koin.java.KoinJavaComponent.get(com.torve.data.usenet.NewznabClient::class.java),
                                    resolver = remember(pandaState.downloadClient, pandaState.downloadClientApiKey, pandaState.enableUsenet, nzbdavConfiguredSports) {
                                        buildNzbResolver(pandaState, nzbdavConfiguredSports)
                                    },
                                    indexerType = sIndexerType,
                                    indexerUrl = sIndexerUrl,
                                    indexerApiKey = sIndexerKey,
                                    indexerSecretsRedacted = sSecretsRedacted,
                                    downloadManager = downloadManager,
                                    onOpenDownloadSettings = { settingsOpen = true },
                                    onPlayStream = { url, title, sizeBytes ->
                                        clearPlaybackRestoreTarget()
                                        playerController.openPreResolvedStream(
                                            streamUrl = url,
                                            title = title,
                                            sizeBytes = sizeBytes,
                                            sourceSurface = "sports",
                                        )
                                    },
                                    onOpenPandaSetup = { pandaSetupOpen = true },
                                )
                            }
                            V2Destination.ADULT -> {
                                // Both the indexer credentials and the TorBox API key live
                                // inside Panda's config - re-asking would duplicate setup.
                                // Pull them straight from Panda's state.
                                val pandaState by pandaSetupViewModel.state.collectAsState()
                                val nzbdavVmAdult: com.torve.presentation.usenet.NzbdavSetupViewModel = remember {
                                    org.koin.java.KoinJavaComponent.get(
                                        com.torve.presentation.usenet.NzbdavSetupViewModel::class.java,
                                    )
                                }
                                val nzbdavStateAdult by nzbdavVmAdult.state.collectAsState()
                                val nzbdavConfiguredAdult = nzbdavStateAdult.status.let {
                                    it !is com.torve.presentation.usenet.NzbdavStatus.NotConfigured &&
                                        it !is com.torve.presentation.usenet.NzbdavStatus.ConnectionFailed
                                }
                                val torboxKey = if (pandaState.downloadClient == "torbox")
                                    pandaState.downloadClientApiKey else ""
                                // Multi-indexer rows first; fall back to the legacy single-
                                // indexer fields for older Panda deploys whose config
                                // predates the nzbIndexers array. Panda redacts
                                // secrets on manifest-token reads - treat the
                                // literal "[redacted]" as effectively missing.
                                fun String.isUsableSecret(): Boolean =
                                    isNotBlank() && !contains("redact", ignoreCase = true)

                                val activeIndexer = pandaState.nzbIndexers
                                    .firstOrNull { it.apiKey.isUsableSecret() && it.type != "none" }
                                val indexerUrl: String
                                val indexerKey: String
                                if (activeIndexer != null) {
                                    indexerUrl = com.torve.desktop.adult.IndexerUrlResolver
                                        .resolve(activeIndexer.type, activeIndexer.url)
                                    indexerKey = activeIndexer.apiKey
                                } else if (pandaState.nzbIndexerApiKey.isUsableSecret() &&
                                    pandaState.nzbIndexer != "none") {
                                    indexerUrl = com.torve.desktop.adult.IndexerUrlResolver
                                        .resolve(pandaState.nzbIndexer, pandaState.nzbIndexerUrl)
                                    indexerKey = pandaState.nzbIndexerApiKey
                                } else {
                                    indexerUrl = ""
                                    indexerKey = ""
                                }
                                // Detect the case where Panda has indexers but the secrets
                                // are masked - surface a different message than "no indexer".
                                val indexerSecretsRedacted = indexerKey.isBlank() && (
                                    pandaState.nzbIndexers.any { it.type != "none" } ||
                                    pandaState.nzbIndexer != "none"
                                )
                                println(
                                    "TORVE ADULT | indexer type=${activeIndexer?.type ?: pandaState.nzbIndexer} " +
                                        "urlConfigured=${indexerUrl.isNotBlank()} keyConfigured=${indexerKey.isNotBlank()} " +
                                        "redacted=$indexerSecretsRedacted",
                                )
                                com.torve.desktop.ui.v2.adult.V2AdultPage(
                                    newznab = remember { com.torve.desktop.adult.NewznabClient() },
                                    resolver = remember(pandaState.downloadClient, pandaState.downloadClientApiKey, pandaState.enableUsenet, nzbdavConfiguredAdult) {
                                        buildNzbResolver(pandaState, nzbdavConfiguredAdult)
                                    },
                                    indexerUrl = indexerUrl,
                                    indexerApiKey = indexerKey,
                                    indexerSecretsRedacted = indexerSecretsRedacted,
                                    downloadManager = downloadManager,
                                    onOpenDownloadSettings = { settingsOpen = true },
                                    onPlayStream = { url, title, sizeBytes ->
                                        clearPlaybackRestoreTarget()
                                        playerController.openPreResolvedStream(
                                            streamUrl = url,
                                            title = title,
                                            sizeBytes = sizeBytes,
                                            sourceSurface = "adult",
                                        )
                                    },
                                    onOpenPandaSetup = { pandaSetupOpen = true },
                                )
                            }
                        }
                    }

                    // ── Next-episode autoplay prompt ──────────────────
                    if (nextEpisodePromptVisible && nextEpisodeCandidate != null && nextEpisodeMediaItem != null) {
                        val next = nextEpisodeCandidate!!
                        val series = nextEpisodeMediaItem!!
                        com.torve.desktop.ui.v2.playback.V2NextEpisodePrompt(
                            showTitle = series.title,
                            nextEpisodeLabel = "S${next.seasonNumber}E${next.episodeNumber}",
                            onPlayNow = {
                                nextEpisodePromptVisible = false
                                launchPlayback(series, sn = next.seasonNumber, ep = next.episodeNumber)
                            },
                            onCancel = { nextEpisodePromptVisible = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                                .zIndex(7f),
                        )
                    }

                    // ── Picture-in-Picture overlay ────────────────────
                    // Floats in the bottom-right while the rest of the shell
                    // stays interactive. Same AWT Canvas as the full-screen
                    // surface - the outer `if` above ensures only one mounts.
                    if (isEmbeddedActive && pipMode && vlcEngine != null) {
                        com.torve.desktop.ui.v2.playback.V2PipOverlay(
                            engine = vlcEngine,
                            title = playerTitle,
                            subtitle = playerSubtitle,
                            onPlayPause = {
                                if (playerState.phase == DesktopPlayerPhase.PLAYING) playerController.pause()
                                else playerController.play()
                            },
                            onExpand = { pipMode = false },
                            onClose = {
                                pipMode = false
                                playerController.close()
                                playbackOrigin?.let(::restoreOrigin)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 24.dp)
                                .zIndex(6f),
                        )
                    }

                    // Nav rail overlays left edge (hidden during embedded playback)
                    V2NavRail(
                        currentDestination = destination,
                        onSelectDestination = { nextDestination ->
                            if (nextDestination == V2Destination.LIVE_TV && liveTvSyncBlocking) {
                                liveTvNavigationPending = true
                                if (channelsState.selectedPlaylistId == null) {
                                    channelsState.playlists.firstOrNull()?.let { channelsViewModel.selectPlaylist(it.id) }
                                }
                            } else {
                                // Dismiss every transient overlay so the rail
                                // always wins. The when{} that picks the visible
                                // page falls through to the destination switch
                                // only if NONE of these flags are true.
                                settingsOpen = false
                                pandaSetupOpen = false
                                manageDevicesOpen = false
                                statsOpen = false
                                deviceLimitReachedOpen = false
                                detailStack = emptyList()
                                seeAllRoute = null
                                destination = nextDestination
                            }
                        },
                        onOpenSettings = {
                            settingsOpen = true
                            pandaSetupOpen = false
                            manageDevicesOpen = false
                            statsOpen = false
                            deviceLimitReachedOpen = false
                            detailStack = emptyList()
                            seeAllRoute = null
                        },
                        isSettingsActive = settingsOpen,
                        backdropLuminance = navRailLuminance,
                        adultModeEnabled = adultModeEnabled,
                        modifier = Modifier.align(Alignment.CenterStart).zIndex(2f),
                    )

                    // Update-available banner - slim pill at top-center.
                    // Auto-dismissable; the File → "Check for Updates..."
                    // menu item is the manual entry point.
                    val available = updateState as? com.torve.desktop.updates.UpdateChecker.Result.Available
                    if (available != null && !updateBannerDismissed && !settingsOpen) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .widthIn(max = 540.dp),
                        ) {
                            // One handoff instance per banner
                            // appearance; the start() coroutine is
                            // single-shot per UpdateInfo.
                            val handoffScope = rememberCoroutineScope()
                            val handoff = remember(available.info) {
                                com.torve.desktop.updates.UpdateInstallerHandoff()
                            }
                            val handoffPhase by handoff.phase.collectAsState()
                            com.torve.desktop.updates.UpdateBanner(
                                info = available.info,
                                currentVersion = com.torve.desktop.globalUpdateChecker
                                    ?.currentVersion ?: "",
                                handoffPhase = handoffPhase,
                                onView = {
                                    runCatching {
                                        java.awt.Desktop.getDesktop().browse(
                                            java.net.URI(available.info.htmlUrl),
                                        )
                                    }
                                },
                                onInstall = if (available.info.installerUrl != null) {
                                    {
                                        handoffScope.launch {
                                            handoff.start(available.info)
                                        }
                                    }
                                } else null,
                                onDismiss = { updateBannerDismissed = true },
                            )
                        }
                    }

                    // User badge - compact circular avatar at top-right.
                    // Used to be a wide email-bearing pill; that overlapped
                    // page chrome on every surface. Now it's a 36dp circle
                    // showing initials. Click expands a dropdown with the
                    // full identity + actions (Settings / Sign Out), so
                    // power users still see the email but the resting
                    // footprint is small enough to never collide.
                    if (!settingsOpen) {
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp)) {
                            Box {
                                Surface(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable(onClick = { accessMenuExpanded = !accessMenuExpanded }),
                                    color = colors.accent.copy(alpha = 0.85f),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = userBadgeInitials(signedInPrimaryLabel),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        )
                                    }
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = accessMenuExpanded,
                                    onDismissRequest = { accessMenuExpanded = false },
                                    containerColor = colors.drawerSurface,
                                ) {
                                    // Identity header - disabled item so it
                                    // reads as context, not a tap target.
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    signedInPrimaryLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = colors.textPrimary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                                    maxLines = 1,
                                                )
                                                signedInSecondaryLabel?.let { email ->
                                                    Text(
                                                        email,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.textSecondary,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = { accessMenuExpanded = false },
                                        enabled = false,
                                    )
                                    androidx.compose.material3.HorizontalDivider()
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(ds("Settings")) },
                                        onClick = { accessMenuExpanded = false; settingsOpen = true },
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(ds("Sign Out")) },
                                        onClick = { accessMenuExpanded = false; authController.signOut() },
                                    )
                                    androidx.compose.material3.HorizontalDivider()
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(ds("Quit Torve")) },
                                        onClick = {
                                            accessMenuExpanded = false
                                            kotlin.system.exitProcess(0)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Playback dock (hidden during embedded playback since chrome is inline)
            if (!isEmbeddedActive) {
                DesktopPlaybackDock(
                    playerState = playerState, dismissed = dockDismissed,
                    onReturn = { playbackOrigin?.let(::restoreOrigin) },
                    onResume = { playbackOrigin?.let(::restoreOrigin); playerController.play() },
                    onPause = playerController::pause,
                    onRetry = { playbackOrigin?.let(::restoreOrigin); playerController.retryCurrentRequest() },
                    onChooseSource = {
                        playbackOrigin?.let { origin ->
                            restoreOrigin(origin)
                            val request = playerState.currentRequest ?: origin.item.toDesktopPlaybackRequest(
                                sourceSurface = playbackSourceSurfaceLabel(origin.destination, origin.surface),
                                seasonNumber = origin.seasonNumber, episodeNumber = origin.episodeNumber,
                            )
                            sourcePickerRoute = DesktopSourcePickerRoute(request = request, origin = origin)
                            if (request.matches(playerState.currentRequest)) {
                                if (playerState.preparedSession == null && playerState.phase != DesktopPlayerPhase.RESOLVING) playerController.prepareCurrentRequest()
                            } else playerController.prepareRequest(request)
                        }
                    },
                    onDismissResumable = { dockDismissed = true },
                )
            }
        }

        // Top-center download status overlay (toast + progress bar)
        Box(Modifier.fillMaxSize().zIndex(7f), contentAlignment = Alignment.TopCenter) {
            com.torve.desktop.ui.v2.components.DownloadStatusOverlay(state = desktopDownloadState)
        }

        watchlistState.snackbarMessage?.let { message ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp)
                    .zIndex(7.5f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 280.dp, max = 520.dp),
                    color = colors.dockSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)),
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2_500)
                watchlistViewModel.clearSnackbar()
            }
        }

        // Source picker overlay
        if (!isEmbeddedActive) {
            val isMovieRequest = sourcePickerRoute?.request?.mediaType == com.torve.domain.model.MediaType.MOVIE
            DesktopSourcePickerOverlay(
                visible = sourcePickerRoute != null, playerState = playerState,
                intent = if (downloadIntent) com.torve.desktop.ui.playback.DesktopSourcePickerIntent.DOWNLOAD
                    else com.torve.desktop.ui.playback.DesktopSourcePickerIntent.PLAY,
                onDismiss = { sourcePickerRoute = null; downloadIntent = false },
                onSelectCandidate = playerController::selectCandidate,
                onPlayCandidate = { sourcePickerRoute?.origin?.let(::restoreOrigin); playerController.playCandidate(it) },
                onDownloadCandidate = if (isMovieRequest) { candidateId ->
                    playerController.selectCandidate(candidateId)
                    playerState.preparedSession?.let(downloadManager::queueMovieDownload)
                    sourcePickerRoute = null
                    downloadIntent = false
                } else null,
                onDownloadSelected = if (isMovieRequest) {
                    {
                        playerState.preparedSession?.let(downloadManager::queueMovieDownload)
                        sourcePickerRoute = null
                        downloadIntent = false
                    }
                } else {
                    null
                },
                onRetry = { sourcePickerRoute?.origin?.let(::restoreOrigin); playerController.retryCurrentRequest() },
                onPlaySelected = { sourcePickerRoute?.origin?.let(::restoreOrigin); playerController.play() },
            )
        }

        if (liveTvNavigationPending && liveTvSyncBlocking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.shellBackground.copy(alpha = 0.76f))
                    .zIndex(8f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = colors.cardSurface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                        Text(
                            text = "Syncing IPTV playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = liveTvPlaylistName?.let { "Loading $it before opening Live TV." }
                                ?: "Loading your IPTV data before opening Live TV.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        // Command palette overlay - sits above everything when open so it
        // works regardless of which page or modal the user is on.
        if (commandPaletteOpen) {
            val paletteActions = remember(destination, settingsOpen) {
                buildList {
                    V2Destination.entries.forEach { dest ->
                        add(
                            com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                                id = "go_${dest.name}",
                                label = "Go to ${dest.label}",
                                hint = "Navigation",
                                icon = dest.icon,
                                keywords = listOf(dest.label, dest.name.lowercase(), "navigate", "open"),
                                action = {
                                    settingsOpen = false; pandaSetupOpen = false
                                    manageDevicesOpen = false; statsOpen = false
                                    deviceLimitReachedOpen = false
                                    detailStack = emptyList(); seeAllRoute = null
                                    destination = dest
                                },
                            ),
                        )
                    }
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "open_settings",
                            label = "Settings",
                            hint = "Account, playback, integrations",
                            keywords = listOf("preferences", "config", "options"),
                            action = { settingsOpen = true },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "manage_devices",
                            label = "Manage devices",
                            hint = "See active sessions on your account",
                            keywords = listOf("device", "session", "logout"),
                            action = { manageDevicesOpen = true },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "open_stats",
                            label = "Stats",
                            hint = "Watch history aggregates",
                            keywords = listOf("history", "aggregate"),
                            action = { statsOpen = true },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "check_updates",
                            label = "Check for updates...",
                            hint = "Query the configured update channel",
                            keywords = listOf("upgrade", "release", "version"),
                            action = {
                                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                                kotlinx.coroutines.GlobalScope.launch {
                                    com.torve.desktop.globalUpdateChecker?.check()
                                }
                            },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "sign_out",
                            label = "Sign out",
                            hint = "End the current Torve session",
                            keywords = listOf("logout", "log out", "leave"),
                            action = { authController.signOut() },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "keyboard_help",
                            label = "Keyboard shortcuts",
                            hint = "List every key binding",
                            keywords = listOf("help", "shortcuts", "hotkeys", "keybindings", "cheat sheet"),
                            action = { keyboardHelpOpen = true },
                        ),
                    )
                    add(
                        com.torve.desktop.ui.v2.palette.CommandPaletteAction(
                            id = "quit_app",
                            label = "Quit Torve",
                            hint = "Close the app entirely",
                            keywords = listOf("exit", "close app"),
                            action = { kotlin.system.exitProcess(0) },
                        ),
                    )
                }
            }
            com.torve.desktop.ui.v2.palette.CommandPalette(
                actions = paletteActions,
                onDismiss = { commandPaletteOpen = false },
            )
        }

        // Keyboard help overlay - Cmd+/ from anywhere, plus exposed via
        // command palette as "Keyboard shortcuts".
        if (keyboardHelpOpen) {
            com.torve.desktop.ui.v2.help.KeyboardHelpOverlay(
                onDismiss = { keyboardHelpOpen = false },
            )
        }

        // Legacy compatibility overlay. Current Desktop feature access is
        // free by default, so normal product actions do not set this state.
        premiumGateReason?.let { reason ->
            com.torve.desktop.premium.PremiumRequiredOverlay(
                reason = reason,
                onDismiss = { premiumGateReason = null },
            )
        }

        // AI search overlay - natural-language → KeywordSearchService → results.
        if (aiSearchOpen) {
            com.torve.desktop.ui.v2.search.V2AiSearchOverlay(
                keywordSearch = org.koin.java.KoinJavaComponent.get(
                    com.torve.data.ai.KeywordSearchService::class.java,
                ),
                tmdb = org.koin.java.KoinJavaComponent.get(
                    com.torve.data.metadata.TmdbApiClient::class.java,
                ),
                provider = settingsState.aiProvider,
                apiKey = settingsState.activeAiApiKey,
                onDismiss = { aiSearchOpen = false },
                onOpenDetail = { item ->
                    aiSearchOpen = false
                    openFullDetail(item, DesktopDetailControllerKey.SEARCH)
                },
                availabilityAggregator = org.koin.java.KoinJavaComponent.get(
                    com.torve.domain.sourceavailability.SourceAvailabilityAggregator::class.java,
                ),
            )
        }
    }
    }
}

/**
 * Up-to-two-character user-badge initials, derived from the display name
 * or email. Falls back to "?" when neither is meaningful so the avatar
 * still renders a glyph instead of an empty circle.
 */
private fun userBadgeInitials(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isBlank()) return "?"
    // Prefer the first letter of the first two whitespace-separated tokens
    // (display names like "Adam Losonczy"); for emails, pick the first
    // letter and the first letter after the @ host's leading dot, falling
    // back to just the first letter.
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size >= 2) {
        return (tokens[0].first().toString() + tokens[1].first().toString()).uppercase()
    }
    val single = tokens[0]
    val atIdx = single.indexOf('@')
    if (atIdx > 0) {
        return single[0].toString().uppercase()
    }
    return single.take(2).uppercase()
}

/**
 * Snapshot of the active Newznab indexer pulled from Panda config.
 * Used by the Movies-tab "Latest on Usenet" shelf and the dedicated
 * see-all page so they share the same resolution logic - multi-indexer
 * rows preferred, with the legacy single-indexer fields as a fallback
 * for older Panda configs. Filters redacted secrets out so the catalog
 * service doesn't fire requests with `[redacted]` as an apiKey.
 */
private data class ActiveNzbIndexer(val type: String, val url: String, val apiKey: String)

/**
 * Picks the right [NzbResolver] for the browse surfaces (Adult, Sports)
 * based on what the user has configured in Panda:
 *  - TorBox download client with a usable key → [TorBoxNzbResolver]
 *  - Usenet enabled (NzbDAV integration) → [BackendNzbResolver]
 *  - Neither → disabled [TorBoxNzbResolver] with blank key (isConfigured=false)
 */
private fun buildNzbResolver(
    pandaState: com.torve.presentation.panda.PandaSetupUiState,
    nzbdavConfigured: Boolean,
): com.torve.domain.nzb.NzbResolver {
    fun String.isUsableSecret(): Boolean =
        isNotBlank() && !contains("redact", ignoreCase = true)
    if (pandaState.downloadClient == "torbox" &&
        pandaState.downloadClientApiKey.isUsableSecret()
    ) {
        return com.torve.domain.nzb.TorBoxNzbResolver(
            client = org.koin.java.KoinJavaComponent.get(
                com.torve.data.usenet.TorBoxUsenetClient::class.java,
            ),
            apiKey = pandaState.downloadClientApiKey,
        )
    }
    if (pandaState.enableUsenet) {
        return com.torve.domain.nzb.BackendNzbResolver(
            repository = org.koin.java.KoinJavaComponent.get(
                com.torve.data.usenet.UsenetRepository::class.java,
            ),
            configured = nzbdavConfigured,
        )
    }
    return com.torve.domain.nzb.TorBoxNzbResolver(
        client = org.koin.java.KoinJavaComponent.get(
            com.torve.data.usenet.TorBoxUsenetClient::class.java,
        ),
        apiKey = "",
    )
}

private fun resolveActiveNzbIndexer(
    pandaState: com.torve.presentation.panda.PandaSetupUiState,
): ActiveNzbIndexer {
    fun String.isUsableSecret(): Boolean =
        isNotBlank() && !contains("redact", ignoreCase = true)
    val active = pandaState.nzbIndexers.firstOrNull {
        it.apiKey.isUsableSecret() && it.type != "none"
    }
    return when {
        active != null -> ActiveNzbIndexer(
            type = active.type,
            url = com.torve.desktop.adult.IndexerUrlResolver.resolve(active.type, active.url),
            apiKey = active.apiKey,
        )
        pandaState.nzbIndexerApiKey.isUsableSecret() && pandaState.nzbIndexer != "none" ->
            ActiveNzbIndexer(
                type = pandaState.nzbIndexer,
                url = com.torve.desktop.adult.IndexerUrlResolver
                    .resolve(pandaState.nzbIndexer, pandaState.nzbIndexerUrl),
                apiKey = pandaState.nzbIndexerApiKey,
            )
        else -> ActiveNzbIndexer(type = "", url = "", apiKey = "")
    }
}
