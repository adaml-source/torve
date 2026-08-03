package com.torve.domain.integrations

import com.torve.domain.model.MediaType

/**
 * The state of a title as it moves from a household request into the user's
 * permanent Plex/Jellyfin/Emby library. This deliberately stays independent
 * from playback-source availability: a title may be playable from debrid now
 * while Sonarr/Radarr is still acquiring the preferred library copy.
 */
enum class MediaLifecycleState {
    UNCONFIGURED,
    NOT_REQUESTED,
    PENDING_APPROVAL,
    APPROVED,
    PROCESSING,
    PARTIALLY_AVAILABLE,
    AVAILABLE,
    DECLINED,
    FAILED,
    DELETED,
    UNKNOWN,
}

data class MediaLifecycleStatus(
    val tmdbId: Int,
    val mediaType: MediaType,
    val state: MediaLifecycleState,
    val requestId: Int? = null,
    val is4k: Boolean = false,
    val updatedAt: String? = null,
) {
    val canRequest: Boolean
        get() = state in setOf(
            MediaLifecycleState.NOT_REQUESTED,
            MediaLifecycleState.DECLINED,
            MediaLifecycleState.FAILED,
            MediaLifecycleState.DELETED,
        )

    val canRetry: Boolean
        get() = requestId != null && state == MediaLifecycleState.FAILED

    /** Removes the Seerr request record without deleting an acquired media file. */
    val canDeleteRequest: Boolean
        get() = requestId != null && state !in setOf(
            MediaLifecycleState.UNCONFIGURED,
            MediaLifecycleState.NOT_REQUESTED,
            MediaLifecycleState.DELETED,
        )

    val isInProgress: Boolean
        get() = state in setOf(
            MediaLifecycleState.PENDING_APPROVAL,
            MediaLifecycleState.APPROVED,
            MediaLifecycleState.PROCESSING,
            MediaLifecycleState.PARTIALLY_AVAILABLE,
        )
}

data class MediaLifecycleRequest(
    val tmdbId: Int,
    val mediaType: MediaType,
    val seasons: List<Int> = emptyList(),
    val is4k: Boolean = false,
)

/** Rich request-list entry used by the consumer Library experience. */
data class MediaLifecycleEntry(
    val status: MediaLifecycleStatus,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val tvdbId: Int? = null,
)

/**
 * User-facing request lifecycle. Implementations must keep credentials out of
 * exceptions and diagnostics; callers only surface stable, generic errors.
 */
interface MediaLifecycleService {
    suspend fun isConfigured(): Boolean
    suspend fun testConnection(serverUrl: String, apiKey: String): Boolean
    suspend fun getStatus(
        tmdbId: Int,
        mediaType: MediaType,
        seasons: List<Int> = emptyList(),
        is4k: Boolean = false,
    ): MediaLifecycleStatus
    suspend fun request(request: MediaLifecycleRequest): MediaLifecycleStatus
    suspend fun retry(requestId: Int): MediaLifecycleStatus

    /**
     * Deletes the request from the request service. Implementations must not
     * interpret this as permission to delete already acquired media files.
     */
    suspend fun deleteRequest(requestId: Int): Boolean = false

    /** Safe default for request services which only support per-title lookup. */
    suspend fun listRecent(limit: Int = 50): List<MediaLifecycleEntry> = emptyList()
}
