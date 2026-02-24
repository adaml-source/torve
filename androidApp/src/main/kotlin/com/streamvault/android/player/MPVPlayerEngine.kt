package com.streamvault.android.player

import android.content.Context
import com.streamvault.domain.player.PlayerEngine
import com.streamvault.domain.player.PlayerListener
import com.streamvault.domain.player.PlayerState
import com.streamvault.domain.player.TrackDescription

/**
 * PlayerEngine backed by libmpv via JNI.
 * Falls back gracefully if native .so files aren't present.
 */
class MPVPlayerEngine(
    private val context: Context,
) : PlayerEngine, MPVLib.EventObserver {

    private var _state = PlayerState()
    override val state: PlayerState get() = _state

    private val listeners = mutableListOf<PlayerListener>()
    private var initialized = false

    fun initialize(): Boolean {
        if (!MPVLib.tryLoad()) return false
        MPVLib.create(context)

        // Configure mpv options before init
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("gpu-context", "android")
        MPVLib.setPropertyString("hwdec", "mediacodec-copy")
        MPVLib.setPropertyString("ao", "audiotrack")
        MPVLib.setPropertyBoolean("input-default-bindings", true)

        // Buffer settings
        MPVLib.setPropertyString("demuxer-max-bytes", "150MiB")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "50MiB")
        MPVLib.setPropertyInt("cache-secs", 120)

        MPVLib.init()
        MPVLib.addObserver(this)

        // Observe key properties
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("track-list/count", MPVLib.MPV_FORMAT_INT64)

        initialized = true
        return true
    }

    override fun play(url: String) {
        if (!initialized) return
        _state = _state.copy(isIdle = false, isBuffering = true)
        notifyStateChanged()
        MPVLib.loadFile(url)
    }

    override fun pause() {
        if (!initialized) return
        MPVLib.pause()
    }

    override fun resume() {
        if (!initialized) return
        MPVLib.play()
    }

    override fun stop() {
        if (!initialized) return
        MPVLib.stop()
        _state = PlayerState()
        notifyStateChanged()
    }

    override fun seekTo(positionMs: Long) {
        if (!initialized) return
        MPVLib.seek(positionMs / 1000.0)
    }

    override fun seekRelative(deltaMs: Long) {
        if (!initialized) return
        MPVLib.seekRelative(deltaMs / 1000.0)
    }

    override fun setSpeed(speed: Float) {
        if (!initialized) return
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    override fun getSubtitleTracks(): List<TrackDescription> {
        if (!initialized) return emptyList()
        return MPVLib.getTracks()
            .filter { it.type == "sub" }
            .map { track ->
                TrackDescription(
                    id = track.id,
                    label = track.title ?: track.language ?: "Subtitle ${track.id}",
                    language = track.language,
                    isSelected = track.isSelected,
                )
            }
    }

    override fun getAudioTracks(): List<TrackDescription> {
        if (!initialized) return emptyList()
        return MPVLib.getTracks()
            .filter { it.type == "audio" }
            .map { track ->
                TrackDescription(
                    id = track.id,
                    label = track.title ?: track.language ?: "Audio ${track.id}",
                    language = track.language,
                    isSelected = track.isSelected,
                )
            }
    }

    override fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        MPVLib.selectSubtitleTrack(id)
    }

    override fun selectAudioTrack(id: Int) {
        if (!initialized) return
        MPVLib.selectAudioTrack(id)
    }

    override fun disableSubtitles() {
        if (!initialized) return
        MPVLib.disableSubtitles()
    }

    fun setAudioDelayMs(delayMs: Int) {
        if (!initialized) return
        val seconds = delayMs / 1000.0
        MPVLib.setPropertyDouble("audio-delay", seconds)
    }

    fun getAudioDelayMs(): Int {
        if (!initialized) return 0
        return try {
            (MPVLib.getPropertyDouble("audio-delay") * 1000).toInt()
        } catch (_: Exception) {
            0
        }
    }

    override fun release() {
        if (!initialized) return
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        initialized = false
    }

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    // --- MPVLib.EventObserver ---

    override fun onPropertyChange(property: String, value: Any?) {
        when (property) {
            "pause" -> {
                val paused = value as? Boolean ?: return
                _state = _state.copy(isPlaying = !paused, isIdle = false)
                notifyStateChanged()
            }
            "time-pos" -> {
                val seconds = (value as? Double) ?: return
                _state = _state.copy(positionMs = (seconds * 1000).toLong())
                notifyStateChanged()
            }
            "duration" -> {
                val seconds = (value as? Double) ?: return
                _state = _state.copy(durationMs = (seconds * 1000).toLong())
                notifyStateChanged()
            }
            "paused-for-cache" -> {
                val buffering = value as? Boolean ?: return
                _state = _state.copy(isBuffering = buffering)
                notifyStateChanged()
            }
            "track-list/count" -> {
                notifyTracksChanged()
            }
        }
    }

    override fun onEvent(eventId: Int) {
        // MPV event IDs: 7 = end-file, etc.
        when (eventId) {
            7 -> { // MPV_EVENT_END_FILE
                _state = _state.copy(isPlaying = false, isIdle = true)
                notifyStateChanged()
            }
        }
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(_state) }
    }

    private fun notifyTracksChanged() {
        val audio = getAudioTracks()
        val subtitles = getSubtitleTracks()
        listeners.forEach { it.onTracksChanged(audio, subtitles) }
    }
}
