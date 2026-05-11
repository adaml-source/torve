package com.torve.domain.player

/**
 * A subtitle track supplied by the caller, not embedded in the media
 * container. Used for addon-sourced subtitles (Stremio `/subtitles/`
 * resource) and any other side-loaded text tracks.
 *
 * Engines that support side-loading (currently ExoPlayer on Android)
 * attach these as [androidx.media3.common.MediaItem.SubtitleConfiguration]
 * on the next [PlayerEngine.play] call. Engines without side-load support
 * safely ignore the list.
 */
data class ExternalSubtitle(
    val url: String,
    /** ISO 639-1/-2 code if known (e.g. "en", "fr", "eng"), else null. */
    val languageCode: String?,
    val label: String?,
    /**
     * MIME of the subtitle file. Common Stremio values are
     * `application/x-subrip` (SRT) and `text/vtt` (WebVTT). Null means
     * let the engine infer from the URL extension.
     */
    val mimeType: String? = null,
)

/**
 * Platform-agnostic player abstraction.
 * Implemented by MPVPlayerEngine (Android/iOS) or ExoPlayerEngine (Android fallback).
 */
interface PlayerEngine {
    val state: PlayerState

    fun play(url: String)

    /**
     * Play with side-loaded subtitle tracks. Default no-op delegates to
     * [play] so engines that don't support side-loading (e.g. current
     * MPV bindings) keep working unchanged. ExoPlayer overrides this to
     * attach SubtitleConfigurations to the MediaItem before prepare().
     */
    fun play(url: String, externalSubtitles: List<ExternalSubtitle>) {
        play(url)
    }

    /**
     * Stage HTTP request headers to attach on the **next** [play] call
     * only. Used by the LAN-library route (`X-Torve-Lan-Auth`) and
     * other authenticated stream sources that travel over plain HTTP.
     *
     * Default no-op. Engines that can attach headers (ExoPlayer via
     * `DefaultHttpDataSource.Factory().setDefaultRequestProperties`,
     * MPV via `--http-header-fields`) override this to capture the map
     * and consume it when the next play() builds its data source. The
     * headers are intentionally one-shot — staying on the engine would
     * leak them to an unrelated stream the next time the user picks a
     * provider URL.
     */
    fun setNextRequestHeaders(headers: Map<String, String>) {
        // no-op default
    }

    /**
     * Capability flag — true when this engine actually honors the
     * headers staged via [setNextRequestHeaders]. The default is
     * `false` so an engine that hasn't overridden the header method
     * doesn't get LAN routes silently routed through it (they'd 401
     * on the desktop hub side because the auth header is missing).
     *
     * Picker builders use this to suppress the LAN tier when the
     * active engine can't authenticate. Engines that override
     * [setNextRequestHeaders] should also override this to return
     * true.
     */
    val supportsRequestHeaders: Boolean
        get() = false

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekRelative(deltaMs: Long)
    fun setSpeed(speed: Float)

    // Track selection
    fun getSubtitleTracks(): List<TrackDescription>
    fun getAudioTracks(): List<TrackDescription>
    fun selectSubtitleTrack(id: Int)
    fun selectAudioTrack(id: Int)
    fun disableSubtitles()

    // Audio delay (ms): positive = delay audio, negative = advance audio
    fun setAudioDelay(delayMs: Int) {}
    fun getAudioDelay(): Int = 0

    // Subtitle delay (ms): positive = show later, negative = show earlier
    fun setSubtitleDelay(delayMs: Int) {}
    fun getSubtitleDelay(): Int = 0

    // Audio session (Android) — used for equalizer/audio effects
    fun getAudioSessionId(): Int = 0

    // Lifecycle
    fun release()

    // State observation
    fun addListener(listener: PlayerListener)
    fun removeListener(listener: PlayerListener)
}

enum class LiveTuneState {
    IDLE,
    OPENING_MEDIA,
    ANALYZING_TRACKS,
    SELECTING_AUDIO,
    BUFFERING_AV,
    PLAYING_CONFIRMED,
    AUDIO_RECOVERY_RETRY,
    FAILED_EXOPLAYER,
    FALLBACK_ALLOWED,
}

enum class LiveAudioRecoveryMode {
    NONE,
    TRACK_RESELECT,
    PASSTHROUGH_OFF,
    SOFTWARE_AUDIO,
    STEREO_PCM,
    EXOPLAYER_REBUILD,
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
    val isIdle: Boolean = true,
    val liveTuneState: LiveTuneState = LiveTuneState.IDLE,
    val audioRecoveryMode: LiveAudioRecoveryMode = LiveAudioRecoveryMode.NONE,
    val isAudioExpected: Boolean = false,
    val isAudioReady: Boolean = false,
    val isVideoReady: Boolean = false,
    val isEngineFallbackAllowed: Boolean = false,
)

data class TrackDescription(
    val id: Int,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val formatHint: String? = null,
    val channelCount: Int? = null,
)

interface PlayerListener {
    fun onStateChanged(state: PlayerState) {}
    fun onTracksChanged(audio: List<TrackDescription>, subtitles: List<TrackDescription>) {}
    fun onError(message: String) {}
}
