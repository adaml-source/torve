package com.torve.android.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer

/** Session-scoped subtitle timing shared by ExoPlayer's text renderer. */
internal class SubtitleDelayState {
    @Volatile
    var delayMs: Int = 0
}

/**
 * Converts the playback clock into the clock observed by the text renderer.
 * A positive delay makes subtitles later; a negative delay makes them earlier.
 */
internal fun subtitleRendererPositionUs(positionUs: Long, delayMs: Int): Long {
    if (positionUs == C.TIME_UNSET || positionUs == C.TIME_END_OF_SOURCE) return positionUs
    return (positionUs - delayMs.toLong() * 1_000L).coerceAtLeast(0L)
}

/**
 * Keeps subtitle timing inside Media3's renderer pipeline so embedded and
 * side-loaded tracks use the same clock. Delegation preserves the normal
 * renderer lifecycle; only position-sensitive calls are adjusted.
 */
@UnstableApi
internal class SubtitleDelayRenderer(
    private val delegate: Renderer,
    private val delayState: SubtitleDelayState,
) : Renderer by delegate {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(adjust(positionUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long) {
        delegate.resetPosition(adjust(positionUs))
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        delegate.getDurationToProgressUs(adjust(positionUs), elapsedRealtimeUs)

    private fun adjust(positionUs: Long): Long =
        subtitleRendererPositionUs(positionUs, delayState.delayMs)
}
