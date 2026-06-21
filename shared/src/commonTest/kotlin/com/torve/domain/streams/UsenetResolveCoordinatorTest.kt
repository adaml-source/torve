package com.torve.domain.streams

import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetUserMessageKey
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetResolveCoordinatorTest {

    // ── outcomes ────────────────────────────────────────────────────────

    @Test
    fun readyOutcomeCarriesResolvedStream() = runTest {
        val repo = StubUsenetRepository(
            resolveResponse = UsenetResolveResponseDto(
                state = "ready",
                stream = ResolvedStreamDto(
                    url = "https://api.torve.app/handoff/opaque-token",
                    isDirect = true,
                    supportsRange = true,
                    streamId = "s-1",
                ),
            ),
        )
        val coord = UsenetResolveCoordinator(repository = repo, nowMs = { 1_000L })
        val outcome = coord.resolve(
            contentId = "movie:1",
            candidate = payload("c"),
            existingSidecar = emptyMap(),
        )
        val ready = assertIs<ResolveOutcome.Ready>(outcome)
        assertEquals("https://api.torve.app/handoff/opaque-token", ready.stream.url)
        assertTrue(ready.stream.isDirect)
        assertTrue(ready.stream.supportsRange)
        assertEquals(UsenetAvailability.READY, ready.row.availabilityState)
        assertNull(ready.row.jobId)
        // The repo received a full candidate object — not a bare id.
        assertEquals("c", repo.resolveCalls.single().second.candidateId)
        assertEquals("h-c", repo.resolveCalls.single().second.hashKey)
    }

    @Test
    fun warmingOutcomeForwardsJobIdButStartsNoPoller() = runTest {
        val repo = StubUsenetRepository(
            resolveResponse = UsenetResolveResponseDto(state = "warming", jobId = "job-42"),
        )
        val coord = UsenetResolveCoordinator(repo, nowMs = { 1_000L })
        val outcome = coord.resolve("movie:1", payload("c"), emptyMap())
        val warming = assertIs<ResolveOutcome.Warming>(outcome)
        assertEquals("job-42", warming.jobId)
        assertEquals(UsenetAvailability.PREPARING, warming.row.availabilityState)
        assertEquals(UsenetUserMessageKey.PREPARING, warming.row.displayMessageKey)
    }

    @Test
    fun failedOutcomeClearsJobAndStream() = runTest {
        val repo = StubUsenetRepository(
            resolveResponse = UsenetResolveResponseDto(
                state = "failed",
                failureCode = "release_unavailable",
            ),
        )
        val coord = UsenetResolveCoordinator(repo, nowMs = { 1_000L })
        val outcome = coord.resolve("movie:1", payload("c"), emptyMap())
        val failed = assertIs<ResolveOutcome.Failed>(outcome)
        assertEquals(UsenetAvailability.UNAVAILABLE, failed.row.availabilityState)
        assertNull(failed.row.jobId)
        assertNull(failed.row.resolvedStream)
        assertTrue(failed.row.failureMessageKey in allowedKeys)
    }

    @Test
    fun networkFailureMapsToFailedOutcome() = runTest {
        val repo = StubUsenetRepository(resolveThrow = true)
        val coord = UsenetResolveCoordinator(repo, nowMs = { 1_000L })
        val outcome = coord.resolve("movie:1", payload("c"), emptyMap())
        assertIs<ResolveOutcome.Failed>(outcome)
    }

    // ── byte-for-byte URL guarantee ─────────────────────────────────────

    @Test
    fun resolvedUrlIsPreservedByteForByte() = runTest {
        val weirdButExact =
            "https://api.torve.app/handoff/%7B%22tok%22%3A%22a.b.c%22%7D?retry=0&ttl=300#fragment-that-should-stay"
        val repo = StubUsenetRepository(
            resolveResponse = UsenetResolveResponseDto(
                state = "ready",
                stream = ResolvedStreamDto(url = weirdButExact),
            ),
        )
        val coord = UsenetResolveCoordinator(repo, nowMs = { 1_000L })
        val outcome = coord.resolve("movie:1", payload("c"), emptyMap())
        val ready = assertIs<ResolveOutcome.Ready>(outcome)
        assertEquals(weirdButExact.length, ready.stream.url.length)
        for (i in weirdButExact.indices) {
            assertEquals(weirdButExact[i], ready.stream.url[i], "Character at index $i diverged")
        }
    }

    // ── no backend tokens leak into user-facing copy ────────────────────

    @Test
    fun backendFailureCodeNeverLeaksIntoCopy() = runTest {
        val leakyCodes = listOf(
            "release_unavailable",
            "<html>500 Internal</html>",
            "Bearer token missing for upstream",
            "nzb_corrupt:segment-1",
        )
        for (code in leakyCodes) {
            val repo = StubUsenetRepository(
                resolveResponse = UsenetResolveResponseDto(state = "failed", failureCode = code),
            )
            val coord = UsenetResolveCoordinator(repo, nowMs = { 1_000L })
            val failed = assertIs<ResolveOutcome.Failed>(coord.resolve("movie:1", payload("c"), emptyMap()))
            assertTrue(failed.row.displayMessageKey in allowedKeys)
            assertTrue(failed.row.failureMessageKey in allowedKeys)
            assertFalse(failed.row.displayMessageKey?.contains(code) == true)
            assertFalse(failed.row.failureMessageKey?.contains(code) == true)
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun payload(id: String, hash: String = "h-$id") =
        UsenetCandidatePayload(candidateId = id, hashKey = hash)

    private val allowedKeys: Set<String?> = setOf(
        UsenetUserMessageKey.READY_NOW,
        UsenetUserMessageKey.PREPARING,
        UsenetUserMessageKey.UNAVAILABLE,
        UsenetUserMessageKey.TRYING_NEXT_SOURCE,
        UsenetUserMessageKey.PLAYBACK_LINK_EXPIRED,
        null,
    )

    private class StubUsenetRepository(
        private val resolveResponse: UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked"),
        private val resolveThrow: Boolean = false,
    ) : UsenetRepository {
        val resolveCalls = mutableListOf<Pair<String, UsenetCandidateDto>>()

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
            if (resolveThrow) throw RuntimeException("network down")
            resolveCalls += contentId to candidate
            return resolveResponse
        }

        override suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked")

        override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto =
            UsenetJobStatusResponseDto(jobId = jobId, contentId = "x", state = "warming")

        override suspend fun cancelUsenetWarmJobs(
            contentId: String?, candidateId: String?, userSession: String?,
        ): UsenetCancelResponseDto = UsenetCancelResponseDto()

        override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
            ResolvedStreamDto(url = "https://x")
    }
}
