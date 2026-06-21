package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.presentation.error.UserFacingError
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class AccountSettingsSyncState(
    val isRefreshing: Boolean = false,
    val hasPendingPush: Boolean = false,
    val lastFetchedAt: Long? = null,
    val lastRemoteUpdatedAt: String? = null,
    val lastError: String? = null,
)

data class AccountSettingsRefreshResult(
    val appliedChanges: Boolean = false,
    val skipped: Boolean = false,
    val error: String? = null,
)

interface AccountSettingsRepository {
    val state: StateFlow<AccountSettingsSyncState>

    suspend fun syncAfterSignIn(): AccountSettingsRefreshResult
    suspend fun refreshIfStale(force: Boolean = false): AccountSettingsRefreshResult
    fun scheduleSharedSettingSync(key: String, value: String?)
    suspend fun clearSessionState()
}

class AccountSettingsRepositoryImpl(
    private val authClient: AuthClient,
    private val accountSettingsApi: AccountSettingsApi,
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
) : AccountSettingsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AccountSettingsSyncState())
    override val state: StateFlow<AccountSettingsSyncState> = _state.asStateFlow()

    override suspend fun syncAfterSignIn(): AccountSettingsRefreshResult {
        return refreshInternal(force = true, allowMigration = true)
    }

    override suspend fun refreshIfStale(force: Boolean): AccountSettingsRefreshResult {
        return refreshInternal(force = force, allowMigration = false)
    }

    override fun scheduleSharedSettingSync(key: String, value: String?) {
        if (!AccountSettingsSyncPolicy.isSharedKey(key)) return
        scope.launch {
            _state.update { it.copy(hasPendingPush = true, lastError = null) }
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                _state.update { it.copy(hasPendingPush = false) }
                return@launch
            }
            runCatching {
                accountSettingsApi.patchAccountSettings(
                    accessToken = token,
                    settings = AccountSettingsSyncPolicy.outgoingRemoteSettings(key, value),
                )
            }.onSuccess { response ->
                _state.update {
                    it.copy(
                        hasPendingPush = false,
                        lastFetchedAt = nowMs(),
                        lastRemoteUpdatedAt = response.updatedAt,
                        lastError = null,
                    )
                }
            }.onFailure { _ ->
                _state.update {
                    it.copy(
                        hasPendingPush = false,
                        lastError = UserFacingError.SYNC_FAILED.messageKey,
                    )
                }
            }
        }
    }

    override suspend fun clearSessionState() {
        localSettingsRepository.remove(KEY_LAST_FETCHED_AT)
        localSettingsRepository.remove(KEY_REMOTE_UPDATED_AT)
        localSettingsRepository.remove(KEY_MIGRATION_DONE)
        _state.value = AccountSettingsSyncState()
    }

    private suspend fun refreshInternal(
        force: Boolean,
        allowMigration: Boolean,
    ): AccountSettingsRefreshResult {
        val token = authClient.getValidAccessToken()
            ?: return AccountSettingsRefreshResult(skipped = true)
        if (!force && !isStale()) {
            return AccountSettingsRefreshResult(skipped = true)
        }
        _state.update { it.copy(isRefreshing = true, lastError = null) }
        return runCatching {
            val response = accountSettingsApi.getAccountSettings(token)
            val applied = applyRemoteSettings(response.settings, response.updatedAt)
            if (allowMigration) {
                // Push any local shared keys that the backend doesn't have yet.
                // This handles both first-time migration and expanded key sets.
                reconcileLocalToRemote(token, response.settings)
            }
            localSettingsRepository.setString(KEY_MIGRATION_DONE, TRUE)
            localSettingsRepository.setString(KEY_LAST_FETCHED_AT, nowMs().toString())
            response.updatedAt?.let { localSettingsRepository.setString(KEY_REMOTE_UPDATED_AT, it) }
            _state.update {
                it.copy(
                    isRefreshing = false,
                    lastFetchedAt = nowMs(),
                    lastRemoteUpdatedAt = response.updatedAt,
                    lastError = null,
                )
            }
            AccountSettingsRefreshResult(appliedChanges = applied)
        }.getOrElse { _ ->
            _state.update {
                it.copy(
                    isRefreshing = false,
                    lastError = UserFacingError.SYNC_FAILED.messageKey,
                )
            }
            AccountSettingsRefreshResult(error = UserFacingError.SYNC_FAILED.messageKey)
        }
    }

    private suspend fun applyRemoteSettings(
        remoteSettings: Map<String, String>,
        updatedAt: String?,
    ): Boolean {
        var changed = false
        for ((key, value) in AccountSettingsSyncPolicy.normalizeRemoteSettings(remoteSettings)) {
            val localValue = localSettingsRepository.getString(key)
            if (localValue != value) {
                localSettingsRepository.setString(key, value)
                changed = true
            }
        }
        // Security: _sync_payload is no longer imported. Secrets are device-local only.
        if (changed) {
            settingsRefreshNotifier.notifyRefresh(nowMs())
        }
        updatedAt?.let { localSettingsRepository.setString(KEY_REMOTE_UPDATED_AT, it) }
        return changed
    }

    private suspend fun reconcileLocalToRemote(
        token: String,
        remoteSettings: Map<String, String>,
    ) {
        // Find local shared keys that the backend doesn't have yet and push them.
        val patch = mutableMapOf<String, String?>()
        for (key in AccountSettingsSyncPolicy.sharedKeys()) {
            val localValue = localSettingsRepository.getString(key) ?: continue
            val outgoing = AccountSettingsSyncPolicy.outgoingRemoteSettings(key, localValue)
            for ((remoteKey, remoteValue) in outgoing) {
                if (remoteValue != null && !remoteSettings.containsKey(remoteKey)) {
                    patch[remoteKey] = remoteValue
                }
            }
        }
        // Security: sync payloads with secrets are no longer pushed to the backend.
        // Only non-sensitive preference keys are synced via account settings.
        if (patch.isNotEmpty()) {
            val response = accountSettingsApi.patchAccountSettings(token, patch)
            response.updatedAt?.let { localSettingsRepository.setString(KEY_REMOTE_UPDATED_AT, it) }
        }
    }

    private suspend fun isStale(): Boolean {
        val lastFetchedAt = localSettingsRepository.getString(KEY_LAST_FETCHED_AT)?.toLongOrNull()
            ?: return true
        return nowMs() - lastFetchedAt >= STALE_AFTER_MS
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val KEY_LAST_FETCHED_AT = "account_settings_last_fetched_at"
        const val KEY_REMOTE_UPDATED_AT = "account_settings_remote_updated_at"
        const val KEY_MIGRATION_DONE = "account_settings_migration_done"

        const val TRUE = "true"
        const val STALE_AFTER_MS = 5 * 60 * 1000L
    }
}
