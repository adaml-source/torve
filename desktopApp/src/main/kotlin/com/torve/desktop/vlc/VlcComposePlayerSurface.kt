package com.torve.desktop.vlc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.torve.desktop.playback.DesktopPlaybackHotkeyAction
import com.torve.desktop.playback.bindingFor
import com.torve.desktop.playback.toAwtPlaybackKeyCode
import com.torve.desktop.playback.toComposePlaybackKey
import com.torve.desktop.ui.l10n.ds
import com.torve.domain.player.DesktopPlaybackHotkeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val Accent = Color(0xFFE8A838)
private val ChromeBg = Color(0xD0060810)
private val PanelBg = Color(0xF0101420)
private val PanelBorder = Color(0xFF2B3448)
private val ChipBg = Color(0x30FFFFFF)
private val ChipBgActive = Color(0xFFE8A838)

private data class PlayerText(
    val play: String,
    val pause: String,
    val resume: String,
    val stop: String,
    val seekBack: String,
    val seekForward: String,
    val jumpToTime: String,
    val playbackSpeed: String,
    val audio: String,
    val audioTrack: String,
    val audioDevice: String,
    val audioDelay: String,
    val audioSyncPanel: String,
    val equalizerPanel: String,
    val subtitles: String,
    val subtitleTrack: String,
    val subtitleDelay: String,
    val subtitleSyncPanel: String,
    val off: String,
    val video: String,
    val fullscreen: String,
    val exitFullscreen: String,
    val alwaysFitWindow: String,
    val aspectRatio: String,
    val videoTrack: String,
    val takeSnapshot: String,
    val tools: String,
    val mediaInformation: String,
    val codecInformation: String,
    val playbackDiagnostics: String,
    val synchronizationAndEffects: String,
    val window: String,
    val alwaysOnTop: String,
    val showPlaybackControls: String,
    val resetPlayerWindowLayout: String,
    val closePlayer: String,
    val volumeUp: String,
    val volumeDown: String,
    val mute: String,
    val loadExternalSubtitleFile: String,
    val loadSubtitle: String,
    val previousChannel: String,
    val nextChannel: String,
    val noSubtitleTracksAvailable: String,
    val noTracks: String,
    val noAudioTracks: String,
    val searchOnline: String,
    val speed: String,
    val panels: String,
    val audioSync: String,
    val subtitleSync: String,
    val equalizer: String,
    val mediaInfo: String,
    val audioSyncEllipsis: String,
    val subtitleSyncEllipsis: String,
    val equalizerEllipsis: String,
    val mediaInfoEllipsis: String,
    val castTo: String,
    val castUnavailable: String,
    val searchingForDevices: String,
    val stopCasting: String,
    val videoModeTemplate: String,
    val subtitlesTemplate: String,
    val bufferingTemplate: String,
    val loadedSubtitleTemplate: String,
    val snapshotSavedTemplate: String,
    val snapshotFailed: String,
    val audioOutputSwitchedTemplate: String,
    val audioOutputSwitchFailed: String,
)

private enum class AdvancedPanel {
    EQUALIZER,
    AUDIO_SYNC,
    SUBTITLE_SYNC,
    MEDIA_INFO,
    CODEC_INFO,
    PLAYBACK_DIAGNOSTICS,
    SYNC_EFFECTS,
    JUMP_TO_TIME,
}

private data class AspectOption(val label: String, val ratio: String?, val crop: String?, val scale: Float?)

private val aspectOptions = listOf(
    AspectOption("Default", null, null, 0f),
    AspectOption("16:9", "16:9", null, 0f),
    AspectOption("4:3", "4:3", null, 0f),
    AspectOption("21:9", "21:9", null, 0f),
    AspectOption("Fit to Source", null, null, 1f),
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun VlcComposePlayerSurface(
    engine: VlcPlaybackEngine,
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    onStop: () -> Unit,
    channelNavigationEnabled: Boolean = false,
    onPreviousChannel: (() -> Unit)? = null,
    onNextChannel: (() -> Unit)? = null,
    /**
     * Live recording controls for the in-player record button. When
     * onToggleRecord is provided, the chrome shows a record / stop
     * button that toggles based on isCurrentlyRecording. Both null = no
     * record button (e.g. VOD playback where recording doesn't apply).
     */
    isCurrentlyRecording: Boolean = false,
    onToggleRecord: (() -> Unit)? = null,
    windowState: WindowState? = null,
    seekStepMs: Long = 10_000L,
    initialVolume: Int? = null,
    preferredAudioLanguage: String? = null,
    preferredSubtitleLanguage: String? = null,
    subtitlesEnabledByDefault: Boolean = false,
    onVolumeChanged: ((Int) -> Unit)? = null,
    onMinimizeToPip: (() -> Unit)? = null,
    onSearchOnlineSubtitles: (() -> Unit)? = null,
    castController: com.torve.desktop.cast.DesktopCastController? = null,
    hotkeys: DesktopPlaybackHotkeys = DesktopPlaybackHotkeys(),
    modifier: Modifier = Modifier,
) {
    val sessionStateFlow = engine.sessionState
    val sessionState by (sessionStateFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(VlcSessionState()) }).collectAsState()

    var chromeVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val focusRequester = remember { FocusRequester() }
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastFrameCount by remember { mutableLongStateOf(0L) }

    var showSubtitleMenu by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var contextMenuContainerSize by remember { mutableStateOf(IntSize.Zero) }
    var activePanel by remember { mutableStateOf<AdvancedPanel?>(null) }
    var selectedAspect by remember { mutableStateOf("Default") }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var chromePinnedVisible by remember { mutableStateOf(false) }
    var alwaysOnTop by remember { mutableStateOf(false) }
    val playerText = PlayerText(
        play = ds("Play"),
        pause = ds("Pause"),
        resume = ds("Resume"),
        stop = ds("Stop"),
        seekBack = ds("Seek Back %1\$d s").format(seekStepMs / 1000),
        seekForward = ds("Seek Forward %1\$d s").format(seekStepMs / 1000),
        jumpToTime = ds("Jump to Time"),
        playbackSpeed = ds("Playback Speed"),
        audio = ds("Audio"),
        audioTrack = ds("Audio Track"),
        audioDevice = ds("Audio Device"),
        audioDelay = ds("Audio Delay"),
        audioSyncPanel = ds("Audio Sync Panel"),
        equalizerPanel = ds("Equalizer Panel"),
        subtitles = ds("Subtitles"),
        subtitleTrack = ds("Subtitle Track"),
        subtitleDelay = ds("Subtitle Delay"),
        subtitleSyncPanel = ds("Subtitle Sync Panel"),
        off = ds("Off"),
        video = ds("Video"),
        fullscreen = ds("Fullscreen"),
        exitFullscreen = ds("Exit Fullscreen"),
        alwaysFitWindow = ds("Always Fit Window"),
        aspectRatio = ds("Aspect Ratio"),
        videoTrack = ds("Video Track"),
        takeSnapshot = ds("Take Snapshot"),
        tools = ds("Tools"),
        mediaInformation = ds("Media Information"),
        codecInformation = ds("Codec Information"),
        playbackDiagnostics = ds("Playback Diagnostics"),
        synchronizationAndEffects = ds("Synchronization and Effects"),
        window = ds("Window"),
        alwaysOnTop = ds("Always on Top"),
        showPlaybackControls = ds("Show Playback Controls"),
        resetPlayerWindowLayout = ds("Reset Player Window Layout"),
        closePlayer = ds("Close Player"),
        volumeUp = ds("Volume Up"),
        volumeDown = ds("Volume Down"),
        mute = ds("Mute"),
        loadExternalSubtitleFile = ds("Load External Subtitle File"),
        loadSubtitle = ds("Load Subtitle"),
        previousChannel = ds("Previous channel"),
        nextChannel = ds("Next channel"),
        noSubtitleTracksAvailable = ds("No subtitle tracks available"),
        noTracks = ds("No tracks"),
        noAudioTracks = ds("No audio tracks"),
        searchOnline = ds("Search online..."),
        speed = ds("Speed"),
        panels = ds("Panels"),
        audioSync = ds("Audio Sync"),
        subtitleSync = ds("Subtitle Sync"),
        equalizer = ds("Equalizer"),
        mediaInfo = ds("Media Info"),
        audioSyncEllipsis = ds("Audio Sync..."),
        subtitleSyncEllipsis = ds("Subtitle Sync..."),
        equalizerEllipsis = ds("Equalizer..."),
        mediaInfoEllipsis = ds("Media Info..."),
        castTo = ds("Cast to"),
        castUnavailable = ds("Cast not available in this VLC build"),
        searchingForDevices = ds("Searching for devices..."),
        stopCasting = ds("Stop casting"),
        videoModeTemplate = ds("Video mode: %1\$s"),
        subtitlesTemplate = ds("Subtitles: %1\$s"),
        bufferingTemplate = ds("Buffering %1\$d%%"),
        loadedSubtitleTemplate = ds("Loaded subtitle %1\$s"),
        snapshotSavedTemplate = ds("Snapshot saved to %1\$s"),
        snapshotFailed = ds("Snapshot failed"),
        audioOutputSwitchedTemplate = ds("Audio output switched to %1\$s"),
        audioOutputSwitchFailed = ds("Audio output switch failed"),
    )

    // Fix 1: Frame polling reads engine.frameRenderer fresh each tick
    LaunchedEffect(engine) {
        while (isActive) {
            val renderer = engine.frameRenderer
            if (renderer != null && renderer.frameCount != lastFrameCount) {
                currentFrame = renderer.latestFrame()
                lastFrameCount = renderer.frameCount
            }
            delay(16)
        }
    }

    // Auto-hide chrome after 3s while playing
    LaunchedEffect(lastInteractionTime, sessionState.isPlaying) {
        if (sessionState.isPlaying && !sessionState.isBuffering) {
            delay(3000)
            if (!showSubtitleMenu && !showSettingsMenu && !showAspectMenu && !showContextMenu && activePanel == null && !chromePinnedVisible) {
                chromeVisible = false
            }
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2400)
            toastMessage = null
        }
    }

    LaunchedEffect(isHovered) {
        if (isHovered) { chromeVisible = true; lastInteractionTime = System.currentTimeMillis() }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        alwaysOnTop = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.isAlwaysOnTop == true
    }

    // Subscribe to subtitle drops while the player surface is mounted. The
    // AWT DropTarget on the JFrame fires through SubtitleDropBus; we attach
    // the dropped path to the live VLC session.
    androidx.compose.runtime.DisposableEffect(engine) {
        val handle = com.torve.desktop.dnd.SubtitleDropBus.subscribe { droppedPath ->
            val session = engine.session ?: return@subscribe
            val uri = java.io.File(droppedPath).toURI().toString()
            kotlinx.coroutines.runBlocking {
                runCatching {
                    session.addSubtitleFile(uri)
                    session.refreshTracks()
                }
            }
            toastMessage = playerText.loadedSubtitleTemplate.format(java.io.File(droppedPath).name)
        }
        onDispose { handle.unsubscribe() }
    }

    // Apply initial volume from settings
    var volumeApplied by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState.isPlaying, initialVolume) {
        if (sessionState.isPlaying && !volumeApplied && initialVolume != null) {
            engine.session?.let { s ->
                kotlinx.coroutines.runBlocking { s.setVolume(initialVolume) }
            }
            volumeApplied = true
        }
    }

    // Auto-select preferred audio track when tracks become available
    var audioTrackApplied by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState.availableAudioTracks) {
        if (!audioTrackApplied && sessionState.availableAudioTracks.size > 1 && !preferredAudioLanguage.isNullOrBlank()) {
            val lang = preferredAudioLanguage.lowercase()
            val match = sessionState.availableAudioTracks.firstOrNull { track ->
                track.name.lowercase().contains(lang) ||
                    track.language?.lowercase()?.contains(lang) == true
            }
            if (match != null && sessionState.selectedAudioTrack?.id != match.id) {
                engine.session?.let { s ->
                    kotlinx.coroutines.runBlocking {
                        s.selectAudioTrack(match.id)
                        s.refreshTracks()
                    }
                }
            }
            audioTrackApplied = true
        }
    }

    // Auto-select preferred subtitle track or disable subtitles
    var subtitleTrackApplied by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState.availableSubtitleTracks) {
        if (!subtitleTrackApplied && sessionState.availableSubtitleTracks.isNotEmpty()) {
            if (!subtitlesEnabledByDefault) {
                engine.session?.let { s ->
                    kotlinx.coroutines.runBlocking {
                        s.disableSubtitles()
                        s.refreshTracks()
                    }
                }
            } else if (!preferredSubtitleLanguage.isNullOrBlank()) {
                val lang = preferredSubtitleLanguage.lowercase()
                val match = sessionState.availableSubtitleTracks.firstOrNull { track ->
                    track.name.lowercase().contains(lang) ||
                        track.language?.lowercase()?.contains(lang) == true
                }
                if (match != null) {
                    engine.session?.let { s ->
                        kotlinx.coroutines.runBlocking {
                            s.selectSubtitleTrack(match.id)
                            s.refreshTracks()
                        }
                    }
                }
            }
            subtitleTrackApplied = true
        }
    }

    fun onInteraction() { chromeVisible = true; lastInteractionTime = System.currentTimeMillis() }

    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen
    fun activeWindow() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    fun showToast(message: String) { toastMessage = message; onInteraction() }
    fun applyAspect(option: AspectOption) {
        selectedAspect = option.label
        // Aspect ratio is enforced at the Compose drawing layer, not via vlcj API,
        // because callback rendering mode (vmem) ignores vlcj aspect ratio calls.
    }
    fun cycleVideoMode() {
        val currentIndex = aspectOptions.indexOfFirst { it.label == selectedAspect }.coerceAtLeast(0)
        val next = aspectOptions[(currentIndex + 1) % aspectOptions.size]
        applyAspect(next)
        showToast(playerText.videoModeTemplate.format(next.label))
    }
    fun cycleSubtitleTrack() {
        val tracks = sessionState.availableSubtitleTracks
        val session = engine.session
        if (session == null || tracks.isEmpty()) {
            showToast(playerText.noSubtitleTracksAvailable)
            return
        }
        val selectedIndex = tracks.indexOfFirst { it.id == sessionState.selectedSubtitleTrack?.id }
        kotlinx.coroutines.runBlocking {
            if (selectedIndex < 0) {
                session.selectSubtitleTrack(tracks.first().id)
            } else if (selectedIndex >= tracks.lastIndex) {
                session.disableSubtitles()
            } else {
                session.selectSubtitleTrack(tracks[selectedIndex + 1].id)
            }
            session.refreshTracks()
        }
        val nextLabel = when {
            selectedIndex < 0 -> tracks.first().name
            selectedIndex >= tracks.lastIndex -> playerText.off
            else -> tracks[selectedIndex + 1].name
        }
        showToast(playerText.subtitlesTemplate.format(nextLabel))
    }
    fun matchesHotkey(
        event: androidx.compose.ui.input.key.KeyEvent,
        action: DesktopPlaybackHotkeyAction,
    ): Boolean = hotkeys.bindingFor(action).toComposePlaybackKey()?.let { event.key == it } == true
    fun toggleFullscreen() {
        // DISABLED 2026-05-05: was toggling the MAIN Compose Window's
        // WindowState.placement between Fullscreen and Floating. But the
        // main Window is created with undecorated=true (the
        // loginFullscreenPreview path, default true), so going
        // Fullscreen→Floating produced a small, undraggable,
        // unmaximizable, undecorated window that the user could only
        // recover from by force-closing the app.
        //
        // The player surface is already covering the entire main
        // window, so a per-player fullscreen toggle isn't needed --
        // the app is effectively always-fullscreen anyway. F key
        // pressed inside the player is now a no-op; ESC closes the
        // player surface (handled in the key dispatcher).
        onInteraction()
    }

    fun updateAudioDelay(deltaMs: Int) {
        engine.session?.let { session ->
            val nextDelayUs = sessionState.audioDelayUs + deltaMs * 1000L
            kotlinx.coroutines.runBlocking { session.setAudioDelay(nextDelayUs) }
        }
    }

    fun updateSubtitleDelay(deltaMs: Int) {
        engine.session?.let { session ->
            val nextDelayUs = sessionState.subtitleDelayUs + deltaMs * 1000L
            kotlinx.coroutines.runBlocking { session.setSubtitleDelay(nextDelayUs) }
        }
    }

    fun loadExternalSubtitle() {
        val owner = activeWindow() as? Frame
        val chooser = FileDialog(owner, playerText.loadSubtitle, FileDialog.LOAD).apply {
            isMultipleMode = false
            file = "*.srt;*.ass;*.ssa;*.vtt;*.sub"
        }
        chooser.isVisible = true
        val fileName = chooser.file ?: return
        val directory = chooser.directory ?: return
        val subtitlePath = Path.of(directory, fileName).toAbsolutePath()
        engine.session?.let { session ->
            kotlinx.coroutines.runBlocking {
                session.addSubtitleFile(subtitlePath.toUri().toString())
                session.refreshTracks()
            }
            showToast(playerText.loadedSubtitleTemplate.format(subtitlePath.fileName))
        }
    }

    fun takeSnapshot() {
        val snapshotDirectory = Path.of(System.getProperty("user.home"), "Pictures", "Torve", "Snapshots")
        Files.createDirectories(snapshotDirectory)
        val snapshotPath = snapshotDirectory.resolve(
            "torve-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
        )
        val saved = engine.session?.let { session ->
            kotlinx.coroutines.runBlocking { session.takeSnapshot(snapshotPath.toString()) }
        } ?: false
        showToast(
            if (saved) playerText.snapshotSavedTemplate.format(snapshotPath) else playerText.snapshotFailed
        )
    }

    fun toggleAlwaysOnTop() {
        val window = activeWindow() ?: return
        val next = !window.isAlwaysOnTop
        window.isAlwaysOnTop = next
        alwaysOnTop = window.isAlwaysOnTop
        showToast(if (alwaysOnTop) "Always on top enabled" else "Always on top disabled")
    }

    fun switchAudioDevice(device: VlcAudioDevice) {
        val switched = engine.session?.let { session ->
            kotlinx.coroutines.runBlocking { session.switchAudioDevice(device.outputName, device.deviceId) }
        } ?: false
        showToast(
            if (switched) playerText.audioOutputSwitchedTemplate.format(device.deviceLabel) else playerText.audioOutputSwitchFailed
        )
    }

    val diagnostics = engine.readDiagnostics()
    val currentAudioDeviceId = engine.session?.currentAudioDeviceId()
    val currentAudioDeviceLabel = engine.session
        ?.availableAudioDevices()
        ?.firstOrNull { it.deviceId == currentAudioDeviceId }
        ?.deviceLabel

    val contextMenuItems = buildList<PlayerMenuNode> {
        add(
            ActionItem(
                id = "pause_resume",
                label = if (sessionState.isPlaying) playerText.pause else if (sessionState.isPaused) playerText.resume else playerText.play,
                enabled = sessionState.canPause || sessionState.isPaused || sessionState.isPlaying,
            ) {
                kotlinx.coroutines.runBlocking {
                    if (engine.isPlaying()) engine.pause() else engine.play()
                }
            }
        )
        add(
            ActionItem(
                id = "stop",
                label = playerText.stop,
                enabled = sessionState.isPlaying || sessionState.isPaused || sessionState.positionMs > 0,
            ) {
                onStop()
            }
        )
        add(SeparatorItem("transport_sep"))
        add(ActionItem("seek_back", playerText.seekBack, enabled = sessionState.canSeek) {
            engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.seekRelative(-seekStepMs) } }
        })
        add(ActionItem("seek_forward", playerText.seekForward, enabled = sessionState.canSeek) {
            engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.seekRelative(seekStepMs) } }
        })
        add(ActionItem("jump_to_time", playerText.jumpToTime, hint = fmt(sessionState.positionMs), enabled = sessionState.canSeek && sessionState.durationMs > 0) {
            activePanel = AdvancedPanel.JUMP_TO_TIME
        })
        add(SeparatorItem("navigation_sep"))
        add(
            SubmenuItem(
                id = "playback_speed",
                label = playerText.playbackSpeed,
                hint = speedLabel(sessionState.playbackRate),
                enabled = true,
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { speed ->
                    ChoiceGroupItem(
                        id = "speed_$speed",
                        label = speedLabel(speed),
                        selected = abs(sessionState.playbackRate - speed) < 0.01f,
                    ) {
                        engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.setRate(speed) } }
                    }
                }
            }
        )
        add(
            SubmenuItem(id = "audio", label = playerText.audio, hint = sessionState.selectedAudioTrack?.name, enabled = sessionState.hasAudio) {
                buildList {
                    add(
                        SubmenuItem(
                            id = "audio_track",
                            label = playerText.audioTrack,
                            hint = sessionState.selectedAudioTrack?.name,
                            enabled = sessionState.availableAudioTracks.isNotEmpty() || sessionState.canDisableAudioTrack,
                        ) {
                            buildList {
                                if (sessionState.canDisableAudioTrack) {
                                    add(ChoiceGroupItem("audio_track_off", playerText.off, selected = sessionState.selectedAudioTrack == null) {
                                        engine.session?.let { session ->
                                            kotlinx.coroutines.runBlocking {
                                                session.disableAudioTrack()
                                                session.refreshTracks()
                                            }
                                        }
                                    })
                                }
                                addAll(sessionState.availableAudioTracks.map { track ->
                                    ChoiceGroupItem(
                                        id = "audio_track_${track.id}",
                                        label = formatAudioTrackLabel(track),
                                        selected = sessionState.selectedAudioTrack?.id == track.id,
                                    ) {
                                        engine.session?.let { session ->
                                            kotlinx.coroutines.runBlocking {
                                                session.selectAudioTrack(track.id)
                                                session.refreshTracks()
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    )
                    add(
                        SubmenuItem(
                            id = "audio_device",
                            label = playerText.audioDevice,
                            enabled = engine.session?.availableAudioDevices()?.isNotEmpty() == true,
                        ) {
                            engine.session?.availableAudioDevices()?.map { device ->
                                ChoiceGroupItem(
                                    id = "audio_device_${device.outputName}_${device.deviceId}",
                                    label = device.deviceLabel,
                                    selected = device.deviceId == currentAudioDeviceId,
                                ) {
                                    switchAudioDevice(device)
                                }
                            } ?: emptyList()
                        }
                    )
                    add(SeparatorItem("audio_sep_1"))
                    add(ActionItem("volume_up", playerText.volumeUp, hint = "+5", enabled = true) {
                        engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.setVolume(engine.getVolume() + 5) } }
                    })
                    add(ActionItem("volume_down", playerText.volumeDown, hint = "-5", enabled = true) {
                        engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.setVolume(engine.getVolume() - 5) } }
                    })
                    add(ToggleItem("mute", playerText.mute, checked = sessionState.isMuted, enabled = true) {
                        engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.setMute(!engine.isMuted()) } }
                    })
                    add(SeparatorItem("audio_sep_2"))
                    add(
                        SubmenuItem(
                            id = "audio_delay",
                            label = playerText.audioDelay,
                            hint = delayHint(sessionState.audioDelayUs),
                            enabled = true,
                        ) {
                            delayAdjustmentItems("audio_delay") { deltaMs -> updateAudioDelay(deltaMs) }
                        }
                    )
                    add(ActionItem("audio_sync_panel", playerText.audioSyncPanel) { activePanel = AdvancedPanel.AUDIO_SYNC })
                    add(ActionItem("equalizer_panel", playerText.equalizerPanel) { activePanel = AdvancedPanel.EQUALIZER })
                }
            }
        )
        add(
            SubmenuItem(
                id = "subtitles",
                label = playerText.subtitles,
                hint = sessionState.selectedSubtitleTrack?.name ?: playerText.off,
                enabled = true,
            ) {
                buildList {
                    add(
                        SubmenuItem(
                            id = "subtitle_track",
                            label = playerText.subtitleTrack,
                            hint = sessionState.selectedSubtitleTrack?.name ?: playerText.off,
                            enabled = true,
                        ) {
                            buildList {
                                add(ChoiceGroupItem("subtitle_off", playerText.off, selected = sessionState.selectedSubtitleTrack == null) {
                                    engine.session?.let { session ->
                                        kotlinx.coroutines.runBlocking {
                                            session.disableSubtitles()
                                            session.refreshTracks()
                                        }
                                    }
                                })
                                addAll(sessionState.availableSubtitleTracks.map { track ->
                                    ChoiceGroupItem(
                                        id = "subtitle_track_${track.id}",
                                        label = track.name,
                                        selected = sessionState.selectedSubtitleTrack?.id == track.id,
                                    ) {
                                        engine.session?.let { session ->
                                            kotlinx.coroutines.runBlocking {
                                                session.selectSubtitleTrack(track.id)
                                                session.refreshTracks()
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    )
                    add(ActionItem("load_external_subtitle", playerText.loadExternalSubtitleFile, enabled = engine.session != null) {
                        loadExternalSubtitle()
                    })
                    add(SeparatorItem("subtitle_sep"))
                    add(
                        SubmenuItem(
                            id = "subtitle_delay",
                            label = playerText.subtitleDelay,
                            hint = delayHint(sessionState.subtitleDelayUs),
                        ) {
                            delayAdjustmentItems("subtitle_delay") { deltaMs -> updateSubtitleDelay(deltaMs) }
                        }
                    )
                    add(ActionItem("subtitle_sync_panel", playerText.subtitleSyncPanel) { activePanel = AdvancedPanel.SUBTITLE_SYNC })
                }
            }
        )
        add(
            SubmenuItem(id = "video", label = playerText.video, hint = if (isFullscreen) playerText.fullscreen else selectedAspect, enabled = sessionState.hasVideo) {
                buildList {
                    add(ActionItem("fullscreen", if (isFullscreen) playerText.exitFullscreen else playerText.fullscreen) { toggleFullscreen() })
                    add(ToggleItem("fit_window", playerText.alwaysFitWindow, checked = selectedAspect == "Default") {
                        applyAspect(aspectOptions.first { it.label == "Default" })
                    })
                    add(
                        SubmenuItem(id = "aspect_ratio", label = playerText.aspectRatio, hint = selectedAspect) {
                            aspectOptions.map { option ->
                                ChoiceGroupItem(
                                    id = "aspect_${option.label}",
                                    label = option.label,
                                    selected = selectedAspect == option.label,
                                ) {
                                    applyAspect(option)
                                }
                            }
                        }
                    )
                    add(SeparatorItem("video_sep"))
                    if (sessionState.availableVideoTracks.size > 1 || sessionState.canDisableVideoTrack) {
                        add(
                            SubmenuItem(
                                id = "video_track",
                            label = playerText.videoTrack,
                                hint = sessionState.selectedVideoTrack?.name,
                                enabled = sessionState.availableVideoTracks.isNotEmpty() || sessionState.canDisableVideoTrack,
                            ) {
                                buildList {
                                    if (sessionState.canDisableVideoTrack) {
                                        add(ChoiceGroupItem("video_track_off", playerText.off, selected = sessionState.selectedVideoTrack == null) {
                                            engine.session?.let { session ->
                                                kotlinx.coroutines.runBlocking {
                                                    session.selectVideoTrack(-1)
                                                    session.refreshTracks()
                                                }
                                            }
                                        })
                                    }
                                    addAll(sessionState.availableVideoTracks.map { track ->
                                        ChoiceGroupItem(
                                            id = "video_track_${track.id}",
                                            label = track.name,
                                            selected = sessionState.selectedVideoTrack?.id == track.id,
                                        ) {
                                            engine.session?.let { session ->
                                                kotlinx.coroutines.runBlocking {
                                                    session.selectVideoTrack(track.id)
                                                    session.refreshTracks()
                                                }
                                            }
                                        }
                                    })
                                }
                            }
                        )
                    }
                    add(ActionItem("snapshot", playerText.takeSnapshot, enabled = sessionState.hasVideo) { takeSnapshot() })
                }
            }
        )
        add(
            SubmenuItem(id = "tools", label = playerText.tools) {
                listOf(
                    ActionItem("media_information", playerText.mediaInformation) { activePanel = AdvancedPanel.MEDIA_INFO },
                    ActionItem("codec_information", playerText.codecInformation) { activePanel = AdvancedPanel.CODEC_INFO },
                    ActionItem("playback_diagnostics", playerText.playbackDiagnostics) { activePanel = AdvancedPanel.PLAYBACK_DIAGNOSTICS },
                    SeparatorItem("tools_sep"),
                    ActionItem("sync_effects", playerText.synchronizationAndEffects) { activePanel = AdvancedPanel.SYNC_EFFECTS },
                )
            }
        )
        add(
            SubmenuItem(id = "window", label = playerText.window) {
                listOf(
                    ToggleItem("always_on_top", playerText.alwaysOnTop, checked = alwaysOnTop) { toggleAlwaysOnTop() },
                    SeparatorItem("window_sep"),
                    ToggleItem("show_controls", playerText.showPlaybackControls, checked = chromePinnedVisible) {
                        chromePinnedVisible = !chromePinnedVisible
                        chromeVisible = chromePinnedVisible || chromeVisible
                    },
                    ActionItem("reset_layout", playerText.resetPlayerWindowLayout, enabled = false) {},
                )
            }
        )
        add(SeparatorItem("close_sep"))
        add(ActionItem("close_player", playerText.closePlayer) { onClose() })
    }

    // Window-level key dispatcher. Compose's onPreviewKeyEvent (below)
    // only fires when this surface holds keyboard focus, but focus is
    // often retained by whatever button/menu the user clicked to start
    // playback -- leaving every hotkey dead until they manually click
    // the video. Same pattern MpvPlayerShell uses successfully for IPTV.
    //
    // Only unmodified keypresses are intercepted so we don't fight the
    // app menu bar (Ctrl+Q quit, Ctrl+F fullscreen, Ctrl+W tray, etc.).
    androidx.compose.runtime.DisposableEffect(engine, hotkeys, channelNavigationEnabled) {
        fun awtCode(action: DesktopPlaybackHotkeyAction): Int? =
            hotkeys.bindingFor(action).toAwtPlaybackKeyCode()
        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (e.isControlDown || e.isAltDown || e.isMetaDown) return@KeyEventDispatcher false
            val keyCode = e.keyCode
            onInteraction()
            when (keyCode) {
                awtCode(DesktopPlaybackHotkeyAction.EXIT_PLAYBACK) -> {
                    when {
                        showContextMenu -> { showContextMenu = false; true }
                        activePanel != null -> { activePanel = null; true }
                        else -> { onClose(); true }
                    }
                }
                awtCode(DesktopPlaybackHotkeyAction.PREVIOUS_CHANNEL) -> {
                    if (channelNavigationEnabled && onPreviousChannel != null) {
                        onPreviousChannel.invoke()
                        showToast(playerText.previousChannel)
                        true
                    } else false
                }
                awtCode(DesktopPlaybackHotkeyAction.NEXT_CHANNEL) -> {
                    if (channelNavigationEnabled && onNextChannel != null) {
                        onNextChannel.invoke()
                        showToast(playerText.nextChannel)
                        true
                    } else false
                }
                awtCode(DesktopPlaybackHotkeyAction.PLAY_PAUSE) -> {
                    if (engine.session == null) false else {
                        kotlinx.coroutines.runBlocking {
                            if (engine.isPlaying()) engine.pause() else engine.play()
                        }
                        true
                    }
                }
                awtCode(DesktopPlaybackHotkeyAction.TOGGLE_FULLSCREEN) -> {
                    toggleFullscreen(); true
                }
                awtCode(DesktopPlaybackHotkeyAction.SEEK_BACKWARD) -> {
                    val session = engine.session ?: return@KeyEventDispatcher false
                    kotlinx.coroutines.runBlocking { session.seekRelative(-seekStepMs) }
                    true
                }
                awtCode(DesktopPlaybackHotkeyAction.SEEK_FORWARD) -> {
                    val session = engine.session ?: return@KeyEventDispatcher false
                    kotlinx.coroutines.runBlocking { session.seekRelative(seekStepMs) }
                    true
                }
                awtCode(DesktopPlaybackHotkeyAction.VOLUME_UP) -> {
                    val session = engine.session ?: return@KeyEventDispatcher false
                    val next = (engine.getVolume() + 5).coerceIn(0, 100)
                    kotlinx.coroutines.runBlocking { session.setVolume(next) }
                    onVolumeChanged?.invoke(next)
                    true
                }
                awtCode(DesktopPlaybackHotkeyAction.VOLUME_DOWN) -> {
                    val session = engine.session ?: return@KeyEventDispatcher false
                    val next = (engine.getVolume() - 5).coerceIn(0, 100)
                    kotlinx.coroutines.runBlocking { session.setVolume(next) }
                    onVolumeChanged?.invoke(next)
                    true
                }
                awtCode(DesktopPlaybackHotkeyAction.CYCLE_SUBTITLES) -> {
                    cycleSubtitleTrack(); true
                }
                awtCode(DesktopPlaybackHotkeyAction.CYCLE_VIDEO_MODE) -> {
                    cycleVideoMode(); true
                }
                awtCode(DesktopPlaybackHotkeyAction.MUTE) -> {
                    val session = engine.session ?: return@KeyEventDispatcher false
                    kotlinx.coroutines.runBlocking { session.setMute(!engine.isMuted()) }
                    true
                }
                awtCode(DesktopPlaybackHotkeyAction.STOP_PLAYBACK) -> {
                    onStop(); true
                }
                else -> false
            }
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { contextMenuContainerSize = it }
            .focusRequester(focusRequester).hoverable(interactionSource)
            // Drag-and-drop subtitle handler is installed at JFrame level via
            // AWT (see Main.kt → SubtitleDropBus). Subscription happens in a
            // DisposableEffect below so it's tied to this composable's
            // lifetime - when the player closes, the subscription clears.
            // Fix 3: Use onPointerEvent for Move so any mouse movement shows chrome
            .onPointerEvent(PointerEventType.Move) { onInteraction() }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    val cursorPosition = event.changes.firstOrNull()?.position ?: Offset.Zero
                    contextMenuOffset = IntOffset(cursorPosition.x.toInt(), cursorPosition.y.toInt())
                    showContextMenu = true
                    showSubtitleMenu = false
                    showSettingsMenu = false
                    showAspectMenu = false
                    onInteraction()
                }
            }
            .pointerInput(showContextMenu, activePanel) {
                if (showContextMenu || activePanel != null) return@pointerInput
                detectTapGestures(
                    onTap = {
                        onInteraction()
                        if (chromeVisible) kotlinx.coroutines.runBlocking { if (engine.isPlaying()) engine.pause() else engine.play() }
                    },
                    onDoubleTap = {
                        toggleFullscreen()
                    },
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                onInteraction()
                if (matchesHotkey(event, DesktopPlaybackHotkeyAction.EXIT_PLAYBACK)) {
                    if (showContextMenu) { showContextMenu = false; return@onPreviewKeyEvent true }
                    if (activePanel != null) { activePanel = null; return@onPreviewKeyEvent true }
                    // ESC always closes the player surface. We used to
                    // call toggleFullscreen() first when fullscreen, but
                    // that mutated the main window's WindowState into a
                    // broken undecorated-floating state.
                    onClose()
                    return@onPreviewKeyEvent true
                }
                if (channelNavigationEnabled) {
                    if (matchesHotkey(event, DesktopPlaybackHotkeyAction.PREVIOUS_CHANNEL)) {
                        onPreviousChannel?.invoke()
                        showToast(playerText.previousChannel)
                        return@onPreviewKeyEvent true
                    }
                    if (matchesHotkey(event, DesktopPlaybackHotkeyAction.NEXT_CHANNEL)) {
                        onNextChannel?.invoke()
                        showToast(playerText.nextChannel)
                        return@onPreviewKeyEvent true
                    }
                }
                val session = engine.session ?: return@onPreviewKeyEvent false
                when {
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.PLAY_PAUSE) -> {
                        kotlinx.coroutines.runBlocking { if (engine.isPlaying()) engine.pause() else engine.play() }
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.TOGGLE_FULLSCREEN) -> { toggleFullscreen(); true }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.SEEK_BACKWARD) -> {
                        kotlinx.coroutines.runBlocking { session.seekRelative(-seekStepMs) }
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.SEEK_FORWARD) -> {
                        kotlinx.coroutines.runBlocking { session.seekRelative(seekStepMs) }
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.VOLUME_UP) -> {
                        val next = (engine.getVolume() + 5).coerceIn(0, 100)
                        kotlinx.coroutines.runBlocking { session.setVolume(next) }
                        onVolumeChanged?.invoke(next)
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.VOLUME_DOWN) -> {
                        val next = (engine.getVolume() - 5).coerceIn(0, 100)
                        kotlinx.coroutines.runBlocking { session.setVolume(next) }
                        onVolumeChanged?.invoke(next)
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.CYCLE_SUBTITLES) -> { cycleSubtitleTrack(); true }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.CYCLE_VIDEO_MODE) -> { cycleVideoMode(); true }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.MUTE) -> {
                        kotlinx.coroutines.runBlocking { session.setMute(!engine.isMuted()) }
                        true
                    }
                    matchesHotkey(event, DesktopPlaybackHotkeyAction.STOP_PLAYBACK) -> {
                        onStop()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Video - VLC writes RV32 frames into VlcFrameRenderer's double-
        // buffered BufferedImage and we draw the latest as a Compose Image.
        // Lightweight, so the chrome overlays below this block actually
        // render and accept input (a heavyweight AWT Canvas would paint over
        // every Compose component placed above it on Windows).
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            currentFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().align(Alignment.Center),
                )
            }
        }

        TorvePlayerContextMenu(
            visible = showContextMenu,
            anchor = contextMenuOffset,
            containerSize = contextMenuContainerSize,
            items = contextMenuItems,
            onDismissRequest = { showContextMenu = false },
        )

        /*
        if (false) {
            DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = with(density) { DpOffset(contextMenuOffset.x.toDp(), contextMenuOffset.y.toDp()) },
        ) {
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                onClick = {},
                enabled = false,
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (sessionState.isPlaying) "Pause" else "Play") },
                onClick = {
                    kotlinx.coroutines.runBlocking { if (engine.isPlaying()) engine.pause() else engine.play() }
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Stop") },
                onClick = {
                    onStop()
                    showContextMenu = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Seek Back ${seekStepMs / 1000}s") },
                onClick = {
                    engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.seekRelative(-seekStepMs) } }
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Seek Forward ${seekStepMs / 1000}s") },
                onClick = {
                    engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.seekRelative(seekStepMs) } }
                    showContextMenu = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (sessionState.isMuted) "Unmute" else "Mute") },
                onClick = {
                    engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setMute(!engine.isMuted()) } }
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Volume +5") },
                onClick = {
                    engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setVolume(engine.getVolume() + 5) } }
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Volume -5") },
                onClick = {
                    engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setVolume(engine.getVolume() - 5) } }
                    showContextMenu = false
                },
            )
            if (sessionState.availableAudioTracks.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Audio Track", color = Color.Gray) }, onClick = {}, enabled = false)
                sessionState.availableAudioTracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(track.name) },
                        onClick = {
                            engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.selectAudioTrack(track.id); s.refreshTracks() } }
                            showContextMenu = false
                        },
                        leadingIcon = {
                            if (sessionState.selectedAudioTrack?.id == track.id) {
                                Icon(Icons.Filled.Check, null, tint = Accent)
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Subtitles Off") }, onClick = {
                engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.disableSubtitles(); s.refreshTracks() } }
                showContextMenu = false
            })
            sessionState.availableSubtitleTracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.name) },
                    onClick = {
                        engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.selectSubtitleTrack(track.id); s.refreshTracks() } }
                        showContextMenu = false
                    },
                    leadingIcon = {
                        if (sessionState.selectedSubtitleTrack?.id == track.id) {
                            Icon(Icons.Filled.Check, null, tint = Accent)
                        }
                    },
                )
            }
            HorizontalDivider()
            listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                DropdownMenuItem(
                    text = { Text("Speed ${speed}x") },
                    onClick = {
                        engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setRate(speed) } }
                        showContextMenu = false
                    },
                    leadingIcon = {
                        if (kotlin.math.abs(sessionState.playbackRate - speed) < 0.01f) {
                            Icon(Icons.Filled.Check, null, tint = Accent)
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (isFullscreen) "Exit Fullscreen" else "Enter Fullscreen") },
                onClick = {
                    toggleFullscreen()
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Audio Sync...") },
                onClick = {
                    activePanel = AdvancedPanel.AUDIO_SYNC
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Subtitle Sync...") },
                onClick = {
                    activePanel = AdvancedPanel.SUBTITLE_SYNC
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Equalizer...") },
                onClick = {
                    activePanel = AdvancedPanel.EQUALIZER
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Media Info...") },
                onClick = {
                    activePanel = AdvancedPanel.MEDIA_INFO
                    showContextMenu = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Close Player") },
                onClick = {
                    onClose()
                    showContextMenu = false
                },
            )
        }
        }
        */

        // Buffering
        if (sessionState.isBuffering) {
            Surface(Modifier.align(Alignment.Center), color = ChromeBg, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(24.dp, 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(playerText.bufferingTemplate.format(sessionState.bufferedPercent.toInt()), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { sessionState.bufferedPercent / 100f }, Modifier.width(180.dp), color = Accent, trackColor = Color(0x40FFFFFF))
                }
            }
        }

        toastMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp),
                color = PanelBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PanelBorder),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // Chrome overlay
        AnimatedVisibility(visible = chromeVisible || sessionState.isPaused || sessionState.isBuffering, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Box(Modifier.fillMaxWidth().align(Alignment.TopStart).background(Brush.verticalGradient(listOf(Color(0xB0060810), Color.Transparent))).padding(20.dp, 16.dp)) {
                    Column {
                        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (!subtitle.isNullOrBlank()) Text(subtitle, color = Color(0xBBFFFFFF), fontSize = 12.sp)
                    }
                }

                // Bottom control bar
                Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, ChromeBg))).padding(20.dp, 12.dp)) {
                    val progress = if (sessionState.durationMs > 0) sessionState.positionMs.toFloat() / sessionState.durationMs else 0f
                    Slider(value = progress, onValueChange = { f -> engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.seek((sessionState.durationMs * f).toLong()) } }; onInteraction() },
                        modifier = Modifier.fillMaxWidth().height(20.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)))
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        // Left
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PBtn(Icons.AutoMirrored.Filled.ArrowBack) { onClose() }
                            PBtn(if (sessionState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, primary = true) { kotlinx.coroutines.runBlocking { if (engine.isPlaying()) engine.pause() else engine.play() } }
                            PBtn(Icons.Filled.Stop) { onStop() }
                            PBtn(Icons.Filled.FastRewind) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.seekRelative(-seekStepMs) } } }
                            PBtn(Icons.Filled.FastForward) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.seekRelative(seekStepMs) } } }
                            // Record / Stop-recording. Hidden when caller
                            // didn't supply onToggleRecord (e.g. VOD).
                            onToggleRecord?.let { toggleRec ->
                                RecordPBtn(isRecording = isCurrentlyRecording) {
                                    toggleRec()
                                    onInteraction()
                                }
                            }
                        }
                        // Center time
                        Text("${fmt(sessionState.positionMs)} / ${fmt(sessionState.durationMs)}", color = Color.White, fontSize = 12.sp)
                        // Right
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PBtn(if (sessionState.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setMute(!engine.isMuted()) } } }
                            Slider(value = sessionState.volume.toFloat(), onValueChange = { v -> engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setVolume(v.toInt()) } }; onVolumeChanged?.invoke(v.toInt()); onInteraction() },
                                valueRange = 0f..200f, modifier = Modifier.width(100.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)))
                            // Subtitles
                            Box { PBtn(Icons.Filled.Subtitles) { showSubtitleMenu = true; onInteraction() }
                                DropdownMenu(showSubtitleMenu, { showSubtitleMenu = false }) {
                                    CheckItem(playerText.off, sessionState.selectedSubtitleTrack == null) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.disableSubtitles(); s.refreshTracks() } }; showSubtitleMenu = false }
                                    sessionState.availableSubtitleTracks.forEach { t -> CheckItem(t.name, sessionState.selectedSubtitleTrack?.id == t.id) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.selectSubtitleTrack(t.id); s.refreshTracks() } }; showSubtitleMenu = false } }
                                    if (sessionState.availableSubtitleTracks.isEmpty()) DropdownMenuItem(text = { Text(playerText.noTracks, color = Color.Gray) }, onClick = {})
                                    onSearchOnlineSubtitles?.let { search ->
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(playerText.searchOnline) },
                                            onClick = { search(); showSubtitleMenu = false },
                                        )
                                    }
                                } }
                            // Aspect ratio
                            Box { PBtn(Icons.Filled.AspectRatio) { showAspectMenu = true; onInteraction() }
                                DropdownMenu(showAspectMenu, { showAspectMenu = false }) {
                                    aspectOptions.forEach { opt -> CheckItem(opt.label, selectedAspect == opt.label) {
                                        applyAspect(opt)
                                        showAspectMenu = false
                                    } }
                                } }
                            // Settings
                            Box { PBtn(Icons.Filled.Settings) { showSettingsMenu = true; onInteraction() }
                                DropdownMenu(showSettingsMenu, { showSettingsMenu = false }) {
                                    SectionLabel(playerText.speed)
                                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f).forEach { spd ->
                                        CheckItem("${spd}x", kotlin.math.abs(sessionState.playbackRate - spd) < 0.01f) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setRate(spd) } }; showSettingsMenu = false } }
                                    SectionLabel(playerText.audioTrack)
                                    sessionState.availableAudioTracks.forEach { t -> CheckItem(t.name, sessionState.selectedAudioTrack?.id == t.id) { engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.selectAudioTrack(t.id); s.refreshTracks() } }; showSettingsMenu = false } }
                                    if (sessionState.availableAudioTracks.isEmpty()) DropdownMenuItem(text = { Text(playerText.noAudioTracks, color = Color.Gray) }, onClick = {})
                                    SectionLabel(playerText.panels)
                                    DropdownMenuItem(text = { Text(playerText.audioSyncEllipsis) }, onClick = { activePanel = AdvancedPanel.AUDIO_SYNC; showSettingsMenu = false })
                                    DropdownMenuItem(text = { Text(playerText.subtitleSyncEllipsis) }, onClick = { activePanel = AdvancedPanel.SUBTITLE_SYNC; showSettingsMenu = false })
                                    DropdownMenuItem(text = { Text(playerText.equalizerEllipsis) }, onClick = { activePanel = AdvancedPanel.EQUALIZER; showSettingsMenu = false })
                                    DropdownMenuItem(text = { Text(playerText.mediaInfoEllipsis) }, onClick = { activePanel = AdvancedPanel.MEDIA_INFO; showSettingsMenu = false })
                                } }
                            // Cast (Chromecast / DLNA via VLC renderer discoverers)
                            if (castController != null) {
                                val castState by castController.state.collectAsState()
                                var showCastMenu by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) { castController.startDiscovery() }
                                Box {
                                    PBtn(
                                        if (castState.connectedDeviceId != null) Icons.Filled.CastConnected
                                        else Icons.Filled.Cast,
                                    ) { showCastMenu = true; onInteraction() }
                                    DropdownMenu(showCastMenu, { showCastMenu = false }) {
                                        SectionLabel(playerText.castTo)
                                        if (castState.devices.isEmpty()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (castState.activeDiscoverers.isEmpty())
                                                            playerText.castUnavailable
                                                        else playerText.searchingForDevices,
                                                        color = Color.Gray,
                                                    )
                                                },
                                                onClick = {},
                                            )
                                        } else {
                                            castState.devices.forEach { device ->
                                                CheckItem(device.name, castState.connectedDeviceId == device.id) {
                                                    castController.castTo(device.id)
                                                    showCastMenu = false
                                                }
                                            }
                                        }
                                        if (castState.connectedDeviceId != null) {
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text(playerText.stopCasting) },
                                                onClick = {
                                                    castController.disconnect()
                                                    showCastMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            // Pin / Always-on-top - distinct from PiP. Pin keeps
                            // the full window on top of every other window.
                            PBtn(
                                if (alwaysOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                primary = alwaysOnTop,
                            ) { toggleAlwaysOnTop(); onInteraction() }
                            // Picture-in-Picture (minimize to floating overlay)
                            if (onMinimizeToPip != null) {
                                PBtn(Icons.Filled.PictureInPictureAlt) { onMinimizeToPip(); onInteraction() }
                            }
                            // Fullscreen
                            PBtn(if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen) { toggleFullscreen() }
                        }
                    }
                }
            }
        }

        // Advanced panels
        activePanel?.let { panel ->
            Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable { activePanel = null }) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(min = 380.dp, max = 520.dp)
                        .heightIn(max = 560.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                    color = PanelBg, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, PanelBorder),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(when (panel) {
                                AdvancedPanel.EQUALIZER -> playerText.equalizer
                                AdvancedPanel.AUDIO_SYNC -> playerText.audioSync
                                AdvancedPanel.SUBTITLE_SYNC -> playerText.subtitleSync
                                AdvancedPanel.MEDIA_INFO -> playerText.mediaInformation
                                AdvancedPanel.CODEC_INFO -> playerText.codecInformation
                                AdvancedPanel.PLAYBACK_DIAGNOSTICS -> playerText.playbackDiagnostics
                                AdvancedPanel.SYNC_EFFECTS -> playerText.synchronizationAndEffects
                                AdvancedPanel.JUMP_TO_TIME -> playerText.jumpToTime
                            }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { activePanel = null }) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                            when (panel) {
                                AdvancedPanel.AUDIO_SYNC -> DelaySyncPanel(playerText.audioDelay, { engine.session?.getAudioDelayUs() ?: 0 }, { d -> engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setAudioDelay(d) } } })
                                AdvancedPanel.SUBTITLE_SYNC -> DelaySyncPanel(playerText.subtitleDelay, { engine.session?.getSubtitleDelayUs() ?: 0 }, { d -> engine.session?.let { s -> kotlinx.coroutines.runBlocking { s.setSubtitleDelay(d) } } })
                                AdvancedPanel.EQUALIZER -> EqualizerPanel(engine)
                                AdvancedPanel.MEDIA_INFO -> MediaInfoPanel(
                                    diagnostics = diagnostics,
                                    title = title,
                                    subtitle = subtitle,
                                    sessionState = sessionState,
                                    mediaPath = engine.currentMediaPath(),
                                )
                                AdvancedPanel.CODEC_INFO -> CodecInfoPanel(diagnostics)
                                AdvancedPanel.PLAYBACK_DIAGNOSTICS -> PlaybackDiagnosticsPanel(diagnostics, sessionState, engine.backendLabel)
                                AdvancedPanel.SYNC_EFFECTS -> SyncAndEffectsPanel(
                                    audioDelayUs = sessionState.audioDelayUs,
                                    subtitleDelayUs = sessionState.subtitleDelayUs,
                                    onAdjustAudio = ::updateAudioDelay,
                                    onAdjustSubtitle = ::updateSubtitleDelay,
                                    onOpenEqualizer = { activePanel = AdvancedPanel.EQUALIZER },
                                )
                                AdvancedPanel.JUMP_TO_TIME -> JumpToTimePanel(
                                    durationMs = sessionState.durationMs,
                                    positionMs = sessionState.positionMs,
                                    onJump = { targetMs ->
                                        engine.session?.let { session -> kotlinx.coroutines.runBlocking { session.seek(targetMs) } }
                                        activePanel = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Delay sync panel ────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelaySyncPanel(label: String, getDelay: () -> Long, setDelay: (Long) -> Unit) {
    var delayMs by remember { mutableFloatStateOf(getDelay() / 1000f) }
    Text("$label: ${delayMs.toLong()} ms", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Slider(value = delayMs, onValueChange = { delayMs = it; setDelay((it * 1000).toLong()) }, valueRange = -5000f..5000f,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)))
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-250, -100, -50, 0, 50, 100, 250).forEach { step ->
            Chip(if (step == 0) ds("Reset") else "${if (step > 0) "+" else ""}${step} ms") {
                delayMs = if (step == 0) 0f else delayMs + step
                setDelay((delayMs * 1000).toLong())
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(ds("Positive = delayed, negative = earlier"), color = Color.Gray, fontSize = 11.sp)
}

// ── Equalizer panel (fix 1: band levels update when preset changes) ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerPanel(engine: VlcPlaybackEngine) {
    val session = engine.session
    if (session == null) { Text(ds("No active session"), color = Color.Gray); return }

    val presets = remember { session.getEqualizerPresets() }
    val bandFreqs = remember { session.getEqualizerBandFrequencies() }
    var enabled by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    // Key that increments when preset changes to force band slider refresh
    var bandRefreshKey by remember { mutableIntStateOf(0) }

    // Enable/disable
    Chip(if (enabled) ds("Enabled") else ds("Disabled"), active = enabled) {
        enabled = !enabled; kotlinx.coroutines.runBlocking { session.setEqualizerEnabled(enabled) }
    }
    Spacer(Modifier.height(12.dp))
    Chip(ds("Reset")) {
        selectedPreset = null
        enabled = false
        kotlinx.coroutines.runBlocking {
            session.setEqualizerEnabled(false)
            session.setEqualizerPreamp(0f)
            repeat(session.getEqualizerBandCount()) { index -> session.setEqualizerBand(index, 0f) }
        }
        bandRefreshKey++
    }

    // Presets
    if (presets.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(ds("Presets"), color = Color(0xAAFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { preset ->
                Chip(preset, active = selectedPreset == preset) {
                    selectedPreset = preset
                    kotlinx.coroutines.runBlocking { session.applyEqualizerPreset(preset) }
                    enabled = true
                    bandRefreshKey++ // Force band sliders to re-read levels
                }
            }
        }
    }

    // Preamp
    Spacer(Modifier.height(16.dp))
    Text(ds("Preamp"), color = Color(0xAAFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    var preamp by remember(bandRefreshKey) { mutableFloatStateOf(session.getEqualizerPreamp()) }
    Slider(value = preamp, onValueChange = { preamp = it; kotlinx.coroutines.runBlocking { session.setEqualizerPreamp(it) } },
        valueRange = -20f..20f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)))

    // Bands - keyed on bandRefreshKey so they re-read after preset change
    if (bandFreqs.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(ds("Bands"), color = Color(0xAAFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        bandFreqs.forEachIndexed { index, freq ->
            val label = if (freq >= 1000) "${(freq / 1000).toInt()} kHz" else "${freq.toInt()} Hz"
            var level by remember(bandRefreshKey) { mutableFloatStateOf(session.getEqualizerBandLevel(index)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                Text(label, color = Color(0xBBFFFFFF), fontSize = 11.sp, modifier = Modifier.width(54.dp))
                Slider(value = level, onValueChange = { level = it; kotlinx.coroutines.runBlocking { session.setEqualizerBand(index, it) } },
                    valueRange = -20f..20f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)))
                Text("${level.toInt()}", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(28.dp))
            }
        }
    }
}

// ── Picture mode panel ──────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureModePanel(engine: VlcPlaybackEngine, selectedAspect: String, onSelect: (String) -> Unit) {
    Text(ds("Choose how the video fills the player area."), color = Color.Gray, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        aspectOptions.forEach { opt ->
            Chip(opt.label, active = selectedAspect == opt.label) {
                onSelect(opt.label)
                // Aspect ratio is enforced at the Compose drawing layer.
            }
        }
    }
}

// ── Media info panel ────────────────────────────────────────────

@Composable
private fun MediaInfoPanel(state: VlcSessionState) {
    listOf(
        ds("Status") to state.playbackStatus::class.simpleName.orEmpty(),
        ds("Duration") to fmt(state.durationMs),
        ds("Position") to fmt(state.positionMs),
        ds("Video") to (state.videoDimensions?.let { "${it.width}x${it.height}" } ?: ds("N/A")),
        ds("Audio Track") to (state.selectedAudioTrack?.name ?: ds("Default")),
        ds("Subtitle Track") to (state.selectedSubtitleTrack?.name ?: ds("Off")),
        ds("Speed") to "${state.playbackRate}x",
        ds("Volume") to "${state.volume}%",
        ds("Audio Delay") to "${state.audioDelayUs / 1000} ms",
        ds("Subtitle Delay") to "${state.subtitleDelayUs / 1000} ms",
        ds("Can Seek") to state.canSeek.toString(),
        ds("Has Video") to state.hasVideo.toString(),
        ds("Has Audio") to state.hasAudio.toString(),
    ).forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.Gray, fontSize = 12.sp); Text(value, color = Color.White, fontSize = 12.sp)
        }
    }
}

// ── Shared components ───────────────────────────────────────────

@Composable
private fun MediaInfoPanel(
    diagnostics: com.torve.desktop.player.DesktopPlayerDiagnostics,
    title: String,
    subtitle: String?,
    sessionState: VlcSessionState,
    mediaPath: String?,
) {
    listOf(
        ds("Title") to title,
        ds("Subtitle") to (subtitle ?: ds("None")),
        ds("Source") to (mediaPath ?: diagnostics.mediaUrl ?: ds("Unknown")),
        ds("Status") to diagnostics.playbackState,
        ds("Duration") to fmt(sessionState.durationMs),
        ds("Position") to fmt(sessionState.positionMs),
        ds("Video") to (diagnostics.resolution ?: sessionState.videoDimensions?.let { "${it.width}x${it.height}" } ?: ds("N/A")),
        ds("FPS") to (diagnostics.fps ?: ds("N/A")),
        ds("Audio Track") to (sessionState.selectedAudioTrack?.name ?: diagnostics.audioTrack ?: ds("Default")),
        ds("Subtitle Track") to (sessionState.selectedSubtitleTrack?.name ?: diagnostics.subtitleTrack ?: ds("Off")),
        ds("Speed") to speedLabel(sessionState.playbackRate),
        ds("Volume") to "${sessionState.volume}%",
        ds("Audio Delay") to delayHint(sessionState.audioDelayUs),
        ds("Subtitle Delay") to delayHint(sessionState.subtitleDelayUs),
    ).forEach { (label, value) -> InfoRow(label, value) }
}

@Composable
private fun CodecInfoPanel(diagnostics: com.torve.desktop.player.DesktopPlayerDiagnostics) {
    listOf(
        ds("Container") to (diagnostics.sourceType ?: ds("N/A")),
        ds("Video Codec") to (diagnostics.videoCodec ?: ds("N/A")),
        ds("Audio Codec") to (diagnostics.audioCodec ?: ds("N/A")),
        ds("Bitrate") to (diagnostics.bitrate ?: ds("N/A")),
        ds("Resolution") to (diagnostics.resolution ?: ds("N/A")),
        ds("FPS") to (diagnostics.fps ?: ds("N/A")),
        ds("Audio Channels") to (diagnostics.audioChannels ?: ds("N/A")),
        ds("Sample Rate") to (diagnostics.sampleRate ?: ds("N/A")),
    ).forEach { (label, value) -> InfoRow(label, value) }
}

@Composable
private fun PlaybackDiagnosticsPanel(
    diagnostics: com.torve.desktop.player.DesktopPlayerDiagnostics,
    sessionState: VlcSessionState,
    backendLabel: String,
) {
    listOf(
        ds("Backend") to backendLabel,
        ds("State") to diagnostics.playbackState,
        ds("Buffering") to if (sessionState.isBuffering) "${sessionState.bufferedPercent.toInt()}%" else ds("Idle"),
        ds("Buffer Mode") to (diagnostics.bufferingMode ?: ds("Auto")),
        ds("Source Type") to (diagnostics.sourceType ?: ds("Unknown")),
        ds("Source URL") to (diagnostics.mediaUrl ?: ds("Unknown")),
        ds("Runtime") to (diagnostics.runtimePath ?: ds("Bundled/Auto")),
        ds("VLC Version") to (diagnostics.vlcVersion ?: ds("Unknown")),
        ds("Playback Speed") to speedLabel(diagnostics.playbackSpeed),
        ds("Muted") to diagnostics.isMuted.toString(),
    ).forEach { (label, value) -> InfoRow(label, value) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncAndEffectsPanel(
    audioDelayUs: Long,
    subtitleDelayUs: Long,
    onAdjustAudio: (Int) -> Unit,
    onAdjustSubtitle: (Int) -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    Text(ds("Quick sync controls for the current session."), color = Color.Gray, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    Text(ds("Audio Delay"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-250, -100, -50, 50, 100, 250).forEach { step ->
            Chip("${if (step > 0) "+" else ""}${step} ms") { onAdjustAudio(step) }
        }
        Chip(ds("Reset")) { onAdjustAudio((-audioDelayUs / 1000).toInt()) }
    }
    Spacer(Modifier.height(8.dp))
    Text(delayHint(audioDelayUs), color = Color.Gray, fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))
    Text(ds("Subtitle Delay"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-250, -100, -50, 50, 100, 250).forEach { step ->
            Chip("${if (step > 0) "+" else ""}${step} ms") { onAdjustSubtitle(step) }
        }
        Chip(ds("Reset")) { onAdjustSubtitle((-subtitleDelayUs / 1000).toInt()) }
    }
    Spacer(Modifier.height(8.dp))
    Text(delayHint(subtitleDelayUs), color = Color.Gray, fontSize = 11.sp)
    Spacer(Modifier.height(18.dp))
    Chip(ds("Open Equalizer")) { onOpenEqualizer() }
}

@Composable
private fun JumpToTimePanel(
    durationMs: Long,
    positionMs: Long,
    onJump: (Long) -> Unit,
) {
    var jumpText by remember(positionMs) { mutableStateOf(fmt(positionMs)) }
    Text(ds("Enter mm:ss or hh:mm:ss."), color = Color.Gray, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = jumpText,
        onValueChange = { jumpText = it },
        label = { Text(ds("Target time")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Text(ds("Duration: %1\$s").format(fmt(durationMs)), color = Color(0xAAFFFFFF), fontSize = 11.sp)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onJump(parseJumpTarget(jumpText).coerceIn(0L, durationMs)) }) {
            Text(ds("Jump"))
        }
        TextButton(onClick = { jumpText = fmt(positionMs) }) {
            Text(ds("Use Current"))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

private fun delayAdjustmentItems(prefix: String, onAdjust: (Int) -> Unit): List<PlayerMenuNode> {
    return listOf(-250, -100, -50, 0, 50, 100, 250).map { deltaMs ->
        ActionItem(
            id = "${prefix}_$deltaMs",
            label = when (deltaMs) {
                0 -> "Reset to 0 ms"
                else -> "${if (deltaMs > 0) "Plus" else "Minus"} ${abs(deltaMs)} ms"
            },
        ) {
            onAdjust(deltaMs)
        }
    }
}

private fun formatAudioTrackLabel(track: VlcTrack): String {
    return listOfNotNull(
        track.language?.takeIf { it.isNotBlank() },
        track.name.takeIf { it.isNotBlank() },
        track.codec?.takeIf { it.isNotBlank() },
        track.channels?.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" · ").ifBlank { "Track ${track.id}" }
}

private fun delayHint(delayUs: Long): String {
    val delayMs = delayUs / 1000
    return if (delayMs == 0L) "0 ms" else "${if (delayMs > 0) "+" else ""}${delayMs} ms"
}

/** Parses the selected aspect label into a width/height ratio. Falls back to source aspect. */
private fun parseAspectRatio(label: String, sourceAspect: Float): Float = when (label) {
    "16:9" -> 16f / 9f
    "4:3" -> 4f / 3f
    "21:9" -> 21f / 9f
    "Fit to Source" -> sourceAspect
    else -> sourceAspect // "Default" - use the video's native aspect
}

private fun speedLabel(rate: Float): String = when {
    abs(rate - 1f) < 0.01f -> "1.0x"
    abs(rate - rate.toInt()) < 0.01f -> "${rate.toInt()}.0x"
    else -> "${rate}x"
}

private fun parseJumpTarget(value: String): Long {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return 0L
    val parts = trimmed.split(':').mapNotNull { it.toLongOrNull() }
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0L
    }
    return seconds.coerceAtLeast(0) * 1000
}

@Composable
private fun RecordPBtn(isRecording: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        CircleShape,
        if (isRecording) Color(0xFF8A1F1F) else ChipBg,
        border = BorderStroke(
            if (hovered) 1.5.dp else 1.dp,
            if (hovered) Accent else Color(0xFFFF3B30).copy(alpha = if (isRecording) 0.9f else 0.72f),
        ),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (isRecording) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(Color(0xFFFF2D2D), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun PBtn(icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    val sz = if (primary) 44.dp else 36.dp; val isz = if (primary) 28.dp else 22.dp
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        Modifier
            .size(sz)
            .clip(CircleShape)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        CircleShape,
        if (primary) Accent else ChipBg,
        border = BorderStroke(
            if (hovered) 1.5.dp else 0.dp,
            if (hovered) Accent else Color.Transparent,
        ),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(icon, null, Modifier.size(isz), if (primary) Color(0xFF060810) else Color.White) }
    }
}

@Composable
private fun Chip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(Modifier.clickable(onClick = onClick), RoundedCornerShape(8.dp), if (active) ChipBgActive else ChipBg) {
        Text(text, color = if (active) Color.Black else Color.White, modifier = Modifier.padding(12.dp, 7.dp), fontSize = 12.sp)
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, color = Color.Gray)

@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { if (checked) Icon(Icons.Filled.Check, null, tint = Accent) })

private fun fmt(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60; return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s) }
