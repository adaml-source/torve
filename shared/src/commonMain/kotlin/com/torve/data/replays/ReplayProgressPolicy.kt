package com.torve.data.replays

/** Replay positions stay separate from film/episode watched and tracking state. */
internal object ReplayProgressPolicy {
    fun start(saved: ReplayProgress?): Long = saved?.takeIf {
        it.durationMs > 0 && it.positionMs > 0 && it.positionMs < it.durationMs * 0.95
    }?.positionMs ?: 0
    fun record(saved: Map<String, ReplayProgress>, id: String, position: Long, duration: Long,
               hasRenderedOutput: Boolean, now: Long): Map<String, ReplayProgress> {
        if (!hasRenderedOutput || position <= 0 || duration <= 0 || !id.startsWith("web-replay:")) return saved
        return (saved + (id to ReplayProgress(position.coerceAtMost(duration), duration, now)))
            .entries.sortedByDescending { it.value.updatedAt }.take(200).associate { it.key to it.value }
    }
}
