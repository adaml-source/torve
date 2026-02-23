package com.streamvault.domain.sync

interface SyncRepository {
    suspend fun exportSyncPayload(): SyncPayload
    suspend fun importSyncPayload(payload: SyncPayload): SyncResult
    suspend fun exportToJson(): String
    suspend fun importFromJson(json: String): SyncResult
}
