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

    // ── Phase 3 Slice B + sub-pass 2: credential transfer ────────────
    // JVM crypto engine + protocol wrapper + receiver VM. The applier
    // and consumed-nonce store live in SharedModule (no platform deps).
    single<com.torve.domain.transfer.TransferCryptoEngine> {
        com.torve.desktop.security.JvmTransferCryptoEngine()
    }
    single { com.torve.domain.transfer.SecretsTransferProtocol(engine = get()) }
    // Receiver and sender VM bindings now live in SharedModule — both
    // desktop and mobile/TV consume the same factories.

    // ── Phase 3 Slice C: LAN library plumbing ────────────────────────
    // Token table + allowlist + manifest builder + local-first router
    // are independent singletons; the LanServingController owns the
    // HTTP server lifecycle and listens to settings + auth state.
    single { com.torve.desktop.lanlibrary.LanMediaTokenTable() }
    single {
        com.torve.desktop.lanlibrary.DownloadFolderAllowlist(
            rootsProvider = {
                val s = get<com.torve.presentation.settings.SettingsViewModel>().state.value
                listOfNotNull(
                    s.movieDownloadPath.takeIf { it.isNotBlank() },
                    s.showDownloadPath.takeIf { it.isNotBlank() },
                    s.adultDownloadPath.takeIf { it.isNotBlank() },
                    s.sportsDownloadPath.takeIf { it.isNotBlank() },
                ).map { java.io.File(it) }
            },
        )
    }
    single {
        com.torve.desktop.lanlibrary.LanLibraryManifestBuilder(
            publisherIdProvider = { get<DeviceIdProvider>().getDeviceId() },
            allowlist = get(),
            tokenTable = get(),
        )
    }
    single {
        com.torve.desktop.lanlibrary.LocalFirstPlaybackRouter(
            downloadRepository = get(),
            allowlist = get(),
        )
    }
    single {
        com.torve.desktop.lanlibrary.LanServingController(
            tokenTable = get(),
            allowlist = get(),
            manifestBuilder = get(),
            downloadRepository = get(),
            settings = get(),
            authClient = get(),
        )
    }

    // Desktop wires its own ProviderHealthChecker set into the shared
    // ProviderHealthCoordinator at startup. See V2App for the call site.
    single {
        com.torve.desktop.providerhealth.DesktopProviderHealthInit(
            repository = get(),
            coordinator = get(),
            debridClient = get(),
            secretStore = get(),
            prefs = get(),
            libraryService = get(),
            addonRepository = get(),
            stremioAddonClient = get(),
            channelsViewModel = get(),
            pandaConfigStateStore = get(),
            refreshOnSettings = get(),
        )
    }
}
