package com.torve.domain.recording

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * Pure scheduling layer. Owns the contract:
 *   - Schedule a one-off recording from an EPG programme + channel.
 *   - Refuse overlapping schedules unless the caller explicitly forces.
 *   - Flip status: SCHEDULED → RECORDING → COMPLETED / FAILED.
 *   - Cancel any non-terminal recording.
 *
 * Storage IO is delegated to [RecordingRepository]; the actual stream
 * pull is delegated to a [RecordingService] (desktop only).
 *
 * Series passes: schema only. [scheduleSeriesPass] refuses unless
 * `passResolver` is wired; default null = disabled. UI surfaces the
 * "coming soon" copy by checking [seriesPassesEnabled].
 */
class RecordingScheduler(
    private val repository: RecordingRepository,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { defaultId() },
    private val passResolver: SeriesPassResolver? = null,
) {

    /** Surfaces the in-progress + scheduled rows as a derived flow. */
    val active: Flow<List<Recording>> = repository.recordings.map { rows ->
        rows.filter {
            it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING
        }.sortedBy { it.startMs }
    }

    val seriesPassesEnabled: Boolean get() = passResolver != null

    /**
     * Outcomes of a schedule attempt — modeled explicitly so UI knows
     * which user-friendly copy to surface.
     */
    sealed interface ScheduleResult {
        data class Scheduled(val recording: Recording) : ScheduleResult
        data class Conflict(val candidate: Recording, val existing: Recording) : ScheduleResult
        data object InThePast : ScheduleResult
        data class Invalid(val reason: String) : ScheduleResult
    }

    /**
     * Schedule a one-off recording. Refuses overlapping schedules unless
     * [force] is true (UI's "Schedule anyway" button).
     */
    suspend fun schedule(
        playlistId: String,
        channelId: String,
        channelName: String,
        streamUrl: String,
        programmeTitle: String,
        programmeDescription: String?,
        startMs: Long,
        endMs: Long,
        force: Boolean = false,
        metadata: RecordingMetadataSnapshot? = null,
    ): ScheduleResult {
        if (endMs <= startMs) return ScheduleResult.Invalid("End must be after start.")
        if (endMs <= nowMs()) return ScheduleResult.InThePast
        if (streamUrl.isBlank()) return ScheduleResult.Invalid("Channel has no stream URL.")
        val candidate = Recording(
            id = newId(),
            playlistId = playlistId,
            channelId = channelId,
            channelName = channelName,
            streamUrl = streamUrl,
            programmeTitle = metadata?.programmeTitle ?: programmeTitle,
            programmeDescription = metadata?.programmeDescription ?: programmeDescription,
            epgProgrammeTitle = metadata?.epgProgrammeTitle,
            epgProgrammeSubtitle = metadata?.epgProgrammeSubtitle,
            epgProgrammeCategory = metadata?.epgProgrammeCategory,
            epgProgrammeIconUrl = metadata?.epgProgrammeIconUrl,
            epgChannelId = metadata?.epgChannelId,
            sourceLabel = metadata?.sourceLabel,
            recordingKind = metadata?.recordingKind ?: RecordingKind.SCHEDULED_EPG,
            epgMatchStatus = metadata?.epgMatchStatus ?: RecordingEpgMatchStatus.UNKNOWN,
            startMs = startMs,
            endMs = endMs,
            status = RecordingStatus.SCHEDULED,
            scheduleKind = RecordingScheduleKind.ONE_OFF,
            createdAtMs = nowMs(),
        )
        if (!force) {
            val conflict = RecordingConflictDetector
                .firstConflict(candidate, repository.listAll())
            if (conflict != null) return ScheduleResult.Conflict(candidate, conflict)
        }
        repository.upsert(candidate)
        return ScheduleResult.Scheduled(candidate)
    }

    /**
     * Series passes are schema-only this slice. Always returns Disabled
     * unless [passResolver] is wired (deferred to a future slice).
     */
    suspend fun scheduleSeriesPass(@Suppress("UNUSED_PARAMETER") pass: RecordingSeriesPass): ScheduleResult {
        if (passResolver == null) {
            return ScheduleResult.Invalid(
                "Series-pass scheduling isn't enabled yet. Use one-off recordings for now.",
            )
        }
        return ScheduleResult.Invalid("Not yet implemented.")
    }

    /**
     * Mark a recording started. Caller (the platform [RecordingService])
     * calls this when the file is opened and the first byte is written;
     * idempotent — returns the latest row whether or not it transitioned.
     */
    suspend fun markStarted(id: String, filePath: String): Recording? {
        val current = repository.get(id) ?: return null
        if (current.status != RecordingStatus.SCHEDULED) return current
        val updated = current.copy(
            status = RecordingStatus.RECORDING,
            filePath = filePath,
            startedAtMs = nowMs(),
        )
        repository.upsert(updated)
        return updated
    }

    /** Mark the recording finished cleanly. */
    suspend fun markCompleted(id: String, fileSizeBytes: Long): Recording? {
        val current = repository.get(id) ?: return null
        if (current.status == RecordingStatus.COMPLETED ||
            current.status == RecordingStatus.CANCELLED
        ) return current
        val updated = current.copy(
            status = RecordingStatus.COMPLETED,
            fileSizeBytes = fileSizeBytes,
            completedAtMs = nowMs(),
        )
        repository.upsert(updated)
        return updated
    }

    suspend fun markFailed(
        id: String,
        reason: RecordingFailureReason,
        message: String?,
    ): Recording? {
        val current = repository.get(id) ?: return null
        if (current.status == RecordingStatus.COMPLETED ||
            current.status == RecordingStatus.CANCELLED
        ) return current
        val updated = current.copy(
            status = RecordingStatus.FAILED,
            failureReason = reason,
            failureMessage = message,
            completedAtMs = nowMs(),
        )
        repository.upsert(updated)
        return updated
    }

    suspend fun cancel(id: String): Recording? {
        val current = repository.get(id) ?: return null
        if (current.status == RecordingStatus.COMPLETED ||
            current.status == RecordingStatus.FAILED ||
            current.status == RecordingStatus.CANCELLED
        ) return current
        val updated = current.copy(
            status = RecordingStatus.CANCELLED,
            completedAtMs = nowMs(),
        )
        repository.upsert(updated)
        return updated
    }

    /** Detect every overlapping pair in the current state. */
    suspend fun conflicts(): List<RecordingConflictDetector.Conflict> =
        RecordingConflictDetector.detect(repository.listAll())

    companion object {
        private fun defaultId(): String = "rec-" +
            (Clock.System.now().toEpochMilliseconds().toString(16)) + "-" +
            ((Int.MIN_VALUE..Int.MAX_VALUE).random().toString(16))
    }
}

/**
 * Hook the scheduler calls when a series pass is materialized. Out of
 * scope this slice — the contract is here so the next slice can wire
 * the resolver without changing the scheduler API.
 */
fun interface SeriesPassResolver {
    suspend fun resolve(pass: RecordingSeriesPass, nowMs: Long): List<Recording>
}
