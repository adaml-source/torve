package com.torve.data.usenet

import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format contract tests for the backend ↔ app boundary. Aligned to
 * the live contract after the contract-fix sprint: `state`, `failure_code`,
 * `stream`, `results`, and full `UsenetCandidate` payloads. No app-side
 * consumer reads `backend_phase`, `failure_reason`, `resolved`,
 * `warm_hit`, `headers`, or `expires_at_ms` anymore.
 */
class UsenetApiContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── Integrations ────────────────────────────────────────────────────

    @Test
    fun nzbdavTestResponseDecodesOk() {
        val dto = json.decodeFromString<NzbdavTestResponseDto>(
            """
            { "ok": true, "degraded": false, "reason": null }
            """.trimIndent(),
        )
        assertTrue(dto.ok)
        assertFalse(dto.degraded)
        assertNull(dto.reason)
    }

    @Test
    fun nzbdavTestResponseDecodesDegraded() {
        val dto = json.decodeFromString<NzbdavTestResponseDto>(
            """
            { "ok": true, "degraded": true, "reason": "upstream_below_version_floor" }
            """.trimIndent(),
        )
        assertTrue(dto.ok)
        assertTrue(dto.degraded)
        assertEquals("upstream_below_version_floor", dto.reason)
    }

    @Test
    fun nzbdavStatusResponseDecodesNotConfigured() {
        val dto = json.decodeFromString<NzbdavStatusResponseDto>(
            """
            { "configured": false, "degraded": false }
            """.trimIndent(),
        )
        assertFalse(dto.configured)
        assertFalse(dto.degraded)
        assertNull(dto.isEnabled)
    }

    @Test
    fun nzbdavStatusResponseDecodesConnected() {
        val dto = json.decodeFromString<NzbdavStatusResponseDto>(
            """
            {
              "configured": true,
              "is_enabled": true,
              "last_tested_at": "2026-04-22T10:00:00Z",
              "last_healthy_at": "2026-04-22T10:00:00Z",
              "degraded": false,
              "reason": null
            }
            """.trimIndent(),
        )
        assertTrue(dto.configured)
        assertEquals(true, dto.isEnabled)
        assertFalse(dto.degraded)
    }

    @Test
    fun nzbdavStatusResponseDecodesDegraded() {
        val dto = json.decodeFromString<NzbdavStatusResponseDto>(
            """
            {
              "configured": true,
              "is_enabled": true,
              "degraded": true,
              "reason": "upstream_below_version_floor"
            }
            """.trimIndent(),
        )
        assertTrue(dto.degraded)
        assertEquals("upstream_below_version_floor", dto.reason)
    }

    // ── UsenetCandidate (shared body shape) ─────────────────────────────

    @Test
    fun candidateDecodesAllThreeFields() {
        val dto = json.decodeFromString<UsenetCandidateDto>(
            """
            {
              "candidate_id": "c1",
              "hash_key": "abc123",
              "nzb_url": "https://nzbdav.example.com/nzb/c1.nzb"
            }
            """.trimIndent(),
        )
        assertEquals("c1", dto.candidateId)
        assertEquals("abc123", dto.hashKey)
        assertEquals("https://nzbdav.example.com/nzb/c1.nzb", dto.nzbUrl)
    }

    @Test
    fun candidateDecodesWithoutOptionalNzbUrl() {
        val dto = json.decodeFromString<UsenetCandidateDto>(
            """
            { "candidate_id": "c2", "hash_key": "deadbeef" }
            """.trimIndent(),
        )
        assertEquals("c2", dto.candidateId)
        assertEquals("deadbeef", dto.hashKey)
        assertNull(dto.nzbUrl)
    }

    // ── Warm ────────────────────────────────────────────────────────────

    @Test
    fun warmResponseDecodesResultsArray() {
        val dto = json.decodeFromString<UsenetWarmResponseDto>(
            """
            {
              "content_id": "tv:1:s1:e1",
              "results": [
                { "candidate_id": "c1", "state": "ready" },
                { "candidate_id": "c2", "state": "warming", "job_id": "job-7" },
                { "candidate_id": "c3", "state": "failed", "failure_code": "release_unavailable" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("tv:1:s1:e1", dto.contentId)
        assertEquals(3, dto.results.size)
        assertEquals("ready", dto.results[0].state)
        assertEquals("warming", dto.results[1].state)
        assertEquals("job-7", dto.results[1].jobId)
        assertEquals("failed", dto.results[2].state)
        assertEquals("release_unavailable", dto.results[2].failureCode)
    }

    @Test
    fun warmResponseCandidatesCarryFallbackSuggestions() {
        val dto = json.decodeFromString<UsenetWarmResponseDto>(
            """
            {
              "content_id": "movie:1",
              "results": [
                {
                  "candidate_id": "c1",
                  "state": "failed",
                  "failure_code": "nzb_corrupt",
                  "fallback_suggestions": [
                    { "candidate_id": "c2", "hash_key": "h2" },
                    { "candidate_id": "c3", "hash_key": "h3", "nzb_url": "https://x" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val first = dto.results.single()
        assertEquals(2, first.fallbackSuggestions.size)
        assertEquals("c2", first.fallbackSuggestions[0].candidateId)
        assertEquals("h3", first.fallbackSuggestions[1].hashKey)
    }

    // ── Resolve ─────────────────────────────────────────────────────────

    @Test
    fun resolveResponseDecodesReady() {
        val dto = json.decodeFromString<UsenetResolveResponseDto>(
            """
            {
              "state": "ready",
              "stream": {
                "url": "https://api.torve.app/handoff/opaque-token",
                "is_direct": true,
                "supports_range": true,
                "stream_id": "stream-42"
              },
              "fallback_suggestions": []
            }
            """.trimIndent(),
        )
        assertEquals("ready", dto.state)
        val stream = assertNotNull(dto.stream)
        assertEquals("https://api.torve.app/handoff/opaque-token", stream.url)
        assertTrue(stream.isDirect)
        assertTrue(stream.supportsRange)
        assertEquals("stream-42", stream.streamId)
    }

    @Test
    fun resolveResponseDecodesWarming() {
        val dto = json.decodeFromString<UsenetResolveResponseDto>(
            """
            { "state": "warming", "job_id": "job-42", "fallback_suggestions": [] }
            """.trimIndent(),
        )
        assertEquals("warming", dto.state)
        assertEquals("job-42", dto.jobId)
        assertNull(dto.stream)
        assertNull(dto.failureCode)
    }

    @Test
    fun resolveResponseDecodesFailedWithSuggestions() {
        val dto = json.decodeFromString<UsenetResolveResponseDto>(
            """
            {
              "state": "failed",
              "failure_code": "nzb_unavailable",
              "fallback_suggestions": [
                { "candidate_id": "c-alt", "hash_key": "h-alt" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("failed", dto.state)
        assertEquals("nzb_unavailable", dto.failureCode)
        assertNull(dto.stream)
        assertEquals(1, dto.fallbackSuggestions.size)
        assertEquals("c-alt", dto.fallbackSuggestions[0].candidateId)
    }

    // ── Job status ──────────────────────────────────────────────────────

    @Test
    fun jobStatusDecodesReadyWithoutStreamField() {
        // JobOut per the live contract does NOT include `stream`. A caller
        // seeing state=ready must re-call /resolve to pick up the handoff.
        val dto = json.decodeFromString<UsenetJobStatusResponseDto>(
            """
            {
              "job_id": "job-42",
              "content_id": "movie:1",
              "state": "ready",
              "fallback_suggestions": []
            }
            """.trimIndent(),
        )
        assertEquals("job-42", dto.jobId)
        assertEquals("movie:1", dto.contentId)
        assertEquals("ready", dto.state)
        assertNull(dto.failureCode)
    }

    @Test
    fun jobStatusDecodesFailedWithFallbacks() {
        val dto = json.decodeFromString<UsenetJobStatusResponseDto>(
            """
            {
              "job_id": "job-42",
              "content_id": "movie:1",
              "state": "failed",
              "failure_code": "nzb_corrupt",
              "fallback_suggestions": [
                { "candidate_id": "c-alt", "hash_key": "h-alt" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("failed", dto.state)
        assertEquals("nzb_corrupt", dto.failureCode)
        assertEquals(1, dto.fallbackSuggestions.size)
    }
}
