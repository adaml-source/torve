package com.torve.domain.recording

/**
 * User-configurable disk budget for IPTV recordings (Prompt 21 — DVR
 * storage governance). Three independent ceilings, applied in order;
 * a `null` on any field disables that ceiling.
 *
 *  - [maxTotalBytes]:        cap on combined size of every COMPLETED
 *                            recording. When exceeded, oldest entries
 *                            are pruned until the survivors fit under.
 *  - [maxRetentionDays]:     any COMPLETED recording older than this
 *                            many days is pruned regardless of size.
 *  - [perSeriesKeepCount]:   per-series-pass keep-last-N. Once a
 *                            series accumulates more than N completed
 *                            recordings, the oldest are pruned.
 *
 * The default [UNLIMITED] is a no-op so existing users keep every
 * recording until they opt in to a quota.
 */
data class RecordingStorageQuota(
    val maxTotalBytes: Long? = null,
    val maxRetentionDays: Int? = null,
    val perSeriesKeepCount: Int? = null,
) {
    companion object {
        /** Default — no ceiling. Nothing is ever pruned. */
        val UNLIMITED = RecordingStorageQuota()
    }
}

/**
 * Pure picker for which recordings to delete given a [quota] and the
 * current set of recordings. Lives in the domain layer so the desktop
 * service (and a future mobile client) can run it without the file
 * system, then hand the returned ids to a real `delete(id)`.
 */
object RecordingStoragePruneSelector {

    /**
     * Pick recordings to delete to satisfy [quota] given the current
     * [recordings] list and [nowMs] clock. Application order:
     *   1. Past [maxRetentionDays] (oldest-first by completedAt).
     *   2. Series-pass overflow (oldest entries beyond keepCount).
     *   3. Total-bytes ceiling — trim oldest survivors until the
     *      combined `fileSizeBytes` fits under [maxTotalBytes].
     *
     * Only recordings with status [RecordingStatus.COMPLETED] are
     * considered — in-progress and scheduled rows are never deleted
     * by the pruner.
     *
     * Returns the recording ids to delete, in deterministic order so
     * tests can assert exactly what's removed. Pure: never reads disk.
     */
    fun select(
        recordings: List<Recording>,
        quota: RecordingStorageQuota,
        nowMs: Long,
    ): List<String> {
        if (quota == RecordingStorageQuota.UNLIMITED) return emptyList()

        val completed = recordings
            .filter { it.status == RecordingStatus.COMPLETED }
            .sortedBy { it.completedAtMs ?: it.endMs }
        val toRemove = linkedSetOf<String>()

        // 1) Retention age
        val retentionMs = quota.maxRetentionDays
            ?.takeIf { it > 0 }
            ?.let { it.toLong() * MS_PER_DAY }
        if (retentionMs != null) {
            for (r in completed) {
                val completedAt = r.completedAtMs ?: r.endMs
                if (nowMs - completedAt > retentionMs) toRemove += r.id
            }
        }

        // 2) Per-series keep-last-N
        val keep = quota.perSeriesKeepCount
        if (keep != null && keep >= 0) {
            completed
                .filter { it.seriesPassId != null }
                .groupBy { it.seriesPassId!! }
                .forEach { (_, group) ->
                    val newestFirst = group.sortedByDescending { it.completedAtMs ?: it.endMs }
                    newestFirst.drop(keep).forEach { toRemove += it.id }
                }
        }

        // 3) Total bytes ceiling — trim oldest survivor first
        val maxBytes = quota.maxTotalBytes
        if (maxBytes != null && maxBytes >= 0L) {
            val survivors = completed.filterNot { it.id in toRemove }
            var total = survivors.sumOf { it.fileSizeBytes ?: 0L }
            if (total > maxBytes) {
                for (r in survivors) {
                    if (total <= maxBytes) break
                    toRemove += r.id
                    total -= r.fileSizeBytes ?: 0L
                }
            }
        }

        return toRemove.toList()
    }

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
}
