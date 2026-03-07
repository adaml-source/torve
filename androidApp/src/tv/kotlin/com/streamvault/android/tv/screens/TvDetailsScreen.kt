package com.streamvault.android.tv.screens

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
import androidx.compose.runtime.LaunchedEffect
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
import com.streamvault.android.R
import com.streamvault.android.ui.theme.*
import com.streamvault.android.player.DeviceCodecProbe
import com.streamvault.android.tv.NotificationType
import com.streamvault.android.tv.TvNotificationQueue
import com.streamvault.android.tv.components.TvEpisodePicker
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.Icon
import com.streamvault.android.download.DownloadWorker
import com.streamvault.data.download.BulkDownloadManager
import com.streamvault.domain.model.AvailabilityOfferType
import com.streamvault.domain.model.Download
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.presentation.detail.DetailViewModel
import com.streamvault.presentation.download.DownloadViewModel
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.subscription.SubscriptionViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import com.streamvault.domain.model.PremiumFeature
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
    ) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    onCastClick: (castId: Int, castName: String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
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
    val state by detailViewModel.state.collectAsState()
    val context = LocalContext.current

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
    // downloadToastMessage replaced by TvNotificationQueue

    BackHandler(onBack = onBack)

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
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 34.dp, bottom = 24.dp),
    ) {
        mediaItem?.let { item ->
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
                            val playText = when {
                                state.isLoadingStreams -> stringResource(R.string.tv_detail_finding_streams)
                                state.isResolving -> stringResource(R.string.tv_detail_resolving)
                                !settingsState.debridConnected -> stringResource(R.string.tv_detail_connect_cloud)
                                else -> stringResource(R.string.tv_action_play)
                            }
                            TvActionButton(
                                text = playText,
                                isPrimary = true,
                                modifier = Modifier
                                    .focusRequester(playFocusRequester)
                                    .focusProperties { left = railFocusRequester },
                                enabled = !isBusy,
                                onFocused = { onContentFocused(playFocusRequester) },
                                onClick = {
                                    if (!subscriptionViewModel.checkAccess(PremiumFeature.STREAM_PLAYBACK)) return@TvActionButton
                                    if (!settingsState.debridConnected) {
                                        onSettingsClick()
                                    } else if (item.type == MediaType.SERIES) {
                                        detailViewModel.playNextEpisode()
                                    } else {
                                        detailViewModel.fetchStreams()
                                    }
                                },
                            )

                            TvActionButton(
                                text = if (isInWatchlist) {
                                    stringResource(R.string.tv_action_remove_watchlist)
                                } else {
                                    stringResource(R.string.tv_action_add_watchlist)
                                },
                                modifier = Modifier.focusRequester(watchlistFocusRequester),
                                onFocused = { onContentFocused(watchlistFocusRequester) },
                                onClick = {
                                    if (isInWatchlist) {
                                        watchlistViewModel.toggleWatchlist(item)
                                    } else {
                                        val traktOn = settingsState.traktConnected
                                        val simklOn = settingsState.simklConnected
                                        if (!traktOn && !simklOn) {
                                            watchlistViewModel.toggleWatchlist(item)
                                        } else {
                                            showWatchlistPicker = true
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
                                        if (state.isMarkedWatched) {
                                            detailViewModel.markUnwatched()
                                        } else {
                                            detailViewModel.markWatched()
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
                                        val next = when (state.userRating) {
                                            null -> 7
                                            7 -> 8
                                            8 -> 9
                                            9 -> 10
                                            else -> null
                                        }
                                        detailViewModel.setUserRating(next)
                                    },
                                )
                            }

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

                            // Download buttons
                            if (item.type == MediaType.MOVIE) {
                                TvActionButton(
                                    text = stringResource(R.string.tv_action_download),
                                    modifier = Modifier.focusRequester(downloadMovieFocusRequester),
                                    enabled = !isBusy,
                                    onFocused = { onContentFocused(downloadMovieFocusRequester) },
                                    onClick = {
                                        if (!subscriptionViewModel.checkAccess(PremiumFeature.DOWNLOAD)) return@TvActionButton
                                        pendingDownloadAction = DownloadAction.Movie
                                        detailViewModel.fetchStreams(forceManualPick = true)
                                    },
                                )
                            }
                            if (item.type == MediaType.SERIES) {
                                TvActionButton(
                                    text = stringResource(R.string.tv_action_download_all),
                                    modifier = Modifier.focusRequester(downloadAllFocusRequester),
                                    enabled = !isBusy,
                                    onFocused = { onContentFocused(downloadAllFocusRequester) },
                                    onClick = {
                                        if (!subscriptionViewModel.checkAccess(PremiumFeature.DOWNLOAD)) return@TvActionButton
                                        val media = state.mediaItem ?: return@TvActionButton
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
                                            TvNotificationQueue.post("Queued ${ids.size} episodes for download", NotificationType.SUCCESS)
                                        }
                                    },
                                )
                            }
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
            if (item.ratings != null) {
                item(key = "ratings") {
                    val r = item.ratings!!
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        r.imdbScore?.let { score ->
                            TvRatingChip(label = "IMDb", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_imdb)
                        }
                        r.rottenTomatoesScore?.let { score ->
                            TvRatingChip(label = "RT", value = "${score}%", iconRes = R.drawable.ic_rating_rt)
                        }
                        r.tmdbScore?.let { score ->
                            TvRatingChip(label = "TMDB", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_tmdb)
                        }
                        r.metacriticScore?.let { score ->
                            TvRatingChip(label = "MC", value = "$score", iconRes = R.drawable.ic_rating_metacritic)
                        }
                        r.traktScore?.let { score ->
                            TvRatingChip(label = "Trakt", value = "%.0f".format(score), iconRes = R.drawable.ic_rating_trakt)
                        }
                        r.letterboxdScore?.let { score ->
                            TvRatingChip(label = "LB", value = "%.1f".format(score), iconRes = R.drawable.ic_rating_letterboxd)
                        }
                    }
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
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) {
                                                    val url = offer.deeplinkUrl ?: offer.webUrl
                                                    if (url != null) {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                                            )
                                                        }
                                                    }
                                                }
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
                        contentPadding = PaddingValues(end = 16.dp),
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
                        onSeasonDownload = { season ->
                            val media = state.mediaItem ?: return@TvEpisodePicker
                            val seasonObj = media.seasons.find { it.seasonNumber == season }
                            val epCount = seasonObj?.episodeCount ?: return@TvEpisodePicker
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
                                TvNotificationQueue.post("Queued ${ids.size} episodes for download", NotificationType.SUCCESS)
                            }
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
                        contentPadding = PaddingValues(end = 16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        items(state.similar.take(10), key = { it.id }) { similar ->
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
                                watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = false, syncSimkl = false)
                                showWatchlistPicker = false
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
                                    watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = true, syncSimkl = false)
                                    showWatchlistPicker = false
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
                                    watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = false, syncSimkl = true)
                                    showWatchlistPicker = false
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
                                    watchlistViewModel.addToWatchlist(mediaItem, syncTrakt = true, syncSimkl = true)
                                    showWatchlistPicker = false
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
                item(key = "picker_header") {
                    Text(
                        text = stringResource(R.string.tv_streams_pick),
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(
                    state.streams,
                    key = { index, _ -> "stream_$index" },
                ) { index, stream ->
                    val req = if (index == 0) firstStreamFocusRequester else remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    val bg = if (focused) Amber.copy(alpha = 0.2f) else Graphite
                    val streamBorderColor by animateColorAsState(targetValue = if (focused) Amber else Steel.copy(alpha = 0.3f), label = "streamBorder")
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
        } ?: item(key = "empty") {
            Text(
                text = state.error ?: stringResource(R.string.tv_no_data),
                style = MaterialTheme.typography.bodyLarge,
                color = Snow,
            )
        }
    }

    // Watchlist feedback toast
    watchlistState.snackbarMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            watchlistViewModel.clearSnackbar()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 72.dp)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = msg,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                modifier = Modifier
                    .background(Charcoal.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }

    // Download toast now rendered by TvNotificationQueue in TvRoot

    } // end Box
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
