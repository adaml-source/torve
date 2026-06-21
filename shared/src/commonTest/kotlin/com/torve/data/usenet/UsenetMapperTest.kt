package com.torve.data.usenet

import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidateStates
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.data.usenet.model.UsenetUserMessageKey
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.data.usenet.model.WarmCandidateOutDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UsenetMapperTest {

    // ── Backend state → simplified state collapse ────────────────────────

    @Test
    fun warmResponseMapsReadyPreparingAndUnavailable() {
        val now = 1_000L
        val resp = UsenetWarmResponseDto(
            contentId = "movie:1",
            results = listOf(
                WarmCandidateOutDto(candidateId = "c1", state = "ready"),
                WarmCandidateOutDto(candidateId = "c2", state = "warming", jobId = "j2"),
                WarmCandidateOutDto(candidateId = "c3", state = "failed", failureCode = "release_unavailable"),
            ),
        )
        val merged = UsenetMapper.mergeWarmResponse(emptyMap(), resp, now)
        assertEquals(UsenetAvailability.READY, merged.getValue("c1").availabilityState)
        assertEquals(UsenetAvailability.PREPARING, merged.getValue("c2").availabilityState)
        assertEquals(UsenetAvailability.UNAVAILABLE, merged.getValue("c3").availabilityState)
    }

    @Test
    fun unknownStateDefaultsToPreparing() {
        // The spec vocabulary is ready | warming | failed. Any other state
        // token (future backend rev) defaults to PREPARING — safer than
        // ejecting the user prematurely.
        val now = 1_000L
        val resp = UsenetWarmResponseDto(
            results = listOf(
                WarmCandidateOutDto(candidateId = "c", state = "unexpected_new_phase"),
                WarmCandidateOutDto(candidateId = "c2", state = ""),
            ),
        )
        val merged = UsenetMapper.mergeWarmResponse(emptyMap(), resp, now)
        assertEquals(UsenetAvailability.PREPARING, merged.getValue("c").availabilityState)
        assertEquals(UsenetAvailability.PREPARING, merged.getValue("c2").availabilityState)
    }

    @Test
    fun resolveReadyPopulatesResolvedStreamAndClearsJobId() {
        val now = 1_000L
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "stale-job", now = 0L)
        val resp = UsenetResolveResponseDto(
            state = "ready",
            stream = ResolvedStreamDto(
                url = "https://api.torve.app/handoff/opaque",
                isDirect = true,
                supportsRange = true,
                streamId = "s-1",
            ),
        )
        val updated = UsenetMapper.applyResolveResponse(prior, resp, now)
        assertEquals(UsenetAvailability.READY, updated.availabilityState)
        val stream = assertNotNull(updated.resolvedStream)
        assertEquals("https://api.torve.app/handoff/opaque", stream.url)
        assertTrue(stream.isDirect)
        assertTrue(stream.supportsRange)
        assertEquals("s-1", stream.streamId)
        assertNull(updated.jobId)
        assertTrue(updated.isImmediatelyPlayable)
    }

    @Test
    fun resolveWarmingCapturesJobIdAndKeepsResolvedStreamNull() {
        val now = 1_000L
        val prior = seed("c", UsenetAvailability.UNAVAILABLE, now = 0L)
        val resp = UsenetResolveResponseDto(state = "warming", jobId = "job-42")
        val updated = UsenetMapper.applyResolveResponse(prior, resp, now)
        assertEquals(UsenetAvailability.PREPARING, updated.availabilityState)
        assertEquals("job-42", updated.jobId)
        assertNull(updated.resolvedStream)
    }

    @Test
    fun resolveFailedClearsStaleJobAndStream() {
        val now = 1_000L
        val prior = seed(
            "c",
            UsenetAvailability.READY,
            jobId = "stale",
            resolved = resolved("https://stale"),
            now = 0L,
        )
        val resp = UsenetResolveResponseDto(state = "failed", failureCode = "nzb_unavailable")
        val updated = UsenetMapper.applyResolveResponse(prior, resp, now)
        assertEquals(UsenetAvailability.UNAVAILABLE, updated.availabilityState)
        assertNull(updated.jobId)
        assertNull(updated.resolvedStream)
    }

    @Test
    fun failureCodeOverridesAmbiguousState() {
        val now = 1_000L
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "j", now = 0L)
        val resp = UsenetResolveResponseDto(state = "warming", jobId = "j", failureCode = "bad_input")
        val updated = UsenetMapper.applyResolveResponse(prior, resp, now)
        assertEquals(UsenetAvailability.UNAVAILABLE, updated.availabilityState)
    }

    @Test
    fun jobStatusPollProgressesToReadyWithoutStreamField() {
        // Per the live contract, JobOut has no `stream`. The mapper must
        // preserve prior.resolvedStream (null in warming-then-poll path);
        // the caller is responsible for re-resolving to pick up the handoff.
        val now = 5_000L
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "job-42", now = 1_000L)
        val resp = UsenetJobStatusResponseDto(
            jobId = "job-42",
            contentId = "movie:1",
            state = "ready",
        )
        val updated = UsenetMapper.applyJobStatus(prior, resp, now)
        assertEquals(UsenetAvailability.READY, updated.availabilityState)
        assertNull(updated.resolvedStream, "JobOut carries no stream; mapper must leave resolved stream unset")
        assertNull(updated.jobId)
        assertEquals(now, updated.lastStateChangeAt)
    }

    @Test
    fun jobStatusFailedCollapsesCorrectly() {
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "job-42", now = 1_000L)
        val resp = UsenetJobStatusResponseDto(
            jobId = "job-42",
            contentId = "movie:1",
            state = "failed",
            failureCode = "nzb_unavailable",
        )
        val updated = UsenetMapper.applyJobStatus(prior, resp, now = 2_000L)
        assertEquals(UsenetAvailability.UNAVAILABLE, updated.availabilityState)
        assertNull(updated.jobId)
        assertNull(updated.resolvedStream)
    }

    // ── Stability / identity guarantees ─────────────────────────────────

    @Test
    fun mergeWarmResponsePreservesUnrelatedCandidates() {
        val now = 2_000L
        val existing: UsenetCandidateStates = mapOf(
            "keep" to seed("keep", UsenetAvailability.READY, resolved = resolved("https://keep"), now = 1_000L),
            "touch" to seed("touch", UsenetAvailability.PREPARING, jobId = "old", now = 1_000L),
        )
        val resp = UsenetWarmResponseDto(
            results = listOf(WarmCandidateOutDto(candidateId = "touch", state = "ready")),
        )
        val merged = UsenetMapper.mergeWarmResponse(existing, resp, now)
        assertSame(existing["keep"], merged["keep"])
        assertNotSame(existing["touch"], merged["touch"])
        assertEquals(UsenetAvailability.READY, merged.getValue("touch").availabilityState)
    }

    @Test
    fun lastStateChangeAtOnlyAdvancesOnStateFlip() {
        val now = 5_000L
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "j", now = 1_000L)
        val noFlip = UsenetMapper.applyJobStatus(
            prior = prior,
            response = UsenetJobStatusResponseDto(jobId = "j", contentId = "movie:1", state = "warming"),
            now = now,
        )
        assertEquals(UsenetAvailability.PREPARING, noFlip.availabilityState)
        assertEquals(1_000L, noFlip.lastStateChangeAt)
        val flip = UsenetMapper.applyJobStatus(
            prior = prior,
            response = UsenetJobStatusResponseDto(jobId = "j", contentId = "movie:1", state = "ready"),
            now = now,
        )
        assertEquals(now, flip.lastStateChangeAt)
    }

    @Test
    fun preferredAndSelectedAreStickyAcrossStateFlips() {
        val now = 2_000L
        val prior = seed(
            "c",
            UsenetAvailability.PREPARING,
            jobId = "j",
            now = 1_000L,
        ).copy(isPreferred = true, isSelected = true, title = "My Show", qualityLabel = "1080p")
        val updated = UsenetMapper.applyJobStatus(
            prior = prior,
            response = UsenetJobStatusResponseDto(jobId = "j", contentId = "movie:1", state = "ready"),
            now = now,
        )
        assertTrue(updated.isPreferred)
        assertTrue(updated.isSelected)
        assertEquals("My Show", updated.title)
        assertEquals("1080p", updated.qualityLabel)
    }

    @Test
    fun warmEmptyResponseReturnsSameMapInstance() {
        val existing: UsenetCandidateStates = mapOf(
            "a" to seed("a", UsenetAvailability.PREPARING, now = 1_000L),
        )
        val merged = UsenetMapper.mergeWarmResponse(
            existing,
            UsenetWarmResponseDto(results = emptyList()),
            now = 9_999L,
        )
        assertSame(existing, merged)
    }

    // ── Warm anti-regression (preserved under simplified state model) ───

    @Test
    fun warmDoesNotDemoteReadyToPreparing() {
        val now = 2_000L
        val prior = seed(
            "c",
            UsenetAvailability.READY,
            resolved = resolved("https://handoff"),
            now = 1_000L,
        )
        val resp = UsenetWarmResponseDto(
            results = listOf(WarmCandidateOutDto(candidateId = "c", state = "warming", jobId = "j")),
        )
        val updated = UsenetMapper.mergeWarmResponse(mapOf("c" to prior), resp, now).getValue("c")
        assertEquals(UsenetAvailability.READY, updated.availabilityState)
        assertNotNull(updated.resolvedStream)
        assertEquals(1_000L, updated.lastStateChangeAt)
    }

    @Test
    fun warmDoesDemoteReadyWhenBackendFailsExplicitly() {
        val now = 2_000L
        val prior = seed(
            "c",
            UsenetAvailability.READY,
            resolved = resolved("https://handoff"),
            now = 1_000L,
        )
        val resp = UsenetWarmResponseDto(
            results = listOf(
                WarmCandidateOutDto(
                    candidateId = "c",
                    state = "failed",
                    failureCode = "dead_release",
                ),
            ),
        )
        val updated = UsenetMapper.mergeWarmResponse(mapOf("c" to prior), resp, now).getValue("c")
        assertEquals(UsenetAvailability.UNAVAILABLE, updated.availabilityState)
        assertNull(updated.resolvedStream)
    }

    @Test
    fun warmCanPromotePreparingToReadyWhenBackendSaysReady() {
        val now = 2_000L
        val prior = seed("c", UsenetAvailability.PREPARING, jobId = "j", now = 1_000L)
        val updated = UsenetMapper.mergeWarmResponse(
            existing = mapOf("c" to prior),
            response = UsenetWarmResponseDto(
                results = listOf(WarmCandidateOutDto(candidateId = "c", state = "ready")),
            ),
            now = now,
        ).getValue("c")
        assertEquals(UsenetAvailability.READY, updated.availabilityState)
        assertNull(updated.jobId)
        assertEquals(now, updated.lastStateChangeAt)
    }

    // ── Copy-leak guard (critical) ──────────────────────────────────────

    @Test
    fun stateTokensNeverLeakIntoMessageKey() {
        val now = 1_000L
        // Unknown or internal-vocabulary state tokens must never appear
        // verbatim in a resource key. Terminal tokens ("ready"/"failed")
        // may share word roots with allowed keys — the allowlist is the
        // real guard.
        val internalOnly = listOf("pending", "queued", "scheduled")
        for (tok in internalOnly) {
            val resp = UsenetWarmResponseDto(
                results = listOf(WarmCandidateOutDto(candidateId = "c", state = tok)),
            )
            val row = UsenetMapper.mergeWarmResponse(emptyMap(), resp, now).getValue("c")
            assertTrue(row.displayMessageKey in ALL_ALLOWED_KEYS)
            assertFalse(row.displayMessageKey?.contains(tok) == true)
        }
    }

    @Test
    fun failureCodeNeverLeaksIntoFailureMessageKey() {
        val now = 1_000L
        val opaqueTokens = listOf(
            "release_unavailable",
            "nzb_corrupt",
            "upstream_5xx",
            "bad_input",
            "policy_denied",
        )
        for (token in opaqueTokens) {
            val resp = UsenetResolveResponseDto(state = "failed", failureCode = token)
            val updated = UsenetMapper.applyResolveResponse(
                prior = seed("c", UsenetAvailability.PREPARING, jobId = "j", now = 0L),
                response = resp,
                now = now,
            )
            assertEquals(UsenetAvailability.UNAVAILABLE, updated.availabilityState)
            assertTrue(updated.failureMessageKey in ALL_ALLOWED_KEYS)
            assertFalse(updated.failureMessageKey?.contains(token) == true)
            assertFalse(updated.displayMessageKey?.contains(token) == true)
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private val ALL_ALLOWED_KEYS = setOf(
        UsenetUserMessageKey.READY_NOW,
        UsenetUserMessageKey.PREPARING,
        UsenetUserMessageKey.UNAVAILABLE,
        UsenetUserMessageKey.TRYING_NEXT_SOURCE,
        UsenetUserMessageKey.PLAYBACK_LINK_EXPIRED,
        null,
    )

    private fun seed(
        id: String,
        state: UsenetAvailability,
        jobId: String? = null,
        resolved: UsenetResolvedStream? = null,
        now: Long,
    ) = UsenetCandidateUiModel(
        candidateId = id,
        availabilityState = state,
        jobId = jobId,
        resolvedStream = resolved,
        lastStateChangeAt = now,
    )

    private fun resolved(url: String) = UsenetResolvedStream(url = url)
}
