package com.torve.android

import android.app.Application
import com.torve.android.billing.BillingManager
import com.torve.android.di.androidAppModule
import com.torve.android.di.storeBillingModule
import com.torve.android.download.DownloadWorker
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.sync.TraktSyncWorker
import com.torve.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class TorveApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Firebase Crashlytics — disabled in debug, unavailable on Amazon builds.
        configureCrashlytics()
        setupUncaughtExceptionHandler()

        startKoin {
            androidLogger()
            androidContext(this@TorveApp)
            modules(sharedModule, androidAppModule, storeBillingModule)
        }
        getKoin().get<BillingManager>().initialize()
        EpisodeNotificationWorker.schedule(this)
        TraktSyncWorker.schedule(this)
        DownloadWorker.ensureChannel(this)
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
