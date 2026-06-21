package com.torve.desktop.vlc

import com.torve.desktop.player.DesktopPlayerDiagnostics
import com.torve.desktop.player.VlcDiagnostics
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.renderer.RendererItem
import java.awt.Canvas
import java.awt.Color as AwtColor

/**
 * Represents one playback session with one [MediaPlayer] instance.
 *
 * All native commands are routed through the [VlcCommandChannel] to ensure
 * they execute on a single dedicated thread. No UI code should call
 * the media player directly.
 *
 * Call [release] when the session is over. Do not reuse a released session.
 */
class VlcPlayerSession(
    private val factory: MediaPlayerFactory,
    private val commandChannel: VlcCommandChannel,
    val eventBridge: VlcEventBridge,
    val frameRenderer: VlcFrameRenderer,
) {
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var currentMediaUrl: String? = null
    private var currentMediaOptions: List<String> = emptyList()

    /**
     * Legacy AWT Canvas field - kept for binary compatibility with anything
     * still reading the property, but not used as the rendering surface
     * anymore. We render via [VlcFrameRenderer]'s callback path instead, so
     * Compose chrome is no longer occluded by a heavyweight peer.
     */
    val awtCanvas: Canvas = Canvas().apply { background = AwtColor.BLACK }

    @Volatile
    var released: Boolean = false; private set

    fun initialize() {
        check(mediaPlayer == null) { "Session already initialized" }
        val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
        // Callback rendering: VLC writes RV32 frames into a BufferedImage that
        // [VlcFrameRenderer] swaps double-buffered. Compose draws the latest
        // frame as an ImageBitmap. This costs a per-frame CPU pixel copy but
        // keeps the video surface lightweight, so Compose buttons, sliders,
        // and overlays sit above it without being painted over by a
        // heavyweight AWT/D3D Canvas.
        val videoSurface = CallbackVideoSurface(
            frameRenderer.bufferFormatCallback,
            frameRenderer.renderCallback,
            true,
            VideoSurfaceAdapters.getVideoSurfaceAdapter(),
        )
        player.videoSurface().set(videoSurface)
        player.events().addMediaPlayerEventListener(eventBridge.createEventAdapter())
        bootstrapAudio(player)
        mediaPlayer = player
        println("TORVE VLC SESSION | initialized (callback rendering)")
    }

    // ── Transport ───────────────────────────────────────────────

    suspend fun play(url: String, vararg options: String) {
        currentMediaUrl = url
        currentMediaOptions = options.toList()
        commandChannel.execute {
            val mp = requirePlayer()
            mp.media().play(url, *options)
            bootstrapAudio(mp)
        }
    }

    suspend fun startPaused(url: String, vararg options: String) {
        currentMediaUrl = url
        currentMediaOptions = options.toList()
        commandChannel.execute {
            val mp = requirePlayer()
            mp.media().startPaused(url, *options)
            bootstrapAudio(mp)
        }
    }

    suspend fun resume() {
        commandChannel.execute { requirePlayer().controls().play() }
    }

    suspend fun pause() {
        commandChannel.execute { requirePlayer().controls().pause() }
    }

    suspend fun stop() {
        commandChannel.execute { requirePlayer().controls().stop() }
    }

    suspend fun seek(timeMs: Long) {
        commandChannel.execute { requirePlayer().controls().setTime(timeMs.coerceAtLeast(0)) }
    }

    suspend fun seekRelative(deltaMs: Long) {
        commandChannel.execute {
            val mp = requirePlayer()
            val target = (mp.status().time() + deltaMs).coerceAtLeast(0)
            mp.controls().setTime(target)
        }
    }

    // ── Volume ──────────────────────────────────────────────────

    suspend fun setVolume(volume: Int) {
        commandChannel.execute {
            val clamped = volume.coerceIn(0, 200)
            requirePlayer().audio().setVolume(clamped)
            // Use the value we just set; never query audio.isMute here -
            // VLC's audio queries are racy during init and would
            // overwrite a fresh mute state with a stale read.
            eventBridge.refreshVolume(clamped, eventBridge.state.value.isMuted)
        }
    }

    suspend fun setMute(muted: Boolean) {
        commandChannel.execute {
            requirePlayer().audio().setMute(muted)
            // Same reasoning as setVolume - keep the volume from current
            // state; don't re-query VLC.
            eventBridge.refreshVolume(eventBridge.state.value.volume, muted)
        }
    }

    fun getVolume(): Int = mediaPlayer?.audio()?.volume() ?: 100
    fun isMuted(): Boolean = mediaPlayer?.audio()?.isMute ?: false

    // ── Playback rate ───────────────────────────────────────────

    suspend fun setRate(rate: Float) {
        commandChannel.execute {
            requirePlayer().controls().setRate(rate.coerceIn(0.25f, 4f))
            eventBridge.refreshRate(rate)
        }
    }

    fun getRate(): Float = mediaPlayer?.status()?.rate() ?: 1f

    // ── Tracks ──────────────────────────────────────────────────

    suspend fun selectAudioTrack(id: Int) {
        commandChannel.execute {
            requirePlayer().audio().setTrack(id)
            eventBridge.refreshTracks(requirePlayer())
        }
    }

    suspend fun disableAudioTrack() {
        commandChannel.execute {
            requirePlayer().audio().setTrack(-1)
            eventBridge.refreshTracks(requirePlayer())
        }
    }

    suspend fun selectSubtitleTrack(id: Int) {
        commandChannel.execute {
            requirePlayer().subpictures().setTrack(id)
            eventBridge.refreshTracks(requirePlayer())
        }
    }

    suspend fun disableSubtitles() {
        commandChannel.execute {
            requirePlayer().subpictures().setTrack(-1)
            eventBridge.refreshTracks(requirePlayer())
        }
    }

    suspend fun addSubtitleFile(uri: String) {
        commandChannel.execute { requirePlayer().subpictures().setSubTitleFile(uri) }
    }

    suspend fun refreshTracks() {
        commandChannel.execute { eventBridge.refreshTracks(requirePlayer()) }
    }

    suspend fun selectVideoTrack(id: Int) {
        commandChannel.execute {
            requirePlayer().video().setTrack(id)
            eventBridge.refreshTracks(requirePlayer())
        }
    }

    fun availableAudioDevices(): List<VlcAudioDevice> {
        val outputs = factory.audio().audioOutputs()
        if (outputs.isEmpty()) return emptyList()
        val preferredPriority = outputs.minOfOrNull { audioOutputPriority(it.name) } ?: return emptyList()
        val devices = outputs
            .filter { audioOutputPriority(it.name) == preferredPriority }
            .flatMap { output ->
            output.devices.map { device ->
                val label = normalizeAudioDeviceLabel(
                    device.longName?.takeIf { it.isNotBlank() } ?: (device.deviceId ?: ""),
                )
                VlcAudioDevice(
                    outputName = output.name,
                    outputLabel = output.description ?: output.name,
                    deviceId = device.deviceId ?: "",
                    deviceLabel = label,
                )
            }
        }
        val concreteDevices = devices.filterNot { isGenericAudioDeviceLabel(it.deviceLabel) }
        val source = if (concreteDevices.isNotEmpty()) concreteDevices else devices
        return source
            .groupBy { stableAudioDeviceKey(it) }
            .values
            .mapNotNull { variants ->
                variants.minByOrNull { audioOutputPriority(it.outputName) }
            }
            .sortedBy { it.deviceLabel.lowercase() }
    }

    fun currentAudioDeviceId(): String? = mediaPlayer?.audio()?.outputDevice()

    suspend fun switchAudioDevice(outputName: String, deviceId: String): Boolean {
        return commandChannel.execute {
            val mp = requirePlayer()
            val mediaUrl = currentMediaUrl ?: return@execute false
            val resumeTime = mp.status().time().coerceAtLeast(0)
            val shouldResume = mp.status().isPlaying
            mp.audio().setOutput(outputName)
            mp.audio().setOutputDevice(outputName, deviceId)
            mp.controls().stop()
            mp.media().startPaused(mediaUrl, *currentMediaOptions.toTypedArray())
            if (resumeTime > 0) {
                mp.controls().setTime(resumeTime)
            }
            if (shouldResume) {
                mp.controls().play()
            }
            bootstrapAudio(mp)
            eventBridge.refreshTracks(mp)
            eventBridge.refreshVolume(mp.audio().volume(), mp.audio().isMute)
            // Remember the user's pick so the next session restores it
            // automatically.
            AudioDevicePreferences.setLastDevice(outputName = outputName, deviceId = deviceId)
            true
        }
    }

    // ── Delays ──────────────────────────────────────────────────

    suspend fun setAudioDelay(delayUs: Long) {
        commandChannel.execute {
            requirePlayer().audio().setDelay(delayUs)
            eventBridge.refreshDelays(delayUs, requirePlayer().subpictures().delay())
        }
    }

    suspend fun setSubtitleDelay(delayUs: Long) {
        commandChannel.execute {
            requirePlayer().subpictures().setDelay(delayUs)
            eventBridge.refreshDelays(requirePlayer().audio().delay(), delayUs)
        }
    }

    fun getAudioDelayUs(): Long = mediaPlayer?.audio()?.delay() ?: 0
    fun getSubtitleDelayUs(): Long = mediaPlayer?.subpictures()?.delay() ?: 0

    // ── Cast / Renderer ─────────────────────────────────────────

    /**
     * Route playback to [rendererItem] (e.g. a Chromecast device discovered via
     * the microdns/mDNS renderer). Passing `null` clears the renderer and
     * resumes local playback.
     */
    suspend fun setRenderer(rendererItem: RendererItem?): Boolean {
        return commandChannel.execute {
            requirePlayer().setRenderer(rendererItem)
        }
    }

    // ── Aspect ratio ────────────────────────────────────────────

    suspend fun setAspectRatio(ratio: String?) {
        commandChannel.execute { requirePlayer().video().setAspectRatio(ratio) }
    }

    suspend fun setCropGeometry(geometry: String?) {
        commandChannel.execute { requirePlayer().video().setCropGeometry(geometry) }
    }

    suspend fun setScale(scale: Float) {
        commandChannel.execute { requirePlayer().video().setScale(scale) }
    }

    // ── Equalizer ───────────────────────────────────────────────

    fun getEqualizerPresets(): List<String> = factory.equalizer().presets() ?: emptyList()
    fun getEqualizerBandCount(): Int = factory.equalizer().bands()?.size ?: 0
    fun getEqualizerBandFrequencies(): List<Float> = factory.equalizer().bands() ?: emptyList()
    fun getEqualizerPreamp(): Float = equalizer?.preamp() ?: 0f
    fun getEqualizerBandLevel(index: Int): Float = equalizer?.amp(index) ?: 0f
    fun getEqualizerBandLevels(): FloatArray = FloatArray(getEqualizerBandCount()) { equalizer?.amp(it) ?: 0f }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        commandChannel.execute {
            if (enabled) {
                if (equalizer == null) equalizer = factory.equalizer().newEqualizer()
                requirePlayer().audio().setEqualizer(equalizer)
            } else {
                requirePlayer().audio().setEqualizer(null)
            }
        }
    }

    suspend fun applyEqualizerPreset(name: String) {
        commandChannel.execute {
            equalizer = factory.equalizer().newEqualizer(name)
            requirePlayer().audio().setEqualizer(equalizer)
        }
    }

    suspend fun setEqualizerPreamp(level: Float) {
        commandChannel.execute { equalizer?.setPreamp(level) }
    }

    suspend fun setEqualizerBand(index: Int, level: Float) {
        commandChannel.execute { equalizer?.setAmp(index, level) }
    }

    // ── Snapshot ────────────────────────────────────────────────

    suspend fun takeSnapshot(path: String, width: Int = 0, height: Int = 0): Boolean {
        return commandChannel.execute {
            requirePlayer().snapshots().save(java.io.File(path), width, height)
        }
    }

    fun readDiagnostics(engineName: String, runtimePath: String? = null): DesktopPlayerDiagnostics {
        return VlcDiagnostics.snapshot(
            mediaPlayer = mediaPlayer,
            factory = factory,
            engineName = engineName,
            runtimePath = runtimePath,
            bufferingModeLabel = eventBridge.bufferingModeLabel,
        )
    }

    // ── Query ───────────────────────────────────────────────────

    fun getTime(): Long = mediaPlayer?.status()?.time() ?: 0
    fun getLength(): Long = mediaPlayer?.status()?.length() ?: 0
    fun isPlaying(): Boolean = mediaPlayer?.status()?.isPlaying ?: false
    fun isPaused(): Boolean {
        val mp = mediaPlayer ?: return false
        return !mp.status().isPlaying && mp.status().isPlayable
    }

    // ── Lifecycle ───────────────────────────────────────────────

    fun release() {
        if (released) return
        released = true
        println("TORVE VLC SESSION | releasing")
        commandChannel.post {
            runCatching {
                mediaPlayer?.controls()?.stop()
                mediaPlayer?.release()
            }
            mediaPlayer = null
            equalizer = null
        }
        frameRenderer.release()
        eventBridge.reset()
        println("TORVE VLC SESSION | released")
    }

    private fun requirePlayer(): MediaPlayer {
        return mediaPlayer ?: throw IllegalStateException("VLC session not initialized or already released")
    }

    private fun bootstrapAudio(player: MediaPlayer) {
        runCatching {
            val currentVolume = player.audio().volume()
            val targetVolume = when {
                currentVolume <= 0 -> 100
                else -> currentVolume.coerceIn(0, 200)
            }

            if (player.audio().isMute) {
                player.audio().setMute(false)
            }
            player.audio().setVolume(targetVolume)

            // Publish the values we just SET, not what audio() reports.
            // VLC's audio queries are racy on first init - they often
            // return mute=true / volume=0 for a few hundred ms after the
            // setters are issued. Trusting those reads here used to leave
            // the chrome stuck on a mute icon with a zero slider while
            // sound was playing fine.
            eventBridge.refreshVolume(targetVolume, false)

            // Restore the user's last-selected output device on top of
            // the default. Best-effort: if the device no longer exists
            // (unplugged, renamed) VLC silently ignores and we stay on
            // the default. No retry, no error.
            AudioDevicePreferences.getLastDevice()?.let { saved ->
                runCatching {
                    player.audio().setOutput(saved.outputName)
                    if (saved.deviceId.isNotBlank()) {
                        player.audio().setOutputDevice(saved.outputName, saved.deviceId)
                    }
                }
            }
        }.onFailure {
            println("TORVE VLC SESSION | audio bootstrap failed: ${it.message}")
        }
    }

    private fun stableAudioDeviceKey(device: VlcAudioDevice): String {
        val label = device.deviceLabel.trim().lowercase()
        val id = device.deviceId.trim().lowercase()
        return when {
            label.isNotBlank() && !isGenericAudioDeviceLabel(device.deviceLabel) -> label
            id.isNotBlank() && !isGenericAudioDeviceLabel(device.deviceLabel) -> id
            label.isNotBlank() -> label
            else -> "${device.outputName}:${device.deviceId}"
        }
    }

    private fun audioOutputPriority(outputName: String): Int {
        return when (outputName.trim().lowercase()) {
            "mmdevice", "wasapi" -> 0
            "directsound", "directx" -> 1
            "waveout" -> 2
            else -> 3
        }
    }

    private fun isGenericAudioDeviceLabel(label: String): Boolean {
        val normalized = label.trim().lowercase()
        return normalized.isBlank() ||
            normalized == "default" ||
            normalized.endsWith("audio output") ||
            normalized == "waveout" ||
            normalized == "mmdevice"
    }

    private fun normalizeAudioDeviceLabel(label: String): String {
        return label
            .substringAfterLast('{')
            .substringBeforeLast('}')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
