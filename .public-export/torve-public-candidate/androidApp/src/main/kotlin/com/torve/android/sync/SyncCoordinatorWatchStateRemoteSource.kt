package com.torve.android.sync

import com.torve.domain.integrations.RemoteWatchState
import com.torve.domain.integrations.WatchStateRemoteSource
import java.time.Instant

/**
 * Bridges the KMP [WatchStateRemoteSource] contract to the Android-only
 * [SyncCoordinator]. The coordinator already owns auth, premium gating and
 * error handling; this adapter only maps the backend DTO into the KMP
 * domain shape.
 */
class SyncCoordinatorWatchStateRemoteSource(
    private val syncCoordinator: SyncCoordinator,
) : WatchStateRemoteSource {
    override suspend fun getLatest(contentId: String): RemoteWatchState? {
        val dto = syncCoordinator.getLatestWatchState(contentId) ?: return null
        val reportedAtMs = parseReportedAt(dto.reportedAt) ?: return null
        return RemoteWatchState(
            contentId = dto.contentId,
            provider = dto.provider,
            positionMs = dto.positionMs,
            reportedAtMs = reportedAtMs,
            deviceId = dto.deviceId,
        )
    }

    private fun parseReportedAt(raw: String): Long? {
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
