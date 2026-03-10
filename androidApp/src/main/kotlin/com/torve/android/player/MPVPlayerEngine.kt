package com.torve.android.player

import android.content.Context
import android.util.Log
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import java.util.Locale

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
    private var audioPassthroughEnabled = false
    private var preferSurroundCodecs = true
    private var liveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var userAudioPassthroughEnabled = false
    private var userPreferSurroundCodecs = true
    private var userLiveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var currentPlaybackContext: LiveAudioPlaybackContext? = null
    private var rememberedCompatibilityHint: LiveAudioCompatibilityHint? = null
    private var rememberedTrackHintApplied = false

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
        applyAudioOutputPreferences()
        return true
    }

    override fun play(url: String) {
        if (!initialized) return
        rememberedTrackHintApplied = false
        applyRememberedCompatibilityHintIfAvailable()
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

    override fun setAudioDelay(delayMs: Int) {
        if (!initialized) return
        val seconds = delayMs / 1000.0
        MPVLib.setPropertyDouble("audio-delay", seconds)
    }

    override fun getAudioDelay(): Int {
        if (!initialized) return 0
        return try {
            (MPVLib.getPropertyDouble("audio-delay") * 1000).toInt()
        } catch (_: Exception) {
            0
        }
    }

    fun setPictureFormat(aspectRatio: Float?, fill: Boolean) {
        if (!initialized) return
        if (fill) {
            MPVLib.setPropertyDouble("video-aspect-override", -1.0)
            MPVLib.setPropertyDouble("panscan", 1.0)
        } else if (aspectRatio != null) {
            MPVLib.setPropertyDouble("video-aspect-override", aspectRatio.toDouble())
            MPVLib.setPropertyDouble("panscan", 0.0)
        } else {
            MPVLib.setPropertyDouble("video-aspect-override", -1.0)
            MPVLib.setPropertyDouble("panscan", 0.0)
        }
    }

    fun setAudioOutputPreferences(
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode = userLiveAudioOutputMode,
    ) {
        userAudioPassthroughEnabled = passthroughEnabled
        userPreferSurroundCodecs = preferSurround
        userLiveAudioOutputMode = outputMode
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        audioPassthroughEnabled = passthroughEnabled
        preferSurroundCodecs = preferSurround
        liveAudioOutputMode = outputMode
        applyAudioOutputPreferences()
    }

    fun setLivePlaybackContext(channel: Channel?) {
        currentPlaybackContext = channel?.let(LiveAudioPlaybackContext::fromChannel)
        rememberedCompatibilityHint = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.resolveHint(context, it)
        }
        rememberedTrackHintApplied = false
    }

    private fun applyAudioOutputPreferences() {
        if (!initialized) return
        val forceStereo = liveAudioOutputMode == LiveAudioOutputMode.FORCE_STEREO_PCM
        val preferCompatible = liveAudioOutputMode == LiveAudioOutputMode.PREFER_COMPATIBLE
        val passthrough = audioPassthroughEnabled && !forceStereo
        MPVLib.setPropertyString(
            "audio-spdif",
            if (passthrough) "ac3,eac3,dts,dts-hd,truehd" else "",
        )

        val channels = when {
            forceStereo -> "stereo"
            preferCompatible -> "stereo"
            preferSurroundCodecs -> "auto-safe"
            else -> "stereo"
        }
        MPVLib.setPropertyString("audio-channels", channels)
    }

    private fun applyRememberedCompatibilityHintIfAvailable() {
        rememberedCompatibilityHint = currentPlaybackContext?.let {
            LiveAudioCompatibilityStore.resolveHint(context, it)
        }
        rememberedTrackHintApplied = false
        val hint = rememberedCompatibilityHint
        if (hint != null) {
            Log.d(
                TAG,
                "Applying remembered live audio hint kind=${hint.recoveryKind} mode=${hint.outputMode} channel=${currentPlaybackContext?.displayName.orEmpty()}",
            )
            audioPassthroughEnabled = hint.passthroughEnabled
            preferSurroundCodecs = hint.preferSurround
            liveAudioOutputMode = hint.liveAudioOutputMode()
        } else {
            audioPassthroughEnabled = userAudioPassthroughEnabled
            preferSurroundCodecs = userPreferSurroundCodecs
            liveAudioOutputMode = userLiveAudioOutputMode
        }
        applyAudioOutputPreferences()
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
                val audioSignature = MPVLib.getTracks().buildAudioSignature()
                invalidateRememberedHintIfTrackMetadataChanged(audioSignature)
                applyRememberedCompatibleTrackIfNeeded()
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

    private fun invalidateRememberedHintIfTrackMetadataChanged(audioSignature: String) {
        val playbackContext = currentPlaybackContext ?: return
        val hint = rememberedCompatibilityHint ?: return
        val storedSignature = hint.audioSignature ?: return
        if (storedSignature == audioSignature) return

        Log.i(TAG, "Invalidating remembered live audio hint for ${playbackContext.displayName} because mpv track metadata changed")
        LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        audioPassthroughEnabled = userAudioPassthroughEnabled
        preferSurroundCodecs = userPreferSurroundCodecs
        liveAudioOutputMode = userLiveAudioOutputMode
        applyAudioOutputPreferences()
    }

    private fun applyRememberedCompatibleTrackIfNeeded() {
        val hint = rememberedCompatibilityHint ?: return
        val preferredTrack = hint.preferredTrack ?: return
        if (hint.recoveryKind != LiveAudioRecoveryKind.COMPATIBLE_TRACK || rememberedTrackHintApplied) {
            return
        }

        val currentTracks = MPVLib.getTracks().filter { it.type == "audio" }
        val selectedTrack = currentTracks.firstOrNull { it.isSelected }
        if (selectedTrack != null && selectedTrack.matches(preferredTrack)) {
            rememberedTrackHintApplied = true
            return
        }

        val candidate = currentTracks.maxByOrNull { it.matchScore(preferredTrack) }
            ?.takeIf { it.matchScore(preferredTrack) >= 3 }
            ?: return

        rememberedTrackHintApplied = true
        Log.d(
            TAG,
            "Applying remembered compatible mpv audio track ${candidate.codec ?: "unknown"} for ${currentPlaybackContext?.displayName.orEmpty()}",
        )
        MPVLib.selectAudioTrack(candidate.id)
    }

    private fun List<MPVLib.Track>.buildAudioSignature(): String {
        return filter { it.type == "audio" }
            .map { track ->
                listOf(
                    track.codec.normalizeFormatKey().orEmpty(),
                    track.language?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                    track.title?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                ).joinToString(separator = ":")
            }
            .sorted()
            .joinToString(separator = "|")
            .take(512)
    }

    private fun MPVLib.Track.matches(trackHint: LiveAudioTrackHint): Boolean {
        return codec.normalizeFormatKey() == trackHint.formatKey &&
            language.equals(trackHint.language, ignoreCase = true) &&
            title.equals(trackHint.label, ignoreCase = true)
    }

    private fun MPVLib.Track.matchScore(trackHint: LiveAudioTrackHint): Int {
        var score = 0
        if (codec.normalizeFormatKey() == trackHint.formatKey) score += 6
        if (language.equals(trackHint.language, ignoreCase = true)) score += 3
        if (title.equals(trackHint.label, ignoreCase = true)) score += 2
        return score
    }

    private fun String?.normalizeFormatKey(): String? {
        return this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        private const val TAG = "MPVPlayerEngine"
    }
}
