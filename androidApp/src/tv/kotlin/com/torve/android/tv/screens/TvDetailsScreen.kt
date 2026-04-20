package com.torve.android.tv.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.ui.detail.DetailPlaybackReadinessCard
import com.torve.android.ui.detail.DetailRatingsAttribution
import com.torve.android.ui.detail.StreamExperienceBadges
import com.torve.android.ui.detail.StreamReadinessLabel
import com.torve.android.ui.detail.groupPlaybackOptionStreams
import com.torve.android.ui.detail.streamUiKey
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.theme.*
import com.torve.android.player.DeviceCodecProbe
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.components.TvEpisodePicker
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.Icon
import com.torve.android.download.DownloadWorker
import com.torve.data.download.BulkDownloadManager
import com.torve.domain.model.AvailabilityOfferType
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private sealed class DownloadAction {
    data object None : DownloadAction()
    data object Movie : DownloadAction()
    data class Episode(val s: Int, val e: Int) : DownloadAction()
}

@Composable
fun TvDetailsScreen(
    type: String,
    id: Int,
    autoPlay: Boolean,
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onPlayResolved: (
        url: String,
        fallbackUrl: String,
        mediaItem: MediaItem,
        season: Int?,
        episode: Int?,
        autoSourceSelection: Boolean,
    ) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    onCastClick: (castId: Int, castName: String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
) {
    val detailViewModel: DetailViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val downloadViewModel: DownloadViewModel = koinInject()
    val bulkDownloadManager: BulkDownloadManager = koinInject()
    val subscriptionViewModel: SubscriptionViewModel = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val state by detailViewModel.state.collectAsState()
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
    val context = LocalContext.current

    val isLockedFeature: (TvEntitledFeature) -> Boolean = { feature ->
        TvPremiumAccess.isPremiumLocked(feature, accessTier)
    }
    val runPremiumAction: (TvEntitledFeature, () -> Unit) -> Unit = { feature, action ->
        if (isLockedFeature(feature)) {
            onRequestLifetimeUnlock(feature)
        } else {
            action()
        }
    }

    val playFocusRequester = remember { FocusRequester() }
    val watchlistFocusRequester = remember { FocusRequester() }
    val markWatchedFocusRequester = remember { FocusRequester() }
    val rateFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }
    val downloadMovieFocusRequester = remember { FocusRequester() }
    val downloadAllFocusRequester = remember { FocusRequester() }
    val firstStreamFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var didAutoPlay by rememberSaveable(type, id) { mutableStateOf(false) }
    var pendingDownloadAction by remember { mutableStateOf<DownloadAction>(DownloadAction.None) }
    var showWatchlistPicker by remember { mutableStateOf(false) }
    val watchlistModalRestoreController = rememberTvModalFocusRestoreController(
        key = "details_watchlist_${type}_$id",
    )
    val watchlistTarget = remember(type, id) {
        TvFocusTargetId(
            screenId = "details:$type:$id",
            rowKey = "hero_actions",
            itemKey = "watchlist_button",
            rowIndex = 0,
            itemIndex = 1,
            targetType = "button",
        )
    }
    val registeredWatchlistFocusRequester = rememberRegisteredTvFocusRequester(
        controller = watchlistModalRestoreController,
        target = watchlistTarget,
        externalRequester = watchlistFocusRequester,
    )
    // downloadToastMessage replaced by TvNotificationQueue

    BackHandler(onBack = onBack)
    BackHandler(enabled = showWatchlistPicker) {
        showWatchlistPicker = false
        watchlistModalRestoreController.requestRestore()
    }

    // Wire download callbacks to trigger WorkManager
    LaunchedEffect(downloadViewModel) {
        downloadViewModel.onDownloadEnqueued = { downloadId ->
            DownloadWorker.enqueue(context, downloadId)
        }
    }

    LaunchedEffect(Unit) {
        detailViewModel.setSettingsProvider { settingsViewModel }
        detailViewModel.setDeviceCodecCaps(DeviceCodecProbe.probe())
        onFirstContentRequester(playFocusRequester)
    }

    LaunchedEffect(type, id) {
        detailViewModel.loadDetail(type, id)
    }

    // Refresh watch state when returning from player (lifecycle ON_RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var resumeCount = 0
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (resumeCount++ > 0) {
                    detailViewModel.refreshWatchState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.mediaItem?.id) {
        if (state.mediaItem != null) {
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    // Surface stream errors as visible notifications
    LaunchedEffect(state.streamsError) {
        state.streamsError?.let { error ->
            TvNotificationQueue.post(error, NotificationType.ERROR)
        }
    }

    LaunchedEffect(watchlistState.snackbarMessage) {
        watchlistState.snackbarMessage?.let { message ->
            TvNotificationQueue.post(message, NotificationType.SUCCESS)
            watchlistViewModel.clearSnackbar()
        }
    }

    // Auto-load first season detail for series
    LaunchedEffect(state.mediaItem?.id, state.mediaItem?.seasons) {
        val media = state.mediaItem ?: return@LaunchedEffect
        if (media.type == MediaType.SERIES && media.seasons.isNotEmpty()) {
            val firstSeason = media.seasons.filter { it.seasonNumber > 0 }.minByOrNull { it.seasonNumber }
            if (firstSeason != null && state.seasonDetail == null) {
                val tvId = media.tmdbId ?: return@LaunchedEffect
                detailViewModel.loadSeasonDetail(tvId, firstSeason.seasonNumber)
            }
        }
    }

    LaunchedEffect(state.mediaItem?.id, autoPlay) {
        val media = state.mediaItem ?: return@LaunchedEffect
        if (autoPlay && !didAutoPlay) {
            didAutoPlay = true
            if (media.type == MediaType.SERIES) {
                detailViewModel.playNextEpisode()
            } else {
                detailViewModel.fetchStreams()
            }
        }
    }

    // Scroll to stream picker and focus the first stream when picker appears
    LaunchedEffect(state.showStreamPicker, state.streams.size) {
        if (state.showStreamPicker && state.streams.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            val pickerIndex = listState.layoutInfo.totalItemsCount - state.streams.size - 1
            if (pickerIndex >= 0) {
                listState.animateScrollToItem(pickerIndex.coerceAtLeast(0))
            }
            kotlinx.coroutines.delay(50)
            runCatching { firstStreamFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(showWatchlistPicker, watchlistModalRestoreController.pendingRestore?.restoreToken) {
        if (showWatchlistPicker) return@LaunchedEffect
        watchlistModalRestoreController.restorePendingFocus(
            screenId = "details:$type:$id",
            outerListState = listState,
        )
    }

    LaunchedEffect(state.resolvedStream, state.mediaItem, pendingDownloadAction) {
        val resolved = state.resolvedStream ?: return@LaunchedEffect
        val media = state.mediaItem ?: return@LaunchedEffect
        // Don't navigate to player if this resolve was for a download action
        if (pendingDownloadAction !is DownloadAction.None) return@LaunchedEffect

        val playbackUrl = resolved.transcodeUrls?.mp4
            ?: resolved.transcodeUrls?.hls
            ?: resolved.url

        val fallbackUrl = when (playbackUrl) {
            resolved.url -> resolved.transcodeUrls?.hls ?: resolved.transcodeUrls?.mp4 ?: ""
            resolved.transcodeUrls?.mp4 -> resolved.transcodeUrls?.hls ?: resolved.url
            else -> resolved.url
        }

        onPlayResolved(
            playbackUrl,
            fallbackUrl,
            media,
            state.streamContextSeason,
            state.streamContextEpisode,
            state.autoPlayStream != null,
        )
        detailViewModel.clearResolvedStream()
    }

    // Download resolution: when stream resolves and there's a pending download action, enqueue single download
    LaunchedEffect(state.resolvedStream, pendingDownloadAction) {
        val resolved = state.resolvedStream ?: return@LaunchedEffect
        val media = state.mediaItem ?: return@LaunchedEffect
        val action = pendingDownloadAction
        if (action is DownloadAction.None) return@LaunchedEffect

        val downloadUrl = resolved.transcodeUrls?.mp4
            ?: resolved.transcodeUrls?.hls
            ?: resolved.url

        // Single movie or episode download
        val download = Download(
            id = "${media.id}_${System.currentTimeMillis()}",
            mediaId = media.id,
            mediaType = media.type,
            title = media.title,
            posterUrl = media.posterUrl,
            streamUrl = downloadUrl,
            status = DownloadStatus.PENDING,
            seasonNumber = state.streamContextSeason,
            episodeNumber = state.streamContextEpisode,
        )
        downloadViewModel.enqueueDownload(download)
        TvNotificationQueue.post(context.getString(R.string.tv_download_queued), NotificationType.SUCCESS)

        pendingDownloadAction = DownloadAction.None
        detailViewModel.clearResolvedStream()
    }

    // Download toast now handled by TvNotificationQueue (auto-dismiss in TvRoot)

    val mediaItem = state.mediaItem
    val isInWatchlist = mediaItem?.let { watchlistViewModel.isInWatchlist(it.id) } == true
    val isBusy = state.isLoadingStreams || state.isResolving

    if (state.isLoading && mediaItem == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().focusGroup()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
    ) {
        if (mediaItem != null) {
            val item = mediaItem
            // ── Hero backdrop + title + buttons ──
            item(key = "hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(330.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    AsyncImage(
                        model = item.backdropUrl ?: item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Obsidian.copy(alpha = 0.91f),
                                        Obsidian.copy(alpha = 0.85f),
                                        Obsidian.copy(alpha = 0.48f),
                                        Obsidian.copy(alpha = 0.19f),
                                    ),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val metadata = buildString {
                            item.year?.let { append(it) }
                            if (item.rating != null) {
                                if (isNotBlank()) append("  ")
                                append(String.format("%.1f", item.rating))
                            }
                            item.runtime?.let {
                                if (isNotBlank()) append("  ")
                                append("${it}m")
                            }
                        }
                        if (metadata.isNotBlank()) {
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.titleMedium,
                                color = Silver,
                            )
                        }

                        // ── Library status indicator ──
                        if (state.isInLibrary) {
                            Text(
                                text = stringResource(R.string.tv_detail_in_library),
                                style = MaterialTheme.typography.labelMedium,
                                color = Amber,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .background(Amber.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // ── Play button with debrid check + loading phases ──
                            val streamLocked = isLockedFeature(TvEntitledFeature.STREAM_PLAYBACK)
                            val playText = when {
                                streamLocked -> TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL
                                state.isLoadingStreams -> stringResource(R.string.tv_detail_finding_streams)
                                state.isResolving -> stringResource(R.string.tv_detail_resolving)
                                !settingsState.debridConnected -> stringResource(R.string.tv_detail_connect_cloud)
                                else -> state.primaryPlayLabel
                            }
                            TvActionButton(
                                text = playText,
                                isPrimary = true,
                                modifier = Modifier
                                    .focusRequester(playFocusRequester)
                                    .focusProperties { left = railFocusRequester },
                                enabled = !isBusy,
                                onFocused = {
                                    onContentFocused(playFocusRequester)
                                    detailViewModel.warmupLikelyPlaybackTarget(
                                        ContentWarmupTrigger.TV_PLAY_ACTION_FOCUS,
                                    )
                                },
                                onClick = {
                                    if (!settingsState.debridConnected) {
                                        runPremiumAction(TvEntitledFeature.CLOUD_PROVIDER_SETUP) {
                                            context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
                                                .edit()
                                                .putBoolean("tv_settings_open_connections_once", true)
                                                .apply()
                                            onSettingsClick()
                                        }
                                        return@TvActionButton
                                    }

                                    runPremiumAction(TvEntitledFeature.STREAM_PLAYBACK) {
                                        if (item.type == MediaType.SERIES) {
                                            detailViewModel.playNextEpisode()
                                        } else {
                                            detailViewModel.fetchStreams()
                                        }
                                    }
                                },
                            )

                            if (!item.trailerKey.isNullOrBlank()) {
                                TvActionButton(
                                    text = stringResource(R.string.tv_action_trailer),
                                    modifier = Modifier.focusRequester(trailerFocusRequester),
                                    onFocused = { onContentFocused(trailerFocusRequester) },
                                    onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://www.youtube.com/watch?v=${item.trailerKey}"),
                                        )
                                        runCatching { context.startActivity(intent) }
                                    },
                                )
                            }

                            val watchlistLocked = isLockedFeature(TvEntitledFeature.WATCHLIST_EDIT)
                            TvActionButton(
                                text = if (isInWatchlist) {
                                    if (watchlistLocked) {
                                        "${stringResource(R.string.tv_action_remove_watchlist)} (Locked)"
                                    } else {
                                        stringResource(R.string.tv_action_remove_watchlist)
                                    }
                                } else {
                                    if (watchlistLocked) {
                                        "${stringResource(R.string.tv_action_add_watchlist)} (Locked)"
                                    } else {
                                        stringResource(R.string.tv_action_add_watchlist)
                                    }
                                },
                                modifier = Modifier.focusRequester(registeredWatchlistFocusRequester),
                                onFocused = {
                                    watchlistModalRestoreController.markFocused(watchlistTarget)
                                    onContentFocused(registeredWatchlistFocusRequester)
                                },
                                onClick = {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        if (isInWatchlist) {
                                            watchlistViewModel.toggleWatchlist(item)
                                        } else {
                                            val traktOn = settingsState.traktConnected
                                            val simklOn = settingsState.simklConnected
                                            if (!traktOn && !simklOn) {
                                                watchlistViewModel.toggleWatchlist(item)
                                            } else {
                                                watchlistModalRestoreController.captureOrigin(
                                                    target = watchlistTarget,
                                                    outerListState = listState,
                                                )
                                                showWatchlistPicker = true
                                            }
                                        }
                                    }
                                },
                            )

                            // ── Mark Watched + Rate (Trakt only) ──
                            if (settingsState.traktConnected) {
                                TvActionButton(
                                    text = if (state.isMarkedWatched) {
                                        stringResource(R.string.tv_detail_unwatched)
                                    } else {
                                        stringResource(R.string.tv_detail_watched)
                                    },
                                    modifier = Modifier.focusRequester(markWatchedFocusRequester),
                                    onFocused = { onContentFocused(markWatchedFocusRequester) },
                                    onClick = {
                                        runPremiumAction(TvEntitledFeature.WATCHED_STATUS_EDIT) {
                                            if (state.isMarkedWatched) {
                                                detailViewModel.markUnwatched()
                                            } else {
                                                detailViewModel.markWatched()
                                            }
                                        }
                                    },
                                )

                                val rateText = state.userRating?.let { rating ->
                                    stringResource(R.string.tv_detail_rated, rating)
                                } ?: stringResource(R.string.tv_detail_rate)
                                TvActionButton(
                                    text = rateText,
                                    modifier = Modifier.focusRequester(rateFocusRequester),
                                    onFocused = { onContentFocused(rateFocusRequester) },
                                    onClick = {
                                        runPremiumAction(TvEntitledFeature.TRAKT_CONNECT) {
                                            val next = when (state.userRating) {
                                                null -> 7
                                                7 -> 8
                                                8 -> 9
                                                9 -> 10
                                                else -> null
                                            }
                                            detailViewModel.setUserRating(next)
                                        }
                                    },
                                )
                            }

                            // Download buttons removed — TV is stream-only
                        }
                    }
                }
            }

            // ── Tagline ──
            if (!item.tagline.isNullOrBlank()) {
                item(key = "tagline") {
                    Text(
                        text = "\"${item.tagline}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Amber.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
                    )
                }
            }

            // ── Genre pills ──
            if (item.genres.isNotEmpty()) {
                item(key = "genres") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = if (item.tagline.isNullOrBlank()) 20.dp else 0.dp, bottom = 12.dp),
                    ) {
                        item.genres.take(5).forEach { genre ->
                            Text(
                                text = genre.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = Snow,
                                modifier = Modifier
                                    .background(Gunmetal, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // ── Multi-source ratings ──
            val ratingPrefs = settingsState.ratingPrefs
            if (item.ratings != null && ratingPrefs.showRatingsOnDetailPage) {
                item(key = "ratings") {
                    val r = item.ratings!!
                    val enabled = ratingPrefs.enabledProviders.toSet()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        for (source in ratingPrefs.providerOrder) {
                            if (source !in enabled) continue
                            when (source) {
                                com.torve.domain.model.RatingSource.IMDB -> r.imdbScore?.let { score ->
                                    TvRatingChip(label = "IMDb", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_imdb)
                                }
                                com.torve.domain.model.RatingSource.ROTTEN_TOMATOES -> r.rottenTomatoesScore?.let { score ->
                                    val rtIcon = when {
                                        score >= 75 -> R.drawable.ic_rt_certified_fresh
                                        score >= 60 -> R.drawable.ic_rt_fresh
                                        else -> R.drawable.ic_rt_rotten
                                    }
                                    TvRatingChip(label = "RT", value = "${score}%", iconRes = rtIcon)
                                }
                                com.torve.domain.model.RatingSource.TMDB -> r.tmdbScore?.let { score ->
                                    TvRatingChip(label = "TMDB", value = "%.1f".format(score), iconRes = R.drawable.tmbd_logo)
                                }
                                com.torve.domain.model.RatingSource.METACRITIC -> r.metacriticScore?.let { score ->
                                    TvRatingChip(label = "MC", value = "$score", iconRes = R.drawable.ic_rating_metacritic)
                                }
                                com.torve.domain.model.RatingSource.TRAKT -> r.traktScore?.let { score ->
                                    TvRatingChip(label = "Trakt", value = "%.0f".format(score), iconRes = R.drawable.ic_rating_trakt)
                                }
                                com.torve.domain.model.RatingSource.LETTERBOXD -> r.letterboxdScore?.let { score ->
                                    TvRatingChip(label = "LB", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_letterboxd)
                                }
                                com.torve.domain.model.RatingSource.RT_AUDIENCE -> r.rtAudienceScore?.let { score ->
                                    TvRatingChip(label = "RT Aud", value = "${score}%", iconRes = R.drawable.ic_rt_fresh)
                                }
                                com.torve.domain.model.RatingSource.MDBLIST -> r.mdblistScore?.let { score ->
                                    TvRatingChip(label = "MDB", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_mdblist)
                                }
                                com.torve.domain.model.RatingSource.MAL -> r.malScore?.let { score ->
                                    TvRatingChip(label = "MAL", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_mal)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                item(key = "ratings_attribution") {
                    DetailRatingsAttribution(
                        ratings = item.ratings!!,
                        prefs = ratingPrefs,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }

            // ── Overview ──
            if (!item.overview.isNullOrBlank()) {
                item(key = "overview") {
                    Text(
                        text = item.overview.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Silver,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            }

            // ── Where to Watch ──
            item(key = "where_to_watch") {
                val offers = state.availability?.offers.orEmpty()
                val grouped = offers.groupBy { it.offerType }
                if (state.isLoadingAvailability) {
                    Row(
                        modifier = Modifier.padding(bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Amber,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.tv_detail_where_to_watch),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                        )
                    }
                } else if (offers.isNotEmpty()) {
                    Column(modifier = Modifier.padding(bottom = 14.dp)) {
                        Text(
                            text = stringResource(R.string.tv_detail_where_to_watch),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        val typeOrder = listOf(
                            AvailabilityOfferType.SUBSCRIPTION,
                            AvailabilityOfferType.FREE,
                            AvailabilityOfferType.ADS,
                            AvailabilityOfferType.RENT,
                            AvailabilityOfferType.BUY,
                        )
                        typeOrder.forEach { offerType ->
                            val typeOffers = grouped[offerType] ?: return@forEach
                            val label = when (offerType) {
                                AvailabilityOfferType.SUBSCRIPTION -> "Stream"
                                AvailabilityOfferType.FREE -> "Free"
                                AvailabilityOfferType.ADS -> "Ads"
                                AvailabilityOfferType.RENT -> "Rent"
                                AvailabilityOfferType.BUY -> "Buy"
                            }
                            Row(
                                modifier = Modifier.padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Silver,
                                    modifier = Modifier.width(52.dp),
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(typeOffers, key = { "${it.providerName}_${it.offerType}" }) { offer ->
                                        val chipReq = remember { FocusRequester() }
                                        var chipFocused by remember { mutableStateOf(false) }
                                        val chipBorderColor by animateColorAsState(targetValue = if (chipFocused) Amber else Steel.copy(alpha = 0.3f), label = "offerChipBorder")
                                        Box(
                                            modifier = Modifier
                                                .border(2.dp, chipBorderColor, RoundedCornerShape(8.dp))
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (chipFocused) Amber.copy(alpha = 0.2f) else Gunmetal)
                                                .focusRequester(chipReq)
                                                .onFocusChanged {
                                                    chipFocused = it.isFocused
                                                    if (it.isFocused) onContentFocused(chipReq)
                                                }
                                                // Info-only — no external links
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                if (!offer.logoUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = offer.logoUrl,
                                                        contentDescription = offer.providerName,
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clip(RoundedCornerShape(4.dp)),
                                                        contentScale = ContentScale.Fit,
                                                    )
                                                }
                                                Text(
                                                    text = offer.providerName,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (chipFocused) Amber else Snow,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (state.availabilityError != null) {
                    Text(
                        text = state.availabilityError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                } else if (!state.isLoading && state.availability != null) {
                    Text(
                        text = stringResource(R.string.tv_detail_no_offers),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }

            // ── Director ──
            if (!item.director.isNullOrBlank()) {
                item(key = "director") {
                    Row(modifier = Modifier.padding(bottom = 10.dp)) {
                        Text(
                            text = stringResource(R.string.tv_detail_director) + "  ",
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                        )
                        Text(
                            text = item.director.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Snow,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ── Cast ──
            if (item.cast.isNotEmpty()) {
                item(key = "cast") {
                    Text(
                        text = stringResource(R.string.tv_detail_cast),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ash,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        items(item.cast.take(12), key = { it.id }) { member ->
                            TvCastCard(
                                name = member.name,
                                character = member.character,
                                profileUrl = member.profileUrl,
                                onClick = { onCastClick(member.id, member.name) },
                            )
                        }
                    }
                }
            }

            // ── Episode picker for series ──
            if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                item(key = "episodes") {
                    Spacer(modifier = Modifier.height(4.dp))
                    TvEpisodePicker(
                        seasons = item.seasons,
                        selectedSeason = state.selectedSeason,
                        seasonDetail = state.seasonDetail,
                        isLoadingSeasonDetail = state.isLoadingSeasonDetail,
                        watchedEpisodes = state.watchedEpisodes,
                        watchProgress = state.watchProgress,
                        railFocusRequester = railFocusRequester,
                        onSeasonSelected = { seasonNumber ->
                            val tvId = item.tmdbId ?: return@TvEpisodePicker
                            detailViewModel.loadSeasonDetail(tvId, seasonNumber)
                        },
                        onEpisodeSelected = { season, episode ->
                            detailViewModel.fetchStreams(season = season, episode = episode)
                        },
                        onSeasonDownload = { },
                        onToggleEpisodeWatched = { season, episode ->
                            detailViewModel.toggleEpisodeWatched(season, episode)
                        },
                        onFirstContentRequester = onFirstContentRequester,
                        onContentFocused = onContentFocused,
                    )
                }
            }

            // ── Similar titles ──
            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    Text(
                        text = stringResource(R.string.tv_detail_more_like_this),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ash,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        itemsIndexed(state.similar.take(10), key = { index, item -> mediaItemLazyKey(item, index) }) { _, similar ->
                            TvSimilarCard(
                                item = similar,
                                onClick = { onMediaClick(similar) },
                            )
                        }
                    }
                }
            }

            // ── Watchlist service picker ──
            if (showWatchlistPicker && mediaItem != null) {
                item(key = "watchlist_picker_header") {
                    Text(
                        text = stringResource(R.string.tv_watchlist_add_to),
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                    )
                }
                // Local only
                item(key = "wl_local") {
                    val req = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    val wlLocalBorder by animateColorAsState(targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f), label = "wlLocalBorder")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .border(2.dp, wlLocalBorder, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Amber.copy(alpha = 0.2f) else Graphite)
                            .focusRequester(req)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onContentFocused(req) }
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = false, syncSimkl = false)
                                        showWatchlistPicker = false
                                        watchlistModalRestoreController.requestRestore()
                                    }
                                }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.tv_watchlist_local_only), color = Snow, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                // Trakt
                if (settingsState.traktConnected) {
                    item(key = "wl_trakt") {
                        val req = remember { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        val wlTraktBorder by animateColorAsState(targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f), label = "wlTraktBorder")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .border(2.dp, wlTraktBorder, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Amber.copy(alpha = 0.2f) else Graphite)
                                .focusRequester(req)
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onContentFocused(req) }
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = true, syncSimkl = false)
                                        showWatchlistPicker = false
                                        watchlistModalRestoreController.requestRestore()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Trakt", color = Snow, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                // Simkl
                if (settingsState.simklConnected) {
                    item(key = "wl_simkl") {
                        val req = remember { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        val wlSimklBorder by animateColorAsState(targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f), label = "wlSimklBorder")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .border(2.dp, wlSimklBorder, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Amber.copy(alpha = 0.2f) else Graphite)
                                .focusRequester(req)
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onContentFocused(req) }
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = false, syncSimkl = true)
                                        showWatchlistPicker = false
                                        watchlistModalRestoreController.requestRestore()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Simkl", color = Snow, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                // Both (if both connected)
                if (settingsState.traktConnected && settingsState.simklConnected) {
                    item(key = "wl_both") {
                        val req = remember { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        val wlBothBorder by animateColorAsState(targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f), label = "wlBothBorder")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .border(2.dp, wlBothBorder, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (focused) Amber.copy(alpha = 0.2f) else Graphite)
                                .focusRequester(req)
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onContentFocused(req) }
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = true, syncSimkl = true)
                                        showWatchlistPicker = false
                                        watchlistModalRestoreController.requestRestore()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Trakt + Simkl", color = Snow, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            // ── Auto-play status ──
            if (state.autoPlayMessage != null) {
                item(key = "autoplay_status") {
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        Text(
                            text = state.autoPlayMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                        )
                        if (state.autoPlayFailed) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val selectManuallyReq = remember { FocusRequester() }
                            TvActionButton(
                                text = stringResource(R.string.tv_detail_select_manually),
                                modifier = Modifier.focusRequester(selectManuallyReq),
                                onFocused = { onContentFocused(selectManuallyReq) },
                                onClick = { detailViewModel.showManualPicker() },
                            )
                        }
                    }
                }
            }

            // ── Resolve error ──
            if (state.isLoadingStreams || state.isResolving || state.streams.isNotEmpty()) {
                item(key = "playback_readiness") {
                    DetailPlaybackReadinessCard(
                        streams = state.streams,
                        startupCandidates = state.startupCandidates,
                        startupStatus = state.playbackStartupStatus,
                        isLoadingStreams = state.isLoadingStreams,
                        isResolving = state.isResolving,
                        isLoadingMoreSources = state.isLoadingMoreSources,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            if (state.resolveError != null) {
                item(key = "resolve_error") {
                    Text(
                        text = state.resolveError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            // ── Stream Picker (when auto-play is off or auto-play failed) ──
            if (state.showStreamPicker && state.streams.isNotEmpty()) {
                val groups = groupPlaybackOptionStreams(state.streams, state.startupCandidates)
                val startupCandidateMap = state.startupCandidates.associateBy { it.streamKey }
                val firstStreamKey = groups.firstOrNull()?.items?.firstOrNull()?.streamUiKey()
                item(key = "picker_header") {
                    Text(
                        text = stringResource(R.string.tv_streams_pick),
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                    )
                }
                groups.forEach { group ->
                    item(key = "picker_group_${group.title}") {
                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = Amber,
                                fontWeight = FontWeight.SemiBold,
                            )
                            group.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Silver,
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        group.items,
                        key = { index, stream -> "stream_${group.title}_${index}_${stream.streamUiKey()}" },
                    ) { _, stream ->
                    val startupCandidate = startupCandidateMap[stream.streamUiKey()]
                    val req = if (stream.streamUiKey() == firstStreamKey) {
                        firstStreamFocusRequester
                    } else {
                        remember { FocusRequester() }
                    }
                    var focused by remember { mutableStateOf(false) }
                    val bg = if (focused) Amber.copy(alpha = 0.2f) else Graphite
                    val streamBorderColor by animateColorAsState(
                        targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f),
                        label = "streamBorder",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .border(2.dp, streamBorderColor, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .focusRequester(req)
                            .onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused) onContentFocused(req)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                detailViewModel.resolveStream(
                                    stream = stream,
                                    provider = settingsViewModel.getDebridProvider(),
                                    apiKey = settingsViewModel.getDebridApiKey(),
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Score circle
                            val score = stream.score
                            val scoreColor = when {
                                score >= 80 -> Emerald
                                score >= 60 -> Amber
                                else -> Gunmetal
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(scoreColor, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$score",
                                    fontSize = 11.sp,
                                    color = if (score >= 60) Obsidian else Snow,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            // Center column: quality details
                            Column(modifier = Modifier.weight(1f)) {
                                StreamReadinessLabel(
                                    stream = stream,
                                    startupCandidate = startupCandidate,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                                Text(
                                    text = buildString {
                                        append(stream.quality)
                                        stream.codec?.let { append(" · $it") }
                                        stream.hdr?.let { append(" · $it") }
                                        stream.size?.let { append(" · $it") }
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Snow,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (stream.title.isNotBlank()) {
                                    Text(
                                        text = stream.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Silver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append(stream.addonName)
                                        stream.source?.let { append(" · $it") }
                                        stream.seeds?.let { if (it > 0) append(" · $it seeds") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Amber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                StreamExperienceBadges(
                                    stream = stream,
                                    startupCandidate = startupCandidate,
                                )
                            }

                            // Cached indicator
                            if (stream.isCached) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDone,
                                    contentDescription = "Cached",
                                    tint = Emerald,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Error ──
            if (state.streamsError != null) {
                item(key = "error") {
                    Text(
                        text = state.streamsError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        } else {
            item(key = "empty") {
                Text(
                    text = com.torve.android.error.resolveErrorKey(androidx.compose.ui.platform.LocalContext.current, state.error) ?: stringResource(R.string.tv_no_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Snow,
                )
            }
        }
    }

    // Download toast now rendered by TvNotificationQueue in TvRoot

    } // end Box
}
}

@Composable
private fun TvSimilarCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "similarScale")
    val borderColor by animateColorAsState(targetValue = if (focused) AmberLight else Color.Transparent, label = "similarBorder")

    Column(
        modifier = Modifier
            .width(130.dp)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Charcoal)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelSmall,
            color = Snow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TvActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "actionScale")
    val borderColor by animateColorAsState(targetValue = if (focused) Amber else Color.Transparent, label = "actionBorder")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled && isPrimary -> Amber.copy(alpha = 0.5f)
                    isPrimary && focused -> AmberDark
                    isPrimary -> Amber
                    focused -> Graphite
                    else -> Gunmetal.copy(alpha = 0.5f)
                },
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // Always focusable — guard click internally so focus is never ejected
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (enabled) onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                isPrimary && !enabled -> Obsidian.copy(alpha = 0.5f)
                isPrimary -> Obsidian
                !enabled -> Snow.copy(alpha = 0.5f)
                else -> Snow
            },
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TvCastCard(
    name: String,
    character: String?,
    profileUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.08f else 1f, label = "castScale")
    val borderColor by animateColorAsState(targetValue = if (focused) Amber else Color.Transparent, label = "castBorder")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .border(2.dp, borderColor, RoundedCornerShape(30.dp))
                .clip(RoundedCornerShape(30.dp)),
        ) {
            if (profileUrl != null) {
                AsyncImage(
                    model = profileUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gunmetal),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Silver,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Amber else Snow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        character?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvRatingChip(label: String, value: String, iconRes: Int? = null) {
    Row(
        modifier = Modifier
            .background(Gunmetal, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (iconRes != null) {
            AsyncImage(
                model = iconRes,
                contentDescription = label,
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
