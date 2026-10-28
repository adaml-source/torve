package com.torve.domain.player

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
data class ViewedRange(val startMs: Long, val endMs: Long)

/** Only decoder-confirmed, continuously played intervals are evidence of viewing. */
@Serializable
data class PlaybackViewingCoverage(
    val durationMs: Long = 0,
    val expectedRuntimeMs: Long = 0,
    val ranges: List<ViewedRange> = emptyList(),
) {
    val runtimeMatches: Boolean get() = durationMs > 0 && expectedRuntimeMs > 0 &&
        abs(durationMs - expectedRuntimeMs) <= min(expectedRuntimeMs / 10, max(30_000, expectedRuntimeMs / 20))
    val watchedMs: Long get() = normalized().sumOf { it.endMs - it.startMs }
    val complete: Boolean get() = runtimeMatches && watchedMs.toDouble() >= max(durationMs, expectedRuntimeMs) * .85

    fun normalized(): List<ViewedRange> {
        val result = mutableListOf<ViewedRange>()
        ranges.map { ViewedRange(it.startMs.coerceIn(0, durationMs.coerceAtLeast(0)), it.endMs.coerceIn(0, durationMs.coerceAtLeast(0))) }
            .filter { it.endMs > it.startMs }.sortedBy { it.startMs }.forEach { range ->
                val last = result.lastOrNull()
                if (last != null && range.startMs <= last.endMs) result[result.lastIndex] = last.copy(endMs = max(last.endMs, range.endMs))
                else result.add(range)
            }
        return result
    }
}

/** Monotonic time bounds decoder movement; pauses, seeks and suspended timers add no coverage. */
class PlaybackViewingTracker(private val expectedRuntimeMs: Long) {
    private var coverage = PlaybackViewingCoverage(expectedRuntimeMs = expectedRuntimeMs)
    private data class Sample(val position: Long, val time: Long, val rate: Double)
    private var previous: Sample? = null
    var hasPlayed: Boolean = false
        private set

    fun discontinuity() { previous = null }
    fun snapshot(): PlaybackViewingCoverage = coverage
    fun restore(saved: PlaybackViewingCoverage) {
        // A finished viewing starts a new cycle. Never infer evidence from a resume bookmark.
        if (saved.complete || abs(saved.durationMs - coverage.durationMs) > 2_000 ||
            saved.expectedRuntimeMs != expectedRuntimeMs) return
        coverage = coverage.copy(ranges = (coverage.ranges + saved.ranges)).let { it.copy(ranges = it.normalized()) }
    }

    fun observe(positionMs: Long, durationMs: Long, monotonicMs: Long, playing: Boolean, ready: Boolean, rate: Double) {
        if (durationMs <= 0 || positionMs !in 0..durationMs || !ready || !playing || !rate.isFinite() || rate <= 0 || rate > 4) {
            discontinuity(); return
        }
        if (abs(coverage.durationMs - durationMs) > 2_000) {
            coverage = PlaybackViewingCoverage(durationMs, expectedRuntimeMs)
            discontinuity()
        } else coverage = coverage.copy(durationMs = durationMs)
        val last = previous
        val sample = Sample(positionMs, monotonicMs, rate)
        if (last == null) { previous = sample; return }
        val elapsed = monotonicMs - last.time
        val moved = positionMs - last.position
        if (elapsed !in 1..2_000 || moved < 0 || abs(last.rate - rate) > .01) {
            previous = sample; return
        }
        // Decoder clocks can repeat or alternate a short tick and a longer tick.
        // Keep the last accepted anchor until monotonic time validates that movement.
        // Large jumps still establish a new anchor without adding their skipped range.
        if (moved == 0L) return
        if (moved > elapsed * rate + 250) {
            if (moved > 1_000 * rate + 250) previous = sample
            return
        }
        previous = sample
        hasPlayed = true
        // Keep resume for mismatched files, but never accumulate completion evidence for them.
        if (coverage.runtimeMatches) coverage = coverage.copy(ranges = coverage.ranges + ViewedRange(last.position, positionMs))
            .let { it.copy(ranges = it.normalized()) }
    }
}
