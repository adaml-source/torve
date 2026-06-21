package com.torve.presentation.streampicker

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.StartupCandidate
import com.torve.domain.player.StartupPlaybackPolicy

/**
 * Pure ordering function for the per-source fallback chain. Produces
 * the same ordered try-list the mobile picker shows visually:
 *   1. Fast Start - high-confidence startup candidates (recent
 *                   success / direct URL / cached hash / connected
 *                   service).
 *   2. Best Fit - score >= 70 OR has any startup-candidate entry,
 *                 in input order.
 *   3. More Options - everything else, in input order.
 *
 * The caller (PlayerScreen on mobile, TV detail) walks this list when
 * the active source fails: ask [nextAfter] for the next stream and
 * play it without dumping the user back to the picker. Pure so the
 * ordering is testable without a Compose harness or running engine.
 *
 * The shared module does not own the `streamUiKey()` extension that
 * androidApp uses for stable identity, so callers pass [keyOf] in.
 */
object StreamFallbackOrdering {

    /**
     * Produce the deterministic try-order for [streams]. The list
     * preserves input order within each tier, so an upstream change
     * to ranking (score, addon weights) flows through unchanged.
     */
    fun streamsInTryOrder(
        streams: List<ParsedStream>,
        startupCandidates: List<StartupCandidate> = emptyList(),
        keyOf: (ParsedStream) -> String,
    ): List<ParsedStream> {
        if (streams.isEmpty()) return emptyList()
        val candidateKeys = startupCandidates.mapTo(linkedSetOf()) { it.streamKey }
        val fastStartKeys = StartupPlaybackPolicy.highConfidenceCandidateKeys(startupCandidates)

        val fastStart = streams.filter { keyOf(it) in fastStartKeys }
        val fastStartKeySet = fastStart.mapTo(linkedSetOf()) { keyOf(it) }
        val bestFit = streams
            .filterNot { keyOf(it) in fastStartKeySet }
            .filter { it.score >= 70 || keyOf(it) in candidateKeys }
        val bestFitKeySet = bestFit.mapTo(linkedSetOf()) { keyOf(it) }
        val more = streams.filterNot { keyOf(it) in fastStartKeySet || keyOf(it) in bestFitKeySet }
        return fastStart + bestFit + more
    }

    /**
     * Return the next stream to try after [failedKey]'s source failed
     * to play, given a [tryOrder] from [streamsInTryOrder] and the set
     * of [alreadyTried] keys (so a retry storm doesn't re-pick a
     * stream that already failed earlier in the session).
     *
     * Returns null when the list is exhausted - caller should surface
     * the "no more sources" state instead of looping.
     */
    fun nextAfter(
        tryOrder: List<ParsedStream>,
        failedKey: String,
        keyOf: (ParsedStream) -> String,
        alreadyTried: Set<String> = emptySet(),
    ): ParsedStream? {
        val failedIndex = tryOrder.indexOfFirst { keyOf(it) == failedKey }
        val rest = if (failedIndex < 0) tryOrder else tryOrder.drop(failedIndex + 1)
        return rest.firstOrNull { keyOf(it) != failedKey && keyOf(it) !in alreadyTried }
    }
}
