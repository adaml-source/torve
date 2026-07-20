package com.torve.domain.integrations

/**
 * Cross-platform bridge for the Torve backend's watch-state endpoints.
 *
 * The backend holds watch-state reports from every device a user signs into,
 * so the detail screen can show "Resume from 23:15" even when the only
 * previous playback happened on another device. Implementations wrap the
 * platform's sync client (Android: SyncCoordinator; iOS: TorveAPIClient)
 * and return `null` whenever the user is signed out, the backend has no row
 * yet, or the network call fails — the caller treats any null as "no remote
 * state" and falls back to the local SQLDelight row.
 */
interface WatchStateRemoteSource {
    suspend fun getLatest(contentId: String): RemoteWatchState?
}

/**
 * Normalized response. Platform-independent, so `DetailViewModel` can merge
 * this with the local [com.torve.domain.model.WatchProgress] without knowing
 * which backend serialized the row.
 *
 * [reportedAtMs] is epoch milliseconds so it's directly comparable against
 * [com.torve.domain.model.WatchProgress.updatedAt].
 */
data class RemoteWatchState(
    val contentId: String,
    val provider: String,
    val positionMs: Long,
    val reportedAtMs: Long,
    val deviceId: String,
)
