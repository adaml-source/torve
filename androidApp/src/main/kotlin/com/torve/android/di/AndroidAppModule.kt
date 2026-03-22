package com.torve.android.di

import com.torve.platform.DatabaseDriverFactory
import com.torve.platform.NetworkMonitor
import com.torve.android.device.AndroidDeviceIdProvider
import com.torve.android.premium.PremiumActionGate
import com.torve.android.security.AndroidKeystoreSecretStore
import com.torve.android.security.AndroidSyncPayloadEncryptor
import com.torve.android.sync.SyncCoordinator
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidAppModule = module {
    single { DatabaseDriverFactory(androidContext()) }
    single { NetworkMonitor(androidContext()) }
    single { AndroidKeystoreSecretStore(androidContext()) }
    single<IntegrationSecretStore> { get<AndroidKeystoreSecretStore>() }
    single<SecureStorage> { get<AndroidKeystoreSecretStore>() }
    single<SyncPayloadEncryptor> { AndroidSyncPayloadEncryptor(get()) }
    single<DeviceIdProvider> { AndroidDeviceIdProvider(androidContext()) }
    single { PremiumActionGate(get(), get()) }
    single { SyncCoordinator(androidContext(), get(), get(), get(), get()) }
}
