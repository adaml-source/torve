package com.torve.domain.recording

/**
 * Pure conflict detection for IPTV recordings.
 *
 * "Conflict" = two recordings whose scheduled windows overlap on the
 * **same playlist** (same M3U / Xtream feed). Different playlists are
 * assumed to back independent tuners — Torve doesn't model multi-tuner
 * constraints beyond that.
 *
 * Status filter: only [RecordingStatus.SCHEDULED] and
 * [RecordingStatus.RECORDING] participate. COMPLETED / FAILED /
 * CANCELLED can't conflict.
 *
 * Algorithm: O(n log n) sweep — sort by `startMs`, walk and keep the
 * "active" set bounded by the latest `endMs` we've seen. Pairs reported
 * unordered (id1 < id2) so the same conflict isn't surfaced twice.
 */
object RecordingConflictDetector {

    /** Pair of recording ids that overlap. */
    data class Conflict(val first: String, val second: String)

    fun detect(recordings: List<Recording>): List<Conflict> {
        val active = recordings.filter {
            it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING
        }
        if (active.size < 2) return emptyList()
        val byPlaylist = active.groupBy { it.playlistId }
        val results = mutableListOf<Conflict>()
        for ((_, group) in byPlaylist) {
            val sorted = group.sortedBy { it.startMs }
            for (i in sorted.indices) {
                val a = sorted[i]
                for (j in i + 1 until sorted.size) {
                    val b = sorted[j]
                    // Sorted by startMs ascending → b.startMs >= a.startMs.
                    // Conflict iff b.startMs < a.endMs.
                    if (b.startMs >= a.endMs) break
                    val (lo, hi) = if (a.id < b.id) a.id to b.id else b.id to a.id
                    results += Conflict(lo, hi)
                }
            }
        }
        return results
    }

    /**
     * Convenience: does [candidate] conflict with anything in [existing]?
     * Used by [RecordingScheduler.schedule] to refuse overlapping
     * one-offs unless the caller passes `force = true`.
     */
    fun firstConflict(candidate: Recording, existing: List<Recording>): Recording? {
        if (candidate.status == RecordingStatus.COMPLETED ||
            candidate.status == RecordingStatus.FAILED ||
            candidate.status == RecordingStatus.CANCELLED
        ) return null
        return existing.firstOrNull { other ->
            other.id != candidate.id &&
                other.playlistId == candidate.playlistId &&
                (other.status == RecordingStatus.SCHEDULED || other.status == RecordingStatus.RECORDING) &&
                other.startMs < candidate.endMs &&
                candidate.startMs < other.endMs
        }
    }
}
