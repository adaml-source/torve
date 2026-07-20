package com.torve.domain.streams

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.data.usenet.model.WarmCandidateOutDto
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.CandidateProvenanceKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsenetWarmCoordinatorTest {

    // ── prewarm dedupe ──────────────────────────────────────────────────

    @Test
    fun identicalPrewarmDedupes() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        val candidates = listOf(payload("c1", "h1"), payload("c2", "h2"))
        coord.prewarm("tv:1:s1:e1", candidates)
        coord.prewarm("tv:1:s1:e1", candidates)
        coord.prewarm("tv:1:s1:e1", candidates)
        assertEquals(1, repo.warmCalls.size)
        assertEquals(listOf("c1", "c2"), repo.warmCalls.single().second.map { it.candidateId })
    }

    @Test
    fun differentCandidateSetFiresAgain() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.prewarm("tv:1", listOf(payload("c1"), payload("c2")))
        assertEquals(2, repo.warmCalls.size)
    }

    @Test
    fun differentContentIdFiresAgain() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.prewarm("tv:2", listOf(payload("c1")))
        assertEquals(2, repo.warmCalls.size)
    }

    @Test
    fun emptyCandidateListIsNoOp() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", emptyList())
        assertTrue(repo.warmCalls.isEmpty())
        assertNull(coord.currentKeyForTest())
    }

    @Test
    fun blankFieldCandidatesAreFilteredOut() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        // Candidates lacking candidate_id or hash_key cannot be sent to
        // the backend — the coordinator must drop them rather than send
        // a malformed body.
        coord.prewarm(
            "tv:1",
            listOf(
                UsenetCandidatePayload(candidateId = "", hashKey = "h"),
                UsenetCandidatePayload(candidateId = "c", hashKey = ""),
            ),
        )
        assertTrue(repo.warmCalls.isEmpty())
    }

    @Test
    fun blankContentIdIsNoOp() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("", listOf(payload("c1")))
        coord.prewarm("   ", listOf(payload("c1")))
        assertTrue(repo.warmCalls.isEmpty())
    }

    @Test
    fun trimsToMaxPrewarmCandidates() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf("a", "b", "c", "d", "e").map { payload(it) })
        assertEquals(1, repo.warmCalls.size)
        val sent = repo.warmCalls.single().second
        assertEquals(UsenetWarmCoordinator.MAX_PREWARM_CANDIDATES, sent.size)
        assertEquals(listOf("a", "b"), sent.map { it.candidateId })
    }

    @Test
    fun duplicateCandidateIdsCollapsed() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("a"), payload("a"), payload("a"), payload("b")))
        val sent = repo.warmCalls.single().second
        assertEquals(listOf("a", "b"), sent.map { it.candidateId })
    }

    @Test
    fun repositoryFailureDoesNotThrowButStillDedupes() = runTest {
        val repo = ThrowingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.prewarm("tv:1", listOf(payload("c1")))
        assertEquals(1, repo.warmCalls)
    }

    // ── prewarmForSheet ─────────────────────────────────────────────────

    @Test
    fun sheetWarmUsesDistinctDedupeFromPrewarm() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        val candidates = listOf(payload("c1"), payload("c2"))
        coord.prewarm("tv:1", candidates)
        assertEquals(1, repo.warmCalls.size)
        coord.prewarmForSheet("tv:1", candidates)
        assertEquals(2, repo.warmCalls.size)
    }

    @Test
    fun sheetWarmTrimsToMaxSheetCandidates() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarmForSheet(
            "tv:1",
            listOf("a", "b", "c", "d", "e", "f", "g").map { payload(it) },
        )
        val sent = repo.warmCalls.single().second
        assertEquals(UsenetWarmCoordinator.MAX_SHEET_WARM_CANDIDATES, sent.size)
        assertEquals(listOf("a", "b", "c", "d", "e"), sent.map { it.candidateId })
    }

    @Test
    fun sheetWarmIdenticalCallDedupes() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarmForSheet("tv:1", listOf(payload("a"), payload("b"), payload("c")))
        coord.prewarmForSheet("tv:1", listOf(payload("a"), payload("b"), payload("c")))
        assertEquals(1, repo.warmCalls.size)
    }

    @Test
    fun sheetWarmEmptyListIsNoOp() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarmForSheet("tv:1", emptyList())
        coord.prewarmForSheet("", listOf(payload("a")))
        assertTrue(repo.warmCalls.isEmpty())
        assertNull(coord.currentSheetKeyForTest())
    }

    @Test
    fun sheetWarmLastResponseExposed() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        assertNull(coord.lastSheetWarmResponse())
        coord.prewarmForSheet("tv:1", listOf(payload("a"), payload("b")))
        val resp = assertNotNull(coord.lastSheetWarmResponse())
        assertEquals(2, resp.results.size)
    }

    // ── clearForContent ─────────────────────────────────────────────────

    @Test
    fun clearForContentAlsoClearsSheetDedupe() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.prewarmForSheet("tv:1", listOf(payload("c1"), payload("c2"), payload("c3")))
        assertEquals(2, repo.warmCalls.size)
        coord.clearForContent("tv:1")
        assertNull(coord.currentKeyForTest())
        assertNull(coord.currentSheetKeyForTest())
        coord.prewarmForSheet("tv:1", listOf(payload("c1"), payload("c2"), payload("c3")))
        assertEquals(3, repo.warmCalls.size)
    }

    @Test
    fun clearForContentResetsDedupeAndCancelsBackend() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        assertEquals(1, repo.warmCalls.size)
        coord.clearForContent("tv:1")
        assertNull(coord.currentKeyForTest())
        assertContains(repo.cancelContentIds, "tv:1")
        // Post-clear, the same prewarm can re-fire.
        coord.prewarm("tv:1", listOf(payload("c1")))
        assertEquals(2, repo.warmCalls.size)
    }

    @Test
    fun clearForDifferentContentKeepsCurrentEntry() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.clearForContent("tv:999")
        val key = assertNotNull(coord.currentKeyForTest())
        assertEquals("tv:1", key.contentId)
    }

    @Test
    fun clearForBlankContentIsNoOp() = runTest {
        val repo = RecordingUsenetRepository()
        val coord = UsenetWarmCoordinator(repo)
        coord.prewarm("tv:1", listOf(payload("c1")))
        coord.clearForContent("")
        assertNotNull(coord.currentKeyForTest())
        assertTrue(repo.cancelContentIds.isEmpty())
    }

    // ── ParsedStream adapter ────────────────────────────────────────────

    @Test
    fun usenetCandidateIdOrNullReturnsIdForNzbdavRow() {
        val stream = fakeStream(
            provenance = CandidateProvenanceKind.USENET_NZBDAV,
            sourceKey = "nzbdav-candidate-42",
        )
        assertEquals("nzbdav-candidate-42", stream.usenetCandidateIdOrNull())
    }

    @Test
    fun usenetCandidateIdOrNullReturnsNullForOtherProvenance() {
        val nonUsenet = listOf(
            CandidateProvenanceKind.STARTUP_FETCH,
            CandidateProvenanceKind.RECENT_SUCCESS,
            CandidateProvenanceKind.INVENTORY_MATCH,
            CandidateProvenanceKind.HASH_AVAILABILITY,
            CandidateProvenanceKind.LOCAL_MEMORY,
            CandidateProvenanceKind.UNKNOWN,
            null,
        )
        for (kind in nonUsenet) {
            val stream = fakeStream(provenance = kind, sourceKey = "x")
            assertNull(stream.usenetCandidateIdOrNull(), "Expected null for provenance $kind")
        }
    }

    @Test
    fun usenetCandidateIdOrNullReturnsNullWhenSourceKeyBlank() {
        val blank = fakeStream(
            provenance = CandidateProvenanceKind.USENET_NZBDAV,
            sourceKey = "   ",
        )
        assertNull(blank.usenetCandidateIdOrNull())
        val missing = fakeStream(
            provenance = CandidateProvenanceKind.USENET_NZBDAV,
            sourceKey = null,
        )
        assertNull(missing.usenetCandidateIdOrNull())
    }

    // ── content-id formatter ────────────────────────────────────────────

    @Test
    fun formatUsenetContentIdMovie() {
        assertEquals("movie:123", formatUsenetContentId("movie", 123))
        assertEquals("tv:7", formatUsenetContentId("TV", 7))
    }

    @Test
    fun formatUsenetContentIdEpisode() {
        assertEquals("tv:1:s1:e4", formatUsenetContentId("tv", 1, season = 1, episode = 4))
    }

    @Test
    fun formatUsenetContentIdMissingTmdbIsNull() {
        assertNull(formatUsenetContentId("tv", null))
    }

    @Test
    fun formatUsenetContentIdBlankTypeIsNull() {
        assertNull(formatUsenetContentId("", 1))
        assertNull(formatUsenetContentId("   ", 1))
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun payload(id: String, hash: String = "h-$id") =
        UsenetCandidatePayload(candidateId = id, hashKey = hash)

    private fun fakeStream(
        provenance: CandidateProvenanceKind?,
        sourceKey: String?,
    ) = ParsedStream(
        addonName = "test",
        quality = "1080p",
        title = "title",
        accelerationSourceKey = sourceKey,
        accelerationProvenanceKind = provenance,
    )

    private class RecordingUsenetRepository : UsenetRepository {
        val warmCalls = mutableListOf<Pair<String, List<UsenetCandidateDto>>>()
        val cancelContentIds = mutableListOf<String?>()
        val cancelCandidateIds = mutableListOf<String?>()

        override suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto =
            NzbdavTestResponseDto(ok = true)

        override suspend fun saveNzbdavIntegration(
            baseUrl: String, apiKey: String, enabled: Boolean, storageMode: IntegrationStorageMode,
        ): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true, isEnabled = enabled)

        override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto =
            NzbdavStatusResponseDto(configured = true)

        override suspend fun deleteNzbdavIntegration() {}

        override suspend fun warmUsenetCandidates(
            contentId: String, candidates: List<UsenetCandidateDto>, topN: Int?,
        ): UsenetWarmResponseDto {
            warmCalls += contentId to candidates
            return UsenetWarmResponseDto(
                contentId = contentId,
                results = candidates.map { WarmCandidateOutDto(candidateId = it.candidateId, state = "warming") },
            )
        }

        override suspend fun resolveUsenetCandidate(
            contentId: String, candidate: UsenetCandidateDto,
        ): UsenetResolveResponseDto = UsenetResolveResponseDto(state = "warming", jobId = "j")

        override suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked")

        override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto =
            UsenetJobStatusResponseDto(jobId = jobId, contentId = "x", state = "warming")

        override suspend fun cancelUsenetWarmJobs(
            contentId: String?, candidateId: String?, userSession: String?,
        ): UsenetCancelResponseDto {
            cancelContentIds += contentId
            cancelCandidateIds += candidateId
            return UsenetCancelResponseDto(cancelled = 0)
        }

        override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
            ResolvedStreamDto(url = "https://example/$token")
    }

    private class ThrowingUsenetRepository : UsenetRepository {
        var warmCalls: Int = 0; private set

        override suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto =
            NzbdavTestResponseDto(ok = true)

        override suspend fun saveNzbdavIntegration(
            baseUrl: String, apiKey: String, enabled: Boolean, storageMode: IntegrationStorageMode,
        ): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true)

        override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto =
            NzbdavStatusResponseDto(configured = true)

        override suspend fun deleteNzbdavIntegration() {}

        override suspend fun warmUsenetCandidates(
            contentId: String, candidates: List<UsenetCandidateDto>, topN: Int?,
        ): UsenetWarmResponseDto {
            warmCalls += 1
            throw RuntimeException("network down")
        }

        override suspend fun resolveUsenetCandidate(
            contentId: String, candidate: UsenetCandidateDto,
        ): UsenetResolveResponseDto = UsenetResolveResponseDto(state = "warming")

        override suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto =
            UsenetResolveResponseDto(state = "failed", failureCode = "unmocked")

        override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto =
            UsenetJobStatusResponseDto(jobId = jobId, contentId = "x", state = "warming")

        override suspend fun cancelUsenetWarmJobs(
            contentId: String?, candidateId: String?, userSession: String?,
        ): UsenetCancelResponseDto = UsenetCancelResponseDto(cancelled = 0)

        override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
            ResolvedStreamDto(url = "https://x")
    }
}
