package com.torve.desktop.di

import com.torve.desktop.platform.DesktopDeviceIdProvider
import com.torve.desktop.security.DesktopSecretStore
import com.torve.desktop.security.DesktopClientTrustSignalProvider
import com.torve.presentation.subscription.DefaultPurchaseStringResolver
import com.torve.presentation.subscription.PurchaseStringResolver
import com.torve.desktop.security.DesktopSyncPayloadEncryptor
import com.torve.desktop.security.createDesktopSecretStore
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
    single<DesktopSecretStore> { createDesktopSecretStore() }
    single<IntegrationSecretStore> { get<DesktopSecretStore>() }
    single<SecureStorage> { get<DesktopSecretStore>() }
    single<SyncPayloadEncryptor> { DesktopSyncPayloadEncryptor(get()) }
    single<DeviceIdProvider> { DesktopDeviceIdProvider() }
    single<com.torve.domain.security.ClientTrustSignalProvider> {
        DesktopClientTrustSignalProvider(get())
    }
    single<PurchaseStringResolver> { DefaultPurchaseStringResolver() }

    // ── Phase 3 Slice B + sub-pass 2: credential transfer ────────────
    // JVM crypto engine + protocol wrapper + receiver VM. The applier
    // and consumed-nonce store live in SharedModule (no platform deps).
    single<com.torve.domain.transfer.TransferCryptoEngine> {
        com.torve.desktop.security.JvmTransferCryptoEngine()
    }
    single { com.torve.domain.transfer.SecretsTransferProtocol(engine = get()) }
    // Receiver and sender VM bindings now live in SharedModule - both
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
                    s.recordingDownloadPath.takeIf { it.isNotBlank() },
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

    // Hand-off slot the credential-first onboarding hub uses to deep-link
    // into a post-onboarding screen (Settings → Integrations or Panda
    // setup). Both DesktopOnboardingShell and V2App resolve the same
    // singleton.
    single { com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink() }

    // Prompt 9B: backend hub publisher. Lifecycle is started from
    // Main.kt once auth + LanServingController are wired. The
    // publisherId is the same value [LanLibraryManifestBuilder] uses,
    // so peers see a stable id across desktop restarts.
    single {
        com.torve.desktop.lanlibrary.LanHubPublisher(
            controller = get(),
            registry = get(),
            settings = get(),
            authClient = get(),
            publisherId = get<com.torve.domain.device.DeviceIdProvider>().getDeviceId(),
        )
    }
    // Prompt 9 storage cleanup. Wired so Settings can call into it.
    single {
        com.torve.desktop.lanlibrary.WatchedDownloadCleanup(
            downloadRepository = get(),
            watchHistoryRepository = get(),
            allowlist = get(),
        )
    }

    // ── IPTV DVR (Prompt 10 / 10B) ────────────────────────────────
    // FileBackedRecordingRepository persists rows under the OS app-data
    // dir; the shared scheduler binding (in SharedModule) resolves to
    // this implementation. The service is started from Main.kt right
    // after LanServingController so both lifecycle owners observe the
    // same auth state.
    single<com.torve.domain.recording.RecordingRepository> {
        com.torve.desktop.recording.FileBackedRecordingRepository(
            rootDir = com.torve.desktop.platform.desktopDataDir(),
        )
    }
    single {
        com.torve.desktop.recording.DesktopRecordingService(
            scheduler = get(),
            repository = get(),
            allowlist = get(),
            // Recording goes to its own dedicated folder (Settings →
            // Preferences → Downloads → Recordings folder). Returns null
            // when the user hasn't set one -- the UI surfaces this as a
            // "Set a recordings folder first" notification rather than
            // silently scribbling into the movies folder.
            recordingsRootProvider = {
                val s = get<com.torve.presentation.settings.SettingsViewModel>().state.value
                s.recordingDownloadPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            },
        )
    }
    // Bind the DesktopRecordingService instance as the shared
    // RecordingStarter so the RecordingsViewModel can call runNow()
    // and deleteRow() without depending on the desktop module.
    single<com.torve.presentation.recording.RecordingStarter> {
        get<com.torve.desktop.recording.DesktopRecordingService>()
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
