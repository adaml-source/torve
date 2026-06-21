package com.torve.domain.player

import com.torve.domain.model.CandidateProvenance
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPlaybackPolicyTest {

    @Test
    fun readyNow_candidate_is_high_confidence() {
        assertTrue(
            StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(
                candidate(readinessState = ReadinessState.READY_NOW),
            ),
        )
    }

    @Test
    fun cached_or_recent_success_candidate_is_high_confidence() {
        assertTrue(
            StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(
                candidate(
                    readinessState = ReadinessState.READY_WITH_RESOLVE,
                    confidenceReasons = listOf(StartupConfidenceReasonCode.HASH_CACHED),
                    isKnownCached = true,
                ),
            ),
        )
        assertTrue(
            StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(
                candidate(
                    readinessState = ReadinessState.READY_WITH_RESOLVE,
                    confidenceReasons = listOf(StartupConfidenceReasonCode.RECENT_SUCCESS),
                ),
            ),
        )
    }

    @Test
    fun lookup_only_candidate_without_signal_is_not_high_confidence() {
        assertFalse(
            StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(
                candidate(
                    readinessState = ReadinessState.LOOKUP_ONLY,
                    confidenceReasons = listOf(
                        StartupConfidenceReasonCode.EXACT_CONTENT_MATCH,
                        StartupConfidenceReasonCode.PROVIDER_SIGNAL,
                    ),
                ),
            ),
        )
    }

    @Test
    fun highConfidenceCandidateKeys_preserves_only_autoplay_safe_candidates() {
        val candidates = listOf(
            candidate(streamKey = "a", readinessState = ReadinessState.READY_NOW),
            candidate(
                streamKey = "b",
                readinessState = ReadinessState.READY_WITH_RESOLVE,
                confidenceReasons = listOf(StartupConfidenceReasonCode.RECENT_SUCCESS),
            ),
            candidate(
                streamKey = "c",
                readinessState = ReadinessState.LOOKUP_ONLY,
                confidenceReasons = listOf(StartupConfidenceReasonCode.EXACT_CONTENT_MATCH),
            ),
        )

        assertEquals(linkedSetOf("a", "b"), StartupPlaybackPolicy.highConfidenceCandidateKeys(candidates))
    }

    private fun candidate(
        streamKey: String = "key",
        readinessState: ReadinessState,
        confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
        isKnownCached: Boolean = false,
    ) = StartupCandidate(
        streamKey = streamKey,
        title = "Title",
        qualityLabel = "1080p",
        addonName = "Addon",
        readinessState = readinessState,
        provenance = CandidateProvenance(CandidateProvenanceKind.STARTUP_FETCH),
        confidenceReasons = confidenceReasons,
        isKnownCached = isKnownCached,
    )
}
