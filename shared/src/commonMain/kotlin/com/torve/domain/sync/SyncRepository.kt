package com.torve.domain.sync

interface SyncRepository {
    /** Export payload WITHOUT secrets. Safe for backup and backend transport. */
    suspend fun exportSyncPayload(): SyncPayload

    /** Import payload. Secrets in the payload are imported only if present. */
    suspend fun importSyncPayload(payload: SyncPayload): SyncResult

    /** Export to JSON WITHOUT secrets. */
    suspend fun exportToJson(): String

    /** Import from JSON. */
    suspend fun importFromJson(json: String): SyncResult

    /**
     * Export payload WITH integration secrets for direct device-to-device transfer.
     * Must only be used for explicit local LAN transfer to a paired device.
     * Must never be sent through the backend.
     */
    suspend fun exportForLocalTransfer(): String
}
