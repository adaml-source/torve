package com.torve.domain.model

/** Presets describe user intent. Display/decoder capability gates still apply at playback. */
enum class PlaybackPreset(val label: String, val description: String) {
    BALANCED("Balanced", "Cached sources, compatible codecs and a balance of quality and startup speed."),
    BEST_QUALITY("Best quality", "Prefer 4K and larger 1080p sources when your device supports them."),
    FAST_START("Fast start", "Prefer stable, cached sources up to 1080p with broadly compatible video."),
    PREFERRED_AUDIO("Preferred audio", "Require your selected audio language; ask before relaxing that rule."),
    CUSTOM("Custom", "Your individually configured playback settings.");

    fun apply(current: StreamPreferences): StreamPreferences = when (this) {
        CUSTOM -> current
        BALANCED -> current.copy(
            autoSourceMode = AutoSourceMode.BALANCED, maxQuality = StreamQuality.REMUX_4K,
            minQuality = StreamQuality.SD_480P, cachedOnly = true, allow4kAuto = false,
            codecPreference = CodecPreference.HEVC_PREFERRED, hdrMode = HdrMode.AUTO,
            minSourceSizePerHourBytes = 0, maxFileSizeBytes = null,
            sourceLanguageMatchMode = SourceLanguageMatchMode.PREFER,
            unknownSourceLanguagePolicy = UnknownSourceMetadataPolicy.ALLOW_WITH_PENALTY,
            unknownSourceSizePolicy = UnknownSourceMetadataPolicy.ALLOW_WITH_PENALTY,
            sourceFallbackPolicy = SourceFallbackPolicy.ASK, preferCompatibleCodecs = true,
        )
        BEST_QUALITY -> BALANCED.apply(current).copy(
            autoSourceMode = AutoSourceMode.QUALITY_FIRST, minQuality = StreamQuality.FHD_1080P,
            allow4kAuto = true, minSourceSizePerHourBytes = 1024L * 1024L * 1024L,
        )
        FAST_START -> BALANCED.apply(current).copy(
            autoSourceMode = AutoSourceMode.STABILITY_FIRST, maxQuality = StreamQuality.FHD_1080P,
            codecPreference = CodecPreference.H264_ONLY, hdrMode = HdrMode.SDR_ONLY,
        )
        PREFERRED_AUDIO -> BALANCED.apply(current).copy(
            sourceLanguageMatchMode = SourceLanguageMatchMode.REQUIRE,
            unknownSourceLanguagePolicy = UnknownSourceMetadataPolicy.REJECT,
        )
    }

    fun isAvailable(current: StreamPreferences): Boolean = this != PREFERRED_AUDIO ||
        current.preferredAudioLanguages.any { it.isNotBlank() && it != "system" }

    companion object {
        fun recognize(current: StreamPreferences): PlaybackPreset = entries
            .firstOrNull { it != CUSTOM && it.isAvailable(current) && it.apply(current) == current }
            ?: CUSTOM
    }
}
