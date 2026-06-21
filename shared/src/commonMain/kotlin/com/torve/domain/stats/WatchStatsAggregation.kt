package com.torve.domain.stats

internal fun WatchSession.countedTimeForTotals(): Long =
    if (runtimeConfidence == RuntimeConfidence.UNKNOWN) 0L else countedWatchMs.coerceAtLeast(0L)

internal fun WatchSession.isCompletedForStats(): Boolean =
    status == WatchSessionStatus.COMPLETED ||
        status == WatchSessionStatus.MANUAL_COMPLETED ||
        status == WatchSessionStatus.IMPORTED_COMPLETED

fun classifyPlaybackSession(
    maxPositionMs: Long,
    durationMs: Long?,
    thresholdPercent: Double = WATCHED_THRESHOLD_PERCENT,
): WatchSessionStatus {
    if (maxPositionMs <= 0L && (durationMs == null || durationMs <= 0L)) {
        return WatchSessionStatus.STARTED
    }
    val duration = durationMs?.takeIf { it > 0L }
    val progress = if (duration != null) {
        (maxPositionMs.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    if (duration != null && progress >= thresholdPercent) {
        return WatchSessionStatus.COMPLETED
    }
    if (maxPositionMs < ABANDONED_MIN_WATCH_MS) {
        return WatchSessionStatus.ABANDONED
    }
    if (duration != null && progress < ABANDONED_MIN_PROGRESS_PERCENT) {
        return WatchSessionStatus.ABANDONED
    }
    return WatchSessionStatus.PARTIAL
}

fun countedWatchTimeForStatus(
    status: WatchSessionStatus,
    maxPositionMs: Long,
    durationMs: Long?,
): Long {
    val duration = durationMs?.takeIf { it > 0L }
    return when (status) {
        WatchSessionStatus.COMPLETED -> duration ?: 0L
        WatchSessionStatus.PARTIAL -> maxPositionMs.coerceAtLeast(0L)
        WatchSessionStatus.MANUAL_COMPLETED,
        WatchSessionStatus.IMPORTED_COMPLETED -> duration ?: 0L
        WatchSessionStatus.STARTED,
        WatchSessionStatus.ABANDONED -> 0L
    }
}
