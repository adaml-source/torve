package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.torve.presentation.detail.primaryPlaybackTarget
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import com.torve.android.ui.components.MobileGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.torve.android.ui.components.MobileGlassOutlinedButton as OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.device.DeviceFormFactor
import com.torve.android.premium.AccessTier
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.components.CastAvatar
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.FloatingBackButton
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.MultiRatingPills
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.SectionHeader
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.image.MobileImagePrefetcher
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.HeroGradient
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.CandidateProvenanceKind
import org.koin.compose.koinInject
import com.torve.android.download.DownloadWorker
import com.torve.data.download.BulkDownloadManager
import com.torve.data.integrations.JellyfinLibraryOverlayService
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.content.Intent
import android.net.Uri
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.canResolveStreams
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.AvailabilityOffer
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.calculateTorveScore
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.android.player.DeviceCodecProbe
import kotlinx.coroutines.launch
import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.platform.NetworkMonitor
import com.torve.platform.NetworkType
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.detail.StreamFilterUiText
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.lanlibrary.LanLibraryConsumer
import com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.tvhome.DetailSourcePickerLookup
import com.torve.presentation.tvhome.TvDetailsSourcePickerStateBuilder
import com.torve.presentation.tvhome.TvSourcePickerState
import com.torve.presentation.watchlist.containsMedia
import com.torve.presentation.watchlist.isMutatingMedia
import com.torve.presentation.watchlist.WatchlistViewModel
import com.torve.domain.repository.MetadataRepository
import androidx.compose.material.icons.rounded.PlaylistAdd
import com.torve.util.FormatUtil
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    type: String,
    id: Int,
    extensionSeedJson: String? = null,
    accessTier: AccessTier = AccessTier.FREE,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    onPlayClick: (url: String, fallbackUrl: String, season: Int?, episode: Int?, imdbId: String?, autoSourceSelection: Boolean) -> Unit,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onPersonClick: ((Int) -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onDevicesClick: (() -> Unit)? = null,
    onCollectionsClick: ((MediaItem) -> Unit)? = null,
    onOpenAddonCatalog: (() -> Unit)? = null,
    onOpenTraktLists: (() -> Unit)? = null,
    initialSeason: Int? = null,
    initialEpisode: Int? = null,
    viewModel: DetailViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    downloadViewModel: DownloadViewModel = koinInject(),
    watchlistViewModel: WatchlistViewModel = koinInject(),
    mediaFavoritesRepository: MediaFavoritesRepository = koinInject(),
    addonViewModel: com.torve.presentation.addon.AddonViewModel = koinInject(),
    downloadRepository: DownloadRepository = koinInject(),
    lanLibraryConsumer: LanLibraryConsumer = koinInject(),
    jellyfinLibrary: JellyfinLibraryOverlayService = koinInject(),
    networkMonitor: NetworkMonitor = koinInject(),
) {
    val bulkDownloadManager: BulkDownloadManager = koinInject()
    val metadataRepository: MetadataRepository = koinInject()
    val bulkProgress by bulkDownloadManager.progress.collectAsState()
    // Kick the LAN-library consumer once so the "Available on desktop"
    // pill resolves on first paint (instead of waiting for the next
    // background poll).
    com.torve.android.ui.components.LanAvailabilityBootstrap()
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    var cinematicLogoUrl by remember(type, id) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mediaItem?.id, state.mediaItem?.logoUrl) {
        val item = state.mediaItem ?: return@LaunchedEffect
        val logo = item.logoUrl ?: item.tmdbId?.let { tmdbId ->
            runCatching { metadataRepository.getLogoUrl(type, tmdbId) }.getOrNull()
        }
        cinematicLogoUrl = logo
        com.torve.android.ui.components.ContentLaunchArtworkStore.clear()
    }
    val settingsState by settingsViewModel.state.collectAsState()
    val runtimeFilterFeedbackEnabled = true
    val watchlistState by watchlistViewModel.state.collectAsState()
    val favoritesState by mediaFavoritesRepository.state.collectAsState()
    val addonState by addonViewModel.state.collectAsState()
    val hasEnabledStreamAddon = remember(addonState.addons) {
        addonState.addons.any { addon -> addon.canResolveStreams() }
    }
    // Settings also includes standalone saved Usenet connections. Keep the
    // fresh addon state while startup account sync is still completing.
    val canPlayStreams = settingsState.canPlayStreams || hasEnabledStreamAddon
    val isLocked: (PremiumFeature) -> Boolean = remember(accessTier) {
        { feature -> PremiumAccess.isPremiumLocked(feature, accessTier) }
    }
    val streamPlaybackLocked = isLocked(PremiumFeature.STREAM_PLAYBACK)
    val watchlistEditLocked = isLocked(PremiumFeature.WATCHLIST_EDIT)
    val favoritesEditLocked = isLocked(PremiumFeature.FAVORITES_EDIT)
    val watchedStatusLocked = isLocked(PremiumFeature.WATCHED_STATUS_EDIT)
    val traktListLocked = isLocked(PremiumFeature.TRAKT_LIST_MANAGER)
    val chooseSourceLocked = isLocked(PremiumFeature.CHOOSE_SOURCE_PREMIUM)
    var showActionSheet by remember { mutableStateOf(false) }
    var requestPlaybackOptions by remember(type, id) { mutableStateOf(false) }
    var resolvedUrl by remember { mutableStateOf("") }
    var resolvedUrlIsTemporary by remember { mutableStateOf(false) }
    var showTrailer by remember { mutableStateOf(false) }
    var pendingWatchedChange by remember(type, id) { mutableStateOf<Boolean?>(null) }
    var showRatingDialog by remember(type, id) { mutableStateOf(false) }
    var sourcePickerState by remember { mutableStateOf<TvSourcePickerState?>(null) }
    var sourcePickerSeason by remember { mutableStateOf<Int?>(null) }
    var sourcePickerEpisode by remember { mutableStateOf<Int?>(null) }
    var jellyfinAvailableEpisodes by remember(type, id) {
        mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    }
    // Tracks whether the current stream resolution is for download (not playback)
    var pendingEpisodeDownload by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current
    val isTvDevice = remember(context) { DeviceFormFactor.isTv(context) }
    val syncCoordinator: com.torve.android.sync.SyncCoordinator = koinInject()
    val syncState by syncCoordinator.state.collectAsState()
    var showTvPicker by remember(type, id) { mutableStateOf(false) }
    var tvHandoffBusy by remember(type, id) { mutableStateOf(false) }
    val tvTargets = remember(syncState.devices, syncState.deviceId) {
        syncCoordinator.targetDevices().filter { it.deviceType.contains("tv", ignoreCase = true) }
    }
    if (showTvPicker) com.torve.android.ui.sync.SyncDevicePickerDialog(
        title = stringResource(R.string.detail_play_on_tv),
        devices = tvTargets,
        onSelectDevice = { device ->
            val item = state.mediaItem
            val tmdbId = item?.tmdbId?.takeIf { it > 0 } ?: id.takeIf { it > 0 }
            showTvPicker = false
            if (item != null && tmdbId != null && !tvHandoffBusy) coroutineScope.launch {
                tvHandoffBusy = true
                try {
                    val result = syncCoordinator.sendPlaybackIntent(
                        targetDeviceId = device.id, contentId = tmdbId.toString(), providerTarget = "torve",
                        // The paired TV selects its own next episode. Do not apply a
                        // phone episode's resume offset to a different TV episode.
                        positionMs = if (item.type == MediaType.MOVIE) state.primaryPlaybackTarget.positionMs ?: 0L else 0L,
                        mediaType = if (item.type == MediaType.SERIES) "tv" else "movie",
                    )
                    android.widget.Toast.makeText(context,
                        if (result.isSuccess) context.getString(R.string.player_sent_to, device.deviceName)
                        else result.exceptionOrNull()?.message ?: context.getString(R.string.player_cannot_handoff),
                        android.widget.Toast.LENGTH_LONG).show()
                } finally { tvHandoffBusy = false }
            }
        },
        onDismiss = { showTvPicker = false },
        onManageDevices = onDevicesClick ?: onSettingsClick,
    )

    fun launchPlayback(url: String, fallback: String, season: Int?, episode: Int?, imdbId: String?, autoSource: Boolean) {
        com.torve.android.ui.player.PlaybackMetadataStore.stage(org.koin.java.KoinJavaComponent.getKoin().get<com.torve.data.auth.UserIdProvider>().currentDataUserId(), viewModel.state.value)
        viewModel.onPlaybackLaunched(season, episode)
        showActionSheet = false
        resolvedUrl = ""
        onPlayClick(url, fallback, season, episode, imdbId, autoSource)
    }

    LaunchedEffect(state.mediaItem, state.similar) {
        state.mediaItem?.let { item ->
            MobileImagePrefetcher.prefetchDetail(
                context = context,
                item = item,
                similar = state.similar,
            )
        }
    }

    fun openSourcePickerOrProvider(season: Int? = null, episode: Int? = null) {
        val item = state.mediaItem ?: return
        if (season != null && episode != null) {
            viewModel.selectEpisodeForPlayback(season, episode)
        }
        coroutineScope.launch {
            val picker = buildMobileDetailPickerState(
                item = item,
                seasonNumber = season,
                episodeNumber = episode,
                downloadRepository = downloadRepository,
                jellyfinLibrary = jellyfinLibrary,
                lanLibraryConsumer = lanLibraryConsumer,
                networkMonitor = networkMonitor,
                wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
            )
            val hasDirectSource = picker.options.any { opt ->
                opt.route is PlaybackRoute.LocalFile ||
                    opt.route is PlaybackRoute.JellyfinStream ||
                    opt.route is PlaybackRoute.LanDesktopStream
            }
            if (!hasDirectSource) {
                if (canPlayStreams) {
                    viewModel.fetchStreams(season = season, episode = episode)
                } else {
                    onOpenAddonCatalog?.invoke()
                }
            } else {
                sourcePickerSeason = season
                sourcePickerEpisode = episode
                sourcePickerState = picker
            }
        }
    }

    // Wire download callbacks to trigger WorkManager
    LaunchedEffect(downloadViewModel) {
        downloadViewModel.onDownloadEnqueued = { downloadId ->
            android.util.Log.w("DetailScreen", "onDownloadEnqueued callback fired, downloadId=$downloadId")
            DownloadWorker.enqueue(context, downloadId)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setSettingsProvider { settingsViewModel }
        viewModel.setDeviceCodecCaps(DeviceCodecProbe.probe())
    }
    LaunchedEffect(type, id, extensionSeedJson) {
        if (extensionSeedJson != null) {
            val seed = com.torve.presentation.apple.AppleCatalogueDetail.decode(extensionSeedJson)
            if (viewModel.state.value.mediaItem?.id != seed.id) {
                val features = com.torve.presentation.apple.AppleNativeFeatures(org.koin.mp.KoinPlatform.getKoin())
                val full = features.catalogueDetail(extensionSeedJson, settingsState.appLanguage.code)
                viewModel.loadExtensionDetail(full)
            }
        } else {
            viewModel.ensureDetailLoaded(type, id)
        }
    }
    var episodeEntryApplied by androidx.compose.runtime.saveable.rememberSaveable(type, id, initialSeason, initialEpisode) { mutableStateOf(false) }
    LaunchedEffect(state.mediaItem?.tmdbId, state.isLoading, initialSeason, initialEpisode) {
        if (!episodeEntryApplied && !state.isLoading && state.mediaItem?.tmdbId == id && initialSeason != null && initialEpisode != null) {
            episodeEntryApplied = true
            viewModel.selectEpisodeForPlayback(initialSeason, initialEpisode, pinPlaybackTarget = true)
            viewModel.loadSeasonDetail(id, initialSeason)
        }
    }

    LaunchedEffect(state.mediaItem?.tmdbId, state.mediaLifecycleStatus?.state) {
        val media = state.mediaItem
        jellyfinAvailableEpisodes = if (media?.type == MediaType.SERIES && media.tmdbId != null) {
            runCatching { jellyfinLibrary.findAvailableEpisodes(media.tmdbId!!) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
    }

    // Refresh watch state when returning from the player (lifecycle ON_RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var resumeCount = 0
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.restoreSourcesAfterPlayback()
                // Skip the initial resume — loadDetail already handles it.
                if (resumeCount++ > 0) {
                    viewModel.resumeDetail()
                    viewModel.refreshMediaLifecycleStatus()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var resolvedFallbackUrl by remember { mutableStateOf("") }
    // Usenet source sheet: fire onSourceSheetOpened exactly once per
    // false→true transition of showStreamPicker. Keyed by the boolean
    // value, not by a recompose token, so the hook doesn't re-fire on
    // unrelated state changes within the open cycle.
    LaunchedEffect(state.showStreamPicker) {
        if (state.showStreamPicker) {
            viewModel.onSourceSheetOpened()
        }
    }

    // Usenet resolve → playback intent. On `/resolve` ready, the VM sets
    // state.usenetPlaybackIntent; we hand the opaque URL byte-for-byte
    // to the existing onPlayClick entrypoint, then clear the intent in
    // the VM so a recomposition can't re-launch playback. The URL is
    // self-authenticating under the live contract — no header staging.
    LaunchedEffect(state.usenetPlaybackIntent) {
        val intent = state.usenetPlaybackIntent ?: return@LaunchedEffect
        val item = state.mediaItem
        launchPlayback(
            /* url = */ intent.url,
            /* fallbackUrl = */ "",
            /* season = */ state.streamContextSeason,
            /* episode = */ state.streamContextEpisode,
            /* imdbId = */ item?.imdbId,
            /* autoSourceSelection = */ false,
        )
        viewModel.consumeUsenetPlaybackIntent()
    }

    LaunchedEffect(state.resolvedStream) {
        state.resolvedStream?.let { resolved ->
            // For debrid streams (service != null) the direct CDN link in
            // resolved.url needs no auth — RD's transcode endpoints require a
            // web session cookie that the in-app player doesn't have, causing
            // silent 401s. For addon-hosted streams there is no direct URL so
            // the transcode candidates are used as before.
            val urlCandidates = if (resolved.service != null) {
                listOf(resolved.url, resolved.transcodeUrls?.hls, resolved.transcodeUrls?.mp4)
            } else {
                listOf(resolved.transcodeUrls?.mp4, resolved.transcodeUrls?.hls, resolved.url)
            }
            val url = urlCandidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()
            val fallback = urlCandidates.firstOrNull { !it.isNullOrBlank() && it != url }.orEmpty()
            if (url.isBlank()) {
                resolvedUrl = ""
                resolvedFallbackUrl = ""
                resolvedUrlIsTemporary = false
                viewModel.clearResolvedStream()
                return@let
            }
            resolvedUrl = url
            resolvedFallbackUrl = fallback
            resolvedUrlIsTemporary = resolved.isTemporary
            viewModel.clearResolvedStream()

            // If this resolution was triggered by an episode download button,
            // enqueue the download directly instead of showing the action sheet.
            val dlTarget = pendingEpisodeDownload
            if (dlTarget != null) {
                val item = state.mediaItem
                android.util.Log.w(
                    "DetailScreen",
                    "pendingEpisodeDownload resolved: hasItem=${item != null} urlConfigured=${url.isNotBlank()}",
                )
                if (item != null) {
                    val (s, e) = dlTarget
                    val dlTitle = "${item.title} S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}"
                    android.util.Log.w("DetailScreen", "Calling downloadViewModel.enqueueDownload for $dlTitle")
                    downloadViewModel.enqueueDownload(
                        com.torve.domain.model.Download(
                            id = "${item.id}_${s}_${e}_${System.currentTimeMillis()}",
                            mediaId = item.id,
                            mediaType = item.type,
                            title = dlTitle,
                            posterUrl = item.posterUrl,
                            streamUrl = url,
                            status = com.torve.domain.model.DownloadStatus.PENDING,
                            seasonNumber = s,
                            episodeNumber = e,
                        ),
                    )
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.notif_download_started) + ": $dlTitle",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                pendingEpisodeDownload = null
            } else if (requestPlaybackOptions) {
                requestPlaybackOptions = false
                showActionSheet = true
            } else {
                launchPlayback(url, fallback, state.streamContextSeason, state.streamContextEpisode,
                    state.mediaItem?.imdbId, resolved.isTemporary)
            }
        }
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    val detailScrollState = rememberScrollState()
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.24f).coerceIn(160.dp, 240.dp)
    var primaryActionAboveViewport by remember(type, id) { mutableStateOf(false) }
    LaunchedEffect(state.returnToPrimaryRequestId) { if (state.returnToPrimaryRequestId > 0) detailScrollState.scrollTo(0) }


    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                val launchArtwork = com.torve.android.ui.components.ContentLaunchArtworkStore.current
                com.torve.android.ui.components.CinematicContentLoading(
                    title = launchArtwork?.title.orEmpty(),
                    backdropUrl = launchArtwork?.backdropUrl,
                    posterUrl = launchArtwork?.posterUrl,
                    logoUrl = launchArtwork?.logoUrl,
                    tmdbId = launchArtwork?.tmdbId,
                    mediaType = launchArtwork?.type,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = com.torve.android.error.resolveErrorKey(context, state.error) ?: stringResource(R.string.error_unknown),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { viewModel.loadDetail(type, id) }) {
                        Text(stringResource(R.string.common_retry))
                    }
                    onSettingsClick?.let { openSettings ->
                        TextButton(onClick = openSettings) { Text(stringResource(R.string.nav_settings)) }
                    }
                }
            }

            state.mediaItem != null -> {
                val item = state.mediaItem!!
                if (item.isStubDetail) {
                    LockedStubDetail(
                        title = stringResource(R.string.detail_stub_title),
                        body = stringResource(R.string.detail_stub_body),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    return@Box
                }
                val detailRatings = remember(item.ratings, item.rating) {
                    item.ratings.withFallbackTmdbScore(item.rating)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(detailScrollState)
                        .navigationBarsPadding(),
                ) {
                    // ── Immersive Backdrop ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                    ) {
                        // Content-policy hardening: never request real artwork for locked items
                        val detailImageModel = if (item.isContentPlaceholder || item.isStubDetail) null
                            else item.backdropUrl ?: item.posterUrl
                        AsyncImage(
                            model = detailImageModel,
                            contentDescription = if (item.isContentPlaceholder || item.isStubDetail) null else item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(HeroGradient)),
                        )

                        Box(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            com.torve.android.ui.product.ArtworkTitle(item, logoUrl = cinematicLogoUrl, onDarkArtwork = true)
                        }
                        // Back button removed — floating version below
                    }

                    // ── Content Section ──
                    Column(modifier = Modifier.align(Alignment.CenterHorizontally)
                        .widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                        // Title


                        // LAN-library presence pill (Prompt 9C). Hidden
                        // when no desktop hub on the same account is
                        // serving this title; shown once
                        // LanLibraryConsumer's manifest cache resolves.
                        com.torve.android.ui.components.LanAvailabilityBadge(
                            title = item.title,
                            modifier = Modifier.padding(top = 6.dp),
                        )

                        Spacer(Modifier.height(8.dp))

                        // Tagline
                        item.tagline?.let { tagline ->
                            if (tagline.isNotBlank()) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = tagline,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }



                        MobileDetailMetadata(item, detailRatings, settingsState.ratingPrefs)

                        Spacer(Modifier.height(8.dp))

                        if (item.genres.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.genres.forEach { genre -> GenrePill(genre.name) }
                            }
                        }
                        // ── Action Buttons ──
                        Spacer(Modifier.height(12.dp))
                        if (item.id.isNotBlank()) {
                            val primaryPlayback: @Composable RowScope.() -> Unit = {
                            Button(
                                accented = true,
                                onClick = {
                                    if (streamPlaybackLocked) {
                                        onLockedFeatureClick(PremiumFeature.STREAM_PLAYBACK)
                                    } else {
                                        // Always probe local download, Jellyfin, and LAN before
                                        // falling back to an online stream provider.
                                        if (item.type == MediaType.SERIES) {
                                            val ctxS = state.streamContextSeason ?: state.nextEpisode?.season
                                            val ctxE = state.streamContextEpisode ?: state.nextEpisode?.episode
                                            if (ctxS != null && ctxE != null) {
                                                openSourcePickerOrProvider(ctxS, ctxE)
                                            } else {
                                                viewModel.playNextEpisode()
                                            }
                                        } else {
                                            openSourcePickerOrProvider()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp).testTag("detail_primary_play")
                                    .onGloballyPositioned { primaryActionAboveViewport = it.boundsInRoot().bottom <= 0f },
                                enabled = !state.isLoadingStreams && !state.isResolving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    disabledContainerColor = Amber.copy(alpha = 0.4f),
                                ),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                if (state.isLoadingStreams || state.isResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.isResolving) stringResource(R.string.detail_resolving) else stringResource(R.string.detail_finding_streams),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else {
                                    Icon(
                                        if (streamPlaybackLocked) Icons.Rounded.Lock else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val playLabel = if (streamPlaybackLocked) {
                                        stringResource(R.string.premium_unlock_with_lifetime)
                                    } else detailPlaybackLabel(state)
                                    DetailPlaybackButtonLabel(playLabel)
                                }
                            }

                            }
                            if (state.isLoadingStreams || state.isResolving || state.streams.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                DetailPlaybackReadinessCard(
                                    streams = state.streams,
                                    startupCandidates = state.startupCandidates,
                                    startupStatus = state.playbackStartupStatus,
                                    isLoadingStreams = state.isLoadingStreams,
                                    isResolving = state.isResolving,
                                    isLoadingMoreSources = state.isLoadingMoreSources,
                                )
                            }

                            // Secondary actions row (adaptive columns to avoid cramped labels on narrow phones)
                            Spacer(Modifier.height(10.dp))
                            val isInWatchlist = state.mediaItem?.let {
                                watchlistState.containsMedia(it.id)
                            } ?: false
                            val isWatchlistUpdating = state.mediaItem?.let {
                                watchlistState.isMutatingMedia(it.id)
                            } ?: false
                            val isFavorite = state.mediaItem?.let {
                                favoritesState.favoriteKeys.contains(it.favoriteMediaKey())
                            } ?: false
                            val secondaryActions = buildList {
                                add(
                                    DetailSecondaryActionSpec(
                                        label = when {
                                            watchlistEditLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                                            isWatchlistUpdating -> stringResource(R.string.common_loading)
                                            isInWatchlist -> stringResource(R.string.detail_remove_watchlist)
                                            else -> stringResource(R.string.detail_add_watchlist)
                                        },
                                        icon = when {
                                            watchlistEditLocked -> Icons.Rounded.Lock
                                            isInWatchlist -> Icons.Rounded.Bookmark
                                            else -> Icons.Rounded.BookmarkBorder
                                        },
                                        containerColor = when {
                                            watchlistEditLocked -> Amber.copy(alpha = 0.2f)
                                            isInWatchlist -> Amber.copy(alpha = 0.2f)
                                            else -> Graphite
                                        },
                                        contentColor = when {
                                            watchlistEditLocked -> Amber
                                            isInWatchlist -> Amber
                                            else -> Snow
                                        },
                                        enabled = !isWatchlistUpdating,
                                        onClick = {
                                            if (watchlistEditLocked) {
                                                onLockedFeatureClick(PremiumFeature.WATCHLIST_EDIT)
                                            } else {
                                                state.mediaItem?.let { watchlistViewModel.toggleWatchlist(it) }
                                            }
                                        },
                                    ),
                                )
                                add(
                                    DetailSecondaryActionSpec(
                                        label = when {
                                            favoritesEditLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                                            isFavorite -> stringResource(R.string.detail_remove_favorites)
                                            else -> stringResource(R.string.detail_add_favorites)
                                        },
                                        icon = when {
                                            favoritesEditLocked -> Icons.Rounded.Lock
                                            isFavorite -> Icons.Rounded.Favorite
                                            else -> Icons.Rounded.FavoriteBorder
                                        },
                                        containerColor = when {
                                            favoritesEditLocked -> Amber.copy(alpha = 0.2f)
                                            isFavorite -> Amber.copy(alpha = 0.2f)
                                            else -> Graphite
                                        },
                                        contentColor = when {
                                            favoritesEditLocked -> Amber
                                            isFavorite -> Amber
                                            else -> Snow
                                        },
                                        onClick = {
                                            if (favoritesEditLocked) {
                                                onLockedFeatureClick(PremiumFeature.FAVORITES_EDIT)
                                            } else {
                                                state.mediaItem?.let { mediaFavoritesRepository.toggleFavorite(it) }
                                            }
                                        },
                                    ),
                                )

                                if (item.type == MediaType.MOVIE) onCollectionsClick?.let { open ->
                                    add(DetailSecondaryActionSpec(
                                        label = stringResource(R.string.detail_explore_collections),
                                        icon = Icons.Rounded.CollectionsBookmark,
                                        containerColor = Graphite,
                                        contentColor = Snow,
                                        testTag = "detail_collections",
                                        onClick = { open(item) },
                                    ))
                                }

                                if (settingsState.traktConnected) {
                                    if (onOpenTraktLists != null) add(DetailSecondaryActionSpec(
                                        label = "Trakt lists", icon = Icons.Rounded.PlaylistAdd,
                                        containerColor = Graphite, contentColor = Snow,
                                        onClick = { if (traktListLocked) onLockedFeatureClick(PremiumFeature.TRAKT_LIST_MANAGER) else onOpenTraktLists() },
                                    ))
                                    add(
                                        DetailSecondaryActionSpec(
                                            label = when {
                                                watchedStatusLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                                                item.type == MediaType.SERIES -> stringResource(R.string.detail_mark_show_watched)
                                                state.isMarkedWatched -> stringResource(R.string.detail_mark_movie_unwatched)
                                                else -> stringResource(R.string.detail_mark_movie_watched)
                                            },
                                            icon = when {
                                                watchedStatusLocked -> Icons.Rounded.Lock
                                                state.isMarkedWatched && item.type != MediaType.SERIES -> Icons.Rounded.VisibilityOff
                                                else -> Icons.Rounded.Visibility
                                            },
                                            containerColor = if (watchedStatusLocked) Amber.copy(alpha = 0.2f) else Graphite,
                                            contentColor = if (watchedStatusLocked) Amber else Snow,
                                            onClick = {
                                                if (watchedStatusLocked) {
                                                    onLockedFeatureClick(PremiumFeature.WATCHED_STATUS_EDIT)
                                                } else {
                                                    pendingWatchedChange = item.type == MediaType.SERIES || !state.isMarkedWatched
                                                }
                                            },
                                        ),
                                    )
                                    if (item.type == MediaType.SERIES && (state.isMarkedWatched || state.watchedEpisodes.isNotEmpty())) {
                                        add(DetailSecondaryActionSpec(
                                            label = stringResource(R.string.detail_clear_show_history),
                                            icon = if (watchedStatusLocked) Icons.Rounded.Lock else Icons.Rounded.VisibilityOff,
                                            containerColor = Graphite, contentColor = Snow,
                                            onClick = {
                                                if (watchedStatusLocked) onLockedFeatureClick(PremiumFeature.WATCHED_STATUS_EDIT)
                                                else pendingWatchedChange = false
                                            },
                                        ))
                                    }
                                    add(
                                        DetailSecondaryActionSpec(
                                            label = if (traktListLocked) {
                                                stringResource(R.string.premium_unlock_with_lifetime)
                                            } else {
                                                state.userRating?.let { stringResource(R.string.detail_rated_value, it) } ?: stringResource(R.string.detail_your_rating)
                                            },
                                            icon = if (traktListLocked) Icons.Rounded.Lock else Icons.Rounded.Star,
                                            containerColor = when {
                                                traktListLocked -> Amber.copy(alpha = 0.2f)
                                                state.userRating != null -> Amber.copy(alpha = 0.2f)
                                                else -> Graphite
                                            },
                                            contentColor = when {
                                                traktListLocked -> Amber
                                                state.userRating != null -> Amber
                                                else -> Snow
                                            },
                                            onClick = {
                                                if (traktListLocked) {
                                                    onLockedFeatureClick(PremiumFeature.TRAKT_LIST_MANAGER)
                                                } else {
                                                    showRatingDialog = true
                                                }
                                            },
                                        ),
                                    )
                                }
                            }
                            val playbackOptions = DetailSecondaryActionSpec(
                                label = "Playback options", icon = Icons.Rounded.PlayArrow,
                                containerColor = Graphite, contentColor = Snow,
                                onClick = { requestPlaybackOptions = true; openSourcePickerOrProvider(state.primaryPlaybackTarget.season, state.primaryPlaybackTarget.episode) })
                            val renderSecondaryActions: @Composable (List<DetailSecondaryActionSpec>) -> Unit = { libraryActions ->
                                DetailSecondaryActions(
                                    title = item.title,
                                    primaryContent = primaryPlayback,
                                    watchlistAction = secondaryActions.firstOrNull(),
                                    onPlayOnTv = if (!isTvDevice && (item.tmdbId ?: id) > 0 && !tvHandoffBusy) ({
                                        syncCoordinator.refreshDevices()
                                        showTvPicker = true
                                    }) else null,
                                    actions = libraryActions + secondaryActions + playbackOptions,
                                    onTrailer = if (item.trailerKey != null) ({ showTrailer = true }) else null,
                                    modifier = Modifier.fillMaxWidth(),
                                    libraryStatus = state,
                                )
                            }
                            if (settingsState.optionalAppFeaturesLoaded && settingsState.optionalAppFeatures.arrStack) {
                                DetailLibraryActions(
                                    state = state,
                                    isSeries = item.type == MediaType.SERIES,
                                    onRequest = { season -> viewModel.requestPermanentCopy(seasons = season?.let(::listOf)) },
                                    onRetry = viewModel::retryMediaLifecycleRequest,
                                    onDeleteRequest = viewModel::deleteMediaLifecycleRequest,
                                    onRefresh = { viewModel.refreshMediaLifecycleStatus(force = true) },
                                    content = renderSecondaryActions,
                                )
                            } else {
                                renderSecondaryActions(emptyList())
                            }

                            // Errors
                            StreamFilterUiText.visibleErrorMessage(
                                error = if (state.streams.isEmpty()) state.streamsError else null,
                                premiumFeedbackEnabled = runtimeFilterFeedbackEnabled,
                            )?.let { visibleError ->
                                ErrorMessage(visibleError, Modifier.padding(top = 8.dp))
                            }
                            StreamFilterUiText.visibleHint(
                                hint = if (state.streams.isEmpty()) state.streamsErrorHint else null,
                                premiumFeedbackEnabled = runtimeFilterFeedbackEnabled,
                            )?.let { hint ->
                                val visibleHint = if (
                                    isTvDevice &&
                                    state.streamsError == StreamFilterUiText.ALL_HIDDEN_MESSAGE &&
                                    hint == StreamFilterUiText.ADJUST_REGEX_HINT
                                ) {
                                    StreamFilterUiText.MANAGE_FILTERS_ON_MOBILE_OR_DESKTOP_HINT
                                } else {
                                    hint
                                }
                                Text(
                                    text = visibleHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Silver,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            // Timeout branch from the preparing loop falls
                            // through to this resolveError row with the
                            // error_stream_preparing_timeout message key.
                            state.resolveError?.let { error ->
                                ErrorMessage(error, Modifier.padding(top = 8.dp))
                            }
                            if (((state.streamsError != null).and(state.streams.isEmpty())).or(state.resolveError != null)) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.fetchStreams(
                                                season = state.streamContextSeason,
                                                episode = state.streamContextEpisode,
                                                forceManualPick = true,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.common_retry), color = Amber)
                                    }
                                    onSettingsClick?.let { openSettings ->
                                        TextButton(onClick = openSettings) {
                                            Text(stringResource(R.string.nav_settings), color = Amber)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        com.torve.android.ui.community.FullSynopsis(item)

                        Spacer(Modifier.height(20.dp))
                        WhereToWatchSection(
                            contentKey = "$type:$id",
                            offers = state.availability?.offers.orEmpty(),
                            isLoading = state.isLoadingAvailability,
                            error = com.torve.android.error.resolveErrorKey(context, state.availabilityError)
                                ?: state.availabilityError,
                            onOpenOffer = { offer ->
                                val targetUrl = offer.deeplinkUrl ?: offer.webUrl
                                if (!targetUrl.isNullOrBlank()) {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                        context.startActivity(intent)
                                    }
                                }
                            },
                        )

                        // ── Director ──
                        item.director?.let { director ->
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.detail_director),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Torve.colors.textTertiary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = director,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = if (item.directorId != null && onPersonClick != null) {
                                        Modifier.clickable { viewModel.leaveDetail(); onPersonClick(item.directorId!!) }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }

                        // ── Bulk download progress ──
                        if (bulkProgress.isActive) {
                            Spacer(Modifier.height(12.dp))
                            Column {
                                Text(
                                    text = localizedUiText("Downloading ${bulkProgress.currentEpisodeLabel} (${bulkProgress.completedEpisodes}/${bulkProgress.totalEpisodes})"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Amber,
                                )
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { if (bulkProgress.totalEpisodes > 0) bulkProgress.completedEpisodes.toFloat() / bulkProgress.totalEpisodes else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Amber,
                                    trackColor = Graphite,
                                )
                            }
                        }

                        // ── Episode Selector (TV Shows) ──
                        if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            EpisodeSelector(
                                hideUnwatchedSpoilers = state.hideUnwatchedSpoilers,
                                seasons = item.seasons,
                                selectedSeason = state.selectedSeason,
                                seasonDetail = state.seasonDetail,
                                isLoadingSeasonDetail = state.isLoadingSeasonDetail,
                                watchedEpisodes = state.watchedEpisodes,
                                availableInJellyfinEpisodes = jellyfinAvailableEpisodes,
                                seriesRatings = item.ratings.withFallbackTmdbScore(item.rating),
                                onSeasonSelected = { seasonNum ->
                                    if (extensionSeedJson != null) viewModel.selectExtensionSeason(seasonNum)
                                    else item.tmdbId?.let { viewModel.loadSeasonDetail(it, seasonNum) }
                                },
                                onEpisodePlay = { season, episode ->
                                    openSourcePickerOrProvider(season, episode)
                                },
                                onEpisodeDownload = { season, episode ->
                                    if (settingsState.connectedDebridProviders.isNotEmpty()) {
                                        pendingEpisodeDownload = season to episode
                                        viewModel.fetchStreams(season = season, episode = episode)
                                    }
                                },
                                onDownloadSeason = { season ->
                                    if (settingsState.connectedDebridProviders.isNotEmpty()) {
                                        val media = state.mediaItem ?: return@EpisodeSelector
                                        val seasonObj = media.seasons.find { it.seasonNumber == season }
                                        val epCount = seasonObj?.episodeCount ?: return@EpisodeSelector
                                        coroutineScope.launch {
                                            val episodes = bulkDownloadManager.buildSeasonTargets(season, epCount)
                                            val ids = bulkDownloadManager.enqueueBulk(
                                                mediaItem = media,
                                                episodes = episodes,
                                                debridProvider = settingsViewModel.getDebridProvider(),
                                                debridApiKey = settingsViewModel.getDebridApiKey(),
                                                debridAccounts = settingsViewModel.getDebridAccounts(),
                                                preferences = settingsViewModel.buildStreamPreferences(),
                                                deviceCaps = DeviceCodecProbe.probe(),
                                            )
                                            ids.forEach { DownloadWorker.enqueue(context, it) }
                                            downloadViewModel.loadDownloads()
                                        }
                                    }
                                },
                                onDownloadAll = {
                                    if (settingsState.connectedDebridProviders.isNotEmpty()) {
                                        val media = state.mediaItem ?: return@EpisodeSelector
                                        coroutineScope.launch {
                                            val episodes = bulkDownloadManager.buildAllSeasonsTargets(media)
                                            val ids = bulkDownloadManager.enqueueBulk(
                                                mediaItem = media,
                                                episodes = episodes,
                                                debridProvider = settingsViewModel.getDebridProvider(),
                                                debridApiKey = settingsViewModel.getDebridApiKey(),
                                                debridAccounts = settingsViewModel.getDebridAccounts(),
                                                preferences = settingsViewModel.buildStreamPreferences(),
                                                deviceCaps = DeviceCodecProbe.probe(),
                                            )
                                            ids.forEach { DownloadWorker.enqueue(context, it) }
                                            downloadViewModel.loadDownloads()
                                        }
                                    }
                                },
                                onMarkSeasonWatched = { season ->
                                    if (watchedStatusLocked) {
                                        onLockedFeatureClick(PremiumFeature.WATCHED_STATUS_EDIT)
                                    } else {
                                        viewModel.markSeasonWatched(season)
                                    }
                                },
                                onToggleEpisodeWatched = { season, episode ->
                                    if (watchedStatusLocked) {
                                        onLockedFeatureClick(PremiumFeature.WATCHED_STATUS_EDIT)
                                    } else {
                                        viewModel.toggleEpisodeWatched(season, episode)
                                    }
                                },
                            )
                        }

                        if (state.sourceStatistics.isNotEmpty() || state.isLoadingStreams) {
                            com.torve.android.ui.community.SourceStatisticsButton(state.sourceStatistics, state.isLoadingStreams || state.isLoadingMoreSources)
                        }
                        // ── Cast ──
                        if (item.cast.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = stringResource(R.string.detail_cast),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(item.cast.take(15)) { castMember ->
                                    CastAvatar(
                                        name = castMember.name,
                                        character = castMember.character,
                                        profileUrl = castMember.profileUrl,
                                        onClick = {
                                            if (castMember.id > 0) { viewModel.leaveDetail(); onPersonClick?.invoke(castMember.id) }
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        com.torve.android.ui.community.ContentCommunityPanel(item)

                        // ── Similar ──
                        if (state.similar.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = stringResource(R.string.detail_more_like_this),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                itemsIndexed(state.similar, key = { index, item -> mediaItemLazyKey(item, index) }) { _, similar ->
                                    PosterCard(
                                        item = similar,
                                        sizeOverride = CardSize.SMALL,
                                        onClick = { onMediaClick(similar) },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(88.dp))
                    }
                }

                // Auto-play message snackbar
                state.autoPlayMessage?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.BottomCenter),
                    ) {
                        Snackbar(
                            containerColor = Graphite,
                            contentColor = Snow,
                            action = if (state.autoPlayFailed) {
                                {
                                    TextButton(
                                        onClick = {
                                            if (chooseSourceLocked) {
                                                onLockedFeatureClick(PremiumFeature.CHOOSE_SOURCE_PREMIUM)
                                            } else {
                                                viewModel.showManualPicker()
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.detail_select_manually), color = Amber)
                                    }
                                }
                            } else null,
                        ) {
                            Text(text = localizedUiText(message), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Watchlist snackbar
                watchlistState.snackbarMessage?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.BottomCenter),
                    ) {
                        Snackbar(
                            containerColor = Graphite,
                            contentColor = Snow,
                        ) {
                            Text(text = localizedUiText(message), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(2000)
                        watchlistViewModel.clearSnackbar()
                    }
                }

                // Stream picker
                if (state.showStreamPicker && state.streams.isNotEmpty()) {
                    StreamPickerSheet(
                        onRefresh = { viewModel.refreshStreams(state.streamContextSeason, state.streamContextEpisode) },
                        isInspectingSourceLanguages = state.isInspectingSourceLanguages,
                        sourceSessionKey = "${state.mediaItem?.id}:${state.streamContextSeason}:${state.streamContextEpisode}",
                        sourceStatistics = state.sourceStatistics,
                        streams = state.streams,
                        startupCandidates = state.startupCandidates,
                        isResolving = state.isResolving,
                        isLoadingMoreSources = state.isLoadingMoreSources,
                        playbackStartupStatus = state.playbackStartupStatus,
                        hiddenByFiltersCount = if (runtimeFilterFeedbackEnabled) state.streamFilterHiddenCount else 0,
                        onStreamSelected = { stream ->
                            // Usenet rows go through the NzbDAV resolver; every
                            // other provenance falls through to the existing
                            // debrid/addon resolve path exactly as before.
                            if (stream.accelerationProvenanceKind == CandidateProvenanceKind.USENET_NZBDAV) {
                                viewModel.selectUsenetSource(stream)
                            } else {
                                viewModel.resolveStream(
                                    stream = stream,
                                    provider = settingsViewModel.getDebridProvider(),
                                    apiKey = settingsViewModel.getDebridApiKey(),
                                )
                            }
                        },
                        usenetCandidates = state.usenetCandidates,
                        onDismiss = { requestPlaybackOptions = false; viewModel.cancelSourceLookup(); viewModel.dismissStreamPicker() },
                    )
                }

                sourcePickerState?.let { picker ->
                    val item = state.mediaItem
                    MobileSourcePickerSheet(
                        state = picker,
                        onSelect = { option ->
                            sourcePickerState = null
                            if (item == null) return@MobileSourcePickerSheet
                            if (TvDetailsSourcePickerStateBuilder.isProviderFetchSentinel(option)) {
                                viewModel.fetchStreams(season = sourcePickerSeason, episode = sourcePickerEpisode)
                                return@MobileSourcePickerSheet
                            }
                            when (val route = option.route) {
                                is PlaybackRoute.LocalFile -> {
                                    launchPlayback(
                                        "file://${route.absolutePath}",
                                        "",
                                        sourcePickerSeason,
                                        sourcePickerEpisode,
                                        item.imdbId,
                                        true,
                                    )
                                }
                                is PlaybackRoute.JellyfinStream -> {
                                    PendingLanPlaybackHandoff.stage(route)
                                    launchPlayback(
                                        route.url,
                                        "",
                                        sourcePickerSeason,
                                        sourcePickerEpisode,
                                        item.imdbId,
                                        true,
                                    )
                                }
                                is PlaybackRoute.LanDesktopStream -> {
                                    PendingLanPlaybackHandoff.stage(route)
                                    launchPlayback(
                                        route.url,
                                        "",
                                        sourcePickerSeason,
                                        sourcePickerEpisode,
                                        item.imdbId,
                                        true,
                                    )
                                }
                                is PlaybackRoute.ProviderStream -> {
                                    viewModel.fetchStreams(season = sourcePickerSeason, episode = sourcePickerEpisode)
                                }
                                PlaybackRoute.ReDownload -> Unit
                            }
                        },
                        onDismiss = {
                            sourcePickerState = null
                            sourcePickerSeason = null
                            sourcePickerEpisode = null
                        },
                    )
                }

                // Action sheet after resolution
                if (showActionSheet && resolvedUrl.isNotBlank()) {
                    val item = state.mediaItem
                    val ctxSeason = state.streamContextSeason
                    val ctxEpisode = state.streamContextEpisode
                    val dlTitle = if (ctxSeason != null && ctxEpisode != null) {
                        "${item?.title ?: ""} S${ctxSeason.toString().padStart(2, '0')}E${ctxEpisode.toString().padStart(2, '0')}"
                    } else {
                        item?.title ?: ""
                    }
                    StreamActionSheet(
                        onExternalPlaybackStarted = { viewModel.onPlaybackLaunched(ctxSeason, ctxEpisode) },
                        url = resolvedUrl,
                        title = dlTitle,
                        posterUrl = item?.posterUrl ?: "",
                        onPlayInApp = { launchPlayback(resolvedUrl, resolvedFallbackUrl, ctxSeason, ctxEpisode, item?.imdbId, resolvedUrlIsTemporary) },
                        onDownload = {
                            if (item != null) {
                                downloadViewModel.enqueueDownload(
                                    Download(
                                        id = "${item.id}_${ctxSeason ?: 0}_${ctxEpisode ?: 0}_${System.currentTimeMillis()}",
                                        mediaId = item.id,
                                        mediaType = item.type,
                                        title = dlTitle,
                                        posterUrl = item.posterUrl,
                                        streamUrl = resolvedUrl,
                                        filePath = null,
                                        fileSizeBytes = null,
                                        status = DownloadStatus.PENDING,
                                        seasonNumber = ctxSeason,
                                        episodeNumber = ctxEpisode,
                                        createdAt = System.currentTimeMillis(),
                                        completedAt = null,
                                    ),
                                )
                            }
                        },
                        onDismiss = {
                            showActionSheet = false
                            resolvedUrl = ""
                            resolvedUrlIsTemporary = false
                        },
                    )
                }

                // Trailer dialog
                if (showTrailer) {
                    state.mediaItem?.trailerKey?.let { key ->
                        TrailerPlayerDialog(
                            youtubeKey = key,
                            onDismiss = { showTrailer = false },
                            onOpenExternal = { launchTrailer(context, key) },
                        )
                    }
                }
            }
        }

            pendingWatchedChange?.let { mark ->
                DetailWatchedConfirmation(
                    isSeries = state.mediaItem?.type == MediaType.SERIES,
                    markWatched = mark,
                    onConfirm = {
                        pendingWatchedChange = null
                        if (mark) viewModel.markWatched() else viewModel.markUnwatched()
                    },
                    onDismiss = { pendingWatchedChange = null },
                )
            }
            if (showRatingDialog) {
                DetailRatingDialog(
                    currentRating = state.userRating,
                    onSave = { rating -> showRatingDialog = false; viewModel.setUserRating(rating) },
                    onDismiss = { showRatingDialog = false },
                )
            }

            // Floating back button — always visible regardless of scroll position
            FloatingBackButton(
                onBack = onBack,
                label = stringResource(R.string.common_back_cd),
                compact = true,
                modifier = Modifier
                    .zIndex(20f)
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 10.dp),
            )

            // Keep the primary action reachable on long mobile detail pages.
            // The in-content button remains the explanatory first action; this
            // compact duplicate only appears once that area has scrolled away.
            val stickyPlayItem = state.mediaItem
            if (
                !isTvDevice &&
                primaryActionAboveViewport &&
                stickyPlayItem != null && (stickyPlayItem.imdbId != null || stickyPlayItem.tmdbId != null) &&
                !state.showStreamPicker &&
                sourcePickerState == null &&
                !showActionSheet
            ) {
                Button(
                    onClick = {
                        if (streamPlaybackLocked) {
                            onLockedFeatureClick(PremiumFeature.STREAM_PLAYBACK)
                        } else if (stickyPlayItem.type == MediaType.SERIES) {
                            val season = state.streamContextSeason ?: state.nextEpisode?.season
                            val episode = state.streamContextEpisode ?: state.nextEpisode?.episode
                            if (season != null && episode != null) {
                                openSourcePickerOrProvider(season, episode)
                            } else {
                                viewModel.playNextEpisode()
                            }
                        } else {
                            openSourcePickerOrProvider()
                        }
                    },
                    modifier = Modifier
                        .zIndex(24f)
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(max = 960.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !state.isLoadingStreams && !state.isResolving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                        disabledContainerColor = Amber.copy(alpha = 0.45f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.isLoadingStreams || state.isResolving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Obsidian,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    val stickyLabel = when {
                        state.isResolving -> stringResource(R.string.detail_resolving)
                        state.isLoadingStreams -> stringResource(R.string.detail_finding_streams)
                        streamPlaybackLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                        else -> detailPlaybackLabel(state)
                    }
                    Text(
                        stickyLabel,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Preparing-stream overlay: blocks the detail surface while a
            // Panda cloud-client stream is still warming up. Never open the
            // player until the probe says Ready; cancelling routes through
            // the VM to stop the retry loop cleanly.
            state.preparing?.let { preparing ->
                StreamPreparingOverlay(
                    state = preparing,
                    mediaItem = state.mediaItem?.copy(logoUrl = cinematicLogoUrl ?: state.mediaItem?.logoUrl),
                    onCancel = { viewModel.cancelPreparing() },
                    modifier = Modifier.zIndex(80f),
                )
            }
            if ((state.isLoadingStreams || state.isResolving) && state.preparing == null && sourcePickerState == null) {
                val resolvingItem = state.mediaItem
                com.torve.android.ui.components.CinematicContentLoading(
                    title = resolvingItem?.title.orEmpty(),
                    backdropUrl = resolvingItem?.backdropUrl,
                    posterUrl = resolvingItem?.posterUrl,
                    logoUrl = cinematicLogoUrl ?: resolvingItem?.logoUrl,
                    tmdbId = resolvingItem?.tmdbId,
                    mediaType = resolvingItem?.type,
                    modifier = Modifier.fillMaxSize().zIndex(70f),
                )
            }
        }
    }
}

private fun launchTrailer(context: android.content.Context, youtubeKey: String) {
    val youtubeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")).apply {
        setPackage("com.google.android.youtube")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeKey")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(youtubeIntent)
    } catch (_: Exception) {
        context.startActivity(webIntent)
    }
}

private suspend fun buildMobileDetailPickerState(
    item: MediaItem,
    seasonNumber: Int?,
    episodeNumber: Int?,
    downloadRepository: DownloadRepository,
    jellyfinLibrary: JellyfinLibraryOverlayService,
    lanLibraryConsumer: LanLibraryConsumer,
    networkMonitor: NetworkMonitor,
    wifiOnlyForLan: Boolean,
): TvSourcePickerState {
    val mediaIds = buildSet {
        item.tmdbId?.toString()?.let(::add)
        item.id.takeIf { it.isNotBlank() }?.let(::add)
    }
    val downloads = runCatching { downloadRepository.getAllDownloads() }.getOrDefault(emptyList())
    val localFilePath = DetailSourcePickerLookup.completedLocalFilePath(
        downloads = downloads,
        mediaIds = mediaIds,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    val jellyfinRoute = item.tmdbId?.let { tmdbId ->
        runCatching {
            jellyfinLibrary.findPlaybackRoute(
                tmdbId = tmdbId,
                mediaType = item.type,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                imdbId = item.imdbId,
            )
        }.getOrNull()
    }
    val lanRoute = runCatching {
        lanLibraryConsumer.findLanRoute(
            title = item.title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
    }.getOrNull()
    val networkMode = when (networkMonitor.currentNetworkType()) {
        NetworkType.WIFI -> NetworkMode.WIFI
        NetworkType.CELLULAR -> NetworkMode.CELLULAR
        NetworkType.ETHERNET -> NetworkMode.ETHERNET
        NetworkType.UNKNOWN, NetworkType.NONE -> NetworkMode.UNKNOWN
    }
    return TvDetailsSourcePickerStateBuilder.build(
        localFilePath = localFilePath,
        jellyfinRoute = jellyfinRoute,
        lanRoute = lanRoute,
        providerAvailable = true,
        networkMode = networkMode,
        wifiOnlyForLan = wifiOnlyForLan,
    )
}

@Composable
private fun LockedStubDetail(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textSecondary,
        )
    }
}

@Composable
private fun MetadataText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Torve.colors.textTertiary,
    )
}

@Composable
private fun GenrePill(name: String) {
    Box(
        modifier = Modifier
            .background(
                Graphite,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = Torve.colors.textSecondary,
        )
    }
}

@Composable
private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    val resolved = com.torve.android.error.resolveErrorKey(
        androidx.compose.ui.platform.LocalContext.current,
        message,
    ) ?: message
    Text(
        text = resolved,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * Shown while a Panda cloud-client stream is still being prepared. Framed as
 * progress (not error): the cloud download is working, we'll auto-retry once,
 * and the user can nudge a retry manually if the auto-retry also 504s.
 */
