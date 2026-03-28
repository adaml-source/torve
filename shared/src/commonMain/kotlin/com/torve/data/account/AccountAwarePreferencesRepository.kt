package com.torve.data.account

import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository

class AccountAwarePreferencesRepository(
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
) : PreferencesRepository {
    override suspend fun getString(key: String): String? {
        return localSettingsRepository.getString(key)
    }

    override suspend fun setString(key: String, value: String) {
        localSettingsRepository.setString(key, value)
        accountSettingsRepository.scheduleSharedSettingSync(key, value)
    }

    override suspend fun remove(key: String) {
        localSettingsRepository.remove(key)
        accountSettingsRepository.scheduleSharedSettingSync(key, null)
    }
}
