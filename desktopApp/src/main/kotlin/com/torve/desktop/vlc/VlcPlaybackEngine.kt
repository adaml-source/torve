package com.torve.desktop.vlc

import com.torve.desktop.playback.DesktopPlaybackEngine
import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.DesktopPlaybackSubtitleCandidate
import com.torve.desktop.playback.PlaybackStartupTrace
import com.torve.desktop.player.DesktopPlayerDiagnostics
import com.torve.desktop.player.VlcDiagnostics
import com.torve.desktop.player.VlcMediaOptions
import com.torve.desktop.player.VlcRuntimeLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.renderer.RendererDiscoverer
import uk.co.caprica.vlcj.player.renderer.RendererDiscovererDescription

/**
 * Application-level entry point for VLC-based desktop playback.
 *
 * Owns the [MediaPlayerFactory] lifecycle and the [VlcCommandChannel].
 * Creates [VlcPlayerSession] instances for each playback session.
 *
 * Implements [DesktopPlaybackEngine] so the existing [DesktopPlayerController]
 * can use it without modification.
 */
class VlcPlaybackEngine : DesktopPlaybackEngine {

    override val backendLabel = "VLC (direct rendering)"
    override val runtimeRequirement =
        "Install VLC media player (64-bit) or place VLC runtime in desktopApp/runtime/windows/vlc/."
    override val isEmbedded = true

    private val _events = MutableSharedFlow<DesktopPlaybackEngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<DesktopPlaybackEngineEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var factory: MediaPlayerFactory? = null
    private val commandChannel = VlcCommandChannel()
    private var currentSession: VlcPlayerSession? = null
    private var eventCollectionJob: Job? = null
    private var runtimeDiscovery: VlcRuntimeLocator.DiscoveryResult? = null

    /** The active session's frame renderer, exposed for Compose rendering. */
    val frameRenderer: VlcFrameRenderer? get() = currentSession?.frameRenderer
    val videoCanvas: java.awt.Canvas? get() = currentSession?.awtCanvas

    /** The active session's state, exposed for Compose observation. */
    val sessionState get() = currentSession?.eventBridge?.state

    /** The active session, for direct access to commands. */
    val session: VlcPlayerSession? get() = currentSession

    /**
     * List of available renderer discoverers (e.g. microdns for Chromecast /
     * UPnP / AirPlay). Returns empty when the VLC runtime isn't ready.
     */
    fun rendererDescriptions(): List<RendererDiscovererDescription> {
        return factory?.renderers()?.discoverers().orEmpty()
    }

    /**
     * Create a new renderer discoverer by name. Caller owns the lifecycle -
     * must call `start()` / `stop()` / `release()`. Returns null if VLC isn't
     * ready or the name isn't supported.
     */
    fun createDiscoverer(name: String): RendererDiscoverer? {
        return factory?.renderers()?.discoverer(name)
    }

    // ── DesktopPlaybackEngine ───────────────────────────────────

    override suspend fun probeRuntime(startupTrace: PlaybackStartupTrace?) {
        if (factory != null) {
            emitProbeSuccess()
            return
        }

        startupTrace?.mark("VLC runtime discovery start")
        val discovery = VlcRuntimeLocator.discover()
        runtimeDiscovery = discovery
        startupTrace?.mark("VLC runtime discovery end", discovery.discoverySource ?: discovery.diagnosticMessage)

        if (!discovery.found) {
            emit(DesktopPlaybackEngineEvent.RuntimeProbe(
                found = false,
                attemptedPaths = discovery.attemptedPaths,
                message = discovery.diagnosticMessage,
            ))
            return
        }

        VlcRuntimeLocator.applyDiscovery(discovery)

        try {
            startupTrace?.mark("LibVLC factory init start", discovery.vlcDirectory)
            factory = MediaPlayerFactory(*VlcMediaOptions.factoryArgs().toTypedArray())
            startupTrace?.mark("LibVLC factory init end")
            emitProbeSuccess()
        } catch (e: Exception) {
            println("TORVE VLC ENGINE | factory init failed: ${e.message}")
            emit(DesktopPlaybackEngineEvent.RuntimeProbe(
                found = false,
                executablePath = discovery.vlcDirectory,
                message = "VLC found but failed to initialize: ${e.message}",
            ))
            startupTrace?.complete("runtime-init-failed")
        }
    }

    /** Preferred subtitle language code (e.g. "eng", "deu"). Set by controller before open(). */
    var preferredSubtitleLanguage: String? = null

    override suspend fun open(session: DesktopPlaybackSession, autoPlay: Boolean, startupTrace: PlaybackStartupTrace?, resumePositionMs: Long?) {
        val f = factory ?: throw IllegalStateException("VLC not initialized. Call probeRuntime first.")
        val url = session.resolvedUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No resolved URL in session.")

        // Release any existing session
        currentSession?.release()

        // Create new session
        val eventBridge = VlcEventBridge()
        val frameRenderer = VlcFrameRenderer { w, h -> eventBridge.setVideoDimensions(w, h) }
        val playerSession = VlcPlayerSession(f, commandChannel, eventBridge, frameRenderer)
        playerSession.initialize()
        currentSession = playerSession

        // Collect engine events from the session's bridge into our shared flow
        eventCollectionJob?.cancel()
        eventCollectionJob = scope.launch {
            eventBridge.engineEvents.collect { _events.tryEmit(it) }
        }

        // Set context for event enrichment
        val selectedSub = selectSubtitle(session.subtitleCandidates)
        eventBridge.mediaPath = url
        eventBridge.headerCount = session.requestHeaders.size
        eventBridge.subtitleLabel = selectedSub?.label

        emit(DesktopPlaybackEngineEvent.Opening(
            mediaPath = url,
            autoPlay = autoPlay,
            appliedHeaderCount = session.requestHeaders.size,
            selectedSubtitleLabel = selectedSub?.label,
        ))

        // Build media options
        val options = VlcMediaOptions.mediaOptions(
            mediaPath = url,
            requestHeaders = session.requestHeaders,
            subtitleUrl = selectedSub?.url,
            startTimeMs = resumePositionMs,
        )

        startupTrace?.mark("media open start", if (autoPlay) "autoplay" else "paused")
        if (autoPlay) {
            playerSession.play(url, *options.toTypedArray())
        } else {
            playerSession.startPaused(url, *options.toTypedArray())
        }
        startupTrace?.mark("media open end")
    }

    override suspend fun play() { currentSession?.resume() }
    override suspend fun pause() { currentSession?.pause() }
    override suspend fun stop() { currentSession?.stop() }

    override suspend fun close() {
        currentSession?.stop()
        currentSession?.release()
        currentSession = null
        emit(DesktopPlaybackEngineEvent.Closed("Closed by desktop player."))
    }

    override fun dispose() {
        println("TORVE VLC ENGINE | dispose")
        eventCollectionJob?.cancel()
        currentSession?.release()
        currentSession = null
        commandChannel.shutdown()
        factory?.release()
        factory = null
        scope.cancel()
    }

    // ── Convenience for the controller ──────────────────────────

    fun isPlaying(): Boolean = currentSession?.isPlaying() ?: false
    fun isPaused(): Boolean = currentSession?.isPaused() ?: false
    fun getTime(): Long = currentSession?.getTime() ?: 0
    fun getLength(): Long = currentSession?.getLength() ?: 0
    fun getRate(): Float = currentSession?.getRate() ?: 1f
    fun getVolume(): Int = currentSession?.getVolume() ?: 100
    fun isMuted(): Boolean = currentSession?.isMuted() ?: false
    fun currentMediaPath(): String? = currentSession?.eventBridge?.mediaPath
    fun readDiagnostics(): DesktopPlayerDiagnostics {
        return currentSession?.readDiagnostics(
            engineName = backendLabel,
            runtimePath = runtimeDiscovery?.vlcDirectory,
        ) ?: VlcDiagnostics.snapshot(
            mediaPlayer = null,
            factory = factory,
            engineName = backendLabel,
            runtimePath = runtimeDiscovery?.vlcDirectory,
        )
    }

    // ── Internal ────────────────────────────────────────────────

    private fun emitProbeSuccess() {
        val d = runtimeDiscovery
        emit(DesktopPlaybackEngineEvent.RuntimeProbe(
            found = true, executablePath = d?.vlcDirectory,
            discoverySource = d?.discoverySource, message = "VLC player ready.",
        ))
        emit(DesktopPlaybackEngineEvent.RuntimeReady(
            executablePath = d?.vlcDirectory ?: "vlc",
            discoverySource = d?.discoverySource ?: "VlcRuntimeLocator",
            processId = ProcessHandle.current().pid(),
        ))
    }

    private fun selectSubtitle(subtitles: List<DesktopPlaybackSubtitleCandidate>): DesktopPlaybackSubtitleCandidate? {
        val preferred = preferredSubtitleLanguage?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            val match = subtitles.firstOrNull { it.language.equals(preferred, ignoreCase = true) }
                ?: subtitles.firstOrNull { it.label.contains(preferred, ignoreCase = true) }
            if (match != null) return match
        }
        // Default fallback: English, then first available
        return subtitles.firstOrNull { it.language.equals("en", ignoreCase = true) }
            ?: subtitles.firstOrNull { it.label.contains("english", ignoreCase = true) }
            ?: subtitles.firstOrNull()
    }

    private fun emit(event: DesktopPlaybackEngineEvent) {
        _events.tryEmit(event)
    }
}
