package com.torve.desktop.di

import com.torve.desktop.platform.DesktopDeviceIdProvider
import com.torve.desktop.security.DesktopFileSecretStore
import com.torve.presentation.subscription.DefaultPurchaseStringResolver
import com.torve.presentation.subscription.PurchaseStringResolver
import com.torve.desktop.security.DesktopSyncPayloadEncryptor
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import com.torve.platform.DatabaseDriverFactory
import com.torve.platform.NetworkMonitor
import org.koin.dsl.module

val desktopAppModule = module {
    single { DatabaseDriverFactory() }
    single { NetworkMonitor() }
    single { DesktopFileSecretStore() }
    single<IntegrationSecretStore> { get<DesktopFileSecretStore>() }
    single<SecureStorage> { get<DesktopFileSecretStore>() }
    single<SyncPayloadEncryptor> { DesktopSyncPayloadEncryptor(get()) }
    single<DeviceIdProvider> { DesktopDeviceIdProvider() }
    single<PurchaseStringResolver> { DefaultPurchaseStringResolver() }
}
