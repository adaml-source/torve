package com.torve.android.tv.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.torve.android.ui.components.getRatingValue
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.components.ratingSourceIconRes
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
import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingSource
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.domain.model.deriveProvidersToRender
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.android.tv.components.TvSourcePickerSheet
import com.torve.data.addon.isTorrentOrDebridStream
import com.torve.data.addon.isUsenetStream
import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.platform.NetworkMonitor
import com.torve.platform.NetworkType
import com.torve.presentation.detail.DetailViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.lanlibrary.LanLibraryConsumer
import com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.tvhome.DetailSourcePickerLookup
import com.torve.presentation.tvhome.TvDetailsSourcePickerStateBuilder
import com.torve.presentation.tvhome.TvSourcePicker
import com.torve.presentation.tvhome.TvSourcePickerOption
import com.torve.presentation.tvhome.TvSourcePickerState
import com.torve.presentation.watchlist.WatchlistMutationState
import com.torve.presentation.watchlist.WatchlistViewModel
import com.torve.presentation.watchlist.isMutatingMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class TvStreamSourceFilter(val label: String) {
    ALL("All"), TORRENT("Torrent/RD"), USENET("Usenet")
}

private const val TV_USENET_PREPARING_NOTIFICATION_TAG = "tv_usenet_preparing"
private const val TV_STREAM_RESOLVING_NOTIFICATION_TAG = "tv_stream_resolving"

private sealed class DownloadAction {
    data object None : DownloadAction()
    data object Movie : DownloadAction()
    data class Episode(val s: Int, val e: Int) : DownloadAction()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvDetailsScreen(
    type: String,
    id: Int,
    autoPlay: Boolean,
    focusEpisodes: Boolean = false,
    railFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onPlayResolved: (
        url: String,
        fallbackUrl: String,
        mediaItem: MediaItem,
        season: Int?,
        episode: Int?,
        autoSourceSelection: Boolean,
        episodeName: String,
    ) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    onCastClick: (castId: Int, castName: String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onLibraryAutomationClick: () -> Unit = {},
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    onWatchedStatusChanged: (MediaItem, Boolean) -> Unit = { _, _ -> },
) {
    val detailViewModel: DetailViewModel = koinInject()
    val metadataRepository: MetadataRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val downloadViewModel: DownloadViewModel = koinInject()
    val bulkDownloadManager: BulkDownloadManager = koinInject()
    val subscriptionViewModel: SubscriptionViewModel = koinInject()
    val downloadRepository: DownloadRepository = koinInject()
    val mediaFavoritesRepository: MediaFavoritesRepository = koinInject()
    val lanLibraryConsumer: LanLibraryConsumer = koinInject()
    val jellyfinLibrary: JellyfinLibraryOverlayService = koinInject()
    val networkMonitor: NetworkMonitor = koinInject()
    // Kick the LAN-library consumer once so the badge resolves on
    // first paint of the TV detail screen.
    com.torve.android.ui.components.LanAvailabilityBootstrap()
    val coroutineScope = rememberCoroutineScope()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val mediaFavoritesState by mediaFavoritesRepository.state.collectAsState()
    val state by detailViewModel.state.collectAsState()
    var cinematicLogoUrl by remember(type, id) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mediaItem?.id, state.mediaItem?.logoUrl) {
        val item = state.mediaItem ?: return@LaunchedEffect
        val logo = item.logoUrl ?: item.tmdbId?.let { tmdbId ->
            runCatching { metadataRepository.getLogoUrl(type, tmdbId) }.getOrNull()
        }
        cinematicLogoUrl = logo
        com.torve.android.ui.components.ContentLaunchArtworkStore.clear()
    }
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
    val favoritesFocusRequester = remember { FocusRequester() }
    val markWatchedFocusRequester = remember { FocusRequester() }
    val rateFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }
    val permanentLibraryFocusRequester = remember { FocusRequester() }
    val removeLibraryRequestFocusRequester = remember { FocusRequester() }
    val downloadMovieFocusRequester = remember { FocusRequester() }
    val downloadAllFocusRequester = remember { FocusRequester() }
    val firstStreamFocusRequester = remember { FocusRequester() }
    var streamPickerFocusInitialized by remember(type, id) { mutableStateOf(false) }
    var restoreFocusAfterPreparingCancel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var episodesFocusHandled by rememberSaveable(type, id, focusEpisodes) { mutableStateOf(false) }
    var tvStreamFilter by remember { mutableStateOf(TvStreamSourceFilter.ALL) }
    val filteredStreams = remember(state.streams, tvStreamFilter) {
        when (tvStreamFilter) {
            TvStreamSourceFilter.ALL -> state.streams
            TvStreamSourceFilter.TORRENT -> state.streams.filter { it.isTorrentOrDebridStream() }
            TvStreamSourceFilter.USENET -> state.streams.filter { it.isUsenetStream() }
        }
    }
    var didAutoPlay by rememberSaveable(type, id) { mutableStateOf(false) }
    var pendingDownloadAction by remember { mutableStateOf<DownloadAction>(DownloadAction.None) }
    var resolvingEpisodeTarget by remember(type, id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var jellyfinAvailableEpisodes by remember(type, id) {
        mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
    }
    var showWatchlistPicker by remember { mutableStateOf(false) }
    // Source picker (Prompt 11C). Non-null while the sheet is open;
    // selection routes either directly to the player (LocalFile/LAN)
    // or falls back to fetchStreams() (Provider). Cleared on dismiss.
    var sourcePickerState by remember { mutableStateOf<TvSourcePickerState?>(null) }
    // Keep the title that produced the picker. Detail metadata can refresh while
    // the modal is open; source selection must not depend on transient UI state.
    var sourcePickerMediaItem by remember(type, id) { mutableStateOf<MediaItem?>(null) }
    var sourcePickerSeason by remember { mutableStateOf<Int?>(null) }
    var sourcePickerEpisode by remember { mutableStateOf<Int?>(null) }
    var sourcePickerOriginEpisode by remember(type, id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingEpisodeFocusRestore by remember(type, id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var initialPlayFocusAssigned by rememberSaveable(type, id) { mutableStateOf(false) }
    var restorePlayFocusAfterSourceDismiss by remember { mutableStateOf(false) }
    val showCinematicSourceLoading = state.shouldShowCinematicSourceLoading(
        sourcePickerVisible = sourcePickerState != null,
    )
    val sourceOperationBlocksDetail = state.preparing != null || showCinematicSourceLoading

    fun queueSourceOriginFocusRestore(
        episodeTarget: Pair<Int, Int>? = sourcePickerOriginEpisode ?: resolvingEpisodeTarget,
    ) {
        if (episodeTarget != null) {
            pendingEpisodeFocusRestore = episodeTarget
            restorePlayFocusAfterSourceDismiss = false
        } else {
            restorePlayFocusAfterSourceDismiss = true
        }
        sourcePickerOriginEpisode = null
        resolvingEpisodeTarget = null
    }
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

    BackHandler(
        enabled = sourcePickerState == null && !showWatchlistPicker && !sourceOperationBlocksDetail,
        onBack = onBack,
    )
    BackHandler(enabled = sourceOperationBlocksDetail && !showWatchlistPicker) {
        queueSourceOriginFocusRestore()
        detailViewModel.cancelSourceLookup()
    }
    DisposableEffect(Unit) {
        onDispose {
            TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        }
    }
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
        mediaFavoritesRepository.refresh(force = true)
    }

    // Refresh data on resume, but never move focus here. ON_RESUME also fires
    // when the detail route is first shown; delayed focus requests from this
    // observer used to race a quickly-opened source modal and steal its focus.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                detailViewModel.refreshWatchState()
                detailViewModel.refreshMediaLifecycleStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Assign entry focus once per title. Background metadata, progress,
    // library-status, and rating updates must never re-focus Watch now.
    // Including modal visibility in the key cancels this one-shot request if
    // the user opens the source chooser in the same frame.
    LaunchedEffect(state.mediaItem?.id, sourcePickerState != null) {
        if (state.mediaItem != null && sourcePickerState == null && !initialPlayFocusAssigned) {
            initialPlayFocusAssigned = true
            kotlinx.coroutines.yield()
            if (sourcePickerState != null) return@LaunchedEffect
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    // Dismissing the modal is the only post-entry path that deliberately
    // restores Watch now. This is explicit rather than being coupled to any
    // asynchronous detail-state update.
    LaunchedEffect(sourcePickerState, restorePlayFocusAfterSourceDismiss) {
        if (sourcePickerState == null && restorePlayFocusAfterSourceDismiss) {
            restorePlayFocusAfterSourceDismiss = false
            kotlinx.coroutines.yield()
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    // Surface stream errors as visible notifications
    LaunchedEffect(state.streamsError, state.resolvedStream, state.isLoadingStreams, state.isResolving) {
        val error = state.streamsError ?: return@LaunchedEffect
        if (state.streams.isNotEmpty()) return@LaunchedEffect
        if (state.resolvedStream != null || state.isLoadingStreams || state.isResolving) {
            return@LaunchedEffect
        }
        TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        TvNotificationQueue.clear(TV_STREAM_RESOLVING_NOTIFICATION_TAG)
        queueSourceOriginFocusRestore(
            terminalEpisodeFocusTarget(state, sourcePickerOriginEpisode, resolvingEpisodeTarget),
        )
        TvNotificationQueue.post(resolveTvDetailMessage(context, error), NotificationType.ERROR)
    }

    LaunchedEffect(state.resolveError, state.resolvedStream, state.isResolving) {
        val error = state.resolveError ?: return@LaunchedEffect
        if (state.resolvedStream != null || state.isResolving) {
            return@LaunchedEffect
        }
        TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        TvNotificationQueue.clear(TV_STREAM_RESOLVING_NOTIFICATION_TAG)
        if (state.hasTerminalSourceFailure()) {
            queueSourceOriginFocusRestore(
                terminalEpisodeFocusTarget(state, sourcePickerOriginEpisode, resolvingEpisodeTarget),
            )
        } else {
            resolvingEpisodeTarget = null
        }
        TvNotificationQueue.post(resolveTvDetailMessage(context, error), NotificationType.ERROR)
    }

    LaunchedEffect(state.showStreamPicker, state.resolvedStream) {
        if (state.showStreamPicker || state.resolvedStream != null) {
            resolvingEpisodeTarget = null
        }
    }

    LaunchedEffect(state.preparing) {
        if (state.preparing != null) {
            TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        }
    }

    LaunchedEffect(watchlistState.snackbarMessage) {
        watchlistState.snackbarMessage?.let { message ->
            val type = if (watchlistState.mutationState is WatchlistMutationState.Error) {
                NotificationType.ERROR
            } else {
                NotificationType.SUCCESS
            }
            TvNotificationQueue.post(message, type)
            watchlistViewModel.clearSnackbar()
        }
    }

    LaunchedEffect(mediaFavoritesState.lastError) {
        mediaFavoritesState.lastError?.takeIf { it.isNotBlank() }?.let { message ->
            TvNotificationQueue.post(message, NotificationType.ERROR)
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

    // Give the picker an initial focus owner once. Streams arrive incrementally;
    // keying this effect to their count repeatedly pulled the list back to the
    // first result while the user was already browsing it.
    LaunchedEffect(
        state.showStreamPicker,
        state.streams.isNotEmpty(),
        sourcePickerState != null,
    ) {
        if (!state.showStreamPicker || state.streams.isEmpty()) {
            streamPickerFocusInitialized = false
            return@LaunchedEffect
        }
        if (sourcePickerState != null || streamPickerFocusInitialized) return@LaunchedEffect

        streamPickerFocusInitialized = true
        val pickerIndex = listState.layoutInfo.totalItemsCount - state.streams.size - 1
        if (pickerIndex >= 0) {
            listState.scrollToItem(pickerIndex)
        }
        withFrameNanos { }
        runCatching { firstStreamFocusRequester.requestFocus() }
    }

    LaunchedEffect(state.mediaItem?.tmdbId, state.mediaLifecycleStatus?.state) {
        val media = state.mediaItem
        jellyfinAvailableEpisodes = if (media?.type == MediaType.SERIES && media.tmdbId != null) {
            runCatching { jellyfinLibrary.findAvailableEpisodes(media.tmdbId!!) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
    }

    LaunchedEffect(
        focusEpisodes,
        state.mediaItem?.id,
        state.mediaItem?.seasons?.size,
        state.seasonDetail?.episodes?.size,
    ) {
        val item = state.mediaItem ?: return@LaunchedEffect
        if (!focusEpisodes || episodesFocusHandled || item.type != MediaType.SERIES || item.seasons.isEmpty()) {
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(220)
        val episodesIndex = 1 +
            (if (!item.tagline.isNullOrBlank()) 1 else 0) +
            (if (item.genres.isNotEmpty()) 1 else 0) +
            (if (
                item.ratings.withFallbackTmdbScore(item.rating) != null &&
                settingsState.ratingPrefs.showRatingsOnDetailPage
            ) 2 else 0) +
            (if (!item.overview.isNullOrBlank()) 1 else 0) +
            (if (!item.director.isNullOrBlank()) 1 else 0) +
            (if (item.cast.isNotEmpty()) 1 else 0)
        runCatching { listState.animateScrollToItem(episodesIndex.coerceAtLeast(0)) }
        episodesFocusHandled = true
    }

    LaunchedEffect(
        restoreFocusAfterPreparingCancel,
        state.preparing,
        state.showStreamPicker,
        state.streams.size,
        sourcePickerState != null,
    ) {
        if (restoreFocusAfterPreparingCancel && state.preparing == null && sourcePickerState == null) {
            if (state.showStreamPicker && state.streams.isNotEmpty()) {
                val pickerIndex = listState.layoutInfo.totalItemsCount - state.streams.size - 1
                if (pickerIndex >= 0) {
                    listState.scrollToItem(pickerIndex.coerceAtLeast(0))
                }
                withFrameNanos { }
                runCatching { firstStreamFocusRequester.requestFocus() }
            } else {
                queueSourceOriginFocusRestore()
            }
            restoreFocusAfterPreparingCancel = false
        }
    }

    // Usenet source-sheet warmup hook. Mirrors the phone DetailScreen
    // wiring exactly â€" keyed on the boolean only so it fires once per
    // falseâ†’true transition of showStreamPicker, not on incidental
    // recomposition. No-op when there are no Usenet candidates.
    LaunchedEffect(state.showStreamPicker) {
        if (state.showStreamPicker) {
            detailViewModel.onSourceSheetOpened()
        }
    }

    // Usenet ready-handoff observer. The shared VM stages the opaque
    // playback URL on `state.usenetPlaybackIntent` when /resolve flips
    // to ready (or the bounded poll terminates ready). Hand it
    // byte-for-byte to the existing TV `onPlayResolved` entrypoint and
    // clear the intent so a recomposition cannot re-launch.
    LaunchedEffect(state.usenetPlaybackIntent) {
        val intent = state.usenetPlaybackIntent ?: return@LaunchedEffect
        val media = state.mediaItem ?: return@LaunchedEffect
        sourcePickerOriginEpisode = null
        pendingEpisodeFocusRestore = null
        resolvingEpisodeTarget = null
        TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        val epName = state.seasonDetail?.episodes
            ?.find { it.episodeNumber == state.streamContextEpisode }?.name.orEmpty()
        onPlayResolved(
            intent.url,
            "",
            media,
            state.streamContextSeason,
            state.streamContextEpisode,
            state.autoPlayStream != null,
            epName,
        )
        detailViewModel.consumeUsenetPlaybackIntent()
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
        TvNotificationQueue.clear(TV_USENET_PREPARING_NOTIFICATION_TAG)
        TvNotificationQueue.clear(TV_STREAM_RESOLVING_NOTIFICATION_TAG)
        // Don't navigate to player if this resolve was for a download action
        if (pendingDownloadAction !is DownloadAction.None) return@LaunchedEffect

        sourcePickerOriginEpisode = null
        pendingEpisodeFocusRestore = null
        resolvingEpisodeTarget = null

        val urlCandidates = if (resolved.service != null) {
            listOf(resolved.url, resolved.transcodeUrls?.hls, resolved.transcodeUrls?.mp4)
        } else {
            listOf(resolved.transcodeUrls?.mp4, resolved.transcodeUrls?.hls, resolved.url)
        }
        val playbackUrl = urlCandidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val fallbackUrl = urlCandidates.firstOrNull { !it.isNullOrBlank() && it != playbackUrl }.orEmpty()

        val epName = state.seasonDetail?.episodes
            ?.find { it.episodeNumber == state.streamContextEpisode }?.name.orEmpty()
        onPlayResolved(
            playbackUrl,
            fallbackUrl,
            media,
            state.streamContextSeason,
            state.streamContextEpisode,
            state.autoPlayStream != null || resolved.isTemporary,
            epName,
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
    val showSeriesCompactHeader by remember(mediaItem?.id, mediaItem?.type, listState) {
        derivedStateOf {
            mediaItem?.type == MediaType.SERIES &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 260)
        }
    }
    val isInWatchlist = mediaItem?.let { watchlistViewModel.isInWatchlist(it.id) } == true
    val isWatchlistUpdating = mediaItem?.let { watchlistState.isMutatingMedia(it.id) } == true
    val isFavorite = mediaItem?.favoriteMediaKey()?.let { it in mediaFavoritesState.favoriteKeys } == true
    val isBusy = state.isLoadingStreams || state.isResolving

    if (state.isLoading && mediaItem == null) {
        val launchArtwork = com.torve.android.ui.components.ContentLaunchArtworkStore.current
        com.torve.android.ui.components.CinematicContentLoading(
            title = launchArtwork?.title.orEmpty(),
            backdropUrl = launchArtwork?.backdropUrl,
            posterUrl = launchArtwork?.posterUrl,
            logoUrl = launchArtwork?.logoUrl,
            tmdbId = launchArtwork?.tmdbId,
            mediaType = launchArtwork?.type,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .focusGroup(),
    ) {
    TvDetailsCinematicBackground(mediaItem)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
    ) {
        if (mediaItem != null) {
            val item = mediaItem
            // â"€â"€ Hero backdrop + title + buttons â"€â"€
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
                        DetailLogoTitle(item = item)
                        // LAN-library presence pill (Prompt 9C). The
                        // shared `LanAvailabilityBadge` is in the main
                        // source set; TV reuses it.
                        com.torve.android.ui.components.LanAvailabilityBadge(
                            title = item.title,
                        )
                        val metadata = buildString {
                            item.year?.let { append(it) }
                            if (item.rating != null && settingsState.ratingPrefs.allowsTmdbRatingProvider()) {
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

                        if (state.isMarkedWatched) {
                            TvWatchedStatusPill()
                        }

                        // â"€â"€ Library status indicator â"€â"€
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
                            // â"€â"€ Play button with debrid check + loading phases â"€â"€
                            val streamLocked = isLockedFeature(TvEntitledFeature.STREAM_PLAYBACK)
                            val playText = when {
                                streamLocked -> TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL
                                state.isLoadingStreams -> stringResource(R.string.tv_detail_finding_streams)
                                state.isResolving -> stringResource(R.string.tv_detail_resolving)
                                item.type == MediaType.SERIES &&
                                    state.streamContextSeason != null &&
                                    state.streamContextEpisode != null ->
                                    "Watch now · S${state.streamContextSeason.toString().padStart(2, '0')}E${state.streamContextEpisode.toString().padStart(2, '0')}"
                                else -> if (state.primaryPlayLabel.equals("Play", ignoreCase = true)) {
                                    "Watch now"
                                } else {
                                    "Watch now · ${state.primaryPlayLabel}"
                                }
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
                                    runPremiumAction(TvEntitledFeature.STREAM_PLAYBACK) {
                                        // Movies: surface the source picker if there's
                                        // a non-Provider option (LocalFile or LAN). For
                                        // series, the next-episode flow stays as-is â€"
                                        // route disambiguation per-episode is too rich
                                        // to hang on the picker without an episode
                                        // context map. Series picker integration is a
                                        // follow-up.
                                        if (item.type == MediaType.SERIES) {
                                            val ctxS = state.streamContextSeason
                                            val ctxE = state.streamContextEpisode
                                            if (ctxS == null || ctxE == null) {
                                                detailViewModel.playNextEpisode()
                                                return@runPremiumAction
                                            }
                                            coroutineScope.launch {
                                                val picker = buildDetailPickerState(
                                                    item = item,
                                                    seasonNumber = ctxS,
                                                    episodeNumber = ctxE,
                                                    downloadRepository = downloadRepository,
                                                    jellyfinLibrary = jellyfinLibrary,
                                                    lanLibraryConsumer = lanLibraryConsumer,
                                                    networkMonitor = networkMonitor,
                                                    wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
                                                )
                                                val hasNonProvider = picker.options.any { opt ->
                                                    opt.route is PlaybackRoute.LocalFile ||
                                                        opt.route is PlaybackRoute.JellyfinStream ||
                                                        opt.route is PlaybackRoute.LanDesktopStream
                                                }
                                                if (!hasNonProvider) {
                                                    if (settingsState.canPlayStreams) {
                                                        detailViewModel.fetchStreams(season = ctxS, episode = ctxE)
                                                    } else {
                                                        context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
                                                            .edit()
                                                            .putBoolean("tv_settings_open_connections_once", true)
                                                            .apply()
                                                        onSettingsClick()
                                                    }
                                                } else {
                                                    sourcePickerOriginEpisode = null
                                                    sourcePickerSeason = ctxS
                                                    sourcePickerEpisode = ctxE
                                                    sourcePickerMediaItem = item
                                                    sourcePickerState = picker
                                                }
                                            }
                                            return@runPremiumAction
                                        }
                                        coroutineScope.launch {
                                            val picker = buildDetailPickerState(
                                                item = item,
                                                seasonNumber = null,
                                                episodeNumber = null,
                                                downloadRepository = downloadRepository,
                                                jellyfinLibrary = jellyfinLibrary,
                                                lanLibraryConsumer = lanLibraryConsumer,
                                                networkMonitor = networkMonitor,
                                                wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
                                            )
                                            // If the only option is Provider (or no real
                                            // options at all), skip the sheet â€" preserve
                                            // existing behavior.
                                            val hasNonProvider = picker.options.any { opt ->
                                                opt.route is PlaybackRoute.LocalFile ||
                                                    opt.route is PlaybackRoute.JellyfinStream ||
                                                    opt.route is PlaybackRoute.LanDesktopStream
                                            }
                                            if (!hasNonProvider) {
                                                if (settingsState.canPlayStreams) {
                                                    detailViewModel.fetchStreams()
                                                } else {
                                                    context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
                                                        .edit()
                                                        .putBoolean("tv_settings_open_connections_once", true)
                                                        .apply()
                                                    onSettingsClick()
                                                }
                                            } else {
                                                sourcePickerOriginEpisode = null
                                                sourcePickerSeason = null
                                                sourcePickerEpisode = null
                                                sourcePickerMediaItem = item
                                                sourcePickerState = picker
                                            }
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
                                text = if (isWatchlistUpdating) {
                                    stringResource(R.string.common_loading)
                                } else if (isInWatchlist) {
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
                                enabled = !isWatchlistUpdating,
                                onFocused = {
                                    watchlistModalRestoreController.markFocused(watchlistTarget)
                                    onContentFocused(registeredWatchlistFocusRequester)
                                },
                                onClick = {
                                    runPremiumAction(TvEntitledFeature.WATCHLIST_EDIT) {
                                        watchlistViewModel.toggleWatchlist(item)
                                    }
                                },
                            )
                            val favoritesLocked = isLockedFeature(TvEntitledFeature.FAVORITES_EDIT)
                            TvActionButton(
                                text = if (isFavorite) {
                                    if (favoritesLocked) {
                                        "${stringResource(R.string.tv_action_remove_favorites)} (Locked)"
                                    } else {
                                        stringResource(R.string.tv_action_remove_favorites)
                                    }
                                } else {
                                    if (favoritesLocked) {
                                        "${stringResource(R.string.tv_action_add_favorites)} (Locked)"
                                    } else {
                                        stringResource(R.string.tv_action_add_favorites)
                                    }
                                },
                                modifier = Modifier.focusRequester(favoritesFocusRequester),
                                onFocused = { onContentFocused(favoritesFocusRequester) },
                                onClick = {
                                    runPremiumAction(TvEntitledFeature.FAVORITES_EDIT) {
                                        val shouldFavorite = !isFavorite
                                        mediaFavoritesRepository.toggleFavorite(item)
                                        TvNotificationQueue.post(
                                            if (shouldFavorite) {
                                                context.getString(R.string.tv_notification_favorites_added, item.title)
                                            } else {
                                                context.getString(R.string.tv_notification_favorites_removed, item.title)
                                            },
                                            NotificationType.SUCCESS,
                                        )
                                    }
                                },
                            )

                            // â"€â"€ Mark Watched + Rate (Trakt only) â"€â"€
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
                                                onWatchedStatusChanged(item, false)
                                            } else {
                                                detailViewModel.markWatched()
                                                onWatchedStatusChanged(item, true)
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

                            // Download buttons removed â€" TV is stream-only
                        }

                    }
                }
            }

            // Keep the permanent-library action outside the fixed-height hero.
            // The hero clips its children to the rounded backdrop; placing the
            // explanatory copy there caused its lower half to be cut off when
            // metadata/status rows above consumed the available 330dp.
            val lifecycleStatus = state.mediaLifecycleStatus
            if (lifecycleStatus != null || state.isLoadingMediaLifecycle) {
                item(key = "library_lifecycle") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        val lifecycleButtonText = when {
                            state.isLoadingMediaLifecycle -> stringResource(R.string.common_loading)
                            lifecycleStatus?.state == MediaLifecycleState.UNCONFIGURED -> "Set up library downloads"
                            lifecycleStatus?.canRetry == true -> stringResource(R.string.detail_library_request_retry)
                            lifecycleStatus?.canRequest == true && item.type == MediaType.SERIES -> {
                                val selectedSeason = state.selectedSeason ?: 1
                                stringResource(R.string.detail_add_season_to_library, selectedSeason)
                            }
                            lifecycleStatus?.canRequest == true -> stringResource(R.string.detail_add_to_library)
                            lifecycleStatus?.state == MediaLifecycleState.PENDING_APPROVAL ->
                                stringResource(R.string.detail_library_request_pending)
                            lifecycleStatus?.state == MediaLifecycleState.APPROVED ->
                                stringResource(R.string.detail_library_request_approved)
                            lifecycleStatus?.state == MediaLifecycleState.PROCESSING ->
                                stringResource(R.string.detail_library_request_processing)
                            lifecycleStatus?.state == MediaLifecycleState.PARTIALLY_AVAILABLE ->
                                stringResource(R.string.detail_library_request_partial)
                            lifecycleStatus?.state == MediaLifecycleState.AVAILABLE ->
                                stringResource(R.string.detail_library_request_available)
                            else -> stringResource(R.string.detail_library_refresh)
                        }
                        TvActionButton(
                            text = lifecycleButtonText,
                            modifier = Modifier.focusRequester(permanentLibraryFocusRequester),
                            enabled = !state.isLoadingMediaLifecycle &&
                                (
                                    lifecycleStatus?.state == MediaLifecycleState.UNCONFIGURED ||
                                        lifecycleStatus?.canRequest == true ||
                                        lifecycleStatus?.canRetry == true
                                    ),
                            onFocused = { onContentFocused(permanentLibraryFocusRequester) },
                            onClick = {
                                when {
                                    lifecycleStatus?.state == MediaLifecycleState.UNCONFIGURED ->
                                        onLibraryAutomationClick()
                                    lifecycleStatus?.canRetry == true ->
                                        detailViewModel.retryMediaLifecycleRequest()
                                    else -> detailViewModel.requestPermanentCopy()
                                }
                            },
                        )
                        if (lifecycleStatus?.canDeleteRequest == true) {
                            TvActionButton(
                                text = if (lifecycleStatus.isInProgress) {
                                    "Cancel library request"
                                } else {
                                    "Remove library request"
                                },
                                modifier = Modifier.focusRequester(removeLibraryRequestFocusRequester),
                                enabled = !state.isLoadingMediaLifecycle,
                                onFocused = { onContentFocused(removeLibraryRequestFocusRequester) },
                                onClick = { detailViewModel.deleteMediaLifecycleRequest() },
                            )
                            Text(
                                text = "Removes the Seerr request. Already downloaded files stay in your library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        if (lifecycleStatus?.state == MediaLifecycleState.UNCONFIGURED) {
                            Text(
                                text = "Connect Seerr once to send movies to Radarr and selected seasons to Sonarr.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        } else if (lifecycleStatus?.canRequest == true || lifecycleStatus?.canRetry == true) {
                            Text(
                                text = "Downloads your preferred copy through Seerr and *Arr. Watch now remains available while it is prepared.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                        state.mediaLifecycleError?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }

            // â"€â"€ Tagline â"€â"€
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

            // â"€â"€ Genre pills â"€â"€
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

            // â"€â"€ Multi-source ratings â"€â"€
            val ratingPrefs = settingsState.ratingPrefs
            val detailRatings = item.ratings.withFallbackTmdbScore(item.rating)
            if (detailRatings != null &&
                ratingPrefs.showRatingsOnDetailPage &&
                detailRatings.hasAnyEnabledDisplayValue(ratingPrefs)
            ) {
                item(key = "ratings") {
                    val selectedProviders = ratingPrefs.enabledProviders.filterNot { it == RatingSource.TORVE }
                    val providers = deriveProvidersToRender(
                        enabledProviders = selectedProviders,
                        providerOrder = ratingPrefs.providerOrder,
                        maxRatingsOnCard = selectedProviders.size.coerceAtLeast(1),
                        fallbackToTmdbWhenNoneSelected = ratingPrefs.enabledProviders.isEmpty(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        for (source in providers) {
                            val value = getRatingValue(source, detailRatings, ratingPrefs) ?: continue
                            TvRatingChip(
                                label = source.displayName,
                                value = value,
                                iconRes = ratingSourceIconRes(source, detailRatings),
                            )
                        }
                    }
                }
            }

            // â"€â"€ Overview â"€â"€
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

            // â"€â"€ Director â"€â"€
            if (!item.director.isNullOrBlank()) {
                item(key = "director") {
                    val directorId = item.directorId
                    val directorReq = remember { FocusRequester() }
                    var directorFocused by remember { mutableStateOf(false) }
                    val directorBorderColor by animateColorAsState(
                        targetValue = if (directorFocused) Amber else Color.Transparent,
                        label = "directorBorder",
                    )
                    val directorModifier = if (directorId != null) {
                        Modifier
                            .padding(bottom = 10.dp)
                            .border(2.dp, directorBorderColor, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (directorFocused) Amber.copy(alpha = 0.16f) else Color.Transparent)
                            .focusRequester(directorReq)
                            .onFocusChanged {
                                directorFocused = it.isFocused
                                if (it.isFocused) onContentFocused(directorReq)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onCastClick(directorId, item.director.orEmpty())
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    } else {
                        Modifier.padding(bottom = 10.dp)
                    }
                    Row(modifier = directorModifier) {
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

            // â"€â"€ Cast â"€â"€
            if (item.cast.isNotEmpty() && item.type != MediaType.SERIES) {
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

            // â"€â"€ Episode picker for series â"€â"€
            if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
                item(key = "episodes") {
                    Spacer(modifier = Modifier.height(4.dp))
                    TvEpisodePicker(
                        seasons = item.seasons,
                        selectedSeason = state.selectedSeason,
                        seasonDetail = state.seasonDetail,
                        isLoadingSeasonDetail = state.isLoadingSeasonDetail,
                        seasonDetailError = state.seasonDetailError,
                        watchedEpisodes = state.watchedEpisodes,
                        watchProgress = state.watchProgress,
                        availableInJellyfinEpisodes = jellyfinAvailableEpisodes,
                        ratingPrefs = settingsState.ratingPrefs,
                        seriesBackdropUrl = item.backdropUrl,
                        seriesPosterUrl = item.posterUrl,
                        railFocusRequester = railFocusRequester,
                        onSeasonSelected = { seasonNumber ->
                            val tvId = item.tmdbId ?: return@TvEpisodePicker
                            detailViewModel.loadSeasonDetail(tvId, seasonNumber)
                        },
                        onEpisodeSelected = { season, episode ->
                            sourcePickerOriginEpisode = season to episode
                            detailViewModel.selectEpisodeForPlayback(season, episode)
                            resolvingEpisodeTarget = season to episode
                            coroutineScope.launch {
                                val picker = buildDetailPickerState(
                                    item = item,
                                    seasonNumber = season,
                                    episodeNumber = episode,
                                    downloadRepository = downloadRepository,
                                    jellyfinLibrary = jellyfinLibrary,
                                    lanLibraryConsumer = lanLibraryConsumer,
                                    networkMonitor = networkMonitor,
                                    wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
                                )
                                val hasNonProvider = picker.options.any { opt ->
                                    opt.route is PlaybackRoute.LocalFile ||
                                        opt.route is PlaybackRoute.JellyfinStream ||
                                        opt.route is PlaybackRoute.LanDesktopStream
                                }
                                if (!hasNonProvider) {
                                    if (settingsState.canPlayStreams) {
                                        detailViewModel.fetchStreams(season = season, episode = episode)
                                    } else {
                                        sourcePickerOriginEpisode = null
                                        resolvingEpisodeTarget = null
                                        context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
                                            .edit()
                                            .putBoolean("tv_settings_open_connections_once", true)
                                            .apply()
                                        onSettingsClick()
                                    }
                                } else {
                                    resolvingEpisodeTarget = null
                                    sourcePickerSeason = season
                                    sourcePickerEpisode = episode
                                    sourcePickerMediaItem = item
                                    sourcePickerState = picker
                                }
                            }
                        },
                        restoreFocusEpisode = pendingEpisodeFocusRestore,
                        onEpisodeFocusRestored = {
                            pendingEpisodeFocusRestore = null
                        },
                        onRetrySeason = {
                            val tvId = item.tmdbId ?: return@TvEpisodePicker
                            detailViewModel.loadSeasonDetail(tvId, state.selectedSeason)
                        },
                        onToggleEpisodeWatched = { season, episode ->
                            detailViewModel.toggleEpisodeWatched(season, episode)
                        },
                        onMarkSeasonWatched = { season ->
                            detailViewModel.markSeasonWatched(season)
                            TvNotificationQueue.post(
                                context.getString(R.string.tv_season_watched, season),
                                NotificationType.SUCCESS,
                            )
                        },
                        onFirstContentRequester = onFirstContentRequester,
                        onContentFocused = onContentFocused,
                        autoFocusFirstSeason = false,
                        resolvingEpisode = resolvingEpisodeTarget
                            ?: if ((state.isLoadingStreams || state.isResolving) &&
                                state.streamContextSeason != null &&
                                state.streamContextEpisode != null
                            ) {
                                state.streamContextSeason!! to state.streamContextEpisode!!
                            } else {
                                null
                            },
                        preferredEpisode = state.nextEpisode?.let { next ->
                            next.season to next.episode
                        },
                    )
                }
            }

            if (item.type == MediaType.SERIES && item.cast.isNotEmpty()) {
                item(key = "cast_secondary") {
                    Text(
                        text = stringResource(R.string.tv_detail_cast),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ash,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        items(item.cast.take(10), key = { it.id }) { member ->
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

            // â"€â"€ Similar titles â"€â"€
            val similarItems = state.similar
                .filterNot { similar ->
                    similar.id == item.id ||
                        (similar.tmdbId != null && item.tmdbId != null && similar.tmdbId == item.tmdbId)
                }
                .take(10)
            when {
                similarItems.isNotEmpty() -> item(key = "similar") {
                    Text(
                        text = stringResource(R.string.tv_detail_more_like_this),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ash,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        itemsIndexed(similarItems, key = { index, item -> mediaItemLazyKey(item, index) }) { _, similar ->
                            TvSimilarCard(
                                item = similar,
                                onClick = { onMediaClick(similar) },
                            )
                        }
                    }
                }
                state.isLoadingSimilar -> item(key = "similar_loading") {
                    Column(modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
                        Text(
                            text = stringResource(R.string.tv_detail_more_like_this),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        TvDetailsCompactInlineState(
                            title = "Loading similar shows...",
                            subtitle = "Looking for related series from the catalog.",
                        )
                    }
                }
                state.similarError != null -> item(key = "similar_error") {
                    Column(modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
                        Text(
                            text = stringResource(R.string.tv_detail_more_like_this),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        TvDetailsCompactInlineState(
                            title = "Couldn't load similar shows.",
                            subtitle = "Check the catalog source or try again.",
                            actionLabel = "Retry",
                            onAction = { detailViewModel.loadDetail(type, id) },
                        )
                    }
                }
                item.type == MediaType.SERIES -> item(key = "similar_empty") {
                    Column(modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
                        Text(
                            text = stringResource(R.string.tv_detail_more_like_this),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        TvDetailsCompactInlineState(
                            title = "No similar shows found.",
                            subtitle = "The catalog did not return related series for this title.",
                        )
                    }
                }
            }

            // â"€â"€ Watchlist service picker â"€â"€
            if (showWatchlistPicker) {
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

            // â"€â"€ Auto-play status â"€â"€
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

            // â"€â"€ Resolve error â"€â"€
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

            // Preparing is now a full-screen overlay rendered outside the
            // lazy list â€" see the overlay block at the end of this scope.
            if (state.resolveError != null) {
                item(key = "resolve_error") {
                    Text(
                        text = resolveTvDetailMessage(context, state.resolveError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            // â"€â"€ Stream Picker (when auto-play is off or auto-play failed) â"€â"€
            if (state.showStreamPicker && state.streams.isNotEmpty()) {
                val groups = groupPlaybackOptionStreams(filteredStreams, state.startupCandidates)
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
                item(key = "picker_filter_pills") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        TvStreamSourceFilter.entries.forEach { filter ->
                            val isActive = tvStreamFilter == filter
                            var pillFocused by remember { mutableStateOf(false) }
                            val pillBg = when {
                                isActive -> Amber
                                pillFocused -> Amber.copy(alpha = 0.3f)
                                else -> Gunmetal
                            }
                            val pillTextColor = if (isActive) Obsidian else Snow
                            Box(
                                modifier = Modifier
                                    .background(pillBg, RoundedCornerShape(20.dp))
                                    .border(1.dp, if (pillFocused || isActive) Amber else Steel.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                                    .onFocusChanged { pillFocused = it.isFocused }
                                    .focusable()
                                    .clickable { tvStreamFilter = filter }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = pillTextColor,
                                )
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "picker_group_${group.titleRes}") {
                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(
                                text = stringResource(group.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                                color = Amber,
                                fontWeight = FontWeight.SemiBold,
                            )
                            group.subtitleRes?.let {
                                Text(
                                    text = stringResource(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Silver,
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        group.items,
                        key = { _, stream -> "stream_${group.titleRes}_${stream.streamUiKey()}" },
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
                                if (stream.isUsenetStream()) {
                                    TvNotificationQueue.post(
                                        message = context.getString(R.string.stream_preparing_title),
                                        type = NotificationType.INFO,
                                        tag = TV_USENET_PREPARING_NOTIFICATION_TAG,
                                        durationMs = null,
                                    )
                                } else {
                                    TvNotificationQueue.post(
                                        context.getString(R.string.stream_resolving),
                                        NotificationType.INFO,
                                        tag = TV_STREAM_RESOLVING_NOTIFICATION_TAG,
                                        durationMs = null,
                                    )
                                }
                                // USENET_NZBDAV rows go through the shared
                                // NzbDAV resolver; everything else stays on
                                // the existing debrid/addon resolve path.
                                if (stream.accelerationProvenanceKind == CandidateProvenanceKind.USENET_NZBDAV) {
                                    detailViewModel.selectUsenetSource(stream)
                                } else {
                                    detailViewModel.resolveStream(
                                        stream = stream,
                                        provider = settingsViewModel.getDebridProvider(),
                                        apiKey = settingsViewModel.getDebridApiKey(),
                                    )
                                }
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
                                        if (stream.languages.isNotEmpty()) {
                                            append(" · \uD83D\uDDE3 ")
                                            append(stream.languages.joinToString(", "))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Snow,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (stream.title.isNotBlank()) {
                                    val titleLines = stream.title.split('\n')
                                    Text(
                                        text = titleLines.first(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Silver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (titleLines.size > 1) {
                                        Text(
                                            text = titleLines.drop(1).joinToString(" "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Silver.copy(alpha = 0.75f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
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

            // â"€â"€ Error â"€â"€
            if ((state.streamsError != null).and(state.streams.isEmpty())) {
                item(key = "error") {
                    Text(
                        text = resolveTvDetailMessage(context, state.streamsError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        } else {
            item(key = "empty") {
                TvDetailsInlineState(
                    title = "Couldn't load this title.",
                    subtitle = com.torve.android.error.resolveErrorKey(
                        androidx.compose.ui.platform.LocalContext.current,
                        state.error,
                    ) ?: "Check the catalog source or try again.",
                )
            }
        }
    }

    // Download toast now rendered by TvNotificationQueue in TvRoot

    } // end LazyColumn

    // Preparing overlay â€" rendered inside the Box but outside the
    // LazyColumn so it always layers above the detail surface, blocking
    // D-pad interaction with the rest of the UI until the probe resolves
    // or the user cancels. Must live in a @Composable scope (Box) â€" not
    // in the LazyColumn's LazyListScope lambda.
    val preparing = state.preparing
    if (preparing != null) {
        TvStreamPreparingOverlay(
            state = preparing,
            mediaItem = mediaItem?.copy(logoUrl = cinematicLogoUrl ?: mediaItem.logoUrl),
            onCancel = {
                restoreFocusAfterPreparingCancel = true
                detailViewModel.cancelPreparing()
            },
            modifier = Modifier.zIndex(80f),
        )
    }
    if (showCinematicSourceLoading) {
        com.torve.android.ui.components.CinematicContentLoading(
            title = mediaItem?.title.orEmpty(),
            backdropUrl = mediaItem?.backdropUrl,
            posterUrl = mediaItem?.posterUrl,
            logoUrl = cinematicLogoUrl ?: mediaItem?.logoUrl,
            tmdbId = mediaItem?.tmdbId,
            mediaType = mediaItem?.type,
            modifier = Modifier.fillMaxSize().zIndex(70f),
        )
    }

    // â"€â"€ Source picker (Prompt 11C) â"€â"€
    // Sits above the rest of the detail content. D-pad OK on a row
    // either launches the player directly (LocalFile / LAN) or
    // dismisses the sheet and re-enters the existing fetchStreams
    // flow (Provider). Route failures are handled by PlayerScreen's
    // auto-source fallback path when launched with autoSourceSelection.
    sourcePickerState?.let { picker ->
        val mediaItem = sourcePickerMediaItem ?: state.mediaItem
        TvSourcePickerSheet(
            state = picker,
            modifier = Modifier.zIndex(90f),
            onSelect = { option ->
                // Capture the launch context before removing the modal. This is
                // atomic with DPAD_CENTER even if detail metadata refreshes now.
                val selectedMediaItem = mediaItem ?: return@TvSourcePickerSheet
                val selectedSeason = sourcePickerSeason
                val selectedEpisode = sourcePickerEpisode
                val selectedOriginEpisode = sourcePickerOriginEpisode
                restorePlayFocusAfterSourceDismiss = false
                pendingEpisodeFocusRestore = null
                sourcePickerState = null
                sourcePickerMediaItem = null
                sourcePickerSeason = null
                sourcePickerEpisode = null
                if (TvDetailsSourcePickerStateBuilder.isProviderFetchSentinel(option)) {
                    sourcePickerOriginEpisode = selectedOriginEpisode
                    resolvingEpisodeTarget = selectedOriginEpisode
                    detailViewModel.fetchStreams(season = selectedSeason, episode = selectedEpisode)
                    return@TvSourcePickerSheet
                }
                sourcePickerOriginEpisode = null
                resolvingEpisodeTarget = null
                val pickerEpName = state.seasonDetail?.episodes
                    ?.find { it.episodeNumber == selectedEpisode }?.name.orEmpty()
                when (val route = option.route) {
                    is PlaybackRoute.LocalFile -> {
                        onPlayResolved(
                            "file://${route.absolutePath}",
                            "",
                            selectedMediaItem,
                            selectedSeason,
                            selectedEpisode,
                            true,
                            pickerEpName,
                        )
                    }
                    is PlaybackRoute.JellyfinStream -> {
                        PendingLanPlaybackHandoff.stage(route)
                        onPlayResolved(
                            route.url,
                            "",
                            selectedMediaItem,
                            selectedSeason,
                            selectedEpisode,
                            true,
                            pickerEpName,
                        )
                    }
                    is PlaybackRoute.LanDesktopStream -> {
                        // Stage headers BEFORE navigating — PlayerScreen
                        // attaches X-Torve-Lan-Auth before play().
                        PendingLanPlaybackHandoff.stage(route)
                        onPlayResolved(
                            route.url,
                            "",
                            selectedMediaItem,
                            selectedSeason,
                            selectedEpisode,
                            true,
                            pickerEpName,
                        )
                    }
                    is PlaybackRoute.ProviderStream -> {
                        // Synthetic "Provider" sentinel handled above.
                        // A real ProviderStream URL here would be
                        // unusual â€" fall back to fetchStreams to keep
                        // the source disambiguation flow.
                        sourcePickerOriginEpisode = selectedOriginEpisode
                        resolvingEpisodeTarget = selectedOriginEpisode
                        detailViewModel.fetchStreams(season = selectedSeason, episode = selectedEpisode)
                    }
                    PlaybackRoute.ReDownload -> {
                        // No-op â€" the picker only emits ReDownload when
                        // there's nothing else, and the build pre-check
                        // already filters those out.
                    }
                }
            },
            onDismiss = {
                val episodeOrigin = sourcePickerOriginEpisode
                pendingEpisodeFocusRestore = episodeOrigin
                restorePlayFocusAfterSourceDismiss = episodeOrigin == null
                sourcePickerOriginEpisode = null
                sourcePickerState = null
                sourcePickerMediaItem = null
                sourcePickerSeason = null
                sourcePickerEpisode = null
            },
        )
    }

    } // end Box
}

/**
 * Build the source picker for a movie or concrete episode. Probes the
 * local download repo + LAN library snapshot and folds them through
 * [TvDetailsSourcePickerStateBuilder]. Cellular guard mirrors the
 * preference applied elsewhere.
 */
private suspend fun buildDetailPickerState(
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
        // The detail screen always assumes provider streams might
        // resolve â€" the existing fetchStreams() will tell the user if
        // none are actually available.
        providerAvailable = true,
        networkMode = networkMode,
        wifiOnlyForLan = wifiOnlyForLan,
    )
}

@Composable
private fun TvSimilarCard(
    item: MediaItem,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "similarScale")
    val borderColor by animateColorAsState(targetValue = if (focused) AmberLight else Color.Transparent, label = "similarBorder")
    val imageUrl = item.posterUrl?.takeIf { it.isNotBlank() }
        ?: item.backdropUrl?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .width(120.dp)
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Gunmetal.copy(alpha = 0.96f),
                            Charcoal.copy(alpha = 0.98f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Obsidian.copy(alpha = 0.82f),
                            ),
                        ),
                    ),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun TvDetailsCinematicBackground(item: MediaItem?) {
    Box(modifier = Modifier.fillMaxSize()) {
        val backdrop = item?.backdropUrl ?: item?.posterUrl
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.98f),
                            Obsidian.copy(alpha = 0.88f),
                            Obsidian.copy(alpha = 0.72f),
                            Obsidian.copy(alpha = 0.90f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.18f),
                            Obsidian.copy(alpha = 0.50f),
                            Obsidian.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun TvSeriesCompactHeader(
    item: MediaItem,
    selectedSeason: Int,
    selectedEpisodeLabel: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Obsidian.copy(alpha = 0.96f))
            .border(1.dp, Snow.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
            .focusProperties { canFocus = false },
    ) {
        val backdrop = item.backdropUrl ?: item.posterUrl
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.98f),
                            Obsidian.copy(alpha = 0.92f),
                            Obsidian.copy(alpha = 0.82f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.36f),
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.42f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TvCompactLogoTitle(
                item = item,
                modifier = Modifier.widthIn(min = 180.dp, max = 300.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val meta = buildList {
                    item.year?.let { add(it.toString()) }
                    add("Season $selectedSeason")
                    selectedEpisodeLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString("  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelLarge,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!selectedEpisodeLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Amber.copy(alpha = 0.20f))
                        .border(1.dp, Amber.copy(alpha = 0.52f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = selectedEpisodeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvCompactLogoTitle(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val logoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (!logoUrl.isNullOrBlank() && !logoFailed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { state ->
                if (state is coil3.compose.AsyncImagePainter.State.Error) {
                    logoFailed = true
                }
            },
            modifier = modifier.heightIn(min = 42.dp, max = 70.dp),
        )
        return
    }
    Text(
        text = item.title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = 24.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.4.sp,
        ),
        color = Snow,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun TvDetailsInlineState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Charcoal.copy(alpha = 0.64f))
            .border(1.dp, Snow.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Silver,
            )
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            var focused by remember { mutableStateOf(false) }
            val borderColor by animateColorAsState(
                targetValue = if (focused) AmberLight else Snow.copy(alpha = 0.14f),
                label = "detailsInlineActionBorder",
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (focused) Amber.copy(alpha = 0.18f) else Gunmetal.copy(alpha = 0.74f))
                    .border(2.dp, borderColor, RoundedCornerShape(999.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TvDetailsCompactInlineState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .widthIn(max = 620.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Charcoal.copy(alpha = 0.48f))
            .border(1.dp, Snow.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Snow.copy(alpha = 0.88f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Silver.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            var focused by remember { mutableStateOf(false) }
            val borderColor by animateColorAsState(
                targetValue = if (focused) AmberLight else Snow.copy(alpha = 0.12f),
                label = "detailsCompactActionBorder",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (focused) Amber.copy(alpha = 0.18f) else Gunmetal.copy(alpha = 0.70f))
                    .border(2.dp, borderColor, RoundedCornerShape(999.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DetailLogoTitle(item: MediaItem) {
    val logoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }

    if (!logoUrl.isNullOrBlank() && !logoFailed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { state ->
                if (state is coil3.compose.AsyncImagePainter.State.Error) {
                    logoFailed = true
                }
            },
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .heightIn(min = 62.dp, max = 104.dp),
        )
        return
    }

    Text(
        text = item.title,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = 38.sp,
            lineHeight = 42.sp,
            letterSpacing = 0.6.sp,
        ),
        color = Snow,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(0.55f),
    )
}

private fun resolveTvDetailMessage(context: android.content.Context, message: String?): String {
    if (message.isNullOrBlank()) return ""
    if (message == "No streams found") {
        return "No playable source found. Episode metadata is available, but no stream is currently available."
    }
    if (message.startsWith("No compatible streams found")) {
        return "No playable source found. Episode metadata is available, but no compatible stream is currently available."
    }
    val shouldResolveAsKey = message.startsWith("error_") || message.startsWith("usenet_")
    return if (shouldResolveAsKey) {
        com.torve.android.error.resolveErrorKey(context, message) ?: message
    } else {
        message
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
            // Always focusable â€" guard click internally so focus is never ejected
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
            .width(74.dp)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
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
                .size(56.dp)
                .background(
                    if (focused) Amber.copy(alpha = 0.16f) else Color.Transparent,
                    CircleShape,
                )
                .border(2.dp, borderColor, CircleShape)
                .padding(3.dp)
                .clip(CircleShape),
        ) {
            if (profileUrl != null) {
                AsyncImage(
                    model = profileUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
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

@Composable
private fun TvWatchedStatusPill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Amber.copy(alpha = 0.18f))
            .border(1.dp, Amber.copy(alpha = 0.52f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudDone,
            contentDescription = null,
            tint = AmberLight,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.tv_watched),
            style = MaterialTheme.typography.labelMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * TV-friendly full-screen overlay for the preparing state. D-pad focus
 * defaults to the Cancel button so the user always has an obvious exit.
 * Sized for couch viewing â€" larger spinner, bigger type, centered panel.
 */
@Composable
internal fun TvStreamPreparingOverlay(
    state: com.torve.presentation.detail.PreparingStreamState,
    mediaItem: MediaItem?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var cancelFocused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.canCancel) {
        if (state.canCancel) {
            kotlinx.coroutines.delay(200)
            runCatching { cancelFocus.requestFocus() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
                    onCancel(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        com.torve.android.ui.components.CinematicContentLoading(
            title = mediaItem?.title.orEmpty(),
            backdropUrl = mediaItem?.backdropUrl,
            posterUrl = mediaItem?.posterUrl,
            logoUrl = mediaItem?.logoUrl,
            tmdbId = mediaItem?.tmdbId,
            mediaType = mediaItem?.type,
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 26.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Gunmetal.copy(alpha = 0.92f))
                .padding(horizontal = 40.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.stream_preparing_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.stream_preparing_body, state.serviceName),
                style = MaterialTheme.typography.bodyLarge,
                color = Silver,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            // Elapsed-time ticker driven by a 1-second pulse.
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(state.startedAt) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1_000L)
                }
            }
            val elapsedSec = ((nowMs - state.startedAt) / 1000L).coerceAtLeast(0L)
            val mm = elapsedSec / 60
            val ss = elapsedSec % 60
            val ssStr = if (ss < 10) "0$ss" else ss.toString()
            Text(
                stringResource(R.string.stream_preparing_elapsed, "$mm:$ssStr"),
                style = MaterialTheme.typography.titleMedium,
                color = Amber,
                fontWeight = FontWeight.Medium,
            )

            if (state.canCancel) {
                Spacer(Modifier.height(28.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (cancelFocused) 2.dp else 1.dp,
                            color = if (cancelFocused) Amber else Steel.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .focusRequester(cancelFocus)
                        .onFocusChanged { cancelFocused = it.isFocused }
                        .focusable(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        stringResource(R.string.stream_preparing_cancel),
                        color = Silver,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
