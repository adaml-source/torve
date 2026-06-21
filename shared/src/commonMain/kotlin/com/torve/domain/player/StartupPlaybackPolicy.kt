package com.torve.domain.player

import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode

object StartupPlaybackPolicy {
    fun isHighConfidenceAutoplayCandidate(candidate: StartupCandidate): Boolean {
        if (candidate.readinessState == ReadinessState.READY_NOW) return true
        if (candidate.isDirectPlayback || candidate.isKnownCached) return true

        val reasons = candidate.confidenceReasons.toSet()
        return reasons.contains(StartupConfidenceReasonCode.RECENT_SUCCESS) ||
            reasons.contains(StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL) ||
            reasons.contains(StartupConfidenceReasonCode.HASH_CACHED) ||
            reasons.contains(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH)
    }

    fun highConfidenceCandidateKeys(candidates: List<StartupCandidate>): Set<String> {
        return candidates
            .filter(::isHighConfidenceAutoplayCandidate)
            .mapTo(linkedSetOf()) { it.streamKey }
    }
}
