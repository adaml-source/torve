package com.torve.android

import android.app.Application
import com.torve.android.billing.BillingManager
import com.torve.android.di.androidAppModule
import com.torve.android.di.storeBillingModule
import com.torve.data.auth.AuthClient
import com.torve.android.download.DownloadWorker
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.sync.TraktSyncWorker
import com.torve.di.sharedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class TorveApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signalled once startKoin + DB pre-warm have completed on the background thread. */
    internal val koinReady = java.util.concurrent.CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()

        // Firebase Crashlytics — disabled in debug, unavailable on Amazon builds.
        configureCrashlytics()
        setupUncaughtExceptionHandler()

        // Init ANR diagnostics (debug only — no-op in release)
        com.torve.android.debug.AnrDebugLogger.init(filesDir)
        com.torve.android.debug.AnrDebugLogger.log("STARTUP Application.onCreate BEGIN")

        // Move ALL Koin initialization (module registration + class loading for
        // 90+ types) off the main thread.  The first Compose frame renders a
        // dark background; once koinReady is signalled the real UI appears.
        appScope.launch {
            com.torve.android.debug.AnrDebugLogger.log("STARTUP startKoin BEGIN (IO thread)")
            startKoin {
                androidLogger()
                androidContext(this@TorveApp)
                modules(sharedModule, androidAppModule, storeBillingModule)
            }
            com.torve.android.debug.AnrDebugLogger.log("STARTUP startKoin END")
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm BEGIN")
            runCatching { getKoin().get<com.torve.db.TorveDatabase>() }
            com.torve.android.debug.AnrDebugLogger.log("STARTUP DB pre-warm END")
            koinReady.countDown()
            com.torve.android.debug.AnrDebugLogger.log("STARTUP koinReady signalled")

            // Non-critical deferred work.
            launch { runCatching { getKoin().get<BillingManager>().initialize() } }
            launch {
                EpisodeNotificationWorker.schedule(this@TorveApp)
                TraktSyncWorker.schedule(this@TorveApp)
                DownloadWorker.ensureChannel(this@TorveApp)
            }
            launch { runCatching { getKoin().get<AuthClient>().restoreSession() } }
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
