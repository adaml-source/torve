package com.streamvault.android.di

import com.streamvault.platform.DatabaseDriverFactory
import com.streamvault.platform.NetworkMonitor
import com.streamvault.android.billing.GooglePlayBillingManager
import com.streamvault.android.device.AndroidDeviceIdProvider
import com.streamvault.android.security.AndroidKeystoreSecretStore
import com.streamvault.android.sync.SyncCoordinator
import com.streamvault.domain.device.DeviceIdProvider
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.security.SecureStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidAppModule = module {
    single { DatabaseDriverFactory(androidContext()) }
    single { NetworkMonitor(androidContext()) }
    single { AndroidKeystoreSecretStore(androidContext()) }
    single<IntegrationSecretStore> { get<AndroidKeystoreSecretStore>() }
    single<SecureStorage> { get<AndroidKeystoreSecretStore>() }
    single<DeviceIdProvider> { AndroidDeviceIdProvider(androidContext()) }
    single { GooglePlayBillingManager(androidContext()) }
    single { SyncCoordinator(androidContext()) }
}
