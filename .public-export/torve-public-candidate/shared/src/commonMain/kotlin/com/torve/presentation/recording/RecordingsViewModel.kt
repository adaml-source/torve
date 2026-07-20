package com.torve.presentation.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingMetadataSnapshot
import com.torve.domain.recording.RecordingRepository
import com.torve.domain.recording.RecordingScheduler
import com.torve.domain.recording.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * UI-side state for the IPTV recording library + scheduling overlay.
 *
 * Wraps [RecordingScheduler] (which owns the contract) + the
 * [RecordingRepository] (which owns persistence) so screens see one
 * surface. Three grouped buckets — `active`, `completed`, `failed` —
 * land on the "My Recordings" page; per-slot lookups feed the EPG
 * grid's record button + status pill.
 *
 * No-op when the platform hasn't wired the scheduler — every method
 * delegates to the scheduler, which itself is null-safe via the
 * coordinator pattern. Compose code can `koinInject()` this with a
 * default fallback (Koin's `getOrNull`) if the desktop module isn't
 * present.
 */
/**
 * Platform-agnostic hook the VM uses to start a recording immediately
 * (bypassing the scheduler's poll interval) and to delete recording
 * library rows. Implemented on desktop by `DesktopRecordingService`;
 * a no-op default keeps tests and platforms without a service working.
 */
interface RecordingStarter {
    suspend fun runNow(rec: Recording) {}
    suspend fun stopRunning(id: String): Boolean = false
    suspend fun deleteRow(id: String) {}
}

class RecordingsViewModel(
    private val scheduler: RecordingScheduler,
    private val repository: RecordingRepository,
    private val starter: RecordingStarter = object : RecordingStarter {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _conflict = MutableStateFlow<PendingConflict?>(null)

    /** Hot list of every recording, sorted by start time. */
    val state: StateFlow<RecordingsUiState> = combine(
        repository.recordings,
        _conflict,
    ) { rows, pending ->
        RecordingsUiState(
            active = rows.filter {
                it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING
            }.sortedBy { it.startMs },
            completed = rows.filter { it.status == RecordingStatus.COMPLETED }
                .sortedByDescending { it.completedAtMs ?: it.endMs },
            failed = rows.filter {
                it.status == RecordingStatus.FAILED || it.status == RecordingStatus.CANCELLED
            }.sortedByDescending { it.completedAtMs ?: it.endMs },
            conflict = pending,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = RecordingsUiState(),
    )

    /**
     * Snapshot lookup used by the EPG grid to render the per-slot pill
     * + button label. Synchronous since the underlying state flow is
     * already collected.
     */
    fun statusFor(streamUrl: String, startMs: Long, endMs: Long): RecordingSlotStatus {
        if (streamUrl.isBlank()) return RecordingSlotStatus.NONE
        val match = state.value.allRows().firstOrNull { row ->
            row.streamUrl == streamUrl && row.startMs == startMs && row.endMs == endMs
        } ?: return RecordingSlotStatus.NONE
        return when (match.status) {
            RecordingStatus.SCHEDULED -> RecordingSlotStatus.SCHEDULED
            RecordingStatus.RECORDING -> RecordingSlotStatus.RECORDING
            RecordingStatus.COMPLETED -> RecordingSlotStatus.COMPLETED
            RecordingStatus.FAILED -> RecordingSlotStatus.FAILED
            RecordingStatus.CANCELLED -> RecordingSlotStatus.CANCELLED
        }
    }

    /**
     * Returns the existing recording row for a slot if one is on file —
     * used by the EPG dropdown to switch between "Record" and "Cancel
     * recording".
     */
    fun rowFor(streamUrl: String, startMs: Long, endMs: Long): Recording? =
        state.value.allRows().firstOrNull { row ->
            row.streamUrl == streamUrl && row.startMs == startMs && row.endMs == endMs
        }

    /**
     * Schedule a one-off recording. On overlap, parks the candidate in
     * [_conflict] and surfaces it as [RecordingsUiState.conflict] so the
     * UI can render an explicit "Schedule anyway / Cancel" prompt.
     */
    fun schedule(
        playlistId: String,
        channelId: String,
        channelName: String,
        streamUrl: String,
        programmeTitle: String,
        programmeDescription: String?,
        startMs: Long,
        endMs: Long,
        metadata: RecordingMetadataSnapshot? = null,
    ) {
        scope.launch {
            val outcome = scheduler.schedule(
                playlistId = playlistId,
                channelId = channelId,
                channelName = channelName,
                streamUrl = streamUrl,
                programmeTitle = programmeTitle,
                programmeDescription = programmeDescription,
                startMs = startMs,
                endMs = endMs,
                metadata = metadata,
            )
            when (outcome) {
                is RecordingScheduler.ScheduleResult.Conflict -> {
                    _conflict.value = PendingConflict(
                        candidate = outcome.candidate,
                        existing = outcome.existing,
                    )
                }
                is RecordingScheduler.ScheduleResult.Scheduled -> {
                    _conflict.value = null
                    // Kick off immediately if start time is now-or-past so
                    // "Record Now" doesn't have to wait for the scheduler's
                    // 30-second poll. Without this, a user who pressed Stop
                    // within 30s of pressing Record would have ZERO bytes
                    // written to disk (service hadn't yet picked up the
                    // scheduled row) and the row would land as Cancelled.
                    val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                    if (outcome.recording.startMs <= now) {
                        runCatching { starter.runNow(outcome.recording) }
                    }
                }
                is RecordingScheduler.ScheduleResult.Invalid,
                RecordingScheduler.ScheduleResult.InThePast -> {
                    // The user-facing copy is rendered from the
                    // returned outcome; expose it via a one-shot
                    // toast in a future slice if needed.
                    _conflict.value = null
                }
            }
        }
    }

    /** Confirm the parked conflict by re-scheduling with `force = true`. */
    fun confirmConflict() {
        val pending = _conflict.value ?: return
        _conflict.value = null
        scope.launch {
            scheduler.schedule(
                playlistId = pending.candidate.playlistId,
                channelId = pending.candidate.channelId,
                channelName = pending.candidate.channelName,
                streamUrl = pending.candidate.streamUrl,
                programmeTitle = pending.candidate.programmeTitle,
                programmeDescription = pending.candidate.programmeDescription,
                startMs = pending.candidate.startMs,
                endMs = pending.candidate.endMs,
                force = true,
                metadata = pending.candidate.toMetadataSnapshot(),
            )
        }
    }

    /** User dismissed the conflict prompt. */
    fun dismissConflict() {
        _conflict.value = null
    }

    fun cancel(id: String) {
        scope.launch {
            val stopped = runCatching { starter.stopRunning(id) }.getOrDefault(false)
            if (!stopped) {
                scheduler.cancel(id)
            }
        }
    }

    /**
     * Permanently delete a recording row. Removes the database entry. The
     * desktop starter implementation also deletes the .ts file from disk
     * if one exists, so the user gets full removal in a single action.
     * Active recordings should be cancelled first; UI surfaces a
     * confirmation step before calling this.
     */
    fun delete(id: String) {
        scope.launch {
            // Cancel anything still running before removing the row, so the
            // service doesn't try to write into a deleted row.
            val current = repository.get(id)
            if (current != null && (current.status == RecordingStatus.SCHEDULED ||
                    current.status == RecordingStatus.RECORDING)
            ) {
                scheduler.cancel(id)
            }
            runCatching { starter.deleteRow(id) }
            repository.delete(id)
        }
    }
}

private fun Recording.toMetadataSnapshot(): RecordingMetadataSnapshot = RecordingMetadataSnapshot(
    programmeTitle = programmeTitle,
    programmeDescription = programmeDescription,
    epgProgrammeTitle = epgProgrammeTitle,
    epgProgrammeSubtitle = epgProgrammeSubtitle,
    epgProgrammeCategory = epgProgrammeCategory,
    epgProgrammeIconUrl = epgProgrammeIconUrl,
    epgChannelId = epgChannelId,
    sourceLabel = sourceLabel,
    recordingKind = recordingKind,
    epgMatchStatus = epgMatchStatus,
)

/**
 * Bucketed UI state. Each bucket is sorted for the page that consumes it.
 */
data class RecordingsUiState(
    val active: List<Recording> = emptyList(),
    val completed: List<Recording> = emptyList(),
    val failed: List<Recording> = emptyList(),
    val conflict: PendingConflict? = null,
) {
    fun allRows(): List<Recording> = active + completed + failed
    val totalCount: Int get() = active.size + completed.size + failed.size
}

/** Per-EPG-slot status hint the V2 grid uses to render the row pill. */
enum class RecordingSlotStatus {
    NONE,
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * One overlapping schedule attempt. The UI shows the existing window,
 * the candidate's window, and offers "Schedule anyway" or "Cancel".
 */
data class PendingConflict(
    val candidate: Recording,
    val existing: Recording,
)
