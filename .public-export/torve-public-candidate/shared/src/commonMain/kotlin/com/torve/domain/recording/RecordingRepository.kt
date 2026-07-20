package com.torve.domain.recording

import kotlinx.coroutines.flow.Flow

/**
 * Persistence for IPTV recordings. SQLDelight-backed on platform
 * implementations; the domain interface stays platform-clean.
 */
interface RecordingRepository {
    /** Hot list of every recording — surface filters by status. */
    val recordings: Flow<List<Recording>>

    suspend fun upsert(recording: Recording)
    suspend fun delete(id: String)
    suspend fun get(id: String): Recording?
    suspend fun listByStatus(vararg statuses: RecordingStatus): List<Recording>
    suspend fun listAll(): List<Recording>

    /** Wipe — used on sign-out. */
    suspend fun clearAll()
}
