package com.torve.desktop.mpv

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import com.torve.desktop.playback.DesktopPlaybackHotkeyAction
import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.playback.DesktopPlayerPhase
import com.torve.desktop.playback.bindingFor
import com.torve.desktop.playback.toAwtPlaybackKeyCode
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.player.DesktopPlaybackHotkeys
import kotlinx.coroutines.launch

/**
 * Shell around [MpvComposePlayerSurface] that adds the chrome users need
 * to leave / control playback. Unlike the VLC surface, chrome sits *next*
 * to the canvas (top + bottom rows), not over it - Java AWT components
 * always paint above lightweight Compose content, so an overlay would be
 * hidden anyway. mpv's built-in OSC (enabled in [MpvPlaybackEngine]) draws
 * play/pause/seek inside the canvas when the user hovers, which gives
 * full transport without a Compose overlay.
 *
 * Stage 3 deliberately keeps this chrome minimal:
 *  - Back arrow + title in the top bar.
 *  - Position / duration label and Close button in the bottom bar.
 *  - mpv handles the actual transport overlay.
 *
 * When mpv's render API + Skia interop ship, this file gets replaced by
 * a real overlay-chrome surface that matches the VLC one feature-for-
 * feature.
 */
@Composable
fun MpvPlayerShell(
    engine: MpvPlaybackEngine,
    playerController: DesktopPlayerController,
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    onStop: () -> Unit,
    initialVolume: Int? = null,
    onVolumeChanged: ((Int) -> Unit)? = null,
    preferredAudioLanguage: String? = null,
    preferredSubtitleLanguage: String? = null,
    windowState: androidx.compose.ui.window.WindowState? = null,
    seekStepMs: Long = 10_000L,
    onSearchOnlineSubtitles: (() -> Unit)? = null,
    channelNavigationEnabled: Boolean = false,
    onPreviousChannel: (() -> Unit)? = null,
    onNextChannel: (() -> Unit)? = null,
    hotkeys: DesktopPlaybackHotkeys = DesktopPlaybackHotkeys(),
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()
    var positionSec by remember { mutableStateOf<Double?>(null) }
    var durationSec by remember { mutableStateOf<Double?>(null) }
    val mpvVolume by engine.volume.collectAsState()
    val mpvMuted by engine.muted.collectAsState()
    // Local slider value: prefer mpv's reported value once available, else
    // the user's persisted preference, else 100.
    val volume = mpvVolume ?: initialVolume?.coerceIn(0, 100) ?: 100
    var seekDraft by remember { mutableStateOf<Double?>(null) }
    val tracks by engine.tracks.collectAsState()
    val mpvVideoModes = remember { listOf("Default" to null, "16:9" to "16:9", "4:3" to "4:3", "21:9" to "21:9") }
    var mpvVideoModeIndex by remember { mutableStateOf(0) }

    // Subtitle drag-drop - when a recognised subtitle file lands on the
    // window, mpv loads it and switches to it. Same pattern the VLC
    // surface uses, against the same global SubtitleDropBus.
    DisposableEffect(engine) {
        val handle = com.torve.desktop.dnd.SubtitleDropBus.subscribe { droppedPath ->
            scope.launch { engine.addSubtitle(droppedPath) }
        }
        onDispose { handle.unsubscribe() }
    }
    val audioDevices by engine.audioDevices.collectAsState()
    var audioDeviceMenuOpen by remember { mutableStateOf(false) }
    // Seed once on entry so the menu has data even before the first
    // mpv property-change fires (mpv doesn't always emit a change for
    // the *initial* value).
    LaunchedEffect(engine) {
        if (audioDevices.isEmpty()) {
            runCatching { engine.listAudioDevices() }
                .onSuccess { initial ->
                    if (initial.isNotEmpty()) {
                        // Mirror through the engine flow so observers stay
                        // consistent rather than diverging local state.
                        engine.refreshAudioDevicesNow(initial)
                    }
                }
        }
    }

    LaunchedEffect(engine) {
        engine.events.collect { event ->
            if (event is DesktopPlaybackEngineEvent.Position) {
                positionSec = event.positionSeconds
                durationSec = event.durationSeconds
            }
        }
    }

    // Apply initial volume to mpv once it's ready (engine ignores writes
    // before init, so we re-try until tracks become available).
    LaunchedEffect(engine, initialVolume) {
        initialVolume?.coerceIn(0, 100)?.let { engine.setVolume(it) }
    }

    LaunchedEffect(engine, preferredAudioLanguage, preferredSubtitleLanguage) {
        engine.setPreferredLanguages(preferredAudioLanguage, preferredSubtitleLanguage)
    }

    // Collect phase as state so the chrome recomposes on phase
    // transitions (RESOLVING → OPENING → BUFFERING → PLAYING).
    val playerState by playerController.state.collectAsState()
    val phase = playerState.phase
    val isPaused = phase == DesktopPlayerPhase.PAUSED
    val phaseLabel = when (phase) {
        DesktopPlayerPhase.RESOLVING -> "Resolving source..."
        DesktopPlayerPhase.OPENING -> "Opening stream..."
        DesktopPlayerPhase.BUFFERING -> "Buffering..."
        else -> null
    }

    // Fullscreen behaviour - track our own boolean instead of relying on
    // WindowState.placement, because that doesn't always actually take
    // effect on JBR + Compose 1.7.3. Chrome auto-hides after 3s of
    // mouse idle while in fullscreen mode; canvas-area motion (via the
    // AWT MouseMotionListener forwarded from MpvComposePlayerSurface)
    // resets the timer. Esc clears both this state and the OS-level
    // placement.
    var userWantsFullscreen by remember { mutableStateOf(false) }
    val isFullscreen = userWantsFullscreen
    var lastMotionMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            while (true) {
                kotlinx.coroutines.delay(500)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    val chromeRevealed = !isFullscreen || (nowMs - lastMotionMs < 3000)
    val showChrome = chromeRevealed
    val revealChrome: () -> Unit = { lastMotionMs = System.currentTimeMillis() }

    // Title-treatment: show the show/movie's TMDB logo (the title in its
    // own brand font) when available, with the episode label underneath
    // for series. Falls back to plain text if no logo on disk yet.
    val mediaItem = playerState.preparedSession?.mediaItem
    val episodeContext = playerState.preparedSession?.episodeContext
    val logoBitmap = com.torve.desktop.ui.v2.components.rememberCachedBitmap(mediaItem?.logoUrl)
    // Latest-value snapshots for the AWT dispatcher closure - without
    // these the dispatcher would close over stale composition values
    // and Space/seek-keys would reference an old position/pause state.
    val pausedRef by rememberUpdatedState(isPaused)
    val positionRef by rememberUpdatedState(positionSec)
    val fullscreenRef by rememberUpdatedState(userWantsFullscreen)
    val volumeRef by rememberUpdatedState(volume)
    val mutedRef by rememberUpdatedState(mpvMuted)
    val tracksRef by rememberUpdatedState(tracks)
    val hotkeysRef by rememberUpdatedState(hotkeys)
    val seekStepMsRef by rememberUpdatedState(seekStepMs)
    val onCloseRef by rememberUpdatedState(onClose)
    val onVolumeChangedRef by rememberUpdatedState(onVolumeChanged)
    DisposableEffect(windowState, channelNavigationEnabled, onPreviousChannel, onNextChannel) {
        fun code(action: DesktopPlaybackHotkeyAction): Int? =
            hotkeysRef.bindingFor(action).toAwtPlaybackKeyCode()

        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val key = e.keyCode
            when {
                key == code(DesktopPlaybackHotkeyAction.EXIT_PLAYBACK) -> {
                    if (fullscreenRef) {
                        userWantsFullscreen = false
                        true
                    } else {
                        onCloseRef()
                        true
                    }
                }
                channelNavigationEnabled && key == code(DesktopPlaybackHotkeyAction.PREVIOUS_CHANNEL) -> {
                    if (onPreviousChannel != null) {
                        onPreviousChannel(); true
                    } else false
                }
                channelNavigationEnabled && key == code(DesktopPlaybackHotkeyAction.NEXT_CHANNEL) -> {
                    if (onNextChannel != null) {
                        onNextChannel(); true
                    } else false
                }
                key == code(DesktopPlaybackHotkeyAction.SEEK_BACKWARD) -> {
                    scope.launch { engine.seekTo(((positionRef ?: 0.0) - seekStepMsRef / 1000.0).coerceAtLeast(0.0)) }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.SEEK_FORWARD) -> {
                    scope.launch { engine.seekTo((positionRef ?: 0.0) + seekStepMsRef / 1000.0) }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.VOLUME_UP) -> {
                    val next = (volumeRef + 5).coerceIn(0, 100)
                    scope.launch { engine.setVolume(next) }
                    onVolumeChangedRef?.invoke(next)
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.VOLUME_DOWN) -> {
                    val next = (volumeRef - 5).coerceIn(0, 100)
                    scope.launch { engine.setVolume(next) }
                    onVolumeChangedRef?.invoke(next)
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.PLAY_PAUSE) -> {
                    scope.launch { if (pausedRef) engine.play() else engine.pause() }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.MUTE) -> {
                    scope.launch { engine.setMuted(!mutedRef) }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.STOP_PLAYBACK) -> {
                    onStop()
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.CYCLE_SUBTITLES) -> {
                    val subtitleTracks = tracksRef.filter { it.type == "sub" }
                    if (subtitleTracks.isNotEmpty()) {
                        val selectedIndex = subtitleTracks.indexOfFirst { it.selected }
                        val nextId = when {
                            selectedIndex < 0 -> subtitleTracks.first().id
                            selectedIndex >= subtitleTracks.lastIndex -> null
                            else -> subtitleTracks[selectedIndex + 1].id
                        }
                        scope.launch { engine.selectSubtitleTrack(nextId) }
                    }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.CYCLE_VIDEO_MODE) -> {
                    val nextIndex = (mpvVideoModeIndex + 1) % mpvVideoModes.size
                    mpvVideoModeIndex = nextIndex
                    scope.launch { engine.setVideoAspectOverride(mpvVideoModes[nextIndex].second) }
                    true
                }
                key == code(DesktopPlaybackHotkeyAction.TOGGLE_FULLSCREEN) -> {
                    userWantsFullscreen = !userWantsFullscreen
                    true
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Top bar - back button + title. Hidden in fullscreen mode so
        // the canvas owns the full screen; Esc reveals it again.
        if (showChrome) Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.stageSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                // Logo (if available) sits in the title slot so series and
                // movies show their branded title art. Falls back to plain
                // text. Episode label shows S{n}E{n} • title for series.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (logoBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = logoBitmap,
                            contentDescription = title,
                            modifier = Modifier.height(32.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                        )
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                    }
                    androidx.compose.material3.Surface(
                        color = colors.accentContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "MPV",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (phaseLabel != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                        Text(
                            text = phaseLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                val episodeLabel = episodeContext?.label
                if (!episodeLabel.isNullOrBlank()) {
                    Text(
                        text = episodeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }

            if (onSearchOnlineSubtitles != null) {
                IconButton(onClick = onSearchOnlineSubtitles) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search online subtitles",
                        tint = colors.textPrimary,
                    )
                }
            }
            // Immersive toggle - hides chrome only. We deliberately do NOT
            // change WindowState.placement here: on JBR + Compose 1.7.3
            // toggling Fullscreen rebuilds the AWT canvas peer, which
            // breaks mpv's `wid` binding and shows a white window. Hiding
            // chrome alone gives the same video-real-estate win without
            // tearing down playback.
            IconButton(onClick = {
                userWantsFullscreen = !userWantsFullscreen
                lastMotionMs = System.currentTimeMillis()
            }) {
                Icon(
                    if (userWantsFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (userWantsFullscreen) "Show chrome" else "Hide chrome",
                    tint = colors.textPrimary,
                )
            }
        }

        // Canvas - defer mounting the SwingPanel until we're past the
        // RESOLVING phase. While resolving, we show a calm loading
        // surface (Compose-only, no AWT canvas to fight with) so the
        // user doesn't stare at a flat white area waiting for mpv to
        // wake up. Once we transition to OPENING, the canvas mounts and
        // attaches; the engine's open() waits up to 3s for that attach
        // before falling back to standalone, so timing is safe.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.shellBackground),
            contentAlignment = Alignment.Center,
        ) {
            val showLoadingScreen = phase == DesktopPlayerPhase.RESOLVING
            if (showLoadingScreen) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(56.dp).height(56.dp),
                        strokeWidth = 4.dp,
                        color = colors.accent,
                    )
                    Text(
                        text = phaseLabel ?: "Preparing...",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Locating the best source for this title.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            } else {
                MpvComposePlayerSurface(
                    engine = engine,
                    onCanvasMotion = revealChrome,
                    // Hide the OS cursor whenever the chrome is hidden -
                    // immersive viewing should fade pointer + controls
                    // together. In windowed mode chrome is always shown,
                    // so the cursor stays visible.
                    cursorHidden = !showChrome,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Bottom chrome - two stacked rows: seek slider on top, transport
        // + volume + track menus underneath. Hidden in fullscreen.
        if (showChrome) Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.stageSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // Seek slider - uses a draft value while dragging so playback
            // continues smoothly until the user releases.
            val total = durationSec ?: 0.0
            val current = seekDraft ?: positionSec ?: 0.0
            Slider(
                value = if (total > 0) (current / total).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    if (total > 0) seekDraft = (fraction * total).toDouble()
                },
                onValueChangeFinished = {
                    seekDraft?.let { target ->
                        scope.launch { engine.seekTo(target) }
                    }
                    seekDraft = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = total > 0,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = {
                    scope.launch {
                        if (isPaused) engine.play() else engine.pause()
                    }
                }) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                        tint = colors.textPrimary,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = colors.textPrimary)
                }
                Text(
                    text = formatPositionLabel(positionSec, durationSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )

                Spacer(Modifier.weight(1f))

                // Audio + subtitle pickers. The track-list property
                // observer should keep these populated, but mpv doesn't
                // always emit a property-change for the *initial* track
                // load; we re-fetch synchronously when the menu opens
                // so the user never sees an empty list during a session
                // that actually has tracks.
                TrackMenu(
                    icon = Icons.Filled.Audiotrack,
                    label = "Audio",
                    tracks = tracks.filter { it.type == "audio" },
                    onSelect = { id ->
                        scope.launch { engine.selectAudioTrack(id) }
                    },
                    onOpen = { scope.launch { engine.refreshTracksNow() } },
                )
                TrackMenu(
                    icon = Icons.Filled.Subtitles,
                    label = "Subtitles",
                    tracks = tracks.filter { it.type == "sub" },
                    onSelect = { id ->
                        scope.launch { engine.selectSubtitleTrack(id) }
                    },
                    allowOff = true,
                    onOpen = { scope.launch { engine.refreshTracksNow() } },
                )

                AudioDeviceMenu(
                    open = audioDeviceMenuOpen,
                    onOpen = { audioDeviceMenuOpen = true },
                    onDismiss = { audioDeviceMenuOpen = false },
                    devices = audioDevices,
                    onSelect = { name ->
                        scope.launch { engine.setAudioDevice(name) }
                    },
                )

                Spacer(Modifier.width(8.dp))

                // Volume - slider drives mpv; mpv's `volume` property
                // observer drives the slider back so OSC adjustments
                // stay in sync.
                IconButton(onClick = {
                    scope.launch { engine.setMuted(!mpvMuted) }
                }) {
                    Icon(
                        if (mpvMuted || volume == 0) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (mpvMuted) "Unmute" else "Mute",
                        tint = colors.textPrimary,
                    )
                }
                Slider(
                    value = volume.toFloat() / 100f,
                    onValueChange = { f ->
                        val next = (f * 100).toInt().coerceIn(0, 100)
                        if (next != volume) {
                            scope.launch { engine.setVolume(next) }
                            onVolumeChanged?.invoke(next)
                        }
                    },
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    text = "$volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.width(28.dp),
                )

                Spacer(Modifier.width(8.dp))

                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun AudioDeviceMenu(
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    devices: List<MpvPlaybackEngine.MpvAudioDevice>,
    onSelect: (String) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    IconButton(onClick = onOpen) {
        Icon(
            Icons.Filled.Speaker,
            contentDescription = "Audio output",
            tint = colors.textPrimary,
        )
    }
    if (open) {
        FloatingMenuPopup(title = "Audio output", onDismiss = onDismiss) {
            if (devices.isEmpty()) {
                MenuRow(label = "Loading audio devices...", enabled = false) {}
            } else {
                devices.forEach { d ->
                    val display = d.description?.takeIf { it.isNotBlank() } ?: d.name
                    MenuRow(label = display) {
                        onSelect(d.name)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tracks: List<MpvPlaybackEngine.MpvTrack>,
    onSelect: (Int?) -> Unit,
    allowOff: Boolean = false,
    onOpen: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val colors = TorveDesktopThemeTokens.colors
    IconButton(onClick = {
        onOpen()
        open = true
    }) {
        Icon(icon, contentDescription = label, tint = colors.textPrimary)
    }
    if (open) {
        FloatingMenuPopup(title = label, onDismiss = { open = false }) {
            if (tracks.isEmpty()) {
                MenuRow(label = "No $label tracks", enabled = false) {}
            } else {
                if (allowOff) {
                    MenuRow(label = "Off") {
                        onSelect(null)
                        open = false
                    }
                }
                tracks.forEach { t ->
                    val titleParts = listOfNotNull(
                        t.lang?.takeIf { it.isNotBlank() }?.uppercase(),
                        t.title?.takeIf { it.isNotBlank() },
                    )
                    val display = if (titleParts.isEmpty()) "Track ${t.id}" else titleParts.joinToString(" - ")
                    MenuRow(label = if (t.selected) "✓ $display" else display) {
                        onSelect(t.id)
                        open = false
                    }
                }
            }
        }
    }
}

/**
 * Borderless heavyweight popup. Lives in its own JFrame so AWT z-order
 * lets it paint over the embedded MPV canvas (which is heavyweight and
 * always wins inside the parent window). Auto-dismisses on focus loss
 * so it behaves like a regular dropdown rather than a window the user
 * has to chase. Positioned near the bottom-right of the screen where
 * the chrome icons live.
 */
@Composable
private fun FloatingMenuPopup(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val screenSize = remember { java.awt.Toolkit.getDefaultToolkit().screenSize }
    val popupWidthPx = 360
    val popupHeightPx = 420
    val pad = 80
    val state = androidx.compose.ui.window.rememberWindowState(
        width = popupWidthPx.dp,
        height = popupHeightPx.dp,
        position = androidx.compose.ui.window.WindowPosition(
            x = (screenSize.width - popupWidthPx - pad).dp,
            y = (screenSize.height - popupHeightPx - pad).dp,
        ),
    )
    androidx.compose.ui.window.Window(
        onCloseRequest = onDismiss,
        title = title,
        state = state,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
    ) {
        // Auto-dismiss on focus loss so clicking back into the player
        // closes the menu - same UX as a normal dropdown.
        DisposableEffect(Unit) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) {}
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { onDismiss() }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        androidx.compose.material3.Surface(
            color = colors.elevatedSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
            modifier = Modifier.fillMaxSize(),
        ) {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(scrollState),
                content = content,
            )
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.textPrimary else colors.textMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatPositionLabel(pos: Double?, dur: Double?): String {
    val p = pos?.let(::secondsToHms) ?: "--:--"
    val d = dur?.let(::secondsToHms) ?: "--:--"
    return "$p / $d"
}

private fun secondsToHms(value: Double): String {
    if (value.isNaN() || value < 0) return "--:--"
    val total = value.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
