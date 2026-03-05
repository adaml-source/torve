package com.streamvault.domain.sync

enum class IdentityState {
    LOCAL,
    SIGNED_IN,
}

enum class SyncState {
    OFF,
    ON,
    PAUSED,
}

data class AccountSyncStatus(
    val identity: IdentityState = IdentityState.LOCAL,
    val sync: SyncState = SyncState.OFF,
    val pairedDevicesCount: Int = 0,
    val lastSyncTimeMs: Long? = null,
    val pendingActionsCount: Int = 0,
)
