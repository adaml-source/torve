package com.torve.presentation.apple

/** Stored using the existing Android phone format; never contains source URLs. */
data class AppleContentPlaybackPreferences(
    val audioDelayMs: Int = 0,
    val speed: Double? = null,
    val picture: String? = null,
    val audioTag: String? = null,
    val subtitleTag: String? = null,
    val subtitlesEnabled: Boolean = true,
)

object ApplePlaybackPreferencePolicy {
    private val pictures = setOf("SOURCE", "FULLSCREEN", "RATIO_16_9", "RATIO_4_3", "RATIO_21_9")
    fun key(identity: ApplePlaybackIdentity): String {
        val type = if (identity.mediaType == com.torve.domain.model.MediaType.SERIES) "tv" else "movie"
        return "player_playback_prefs_${"$type:media:${identity.mediaId}".hashCode().toUInt().toString(16)}"
    }
    fun validKey(key: String): Boolean = Regex("player_playback_prefs_[0-9a-f]{1,8}").matches(key)
    fun read(playback: String?, tracks: String?): AppleContentPlaybackPreferences {
        val values = playback?.split('|').orEmpty()
        val trackValues = tracks?.split('|').orEmpty()
        val speed = values.getOrNull(1)?.toDoubleOrNull()?.takeIf(Double::isFinite)?.coerceIn(.25, 3.0)
        return AppleContentPlaybackPreferences(
            audioDelayMs = values.getOrNull(0)?.toIntOrNull()?.coerceIn(-2000, 2000) ?: 0,
            speed = speed,
            picture = values.getOrNull(2)?.let { if (it in pictures) it else "SOURCE" },
            audioTag = trackValues.getOrNull(0)?.takeIf(String::isNotBlank),
            subtitleTag = trackValues.getOrNull(1)?.takeIf(String::isNotBlank),
            subtitlesEnabled = trackValues.getOrNull(2)?.let { it == "1" } ?: true,
        )
    }
    fun playbackValue(delay: Int, speed: Double, picture: String): String =
        "${delay.coerceIn(-2000, 2000)}|${speed.takeIf(Double::isFinite)?.coerceIn(.25, 3.0) ?: 1.0}|${picture.takeIf { it in pictures } ?: "SOURCE"}"
    fun tracksValue(audio: String?, subtitle: String?, enabled: Boolean): String =
        "${audio.orEmpty().replace('|', ' ').take(48)}|${subtitle.orEmpty().replace('|', ' ').take(48)}|${if (enabled) "1" else "0"}"
}
