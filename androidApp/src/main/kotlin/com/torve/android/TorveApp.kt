package com.torve.android

import android.app.Application
import com.torve.android.billing.BillingManager
import com.torve.android.catalog.CatalogWarmupWorker
import com.torve.android.di.androidAppModule
import com.torve.android.diagnostics.AndroidDiagnosticsRecorder
import com.torve.android.di.storeBillingModule
import com.torve.android.download.DownloadWorker
import com.torve.android.epg.EpgWarmupWorker
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.sync.TraktSyncWorker
import com.torve.android.update.AppUpdateWorker
import com.torve.android.update.usesVpsReleaseUpdates
import com.torve.data.acceleration.AccelerationInventorySyncService
import com.torve.data.contentpolicy.MutableContentChannelProvider
import com.torve.data.trakt.TraktClient
import com.torve.di.sharedModule
import com.torve.domain.security.ClientTrustSignalProvider
import com.torve.domain.security.ClientTrustSignalRegistry
import com.torve.platform.TorveRuntimeDebug
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class TorveApp : Application(), androidx.work.Configuration.Provider {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        com.torve.android.debug.StartupMetrics.init(this)
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    internal val tvUiReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    internal fun onTvUiReady() {
        if (tvUiReady.complete(Unit)) {
            com.torve.android.debug.AnrDebugLogger.log("STARTUP home_ui_ready")
        }
    }

    companion object {
        private const val NON_CRITICAL_STARTUP_DELAY_MS = 2_000L
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signalled once dependency registration and packaged credentials are ready. */
    internal val koinReady = java.util.concurrent.CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()
        val applicationEnteredAtMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("TorveStartup", "application_enter")
        com.torve.data.addon.ExtensionRuntimePolicy.restorePandaFromAccount = false
        com.torve.data.addon.ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls = true
        com.torve.data.addon.ExtensionRuntimePolicy.userManagedOnly = true
        com.torve.data.addon.ExtensionRuntimePolicy.optionalMetadataExtensions = true
        // Installed TV debug builds must exercise normal remote performance.
        // Verbose network logging repeatedly redacts large payloads on callers.
        TorveRuntimeDebug.verboseLoggingEnabled = BuildConfig.DEBUG && !BuildConfig.FLAVOR.endsWith("Tv", ignoreCase = true)

        // Firebase Crashlytics — disabled in debug, unavailable on Amazon builds.
        if (BuildConfig.FLAVOR.contains("google", ignoreCase = true)) configureCrashlytics()
        setupUncaughtExceptionHandler()
        // Compiling diagnostic redaction patterns is independent of the first TV screen.
        appScope.launch { AndroidDiagnosticsRecorder.init(this@TorveApp) }

        // Init ANR diagnostics (debug only — no-op in release)
        com.torve.android.debug.AnrDebugLogger.init(filesDir)
        com.torve.android.debug.AnrDebugLogger.log("STARTUP Application.onCreate BEGIN initMs=${android.os.SystemClock.elapsedRealtime() - applicationEnteredAtMs}")

        // Fire TV startup has proven sensitive to secondary-dex class resolution
        // when Koin module registration is pushed onto a worker thread. Keep DI
        // startup on the main thread, and defer only non-critical warm-up work.
        com.torve.android.debug.AnrDebugLogger.log("STARTUP startKoin BEGIN (main thread)")
        startKoin {
            if (BuildConfig.DEBUG) {
                androidLogger()
            }
            androidContext(this@TorveApp)
            modules(sharedModule, androidAppModule, storeBillingModule)
        }
        com.torve.android.debug.AnrDebugLogger.log("STARTUP startKoin END")
        ClientTrustSignalRegistry.setProvider(getKoin().get<ClientTrustSignalProvider>())
        getKoin().get<TraktClient>().setPackagedCredentials(
            clientId = BuildConfig.TRAKT_CLIENT_ID,
            clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
        )
        getKoin().get<MutableContentChannelProvider>().update(
            if (BuildConfig.FLAVOR.contains("google", ignoreCase = true)) "google_play" else null,
        )
        koinReady.countDown()
        com.torve.android.debug.AnrDebugLogger.log("STARTUP koinReady signalled")

        appScope.launch {
            val isTvBuild = BuildConfig.FLAVOR.contains("tv", ignoreCase = true)
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm BEGIN")
            runCatching { getKoin().get<com.torve.db.TorveDatabase>() }
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm END")

            // Non-critical deferred work.
            if (isTvBuild) {
                // Avoid competing with initial composition for CPU, disk and DI locks.
                kotlinx.coroutines.withTimeoutOrNull(15_000L) { tvUiReady.await() }
                kotlinx.coroutines.delay(1_000L)
            } else kotlinx.coroutines.delay(NON_CRITICAL_STARTUP_DELAY_MS)
            com.torve.android.debug.AnrDebugLogger.log("STARTUP background_maintenance_begin")
            com.torve.android.launcher.AndroidTvPlayNext.start(this@TorveApp, appScope)
            if (BuildConfig.HAS_BILLING && !com.torve.android.billing.isStripeFireTvBillingBuild()) {
                launch { runCatching { getKoin().get<BillingManager>().initialize() } }
            }
            launch {
                TraktSyncWorker.schedule(this@TorveApp)
                if (isTvBuild) {
                    CatalogWarmupWorker.cancel(this@TorveApp)
                    EpgWarmupWorker.schedule(this@TorveApp)
                } else {
                    EpgWarmupWorker.schedule(this@TorveApp)
                    CatalogWarmupWorker.schedule(this@TorveApp)
                }
                DownloadWorker.ensureChannel(this@TorveApp)
                com.torve.android.update.StoreUpdates.schedule(this@TorveApp)
                if (usesVpsReleaseUpdates(BuildConfig.FLAVOR)) {
                    AppUpdateWorker.schedule(this@TorveApp)
                    AppUpdateWorker.checkNow(this@TorveApp)
                } else {
                    // Google Play owns delivery for Play-distributed builds. In
                    // particular, Play's in-app update API is not supported on TV.
                    AppUpdateWorker.cancel(this@TorveApp)
                }
            }
            launch {
                runCatching {
                    getKoin().get<AccountSessionCoordinator>().restoreSession(
                        promoteLegacyTvJellyfin = isTvBuild,
                    )
                }
                // Independent account follow-up work must not form one long
                // serial chain behind Panda's remote configuration hydration.
                launch {
                    runCatching { getKoin().get<AccelerationInventorySyncService>().syncConnectedProviders() }
                }
                launch {
                    runCatching { getKoin().get<com.torve.presentation.device.DeviceGovernanceViewModel>().fetchAccessState() }
                }
                launch { runCatching { EpisodeNotificationWorker.scheduleIfEnabled(this@TorveApp) } }
            }
            launch { runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() } }

            // Provider-health: register shared checkers whose Android
            // dependencies are bound, then run them once. Subsequent
            // re-runs are driven by the credential-transfer completion
            // notifier — see the observer below.
            launch {
                runCatching {
                    val init = getKoin()
                        .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                    init.start()
                }
            }

            // Keep the active profile's episode-alert preference scheduled.
            launch {
                val settingsNotifier = getKoin().get<com.torve.presentation.settings.SettingsRefreshNotifier>()
                settingsNotifier.events.collect {
                    // Changing profiles also emits this event. Never retain another
                    // person's visible alert or leave their preference scheduled.
                    androidx.core.app.NotificationManagerCompat.from(this@TorveApp).cancel(EpisodeNotificationWorker.NOTIFICATION_ID)
                    runCatching { EpisodeNotificationWorker.scheduleIfEnabled(this@TorveApp) }
                }
            }

            // Refresh provider-health rows the moment a credential
            // transfer succeeds. The notifier ticks `lastImportEpochMs`
            // on every Success branch (manual paste + relay-driven), so
            // collecting it here drives a coordinator.runAll() per
            // import — no recomposition-driven rerun storms.
            launch {
                runCatching {
                    val notifier = getKoin()
                        .get<com.torve.presentation.transfer.TransferImportCompletionNotifier>()
                    val init = getKoin()
                        .get<com.torve.android.providerhealth.AndroidProviderHealthInit>()
                    notifier.lastImportEpochMs.collect { ms ->
                        if (ms > 0L) init.refreshAll()
                    }
                }
            }
        }
    }

    private fun configureCrashlytics() {
        try {
            val clazz = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("setCrashlyticsCollectionEnabled", Boolean::class.java)
                .invoke(instance, !BuildConfig.DEBUG)
        } catch (_: Throwable) {
            // Firebase not available (Amazon build)
        }
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val clazz = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                val instance = clazz.getMethod("getInstance").invoke(null)
                clazz.getMethod("recordException", Throwable::class.java)
                    .invoke(instance, throwable)
            } catch (_: Throwable) { }
            runCatching { AndroidDiagnosticsRecorder.persistCrash(this, thread, throwable) }
            android.util.Log.e("Torve", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
