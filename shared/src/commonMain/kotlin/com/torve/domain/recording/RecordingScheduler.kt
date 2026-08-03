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
 * Series passes are expanded through [passResolver], de-duplicated against
 * recording history, conflict-checked, and persisted as scheduled rows.
 * A missing resolver remains an explicit capability-off state.
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
        data class SeriesScheduled(
            val passId: String,
            val recordings: List<Recording>,
            val skippedConflicts: Int,
        ) : ScheduleResult
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

    /** Resolve and persist every future, non-conflicting episode for a pass. */
    suspend fun scheduleSeriesPass(pass: RecordingSeriesPass): ScheduleResult {
        if (passResolver == null) {
            return ScheduleResult.Invalid(
                "Series-pass scheduling isn't enabled yet. Use one-off recordings for now.",
            )
        }
        if (pass.titleMatch.isBlank()) {
            return ScheduleResult.Invalid("Enter a programme title for the series pass.")
        }
        val resolved = passResolver.resolve(pass, nowMs())
            .filter { it.endMs > nowMs() && it.endMs > it.startMs && it.streamUrl.isNotBlank() }
            .distinctBy { Triple(it.channelId, it.startMs, it.endMs) }
            .sortedBy { it.startMs }
        if (resolved.isEmpty()) {
            return ScheduleResult.Invalid("No future matching episodes were found in the current guide.")
        }

        val occupied = repository.listAll().toMutableList()
        val accepted = mutableListOf<Recording>()
        var skippedConflicts = 0
        for (candidate in resolved) {
            val normalized = candidate.copy(
                scheduleKind = RecordingScheduleKind.SERIES,
                seriesPassId = pass.id,
                status = RecordingStatus.SCHEDULED,
            )
            if (RecordingConflictDetector.firstConflict(normalized, occupied) != null) {
                skippedConflicts += 1
                continue
            }
            repository.upsert(normalized)
            occupied += normalized
            accepted += normalized
        }
        if (accepted.isEmpty()) {
            return ScheduleResult.Invalid("Matching episodes conflict with existing recordings.")
        }
        return ScheduleResult.SeriesScheduled(
            passId = pass.id,
            recordings = accepted,
            skippedConflicts = skippedConflicts,
        )
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
