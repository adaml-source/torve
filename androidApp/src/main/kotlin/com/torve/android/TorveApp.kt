package com.torve.android

import android.app.Application
import com.torve.android.billing.BillingManager
import com.torve.android.di.androidAppModule
import com.torve.android.di.storeBillingModule
import com.torve.android.download.DownloadWorker
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.sync.TraktSyncWorker
import com.torve.data.acceleration.AccelerationInventorySyncService
import com.torve.data.contentpolicy.MutableContentChannelProvider
import com.torve.di.sharedModule
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

class TorveApp : Application() {
    companion object {
        private const val NON_CRITICAL_STARTUP_DELAY_MS = 2_000L
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signalled once startKoin + DB pre-warm have completed on the background thread. */
    internal val koinReady = java.util.concurrent.CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()
        TorveRuntimeDebug.verboseLoggingEnabled = BuildConfig.DEBUG

        // Firebase Crashlytics — disabled in debug, unavailable on Amazon builds.
        configureCrashlytics()
        setupUncaughtExceptionHandler()

        // Init ANR diagnostics (debug only — no-op in release)
        com.torve.android.debug.AnrDebugLogger.init(filesDir)
        com.torve.android.debug.AnrDebugLogger.log("STARTUP Application.onCreate BEGIN")

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
        getKoin().get<MutableContentChannelProvider>().update(
            if (BuildConfig.FLAVOR.contains("google", ignoreCase = true)) "google_play" else null,
        )
        koinReady.countDown()
        com.torve.android.debug.AnrDebugLogger.log("STARTUP koinReady signalled")

        appScope.launch {
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm BEGIN")
            runCatching { getKoin().get<com.torve.db.TorveDatabase>() }
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm END")

            // Non-critical deferred work.
            kotlinx.coroutines.delay(NON_CRITICAL_STARTUP_DELAY_MS)
            launch { runCatching { getKoin().get<BillingManager>().initialize() } }
            launch {
                EpisodeNotificationWorker.schedule(this@TorveApp)
                TraktSyncWorker.schedule(this@TorveApp)
                DownloadWorker.ensureChannel(this@TorveApp)
            }
            launch {
                runCatching { getKoin().get<AccountSessionCoordinator>().restoreSession() }
                runCatching { getKoin().get<AccelerationInventorySyncService>().syncConnectedProviders() }
            }
            launch { runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() } }
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
            android.util.Log.e("Torve", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
