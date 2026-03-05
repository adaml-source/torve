package com.streamvault.domain.sync

import com.streamvault.data.auth.AuthClient
import com.streamvault.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class AccountSyncManager(
    private val prefsRepo: PreferencesRepository,
    private val authClient: AuthClient,
) {
    companion object {
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_SYNC_PAUSED = "sync_paused"
        const val KEY_LAST_SYNC_TIME = "sync_last_sync_time_ms"
        const val KEY_PAIRED_DEVICES_COUNT = "sync_paired_devices_count"
        const val KEY_PENDING_SYNC_ACTIONS = "sync_pending_actions"

        const val KEY_ACTIVE_PAIRING_CODE = "sync_active_pairing_code"
        const val KEY_TV_PAIRED = "sync_tv_paired"
        const val KEY_TV_DEVICE_NAME = "sync_tv_device_name"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(AccountSyncStatus())
    val status: StateFlow<AccountSyncStatus> = _status.asStateFlow()

    init {
        scope.launch {
            refreshStatus()
        }
    }

    suspend fun refreshStatus() {
        _status.value = readStatus()
    }

    suspend fun enableSync() {
        prefsRepo.setString(KEY_SYNC_ENABLED, true.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        refreshStatus()
    }

    suspend fun disableSync() {
        prefsRepo.setString(KEY_SYNC_ENABLED, false.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        refreshStatus()
    }

    suspend fun pauseSync() {
        prefsRepo.setString(KEY_SYNC_PAUSED, true.toString())
        refreshStatus()
    }

    suspend fun resumeSync() {
        prefsRepo.setString(KEY_SYNC_ENABLED, true.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        refreshStatus()
    }

    suspend fun markLocalOnly() {
        prefsRepo.setString(KEY_SYNC_ENABLED, false.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        refreshStatus()
    }

    suspend fun recordSyncActionQueued(type: String) {
        if (type.isBlank()) return
        val pending = readInt(KEY_PENDING_SYNC_ACTIONS).coerceAtLeast(0) + 1
        prefsRepo.setString(KEY_PENDING_SYNC_ACTIONS, pending.toString())
        refreshStatus()
    }

    suspend fun recordSyncActionDelivered() {
        val pending = (readInt(KEY_PENDING_SYNC_ACTIONS) - 1).coerceAtLeast(0)
        prefsRepo.setString(KEY_PENDING_SYNC_ACTIONS, pending.toString())
        prefsRepo.setString(KEY_LAST_SYNC_TIME, currentTimeMs().toString())
        refreshStatus()
    }

    suspend fun createPairingCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = buildString {
            repeat(6) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
        prefsRepo.setString(KEY_ACTIVE_PAIRING_CODE, code)
        return code
    }

    suspend fun claimPairingCode(code: String): Boolean {
        val normalized = code.trim().uppercase()
        if (normalized.length != 6) return false

        val activeCode = prefsRepo.getString(KEY_ACTIVE_PAIRING_CODE)?.trim()?.uppercase()
        if (activeCode != null && activeCode.isNotBlank() && activeCode != normalized) {
            return false
        }

        val paired = readInt(KEY_PAIRED_DEVICES_COUNT).coerceAtLeast(0) + 1
        prefsRepo.setString(KEY_PAIRED_DEVICES_COUNT, paired.toString())
        prefsRepo.setString(KEY_SYNC_ENABLED, true.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        prefsRepo.setString(KEY_LAST_SYNC_TIME, currentTimeMs().toString())
        prefsRepo.remove(KEY_ACTIVE_PAIRING_CODE)
        refreshStatus()
        return true
    }

    suspend fun completeTvPairing(deviceName: String = "Living Room TV") {
        prefsRepo.setString(KEY_TV_PAIRED, true.toString())
        prefsRepo.setString(KEY_TV_DEVICE_NAME, deviceName)
        prefsRepo.setString(KEY_SYNC_ENABLED, true.toString())
        prefsRepo.setString(KEY_SYNC_PAUSED, false.toString())
        prefsRepo.setString(KEY_LAST_SYNC_TIME, currentTimeMs().toString())
        refreshStatus()
    }

    suspend fun getActivePairingCode(): String? {
        return prefsRepo.getString(KEY_ACTIVE_PAIRING_CODE)
    }

    private suspend fun readStatus(): AccountSyncStatus {
        val loggedIn = authClient.isLoggedIn()
        val identity = if (loggedIn) IdentityState.SIGNED_IN else IdentityState.LOCAL

        val syncEnabled = readBoolean(KEY_SYNC_ENABLED)
        val syncPaused = readBoolean(KEY_SYNC_PAUSED)
        val syncState = when {
            syncPaused -> SyncState.PAUSED
            syncEnabled -> SyncState.ON
            else -> SyncState.OFF
        }

        return AccountSyncStatus(
            identity = identity,
            sync = syncState,
            pairedDevicesCount = readInt(KEY_PAIRED_DEVICES_COUNT).coerceAtLeast(0),
            lastSyncTimeMs = readLong(KEY_LAST_SYNC_TIME),
            pendingActionsCount = readInt(KEY_PENDING_SYNC_ACTIONS).coerceAtLeast(0),
        )
    }

    private suspend fun readBoolean(key: String): Boolean {
        return prefsRepo.getString(key)?.toBooleanStrictOrNull() ?: false
    }

    private suspend fun readInt(key: String): Int {
        return prefsRepo.getString(key)?.toIntOrNull() ?: 0
    }

    private suspend fun readLong(key: String): Long? {
        return prefsRepo.getString(key)?.toLongOrNull()
    }

    private fun currentTimeMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
