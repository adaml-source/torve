package com.torve.presentation.apple

import com.torve.data.addon.*
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.SourceFallbackPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.DebridServiceType
import com.torve.domain.repository.StreamReadiness
import com.torve.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class AppleRecoveredSource(val url: String, val fallback: String?, val title: String)

/** Android's source ranking and once-per-source retry plan, bounded to the user's retry limit. */
class AppleSourceRecovery(
    private val ids: UserIdProvider,
    private val sessions: ApplePlaybackSessions,
    private val token: String,
    private val repository: StreamRepository,
    private val preferences: () -> StreamPreferences,
    private val accounts: () -> Map<DebridServiceType, String>,
    private val continuation: SourceContinuationSession = SourceContinuationSessionStore.session,
) {
    private val owner = ids.currentDataUserId()
    private var plan: ContinuationRetryPlan? = null
    private var attempts = 0
    private val candidates = sessions.sourceSnapshot(token)?.streams.orEmpty()
    private fun valid() = ids.currentDataUserId() == owner && sessions.identity(token) != null
    fun available(): Boolean = valid() && candidates.isNotEmpty() && plan?.remainingCount() != 0 && attempts < preferences().maxFallbackAttempts.coerceIn(0, 3)
    fun started(url: String, durationMs: Long) {
        if (!valid()) return
        val identity = sessions.identity(token) ?: return
        continuation.recordPlaybackStarted(url, durationMs.takeIf { it > 0 }, identity.season, identity.episode)
        sessions.recordSource(token, continuation.profileForPlayback(url))
    }
    @Throws(Exception::class)
    suspend fun recover(url: String, durationMs: Long, automatic: Boolean): AppleRecoveredSource? {
        if (!available() || (automatic && preferences().sourceFallbackPolicy != SourceFallbackPolicy.BEST_AVAILABLE)) return null
        val failed = continuation.recordPlaybackFailure(url, ContinuationPlaybackOutcome.PLAYBACK_ERROR)
            ?: candidates.firstOrNull { it.directUrl == url }
            ?: sessions.sourceSnapshot(token)?.autoPlayStream
        if (plan == null) {
            val reference = failed?.let { SourceProfile.from(it, resolvedUrl = url, durationMs = durationMs) }
                ?: continuation.currentProfile()?.takeIf { current -> candidates.any { continuationSourceKey(it) == current.sourceKey } }
            val ranked = if (reference == null) candidates else StreamContinuationSelector().rankSourcesForContinuation(candidates, reference).map { it.stream }
            plan = ContinuationRetryPlan(ranked).also { retry -> failed?.let(retry::markAttempted) }
        } else failed?.let { plan?.markFailed(it) }
        return ids.withPersonalDataScope {
            while (available()) {
                val candidate = plan?.nextCandidate() ?: return@withPersonalDataScope null
                attempts++
                val resolved = try {
                    withTimeoutOrNull(15_000) { repository.resolveStreamWithFallback(candidate, accounts()) }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
                if (!valid()) return@withPersonalDataScope null
                if (resolved == null || resolved.url.isBlank() || resolved.url == url) continue
                val primary = resolved.transcodeUrls?.hls?.takeIf(String::isNotBlank) ?: resolved.url
                val ready = if (candidate.isAddonHostedUrl()) {
                    try { withTimeoutOrNull(5_000) { repository.probeStreamReadiness(primary) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                } else StreamReadiness.Ready(primary)
                if (!valid()) return@withPersonalDataScope null
                if (ready !is StreamReadiness.Ready) continue
                continuation.stageResolvedSource(candidate, ready.finalUrl, listOf(resolved.url),
                    resolved.fileName, resolved.fileSize, ContinuationSelectionOrigin.RETRY)
                return@withPersonalDataScope AppleRecoveredSource(ready.finalUrl,
                    resolved.url.takeUnless { it == ready.finalUrl }, candidate.title)
            }
            null
        }
    }
}
