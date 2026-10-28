package com.torve.domain.sync

interface SyncRepository {
    /** Export payload WITHOUT secrets. Safe for backup and backend transport. */
    suspend fun exportSyncPayload(): SyncPayload

    /** Import a backup projection. Connections and credentials are never imported. */
    suspend fun importSyncPayload(payload: SyncPayload): SyncResult

    /** Only the authenticated, encrypted, paired LAN receiver may call this. */
    suspend fun importForLocalTransfer(payload: SyncPayload): SyncResult = importSyncPayload(payload)

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
