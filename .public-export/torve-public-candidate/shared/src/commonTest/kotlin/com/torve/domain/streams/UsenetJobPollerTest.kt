package com.torve.domain.streams

import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetJobPollerTest {

    @Test
    fun pollReadyTriggersReResolveAndProducesStream() = runTest {
        // The critical P3 path: JobOut doesn't carry a stream. When the
        // poll ticks state=ready, the poller must re-resolve for the
        // same candidate and surface the hot-cache stream on the Ready
        // outcome — byte-for-byte URL preservation intact.
        val exactHandoffUrl = "https://api.torve.app/handoff/opaque-token-XYZ?ttl=300"
        val repo = ProgrammableRepo(
            jobSequence = listOf(
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "warming"),
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "ready"),
            ),
            resolveResponse = UsenetResolveResponseDto(
                state = "ready",
                stream = ResolvedStreamDto(
                    url = exactHandoffUrl,
                    isDirect = true,
                    supportsRange = true,
                    streamId = "s-1",
                ),
            ),
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c", "hash-c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        val ready = assertIs<PollOutcome.Ready>(terminal.await())
        // Byte-for-byte URL preservation through the re-resolve path.
        assertEquals(exactHandoffUrl.length, ready.stream.url.length)
        for (i in exactHandoffUrl.indices) {
            assertEquals(exactHandoffUrl[i], ready.stream.url[i])
        }
        assertTrue(ready.stream.isDirect)
        assertTrue(ready.stream.supportsRange)
        // The poller sent a full candidate (with hash_key) to resolve.
        val resolveCall = assertNotNull(repo.resolveCalls.singleOrNull())
        assertEquals("c", resolveCall.candidateId)
        assertEquals("hash-c", resolveCall.hashKey)
        assertNull(poller.activeJobId())
    }

    @Test
    fun pollReadyButReResolveFailsEmitsFailed() = runTest {
        // Defensive fallback: poll says ready, hot-cache resolve returns
        // failed (rare race — e.g. eviction between tick and resolve).
        // Poller must not synthesize a Ready; VM chain advances.
        val repo = ProgrammableRepo(
            jobSequence = listOf(
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "ready"),
            ),
            resolveResponse = UsenetResolveResponseDto(state = "failed", failureCode = "evicted"),
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        assertIs<PollOutcome.Failed>(terminal.await())
    }

    @Test
    fun pollReadyButReResolveReturnsWarmingEmitsFailed() = runTest {
        // The backend telling us "the job is done" and then responding
        // to /resolve with warming is a contract race. Treat as Failed
        // so the user doesn't get stuck — they can re-tap.
        val repo = ProgrammableRepo(
            jobSequence = listOf(
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "ready"),
            ),
            resolveResponse = UsenetResolveResponseDto(state = "warming", jobId = "j-new"),
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        assertIs<PollOutcome.Failed>(terminal.await())
    }

    @Test
    fun pollReadyButReResolveHasNoStreamEmitsFailed() = runTest {
        val repo = ProgrammableRepo(
            jobSequence = listOf(
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "ready"),
            ),
            resolveResponse = UsenetResolveResponseDto(state = "ready", stream = null),
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        assertIs<PollOutcome.Failed>(terminal.await())
    }

    @Test
    fun failedStopsPolling() = runTest {
        val repo = ProgrammableRepo(
            jobSequence = listOf(
                UsenetJobStatusResponseDto(
                    jobId = "j1",
                    contentId = "movie:1",
                    state = "failed",
                    failureCode = "release_unavailable",
                ),
            ),
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        assertIs<PollOutcome.Failed>(terminal.await())
        assertNull(poller.activeJobId())
        // No re-resolve attempted on failed — the single re-resolve is
        // scoped to the ready path only.
        assertTrue(repo.resolveCalls.isEmpty())
    }

    @Test
    fun timesOutAfterMaxDurationAndMarksUnavailable() = runTest {
        val repo = ProgrammableRepo(
            jobSequence = List(1000) {
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "warming")
            },
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 100L,
            nowMs = { testScheduler.currentTime },
        )
        val terminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !terminal.isCompleted) terminal.complete(o) },
        )
        advanceUntilIdle()
        val timed = assertIs<PollOutcome.TimedOut>(terminal.await())
        assertEquals(UsenetAvailability.UNAVAILABLE, timed.row.availabilityState)
        assertNull(poller.activeJobId())
    }

    @Test
    fun startingAnewJobCancelsThePriorViaCandidateIdCancel() = runTest {
        val slowRepo = ProgrammableRepo(
            jobSequence = List(1000) {
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "warming")
            },
        )
        val poller = UsenetJobPoller(
            repository = slowRepo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val firstTerminal = CompletableDeferred<PollOutcome>()
        val firstJob = poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c1"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c1"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !firstTerminal.isCompleted) firstTerminal.complete(o) },
        )
        advanceTimeBy(50L)
        val secondTerminal = CompletableDeferred<PollOutcome>()
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c2"),
            jobId = "j2",
            scope = this,
            seedRow = seedRow("c2"),
            onOutcome = { o -> if (o !is PollOutcome.StillWarming && !secondTerminal.isCompleted) secondTerminal.complete(o) },
        )
        advanceUntilIdle()
        assertFalse(firstTerminal.isCompleted, "First poll must be pre-empted")
        assertContains(slowRepo.cancelCandidateIds.filterNotNull(), "c1")
        assertTrue(firstJob.isCancelled || firstJob.isCompleted)
    }

    @Test
    fun cancelActiveStopsPollingAndCancelsBackendByCandidateId() = runTest {
        val repo = ProgrammableRepo(
            jobSequence = List(1000) {
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "warming")
            },
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val pollJob = poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { },
        )
        advanceTimeBy(40L)
        poller.cancelActive(cancelOnBackend = true)
        advanceUntilIdle()
        assertTrue(pollJob.isCancelled || pollJob.isCompleted)
        assertNull(poller.activeJobId())
        assertContains(repo.cancelCandidateIds.filterNotNull(), "c")
    }

    @Test
    fun cancelActiveSkipsBackendWhenRequested() = runTest {
        val repo = ProgrammableRepo(
            jobSequence = List(1000) {
                UsenetJobStatusResponseDto(jobId = "j1", contentId = "movie:1", state = "warming")
            },
        )
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        poller.startPolling(
            contentId = "movie:1",
            candidate = payload("c"),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { },
        )
        advanceTimeBy(40L)
        poller.cancelActive(cancelOnBackend = false)
        advanceUntilIdle()
        assertTrue(repo.cancelCandidateIds.isEmpty())
    }

    @Test
    fun blankHashKeyRejectsStartPolling() = runTest {
        // Defensive: a candidate without hash_key can't satisfy the
        // re-resolve step. Don't even start polling — return a
        // no-op placeholder job.
        val repo = ProgrammableRepo(jobSequence = emptyList())
        val poller = UsenetJobPoller(
            repository = repo,
            intervalMs = 10L,
            maxDurationMs = 10_000L,
            nowMs = { testScheduler.currentTime },
        )
        val job = poller.startPolling(
            contentId = "movie:1",
            candidate = UsenetCandidatePayload(candidateId = "c", hashKey = ""),
            jobId = "j1",
            scope = this,
            seedRow = seedRow("c"),
            onOutcome = { },
        )
        advanceUntilIdle()
        assertTrue(job.isCompleted || job.isCancelled)
        assertNull(poller.activeJobId())
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun payload(id: String, hash: String = "h-$id") =
        UsenetCandidatePayload(candidateId = id, hashKey = hash)

    private fun seedRow(id: String) = UsenetCandidateUiModel(
        candidateId = id,
        availabilityState = UsenetAvailability.PREPARING,
        lastStateChangeAt = 0L,
    )

    private class ProgrammableRepo(
        private val jobSequence: List<UsenetJobStatusResponseDto>,
        private val resolveResponse: UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked"),
    ) : UsenetRepository {
        private var idx = 0
        val cancelCandidateIds = mutableListOf<String?>()
        val resolveCalls = mutableListOf<UsenetCandidateDto>()

        override suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto =
            NzbdavTestResponseDto(ok = true)

        override suspend fun saveNzbdavIntegration(
            baseUrl: String, apiKey: String, enabled: Boolean, storageMode: IntegrationStorageMode,
        ): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true)

        override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true)

        override suspend fun deleteNzbdavIntegration() {}

        override suspend fun warmUsenetCandidates(
            contentId: String, candidates: List<UsenetCandidateDto>, topN: Int?,
        ): UsenetWarmResponseDto = UsenetWarmResponseDto()

        override suspend fun resolveUsenetCandidate(
            contentId: String, candidate: UsenetCandidateDto,
        ): UsenetResolveResponseDto {
            resolveCalls += candidate
            return resolveResponse
        }

        override suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked")

        override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto {
            if (jobSequence.isEmpty()) return UsenetJobStatusResponseDto(jobId = jobId, contentId = "x", state = "warming")
            val next = jobSequence.getOrNull(idx) ?: jobSequence.last()
            idx += 1
            return next
        }

        override suspend fun cancelUsenetWarmJobs(
            contentId: String?, candidateId: String?, userSession: String?,
        ): UsenetCancelResponseDto {
            cancelCandidateIds += candidateId
            return UsenetCancelResponseDto(cancelled = if (candidateId != null) 1 else 0)
        }

        override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
            ResolvedStreamDto(url = "https://x")
    }
}
