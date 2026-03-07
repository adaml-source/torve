package com.torve.android.tv.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.torve.android.player.ExoPlayerEngine
import com.torve.android.player.MPVPlayerEngine
import com.torve.android.player.MPVView
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Obsidian
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private const val AUTO_HIDE_DELAY_MS = 5_000L
private const val LONG_PRESS_THRESHOLD_MS = 800L

private enum class LivePictureFormat(
    val key: String,
    val label: String,
    val frameAspectRatio: Float?,
    val exoResizeMode: Int,
) {
    SOURCE("source", "Source", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ORIGINAL("original", "Original", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FULLSCREEN("fullscreen", "Full screen", null, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_16_9("16_9", "16:9", 16f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_4_3("4_3", "4:3", 4f / 3f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    RATIO_21_9("21_9", "21:9", 21f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ;

    companion object {
        fun fromKey(key: String): LivePictureFormat = entries.firstOrNull { it.key == key } ?: SOURCE
    }
}

/**
 * TiviMate-style live TV player with overlays for channel info, menu bar,
 * EPG guide, channel list browser, and settings panel.
 */
@Composable
fun TvLivePlayerScreen(
    channelUrl: String,
    channelName: String,
    groupName: String,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // ── Player engine (same pattern as PlayerScreen L264-275) ──
    var useMpv by remember { mutableStateOf(false) }
    val engine = remember {
        val mpvEngine = MPVPlayerEngine(context)
        if (mpvEngine.initialize()) {
            useMpv = true
            mpvEngine as PlayerEngine
        } else {
            val exoEngine = ExoPlayerEngine(context)
            exoEngine.initialize()
            exoEngine as PlayerEngine
        }
    }

    // ── Player state observation ──
    var playerState by remember { mutableStateOf(PlayerState()) }

    DisposableEffect(engine) {
        val listener = object : PlayerListener {
            override fun onStateChanged(state: PlayerState) { playerState = state }
            override fun onError(message: String) { /* handled below via retry */ }
        }
        engine.addListener(listener)
        onDispose { engine.removeListener(listener) }
    }

    // ── Channel state ──
    var currentChannel by remember { mutableStateOf<Channel?>(null) }
    var currentGroupName by remember { mutableStateOf(groupName) }
    var channelNumber by remember { mutableIntStateOf(1) }

    // ── Overlay state ──
    var activeOverlay by remember { mutableStateOf(LivePlayerOverlay.NONE) }
    var overlayTimestamp by remember { mutableLongStateOf(0L) }

    // ── Stream info for menu bar ──
    var videoResolution by remember { mutableStateOf("") }
    var audioCodec by remember { mutableStateOf("") }

    // ── Long-press detection ──
    var centerKeyDownTime by remember { mutableLongStateOf(0L) }
    val playerRootFocusRequester = remember { FocusRequester() }
    var selectedPictureFormatKey by rememberSaveable { mutableStateOf(LivePictureFormat.SOURCE.key) }
    val selectedPictureFormat = LivePictureFormat.fromKey(selectedPictureFormatKey)
    var exoPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    suspend fun requestPlayerRootFocus() {
        repeat(12) {
            val focused = runCatching {
                playerRootFocusRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (focused) return
            delay(40)
        }
    }

    // ── Resolve the initial channel from ViewModel state ──
    LaunchedEffect(channelUrl, state.categories) {
        if (currentChannel == null) {
            val ch = findChannelByUrl(channelUrl, state.categories.flatMap { it.channels })
            if (ch != null) {
                currentChannel = ch.channel
                val (group, idx) = findChannelGroupAndIndex(ch.channel, state.categories)
                currentGroupName = group ?: groupName
                channelNumber = idx + 1
            } else {
                // Fallback: create a minimal channel
                currentChannel = Channel(
                    name = channelName,
                    url = channelUrl,
                    groupTitle = groupName,
                )
                channelNumber = 1
            }
        }
    }

    // ── Start playback when channel is set ──
    LaunchedEffect(currentChannel?.url) {
        currentChannel?.let { ch ->
            engine.play(ch.url)
            viewModel.recordChannelViewed(ch)
        }
    }

    LaunchedEffect(useMpv, state.audioPassthroughEnabled, state.preferSurroundCodecs) {
        if (useMpv) {
            (engine as? MPVPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = state.audioPassthroughEnabled,
                preferSurround = state.preferSurroundCodecs,
            )
        } else {
            (engine as? ExoPlayerEngine)?.setAudioOutputPreferences(
                passthroughEnabled = state.audioPassthroughEnabled,
                preferSurround = state.preferSurroundCodecs,
            )
        }
    }

    LaunchedEffect(selectedPictureFormat, useMpv) {
        if (useMpv) return@LaunchedEffect
        exoPlayerView?.resizeMode = selectedPictureFormat.exoResizeMode
    }

    // ── Update stream info from ExoPlayer format (poll every 2s) ──
    LaunchedEffect(useMpv) {
        if (!useMpv) {
            while (true) {
                delay(2000)
                val exo = (engine as? ExoPlayerEngine)?.getExoPlayer()
                exo?.let {
                    val vf = it.videoFormat
                    val af = it.audioFormat
                    videoResolution = if (vf != null) "${vf.width}×${vf.height}" else ""
                    audioCodec = af?.sampleMimeType?.substringAfterLast("/")?.uppercase() ?: ""
                }
            }
        }
    }

    // ── Auto-hide timer for CHANNEL_INFO and MENU_BAR ──
    LaunchedEffect(activeOverlay, overlayTimestamp) {
        if (activeOverlay == LivePlayerOverlay.CHANNEL_INFO || activeOverlay == LivePlayerOverlay.MENU_BAR) {
            delay(AUTO_HIDE_DELAY_MS)
            if (activeOverlay == LivePlayerOverlay.CHANNEL_INFO || activeOverlay == LivePlayerOverlay.MENU_BAR) {
                activeOverlay = LivePlayerOverlay.NONE
            }
        }
    }

    // ── Keep screen on ──
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            engine.release()
        }
    }

    // ── Back handler ──
    BackHandler(enabled = true) {
        if (activeOverlay != LivePlayerOverlay.NONE) {
            activeOverlay = LivePlayerOverlay.NONE
        } else {
            onBack()
        }
    }

    LaunchedEffect(Unit) {
        requestPlayerRootFocus()
    }

    LaunchedEffect(activeOverlay) {
        centerKeyDownTime = 0L
        if (activeOverlay == LivePlayerOverlay.NONE) {
            delay(40)
            requestPlayerRootFocus()
        }
    }

    // ── Channel zapping helper ──
    fun zapChannel(delta: Int) {
        val categories = state.categories
        val ch = currentChannel ?: return
        val allChannels = categories.flatMap { it.channels }
        val currentIdx = allChannels.indexOfFirst { it.channel.url == ch.url }
        if (currentIdx < 0 || allChannels.isEmpty()) return

        val newIdx = (currentIdx + delta).mod(allChannels.size)
        val newEnriched = allChannels[newIdx]
        currentChannel = newEnriched.channel
        val (group, idx) = findChannelGroupAndIndex(newEnriched.channel, categories)
        currentGroupName = group ?: currentGroupName
        channelNumber = idx + 1
        // Playback is triggered by LaunchedEffect on currentChannel.url
        overlayTimestamp = System.currentTimeMillis() // Reset auto-hide
    }

    // ── Build enriched current channel for overlays ──
    val enrichedCurrentChannel: EnrichedChannel? = remember(currentChannel?.url, state.categories) {
        currentChannel?.let { ch ->
            findChannelByUrl(ch.url, state.categories.flatMap { it.channels })
                ?: EnrichedChannel(channel = ch, currentProgramme = null, nextProgramme = null)
        }
    }

    // ── UI ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .focusRequester(playerRootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                when {
                    // ── Center/Confirm key ──
                    event.key == Key.Enter || event.key == Key.DirectionCenter ||
                        event.key == Key.NumPadEnter -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (activeOverlay != LivePlayerOverlay.NONE) {
                                    false
                                } else {
                                    if (centerKeyDownTime == 0L) {
                                        centerKeyDownTime = System.currentTimeMillis()
                                    }
                                    true
                                }
                            }
                            KeyEventType.KeyUp -> {
                                if (activeOverlay != LivePlayerOverlay.NONE) {
                                    return@onPreviewKeyEvent false
                                }
                                val downTime = centerKeyDownTime
                                centerKeyDownTime = 0L
                                if (downTime == 0L) {
                                    return@onPreviewKeyEvent false
                                }
                                val held = System.currentTimeMillis() - downTime
                                if (held >= LONG_PRESS_THRESHOLD_MS) {
                                    // Long press → settings
                                    activeOverlay = LivePlayerOverlay.SETTINGS_PANEL
                                } else {
                                    // Short press on fullscreen playback → channel switch list
                                    activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                                    overlayTimestamp = System.currentTimeMillis()
                                }
                                true
                            }
                            else -> false
                        }
                    }

                    // ── Menu key → Menu Bar ──
                    event.key == Key.Menu && event.type == KeyEventType.KeyDown -> {
                        activeOverlay = if (activeOverlay == LivePlayerOverlay.MENU_BAR) {
                            LivePlayerOverlay.NONE
                        } else {
                            LivePlayerOverlay.MENU_BAR
                        }
                        overlayTimestamp = System.currentTimeMillis()
                        true
                    }

                    // ── Media Play/Pause key ──
                    event.key == Key.MediaPlayPause && event.type == KeyEventType.KeyDown -> {
                        if (playerState.isPlaying) engine.pause() else engine.resume()
                        true
                    }

                    // ── D-pad Up/Down during CHANNEL_INFO → zap ──
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown &&
                        activeOverlay == LivePlayerOverlay.CHANNEL_INFO -> {
                        zapChannel(-1)
                        true
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                        activeOverlay == LivePlayerOverlay.CHANNEL_INFO -> {
                        zapChannel(1)
                        true
                    }

                    // ── Channel Up/Down hardware keys → zap always ──
                    event.key == Key.ChannelUp && event.type == KeyEventType.KeyDown -> {
                        zapChannel(-1)
                        if (activeOverlay == LivePlayerOverlay.NONE) {
                            activeOverlay = LivePlayerOverlay.CHANNEL_INFO
                            overlayTimestamp = System.currentTimeMillis()
                        }
                        true
                    }
                    event.key == Key.ChannelDown && event.type == KeyEventType.KeyDown -> {
                        zapChannel(1)
                        if (activeOverlay == LivePlayerOverlay.NONE) {
                            activeOverlay = LivePlayerOverlay.CHANNEL_INFO
                            overlayTimestamp = System.currentTimeMillis()
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // ── Video surface ──
        val videoSurfaceModifier = selectedPictureFormat.frameAspectRatio?.let { ratio ->
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .align(Alignment.Center)
        } ?: Modifier.fillMaxSize()

        if (useMpv) {
            AndroidView(
                factory = { ctx ->
                    MPVView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                modifier = videoSurfaceModifier,
            )
        } else {
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                        resizeMode = selectedPictureFormat.exoResizeMode
                    }
                },
                update = { view ->
                    exoPlayerView = view
                    view.resizeMode = selectedPictureFormat.exoResizeMode
                    view.player = (engine as? ExoPlayerEngine)?.getExoPlayer()
                },
                onRelease = { view ->
                    if (exoPlayerView === view) {
                        exoPlayerView = null
                    }
                    view.player = null
                },
                modifier = videoSurfaceModifier,
            )
        }

        // ── Buffering indicator ──
        if (playerState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
        }

        // ── Channel Info Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.CHANNEL_INFO,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            enrichedCurrentChannel?.let { ec ->
                LiveChannelInfoOverlay(
                    currentChannel = ec,
                    groupName = currentGroupName,
                    channelNumber = channelNumber,
                    recentChannels = state.recentlyViewedChannels,
                    favoriteChannels = state.favorites,
                    onOpenEpgGuide = {
                        activeOverlay = LivePlayerOverlay.EPG_GUIDE
                    },
                    onOpenHistory = {
                        activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                    },
                    onTuneChannel = { ch ->
                        tuneToChannel(ch, state.categories) { newCh, group, idx ->
                            currentChannel = newCh
                            currentGroupName = group
                            channelNumber = idx + 1
                            activeOverlay = LivePlayerOverlay.NONE
                        }
                    },
                    onClearRecent = {
                        viewModel.clearRecentlyViewed()
                    },
                )
            }
        }

        // ── Menu Bar Overlay ──
        fun enterPipMode(): Boolean {
            val activity = context as? Activity ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return runCatching {
                val params = PictureInPictureParams.Builder().build()
                activity.enterPictureInPictureMode(params)
            }.isSuccess
        }

        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.MENU_BAR,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
        ) {
            LiveMenuBarOverlay(
                videoResolution = videoResolution,
                audioCodec = audioCodec,
                onSearch = {
                    activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                },
                onChannelList = {
                    activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                },
                onRecordings = {
                    activeOverlay = LivePlayerOverlay.EPG_GUIDE
                },
                onMultiview = {
                    activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                },
                onPip = {
                    if (enterPipMode()) {
                        activeOverlay = LivePlayerOverlay.NONE
                    }
                },
            )
        }

        // ── EPG Guide Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.EPG_GUIDE,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiveEpgGuideOverlay(
                guideChannels = state.guideChannels,
                guideProgrammes = state.guideProgrammes,
                currentChannelUrl = currentChannel?.url ?: "",
                onTuneChannel = { ch ->
                    tuneToChannel(ch, state.categories) { newCh, group, idx ->
                        currentChannel = newCh
                        currentGroupName = group
                        channelNumber = idx + 1
                        activeOverlay = LivePlayerOverlay.NONE
                    }
                },
                onShowChannelList = {
                    activeOverlay = LivePlayerOverlay.CHANNEL_LIST
                },
            )
        }

        // ── Channel List Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.CHANNEL_LIST,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiveChannelListOverlay(
                categories = state.categories,
                currentChannelUrl = currentChannel?.url ?: "",
                currentGroupName = currentGroupName,
                favoriteChannels = state.favorites,
                onTuneChannel = { ch, selectedGroupName ->
                    tuneToChannel(ch, state.categories, selectedGroupName) { newCh, group, idx ->
                        currentChannel = newCh
                        currentGroupName = group
                        channelNumber = idx + 1
                        activeOverlay = LivePlayerOverlay.NONE
                    }
                },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
            )
        }

        // ── Settings Overlay ──
        AnimatedVisibility(
            visible = activeOverlay == LivePlayerOverlay.SETTINGS_PANEL,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiveSettingsOverlay(
                state = state,
                currentChannel = currentChannel,
                pictureFormats = LivePictureFormat.entries.map {
                    LivePictureFormatOption(
                        key = it.key,
                        label = it.label,
                    )
                },
                selectedPictureFormatKey = selectedPictureFormatKey,
                onSelectPlaylist = { viewModel.selectPlaylist(it) },
                onToggleCountry = { viewModel.toggleCountry(it) },
                onSelectAllCountries = { viewModel.setCountryFilter(state.availableCountries.toSet()) },
                onClearAllCountries = { viewModel.clearCountryFilter() },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onToggleHiddenCategory = { viewModel.toggleHiddenCategory(it) },
                onSetPictureFormat = { formatKey ->
                    selectedPictureFormatKey = formatKey
                },
                onSetXxxEnabled = { viewModel.setXxxEnabled(it) },
                onSetAudioPassthroughEnabled = { viewModel.setAudioPassthroughEnabled(it) },
                onSetPreferSurroundCodecs = { viewModel.setPreferSurroundCodecs(it) },
            )
        }
    }
}

// ── Helper functions ──

private fun findChannelByUrl(
    url: String,
    enrichedChannels: List<EnrichedChannel>,
): EnrichedChannel? = enrichedChannels.firstOrNull { it.channel.url == url }

private fun findChannelGroupAndIndex(
    channel: Channel,
    categories: List<com.torve.domain.model.ChannelCategory>,
    preferredGroupName: String? = null,
): Pair<String?, Int> {
    preferredGroupName
        ?.takeIf { it.isNotBlank() }
        ?.let { preferred ->
            val preferredCategory = categories.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            if (preferredCategory != null) {
                val preferredIdx = preferredCategory.channels.indexOfFirst { it.channel.url == channel.url }
                if (preferredIdx >= 0) {
                    return preferredCategory.name to preferredIdx
                }
            }
        }

    for (cat in categories) {
        val idx = cat.channels.indexOfFirst { it.channel.url == channel.url }
        if (idx >= 0) return cat.name to idx
    }
    return null to 0
}

private inline fun tuneToChannel(
    channel: Channel,
    categories: List<com.torve.domain.model.ChannelCategory>,
    preferredGroupName: String? = null,
    onResult: (Channel, String, Int) -> Unit,
) {
    val (group, idx) = findChannelGroupAndIndex(channel, categories, preferredGroupName)
    onResult(channel, group ?: channel.groupTitle.orEmpty(), idx)
}


