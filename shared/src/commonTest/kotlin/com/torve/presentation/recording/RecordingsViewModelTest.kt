package com.torve.presentation.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingRepository
import com.torve.domain.recording.RecordingScheduler
import com.torve.domain.recording.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingsViewModelTest {

    private val now = 1_700_000_000_000L
    private fun ms(seconds: Int) = seconds * 1000L

    private fun fixture(): Triple<InMemoryRecordingRepository, RecordingScheduler, RecordingsViewModel> {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-${repo.listAllSync().size}" })
        val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return Triple(repo, sch, RecordingsViewModel(sch, repo, scope = testScope))
    }

    @Test
    fun `schedule produces an active row`() = runTest {
        val (_, _, vm) = fixture()
        vm.schedule(
            playlistId = "p1",
            channelId = "ch",
            channelName = "BBC One",
            streamUrl = "http://x/stream",
            programmeTitle = "Sherlock",
            programmeDescription = null,
            startMs = now + ms(60),
            endMs = now + ms(3600),
        )
        val state = vm.state.first { it.active.isNotEmpty() }
        assertEquals(1, state.active.size)
        assertEquals(RecordingStatus.SCHEDULED, state.active.single().status)
        assertNull(state.conflict)
    }

    @Test
    fun `overlapping schedule parks a conflict instead of creating two rows`() = runTest {
        val (_, _, vm) = fixture()
        vm.schedule("p1", "a", "BBC One", "http://x", "First", null, now + ms(60), now + ms(3600))
        // Wait for first scheduled.
        vm.state.first { it.active.size == 1 }
        vm.schedule("p1", "b", "BBC Two", "http://x", "Second", null, now + ms(120), now + ms(3000))
        val withConflict = vm.state.first { it.conflict != null }
        assertEquals(1, withConflict.active.size, "second schedule must NOT create a row")
        assertNotNull(withConflict.conflict)
        assertEquals("Second", withConflict.conflict.candidate.programmeTitle)
        assertEquals("First", withConflict.conflict.existing.programmeTitle)
    }

    @Test
    fun `confirmConflict re-schedules with force and clears the prompt`() = runTest {
        val (_, _, vm) = fixture()
        vm.schedule("p1", "a", "BBC One", "http://x", "First", null, now + ms(60), now + ms(3600))
        vm.state.first { it.active.size == 1 }
        vm.schedule("p1", "b", "BBC Two", "http://x", "Second", null, now + ms(120), now + ms(3000))
        vm.state.first { it.conflict != null }
        vm.confirmConflict()
        val cleared = vm.state.first { it.conflict == null && it.active.size == 2 }
        assertEquals(2, cleared.active.size)
    }

    @Test
    fun `dismissConflict drops the prompt without re-scheduling`() = runTest {
        val (_, _, vm) = fixture()
        vm.schedule("p1", "a", "BBC One", "http://x", "First", null, now + ms(60), now + ms(3600))
        vm.state.first { it.active.size == 1 }
        vm.schedule("p1", "b", "BBC Two", "http://x", "Second", null, now + ms(120), now + ms(3000))
        vm.state.first { it.conflict != null }
        vm.dismissConflict()
        val cleared = vm.state.first { it.conflict == null }
        assertEquals(1, cleared.active.size)
    }

    @Test
    fun `statusFor returns SCHEDULED for a known scheduled slot`() = runTest {
        val (_, _, vm) = fixture()
        vm.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        vm.state.first { it.active.isNotEmpty() }
        val status = vm.statusFor("http://x", now + ms(60), now + ms(3600))
        assertEquals(RecordingSlotStatus.SCHEDULED, status)
    }

    @Test
    fun `statusFor returns NONE when no row matches`() = runTest {
        val (_, _, vm) = fixture()
        assertEquals(RecordingSlotStatus.NONE, vm.statusFor("http://x", 0L, 1L))
    }

    @Test
    fun `cancel transitions a scheduled row to CANCELLED`() = runTest {
        val (repo, _, vm) = fixture()
        vm.schedule("p1", "ch", "BBC", "http://x", "S", null, now + ms(60), now + ms(3600))
        val first = vm.state.first { it.active.isNotEmpty() }.active.single()
        vm.cancel(first.id)
        val terminal = vm.state.first { it.failed.isNotEmpty() }
        assertTrue(terminal.failed.any { it.id == first.id && it.status == RecordingStatus.CANCELLED })
        assertEquals(0, terminal.active.size)
    }

    @Test
    fun `cancel on active recording delegates to starter stop instead of marking cancelled`() = runTest {
        val repo = InMemoryRecordingRepository()
        val sch = RecordingScheduler(repo, nowMs = { now }, newId = { "rec-active" })
        var stopCalls = 0
        val starter = object : RecordingStarter {
            override suspend fun stopRunning(id: String): Boolean {
                stopCalls++
                return true
            }
        }
        val vm = RecordingsViewModel(
            scheduler = sch,
            repository = repo,
            starter = starter,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )
        repo.upsert(
            Recording(
                id = "rec-active",
                playlistId = "p1",
                channelId = "ch",
                channelName = "BBC",
                streamUrl = "http://x",
                programmeTitle = "Live",
                startMs = now,
                endMs = now + ms(3600),
                status = RecordingStatus.RECORDING,
                filePath = "/recordings/live.ts",
                createdAtMs = now,
                startedAtMs = now,
            ),
        )

        vm.cancel("rec-active")

        assertEquals(1, stopCalls)
        assertEquals(RecordingStatus.RECORDING, repo.get("rec-active")?.status)
    }

    @Test
    fun `completed bucket sorts most-recent first`() = runTest {
        val (repo, sch, vm) = fixture()
        // Two rows that already completed, with distinct completedAtMs.
        repo.upsert(
            Recording(
                id = "older",
                playlistId = "p1", channelId = "a", channelName = "A",
                streamUrl = "http://x", programmeTitle = "Old",
                startMs = 0L, endMs = 1L,
                status = RecordingStatus.COMPLETED,
                completedAtMs = 100L,
                createdAtMs = 0L,
            ),
        )
        repo.upsert(
            Recording(
                id = "newer",
                playlistId = "p1", channelId = "a", channelName = "A",
                streamUrl = "http://x", programmeTitle = "New",
                startMs = 0L, endMs = 1L,
                status = RecordingStatus.COMPLETED,
                completedAtMs = 200L,
                createdAtMs = 0L,
            ),
        )
        val state = vm.state.first { it.completed.size == 2 }
        assertEquals("newer", state.completed.first().id)
    }

    /** Same in-memory repo used by the scheduler tests, with a sync helper. */
    private class InMemoryRecordingRepository : RecordingRepository {
        private val flow = MutableStateFlow<List<Recording>>(emptyList())
        override val recordings: StateFlow<List<Recording>> = flow.asStateFlow()
        override suspend fun upsert(recording: Recording) {
            flow.value = flow.value.filterNot { it.id == recording.id } + recording
        }
        override suspend fun delete(id: String) {
            flow.value = flow.value.filterNot { it.id == id }
        }
        override suspend fun get(id: String): Recording? = flow.value.firstOrNull { it.id == id }
        override suspend fun listByStatus(vararg statuses: RecordingStatus): List<Recording> =
            flow.value.filter { it.status in statuses }
        override suspend fun listAll(): List<Recording> = flow.value
        override suspend fun clearAll() { flow.value = emptyList() }
        fun listAllSync(): List<Recording> = flow.value
    }
}
