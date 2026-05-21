package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.torve.android.R
import com.torve.android.player.DecoderKind
import com.torve.android.player.PlaybackRuntimeInfo
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.TrackDescription

private enum class PlaybackMenuRoute {
    MAIN,
    PLAYBACK_OPTIONS,
    AUDIO_TRACKS,
    SUBTITLE_TRACKS,
    DISPLAY_MODE,
    AUDIO_OUTPUT_MODE,
    BUFFER_SIZE,
    DECODER_INFO,
    CHANNEL_OPTIONS,
    SLEEP_TIMER,
    PLAYBACK_INFO,
}

enum class LiveBufferPreset(
    val key: String,
    val label: String,
    val durationMs: Int,
    val description: String,
) {
    MINIMUM("minimum", "Minimum (2 s)", 2_500, "Smallest viable buffer — lowest latency, best for channels that repeat/glitch"),
    LOW("low", "Low (5 s)", 5_000, "Small buffer — low latency, slight jitter absorption"),
    MEDIUM("medium", "Medium (20 s)", 20_000, "Balanced buffer for most channels"),
    HIGH("high", "High (50 s)", 50_000, "Deep buffer — absorbs network jitter, may drift on some channels"),
}

private data class PlaybackMenuItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
)

@Composable
internal fun LivePlaybackMenuOverlay(
    currentChannel: Channel?,
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
    selectedBufferPreset: LiveBufferPreset,
    onDismiss: () -> Unit,
    onOpenChannelList: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenChannelInfo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReloadStream: () -> Unit,
    onEnterPip: () -> Unit,
    onSelectPictureFormat: (String) -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSubtitleTrack: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectAudioOutputMode: (LiveAudioOutputMode) -> Unit,
    onSelectBufferSize: (LiveBufferPreset) -> Unit,
    onSelectSleepTimer: (Int?) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf(PlaybackMenuRoute.MAIN) }
    var focusedItemId by rememberSaveable(route) { mutableStateOf("") }
    val firstFocus = remember(route) { FocusRequester() }

    fun closeCurrentLayer() {
        route = when (route) {
            PlaybackMenuRoute.MAIN -> {
                onDismiss()
                PlaybackMenuRoute.MAIN
            }
            PlaybackMenuRoute.PLAYBACK_INFO,
            PlaybackMenuRoute.PLAYBACK_OPTIONS,
            PlaybackMenuRoute.CHANNEL_OPTIONS,
            PlaybackMenuRoute.SLEEP_TIMER -> PlaybackMenuRoute.MAIN
            PlaybackMenuRoute.AUDIO_TRACKS,
            PlaybackMenuRoute.SUBTITLE_TRACKS,
            PlaybackMenuRoute.DISPLAY_MODE,
            PlaybackMenuRoute.AUDIO_OUTPUT_MODE,
            PlaybackMenuRoute.BUFFER_SIZE,
            PlaybackMenuRoute.DECODER_INFO -> PlaybackMenuRoute.PLAYBACK_OPTIONS
        }
    }

    BackHandler { closeCurrentLayer() }

    LaunchedEffect(route) {
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.82f),
                    0.55f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                ),
            )
            .focusable()
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Menu && event.type == KeyEventType.KeyDown -> {
                        if (route == PlaybackMenuRoute.MAIN) onDismiss() else closeCurrentLayer()
                        true
                    }
                    event.key == Key.Back && event.type == KeyEventType.KeyDown -> {
                        closeCurrentLayer()
                        true
                    }
                    else -> false
                }
            },
    ) {
        when (route) {
                PlaybackMenuRoute.PLAYBACK_INFO,
                PlaybackMenuRoute.DECODER_INFO -> {
                    PlaybackInfoPane(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 40.dp)
                            .fillMaxHeight(0.86f)
                            .width(920.dp),
                        title = if (route == PlaybackMenuRoute.PLAYBACK_INFO) stringResource(R.string.tv_menu_playback_info) else stringResource(R.string.tv_menu_decoder_info),
                        playbackRuntimeInfo = playbackRuntimeInfo,
                        currentChannel = currentChannel,
                        sleepTimerRemainingLabel = sleepTimerRemainingLabel,
                    )
                }

                else -> {
                    val items = when (route) {
                        PlaybackMenuRoute.MAIN -> buildList {
                            add(PlaybackMenuItem("channel_list", stringResource(R.string.tv_menu_channel_list), stringResource(R.string.tv_menu_channel_list_desc)))
                            add(PlaybackMenuItem("guide", stringResource(R.string.tv_menu_tv_guide), stringResource(R.string.tv_menu_tv_guide_desc)))
                            add(PlaybackMenuItem("playback_options", stringResource(R.string.tv_menu_playback_options), stringResource(R.string.tv_menu_playback_options_desc)))
                            add(PlaybackMenuItem("channel_options", stringResource(R.string.tv_menu_channel_options), stringResource(R.string.tv_menu_channel_options_desc)))
                            add(PlaybackMenuItem("sleep_timer", stringResource(R.string.tv_menu_sleep_timer), sleepTimerRemainingLabel ?: stringResource(R.string.tv_menu_sleep_timer_desc)))
                            add(PlaybackMenuItem("playback_info", stringResource(R.string.tv_menu_playback_info), stringResource(R.string.tv_menu_playback_info_desc)))
                            if (pipSupported) add(PlaybackMenuItem("pip", "Picture in Picture", "Keep playing while browsing"))
                            add(
                                PlaybackMenuItem(
                                    "multiview",
                                    "Multiview",
                                    if (multiviewAvailable) "Open multiview" else "Unavailable",
                                    enabled = multiviewAvailable,
                                ),
                            )
                        }

                        PlaybackMenuRoute.PLAYBACK_OPTIONS -> listOf(
                            PlaybackMenuItem("audio_tracks", "Audio Track", selectedAudioSummary(audioTracks)),
                            PlaybackMenuItem("subtitle_tracks", "Subtitle Track", selectedSubtitleSummary(subtitleTracks)),
                            PlaybackMenuItem("display_mode", "Aspect Ratio / Display Mode", selectedPictureFormatLabel(pictureFormats, selectedPictureFormatKey)),
                            PlaybackMenuItem("audio_output", "Audio Output Mode", playbackRuntimeInfo.outputMode?.storageValue?.replace('_', ' ') ?: "Unknown"),
                            PlaybackMenuItem("buffer_size", "Buffer Size", selectedBufferPreset.label),
                            PlaybackMenuItem("decoder_info", stringResource(R.string.tv_menu_decoder_info), decoderSummary(playbackRuntimeInfo)),
                        )

                        PlaybackMenuRoute.CHANNEL_OPTIONS -> listOf(
                            PlaybackMenuItem("favorite", if (isFavorite) "Remove from Favorites" else "Add to Favorites", currentChannel?.name),
                            PlaybackMenuItem("channel_info", "Channel Info", currentChannel?.groupTitle ?: currentChannel?.name),
                            PlaybackMenuItem("reload", "Reload Stream", "Restart the current channel"),
                        )

                        PlaybackMenuRoute.SLEEP_TIMER -> listOf(
                            PlaybackMenuItem("sleep_off", "Off", "Disable the timer"),
                            PlaybackMenuItem("sleep_15", "15 min"),
                            PlaybackMenuItem("sleep_30", "30 min"),
                            PlaybackMenuItem("sleep_60", "60 min"),
                            PlaybackMenuItem("sleep_90", "90 min"),
                            PlaybackMenuItem("sleep_120", "120 min"),
                        )

                        PlaybackMenuRoute.AUDIO_TRACKS -> {
                            if (audioTracks.isEmpty()) {
                                listOf(PlaybackMenuItem("audio_none", "No audio tracks detected", enabled = false))
                            } else {
                                audioTracks.map { track ->
                                    PlaybackMenuItem(
                                        id = "audio_${track.id}",
                                        title = track.label,
                                        subtitle = buildTrackSubtitle(track),
                                    )
                                }
                            }
                        }

                        PlaybackMenuRoute.SUBTITLE_TRACKS -> buildList {
                            add(PlaybackMenuItem("subtitles_off", "Off", "Disable subtitles"))
                            if (subtitleTracks.isEmpty()) {
                                add(PlaybackMenuItem("subtitle_none", "No subtitle tracks detected", enabled = false))
                            } else {
                                subtitleTracks.forEach { track ->
                                    add(
                                        PlaybackMenuItem(
                                            id = "subtitle_${track.id}",
                                            title = track.label,
                                            subtitle = buildTrackSubtitle(track),
                                        ),
                                    )
                                }
                            }
                        }

                        PlaybackMenuRoute.DISPLAY_MODE -> pictureFormats.map { format ->
                            PlaybackMenuItem(
                                id = "display_${format.key}",
                                title = format.label,
                                subtitle = if (format.key == selectedPictureFormatKey) "Selected" else null,
                            )
                        }

                        PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> LiveAudioOutputMode.entries.map { mode ->
                            PlaybackMenuItem(
                                id = "output_${mode.storageValue}",
                                title = when (mode) {
                                    LiveAudioOutputMode.AUTO -> "Auto"
                                    LiveAudioOutputMode.PREFER_COMPATIBLE -> "Prefer Compatible"
                                    LiveAudioOutputMode.FORCE_STEREO_PCM -> "Force Stereo PCM"
                                },
                                subtitle = if (mode == playbackRuntimeInfo.outputMode) "Selected" else null,
                            )
                        }

                        PlaybackMenuRoute.BUFFER_SIZE -> LiveBufferPreset.entries.map { preset ->
                            PlaybackMenuItem(
                                id = "buffer_${preset.key}",
                                title = preset.label,
                                subtitle = if (preset == selectedBufferPreset) "Selected" else null,
                            )
                        }

                        PlaybackMenuRoute.PLAYBACK_INFO,
                        PlaybackMenuRoute.DECODER_INFO -> emptyList()
                    }

                    PlaybackMenuShell(
                        route = route,
                        items = items,
                        focusedItemId = focusedItemId,
                        firstFocus = firstFocus,
                        currentChannel = currentChannel,
                        isFavorite = isFavorite,
                        pictureFormats = pictureFormats,
                        selectedPictureFormatKey = selectedPictureFormatKey,
                        audioTracks = audioTracks,
                        subtitleTracks = subtitleTracks,
                        playbackRuntimeInfo = playbackRuntimeInfo,
                        sleepTimerMinutes = sleepTimerMinutes,
                        sleepTimerRemainingLabel = sleepTimerRemainingLabel,
                        selectedBufferPreset = selectedBufferPreset,
                        onFocusItem = { focusedItemId = it },
                        onSelectItem = { item ->
                            when (route) {
                                PlaybackMenuRoute.MAIN -> when (item.id) {
                                    "channel_list" -> {
                                        onDismiss()
                                        onOpenChannelList()
                                    }
                                    "guide" -> {
                                        onDismiss()
                                        onOpenGuide()
                                    }
                                    "playback_options" -> route = PlaybackMenuRoute.PLAYBACK_OPTIONS
                                    "channel_options" -> route = PlaybackMenuRoute.CHANNEL_OPTIONS
                                    "sleep_timer" -> route = PlaybackMenuRoute.SLEEP_TIMER
                                    "playback_info" -> route = PlaybackMenuRoute.PLAYBACK_INFO
                                    "pip" -> {
                                        onDismiss()
                                        onEnterPip()
                                    }
                                    else -> Unit
                                }

                                PlaybackMenuRoute.PLAYBACK_OPTIONS -> when (item.id) {
                                    "audio_tracks" -> route = PlaybackMenuRoute.AUDIO_TRACKS
                                    "subtitle_tracks" -> route = PlaybackMenuRoute.SUBTITLE_TRACKS
                                    "display_mode" -> route = PlaybackMenuRoute.DISPLAY_MODE
                                    "audio_output" -> route = PlaybackMenuRoute.AUDIO_OUTPUT_MODE
                                    "buffer_size" -> route = PlaybackMenuRoute.BUFFER_SIZE
                                    "decoder_info" -> route = PlaybackMenuRoute.DECODER_INFO
                                    else -> Unit
                                }

                                PlaybackMenuRoute.CHANNEL_OPTIONS -> when (item.id) {
                                    "favorite" -> onToggleFavorite()
                                    "channel_info" -> {
                                        onDismiss()
                                        onOpenChannelInfo()
                                    }
                                    "reload" -> {
                                        onDismiss()
                                        onReloadStream()
                                    }
                                    else -> Unit
                                }

                                PlaybackMenuRoute.SLEEP_TIMER -> onSelectSleepTimer(
                                    when (item.id) {
                                        "sleep_15" -> 15
                                        "sleep_30" -> 30
                                        "sleep_60" -> 60
                                        "sleep_90" -> 90
                                        "sleep_120" -> 120
                                        else -> null
                                    },
                                )

                                PlaybackMenuRoute.AUDIO_TRACKS -> {
                                    audioTracks.firstOrNull { "audio_${it.id}" == item.id }?.let { track ->
                                        onSelectAudioTrack(track.id)
                                    }
                                }

                                PlaybackMenuRoute.SUBTITLE_TRACKS -> {
                                    if (item.id == "subtitles_off") {
                                        onDisableSubtitles()
                                    } else {
                                        subtitleTracks.firstOrNull { "subtitle_${it.id}" == item.id }?.let { track ->
                                            onSelectSubtitleTrack(track.id)
                                        }
                                    }
                                }

                                PlaybackMenuRoute.DISPLAY_MODE -> {
                                    pictureFormats.firstOrNull { "display_${it.key}" == item.id }?.let { format ->
                                        onSelectPictureFormat(format.key)
                                    }
                                }

                                PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> {
                                    LiveAudioOutputMode.entries.firstOrNull { "output_${it.storageValue}" == item.id }?.let { mode ->
                                        onSelectAudioOutputMode(mode)
                                    }
                                }

                                PlaybackMenuRoute.BUFFER_SIZE -> {
                                    LiveBufferPreset.entries.firstOrNull { "buffer_${it.key}" == item.id }?.let { preset ->
                                        onSelectBufferSize(preset)
                                    }
                                }

                                PlaybackMenuRoute.PLAYBACK_INFO,
                                PlaybackMenuRoute.DECODER_INFO -> Unit
                            }
                        },
                    )
                }
            }
    }
}

@Composable
private fun PlaybackMenuShell(
    route: PlaybackMenuRoute,
    items: List<PlaybackMenuItem>,
    focusedItemId: String,
    firstFocus: FocusRequester,
    currentChannel: Channel?,
    isFavorite: Boolean,
    pictureFormats: List<LivePictureFormatOption>,
    selectedPictureFormatKey: String,
    audioTracks: List<TrackDescription>,
    subtitleTracks: List<TrackDescription>,
    playbackRuntimeInfo: PlaybackRuntimeInfo,
    sleepTimerMinutes: Int?,
    sleepTimerRemainingLabel: String?,
    selectedBufferPreset: LiveBufferPreset = LiveBufferPreset.HIGH,
    onFocusItem: (String) -> Unit,
    onSelectItem: (PlaybackMenuItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(start = 36.dp, top = 28.dp, bottom = 28.dp)
            .fillMaxHeight(0.9f),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(Obsidian.copy(alpha = 0.94f), RoundedCornerShape(20.dp))
                .border(1.dp, Amber.copy(alpha = 0.24f), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Text(
                text = menuTitle(route),
                color = Snow,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = menuSubtitle(route),
                color = Silver,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    PlaybackMenuButton(
                        item = item,
                        isSelected = focusedItemId == item.id,
                        focusRequester = if (items.firstOrNull()?.id == item.id) firstFocus else null,
                        onFocus = { onFocusItem(item.id) },
                        onClick = { onSelectItem(item) },
                    )
                }
            }
        }

        PlaybackMenuDetailPane(
            modifier = Modifier
                .width(540.dp)
                .fillMaxHeight()
                .background(Color(0xE8181E29), RoundedCornerShape(20.dp))
                .border(1.dp, Amber.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp),
            route = route,
            focusedItemId = focusedItemId,
            currentChannel = currentChannel,
            isFavorite = isFavorite,
            pictureFormats = pictureFormats,
            selectedPictureFormatKey = selectedPictureFormatKey,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            playbackRuntimeInfo = playbackRuntimeInfo,
            sleepTimerRemainingLabel = sleepTimerRemainingLabel,
            sleepTimerMinutes = sleepTimerMinutes,
            selectedBufferPreset = selectedBufferPreset,
        )
    }
}

@Composable
private fun PlaybackMenuButton(
    item: PlaybackMenuItem,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = item.enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) onFocus()
            }
            .height(64.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Amber.copy(alpha = 0.18f) else Graphite.copy(alpha = 0.88f),
            focusedContainerColor = Amber.copy(alpha = 0.26f),
            disabledContainerColor = Graphite.copy(alpha = 0.45f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, Amber),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                color = if (item.enabled) Snow else Silver.copy(alpha = 0.6f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Silver,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun PlaybackMenuDetailPane(
    modifier: Modifier,
    route: PlaybackMenuRoute,
    focusedItemId: String,
    currentChannel: Channel?,
    isFavorite: Boolean,
    pictureFormats: List<LivePictureFormatOption>,
    selectedPictureFormatKey: String,
    audioTracks: List<TrackDescription>,
    subtitleTracks: List<TrackDescription>,
    playbackRuntimeInfo: PlaybackRuntimeInfo,
    sleepTimerRemainingLabel: String?,
    sleepTimerMinutes: Int?,
    selectedBufferPreset: LiveBufferPreset = LiveBufferPreset.HIGH,
) {
    Column(modifier = modifier) {
        Text(
            text = detailTitle(route, focusedItemId),
            color = Snow,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))

        when (route) {
            PlaybackMenuRoute.MAIN -> {
                DetailParagraph(
                    text = when (focusedItemId) {
                        "channel_list" -> "Browse all channels and retune without interrupting playback."
                        "guide" -> "Open the current channel schedule first, then jump into the full live guide."
                        "playback_options" -> "Change audio track, subtitles, display mode, and view decoder details."
                        "channel_options" -> "Manage favorites, inspect the current channel, or reload the stream."
                        "sleep_timer" -> "Stop playback automatically after a selected delay."
                        "playback_info" -> "Inspect engine, codecs, decoder mode, output mode, and selected tracks."
                        "pip" -> "Enter picture-in-picture if the device supports it."
                        "multiview" -> "Multiview is not available in this build yet."
                        else -> "Use the Menu button for quick live-TV controls."
                    },
                )
                Spacer(Modifier.height(16.dp))
                DetailKv("Channel", currentChannel?.name ?: "Unknown")
                DetailKv("Engine", playbackRuntimeInfo.engineId.storageValue)
                DetailKv("Video Decoder", decoderLabel(playbackRuntimeInfo.videoDecoderKind, playbackRuntimeInfo.videoDecoderName))
                DetailKv("Audio Decoder", decoderLabel(playbackRuntimeInfo.audioDecoderKind, playbackRuntimeInfo.audioDecoderName))
                DetailKv("Selected Audio", playbackRuntimeInfo.selectedAudioTrack?.label ?: "None")
                DetailKv("Sleep Timer", sleepTimerRemainingLabel ?: "Off")
            }

            PlaybackMenuRoute.PLAYBACK_OPTIONS -> {
                DetailParagraph(text = "Playback options stay focused on live playback: track selection, display mode, audio output mode, and decoder visibility.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Audio Track", playbackRuntimeInfo.selectedAudioTrack?.label ?: "None")
                DetailKv("Subtitle", playbackRuntimeInfo.selectedSubtitleTrack?.label ?: "Off")
                DetailKv("Display", selectedPictureFormatLabel(pictureFormats, selectedPictureFormatKey))
                DetailKv("Audio Output", playbackRuntimeInfo.outputMode?.storageValue?.replace('_', ' ') ?: "Unknown")
            }

            PlaybackMenuRoute.CHANNEL_OPTIONS -> {
                DetailParagraph(text = "These actions apply to the current channel only.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Channel", currentChannel?.name ?: "Unknown")
                DetailKv("Group", currentChannel?.groupTitle ?: "Unknown")
                DetailKv("Favorite", if (isFavorite) "Yes" else "No")
                DetailKv("Stream", if (currentChannel?.url.isNullOrBlank()) "Unavailable" else "Available")
            }

            PlaybackMenuRoute.SLEEP_TIMER -> {
                DetailParagraph(text = "Choose when live playback should stop automatically.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Current Timer", sleepTimerRemainingLabel ?: "Off")
                DetailKv("Selection", sleepTimerMinutes?.let { "$it min" } ?: "Off")
            }

            PlaybackMenuRoute.AUDIO_TRACKS -> {
                val track = audioTracks.firstOrNull { "audio_${it.id}" == focusedItemId } ?: playbackRuntimeInfo.selectedAudioTrack
                DetailTrackCard(track = track, emptyLabel = "No audio track selected")
            }

            PlaybackMenuRoute.SUBTITLE_TRACKS -> {
                val track = subtitleTracks.firstOrNull { "subtitle_${it.id}" == focusedItemId } ?: playbackRuntimeInfo.selectedSubtitleTrack
                DetailTrackCard(track = track, emptyLabel = "Subtitles off")
            }

            PlaybackMenuRoute.DISPLAY_MODE -> {
                DetailParagraph(text = "Change how the video surface fits the screen without interrupting live playback.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Current Mode", selectedPictureFormatLabel(pictureFormats, selectedPictureFormatKey))
            }

            PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> {
                DetailParagraph(text = "Hardware decoding remains the default first choice. Audio output mode only changes how Torve handles compatibility and output routing.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Output Mode", playbackRuntimeInfo.outputMode?.storageValue?.replace('_', ' ') ?: "Unknown")
                DetailKv("Passthrough", if (playbackRuntimeInfo.passthroughEnabled) "On" else "Off")
                DetailKv("Prefer Surround", if (playbackRuntimeInfo.preferSurround) "On" else "Off")
            }

            PlaybackMenuRoute.BUFFER_SIZE -> {
                val focused = LiveBufferPreset.entries.firstOrNull { "buffer_${it.key}" == focusedItemId }
                DetailParagraph(text = focused?.description ?: "Choose how much content to buffer ahead of playback. Lower values reduce repeat-glitches on some channels.")
                Spacer(Modifier.height(14.dp))
                DetailKv("Current Buffer", selectedBufferPreset.label)
            }

            PlaybackMenuRoute.PLAYBACK_INFO,
            PlaybackMenuRoute.DECODER_INFO -> Unit
        }
    }
}

@Composable
private fun PlaybackInfoPane(
    modifier: Modifier,
    title: String,
    playbackRuntimeInfo: PlaybackRuntimeInfo,
    currentChannel: Channel?,
    sleepTimerRemainingLabel: String?,
) {
    Column(
        modifier = modifier
            .background(Color(0xE8181E29), RoundedCornerShape(22.dp))
            .border(1.dp, Amber.copy(alpha = 0.2f), RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Text(
            text = title,
            color = Snow,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("channel") { DetailKv("Channel", currentChannel?.name ?: "Unknown") }
            item("engine") { DetailKv("Player Engine", playbackRuntimeInfo.engineId.storageValue) }
            item("video_codec") { DetailKv("Video Codec", playbackRuntimeInfo.videoCodec ?: "Unknown") }
            item("audio_codec") { DetailKv("Audio Codec", playbackRuntimeInfo.audioCodec ?: "Unknown") }
            item("video_decoder") {
                DetailKv("Video Decoder", decoderLabel(playbackRuntimeInfo.videoDecoderKind, playbackRuntimeInfo.videoDecoderName))
            }
            item("audio_decoder") {
                DetailKv("Audio Decoder", decoderLabel(playbackRuntimeInfo.audioDecoderKind, playbackRuntimeInfo.audioDecoderName))
            }
            item("resolution") { DetailKv("Resolution", playbackRuntimeInfo.resolutionLabel ?: "Unknown") }
            item("fps") { DetailKv("FPS", playbackRuntimeInfo.frameRate?.let { String.format("%.2f", it) } ?: "Unknown") }
            item("audio_track") { DetailKv("Audio Track", playbackRuntimeInfo.selectedAudioTrack?.let(::buildTrackSubtitle) ?: "None") }
            item("subtitle_track") { DetailKv("Subtitle Track", playbackRuntimeInfo.selectedSubtitleTrack?.let(::buildTrackSubtitle) ?: "Off") }
            item("output_mode") { DetailKv("Audio Output Mode", playbackRuntimeInfo.outputMode?.storageValue?.replace('_', ' ') ?: "Unknown") }
            item("passthrough") { DetailKv("Passthrough", if (playbackRuntimeInfo.passthroughEnabled) "On" else "Off") }
            item("fallback") { DetailKv("Fallback From Hardware Default", if (playbackRuntimeInfo.fallbackFromHardwareDefault) "Yes" else "No") }
            item("sleep") { DetailKv("Sleep Timer", sleepTimerRemainingLabel ?: "Off") }
        }
    }
}

@Composable
private fun DetailParagraph(text: String) {
    Text(
        text = text,
        color = Silver,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

@Composable
private fun DetailKv(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Graphite.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = label, color = Silver, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(text = value, color = Snow, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailTrackCard(track: TrackDescription?, emptyLabel: String) {
    if (track == null) {
        DetailParagraph(text = emptyLabel)
        return
    }
    DetailKv("Label", track.label)
    Spacer(Modifier.height(10.dp))
    DetailKv("Language", track.language ?: "Unknown")
    Spacer(Modifier.height(10.dp))
    DetailKv("Codec", track.formatHint ?: "Unknown")
    Spacer(Modifier.height(10.dp))
    DetailKv("Channels", track.channelCount?.toString() ?: "Unknown")
    Spacer(Modifier.height(10.dp))
    DetailKv("Selected", if (track.isSelected) "Yes" else "No")
}

private fun menuTitle(route: PlaybackMenuRoute): String = when (route) {
    PlaybackMenuRoute.MAIN -> "Playback Menu"
    PlaybackMenuRoute.PLAYBACK_OPTIONS -> "Playback Options"
    PlaybackMenuRoute.AUDIO_TRACKS -> "Audio Track"
    PlaybackMenuRoute.SUBTITLE_TRACKS -> "Subtitle Track"
    PlaybackMenuRoute.DISPLAY_MODE -> "Aspect Ratio / Display"
    PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> "Audio Output Mode"
    PlaybackMenuRoute.BUFFER_SIZE -> "Buffer Size"
    PlaybackMenuRoute.DECODER_INFO -> "Decoder Info"
    PlaybackMenuRoute.CHANNEL_OPTIONS -> "Channel Options"
    PlaybackMenuRoute.SLEEP_TIMER -> "Sleep Timer"
    PlaybackMenuRoute.PLAYBACK_INFO -> "Playback Info"
}

private fun menuSubtitle(route: PlaybackMenuRoute): String = when (route) {
    PlaybackMenuRoute.MAIN -> "Live playback hub"
    PlaybackMenuRoute.PLAYBACK_OPTIONS -> "Tracks, display, output, decoder info"
    PlaybackMenuRoute.AUDIO_TRACKS -> "Select a live audio track manually"
    PlaybackMenuRoute.SUBTITLE_TRACKS -> "Select subtitles or turn them off"
    PlaybackMenuRoute.DISPLAY_MODE -> "Choose how video fits the screen"
    PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> "Change live audio compatibility mode"
    PlaybackMenuRoute.BUFFER_SIZE -> "Adjust live stream buffering"
    PlaybackMenuRoute.DECODER_INFO -> "Inspect hardware vs software decode"
    PlaybackMenuRoute.CHANNEL_OPTIONS -> "Actions for the current channel"
    PlaybackMenuRoute.SLEEP_TIMER -> "Stop playback later"
    PlaybackMenuRoute.PLAYBACK_INFO -> "Current runtime playback details"
}

private fun detailTitle(route: PlaybackMenuRoute, focusedItemId: String): String {
    return when (route) {
        PlaybackMenuRoute.MAIN -> "Live TV Controls"
        PlaybackMenuRoute.PLAYBACK_OPTIONS -> "Playback Controls"
        PlaybackMenuRoute.CHANNEL_OPTIONS -> "Channel Actions"
        PlaybackMenuRoute.SLEEP_TIMER -> "Sleep Timer"
        PlaybackMenuRoute.AUDIO_TRACKS -> if (focusedItemId.isBlank()) "Audio Tracks" else "Track Details"
        PlaybackMenuRoute.SUBTITLE_TRACKS -> if (focusedItemId == "subtitles_off") "Subtitles Off" else "Subtitle Details"
        PlaybackMenuRoute.DISPLAY_MODE -> "Display Mode"
        PlaybackMenuRoute.AUDIO_OUTPUT_MODE -> "Audio Output"
        PlaybackMenuRoute.BUFFER_SIZE -> "Buffer Size"
        PlaybackMenuRoute.DECODER_INFO -> "Decoder Info"
        PlaybackMenuRoute.PLAYBACK_INFO -> "Playback Info"
    }
}

private fun selectedPictureFormatLabel(
    pictureFormats: List<LivePictureFormatOption>,
    selectedKey: String,
): String {
    return pictureFormats.firstOrNull { it.key == selectedKey }?.label ?: "Auto"
}

private fun selectedAudioSummary(audioTracks: List<TrackDescription>): String {
    return audioTracks.firstOrNull { it.isSelected }?.let(::buildTrackSubtitle)
        ?: if (audioTracks.isEmpty()) "No audio tracks" else "Choose track"
}

private fun selectedSubtitleSummary(subtitleTracks: List<TrackDescription>): String {
    return subtitleTracks.firstOrNull { it.isSelected }?.let(::buildTrackSubtitle)
        ?: if (subtitleTracks.isEmpty()) "Off" else "Choose subtitle"
}

private fun buildTrackSubtitle(track: TrackDescription): String {
    return listOfNotNull(
        track.language?.takeIf { it.isNotBlank() },
        track.formatHint?.takeIf { it.isNotBlank() },
        track.channelCount?.let { "${it}ch" },
    ).joinToString(separator = " • ").ifBlank { track.label }
}

private fun decoderSummary(info: PlaybackRuntimeInfo): String {
    return "${decoderLabel(info.videoDecoderKind, info.videoDecoderName)} / ${decoderLabel(info.audioDecoderKind, info.audioDecoderName)}"
}

private fun decoderLabel(kind: DecoderKind, decoderName: String?): String {
    val prefix = when (kind) {
        DecoderKind.HARDWARE -> "Hardware"
        DecoderKind.SOFTWARE -> "Software"
        DecoderKind.UNKNOWN -> "Unknown"
    }
    return decoderName?.takeIf { it.isNotBlank() }?.let { "$prefix ($it)" } ?: prefix
}
