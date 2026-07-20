package com.torve.android.ui.detail

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.HeroGradient
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.CandidateProvenanceKind
import org.koin.compose.koinInject
import com.torve.android.download.DownloadWorker
import com.torve.data.download.BulkDownloadManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.content.Intent
import android.net.Uri
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.AvailabilityOffer
import com.torve.domain.model.AvailabilityOfferType
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
import com.torve.util.FormatUtil
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    type: String,
    id: Int,
    accessTier: AccessTier = AccessTier.FREE,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    onPlayClick: (url: String, fallbackUrl: String, season: Int?, episode: Int?, imdbId: String?, autoSourceSelection: Boolean) -> Unit,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onPersonClick: ((Int) -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onOpenAddonCatalog: (() -> Unit)? = null,
    viewModel: DetailViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    downloadViewModel: DownloadViewModel = koinInject(),
    watchlistViewModel: WatchlistViewModel = koinInject(),
    mediaFavoritesRepository: MediaFavoritesRepository = koinInject(),
    addonViewModel: com.torve.presentation.addon.AddonViewModel = koinInject(),
    downloadRepository: DownloadRepository = koinInject(),
    lanLibraryConsumer: LanLibraryConsumer = koinInject(),
    networkMonitor: NetworkMonitor = koinInject(),
) {
    val bulkDownloadManager: BulkDownloadManager = koinInject()
    val bulkProgress by bulkDownloadManager.progress.collectAsState()
    // Kick the LAN-library consumer once so the "Available on desktop"
    // pill resolves on first paint (instead of waiting for the next
    // background poll).
    com.torve.android.ui.components.LanAvailabilityBootstrap()
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val runtimeFilterFeedbackEnabled = true
    val watchlistState by watchlistViewModel.state.collectAsState()
    val favoritesState by mediaFavoritesRepository.state.collectAsState()
    val addonState by addonViewModel.state.collectAsState()
    val hasAnyAddon = addonState.addons.isNotEmpty()
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
    var resolvedUrl by remember { mutableStateOf("") }
    var resolvedUrlIsTemporary by remember { mutableStateOf(false) }
    var showTrailer by remember { mutableStateOf(false) }
    var sourcePickerState by remember { mutableStateOf<TvSourcePickerState?>(null) }
    var sourcePickerSeason by remember { mutableStateOf<Int?>(null) }
    var sourcePickerEpisode by remember { mutableStateOf<Int?>(null) }
    // Tracks whether the current stream resolution is for download (not playback)
    var pendingEpisodeDownload by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current
    val isTvDevice = remember(context) { DeviceFormFactor.isTv(context) }

    fun openSourcePickerOrProvider(season: Int? = null, episode: Int? = null) {
        val item = state.mediaItem ?: return
        coroutineScope.launch {
            val picker = buildMobileDetailPickerState(
                item = item,
                seasonNumber = season,
                episodeNumber = episode,
                downloadRepository = downloadRepository,
                lanLibraryConsumer = lanLibraryConsumer,
                networkMonitor = networkMonitor,
                wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
            )
            val hasDirectSource = picker.options.any { opt ->
                opt.route is PlaybackRoute.LocalFile || opt.route is PlaybackRoute.LanDesktopStream
            }
            if (!hasDirectSource) {
                viewModel.fetchStreams(season = season, episode = episode)
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
    LaunchedEffect(type, id) {
        if (id <= 0) {
            onBack()
            return@LaunchedEffect
        }
        viewModel.loadDetail(type, id)
    }

    // Refresh watch state when returning from the player (lifecycle ON_RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var resumeCount = 0
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Skip the initial resume — loadDetail already handles it.
                if (resumeCount++ > 0) {
                    viewModel.refreshWatchState()
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
        onPlayClick(
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
                            id = "${item.tmdbId}_${s}_${e}_${System.currentTimeMillis()}",
                            mediaId = item.tmdbId.toString(),
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
            } else {
                showActionSheet = true
            }
        }
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    val detailScrollState = rememberScrollState()
    val compactBackThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    val useCompactBackButton = detailScrollState.value >= compactBackThresholdPx

    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(48.dp),
                    color = Amber,
                )
            }

            state.error != null -> {
                Text(
                    text = com.torve.android.error.resolveErrorKey(androidx.compose.ui.platform.LocalContext.current, state.error) ?: stringResource(R.string.error_unknown),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
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
                val hasRenderableDetailProviders = remember(detailRatings, settingsState.ratingPrefs) {
                    detailRatings?.hasAnyEnabledDisplayValue(settingsState.ratingPrefs) == true
                }
                val torveDetailScore = remember(detailRatings, settingsState.ratingPrefs) {
                    detailRatings?.takeIf { settingsState.ratingPrefs.showTorveScoreOnDetailPage }
                        ?.let { calculateTorveScore(it, settingsState.ratingPrefs.torveWeights) }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(detailScrollState),
                ) {
                    // ── Immersive Backdrop ──
                    // Taller than before, deeper gradient, content overlaid
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
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

                        // Back button removed — floating version below
                    }

                    // ── Content Section ──
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        // Title
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            color = Snow,
                        )

                        // LAN-library presence pill (Prompt 9C). Hidden
                        // when no desktop hub on the same account is
                        // serving this title; shown once
                        // LanLibraryConsumer's manifest cache resolves.
                        com.torve.android.ui.components.LanAvailabilityBadge(
                            title = item.title,
                            modifier = Modifier.padding(top = 6.dp),
                        )

                        if (state.isInLibrary) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .background(Graphite, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_in_library),
                                    color = Amber,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Metadata row — year • runtime • rating
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item.year?.let {
                                MetadataText(it.toString())
                            }
                            item.runtime?.let { runtime ->
                                if (runtime > 0) {
                                    MetadataText("•")
                                    MetadataText(FormatUtil.formatRuntime(runtime))
                                }
                            }
                            torveDetailScore?.let {
                                MetadataText("•")
                                Text(
                                    text = "Torve ${"%.0f".format(it)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Genre pills
                        if (item.genres.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item.genres.forEach { genre ->
                                    GenrePill(genre.name)
                                }
                            }
                        }

                        // Multi-source rating pills
                        if (settingsState.ratingPrefs.showRatingsOnDetailPage && detailRatings != null) {
                            if (hasRenderableDetailProviders) {
                                Spacer(Modifier.height(12.dp))
                                MultiRatingPills(
                                    ratings = detailRatings,
                                    prefs = settingsState.ratingPrefs.copy(
                                        maxRatingsOnCard = settingsState.ratingPrefs.enabledProviders.size.coerceAtLeast(1),
                                    ),
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            if (hasRenderableDetailProviders || torveDetailScore != null) {
                                DetailRatingsAttribution(
                                    ratings = detailRatings,
                                    prefs = settingsState.ratingPrefs,
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Action Buttons ──
                        if (item.imdbId != null) {
                            // Play button — full width, amber, prominent
                            Button(
                                onClick = {
                                    if (streamPlaybackLocked) {
                                        onLockedFeatureClick(PremiumFeature.STREAM_PLAYBACK)
                                    } else if (!settingsState.canPlayStreams) {
                                        // No stream source (debrid or stream-providing addon) available.
                                        // Metadata-only addons like Cinemeta don't qualify. Send the user
                                        // to the addon catalog to install one (e.g. Panda). Predicate
                                        // matches TvDetailsScreen for phone/TV consistency.
                                        onOpenAddonCatalog?.invoke()
                                    } else {
                                        // Let the addon layer decide between debrid/Usenet/etc. Panda
                                        // handles its own routing based on its saved config.
                                        if (item.type == MediaType.SERIES) {
                                            val ctxS = state.streamContextSeason
                                            val ctxE = state.streamContextEpisode
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
                                    .fillMaxWidth()
                                    .height(52.dp),
                                enabled = !state.isLoadingStreams && !state.isResolving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Amber,
                                    contentColor = Obsidian,
                                    disabledContainerColor = Amber.copy(alpha = 0.4f),
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (state.isLoadingStreams || state.isResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Obsidian,
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
                                    val playLabel = when {
                                        streamPlaybackLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                                        !settingsState.canPlayStreams -> stringResource(R.string.detail_install_addon)
                                        item.type == MediaType.SERIES && state.streamContextSeason != null && state.streamContextEpisode != null ->
                                            "S${state.streamContextSeason.toString().padStart(2, '0')}E${state.streamContextEpisode.toString().padStart(2, '0')}"
                                        else -> state.primaryPlayLabel
                                    }
                                    Text(
                                        playLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            // Watch Trailer button
                            state.mediaItem?.trailerKey?.let {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { showTrailer = true },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Movie,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.detail_watch_trailer),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
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

                                if (settingsState.traktConnected) {
                                    add(
                                        DetailSecondaryActionSpec(
                                            label = when {
                                                watchedStatusLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                                                state.isMarkedWatched -> stringResource(R.string.detail_unwatched)
                                                else -> stringResource(R.string.detail_watched)
                                            },
                                            icon = when {
                                                watchedStatusLocked -> Icons.Rounded.Lock
                                                state.isMarkedWatched -> Icons.Rounded.VisibilityOff
                                                else -> Icons.Rounded.Visibility
                                            },
                                            containerColor = if (watchedStatusLocked) Amber.copy(alpha = 0.2f) else Graphite,
                                            contentColor = if (watchedStatusLocked) Amber else Snow,
                                            onClick = {
                                                if (watchedStatusLocked) {
                                                    onLockedFeatureClick(PremiumFeature.WATCHED_STATUS_EDIT)
                                                } else {
                                                    if (state.isMarkedWatched) viewModel.markUnwatched()
                                                    else viewModel.markWatched()
                                                }
                                            },
                                        ),
                                    )
                                    add(
                                        DetailSecondaryActionSpec(
                                            label = if (traktListLocked) {
                                                stringResource(R.string.premium_unlock_with_lifetime)
                                            } else {
                                                state.userRating?.let { "Rated $it/10" } ?: "Rate"
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
                                                    val next = when (val current = state.userRating) {
                                                        null -> 7
                                                        10 -> null
                                                        else -> current + 1
                                                    }
                                                    viewModel.setUserRating(next)
                                                }
                                            },
                                        ),
                                    )
                                }
                            }
                            DetailSecondaryActionsGrid(
                                actions = secondaryActions,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // Errors
                            StreamFilterUiText.visibleErrorMessage(
                                error = state.streamsError,
                                premiumFeedbackEnabled = runtimeFilterFeedbackEnabled,
                            )?.let { visibleError ->
                                ErrorMessage(visibleError, Modifier.padding(top = 8.dp))
                            }
                            StreamFilterUiText.visibleHint(
                                hint = state.streamsErrorHint,
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
                        }

                        Spacer(Modifier.height(20.dp))
                        WhereToWatchSection(
                            offers = state.availability?.offers.orEmpty(),
                            isLoading = state.isLoadingAvailability,
                            error = state.availabilityError,
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

                        // Tagline
                        item.tagline?.let { tagline ->
                            if (tagline.isNotBlank()) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = "\"$tagline\"",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Amber.copy(alpha = 0.8f),
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }

                        // Overview
                        item.overview?.let { overview ->
                            if (overview.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = overview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Torve.colors.textSecondary,
                                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                                )
                            }
                        }

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
                                Text(
                                    text = director,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = if (item.directorId != null && onPersonClick != null) {
                                        Modifier.clickable { onPersonClick(item.directorId!!) }
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
                                    text = "Downloading ${bulkProgress.currentEpisodeLabel} (${bulkProgress.completedEpisodes}/${bulkProgress.totalEpisodes})",
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
                                seasons = item.seasons,
                                selectedSeason = state.selectedSeason,
                                seasonDetail = state.seasonDetail,
                                isLoadingSeasonDetail = state.isLoadingSeasonDetail,
                                watchedEpisodes = state.watchedEpisodes,
                                seriesRatings = item.ratings.withFallbackTmdbScore(item.rating),
                                onSeasonSelected = { seasonNum ->
                                    item.tmdbId?.let { tvId ->
                                        viewModel.loadSeasonDetail(tvId, seasonNum)
                                    }
                                },
                                onEpisodePlay = { season, episode ->
                                    if (!hasAnyAddon) {
                                        onOpenAddonCatalog?.invoke()
                                    } else {
                                        openSourcePickerOrProvider(season, episode)
                                    }
                                },
                                onEpisodeDownload = { season, episode ->
                                    if (settingsState.debridConnected) {
                                        pendingEpisodeDownload = season to episode
                                        viewModel.fetchStreams(season = season, episode = episode)
                                    }
                                },
                                onDownloadSeason = { season ->
                                    if (settingsState.debridConnected) {
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
                                    if (settingsState.debridConnected) {
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

                        // ── Cast ──
                        if (item.cast.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = stringResource(R.string.detail_cast),
                                modifier = Modifier.padding(horizontal = 0.dp),
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
                                            onPersonClick?.invoke(castMember.id)
                                        },
                                    )
                                }
                            }
                        }

                        // ── Similar ──
                        if (state.similar.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionHeader(
                                title = stringResource(R.string.detail_more_like_this),
                                modifier = Modifier.padding(horizontal = 0.dp),
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

                        Spacer(Modifier.height(32.dp))
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
                            Text(text = message, style = MaterialTheme.typography.bodySmall)
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
                            Text(text = message, style = MaterialTheme.typography.bodySmall)
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
                        onDismiss = { viewModel.dismissStreamPicker() },
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
                                    onPlayClick(
                                        "file://${route.absolutePath}",
                                        "",
                                        sourcePickerSeason,
                                        sourcePickerEpisode,
                                        item.imdbId,
                                        true,
                                    )
                                }
                                is PlaybackRoute.LanDesktopStream -> {
                                    PendingLanPlaybackHandoff.stage(route)
                                    onPlayClick(
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
                        url = resolvedUrl,
                        title = dlTitle,
                        posterUrl = item?.posterUrl ?: "",
                        onPlayInApp = { onPlayClick(resolvedUrl, resolvedFallbackUrl, ctxSeason, ctxEpisode, item?.imdbId, resolvedUrlIsTemporary) },
                        onDownload = {
                            if (item != null) {
                                downloadViewModel.enqueueDownload(
                                    Download(
                                        id = "${item.tmdbId}_${ctxSeason ?: 0}_${ctxEpisode ?: 0}_${System.currentTimeMillis()}",
                                        mediaId = item.tmdbId.toString(),
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

            // Floating back button — always visible regardless of scroll position
            FloatingBackButton(
                onBack = onBack,
                label = stringResource(R.string.common_back_cd),
                compact = useCompactBackButton,
                modifier = Modifier
                    .zIndex(20f)
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 10.dp),
            )

            // Preparing-stream overlay: blocks the detail surface while a
            // Panda cloud-client stream is still warming up. Never open the
            // player until the probe says Ready; cancelling routes through
            // the VM to stop the retry loop cleanly.
            state.preparing?.let { preparing ->
                StreamPreparingOverlay(
                    state = preparing,
                    onCancel = { viewModel.cancelPreparing() },
                    modifier = Modifier.zIndex(80f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereToWatchSection(
    offers: List<AvailabilityOffer>,
    isLoading: Boolean,
    error: String?,
    onOpenOffer: (AvailabilityOffer) -> Unit,
) {
    SectionHeader(title = stringResource(R.string.detail_where_to_watch))
    Spacer(Modifier.height(8.dp))
    when {
        isLoading -> {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Amber)
        }
        error != null -> {
            Text(error, color = Torve.colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
        offers.isEmpty() -> {
            Text(stringResource(R.string.detail_no_offers), color = Torve.colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            val grouped = offers.groupBy { it.offerType }
            val order = listOf(
                AvailabilityOfferType.SUBSCRIPTION,
                AvailabilityOfferType.FREE,
                AvailabilityOfferType.ADS,
                AvailabilityOfferType.RENT,
                AvailabilityOfferType.BUY,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                order.forEach { type ->
                    val rows = grouped[type].orEmpty()
                    if (rows.isNotEmpty()) {
                        Text(
                            text = when (type) {
                                AvailabilityOfferType.SUBSCRIPTION -> "Subscription"
                                AvailabilityOfferType.RENT -> "Rent"
                                AvailabilityOfferType.BUY -> "Buy"
                                AvailabilityOfferType.FREE -> "Free"
                                AvailabilityOfferType.ADS -> "With Ads"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { offer ->
                                Row(
                                    modifier = Modifier
                                        .background(Graphite, RoundedCornerShape(16.dp))
                                        .clickable { onOpenOffer(offer) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        offer.providerName,
                                        color = Snow,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DetailSecondaryActionSpec(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun DetailSecondaryActionsGrid(
    actions: List<DetailSecondaryActionSpec>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val spacing = 10.dp
    BoxWithConstraints(modifier = modifier) {
        val columns = when {
            actions.size <= 1 -> 1
            maxWidth < 400.dp -> 2
            else -> minOf(3, actions.size)
        }
        val actionRows = actions.chunked(columns)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            actionRows.forEach { rowActions ->
                val singleWideRow = rowActions.size == 1 && columns > 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    rowActions.forEach { action ->
                        DetailSecondaryActionButton(
                            action = action,
                            modifier = if (singleWideRow) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.weight(1f)
                            },
                        )
                    }
                    if (!singleWideRow && rowActions.size < columns) {
                        repeat(columns - rowActions.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSecondaryActionButton(
    action: DetailSecondaryActionSpec,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = action.onClick,
        modifier = modifier.heightIn(min = 46.dp),
        enabled = action.enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = action.containerColor,
            contentColor = action.contentColor,
        ),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
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
