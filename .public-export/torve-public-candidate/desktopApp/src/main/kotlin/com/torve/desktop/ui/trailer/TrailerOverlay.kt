package com.torve.desktop.ui.trailer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.vlc.VlcPlaybackEngine
import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

/**
 * Modal in-app trailer player.
 *
 * Spins up its own dedicated [VlcPlaybackEngine] so trailer playback never
 * disturbs (or competes with) any active main playback session. The engine
 * is released on dismiss. VLC's built-in `youtube.luac` resolver turns the
 * YouTube watch URL into a playable HLS/MP4 stream - no embedded WebView
 * needed, no Chromium dep, and no JxBrowser/JCEF native bundle.
 *
 * The overlay uses callback rendering (RV32 → ImageBitmap) just like the
 * main player surface; the chrome is tiny - a title, a centred 16:9 video
 * area, and a Close button.
 */
@Composable
fun TrailerOverlay(
    youtubeKey: String,
    title: String,
    onDismiss: () -> Unit,
    windowState: WindowState? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val engine = remember { VlcPlaybackEngine() }
    // engine.sessionState is `currentSession?.eventBridge?.state` - null
    // until open() runs. Capturing it at composition time freezes us to a
    // placeholder flow that never emits, so the chrome looks stale.
    // Mirror it into a stable local MutableStateFlow once the session
    // actually exists, then collectAsState observes that.
    val mirroredState = remember(engine) {
        kotlinx.coroutines.flow.MutableStateFlow(com.torve.desktop.vlc.VlcSessionState())
    }
    LaunchedEffect(engine) {
        while (engine.sessionState == null && isActive) {
            delay(50)
        }
        engine.sessionState?.collect { mirroredState.value = it }
    }
    val sessionState by mirroredState.collectAsState()
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastFrameCount by remember { mutableLongStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }
    // Quality selector - null = "Auto" (highest combined mp4 ≤1080p).
    // When the user picks a specific resolution, the resolution-effect is
    // re-keyed and we restart playback at the chosen height.
    var qualityCap by remember { mutableStateOf<Int?>(null) }

    // Resolution chain:
    //  1. Auto-install yt-dlp on first click if missing (one-time ~25MB
    //     download from yt-dlp's official GitHub releases).
    //  2. yt-dlp resolve.
    //  3. NewPipeExtractor as a fallback if yt-dlp couldn't run.
    //  4. Raw watch URL into VLC as the absolute last-ditch attempt.
    LaunchedEffect(engine, youtubeKey, qualityCap) {
        loadFailed = false
        runCatching {
            engine.probeRuntime(startupTrace = null)
            com.torve.desktop.trailer.YtDlpInstaller.ensureInstalled()
            val ytDlpUrl = com.torve.desktop.trailer.YtDlpResolver.resolveDirectUrl(
                youtubeKey = youtubeKey,
                maxHeight = qualityCap,
            )
            val newPipeUrl = if (ytDlpUrl == null) {
                com.torve.desktop.trailer.YouTubeResolver.resolveDirectUrl(youtubeKey)
            } else null
            val playUrl = ytDlpUrl ?: newPipeUrl ?: run {
                println("TORVE TRAILER | both resolvers failed for $youtubeKey; trying raw VLC URL")
                "https://www.youtube.com/watch?v=$youtubeKey"
            }
            println("TORVE TRAILER | resolved via=${
                when {
                    ytDlpUrl != null -> "yt-dlp"
                    newPipeUrl != null -> "newpipe"
                    else -> "vlc-raw"
                }
            } key=$youtubeKey cap=${qualityCap ?: "auto"}")

            // Reuse the existing session when this is a re-resolve (quality
            // change). Calling engine.open() would replace the session and
            // its event bridge, which means the chrome's mirrored state
            // flow disconnects from the live one and starts lying about
            // play/pause and volume again. Retargeting via
            // session.play(url) keeps the same bridge alive.
            val existing = engine.session
            if (existing != null) {
                // Carry over playback position + audio state across the URL
                // swap. VLC's :start-time media option seeks the new
                // stream; volume/mute must be re-applied AFTER VLC has
                // reinitialised its audio device. Order matters - see
                // the analogous block in the open() branch below.
                val savedPositionMs = existing.getTime().coerceAtLeast(0L)
                val savedVolume = existing.getVolume().takeIf { it > 0 } ?: TRAILER_DEFAULT_VOLUME
                val savedMuted = existing.isMuted()
                val startSec = savedPositionMs / 1000.0
                existing.play(playUrl, ":start-time=$startSec")
                kotlinx.coroutines.delay(400)
                runCatching { existing.setMute(savedMuted) }
                runCatching { existing.setVolume(savedVolume) }
            } else {
                currentFrame = null
                val mediaItem = com.torve.domain.model.MediaItem(
                    id = "trailer:$youtubeKey",
                    tmdbId = null,
                    imdbId = null,
                    type = com.torve.domain.model.MediaType.MOVIE,
                    title = title,
                )
                val request = com.torve.desktop.playback.DesktopPlaybackRequest(
                    mediaId = "trailer:$youtubeKey",
                    mediaType = com.torve.domain.model.MediaType.MOVIE,
                    title = title,
                    tmdbId = null,
                    imdbId = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    artworkUrl = null,
                    sourceSurface = "trailer",
                )
                val session = com.torve.desktop.playback.DesktopPlaybackSession(
                    request = request,
                    mediaItem = mediaItem,
                    resolvedUrl = playUrl,
                )
                engine.open(session = session, autoPlay = true, startupTrace = null, resumePositionMs = null)
                // VLC's bootstrap publishes whatever the audio device
                // reports during initial open - often `mute=true,
                // volume=0` while the device is still initialising.
                // Order matters here: VlcPlayerSession.setVolume publishes
                // refreshVolume(volumeParam, audio.isMute) - queries the
                // mute. setMute publishes refreshVolume(audio.volume,
                // muteParam) - queries the volume. So if we call
                // setVolume(N) then setMute(b), the second call overwrites
                // our N with VLC's stale audio.volume (still 0). Calling
                // setMute first, then setVolume, ends with the volume
                // param sticking and the mute query reading the value we
                // just set.
                kotlinx.coroutines.delay(500)
                engine.session?.let { newly ->
                    runCatching { newly.setMute(false) }
                    runCatching { newly.setVolume(TRAILER_DEFAULT_VOLUME) }
                }
            }
        }.onFailure {
            loadFailed = true
            println("TORVE TRAILER | engine open failed: ${it.message}")
        }
    }

    // Surface VLC error events so we can show a friendly message instead of
    // a black box if YouTube's URL doesn't resolve (geo-block, removed video,
    // ancient libvlc without youtube.luac, etc.).
    LaunchedEffect(engine) {
        engine.events.collect { event ->
            if (event is DesktopPlaybackEngineEvent.Error) loadFailed = true
        }
    }

    // Frame poller - same pattern as VlcComposePlayerSurface. Stops when the
    // composable leaves the tree.
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

    DisposableEffect(engine) {
        onDispose {
            runCatching { engine.dispose() }
        }
    }

    // Subtitle drag-and-drop: while the trailer overlay is mounted,
    // subscribe to the same SubtitleDropBus the main player uses. The
    // AWT DropTarget is installed on the JFrame root pane so dropping
    // onto the trailer surface fires the same event.
    DisposableEffect(engine) {
        val handle = com.torve.desktop.dnd.SubtitleDropBus.subscribe { droppedPath ->
            val session = engine.session ?: return@subscribe
            val uri = java.io.File(droppedPath).toURI().toString()
            kotlinx.coroutines.runBlocking {
                runCatching {
                    session.addSubtitleFile(uri)
                    session.refreshTracks()
                }
            }
        }
        onDispose { handle.unsubscribe() }
    }

    // (Force-correct of mute/volume now happens explicitly inside the
    // resolve LaunchedEffect's open + play branches, after a 400-500ms
    // grace for VLC's audio device to settle. The state-keyed approach
    // here was racy - it fired once on isPlaying transition but the
    // bad state often arrived after.)

    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        // In fullscreen, pop back to floating first; second
                        // Escape dismisses. Mirrors the main player's pattern.
                        if (isFullscreen && windowState != null) {
                            windowState.placement = WindowPlacement.Floating
                        } else {
                            onDismiss()
                        }
                        true
                    }
                    Key.Spacebar -> {
                        kotlinx.coroutines.runBlocking {
                            if (engine.isPlaying()) engine.pause() else engine.play()
                        }
                        true
                    }
                    Key.F, Key.F11 -> {
                        windowState?.let {
                            it.placement = if (it.placement == WindowPlacement.Fullscreen)
                                WindowPlacement.Floating else WindowPlacement.Fullscreen
                        }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isFullscreen) {
            FullscreenTrailerLayout(
                title = title,
                youtubeKey = youtubeKey,
                engine = engine,
                sessionState = sessionState,
                currentFrame = currentFrame,
                loadFailed = loadFailed,
                colors = colors,
                qualityCap = qualityCap,
                onQualityChange = { qualityCap = it },
                onExitFullscreen = { windowState?.placement = WindowPlacement.Floating },
                onDismiss = onDismiss,
            )
        } else {
            PopupTrailerLayout(
                title = title,
                youtubeKey = youtubeKey,
                engine = engine,
                sessionState = sessionState,
                currentFrame = currentFrame,
                loadFailed = loadFailed,
                colors = colors,
                hasWindowState = windowState != null,
                qualityCap = qualityCap,
                onQualityChange = { qualityCap = it },
                onEnterFullscreen = { windowState?.placement = WindowPlacement.Fullscreen },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Popup layout - Surface auto-sizes to content (no fillMaxSize wrapper)
 * so the panel hugs the column and there is no dead space below the
 * chrome bar.
 */
@Composable
private fun PopupTrailerLayout(
    title: String,
    youtubeKey: String,
    engine: VlcPlaybackEngine,
    sessionState: com.torve.desktop.vlc.VlcSessionState,
    currentFrame: ImageBitmap?,
    loadFailed: Boolean,
    colors: com.torve.desktop.ui.theme.TorveDesktopColors,
    hasWindowState: Boolean,
    qualityCap: Int?,
    onQualityChange: (Int?) -> Unit,
    onEnterFullscreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(960.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .clickable(enabled = false, onClick = {}),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasWindowState) {
                    IconButton(onClick = onEnterFullscreen) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "Enter fullscreen",
                            tint = colors.textPrimary,
                        )
                    }
                }
                IconButton(onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(
                            java.net.URI("https://www.youtube.com/watch?v=$youtubeKey"),
                        )
                    }
                }) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = "Open on YouTube",
                        tint = colors.textPrimary,
                    )
                }
                TorveGhostButton(text = "Close", onClick = onDismiss)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                VideoOrPlaceholder(
                    currentFrame = currentFrame,
                    loadFailed = loadFailed,
                    youtubeKey = youtubeKey,
                    accentColor = colors.accent,
                    onDismiss = onDismiss,
                )
            }

            if (currentFrame != null && !loadFailed) {
                TrailerChrome(
                    engine = engine,
                    sessionState = sessionState,
                    accentColor = colors.accent,
                    textPrimary = colors.textPrimary,
                    textSecondary = colors.textSecondary,
                    qualityCap = qualityCap,
                    onQualityChange = onQualityChange,
                )
            }
        }
    }
}

/**
 * Fullscreen layout - video fills the entire window via fillMaxSize +
 * ContentScale.Fit, and chrome floats over the video at the bottom on a
 * translucent background. Top-right floating cluster replaces the popup
 * header.
 */
@Composable
private fun FullscreenTrailerLayout(
    title: String,
    youtubeKey: String,
    engine: VlcPlaybackEngine,
    sessionState: com.torve.desktop.vlc.VlcSessionState,
    currentFrame: ImageBitmap?,
    loadFailed: Boolean,
    colors: com.torve.desktop.ui.theme.TorveDesktopColors,
    qualityCap: Int?,
    onQualityChange: (Int?) -> Unit,
    onExitFullscreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxSize().clickable(enabled = false, onClick = {}),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Video / placeholder takes the full screen.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                VideoOrPlaceholder(
                    currentFrame = currentFrame,
                    loadFailed = loadFailed,
                    youtubeKey = youtubeKey,
                    accentColor = colors.accent,
                    onDismiss = onDismiss,
                )
            }

            // Floating top-right buttons.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 128.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
            ) {
                IconButton(onClick = onExitFullscreen) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(
                            java.net.URI("https://www.youtube.com/watch?v=$youtubeKey"),
                        )
                    }
                }) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = "Open on YouTube",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }

            // Bottom chrome bar floats over the video.
            if (currentFrame != null && !loadFailed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    TrailerChrome(
                        engine = engine,
                        sessionState = sessionState,
                        accentColor = colors.accent,
                        textPrimary = Color.White,
                        textSecondary = Color.White.copy(alpha = 0.7f),
                        qualityCap = qualityCap,
                        onQualityChange = onQualityChange,
                    )
                }
            }
        }
    }
}

/**
 * Inner surface - either the live VLC frame, the failure recovery card,
 * or the loading / installer-progress state. Used by both popup and
 * fullscreen layouts.
 */
@Composable
private fun VideoOrPlaceholder(
    currentFrame: ImageBitmap?,
    loadFailed: Boolean,
    youtubeKey: String,
    accentColor: Color,
    onDismiss: () -> Unit,
) {
    when {
        loadFailed -> {
            val ytDlpAvailable = remember {
                com.torve.desktop.trailer.YtDlpResolver.isAvailable()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "In-app trailer playback failed.",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Text(
                    text = if (ytDlpAvailable) {
                        "yt-dlp couldn't fetch the stream (likely a YouTube update - yt-dlp may need an upgrade). Opening the trailer on YouTube as a workaround."
                    } else {
                        "YouTube blocks generic scrapers, and our bundled extractor is currently broken. Install yt-dlp for reliable in-app trailers (drop yt-dlp.exe into Torve's data folder, or `brew install yt-dlp`). For now, open on YouTube."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                TorveGhostButton(
                    text = "Open on YouTube",
                    onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(
                                java.net.URI("https://www.youtube.com/watch?v=$youtubeKey"),
                            )
                        }
                        onDismiss()
                    },
                )
            }
        }
        currentFrame != null -> {
            Image(
                bitmap = currentFrame,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> {
            val installerState by com.torve.desktop.trailer.YtDlpInstaller.state.collectAsState()
            val installerProgress by com.torve.desktop.trailer.YtDlpInstaller.progressBytes.collectAsState()
            val installerTotal by com.torve.desktop.trailer.YtDlpInstaller.totalBytes.collectAsState()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = accentColor)
                Text(
                    text = if (installerState == com.torve.desktop.trailer.YtDlpInstaller.State.DOWNLOADING) {
                        val mb = installerProgress / 1_000_000
                        val totalMb = installerTotal?.let { it / 1_000_000 }
                        if (totalMb != null) "Installing trailer helper...  $mb / $totalMb MB"
                        else "Installing trailer helper...  $mb MB"
                    } else {
                        "Loading trailer..."
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (installerState == com.torve.desktop.trailer.YtDlpInstaller.State.DOWNLOADING) {
                    Text(
                        text = "One-time ~25MB download from yt-dlp's official GitHub releases.",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrailerChrome(
    engine: VlcPlaybackEngine,
    sessionState: com.torve.desktop.vlc.VlcSessionState,
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    qualityCap: Int? = null,
    onQualityChange: ((Int?) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Time scrubber
        val durationMs = sessionState.durationMs.coerceAtLeast(0L)
        val positionFraction = if (durationMs > 0L) {
            (sessionState.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
        Slider(
            value = positionFraction,
            onValueChange = { fraction ->
                val target = (durationMs * fraction).toLong().coerceIn(0L, durationMs)
                engine.session?.let { session ->
                    kotlinx.coroutines.runBlocking { session.seek(target) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = textSecondary.copy(alpha = 0.35f),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play / pause toggle.
            IconButton(onClick = {
                kotlinx.coroutines.runBlocking {
                    if (engine.isPlaying()) engine.pause() else engine.play()
                }
            }) {
                Icon(
                    if (sessionState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (sessionState.isPlaying) "Pause" else "Play",
                    tint = textPrimary,
                )
            }

            // Time readout.
            Text(
                text = "${formatTime(sessionState.positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = textSecondary,
            )

            Box(modifier = Modifier.width(0.dp)) // weight stand-in eaten by Spacer below
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

            // Buffering hint when in flight.
            if (sessionState.isBuffering) {
                Text(
                    text = "Buffering ${sessionState.bufferedPercent.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondary,
                )
            }

            // Quality picker - when the user changes resolution we
            // re-trigger the LaunchedEffect upstream to re-resolve the
            // stream URL at that height. Restarts from the beginning;
            // position-keeping isn't worth the complexity for trailers.
            if (onQualityChange != null) {
                var qualityMenu by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.TextButton(onClick = { qualityMenu = true }) {
                        Text(
                            text = qualityLabel(qualityCap),
                            color = textPrimary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = qualityMenu,
                        onDismissRequest = { qualityMenu = false },
                    ) {
                        listOf(null, 1080, 720, 480, 360).forEach { cap ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        text = qualityLabel(cap) +
                                            if (cap == qualityCap) "  ✓" else "",
                                    )
                                },
                                onClick = {
                                    qualityMenu = false
                                    if (cap != qualityCap) onQualityChange(cap)
                                },
                            )
                        }
                    }
                }
            }

            // Volume mute toggle.
            IconButton(onClick = {
                engine.session?.let { session ->
                    kotlinx.coroutines.runBlocking {
                        session.setMute(!engine.isMuted())
                    }
                }
            }) {
                Icon(
                    if (sessionState.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (sessionState.isMuted) "Unmute" else "Mute",
                    tint = textPrimary,
                )
            }
            // Volume slider.
            Slider(
                value = sessionState.volume.toFloat(),
                onValueChange = { value ->
                    engine.session?.let { session ->
                        kotlinx.coroutines.runBlocking { session.setVolume(value.toInt()) }
                    }
                },
                valueRange = 0f..200f,
                modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(
                    thumbColor = textPrimary,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = textSecondary.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    val sStr = if (s < 10) "0$s" else s.toString()
    return "$m:$sStr"
}

private fun qualityLabel(cap: Int?): String = when (cap) {
    null -> "Auto"
    else -> "${cap}p"
}

/**
 * Initial volume the trailer player snaps to once VLC's audio device
 * settles. Polite default - louder than 50% (which feels weak for short
 * trailers) but not slamming at 100%. User can drag the slider to any
 * value in [0, 200] from there.
 */
private const val TRAILER_DEFAULT_VOLUME: Int = 75
