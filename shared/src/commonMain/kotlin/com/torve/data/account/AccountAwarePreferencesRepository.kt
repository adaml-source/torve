package com.torve.data.account

import com.torve.data.auth.UserIdProvider
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository

class AccountAwarePreferencesRepository(
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val userIdProvider: UserIdProvider,
) : PreferencesRepository {
    override suspend fun getString(key: String): String? {
        if (!userIdProvider.isSignedIn()) return null
        return localSettingsRepository.getString(key)
    }

    override suspend fun setString(key: String, value: String) {
        if (!userIdProvider.isSignedIn()) return
        localSettingsRepository.setString(key, value)
        accountSettingsRepository.scheduleSharedSettingSync(key, value)
    }

    override suspend fun remove(key: String) {
        if (!userIdProvider.isSignedIn()) return
        localSettingsRepository.remove(key)
        accountSettingsRepository.scheduleSharedSettingSync(key, null)
    }
}
