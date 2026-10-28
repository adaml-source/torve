package com.torve.domain.stats

interface WatchStatsRepository {
    suspend fun getSessions(filters: WatchStatsFilters = WatchStatsFilters()): List<WatchSession>
    suspend fun getLatestPlayerSession(mediaId: String): WatchSession? =
        getSessions(WatchStatsFilters(sources = setOf(WatchSessionSource.TORVE_PLAYER)))
            .filter { it.mediaId == mediaId }
            .maxWithOrNull(compareBy<WatchSession> { it.startedAt }.thenBy { it.updatedAt })
    suspend fun getAggregation(filters: WatchStatsFilters = WatchStatsFilters()): WatchStatsSummary
    suspend fun upsertSession(session: WatchSession)
    suspend fun updateSessionProgress(
        id: String,
        endedAt: Long?,
        status: WatchSessionStatus,
        durationMs: Long?,
        maxPositionMs: Long,
        countedWatchMs: Long,
        completionPercent: Double,
        runtimeConfidence: RuntimeConfidence,
        updatedAt: Long,
    )
    suspend fun runBackfillIfNeeded()
    suspend fun clearForCurrentUser()
    suspend fun clearForMedia(mediaId: String)
}
