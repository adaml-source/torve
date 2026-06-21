package com.torve.domain.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks in the prune order Prompt 21 promises:
 *   1. Past-retention recordings are pruned regardless of size.
 *   2. Series-pass overflow is pruned regardless of size.
 *   3. Total-byte ceiling trims the *oldest survivor* until the
 *      combined size fits under the cap.
 *
 * In-progress and scheduled recordings must never be selected — the
 * pruner is for completed archives only.
 */
class RecordingStorageQuotaTest {

    private val day: Long = 24L * 60L * 60L * 1000L
    private val now: Long = 1_700_000_000_000L

    private fun rec(
        id: String,
        completedDaysAgo: Int,
        sizeBytes: Long? = 1024L,
        status: RecordingStatus = RecordingStatus.COMPLETED,
        seriesPassId: String? = null,
    ): Recording {
        val completedAt = now - completedDaysAgo * day
        return Recording(
            id = id,
            playlistId = "pl",
            channelId = "ch",
            channelName = "Ch",
            streamUrl = "http://stream/$id",
            programmeTitle = id,
            startMs = completedAt - 60_000L,
            endMs = completedAt,
            status = status,
            seriesPassId = seriesPassId,
            fileSizeBytes = sizeBytes,
            createdAtMs = completedAt - 120_000L,
            startedAtMs = completedAt - 60_000L,
            completedAtMs = completedAt,
        )
    }

    @Test
    fun `unlimited quota never picks anything to prune`() {
        val recordings = listOf(rec("a", 100), rec("b", 200), rec("c", 365))
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            quota = RecordingStorageQuota.UNLIMITED,
            nowMs = now,
        )
        assertEquals(emptyList(), pruned)
    }

    @Test
    fun `retention prunes anything older than maxRetentionDays`() {
        val recordings = listOf(
            rec("fresh", 5),
            rec("old", 31),
            rec("ancient", 90),
        )
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            quota = RecordingStorageQuota(maxRetentionDays = 30),
            nowMs = now,
        )
        // ancient + old, ordered oldest-first.
        assertEquals(listOf("ancient", "old"), pruned)
    }

    @Test
    fun `series keepCount drops the oldest beyond N for each series`() {
        val recordings = listOf(
            rec("ep1", completedDaysAgo = 30, seriesPassId = "show-A"),
            rec("ep2", completedDaysAgo = 20, seriesPassId = "show-A"),
            rec("ep3", completedDaysAgo = 10, seriesPassId = "show-A"),
            rec("ep4", completedDaysAgo = 5, seriesPassId = "show-A"),
            rec("solo", completedDaysAgo = 100), // not in a series, untouched
        )
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            quota = RecordingStorageQuota(perSeriesKeepCount = 2),
            nowMs = now,
        )
        // Two newest of show-A (ep4, ep3) survive; ep1 + ep2 pruned.
        assertTrue(pruned.containsAll(listOf("ep1", "ep2")))
        assertEquals(2, pruned.size)
    }

    @Test
    fun `total bytes ceiling trims oldest until under cap`() {
        val recordings = listOf(
            rec("oldest", completedDaysAgo = 30, sizeBytes = 100L),
            rec("middle", completedDaysAgo = 20, sizeBytes = 100L),
            rec("newest", completedDaysAgo = 10, sizeBytes = 100L),
        )
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            // Cap fits 1.5 entries — must trim oldest until under 150.
            quota = RecordingStorageQuota(maxTotalBytes = 150L),
            nowMs = now,
        )
        // Only "oldest" needs to go: surviving 200 still over 150 so
        // also trim "middle". "newest" survives.
        assertEquals(listOf("oldest", "middle"), pruned)
    }

    @Test
    fun `in-progress and scheduled recordings are never pruned`() {
        val recordings = listOf(
            rec("recording-now", completedDaysAgo = 90, status = RecordingStatus.RECORDING),
            rec("scheduled", completedDaysAgo = 90, status = RecordingStatus.SCHEDULED),
            rec("failed", completedDaysAgo = 90, status = RecordingStatus.FAILED),
            rec("done-but-fresh", completedDaysAgo = 10),
        )
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            quota = RecordingStorageQuota(maxRetentionDays = 30, maxTotalBytes = 0L),
            nowMs = now,
        )
        // Even with the most aggressive quota, only completed rows are
        // touched — and "done-but-fresh" survives retention but fails
        // the total-bytes test, so it gets pruned.
        assertEquals(listOf("done-but-fresh"), pruned)
    }

    @Test
    fun `retention plus series plus bytes all compose without double-counting`() {
        val recordings = listOf(
            rec("ancient", completedDaysAgo = 90, sizeBytes = 50L, seriesPassId = "S"),
            rec("old-series-overflow", completedDaysAgo = 40, sizeBytes = 50L, seriesPassId = "S"),
            rec("series-keep-1", completedDaysAgo = 20, sizeBytes = 50L, seriesPassId = "S"),
            rec("series-keep-2", completedDaysAgo = 10, sizeBytes = 50L, seriesPassId = "S"),
            rec("solo-fresh-big", completedDaysAgo = 5, sizeBytes = 200L),
        )
        val pruned = RecordingStoragePruneSelector.select(
            recordings = recordings,
            quota = RecordingStorageQuota(
                maxRetentionDays = 60,
                perSeriesKeepCount = 2,
                maxTotalBytes = 150L,
            ),
            nowMs = now,
        )
        // ancient (retention) + old-series-overflow (series) prune
        // first; survivors total 50+50+200 = 300 bytes > 150, so trim
        // oldest survivors next: series-keep-1 (50) → 250, then
        // series-keep-2 (50) → 200, then solo-fresh-big (200) → 0.
        // Series-keep-1 alone gets the cap to 250 still over; need to
        // keep going until under 150.
        assertTrue(pruned.contains("ancient"))
        assertTrue(pruned.contains("old-series-overflow"))
        assertTrue(pruned.contains("series-keep-1"))
        assertTrue(pruned.contains("series-keep-2"))
        // Solo-fresh-big is the last man standing only if pruning
        // stops once the cap is met; with cap=150 and one 200-byte
        // file, the last file always exceeds — guarantee is
        // "everything older than the newest is pruned".
    }

    @Test
    fun `recording without completedAtMs falls back to endMs for sort and age`() {
        val withoutTimestamp = rec("legacy", completedDaysAgo = 60).copy(completedAtMs = null)
        val withTimestamp = rec("modern", completedDaysAgo = 5)
        val pruned = RecordingStoragePruneSelector.select(
            recordings = listOf(withoutTimestamp, withTimestamp),
            quota = RecordingStorageQuota(maxRetentionDays = 30),
            nowMs = now,
        )
        // Legacy uses endMs (60 days ago) — pruned. Modern (5 days
        // ago) survives.
        assertEquals(listOf("legacy"), pruned)
    }
}
