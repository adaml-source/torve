package com.torve.presentation.streampicker

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.CandidateProvenance
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks in the per-source fallback ordering Prompt 19 promises:
 *  - Fast Start (high-confidence startup candidates) plays before
 *    anything else, even when a higher-scoring stream exists later.
 *  - Best Fit (score >= 70 OR has a startup-candidate entry) plays
 *    before More Options.
 *  - More Options preserves input order for ties.
 *  - [nextAfter] walks the chain in order and never re-picks a key
 *    that already failed in the session.
 */
class StreamFallbackOrderingTest {

    private fun stream(
        key: String,
        score: Int = 0,
        addonName: String = "Test",
        title: String = key,
    ) = ParsedStream(
        addonName = addonName,
        quality = "1080p",
        title = title,
        score = score,
        // Set directUrl so the default streamUiKey() in androidApp would
        // also resolve to this; tests use the explicit keyOf below so
        // the value here only needs to be unique.
        directUrl = "https://stream/$key",
    )

    private fun fastStartCandidate(streamKey: String) = StartupCandidate(
        streamKey = streamKey,
        title = streamKey,
        qualityLabel = "1080p",
        addonName = "Test",
        readinessState = ReadinessState.READY_NOW,
        provenance = CandidateProvenance(kind = CandidateProvenanceKind.RECENT_SUCCESS),
        confidenceReasons = listOf(StartupConfidenceReasonCode.RECENT_SUCCESS),
    )

    private fun lowConfidenceCandidate(streamKey: String) = StartupCandidate(
        streamKey = streamKey,
        title = streamKey,
        qualityLabel = "1080p",
        addonName = "Test",
        readinessState = ReadinessState.LOOKUP_ONLY,
        provenance = CandidateProvenance(kind = CandidateProvenanceKind.INVENTORY_MATCH),
        // No high-confidence reason - this candidate annotates the row
        // for Best Fit but does NOT promote it to Fast Start.
        confidenceReasons = listOf(StartupConfidenceReasonCode.NO_ACCELERATION_SIGNAL),
    )

    private val keyOf: (ParsedStream) -> String = { it.title }

    @Test
    fun `fast start streams play before higher scoring best fit streams`() {
        val streams = listOf(
            stream("low-score", score = 10),
            stream("high-score-90", score = 90),
            stream("fast-start-row", score = 30),
        )
        val order = StreamFallbackOrdering.streamsInTryOrder(
            streams = streams,
            startupCandidates = listOf(fastStartCandidate("fast-start-row")),
            keyOf = keyOf,
        ).map(keyOf)
        assertEquals(listOf("fast-start-row", "high-score-90", "low-score"), order)
    }

    @Test
    fun `best fit picks score gte 70 even without a startup candidate`() {
        val streams = listOf(
            stream("more-65", score = 65),
            stream("best-70", score = 70),
            stream("best-95", score = 95),
            stream("more-50", score = 50),
        )
        val order = StreamFallbackOrdering.streamsInTryOrder(
            streams = streams,
            keyOf = keyOf,
        ).map(keyOf)
        assertEquals(listOf("best-70", "best-95", "more-65", "more-50"), order)
    }

    @Test
    fun `low confidence candidate row promotes into best fit but not fast start`() {
        val streams = listOf(
            stream("low-score-with-candidate", score = 10),
            stream("score-80", score = 80),
        )
        val order = StreamFallbackOrdering.streamsInTryOrder(
            streams = streams,
            startupCandidates = listOf(lowConfidenceCandidate("low-score-with-candidate")),
            keyOf = keyOf,
        ).map(keyOf)
        // Both belong to Best Fit (input order preserved); neither is
        // Fast Start because the candidate has no high-confidence reason.
        assertEquals(listOf("low-score-with-candidate", "score-80"), order)
    }

    @Test
    fun `more options preserves input order for ties`() {
        val streams = listOf(
            stream("a", score = 10),
            stream("b", score = 10),
            stream("c", score = 10),
        )
        val order = StreamFallbackOrdering.streamsInTryOrder(
            streams = streams,
            keyOf = keyOf,
        ).map(keyOf)
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `empty streams returns empty list`() {
        val order = StreamFallbackOrdering.streamsInTryOrder(
            streams = emptyList(),
            keyOf = keyOf,
        )
        assertEquals(emptyList(), order)
    }

    @Test
    fun `nextAfter returns the stream immediately after the failed one`() {
        val tryOrder = listOf(
            stream("a", score = 90),
            stream("b", score = 80),
            stream("c", score = 50),
        )
        val next = StreamFallbackOrdering.nextAfter(
            tryOrder = tryOrder,
            failedKey = "a",
            keyOf = keyOf,
        )
        assertEquals("b", next?.let(keyOf))
    }

    @Test
    fun `nextAfter skips streams already tried earlier in the session`() {
        val tryOrder = listOf(
            stream("a", score = 90),
            stream("b", score = 80),
            stream("c", score = 50),
        )
        val next = StreamFallbackOrdering.nextAfter(
            tryOrder = tryOrder,
            failedKey = "b",
            keyOf = keyOf,
            // The user already tried 'c' earlier and bounced back to
            // 'b'; do not re-pick it.
            alreadyTried = setOf("c"),
        )
        assertNull(next)
    }

    @Test
    fun `nextAfter returns null when there is nothing left in the chain`() {
        val tryOrder = listOf(stream("only", score = 90))
        val next = StreamFallbackOrdering.nextAfter(
            tryOrder = tryOrder,
            failedKey = "only",
            keyOf = keyOf,
        )
        assertNull(next)
    }

    @Test
    fun `nextAfter falls back to the start when failed key is not in the order`() {
        val tryOrder = listOf(
            stream("a", score = 90),
            stream("b", score = 80),
        )
        val next = StreamFallbackOrdering.nextAfter(
            tryOrder = tryOrder,
            failedKey = "stale-key-not-in-list",
            keyOf = keyOf,
        )
        // Defensive: if the caller passes a stale key (e.g. order was
        // rebuilt mid-session), return the first untried entry rather
        // than null.
        assertEquals("a", next?.let(keyOf))
    }
}
