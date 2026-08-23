package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.torve.android.player.DecoderKind
import com.torve.android.player.PlaybackRuntimeInfo
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.TrackDescription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

enum class LiveBufferPreset(
    val key: String,
    val label: String,
    val durationMs: Int,
    val description: String,
) {
    MINIMUM("minimum", "Minimum (2 s)", 2_500, "Lowest latency"),
    LOW("low", "Low (5 s)", 5_000, "Low latency with light jitter protection"),
    MEDIUM("medium", "Medium (20 s)", 20_000, "Balanced for most channels"),
    HIGH("high", "High (50 s)", 50_000, "Maximum jitter protection"),
}

private enum class LiveOsdPanel(val ownerControlId: String, val title: String) {
    AUDIO("audio", "Audio track"),
    SUBTITLES("subtitles", "Subtitles"),
    SUBTITLE_DELAY("sync", "Subtitle sync"),
    ASPECT_RATIO("aspect", "Aspect ratio"),
    SLEEP_TIMER("timer", "Sleep timer"),
    STREAM("stream", "Stream options"),
    INFO("info", "Playback information"),
}

private data class LiveOsdControl(
    val id: String,
    val label: String,
    val value: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private data class LiveOsdOption(
    val id: String,
    val label: String,
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Video-first live-TV OSD. It owns UI focus only and never recreates or reloads the player.
 */
@Composable
internal fun LivePlaybackMenuOverlay(
    currentChannel: Channel?,
    channelNumber: Int,
    currentProgramme: EpgProgramme?,
    videoResolution: String,
    audioCodec: String,
    isFavorite: Boolean,
    pictureFormats: List<LivePictureFormatOption>,
    selectedPictureFormatKey: String,
    audioTracks: List<TrackDescription>,
    subtitleTracks: List<TrackDescription>,
    playbackRuntimeInfo: PlaybackRuntimeInfo,
    sleepTimerMinutes: Int?,
    sleepTimerRemainingLabel: String?,
    pipSupported: Boolean,
    multiviewAvailable: Boolean,
    timeshiftAvailable: Boolean,
    replayTimeshiftActive: Boolean,
    timeshiftPaused: Boolean,
    liveOffsetMs: Long,
    timeshiftPositionMs: Long,
    timeshiftDurationMs: Long,
    selectedBufferPreset: LiveBufferPreset,
    canStartFromBeginning: Boolean,
    isPlaying: Boolean,
    subtitleDelayMs: Int,
    externalInteractionId: Long,
    onDismiss: () -> Unit,
    onOpenChannelList: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenChannelInfo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReloadStream: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleMultiview: () -> Unit,
    onToggleTimeshiftPause: () -> Unit,
    onGoLive: () -> Unit,
    onSeekTimeshift: (Long) -> Unit,
    onStartFromBeginning: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSetSubtitleDelay: (Int) -> Unit,
    onSelectPictureFormat: (String) -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSubtitleTrack: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectAudioOutputMode: (LiveAudioOutputMode) -> Unit,
    onSelectBufferSize: (LiveBufferPreset) -> Unit,
    onSelectSleepTimer: (Int?) -> Unit,
) {
    var openPanel by remember { mutableStateOf<LiveOsdPanel?>(null) }
    var focusedControlId by remember { mutableStateOf(LivePlaybackOsdPolicy.DEFAULT_CONTROL_ID) }
    var pendingRailFocusId by remember {
        mutableStateOf<String?>(LivePlaybackOsdPolicy.DEFAULT_CONTROL_ID)
    }
    var interactionTick by remember { mutableLongStateOf(0L) }
    val railFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val panelFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val railState = rememberLazyListState()
    val panelState = rememberLazyListState()

    fun registerInteraction() {
        interactionTick += 1L
    }

    fun closePanelOrOsd() {
        val panel = openPanel
        if (panel == null) {
            onDismiss()
        } else {
            focusedControlId = panel.ownerControlId
            pendingRailFocusId = panel.ownerControlId
            openPanel = null
            registerInteraction()
        }
    }

    BackHandler { closePanelOrOsd() }

    LaunchedEffect(externalInteractionId) {
        registerInteraction()
    }

    LaunchedEffect(interactionTick, openPanel) {
        if (openPanel != null) return@LaunchedEffect
        delay(LivePlaybackOsdPolicy.AUTO_HIDE_DELAY_MS)
        onDismiss()
    }

    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    val selectedSubtitle = subtitleTracks.firstOrNull { it.isSelected }
    val selectedAspect = pictureFormats.firstOrNull { it.key == selectedPictureFormatKey }?.label ?: "Auto"
    val resolutionLabel = videoResolution.ifBlank { playbackRuntimeInfo.resolutionLabel.orEmpty() }
    val audioLabel = audioCodec.ifBlank { playbackRuntimeInfo.audioCodec.orEmpty() }

    fun showPanel(panel: LiveOsdPanel) {
        focusedControlId = panel.ownerControlId
        openPanel = panel
        registerInteraction()
    }

    val controls = buildList {
        add(
            LiveOsdControl(
                id = "play_pause",
                label = if (isPlaying) "Pause" else "Play",
                value = if (timeshiftPaused) "Timeshift paused" else if (isPlaying) "Playing" else "Paused",
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                onClick = {
                    if (timeshiftAvailable) onToggleTimeshiftPause() else onTogglePlayPause()
                },
            ),
        )
        add(
            LiveOsdControl("channels", "Channels", currentChannel?.name.orEmpty(), Icons.Filled.List) {
                onDismiss()
                onOpenChannelList()
            },
        )
        add(
            LiveOsdControl("guide", "Guide", currentProgramme?.title.orEmpty(), Icons.Filled.CalendarMonth) {
                onDismiss()
                onOpenGuide()
            },
        )
        if (canStartFromBeginning) {
            add(
                LiveOsdControl(
                    "start_over",
                    "Start over",
                    "From beginning",
                    Icons.Filled.History,
                    onClick = onStartFromBeginning,
                ),
            )
        }
        if (timeshiftAvailable) {
            add(
                LiveOsdControl(
                    "rewind",
                    "Fast rewind",
                    "1 minute",
                    Icons.Filled.FastRewind,
                    onClick = { onSeekTimeshift(-TvLivePlaybackPolicy.REPLAY_SEEK_STEP_MS) },
                ),
            )
            add(
                LiveOsdControl(
                    "forward",
                    "Fast forward",
                    "1 minute",
                    Icons.Filled.FastForward,
                    onClick = { onSeekTimeshift(TvLivePlaybackPolicy.REPLAY_SEEK_STEP_MS) },
                ),
            )
            add(
                LiveOsdControl(
                    "go_live",
                    "Live",
                    when {
                        replayTimeshiftActive -> "Return to channel"
                        liveOffsetMs >= 2_000L -> "${liveOffsetMs / 1_000L}s behind"
                        else -> "At live edge"
                    },
                    Icons.Filled.LiveTv,
                    onClick = onGoLive,
                ),
            )
        }
        add(LiveOsdControl("audio", "Audio", selectedAudio?.language ?: selectedAudio?.label ?: "Auto", Icons.Filled.Audiotrack, onClick = { showPanel(LiveOsdPanel.AUDIO) }))
        add(LiveOsdControl("subtitles", "Subtitles", selectedSubtitle?.language ?: selectedSubtitle?.label ?: "Off", Icons.Filled.Subtitles, onClick = { showPanel(LiveOsdPanel.SUBTITLES) }))
        add(LiveOsdControl("sync", "Sync", formatDelayMs(subtitleDelayMs), Icons.Filled.Sync, onClick = { showPanel(LiveOsdPanel.SUBTITLE_DELAY) }))
        add(LiveOsdControl("aspect", "Aspect", selectedAspect, Icons.Filled.AspectRatio, onClick = { showPanel(LiveOsdPanel.ASPECT_RATIO) }))
        add(LiveOsdControl("timer", "Timer", sleepTimerRemainingLabel ?: "Off", Icons.Filled.Timer, onClick = { showPanel(LiveOsdPanel.SLEEP_TIMER) }))
        add(
            LiveOsdControl(
                "favorite",
                "Favorite",
                if (isFavorite) "On" else "Off",
                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                onClick = onToggleFavorite,
            ),
        )
        add(
            LiveOsdControl(
                "stream",
                "Stream",
                resolutionLabel.ifBlank { selectedBufferPreset.label },
                Icons.Filled.Settings,
                onClick = { showPanel(LiveOsdPanel.STREAM) },
            ),
        )
        add(
            LiveOsdControl(
                "info",
                "Info",
                listOf(resolutionLabel, audioLabel).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Details" },
                Icons.Filled.Info,
                onClick = { showPanel(LiveOsdPanel.INFO) },
            ),
        )
        if (pipSupported) {
            add(
                LiveOsdControl("pip", "Picture in picture", "Open", Icons.Filled.PictureInPicture) {
                    onDismiss()
                    onEnterPip()
                },
            )
        }
        add(
            LiveOsdControl(
                "multiview",
                "Multiview",
                if (multiviewAvailable) "Available" else "Unavailable",
                Icons.Filled.GridView,
                enabled = multiviewAvailable,
                onClick = {
                    onDismiss()
                    onToggleMultiview()
                },
            ),
        )
    }
    val controlIds = controls.map(LiveOsdControl::id)
    val density = LocalDensity.current
    LaunchedEffect(controlIds) {
        val retained = LivePlaybackOsdPolicy.retainedControlId(focusedControlId, controlIds)
        if (retained != focusedControlId) focusedControlId = retained
        if (openPanel == null) pendingRailFocusId = retained
    }

    LaunchedEffect(openPanel, pendingRailFocusId, controlIds) {
        if (openPanel != null) return@LaunchedEffect
        val requestedTarget = pendingRailFocusId ?: return@LaunchedEffect
        val target = LivePlaybackOsdPolicy.retainedControlId(requestedTarget, controlIds)
        val targetIndex = controlIds.indexOf(target).takeIf { it >= 0 } ?: return@LaunchedEffect

        val initialLayout = snapshotFlow { railState.layoutInfo }
            .filter { it.totalItemsCount > 0 && it.visibleItemsInfo.isNotEmpty() }
            .first()
        val visibleTarget = initialLayout.visibleItemsInfo.firstOrNull { it.key == target }

        // A composed target can own focus immediately. Scrolling, when needed, only
        // reveals the clipped edge instead of reanchoring the item at the row start.
        if (visibleTarget != null) {
            runCatching { railFocusRequesters[target]?.requestFocus() }
        }

        val itemSpacingPx = with(density) { 8.dp.roundToPx() }
        val targetBounds = visibleTarget?.let { it.offset to (it.offset + it.size) } ?: run {
            val anchor = if (targetIndex < initialLayout.visibleItemsInfo.first().index) {
                initialLayout.visibleItemsInfo.first()
            } else {
                initialLayout.visibleItemsInfo.last()
            }
            val itemStridePx = anchor.size + itemSpacingPx
            val estimatedStart = anchor.offset + ((targetIndex - anchor.index) * itemStridePx)
            estimatedStart to (estimatedStart + anchor.size)
        }
        val scrollDeltaPx = LivePlaybackOsdPolicy.revealScrollDeltaPx(
            itemStartPx = targetBounds.first,
            itemEndPx = targetBounds.second,
            viewportStartPx = initialLayout.viewportStartOffset,
            viewportEndPx = initialLayout.viewportEndOffset,
        )
        if (scrollDeltaPx != 0) {
            railState.animateScrollBy(
                value = scrollDeltaPx.toFloat(),
                animationSpec = tween(
                    durationMillis = LivePlaybackOsdPolicy.RAIL_SCROLL_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }

        if (visibleTarget == null) {
            snapshotFlow {
                railState.layoutInfo.visibleItemsInfo.any { it.key == target }
            }.filter { it }.first()
            androidx.compose.runtime.withFrameNanos { }
            runCatching { railFocusRequesters[target]?.requestFocus() }
        }
        pendingRailFocusId = null
    }

    val panelOptions = when (openPanel) {
        LiveOsdPanel.AUDIO -> buildList {
            if (audioTracks.isEmpty()) {
                add(LiveOsdOption("audio_none", "No audio tracks detected", enabled = false, onClick = {}))
            } else {
                audioTracks.forEach { track ->
                    add(
                        LiveOsdOption(
                            id = "audio_${track.id}",
                            label = track.label,
                            value = buildTrackSubtitle(track),
                            selected = track.isSelected,
                            onClick = { onSelectAudioTrack(track.id) },
                        ),
                    )
                }
            }
            add(backOption(::closePanelOrOsd))
        }

        LiveOsdPanel.SUBTITLES -> buildList {
            add(LiveOsdOption("subtitles_off", "Off", selected = selectedSubtitle == null, onClick = onDisableSubtitles))
            subtitleTracks.forEach { track ->
                add(
                    LiveOsdOption(
                        id = "subtitle_${track.id}",
                        label = track.label,
                        value = buildTrackSubtitle(track),
                        selected = track.isSelected,
                        onClick = { onSelectSubtitleTrack(track.id) },
                    ),
                )
            }
            add(backOption(::closePanelOrOsd))
        }

        LiveOsdPanel.SUBTITLE_DELAY -> listOf(
            LiveOsdOption("delay_earlier_500", "Earlier", "−500 ms", onClick = { onSetSubtitleDelay((subtitleDelayMs - 500).coerceAtLeast(-10_000)) }),
            LiveOsdOption("delay_earlier_100", "Earlier", "−100 ms", onClick = { onSetSubtitleDelay((subtitleDelayMs - 100).coerceAtLeast(-10_000)) }),
            LiveOsdOption("delay_reset", "Reset", "0 ms", selected = subtitleDelayMs == 0, onClick = { onSetSubtitleDelay(0) }),
            LiveOsdOption("delay_later_100", "Later", "+100 ms", onClick = { onSetSubtitleDelay((subtitleDelayMs + 100).coerceAtMost(10_000)) }),
            LiveOsdOption("delay_later_500", "Later", "+500 ms", onClick = { onSetSubtitleDelay((subtitleDelayMs + 500).coerceAtMost(10_000)) }),
            backOption(::closePanelOrOsd),
        )

        LiveOsdPanel.ASPECT_RATIO -> buildList {
            pictureFormats.forEach { format ->
                add(
                    LiveOsdOption(
                        id = "aspect_${format.key}",
                        label = format.label,
                        selected = format.key == selectedPictureFormatKey,
                        onClick = { onSelectPictureFormat(format.key) },
                    ),
                )
            }
            add(backOption(::closePanelOrOsd))
        }

        LiveOsdPanel.SLEEP_TIMER -> buildList {
            listOf<Int?>(null, 15, 30, 60, 90, 120).forEach { minutes ->
                add(
                    LiveOsdOption(
                        id = minutes?.let { "timer_$it" } ?: "timer_off",
                        label = minutes?.let { "$it minutes" } ?: "Off",
                        selected = sleepTimerMinutes == minutes,
                        onClick = { onSelectSleepTimer(minutes) },
                    ),
                )
            }
            add(backOption(::closePanelOrOsd))
        }

        LiveOsdPanel.STREAM -> buildList {
            add(
                LiveOsdOption(
                    "reload",
                    "Reload stream",
                    "Reconnect current channel",
                    onClick = {
                        onDismiss()
                        onReloadStream()
                    },
                ),
            )
            LiveBufferPreset.entries.forEach { preset ->
                add(
                    LiveOsdOption(
                        id = "buffer_${preset.key}",
                        label = "Buffer • ${preset.label}",
                        value = preset.description,
                        selected = preset == selectedBufferPreset,
                        onClick = { onSelectBufferSize(preset) },
                    ),
                )
            }
            LiveAudioOutputMode.entries.forEach { mode ->
                add(
                    LiveOsdOption(
                        id = "output_${mode.storageValue}",
                        label = "Audio output • ${audioOutputLabel(mode)}",
                        selected = mode == playbackRuntimeInfo.outputMode,
                        onClick = { onSelectAudioOutputMode(mode) },
                    ),
                )
            }
            add(backOption(::closePanelOrOsd))
        }

        LiveOsdPanel.INFO -> buildList {
            add(
                LiveOsdOption("channel_info", "Open channel information", currentChannel?.name) {
                    onDismiss()
                    onOpenChannelInfo()
                },
            )
            add(infoOption("engine", "Player", playbackRuntimeInfo.engineId.storageValue))
            add(infoOption("resolution", "Resolution", resolutionLabel.ifBlank { "Unknown" }))
            add(infoOption("video_codec", "Video", playbackRuntimeInfo.videoCodec ?: "Unknown"))
            add(infoOption("audio_codec", "Audio", playbackRuntimeInfo.audioCodec ?: audioLabel.ifBlank { "Unknown" }))
            add(infoOption("video_decoder", "Video decoder", decoderLabel(playbackRuntimeInfo.videoDecoderKind, playbackRuntimeInfo.videoDecoderName)))
            add(infoOption("audio_decoder", "Audio decoder", decoderLabel(playbackRuntimeInfo.audioDecoderKind, playbackRuntimeInfo.audioDecoderName)))
            add(infoOption("selected_audio", "Audio track", selectedAudio?.let(::buildTrackSubtitle) ?: "None"))
            add(infoOption("selected_subtitle", "Subtitles", selectedSubtitle?.let(::buildTrackSubtitle) ?: "Off"))
            add(backOption(::closePanelOrOsd))
        }

        null -> emptyList()
    }

    val preferredPanelOptionId = when (openPanel) {
        LiveOsdPanel.AUDIO -> selectedAudio?.let { "audio_${it.id}" }
        LiveOsdPanel.SUBTITLES -> selectedSubtitle?.let { "subtitle_${it.id}" } ?: "subtitles_off"
        LiveOsdPanel.SUBTITLE_DELAY -> "delay_reset"
        LiveOsdPanel.ASPECT_RATIO -> "aspect_$selectedPictureFormatKey"
        LiveOsdPanel.SLEEP_TIMER -> sleepTimerMinutes?.let { "timer_$it" } ?: "timer_off"
        LiveOsdPanel.STREAM -> "buffer_${selectedBufferPreset.key}"
        LiveOsdPanel.INFO -> "channel_info"
        null -> null
    }

    LaunchedEffect(openPanel, panelOptions.map(LiveOsdOption::id)) {
        val panel = openPanel ?: return@LaunchedEffect
        val target = preferredPanelOptionId
            ?.takeIf { id -> panelOptions.any { it.id == id && it.enabled } }
            ?: panelOptions.firstOrNull { it.enabled }?.id
            ?: return@LaunchedEffect
        val index = panelOptions.indexOfFirst { it.id == target }
        panelState.scrollToItem(index.coerceAtLeast(0))
        snapshotFlow {
            panelState.layoutInfo.visibleItemsInfo.any { it.key == target }
        }.filter { it }.first()
        androidx.compose.runtime.withFrameNanos { }
        runCatching { panelFocusRequesters["${panel.name}:$target"]?.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("live-playback-osd")
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) registerInteraction()
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                    closePanelOrOsd()
                    true
                } else {
                    false
                }
            },
    ) {
        val railHeight = LivePlaybackOsdPolicy.railHeightDp(maxHeight.value).dp
        val controlWidth = LivePlaybackOsdPolicy.controlWidthDp(maxWidth.value).dp
        val panelWidth = if (maxWidth < 900.dp) 270.dp else 310.dp
        val anchorCenterPx = railState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == focusedControlId }
            ?.let { it.offset + (it.size / 2) }
            ?: 0
        val rawPanelX = with(density) { anchorCenterPx.toDp() } - (panelWidth / 2)
        val panelX = rawPanelX.coerceIn(24.dp, (maxWidth - panelWidth - 24.dp).coerceAtLeast(24.dp))

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .heightIn(min = 82.dp, max = 126.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.44f),
                        1f to Color.Transparent,
                    ),
                ),
        ) {
            LiveOsdChannelHeader(
                currentChannel = currentChannel,
                channelNumber = channelNumber,
                programme = currentProgramme,
                resolution = resolutionLabel,
                audio = audioLabel,
            )
        }

        openPanel?.let { panel ->
            LiveOsdContextPanel(
                title = panel.title,
                options = panelOptions,
                listState = panelState,
                focusRequesters = panelFocusRequesters,
                focusKeyPrefix = panel.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = railHeight + 10.dp)
                    .offset(x = panelX)
                    .width(panelWidth)
                    .heightIn(max = if (maxHeight < 600.dp) 260.dp else 330.dp),
                onInteraction = ::registerInteraction,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(railHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.25f to Color.Black.copy(alpha = 0.58f),
                        1f to Color.Black.copy(alpha = 0.82f),
                    ),
                )
                .padding(
                    top = LivePlaybackOsdPolicy.RAIL_TOP_PADDING_DP.dp,
                    bottom = LivePlaybackOsdPolicy.RAIL_BOTTOM_PADDING_DP.dp,
                ),
        ) {
            val showTimeshiftTimeline = timeshiftAvailable && timeshiftDurationMs > 0L
            Column(modifier = Modifier.fillMaxSize()) {
                if (showTimeshiftTimeline) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LivePlaybackOsdPolicy.TIMELINE_REGION_HEIGHT_DP.dp)
                            .padding(horizontal = 30.dp)
                            .testTag("live-playback-osd-timeline"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatPlaybackTime(timeshiftPositionMs),
                                color = Snow,
                                fontSize = 9.sp,
                            )
                            Text(
                                text = formatPlaybackTime(timeshiftDurationMs),
                                color = Silver,
                                fontSize = 9.sp,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = {
                                (timeshiftPositionMs.toFloat() / timeshiftDurationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Amber,
                            trackColor = Color.White.copy(alpha = 0.16f),
                        )
                    }
                    Spacer(Modifier.height(LivePlaybackOsdPolicy.TIMELINE_CONTROL_GAP_DP.dp))
                }
                LazyRow(
                    state = railState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("live-playback-osd-rail"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                items(controls, key = LiveOsdControl::id) { control ->
                    val requester = railFocusRequesters.getOrPut(control.id) { FocusRequester() }
                    LiveOsdControlButton(
                        control = control,
                        width = controlWidth,
                        focusRequester = requester,
                        onFocused = {
                            focusedControlId = control.id
                            registerInteraction()
                        },
                        onMove = { direction ->
                            val target = LivePlaybackOsdPolicy.nextControlId(
                                controlIds = controlIds,
                                // Use the latest logical target so repeated key presses can
                                // supersede an in-flight reveal animation without queuing it.
                                currentControlId = focusedControlId,
                                direction = direction,
                            )
                            if (target != focusedControlId) {
                                focusedControlId = target
                                pendingRailFocusId = target
                            }
                        },
                        onClick = {
                            focusedControlId = control.id
                            registerInteraction()
                            control.onClick()
                        },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun LiveOsdChannelHeader(
    currentChannel: Channel?,
    channelNumber: Int,
    programme: EpgProgramme?,
    resolution: String,
    audio: String,
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val progress = programme?.let {
        if (it.endTime <= it.startTime) 0f
        else ((nowMs - it.startTime).toFloat() / (it.endTime - it.startTime).toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 720.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            currentChannel?.tvgLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channelNumber > 0) {
                        Text("$channelNumber", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = currentChannel?.name ?: "Live TV",
                        color = Snow,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                programme?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = it.title,
                        color = Silver,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .width(240.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Amber,
                        trackColor = Color.White.copy(alpha = 0.14f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            resolution.takeIf { it.isNotBlank() }?.let { LiveOsdInfoLabel(it) }
            audio.takeIf { it.isNotBlank() }?.let { LiveOsdInfoLabel(it) }
            LiveOsdInfoLabel(timeFormat.format(Date(nowMs)))
        }
    }
}

@Composable
private fun LiveOsdInfoLabel(label: String) {
    Text(
        text = label,
        color = Snow.copy(alpha = 0.9f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun LiveOsdControlButton(
    control: LiveOsdControl,
    width: Dp,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onMove: (LivePlaybackOsdDirection) -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = control.enabled,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> LivePlaybackOsdDirection.PREVIOUS
                    Key.DirectionRight -> LivePlaybackOsdDirection.NEXT
                    else -> null
                }
                if (direction != null) {
                    if (event.type == KeyEventType.KeyDown) onMove(direction)
                    true
                } else {
                    false
                }
            }
            .testTag("live-osd-control-${control.id}"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        // Surface scale and border are draw-time treatments. The fixed width/height
        // above never change when focus moves, so neighbouring controls do not shift.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Amber.copy(alpha = 0.17f),
            disabledContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, Amber),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = control.icon,
                contentDescription = control.label,
                tint = if (control.enabled) Snow else Silver.copy(alpha = 0.45f),
                modifier = Modifier.size(25.dp),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = control.label,
                color = if (control.enabled) Snow else Silver.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = control.value,
                color = Silver,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LiveOsdContextPanel(
    title: String,
    options: List<LiveOsdOption>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequesters: MutableMap<String, FocusRequester>,
    focusKeyPrefix: String,
    modifier: Modifier,
    onInteraction: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(Color(0xE6171B24), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("live-osd-context-panel"),
    ) {
        Text(
            text = title,
            color = Snow,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 4.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(options, key = LiveOsdOption::id) { option ->
                val requesterKey = "$focusKeyPrefix:${option.id}"
                val requester = focusRequesters.getOrPut(requesterKey) { FocusRequester() }
                Surface(
                    onClick = {
                        onInteraction()
                        option.onClick()
                    },
                    enabled = option.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 43.dp)
                        .focusRequester(requester)
                        .onPreviewKeyEvent { event ->
                            event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                        }
                        .onFocusChanged { if (it.isFocused) onInteraction() }
                        .testTag("live-osd-option-${option.id}"),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
                    // Context options stay within the popup's padded viewport. The
                    // default TV Surface focus scale otherwise grows past every edge.
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = LivePlaybackOsdPolicy.CONTEXT_OPTION_FOCUSED_SCALE,
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (option.selected) Amber.copy(alpha = 0.12f) else Graphite.copy(alpha = 0.42f),
                        focusedContainerColor = Amber.copy(alpha = 0.2f),
                        disabledContainerColor = Color.Transparent,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = BorderStroke(2.dp, Amber),
                            shape = RoundedCornerShape(9.dp),
                        ),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                color = if (option.enabled) Snow else Silver,
                                fontSize = 12.sp,
                                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            option.value?.takeIf { it.isNotBlank() }?.let { value ->
                                Text(
                                    text = value,
                                    color = Silver,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (option.selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = Amber,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun backOption(onClick: () -> Unit) = LiveOsdOption(
    id = "back_to_controls",
    label = "Back to controls",
    onClick = onClick,
)

private fun infoOption(id: String, label: String, value: String) = LiveOsdOption(
    id = id,
    label = label,
    value = value,
    enabled = false,
    onClick = {},
)

private fun formatDelayMs(delayMs: Int): String {
    if (delayMs == 0) return "No delay"
    return "${if (delayMs > 0) "+" else ""}$delayMs ms"
}

private fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds)
    }
}

private fun buildTrackSubtitle(track: TrackDescription): String = listOfNotNull(
    track.language?.takeIf { it.isNotBlank() },
    track.formatHint?.takeIf { it.isNotBlank() },
    track.channelCount?.let { "${it}ch" },
).joinToString(" • ").ifBlank { track.label }

private fun audioOutputLabel(mode: LiveAudioOutputMode): String = when (mode) {
    LiveAudioOutputMode.AUTO -> "Auto"
    LiveAudioOutputMode.PREFER_COMPATIBLE -> "Prefer compatible"
    LiveAudioOutputMode.FORCE_STEREO_PCM -> "Stereo PCM"
}

private fun decoderLabel(kind: DecoderKind, decoderName: String?): String {
    val type = when (kind) {
        DecoderKind.HARDWARE -> "Hardware"
        DecoderKind.SOFTWARE -> "Software"
        DecoderKind.UNKNOWN -> "Unknown"
    }
    return decoderName?.takeIf { it.isNotBlank() }?.let { "$type • $it" } ?: type
}
