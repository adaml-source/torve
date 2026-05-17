package com.torve.android.di

import com.torve.platform.DatabaseDriverFactory
import com.torve.platform.NetworkMonitor
import com.torve.android.device.AndroidDeviceIdProvider
import com.torve.android.i18n.AndroidPurchaseStringResolver
import com.torve.android.premium.PremiumActionGate
import com.torve.android.security.AndroidKeystoreSecretStore
import com.torve.android.security.AndroidClientTrustSignalProvider
import com.torve.android.security.AndroidSyncPayloadEncryptor
import com.torve.android.security.AndroidTransferCryptoEngine
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.SyncCoordinatorWatchStateRemoteSource
import com.torve.android.sync.network.TorveSyncApiClient
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.WatchStateRemoteSource
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import com.torve.presentation.subscription.PurchaseStringResolver
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
    single<com.torve.domain.security.ClientTrustSignalProvider> {
        AndroidClientTrustSignalProvider(androidContext(), get())
    }
    single<PurchaseStringResolver> { AndroidPurchaseStringResolver(androidContext()) }
    single { PremiumActionGate(get(), get(), androidContext()) }
    single { TorveSyncApiClient(httpClient = get()) }
    single { SyncCoordinator(androidContext(), get(), get(), get(), get(), get()) }
    single<WatchStateRemoteSource> { SyncCoordinatorWatchStateRemoteSource(get()) }

    // ── Phase 3 credential transfer (Android sender) ─────────────────
    // Crypto engine + protocol. Receiver-side bindings (SecretsTransferApplier,
    // ConsumedNonceStore) are already in SharedModule; the sender VM
    // factory is also in SharedModule and consumes this engine.
    single<com.torve.domain.transfer.TransferCryptoEngine> {
        AndroidTransferCryptoEngine()
    }
    single { com.torve.domain.transfer.SecretsTransferProtocol(engine = get()) }

    // ── Provider-health init (Android equivalent of DesktopProviderHealthInit) ──
    // Registers shared checkers whose dependencies are bound on Android.
    // Started once from TorveApp.onCreate.
    single {
        com.torve.android.providerhealth.AndroidProviderHealthInit(
            repository = get(),
            coordinator = get(),
            debridClient = get(),
            secretStore = get(),
            traktTokenStore = get(),
            prefs = get(),
            libraryService = get(),
            addonRepository = get(),
            stremioAddonClient = get(),
            channelsViewModel = get(),
            pandaConfigStateStore = get(),
            refreshOnSettings = get(),
            context = androidContext(),
        )
    }
}
