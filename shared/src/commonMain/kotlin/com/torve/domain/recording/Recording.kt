package com.torve.domain.recording

import kotlinx.serialization.Serializable

/**
 * Lifecycle status of an IPTV recording.
 *
 *   - [SCHEDULED]  — entry exists but the start time hasn't arrived yet.
 *   - [RECORDING]  — service is actively pulling the live stream into a
 *                    file. Progress is byte-count only; per-second is
 *                    a UI follow-up.
 *   - [COMPLETED]  — file is closed, on disk, and inside the configured
 *                    recordings folder.
 *   - [FAILED]     — pull aborted before reaching `endMs`. The
 *                    [Recording.failureReason] carries an actionable
 *                    line for the UI.
 *   - [CANCELLED]  — user cancelled before usable output existed, or
 *                    explicitly discarded it. User Stop on an active
 *                    recording with bytes on disk finalizes as COMPLETED.
 */
@Serializable
enum class RecordingStatus { SCHEDULED, RECORDING, COMPLETED, FAILED, CANCELLED }

/**
 * Distinct categories of recording failures so the UI can present
 * actionable copy instead of a raw exception. Mapping from a
 * [Throwable] / IO error happens inside [RecordingService] so the
 * domain stays platform-clean.
 */
@Serializable
enum class RecordingFailureReason {
    NETWORK_ERROR,
    UPSTREAM_REJECTED,
    FILE_WRITE_ERROR,
    OUT_OF_ALLOWLIST,
    DISK_FULL,
    CANCELLED_BY_USER,
    UNKNOWN
}

/**
 * Schedule shape. [SERIES] is schema-only for this slice — see
 * `RecordingSeriesPass` for the data class. The scheduler refuses to
 * schedule series passes unless the platform's series-resolver hook is
 * provided; UI surfaces the disabled state with a "coming soon" copy.
 */
@Serializable
enum class RecordingKind {
    LIVE,
    SCHEDULED_EPG,
    SERIES_EPG
}

@Serializable
enum class RecordingEpgMatchStatus {
    MATCHED,
    NO_MATCH_AT_START,
    UNKNOWN
}

@Serializable
enum class RecordingScheduleKind { ONE_OFF, SERIES }

/**
 * One scheduled / in-flight / archived recording.
 *
 * The shape is platform-clean: no filesystem objects, no Ktor types.
 * Storage is handled by [RecordingService] on desktop; the data class
 * itself round-trips through SQLDelight + JSON without changes.
 *
 * [filePath] is **null until the recording starts**. Once non-null it
 * MUST sit inside one of the configured recording folders — enforced
 * by [RecordingService.startRecording] and re-checked on every status
 * transition. UI surfaces the path only as a "Reveal in Finder" hint;
 * it never travels to AI prompts or LAN listings.
 */
@Serializable
data class Recording(
    val id: String,
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val streamUrl: String,
    val programmeTitle: String,
    val programmeDescription: String? = null,
    val epgProgrammeTitle: String? = null,
    val epgProgrammeSubtitle: String? = null,
    val epgProgrammeCategory: String? = null,
    val epgProgrammeIconUrl: String? = null,
    val epgChannelId: String? = null,
    val sourceLabel: String? = null,
    val recordingKind: RecordingKind = RecordingKind.SCHEDULED_EPG,
    val epgMatchStatus: RecordingEpgMatchStatus = RecordingEpgMatchStatus.UNKNOWN,
    val startMs: Long,
    val endMs: Long,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    val scheduleKind: RecordingScheduleKind = RecordingScheduleKind.ONE_OFF,
    val seriesPassId: String? = null,
    val filePath: String? = null,
    val fileSizeBytes: Long? = null,
    val failureReason: RecordingFailureReason? = null,
    val failureMessage: String? = null,
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    val displayTitle: String
        get() = epgProgrammeTitle?.takeIf { it.isNotBlank() }
            ?: programmeTitle.takeIf { it.isNotBlank() }
            ?: channelName
}

/**
 * Schema for series passes. **Not active in this slice** — the
 * scheduler refuses to enqueue passes unless the platform supplies a
 * resolver that turns the pass into concrete one-off [Recording] rows.
 * Kept here so the UI / DB schema is forward-compatible.
 */
@Serializable
data class RecordingSeriesPass(
    val id: String,
    val playlistId: String,
    val channelId: String,
    /** Programme title to match (case-insensitive substring). */
    val titleMatch: String,
    /** When true, skip programmes already in `Recording` history. */
    val recordOnlyNew: Boolean = true,
    /** Optional season-number lower bound. */
    val seasonMin: Int? = null,
    /** Optional last-N policy: keep only N most recent recordings. */
    val keepCount: Int? = null,
    val createdAtMs: Long,
)

/**
 * Diagnostic state surfaced when EPG data looks stale or unmatched.
 * The UI renders these as non-blocking warnings — playback still
 * works, but the user knows the guide may be wrong.
 */
@Serializable
data class EpgHealth(
    val playlistId: String,
    val matchedChannelCount: Int,
    val unmatchedChannelCount: Int,
    val latestProgrammeEndMs: Long?,
    val nowMs: Long,
) {
    /** True when the latest programme ended before [nowMs]. */
    val isStale: Boolean
        get() = latestProgrammeEndMs != null && latestProgrammeEndMs < nowMs
    val matchPercent: Int
        get() {
            val total = matchedChannelCount + unmatchedChannelCount
            return if (total == 0) 0 else (matchedChannelCount * 100 / total)
        }
}
