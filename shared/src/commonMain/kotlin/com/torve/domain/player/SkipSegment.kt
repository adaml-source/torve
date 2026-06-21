package com.torve.domain.player

/**
 * Represents a skippable segment in media playback.
 *
 * Skip segments can be detected via:
 * - Server-provided markers (e.g., from Stremio addons or external APIs)
 * - Heuristic detection (e.g., silence detection, audio fingerprinting)
 * - Default ranges (e.g., skip first 30-90 seconds for TV intros)
 */
data class SkipSegment(
    val type: SkipType,
    val startMs: Long,
    val endMs: Long,
    val label: String = type.label,
) {
    val durationMs: Long get() = endMs - startMs

    fun containsPosition(positionMs: Long): Boolean {
        return positionMs in startMs..endMs
    }
}

enum class SkipType(val label: String) {
    INTRO("Skip Intro"),
    OUTRO("Skip Credits"),
    RECAP("Skip Recap"),
}

/**
 * Provides skip segment detection for media playback.
 *
 * For TV show episodes, provides default intro/outro skip segments:
 * - Intro: typically 0-90s (configurable)
 * - Credits: last 90-120s of content
 *
 * These are heuristic defaults. In the future, this can be extended with:
 * - Audio fingerprint matching (like Plex's intro detection)
 * - Server-side skip markers from subtitle/metadata APIs
 */
object SkipSegmentDetector {

    private const val DEFAULT_INTRO_END_MS = 90_000L      // 90 seconds
    private const val MIN_CREDITS_POSITION_MS = 300_000L   // Don't show credits skip for short content
    private const val CREDITS_DURATION_MS = 120_000L       // Last 2 minutes

    /**
     * Generate default skip segments for a media item.
     *
     * @param isEpisode true if this is a TV episode (intros are episode-specific)
     * @param durationMs total media duration in milliseconds
     * @param episodeNumber episode number (intros rarely on pilot episodes)
     */
    fun detectSegments(
        isEpisode: Boolean,
        durationMs: Long,
        episodeNumber: Int? = null,
    ): List<SkipSegment> {
        if (durationMs <= 0) return emptyList()

        val segments = mutableListOf<SkipSegment>()

        // Intro detection — for episodes (not pilot/first episode)
        if (isEpisode && (episodeNumber == null || episodeNumber > 1)) {
            segments.add(
                SkipSegment(
                    type = SkipType.INTRO,
                    startMs = 0,
                    endMs = DEFAULT_INTRO_END_MS.coerceAtMost(durationMs / 4),
                ),
            )
        }

        // Credits detection — for content longer than 5 minutes
        if (durationMs > MIN_CREDITS_POSITION_MS) {
            val creditsStart = (durationMs - CREDITS_DURATION_MS).coerceAtLeast(durationMs * 9 / 10)
            segments.add(
                SkipSegment(
                    type = SkipType.OUTRO,
                    startMs = creditsStart,
                    endMs = durationMs,
                ),
            )
        }

        return segments
    }

    /**
     * Find the active skip segment for the current playback position.
     */
    fun findActiveSegment(segments: List<SkipSegment>, positionMs: Long): SkipSegment? {
        return segments.find { it.containsPosition(positionMs) }
    }
}
