package com.torve.domain.recording

import com.torve.domain.model.EpgProgramme
import kotlinx.serialization.Serializable

/**
 * Per-playlist EPG correction state. Stored as JSON in the
 * `preference` table under the playlist's id; each correction is
 * surfaced in TV Guide as a non-blocking warning + a one-tap edit.
 *
 * **Three corrections this slice ships:**
 *   1. [offsetMinutes] — apply to every programme's start/end before
 *      rendering. Lets the user fix providers whose EPG is one hour
 *      off (DST) without re-uploading XMLTV.
 *   2. [tvgIdRemap] — manual `playlistChannelId → epgChannelId` map.
 *      Lets the user re-pair a channel whose tvg-id doesn't match
 *      the EPG. The existing EPG fetcher honors the remap before
 *      computing match counts.
 *   3. [hiddenCategories] — category names the guide should drop. The
 *      sidebar de-clutters without editing the M3U.
 *
 * The model is pure data — application of the offset and remap is
 * done by helper functions on this file so consumers can stay
 * platform-clean.
 */
@Serializable
data class EpgCorrection(
    val playlistId: String,
    val offsetMinutes: Int = 0,
    val tvgIdRemap: Map<String, String> = emptyMap(),
    val hiddenCategories: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = offsetMinutes == 0 && tvgIdRemap.isEmpty() && hiddenCategories.isEmpty()
}

/**
 * Apply a positive [offsetMinutes] to every programme's start/end.
 * Negative values shift the guide earlier. Stable when called on
 * already-shifted lists — [EpgCorrection.offsetMinutes] is the *single*
 * authority; consumers should never double-apply.
 */
fun applyEpgOffset(programmes: List<EpgProgramme>, offsetMinutes: Int): List<EpgProgramme> {
    if (offsetMinutes == 0) return programmes
    val shiftMs = offsetMinutes.toLong() * 60_000L
    return programmes.map { it.copy(startTime = it.startTime + shiftMs, endTime = it.endTime + shiftMs) }
}

/**
 * Replace `channelId` on programmes per [remap]. Used after parsing the
 * EPG so downstream code matches the user-corrected tvg-id without
 * needing to know the remap exists.
 */
fun applyTvgIdRemap(
    programmes: List<EpgProgramme>,
    remap: Map<String, String>,
): List<EpgProgramme> {
    if (remap.isEmpty()) return programmes
    return programmes.map { p ->
        val target = remap[p.channelId]
        if (target == null || target == p.channelId) p else p.copy(channelId = target)
    }
}

/**
 * Compute [EpgHealth] from a programme list. Used by the TV Guide
 * stale-data warning banner.
 */
fun computeEpgHealth(
    playlistId: String,
    matchedChannelCount: Int,
    unmatchedChannelCount: Int,
    programmes: List<EpgProgramme>,
    nowMs: Long,
): EpgHealth = EpgHealth(
    playlistId = playlistId,
    matchedChannelCount = matchedChannelCount,
    unmatchedChannelCount = unmatchedChannelCount,
    latestProgrammeEndMs = programmes.maxOfOrNull { it.endTime },
    nowMs = nowMs,
)
