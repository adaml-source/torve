package com.torve.data.addon

import com.torve.domain.model.ContentWarmupDisposition
import com.torve.domain.model.ContentWarmupResult
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StartupCandidatesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

internal class ContentWarmupRegistry(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class WarmSnapshot(
        val snapshot: StartupCandidatesSnapshot,
        val completedAt: Long,
    )

    private val mutex = Mutex()
    private val snapshots = linkedMapOf<String, WarmSnapshot>()
    private val inFlightKeys = mutableSetOf<String>()

    suspend fun warmup(
        request: SourceAccelerationRequest,
        trigger: ContentWarmupTrigger,
        hasWarmupContext: Boolean,
        loader: suspend () -> StartupCandidatesSnapshot,
    ): ContentWarmupResult {
        val now = nowMs()
        val cached = mutex.withLock {
            snapshots[request.contentKey]?.takeIf { now - it.completedAt <= reuseWindowMs(trigger) }
        }
        if (cached != null) {
            return ContentWarmupResult(
                request = request,
                trigger = trigger,
                disposition = ContentWarmupDisposition.REUSED_RECENT,
                snapshot = cached.snapshot,
                completedAt = cached.completedAt,
            )
        }
        if (!hasWarmupContext) {
            return ContentWarmupResult(
                request = request,
                trigger = trigger,
                disposition = ContentWarmupDisposition.SKIPPED_NO_CONTEXT,
            )
        }

        val shouldLoad = mutex.withLock {
            if (!inFlightKeys.add(request.contentKey)) {
                false
            } else {
                true
            }
        }
        if (!shouldLoad) {
            return ContentWarmupResult(
                request = request,
                trigger = trigger,
                disposition = ContentWarmupDisposition.SKIPPED_IN_FLIGHT,
            )
        }

        return try {
            val snapshot = loader()
            val completedAt = nowMs()
            mutex.withLock {
                snapshots[request.contentKey] = WarmSnapshot(
                    snapshot = snapshot,
                    completedAt = completedAt,
                )
                trimSnapshots()
            }
            ContentWarmupResult(
                request = request,
                trigger = trigger,
                disposition = ContentWarmupDisposition.WARMED,
                snapshot = snapshot,
                completedAt = completedAt,
            )
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (_: Exception) {
            ContentWarmupResult(
                request = request,
                trigger = trigger,
                disposition = ContentWarmupDisposition.FAILED,
            )
        } finally {
            mutex.withLock {
                inFlightKeys.remove(request.contentKey)
            }
        }
    }

    suspend fun getFreshSnapshot(
        request: SourceAccelerationRequest,
        maxAgeMs: Long = DEFAULT_FRESH_SNAPSHOT_AGE_MS,
    ): StartupCandidatesSnapshot? {
        val now = nowMs()
        return mutex.withLock {
            snapshots[request.contentKey]?.takeIf { now - it.completedAt <= maxAgeMs }?.snapshot
        }
    }

    private fun trimSnapshots() {
        while (snapshots.size > MAX_TRACKED_SNAPSHOTS) {
            val oldestKey = snapshots.entries.firstOrNull()?.key ?: break
            snapshots.remove(oldestKey)
        }
    }

    private fun reuseWindowMs(trigger: ContentWarmupTrigger): Long {
        return when (trigger) {
            ContentWarmupTrigger.DETAIL_OPEN -> DETAIL_OPEN_REUSE_WINDOW_MS
            ContentWarmupTrigger.TV_PLAY_ACTION_FOCUS -> TV_FOCUS_REUSE_WINDOW_MS
            ContentWarmupTrigger.NEXT_EPISODE_AUTOPLAY -> NEXT_EPISODE_REUSE_WINDOW_MS
        }
    }

    private companion object {
        const val DETAIL_OPEN_REUSE_WINDOW_MS = 45_000L
        const val TV_FOCUS_REUSE_WINDOW_MS = 15_000L
        const val NEXT_EPISODE_REUSE_WINDOW_MS = 90_000L
        const val DEFAULT_FRESH_SNAPSHOT_AGE_MS = 60_000L
        const val MAX_TRACKED_SNAPSHOTS = 64
    }
}
