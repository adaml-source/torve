package com.torve.domain.telemetry

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
import com.torve.domain.streams.UsenetResolveCoordinator
import com.torve.domain.streams.UsenetWarmCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsenetTelemetryTest {

    // ── Leak guard: the whole point of the telemetry layer ──────────────

    @Test
    fun noRawReasonOrUrlLeaksFromWarmPath() = runTest {
        val leakyReason = "upstream_below_version_floor:v0.9"
        val leakyUrl = "https://api.torve.app/handoff/%7B%22tok%22%3A%22abc%22%7D"
        val sink = RecordingEmitter()
        val coord = UsenetWarmCoordinator(
            repository = FakeRepo(
                warmResponse = UsenetWarmResponseDto(
                    contentId = "tv:1:s1:e1",
                    results = listOf(
                        WarmCandidateOutDto(candidateId = "c1", state = "failed", failureCode = leakyReason),
                        WarmCandidateOutDto(candidateId = "c2", state = "ready"),
                    ),
                ),
            ),
            telemetry = sink,
        )
        coord.prewarm(
            contentId = "tv:1:s1:e1",
            candidates = listOf(payload("c1"), payload("c2")),
        )
        sink.assertNoLeakedValue(leakyReason)
        sink.assertNoLeakedValue(leakyUrl)
    }

    @Test
    fun noRawReasonOrUrlLeaksFromResolvePath() = runTest {
        val leakyReason = "nzb_unavailable"
        val leakyUrl = "https://api.torve.app/handoff/opaque-token"
        val sink = RecordingEmitter()
        val coord = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(
                    state = "ready",
                    stream = ResolvedStreamDto(url = leakyUrl),
                ),
            ),
            nowMs = { 0L },
            telemetry = sink,
        )
        coord.resolve(contentId = "tv:1", candidate = payload("c"), existingSidecar = emptyMap())

        val failSink = RecordingEmitter()
        val coord2 = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(state = "failed", failureCode = leakyReason),
            ),
            nowMs = { 0L },
            telemetry = failSink,
        )
        coord2.resolve(contentId = "tv:1", candidate = payload("c"), existingSidecar = emptyMap())

        sink.assertNoLeakedValue(leakyUrl)
        failSink.assertNoLeakedValue(leakyReason)
    }

    // ── Event emission / contract ───────────────────────────────────────

    @Test
    fun warmRequestedCarriesScopeAndCount() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetWarmCoordinator(repository = FakeRepo(), telemetry = sink)
        coord.prewarm(contentId = "tv:1", candidates = listOf(payload("a"), payload("b"), payload("c")))
        val emission = sink.eventsFor("usenet_warm_requested").single()
        assertEquals("tv:1", emission.attrs["content_id"])
        assertEquals("detail", emission.attrs["warm_scope"])
        assertEquals("2", emission.attrs["candidate_count"])
    }

    @Test
    fun sheetWarmRequestedCarriesSheetScope() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetWarmCoordinator(repository = FakeRepo(), telemetry = sink)
        coord.prewarmForSheet(contentId = "tv:1", candidates = listOf(payload("a"), payload("b"), payload("c")))
        val emission = sink.eventsFor("usenet_warm_requested").single()
        assertEquals("sheet", emission.attrs["warm_scope"])
        assertEquals("3", emission.attrs["candidate_count"])
    }

    @Test
    fun candidateReadyFiresOnlyForReadyStateRows() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetWarmCoordinator(
            repository = FakeRepo(
                warmResponse = UsenetWarmResponseDto(
                    contentId = "tv:1",
                    results = listOf(
                        WarmCandidateOutDto(candidateId = "a", state = "ready"),
                        WarmCandidateOutDto(candidateId = "b", state = "warming", jobId = "j"),
                        WarmCandidateOutDto(candidateId = "c", state = "failed", failureCode = "x"),
                    ),
                ),
            ),
            telemetry = sink,
        )
        coord.prewarm(contentId = "tv:1", candidates = listOf(payload("a"), payload("b"), payload("c")))
        val ready = sink.eventsFor("usenet_candidate_ready")
        assertEquals(1, ready.size)
        assertEquals("a", ready.single().attrs["candidate_id"])
    }

    @Test
    fun resolveReadyEmitsReadyEventWithExpectedKeys() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(
                    state = "ready",
                    stream = ResolvedStreamDto(url = "https://h"),
                ),
            ),
            nowMs = { 0L },
            telemetry = sink,
        )
        coord.resolve(contentId = "movie:1", candidate = payload("c"), existingSidecar = emptyMap())
        val ready = sink.eventsFor("usenet_resolve_ready").single()
        assertEquals("movie:1", ready.attrs["content_id"])
        assertEquals("c", ready.attrs["candidate_id"])
        assertEquals("PREPARING", ready.attrs["initial_state"])
        assertEquals("READY", ready.attrs["final_state"])
        assertTrue(ready.attrs["time_to_ready_bucket"]!!.isNotEmpty())
        // `warm_hit` is no longer emitted — backend doesn't expose it.
        assertFalse(ready.attrs.containsKey("warm_hit"))
        sink.assertAllAttributesSafe()
    }

    @Test
    fun resolveWarmingEmitsWarmingEvent() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(state = "warming", jobId = "j"),
            ),
            nowMs = { 0L },
            telemetry = sink,
        )
        coord.resolve(contentId = "movie:1", candidate = payload("c"), existingSidecar = emptyMap())
        val warming = sink.eventsFor("usenet_resolve_warming").single()
        assertEquals("PREPARING", warming.attrs["final_state"])
    }

    @Test
    fun resolveFailedEmitsFailedEvent() = runTest {
        val sink = RecordingEmitter()
        val coord = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(state = "failed", failureCode = "release_unavailable"),
            ),
            nowMs = { 0L },
            telemetry = sink,
        )
        coord.resolve(contentId = "movie:1", candidate = payload("c"), existingSidecar = emptyMap())
        val failed = sink.eventsFor("usenet_resolve_failed").single()
        assertEquals("UNAVAILABLE", failed.attrs["final_state"])
        assertFalse(failed.attrs.values.any { it.contains("release_unavailable") })
    }

    @Test
    fun emitterFailureDoesNotBreakResolveFlow() = runTest {
        val coord = UsenetResolveCoordinator(
            repository = FakeRepo(
                resolveResponse = UsenetResolveResponseDto(
                    state = "ready",
                    stream = ResolvedStreamDto(url = "https://h"),
                ),
            ),
            nowMs = { 0L },
            telemetry = object : TelemetryEmitter {
                override fun emit(event: String, attributes: Map<String, String>) {
                    throw RuntimeException("analytics sink is down")
                }
            },
        )
        val outcome = coord.resolve(
            contentId = "movie:1",
            candidate = payload("c"),
            existingSidecar = emptyMap(),
        )
        assertTrue(outcome is com.torve.domain.streams.ResolveOutcome.Ready)
    }

    // ── Coverage: all 12 event names are referenced somewhere ───────────

    @Test
    fun allAgreedEventNamesExist() {
        // Keeps Prompt 8's event-name list + the constants in sync.
        val expected = setOf(
            "usenet_source_sheet_opened",
            "usenet_warm_requested",
            "usenet_candidate_ready",
            "usenet_source_selected",
            "usenet_resolve_ready",
            "usenet_resolve_warming",
            "usenet_resolve_failed",
            "usenet_playback_started",
            "usenet_playback_failed_early",
            "usenet_fallback_attempted",
            "usenet_fallback_succeeded",
            "usenet_user_abandoned_before_ready",
        )
        val declared = setOf(
            UsenetTelemetryEvents.SOURCE_SHEET_OPENED,
            UsenetTelemetryEvents.WARM_REQUESTED,
            UsenetTelemetryEvents.CANDIDATE_READY,
            UsenetTelemetryEvents.SOURCE_SELECTED,
            UsenetTelemetryEvents.RESOLVE_READY,
            UsenetTelemetryEvents.RESOLVE_WARMING,
            UsenetTelemetryEvents.RESOLVE_FAILED,
            UsenetTelemetryEvents.PLAYBACK_STARTED,
            UsenetTelemetryEvents.PLAYBACK_FAILED_EARLY,
            UsenetTelemetryEvents.FALLBACK_ATTEMPTED,
            UsenetTelemetryEvents.FALLBACK_SUCCEEDED,
            UsenetTelemetryEvents.USER_ABANDONED_BEFORE_READY,
        )
        assertEquals(expected, declared)
    }

    @Test
    fun timeBucketCoversAllRanges() {
        assertEquals("unknown", timeBucket(-1L))
        assertEquals("lt_500ms", timeBucket(0L))
        assertEquals("lt_500ms", timeBucket(499L))
        assertEquals("500_1500ms", timeBucket(500L))
        assertEquals("1500_5000ms", timeBucket(4_999L))
        assertEquals("5000_15000ms", timeBucket(14_999L))
        assertEquals("15000_60000ms", timeBucket(59_999L))
        assertEquals("gte_60000ms", timeBucket(60_000L))
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private class RecordingEmitter : TelemetryEmitter {
        val emissions = mutableListOf<Emission>()
        data class Emission(val name: String, val attrs: Map<String, String>)

        override fun emit(event: String, attributes: Map<String, String>) {
            emissions += Emission(event, attributes)
        }

        fun eventsFor(name: String): List<Emission> = emissions.filter { it.name == name }

        /**
         * Asserts that no emitted attribute value contains [needle].
         * Used to lock in the "no backend tokens / URLs / headers leak
         * into telemetry" guarantee for ad-hoc test fixtures.
         */
        fun assertNoLeakedValue(needle: String) {
            for (e in emissions) {
                for ((k, v) in e.attrs) {
                    assertFalse(
                        v.contains(needle),
                        "Leak detected in event '${e.name}' attribute '$k': '$v' contains '$needle'",
                    )
                }
            }
        }

        /** Keys we never want to see — URLs, headers, secrets. */
        private val forbiddenKeys = setOf("url", "headers", "api_key", "apiKey", "base_url", "baseUrl", "authorization")
        private val forbiddenValueSubstrings = listOf("https://", "http://", "Bearer ", "api_key=", "base_url=")

        fun assertAllAttributesSafe() {
            for (e in emissions) {
                for ((k, v) in e.attrs) {
                    val keyLower = k.lowercase()
                    assertFalse(
                        keyLower in forbiddenKeys,
                        "Forbidden telemetry key '$k' on event '${e.name}'",
                    )
                    for (sub in forbiddenValueSubstrings) {
                        assertFalse(
                            v.contains(sub),
                            "Forbidden substring '$sub' in event '${e.name}' attribute '$k' = '$v'",
                        )
                    }
                }
            }
        }
    }

    private fun payload(id: String, hash: String = "h-$id") =
        UsenetCandidatePayload(candidateId = id, hashKey = hash)

    private class FakeRepo(
        private val warmResponse: UsenetWarmResponseDto = UsenetWarmResponseDto(),
        private val resolveResponse: UsenetResolveResponseDto = UsenetResolveResponseDto(state = "warming"),
    ) : UsenetRepository {
        override suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto =
            NzbdavTestResponseDto(ok = true)

        override suspend fun saveNzbdavIntegration(
            baseUrl: String, apiKey: String, enabled: Boolean, storageMode: IntegrationStorageMode,
        ): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true)

        override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto = NzbdavStatusResponseDto(configured = true)

        override suspend fun deleteNzbdavIntegration() {}

        override suspend fun warmUsenetCandidates(
            contentId: String, candidates: List<UsenetCandidateDto>, topN: Int?,
        ): UsenetWarmResponseDto = warmResponse

        override suspend fun resolveUsenetCandidate(
            contentId: String, candidate: UsenetCandidateDto,
        ): UsenetResolveResponseDto = resolveResponse

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
