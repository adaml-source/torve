package com.torve.desktop.player

/**
 * Track information for audio, subtitle, and video tracks.
 * Torve-centric model - does not expose VLC internals.
 */
data class DesktopTrackInfo(
    val id: Int,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val isSelected: Boolean = false,
)

data class DesktopEqualizerBand(
    val index: Int,
    val label: String,
    val level: Float,
)

data class DesktopEqualizerSnapshot(
    val available: Boolean,
    val enabled: Boolean,
    val presetName: String?,
    val presets: List<String>,
    val preamp: Float,
    val minLevel: Float,
    val maxLevel: Float,
    val bands: List<DesktopEqualizerBand>,
)

enum class DesktopBufferingPreset(
    val label: String,
    val description: String,
    val networkCachingMs: Int?,
    val liveCachingMs: Int?,
    val fileCachingMs: Int?,
) {
    AUTO(
        label = "Auto",
        description = "Balanced defaults tuned for general playback.",
        networkCachingMs = null,
        liveCachingMs = null,
        fileCachingMs = null,
    ),
    STABLE_STREAM(
        label = "Stable Stream",
        description = "Prioritizes resilience on inconsistent network streams.",
        networkCachingMs = 2500,
        liveCachingMs = 1600,
        fileCachingMs = 1200,
    ),
    LOW_LATENCY(
        label = "Low Latency",
        description = "Reduces startup and seek delay for responsive playback.",
        networkCachingMs = 900,
        liveCachingMs = 450,
        fileCachingMs = 300,
    ),
    AGGRESSIVE_BUFFER(
        label = "Aggressive Buffer",
        description = "Builds a larger cache for unstable long-form streaming.",
        networkCachingMs = 5000,
        liveCachingMs = 3200,
        fileCachingMs = 2500,
    ),
}

/** Aspect ratio / fit modes available in the player. */
enum class DesktopAspectMode(val label: String, val vlcValue: String?) {
    DEFAULT("Default", null),
    FIT("Fit", ""),
    FILL("Fill", ""),
    RATIO_16_9("16:9", "16:9"),
    RATIO_4_3("4:3", "4:3"),
    RATIO_21_9("21:9", "21:9"),
    RATIO_2_35_1("2.35:1", "2.35:1"),
    ORIGINAL("Original", "-1"),
}

/** Playback speed presets. */
enum class DesktopPlaybackSpeed(val label: String, val rate: Float) {
    SPEED_0_25("0.25x", 0.25f),
    SPEED_0_5("0.5x", 0.5f),
    SPEED_0_75("0.75x", 0.75f),
    SPEED_1_0("1x", 1.0f),
    SPEED_1_25("1.25x", 1.25f),
    SPEED_1_5("1.5x", 1.5f),
    SPEED_1_75("1.75x", 1.75f),
    SPEED_2_0("2x", 2.0f),
    SPEED_3_0("3x", 3.0f),
    SPEED_4_0("4x", 4.0f),
}

/** Diagnostic snapshot of the active media and engine. */
data class DesktopPlayerDiagnostics(
    val engineName: String,
    val vlcVersion: String?,
    val runtimePath: String?,
    val playbackState: String,
    val sourceType: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val resolution: String?,
    val fps: String?,
    val bitrate: String?,
    val sampleRate: String?,
    val audioChannels: String?,
    val cacheLevel: String?,
    val bufferingMode: String?,
    val playbackSpeed: Float,
    val volume: Int,
    val isMuted: Boolean,
    val audioDelay: Long,
    val subtitleDelay: Long,
    val aspectMode: String?,
    val mediaUrl: String?,
    val durationMs: Long,
    val currentTimeMs: Long,
    val audioTrack: String?,
    val subtitleTrack: String?,
)

/** User-facing error categories for the player. */
enum class DesktopPlayerErrorCategory(val title: String) {
    RUNTIME_MISSING("VLC runtime not found"),
    RUNTIME_INIT_FAILED("VLC failed to initialize"),
    MEDIA_OPEN_FAILED("Could not open media"),
    CODEC_UNSUPPORTED("Unsupported format"),
    NETWORK_TIMEOUT("Network timeout"),
    PLAYBACK_ABORTED("Playback stopped unexpectedly"),
    SUBTITLE_UNAVAILABLE("Subtitle track unavailable"),
    UNKNOWN("Playback error"),
}
