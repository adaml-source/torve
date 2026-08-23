package com.torve.android.tv.screens

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.channelIdentityCandidates

internal object TvLivePlaybackPolicy {
    const val REPLAY_SEEK_STEP_MS: Long = 60_000L

    fun initialReplayProgramme(
        replayUrl: String,
        startMs: Long,
        endMs: Long,
        title: String,
    ): EpgProgramme? {
        if (replayUrl.isBlank() || startMs < 0L || endMs <= startMs) return null
        return EpgProgramme(
            channelId = "",
            startTime = startMs,
            endTime = endMs,
            title = title,
        )
    }

    fun canOfferReplay(
        programme: EpgProgramme?,
        replayUrl: String?,
        nowMs: Long,
    ): Boolean {
        return programme != null &&
            programme.startTime <= nowMs &&
            !replayUrl.isNullOrBlank()
    }

    fun shouldSeekTimeshift(
        isLive: Boolean,
        isSeekable: Boolean,
        replayActive: Boolean,
        overlayOpen: Boolean,
        multiviewActive: Boolean,
    ): Boolean {
        return hasTimeshiftTransport(
            isLive = isLive,
            isSeekable = isSeekable,
            replayActive = replayActive,
        ) && !overlayOpen && !multiviewActive
    }

    /**
     * Provider catch-up URLs are regular seekable media items rather than Media3
     * live windows. An active replay therefore remains a valid timeshift session
     * even when PlayerState.isLive is false (and MPV cannot expose isSeekable).
     */
    fun hasTimeshiftTransport(
        isLive: Boolean,
        isSeekable: Boolean,
        replayActive: Boolean,
    ): Boolean = replayActive || (isLive && isSeekable)

    fun timeshiftTimeline(
        replayActive: Boolean,
        replayPositionMs: Long,
        replayDurationMs: Long,
        playerPositionMs: Long,
        playerDurationMs: Long,
    ): TimeshiftTimeline {
        val durationMs = if (replayActive) replayDurationMs else playerDurationMs
        val positionMs = if (replayActive) replayPositionMs else playerPositionMs
        val safeDuration = durationMs.coerceAtLeast(0L)
        return TimeshiftTimeline(
            positionMs = positionMs.coerceIn(0L, safeDuration),
            durationMs = safeDuration,
        )
    }

    fun isChannelFavorite(
        channel: Channel,
        favorites: List<Channel>,
    ): Boolean {
        val currentIdentities = channelIdentityCandidates(channel)
        return favorites.any { favorite ->
            channelIdentityCandidates(favorite).any(currentIdentities::contains)
        }
    }

    fun replayDurationMs(programme: EpgProgramme?): Long = programme
        ?.let { (it.endTime - it.startTime).coerceAtLeast(0L) }
        ?: 0L

    fun replayAvailableDurationMs(
        programme: EpgProgramme?,
        nowMs: Long,
    ): Long = programme
        ?.let { (minOf(it.endTime, nowMs) - it.startTime).coerceAtLeast(0L) }
        ?: 0L

    fun replayTimelinePositionMs(
        windowStartOffsetMs: Long,
        playerPositionMs: Long,
        durationMs: Long,
    ): Long = (windowStartOffsetMs + playerPositionMs.coerceAtLeast(0L))
        .coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun replaySeekTargetMs(
        currentPositionMs: Long,
        deltaMs: Long,
        durationMs: Long,
    ): Long {
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (safeDuration == 0L) return 0L
        return (currentPositionMs + deltaMs).coerceIn(0L, safeDuration)
    }
}

internal data class TimeshiftTimeline(
    val positionMs: Long,
    val durationMs: Long,
)
