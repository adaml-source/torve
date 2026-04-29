package com.torve.desktop.player

import com.torve.desktop.playback.DesktopPlaybackEngine
import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.DesktopPlaybackSubtitleCandidate
import com.torve.desktop.playback.PlaybackStartupTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class VlcDesktopPlaybackEngine : DesktopPlaybackEngine {

    override val backendLabel = "Embedded VLC player"
    override val runtimeRequirement =
        "Playback runtime is missing from this Torve build. Reinstall Torve from the official " +
            "release page, or contact support if reinstalling doesn't fix it."
    override val isEmbedded = true

    private val _events = MutableSharedFlow<DesktopPlaybackEngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<DesktopPlaybackEngineEvent> = _events

    private var factory: MediaPlayerFactory? = null
    private var playerComponent: CallbackMediaPlayerComponent? = null
    private val mediaPlayer: MediaPlayer? get() = playerComponent?.mediaPlayer()

    private val stateMapper = VlcPlayerStateMapper(::emit)
    private var runtimeDiscovery: VlcRuntimeLocator.DiscoveryResult? = null
    private var currentBufferingPreset = DesktopBufferingPreset.AUTO
    private var currentAspectMode = DesktopAspectMode.DEFAULT
    private var lastBufferingPercent: Float? = null
    private var lastPlaybackState = "Idle"
    private var lastOpenedMediaUrl: String? = null
    private var currentStartupTrace: PlaybackStartupTrace? = null

    val volume = VlcVolumeController { mediaPlayer }
    val audio = VlcAudioController { mediaPlayer }
    val subtitle = VlcSubtitleController { mediaPlayer }
    val fullscreen = VlcFullscreenController()
    val equalizer = VlcEqualizerController({ factory }, { mediaPlayer })

    val videoSurfaceComponent: Component? get() = playerComponent

    override suspend fun probeRuntime(startupTrace: PlaybackStartupTrace?) {
        currentStartupTrace = startupTrace ?: currentStartupTrace
        if (playerComponent != null && factory != null) {
            val discovery = runtimeDiscovery
            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = true,
                    executablePath = discovery?.vlcDirectory,
                    discoverySource = discovery?.discoverySource,
                    message = "VLC player ready.",
                ),
            )
            emit(
                DesktopPlaybackEngineEvent.RuntimeReady(
                    executablePath = discovery?.vlcDirectory ?: "vlc",
                    discoverySource = discovery?.discoverySource ?: "VlcRuntimeLocator",
                    processId = ProcessHandle.current().pid(),
                ),
            )
            return
        }
        val trace = startupTrace ?: currentStartupTrace
        trace?.mark("VLC runtime discovery start")
        val discovery = VlcRuntimeLocator.discover()
        runtimeDiscovery = discovery
        trace?.mark(
            stage = "VLC runtime discovery end",
            detail = discovery.discoverySource ?: discovery.diagnosticMessage,
        )

        if (!discovery.found) {
            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = false,
                    attemptedPaths = discovery.attemptedPaths,
                    message = discovery.diagnosticMessage,
                ),
            )
            return
        }

        VlcRuntimeLocator.applyDiscovery(discovery)

        try {
            trace?.mark("LibVLC init start", discovery.vlcDirectory)
            val factoryInstance = MediaPlayerFactory(*VlcMediaOptions.factoryArgs().toTypedArray())
            factory = factoryInstance
            playerComponent = CallbackMediaPlayerComponent(factoryInstance, null, null, true, null, null, null)
            trace?.mark("LibVLC init end")

            trace?.mark("media player creation start")
            mediaPlayer?.events()?.addMediaPlayerEventListener(stateMapper.createEventAdapter())
            trace?.mark("media player creation end")

            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = true,
                    executablePath = discovery.vlcDirectory,
                    discoverySource = discovery.discoverySource,
                    message = "VLC player ready.",
                ),
            )
            emit(
                DesktopPlaybackEngineEvent.RuntimeReady(
                    executablePath = discovery.vlcDirectory ?: "vlc",
                    discoverySource = discovery.discoverySource ?: "VlcRuntimeLocator",
                    processId = ProcessHandle.current().pid(),
                ),
            )
            prewarmEmbeddedUi()
        } catch (e: Exception) {
            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = false,
                    executablePath = discovery.vlcDirectory,
                    message = "VLC found but failed to initialize: ${e.message}",
                ),
            )
            trace?.complete("runtime-init-failed")
        }
    }

    override suspend fun open(session: DesktopPlaybackSession, autoPlay: Boolean, startupTrace: PlaybackStartupTrace?, resumePositionMs: Long?) {
        val mp = mediaPlayer
            ?: throw IllegalStateException("VLC player not initialized. Call probeRuntime first.")
        val url = session.resolvedUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No resolved URL in session.")
        currentStartupTrace = startupTrace

        val selectedSub = selectSubtitle(session.subtitleCandidates)
        stateMapper.updateContext(
            mediaPath = url,
            headerCount = session.requestHeaders.size,
            subtitleLabel = selectedSub?.label,
            bufferingModeLabel = currentBufferingPreset.label,
        )
        lastOpenedMediaUrl = url

        emit(
            DesktopPlaybackEngineEvent.Opening(
                mediaPath = url,
                autoPlay = autoPlay,
                appliedHeaderCount = session.requestHeaders.size,
                selectedSubtitleLabel = selectedSub?.label,
                bufferingModeLabel = currentBufferingPreset.label,
            ),
        )

        val options = VlcMediaOptions.mediaOptions(
            mediaPath = url,
            requestHeaders = session.requestHeaders,
            subtitleUrl = selectedSub?.url,
            bufferingPreset = currentBufferingPreset,
        )

        currentStartupTrace?.mark("media open start", if (autoPlay) "autoplay" else "paused")
        if (autoPlay) {
            mp.media().play(url, *options.toTypedArray())
        } else {
            mp.media().startPaused(url, *options.toTypedArray())
        }
        currentStartupTrace?.mark("media open end")
    }

    override suspend fun play() {
        mediaPlayer?.controls()?.play()
    }

    override suspend fun pause() {
        mediaPlayer?.controls()?.pause()
    }

    override suspend fun stop() {
        mediaPlayer?.controls()?.stop()
    }

    override suspend fun close() {
        fullscreen.exitFullscreen()
        mediaPlayer?.controls()?.stop()
        emit(DesktopPlaybackEngineEvent.Closed("Closed by desktop player."))
    }

    override fun dispose() {
        fullscreen.exitFullscreen()
        mediaPlayer?.controls()?.stop()
        playerComponent?.release()
        playerComponent = null
        factory?.release()
        factory = null
        lastBufferingPercent = null
        lastPlaybackState = "Idle"
        currentStartupTrace = null
    }

    fun seek(timeMs: Long) {
        mediaPlayer?.controls()?.setTime(timeMs.coerceAtLeast(0))
    }

    fun seekRelative(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        val target = (mp.status().time() + deltaMs).coerceAtLeast(0)
        mp.controls().setTime(target)
    }

    fun getTime(): Long = mediaPlayer?.status()?.time() ?: 0

    fun getLength(): Long = mediaPlayer?.status()?.length() ?: 0

    fun isPlaying(): Boolean = mediaPlayer?.status()?.isPlaying ?: false

    fun isPaused(): Boolean {
        val mp = mediaPlayer ?: return false
        return !mp.status().isPlaying && mp.status().isPlayable
    }

    fun isBuffering(): Boolean = lastBufferingPercent != null

    fun getBufferingPercent(): Float? = lastBufferingPercent

    fun currentPlaybackStateLabel(): String = lastPlaybackState

    fun getPlaybackRate(): Float = mediaPlayer?.status()?.rate() ?: 1f

    fun setPlaybackRate(rate: Float) {
        mediaPlayer?.controls()?.setRate(rate.coerceIn(0.25f, 4f))
    }

    fun getAspectRatio(): String? = mediaPlayer?.video()?.aspectRatio()

    fun getAspectMode(): DesktopAspectMode = currentAspectMode

    fun setAspectRatio(mode: DesktopAspectMode) {
        val mp = mediaPlayer ?: return
        currentAspectMode = mode
        when (mode) {
            DesktopAspectMode.DEFAULT, DesktopAspectMode.FIT -> {
                mp.video().setAspectRatio(null)
                mp.video().setCropGeometry(null)
                mp.video().setScale(0f)
            }

            DesktopAspectMode.FILL -> {
                mp.video().setAspectRatio(null)
                mp.video().setScale(1.1f)
            }

            DesktopAspectMode.ORIGINAL -> {
                mp.video().setAspectRatio(null)
                mp.video().setCropGeometry(null)
                mp.video().setScale(1f)
            }

            else -> {
                mp.video().setCropGeometry(null)
                mp.video().setScale(0f)
                mp.video().setAspectRatio(mode.vlcValue)
            }
        }
    }

    fun getBufferingPreset(): DesktopBufferingPreset = currentBufferingPreset

    fun setBufferingPreset(preset: DesktopBufferingPreset) {
        currentBufferingPreset = preset
    }

    fun takeSnapshot(filePath: String, width: Int = 0, height: Int = 0): Boolean {
        return mediaPlayer?.snapshots()?.save(java.io.File(filePath), width, height) ?: false
    }

    fun captureSnapshotToLibrary(): String? {
        val snapshotDirectory = defaultSnapshotDirectory()
        Files.createDirectories(snapshotDirectory)
        val snapshotPath = snapshotDirectory.resolve(
            "torve-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())}.png",
        )
        return if (takeSnapshot(snapshotPath.toString())) snapshotPath.toString() else null
    }

    fun readDiagnostics(): DesktopPlayerDiagnostics {
        return VlcDiagnostics.snapshot(
            mediaPlayer = mediaPlayer,
            factory = factory,
            runtimePath = runtimeDiscovery?.vlcDirectory,
            bufferingModeLabel = currentBufferingPreset.label,
        )
    }

    fun getRuntimeDiscovery(): VlcRuntimeLocator.DiscoveryResult? = runtimeDiscovery

    fun getSelectedAudioTrackLabel(): String? = mediaPlayer?.let(VlcTrackModel::selectedAudioTrackLabel)

    fun getSelectedSubtitleTrackLabel(): String? = mediaPlayer?.let(VlcTrackModel::selectedSubtitleTrackLabel)

    fun getMediaUrl(): String? = lastOpenedMediaUrl

    fun obtainChromeHost(
        onClose: () -> Unit = {},
        onStop: () -> Unit = {},
    ): VlcPlayerChromeHost {
        return VlcPlayerChromeHost(
            engine = this,
            onClose = onClose,
            onStop = onStop,
        )
    }

    fun obtainSurfaceHost(
        callbacks: VlcSurfaceCallbacks,
        chromeHost: VlcPlayerChromeHost = obtainChromeHost(),
    ): VlcVideoSurfaceHost {
        return VlcVideoSurfaceHost(
            engine = this,
            callbacks = callbacks,
            chromeHost = chromeHost,
        )
    }

    fun prewarmEmbeddedUi() {
        // UI hosts are composition-owned. Prewarming only the VLC runtime avoids
        // stale Swing components surviving a disposed Compose scene.
    }

    fun markSurfaceAttachStart(detail: String? = null) {
        currentStartupTrace?.mark("surface/chrome attach start", detail)
    }

    fun markSurfaceAttachEnd(detail: String? = null) {
        currentStartupTrace?.mark("surface/chrome attach end", detail)
    }

    private fun defaultSnapshotDirectory(): Path {
        return Paths.get(System.getProperty("user.home"), "Pictures", "Torve", "Snapshots")
    }

    private fun selectSubtitle(subtitles: List<DesktopPlaybackSubtitleCandidate>): DesktopPlaybackSubtitleCandidate? {
        return subtitles.firstOrNull { it.language.equals("en", ignoreCase = true) }
            ?: subtitles.firstOrNull { it.label.contains("english", ignoreCase = true) }
            ?: subtitles.firstOrNull()
    }

    private fun emit(event: DesktopPlaybackEngineEvent) {
        when (event) {
            is DesktopPlaybackEngineEvent.Opening -> {
                lastPlaybackState = "Opening"
                lastBufferingPercent = 0f
                lastOpenedMediaUrl = event.mediaPath
            }

            is DesktopPlaybackEngineEvent.Buffering -> {
                lastPlaybackState = "Buffering"
                lastBufferingPercent = event.cachePercent
                event.mediaPath?.let { lastOpenedMediaUrl = it }
                currentStartupTrace?.markOnce("first buffering callback", "${event.cachePercent.toInt()}%")
            }

            is DesktopPlaybackEngineEvent.Playing -> {
                lastPlaybackState = "Playing"
                lastBufferingPercent = null
                event.mediaPath?.let { lastOpenedMediaUrl = it }
                currentStartupTrace?.markOnce("first playing callback")
            }

            is DesktopPlaybackEngineEvent.FirstFrame -> {
                event.mediaPath?.let { lastOpenedMediaUrl = it }
                currentStartupTrace?.markOnce("first frame shown")
                currentStartupTrace?.complete("first-frame")
                currentStartupTrace = null
            }

            is DesktopPlaybackEngineEvent.Paused -> {
                lastPlaybackState = "Paused"
                lastBufferingPercent = null
                event.mediaPath?.let { lastOpenedMediaUrl = it }
            }

            is DesktopPlaybackEngineEvent.Stopped -> {
                lastPlaybackState = "Stopped"
                lastBufferingPercent = null
            }

            is DesktopPlaybackEngineEvent.Closed -> {
                lastPlaybackState = "Closed"
                lastBufferingPercent = null
            }

            is DesktopPlaybackEngineEvent.Error -> {
                lastPlaybackState = "Error"
                lastBufferingPercent = null
                currentStartupTrace?.complete("engine-error:${event.code}")
                currentStartupTrace = null
            }

            else -> Unit
        }
        _events.tryEmit(event)
    }
}
