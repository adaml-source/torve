package com.torve.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.player.ExoPlayerEngine
import com.torve.android.player.PlaybackRuntimeInfo
import com.torve.android.ui.channels.ChannelRow
import com.torve.android.ui.channels.ChannelsGuideContent
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.TrackDescription
import com.torve.presentation.channels.CategoryNameCleaner

@Composable
internal fun MobilePlayerControlsOverlay(
    title: String,
    badgeUrl: String?,
    subtitle: String?,
    isLivePlayback: Boolean,
    isFavorite: Boolean,
    pictureFormatLabel: String,
    showPictureFormatAction: Boolean,
    showOrientationAction: Boolean,
    showGuideAction: Boolean,
    showChannelListAction: Boolean,
    castAvailable: Boolean,
    isCasting: Boolean,
    showSubtitleAction: Boolean,
    showAudioAction: Boolean,
    showFavoriteAction: Boolean,
    showSettingsAction: Boolean,
    showPlaybackTransport: Boolean,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    sliderPosition: Float,
    isSeeking: Boolean,
    seekPreviewPositionMs: Long,
    skipSegments: List<com.torve.domain.player.SkipSegment>,
    voiceOverlayMessage: String?,
    onBack: () -> Unit,
    onOpenPictureFormat: () -> Unit,
    onRotateOrientation: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenChannelList: () -> Unit,
    onOpenMenu: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenSubtitlePicker: () -> Unit,
    onOpenAudioPicker: () -> Unit,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileChromeButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            if (!badgeUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = badgeUrl,
                        contentDescription = title,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isLivePlayback) {
                        Text(
                            text = "LIVE",
                            color = Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(com.torve.android.ui.theme.Amber)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPictureFormatAction) {
                    MobileChromeButton(onClick = onOpenPictureFormat) {
                        Text(
                            text = pictureFormatLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (showOrientationAction) {
                    MobileChromeButton(onClick = onRotateOrientation) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = "Change orientation",
                            tint = Color.White,
                        )
                    }
                }
                if (showGuideAction) {
                    MobileChromeButton(onClick = onOpenGuide) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = Color.White)
                    }
                } else if (showChannelListAction) {
                    MobileChromeButton(onClick = onOpenChannelList) {
                        Icon(Icons.Default.List, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }

        if (!voiceOverlayMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xC0121B2B))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = voiceOverlayMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (showPlaybackTransport) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MobileTransportButton(onClick = onSeekBack) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                MobileTransportButton(
                    onClick = onTogglePlayback,
                    containerColor = Color.White.copy(alpha = 0.96f),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                MobileTransportButton(onClick = onSeekForward) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (showFavoriteAction) {
                    MobileChromeButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) com.torve.android.ui.theme.Coral else Color.White,
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showPlaybackTransport) {
                        MobileChromeButton(onClick = onTogglePlayback) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    }
                    if (castAvailable) {
                        MobileChromeButton(onClick = onOpenCast) {
                            Icon(
                                if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = null,
                                tint = if (isCasting) com.torve.android.ui.theme.Amber else Color.White,
                            )
                        }
                    }
                    if (showSettingsAction) {
                        MobileChromeButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                        }
                    }
                    if (showSubtitleAction) {
                        MobileChromeButton(onClick = onOpenSubtitlePicker) {
                            Icon(Icons.Default.Subtitles, contentDescription = null, tint = Color.White)
                        }
                    }
                    if (showAudioAction) {
                        MobileChromeButton(onClick = onOpenAudioPicker) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }

            if (showPlaybackTransport) {
                Spacer(Modifier.height(14.dp))
                Slider(
                    value = sliderPosition,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duration > 0L && skipSegments.isNotEmpty()) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                    ) {
                        skipSegments.forEach { segment ->
                            val startFraction = (segment.startMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            val endFraction = (segment.endMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            val segmentWidthFraction = (endFraction - startFraction).coerceAtLeast(0.004f)
                            Box(
                                modifier = Modifier
                                    .padding(start = maxWidth * startFraction)
                                    .width((maxWidth * segmentWidthFraction).coerceAtLeast(2.dp))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xB3E8A838)),
                            )
                        }
                    }
                }
                if (isSeeking) {
                    Text(
                        text = "Preview ${formatTime(seekPreviewPositionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.86f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Live playback stays active while menus are open",
                        color = Color.White.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileChromeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MobileTransportButton(
    onClick: () -> Unit,
    containerColor: Color = Color.White.copy(alpha = 0.12f),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

internal fun buildPlayerProgrammeLine(
    currentProgramme: EpgProgramme?,
    nextProgramme: EpgProgramme?,
): String? {
    currentProgramme?.let { programme ->
        val timeRange = "${formatClock(programme.startTime)} - ${formatClock(programme.endTime)}"
        return "$timeRange  ${programme.title}"
    }
    nextProgramme?.let { programme ->
        val timeRange = "${formatClock(programme.startTime)} - ${formatClock(programme.endTime)}"
        return "Up next  $timeRange  ${programme.title}"
    }
    return null
}

internal fun formatAudioDelayLabel(delayMs: Int): String {
    if (delayMs == 0) return "None"
    val seconds = delayMs / 1000f
    return if (seconds > 0) "+${seconds}s" else "${seconds}s"
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
}

internal fun formatLiveBufferLabel(durationMs: Int): String {
    return if (durationMs == ExoPlayerEngine.DEFAULT_LIVE_BUFFER_MS) {
        "Default"
    } else {
        val seconds = durationMs / 1000
        "$seconds second" + if (seconds == 1) "" else "s"
    }
}

internal fun LiveAudioOutputMode.displayLabel(): String = when (this) {
    LiveAudioOutputMode.PREFER_COMPATIBLE -> "Prefer compatible"
    LiveAudioOutputMode.FORCE_STEREO_PCM -> "Force stereo PCM"
    LiveAudioOutputMode.AUTO -> "Auto"
}

private fun formatClock(epochMs: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return String.format(
        "%02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}

private fun buildTrackSubtitle(track: TrackDescription): String? {
    return buildString {
        track.language?.takeIf { it.isNotBlank() }?.let { append(it) }
        track.formatHint?.takeIf { it.isNotBlank() }?.let {
            if (isNotBlank()) append(" - ")
            append(it)
        }
        track.channelCount?.takeIf { it > 0 }?.let {
            if (isNotBlank()) append(" - ")
            append("$it ch")
        }
    }.ifBlank { null }
}

@Composable
internal fun MobilePlaybackSheetHost(
    activeSheet: MobilePlaybackSheet?,
    canNavigateBack: Boolean,
    sheetLoading: Boolean,
    sheetError: String?,
    isLivePlayback: Boolean,
    isFavorite: Boolean,
    currentTitle: String,
    currentChannel: Channel?,
    currentProgramme: EpgProgramme?,
    nextProgramme: EpgProgramme?,
    playbackRuntimeInfo: PlaybackRuntimeInfo?,
    subtitleTracks: List<TrackDescription>,
    audioTracks: List<TrackDescription>,
    audioDelayMs: Int,
    playbackSpeed: Float,
    pictureFormat: PlayerPictureFormat,
    liveBufferDurationMs: Int,
    liveAudioMode: LiveAudioOutputMode,
    availableSettings: List<MobilePlaybackSetting>,
    categoryChannels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    showEqualizerAction: Boolean,
    handoffAvailable: Boolean,
    voiceState: com.torve.android.voice.VoiceInputPhase,
    onDismiss: () -> Unit,
    onNavigateBack: () -> Unit,
    onPushSheet: (MobilePlaybackSheet) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDevicePicker: () -> Unit,
    onLaunchVoice: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectSubtitle: (TrackDescription?) -> Unit,
    onSelectAudio: (TrackDescription) -> Unit,
    onApplyAudioDelay: (Int) -> Unit,
    onApplyPlaybackSpeed: (Float) -> Unit,
    onApplyPictureFormat: (PlayerPictureFormat) -> Unit,
    onApplyLiveAudioMode: (LiveAudioOutputMode) -> Unit,
    onApplyLiveBuffer: (Int) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    canReplayProgramme: (Channel, EpgProgramme) -> Boolean,
    onReplayProgramme: (Channel, EpgProgramme) -> Unit,
    onToggleChannelFavorite: (Channel) -> Unit,
) {
    if (activeSheet == null) return

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onDismiss),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF0F141E))
                .padding(bottom = 12.dp),
        ) {
            when (activeSheet) {
            MobilePlaybackSheet.QuickActions -> {
                MobileSheetScaffold(title = "More") {
                    MobileSheetActionRow("Stream info", onClick = { onPushSheet(MobilePlaybackSheet.StreamInfo) })
                    if (isLivePlayback && currentChannel != null) {
                        MobileSheetActionRow(
                            title = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            onClick = onToggleFavorite,
                        )
                    }
                    if (audioTracks.isNotEmpty()) {
                        MobileSheetActionRow(
                            title = "Audio track",
                            subtitle = audioTracks.firstOrNull { it.isSelected }?.label,
                            onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.AUDIO_TRACK)) },
                        )
                    }
                    if (subtitleTracks.isNotEmpty()) {
                        MobileSheetActionRow(
                            title = "Subtitles",
                            subtitle = subtitleTracks.firstOrNull { it.isSelected }?.label ?: "Off",
                            onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.SUBTITLE_TRACK)) },
                        )
                    }
                    if (availableSettings.isNotEmpty()) {
                        MobileSheetActionRow("Playback settings", onClick = { onPushSheet(MobilePlaybackSheet.Settings) })
                    }
                    if (isLivePlayback && currentChannel != null) {
                        MobileSheetActionRow("Open full guide / EPG", onClick = { onPushSheet(MobilePlaybackSheet.Guide) })
                        MobileSheetActionRow("Channel list", onClick = { onPushSheet(MobilePlaybackSheet.ChannelList) })
                    }
                    if (handoffAvailable) {
                        MobileSheetActionRow("Play on device", onClick = onOpenDevicePicker)
                    }
                    MobileSheetActionRow(
                        title = "Voice control",
                        subtitle = when (voiceState) {
                            com.torve.android.voice.VoiceInputPhase.Listening -> "Listening"
                            com.torve.android.voice.VoiceInputPhase.Processing -> "Processing"
                            com.torve.android.voice.VoiceInputPhase.Error -> "Retry voice input"
                            com.torve.android.voice.VoiceInputPhase.Unsupported -> "Unavailable on this device"
                            com.torve.android.voice.VoiceInputPhase.Idle -> "Speak to control playback"
                        },
                        enabled = voiceState != com.torve.android.voice.VoiceInputPhase.Unsupported,
                        onClick = onLaunchVoice,
                    )
                    if (showEqualizerAction) {
                        MobileSheetActionRow("Equalizer", onClick = onOpenEqualizer)
                    }
                }
            }

            MobilePlaybackSheet.Settings -> {
                MobileSheetScaffold(
                    title = "Playback settings",
                    onBack = if (canNavigateBack) onNavigateBack else null,
                ) {
                    availableSettings.forEach { setting ->
                        when (setting) {
                            MobilePlaybackSetting.AUDIO_DELAY -> MobileSheetActionRow(
                                title = "Audio delay",
                                subtitle = formatAudioDelayLabel(audioDelayMs),
                                onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.AUDIO_DELAY)) },
                            )

                            MobilePlaybackSetting.PLAYBACK_SPEED -> MobileSheetActionRow(
                                title = "Playback speed",
                                subtitle = formatPlaybackSpeedLabel(playbackSpeed),
                                onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.PLAYBACK_SPEED)) },
                            )

                            MobilePlaybackSetting.PICTURE_FORMAT -> MobileSheetActionRow(
                                title = "Picture format",
                                subtitle = pictureFormat.shortLabel,
                                onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.PICTURE_FORMAT)) },
                            )

                            MobilePlaybackSetting.LIVE_AUDIO_MODE -> MobileSheetActionRow(
                                title = "Live audio mode",
                                subtitle = liveAudioMode.displayLabel(),
                                onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.LIVE_AUDIO_MODE)) },
                            )

                            MobilePlaybackSetting.LIVE_BUFFER -> MobileSheetActionRow(
                                title = "Live stream buffer",
                                subtitle = formatLiveBufferLabel(liveBufferDurationMs),
                                onClick = { onPushSheet(MobilePlaybackSheet.Picker(MobilePlaybackPicker.LIVE_BUFFER)) },
                            )
                        }
                    }
                }
            }

            MobilePlaybackSheet.StreamInfo -> {
                MobileSheetScaffold(
                    title = "Stream info",
                    onBack = if (canNavigateBack) onNavigateBack else null,
                ) {
                    if (currentChannel != null) {
                        MobileInfoBlock(
                            title = currentChannel.name,
                            subtitle = buildPlayerProgrammeLine(currentProgramme, nextProgramme),
                        )
                    } else {
                        MobileInfoBlock(title = currentTitle)
                    }
                    currentProgramme?.description?.takeIf { it.isNotBlank() }?.let { description ->
                        MobileInfoSection("About", listOf("Description" to description))
                    }
                    val aboutRows = buildList {
                        currentProgramme?.category?.takeIf { it.isNotBlank() }?.let { add("Category" to it) }
                        currentChannel?.groupTitle?.takeIf { it.isNotBlank() }?.let { add("Group" to CategoryNameCleaner.clean(it).name) }
                    }
                    if (aboutRows.isNotEmpty()) {
                        MobileInfoSection("Channel", aboutRows)
                    }
                    playbackRuntimeInfo?.let { info ->
                        val videoRows = buildList {
                            add("Playback" to if (isLivePlayback) "Live" else "On-demand")
                            info.resolutionLabel?.let { add("Resolution" to it) }
                            info.frameRate?.let { add("Frame rate" to "${"%.2f".format(it)} fps") }
                            info.videoCodec?.let { add("Video codec" to it) }
                        }
                        if (videoRows.isNotEmpty()) {
                            MobileInfoSection("Video", videoRows)
                        }
                        val audioRows = buildList {
                            info.audioCodec?.let { add("Audio codec" to it) }
                            info.selectedAudioTrack?.label?.let { add("Active audio" to it) }
                            info.selectedSubtitleTrack?.label?.let { add("Subtitles" to it) }
                            info.selectedAudioTrack?.channelCount?.let { add("Channels" to it.toString()) }
                        }
                        if (audioRows.isNotEmpty()) {
                            MobileInfoSection("Audio", audioRows)
                        }
                        MobileInfoSection(
                            "Playback",
                            listOf(
                                "Player engine" to info.engineId.storageValue.uppercase(),
                                "Audio mode" to liveAudioMode.displayLabel(),
                            ),
                        )
                    }
                }
            }

            MobilePlaybackSheet.CurrentChannelGuide -> {
                MobileSheetScaffold(
                    title = currentChannel?.name ?: "This channel",
                    onBack = if (canNavigateBack) onNavigateBack else null,
                ) {
                    when {
                        sheetLoading -> MobileSheetLoading()
                        !sheetError.isNullOrBlank() -> MobileSheetMessage(sheetError)
                        currentChannel == null -> MobileSheetMessage("Guide data is unavailable for this channel.")
                        else -> CurrentChannelGuideSheet(
                            channel = currentChannel,
                            programmes = buildCurrentChannelProgrammeList(
                                channel = currentChannel,
                                guideProgrammes = guideProgrammes,
                                currentProgramme = currentProgramme,
                                nextProgramme = nextProgramme,
                            ),
                            canReplayProgramme = canReplayProgramme,
                            onProgrammePlay = onReplayProgramme,
                            onOpenFullGuide = { onPushSheet(MobilePlaybackSheet.Guide) },
                            onPlayChannel = onPlayChannel,
                        )
                    }
                }
            }

            MobilePlaybackSheet.ChannelList -> {
                MobileSheetScaffold(
                    title = "Channel list",
                    onBack = if (canNavigateBack) onNavigateBack else null,
                    scrollable = false,
                ) {
                    when {
                        sheetLoading -> MobileSheetLoading()
                        !sheetError.isNullOrBlank() -> MobileSheetMessage(sheetError)
                        categoryChannels.isEmpty() -> MobileSheetMessage("No channels available for this group.")
                        else -> LazyColumn {
                            items(categoryChannels, key = { it.channel.playlistId + it.channel.url }) { enriched ->
                                ChannelRow(
                                    enriched = enriched,
                                    onPlay = { onPlayChannel(enriched.channel) },
                                    onFavorite = { onToggleChannelFavorite(enriched.channel) },
                                )
                            }
                        }
                    }
                }
            }

            MobilePlaybackSheet.Guide -> {
                MobileSheetScaffold(
                    title = "Guide / EPG",
                    onBack = if (canNavigateBack) onNavigateBack else null,
                    scrollable = false,
                ) {
                    when {
                        sheetLoading -> MobileSheetLoading()
                        !sheetError.isNullOrBlank() -> MobileSheetMessage(sheetError)
                        categoryChannels.isEmpty() -> MobileSheetMessage("No guide data is available for this channel group.")
                        else -> ChannelsGuideContent(
                            channels = categoryChannels,
                            guideProgrammes = guideProgrammes,
                            isLoading = false,
                            onChannelPlay = onPlayChannel,
                            canReplayProgramme = canReplayProgramme,
                            onProgrammePlay = onReplayProgramme,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is MobilePlaybackSheet.Picker -> {
                when (activeSheet.type) {
                    MobilePlaybackPicker.AUDIO_TRACK -> MobileSingleChoicePickerSheet(
                        title = "Audio track",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = audioTracks.firstOrNull { it.isSelected }?.label,
                        options = audioTracks.map { track ->
                            MobilePickerOption(
                                key = track.id.toString(),
                                title = track.label,
                                subtitle = buildTrackSubtitle(track),
                                selected = track.isSelected,
                            ) { onSelectAudio(track) }
                        },
                    )

                    MobilePlaybackPicker.SUBTITLE_TRACK -> MobileSingleChoicePickerSheet(
                        title = "Subtitles",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = subtitleTracks.firstOrNull { it.isSelected }?.label ?: "Off",
                        options = buildList {
                            add(
                                MobilePickerOption(
                                    key = "off",
                                    title = "Off",
                                    selected = subtitleTracks.none { it.isSelected },
                                ) { onSelectSubtitle(null) },
                            )
                            subtitleTracks.forEach { track ->
                                add(
                                    MobilePickerOption(
                                        key = track.id.toString(),
                                        title = track.label,
                                        subtitle = buildTrackSubtitle(track),
                                        selected = track.isSelected,
                                    ) { onSelectSubtitle(track) },
                                )
                            }
                        },
                    )

                    MobilePlaybackPicker.AUDIO_DELAY -> MobileSingleChoicePickerSheet(
                        title = "Audio delay",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = formatAudioDelayLabel(audioDelayMs),
                        options = listOf(-2000, -1000, -500, -250, 0, 250, 500, 1000, 2000).map { delay ->
                            MobilePickerOption(
                                key = delay.toString(),
                                title = formatAudioDelayLabel(delay),
                                selected = delay == audioDelayMs,
                            ) { onApplyAudioDelay(delay) }
                        },
                    )

                    MobilePlaybackPicker.PLAYBACK_SPEED -> MobileSingleChoicePickerSheet(
                        title = "Playback speed",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = formatPlaybackSpeedLabel(playbackSpeed),
                        options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).map { speed ->
                            MobilePickerOption(
                                key = speed.toString(),
                                title = formatPlaybackSpeedLabel(speed),
                                selected = speed == playbackSpeed,
                            ) { onApplyPlaybackSpeed(speed) }
                        },
                    )

                    MobilePlaybackPicker.PICTURE_FORMAT -> MobileSingleChoicePickerSheet(
                        title = "Picture format",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = pictureFormat.shortLabel,
                        options = PlayerPictureFormat.entries.map { option ->
                            MobilePickerOption(
                                key = option.name,
                                title = option.label,
                                subtitle = option.shortLabel,
                                selected = option == pictureFormat,
                            ) { onApplyPictureFormat(option) }
                        },
                    )

                    MobilePlaybackPicker.LIVE_AUDIO_MODE -> MobileSingleChoicePickerSheet(
                        title = "Live audio mode",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = liveAudioMode.displayLabel(),
                        options = LiveAudioOutputMode.entries.map { mode ->
                            MobilePickerOption(
                                key = mode.storageValue,
                                title = mode.displayLabel(),
                                selected = mode == liveAudioMode,
                            ) { onApplyLiveAudioMode(mode) }
                        },
                    )

                    MobilePlaybackPicker.LIVE_BUFFER -> MobileSingleChoicePickerSheet(
                        title = "Live stream buffer",
                        onBack = if (canNavigateBack) onNavigateBack else null,
                        subtitle = formatLiveBufferLabel(liveBufferDurationMs),
                        options = listOf(
                            ExoPlayerEngine.DEFAULT_LIVE_BUFFER_MS,
                            1_000,
                            2_000,
                            5_000,
                            8_000,
                            10_000,
                            15_000,
                            20_000,
                        ).map { durationMs ->
                            MobilePickerOption(
                                key = durationMs.toString(),
                                title = formatLiveBufferLabel(durationMs),
                                selected = durationMs == liveBufferDurationMs,
                            ) { onApplyLiveBuffer(durationMs) }
                        },
                    )
                }
            }
        }
    }
}
}

@Composable
private fun MobileSheetScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                MobileChromeButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (scrollable) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        } else {
            Box(modifier = Modifier.weight(1f, fill = true)) {
                content()
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun MobileSheetActionRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = if (enabled) 0.72f else 0.38f),
            )
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
}

private data class MobilePickerOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val onSelect: () -> Unit,
)

@Composable
private fun MobileSingleChoicePickerSheet(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String?,
    options: List<MobilePickerOption>,
) {
    MobileSheetScaffold(title = title, onBack = onBack, scrollable = false) {
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn {
            items(options, key = { it.key }) { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = option.onSelect)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        if (!option.subtitle.isNullOrBlank()) {
                            Text(
                                text = option.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                            )
                        }
                    }
                    if (option.selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = com.torve.android.ui.theme.Amber,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun MobileSheetLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = com.torve.android.ui.theme.Amber)
    }
}

@Composable
private fun MobileSheetMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.76f),
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun MobileInfoBlock(
    title: String,
    subtitle: String? = null,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
    )
    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun MobileInfoSection(
    title: String,
    rows: List<Pair<String, String>>,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
    )
    Spacer(Modifier.height(6.dp))
    rows.forEach { (label, value) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.width(120.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

private fun buildCurrentChannelProgrammeList(
    channel: Channel,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    currentProgramme: EpgProgramme?,
    nextProgramme: EpgProgramme?,
): List<EpgProgramme> {
    val key = canonicalEpgChannelKey(
        playlistId = channel.playlistId,
        channel = channel,
    )
    val directProgrammes = if (key.isNullOrBlank()) {
        emptyList()
    } else {
        guideProgrammes[key].orEmpty()
    }
    if (directProgrammes.isNotEmpty()) {
        return directProgrammes
    }
    return listOfNotNull(currentProgramme, nextProgramme)
        .distinctBy { "${it.startTime}|${it.endTime}|${it.title}" }
        .sortedBy { it.startTime }
}

@Composable
private fun CurrentChannelGuideSheet(
    channel: Channel,
    programmes: List<EpgProgramme>,
    canReplayProgramme: (Channel, EpgProgramme) -> Boolean,
    onProgrammePlay: (Channel, EpgProgramme) -> Unit,
    onOpenFullGuide: () -> Unit,
    onPlayChannel: (Channel) -> Unit,
) {
    MobileSheetActionRow(
        title = "Open full guide / EPG",
        subtitle = "Browse all channels in this group",
        onClick = onOpenFullGuide,
    )
    MobileSheetActionRow(
        title = "Watch live",
        subtitle = channel.name,
        onClick = { onPlayChannel(channel) },
    )
    Spacer(Modifier.height(12.dp))
    if (programmes.isEmpty()) {
        MobileSheetMessage("No schedule is available for this channel right now.")
        return
    }
    val nowMs = System.currentTimeMillis()
    programmes.sortedBy { it.startTime }.forEach { programme ->
        val isCurrent = nowMs in programme.startTime until programme.endTime
        val canReplay = canReplayProgramme(channel, programme)
        CurrentChannelProgrammeRow(
            programme = programme,
            isCurrent = isCurrent,
            canReplay = canReplay,
            onClick = if (canReplay) {
                { onProgrammePlay(channel, programme) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun CurrentChannelProgrammeRow(
    programme: EpgProgramme,
    isCurrent: Boolean,
    canReplay: Boolean,
    onClick: (() -> Unit)?,
) {
    val statusLabel = when {
        isCurrent -> "Now"
        canReplay -> "Replay"
        programme.endTime <= System.currentTimeMillis() -> "Ended"
        else -> "Upcoming"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .background(if (isCurrent) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${formatClock(programme.startTime)} - ${formatClock(programme.endTime)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent || canReplay) com.torve.android.ui.theme.Amber else Color.White.copy(alpha = 0.56f),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = programme.title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        programme.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
}
