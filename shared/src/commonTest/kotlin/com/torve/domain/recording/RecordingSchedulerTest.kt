package com.torve.domain.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the scheduler's contract:
 *   - schedule one-off creates a SCHEDULED row
 *   - overlapping schedule returns Conflict (unless force=true)
 *   - in-the-past schedule returns InThePast
 *   - markStarted / markCompleted / markFailed / cancel transition
 *     status correctly and only from non-terminal states
 *   - series passes refused unless a resolver is wired
 *
 * Uses an in-memory [RecordingRepository] stub. Coroutines run on
 * `runTest`'s scheduler so timing is deterministic.
 */
class RecordingSchedulerTest {

    private val now = 1_700_000_000_000L
    private fun ms(seconds: Int) = seconds * 1000L

    @Test
    fun `schedule one-off creates a SCHEDULED row`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "id-1" })
        val result = sch.schedule(
            playlistId = "p1",
            channelId = "ch-1",
            channelName = "BBC One",
            streamUrl = "http://x/stream",
            programmeTitle = "Sherlock",
            programmeDescription = null,
            startMs = now + ms(60),
            endMs = now + ms(3600),
        )
        val scheduled = assertIs<RecordingScheduler.ScheduleResult.Scheduled>(result)
        assertEquals(RecordingStatus.SCHEDULED, scheduled.recording.status)
        assertEquals(1, repo.listAll().size)
    }

    @Test
    fun `schedule refuses overlap by default`() = runTest {
        val repo = InMemoryRecordingRepository()
        var ids = 0
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "id-${ids++}" })
        sch.schedule(
            playlistId = "p1",
            channelId = "ch-1",
            channelName = "BBC One",
            streamUrl = "http://x/stream",
            programmeTitle = "First",
            programmeDescription = null,
            startMs = now + ms(60),
            endMs = now + ms(3600),
        )
        val overlap = sch.schedule(
            playlistId = "p1",
            channelId = "ch-2",
            channelName = "BBC Two",
            streamUrl = "http://x/stream",
            programmeTitle = "Second",
            programmeDescription = null,
            startMs = now + ms(120),
            endMs = now + ms(3000),
        )
        val conflict = assertIs<RecordingScheduler.ScheduleResult.Conflict>(overlap)
        assertEquals("First", conflict.existing.programmeTitle)
        // Only the first row is persisted.
        assertEquals(1, repo.listAll().size)
    }

    @Test
    fun `schedule with force overrides conflict`() = runTest {
        val repo = InMemoryRecordingRepository()
        var ids = 0
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "id-${ids++}" })
        sch.schedule("p1", "ch-1", "BBC One", "http://x", "First", null, now + ms(60), now + ms(3600))
        val forced = sch.schedule(
            "p1", "ch-2", "BBC Two", "http://x", "Second", null,
            now + ms(120), now + ms(3000),
            force = true,
        )
        assertIs<RecordingScheduler.ScheduleResult.Scheduled>(forced)
        assertEquals(2, repo.listAll().size)
    }

    @Test
    fun `schedule rejects in-the-past windows`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now })
        val past = sch.schedule(
            "p1", "ch-1", "BBC", "http://x", "Yesterday", null,
            now - ms(7200), now - ms(3600),
        )
        assertIs<RecordingScheduler.ScheduleResult.InThePast>(past)
    }

    @Test
    fun `schedule rejects empty stream URL and inverted window`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now })
        val noUrl = sch.schedule(
            "p1", "ch-1", "BBC", "", "Show", null, now + ms(60), now + ms(3600),
        )
        assertIs<RecordingScheduler.ScheduleResult.Invalid>(noUrl)
        val inverted = sch.schedule(
            "p1", "ch-1", "BBC", "http://x", "Show", null, now + ms(3600), now + ms(60),
        )
        assertIs<RecordingScheduler.ScheduleResult.Invalid>(inverted)
    }

    @Test
    fun `markStarted flips to RECORDING`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch-1", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        val started = sch.markStarted("rec-1", "/disk/path.ts")
        assertNotNull(started)
        assertEquals(RecordingStatus.RECORDING, started.status)
        assertEquals("/disk/path.ts", started.filePath)
    }

    @Test
    fun `markStarted is idempotent for already-recording rows`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        val first = sch.markStarted("rec-1", "/a.ts")!!
        val second = sch.markStarted("rec-1", "/b.ts")
        // Already RECORDING — second call returns the existing row,
        // doesn't overwrite the path.
        assertEquals(first.filePath, second?.filePath)
    }

    @Test
    fun `markCompleted closes the row with size`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        sch.markStarted("rec-1", "/p.ts")
        val done = sch.markCompleted("rec-1", fileSizeBytes = 12_345L)
        assertEquals(RecordingStatus.COMPLETED, done?.status)
        assertEquals(12_345L, done?.fileSizeBytes)
    }

    @Test
    fun `markCompleted on cancelled row is a no-op`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        sch.cancel("rec-1")
        val out = sch.markCompleted("rec-1", 1L)
        assertEquals(RecordingStatus.CANCELLED, out?.status)
    }

    @Test
    fun `markFailed records reason and message`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        sch.markStarted("rec-1", "/p.ts")
        val failed = sch.markFailed("rec-1", RecordingFailureReason.NETWORK_ERROR, "stream stalled")
        assertEquals(RecordingStatus.FAILED, failed?.status)
        assertEquals(RecordingFailureReason.NETWORK_ERROR, failed?.failureReason)
        assertEquals("stream stalled", failed?.failureMessage)
    }

    @Test
    fun `cancel after schedule transitions to CANCELLED`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-1" })
        sch.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        val cancelled = sch.cancel("rec-1")
        assertEquals(RecordingStatus.CANCELLED, cancelled?.status)
    }

    @Test
    fun `series passes are disabled until a resolver is wired`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now })
        assertTrue(!sch.seriesPassesEnabled)
        val pass = RecordingSeriesPass(
            id = "pass-1",
            playlistId = "p1",
            channelId = "ch",
            titleMatch = "Sherlock",
            createdAtMs = now,
        )
        val out = sch.scheduleSeriesPass(pass)
        assertIs<RecordingScheduler.ScheduleResult.Invalid>(out)
        assertTrue(out.reason.contains("Series-pass"))
    }

    @Test
    fun conflictsReportsOverlappingPairs() = runTest {
        val repo = InMemoryRecordingRepository()
        var ids = 0
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "id-${ids++}" })
        sch.schedule("p1", "a", "A", "http://x", "T1", null, now + ms(60), now + ms(3600))
        sch.schedule("p1", "b", "B", "http://x", "T2", null, now + ms(120), now + ms(3000), force = true)
        sch.schedule("p1", "c", "C", "http://x", "T3", null, now + ms(7200), now + ms(8000))
        val conflicts = sch.conflicts()
        assertEquals(1, conflicts.size)
    }

    @Test
    fun `non-terminal pair on different playlists do not conflict`() = runTest {
        val repo = InMemoryRecordingRepository()
        var ids = 0
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "id-${ids++}" })
        sch.schedule("p1", "a", "A", "http://x", "T1", null, now + ms(60), now + ms(3600))
        sch.schedule("p2", "b", "B", "http://x", "T2", null, now + ms(120), now + ms(3000))
        assertEquals(0, sch.conflicts().size)
    }

    /** Minimal in-memory repo for the suspend contract. */
    private class InMemoryRecordingRepository : RecordingRepository {
        private val state = MutableStateFlow<List<Recording>>(emptyList())
        override val recordings: StateFlow<List<Recording>> = state.asStateFlow()
        override suspend fun upsert(recording: Recording) {
            state.value = state.value.filterNot { it.id == recording.id } + recording
        }
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
        override suspend fun get(id: String): Recording? = state.value.firstOrNull { it.id == id }
        override suspend fun listByStatus(vararg statuses: RecordingStatus): List<Recording> =
            state.value.filter { it.status in statuses }
        override suspend fun listAll(): List<Recording> = state.value
        override suspend fun clearAll() { state.value = emptyList() }
    }
}
