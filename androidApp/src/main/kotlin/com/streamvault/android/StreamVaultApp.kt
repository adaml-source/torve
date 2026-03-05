package com.streamvault.android

import android.app.Application
import com.streamvault.android.di.androidAppModule
import com.streamvault.android.download.DownloadWorker
import com.streamvault.android.notification.EpisodeNotificationWorker
import com.streamvault.android.sync.TraktSyncWorker
import com.streamvault.di.sharedModule
import com.streamvault.android.billing.GooglePlayBillingManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class StreamVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Firebase Crashlytics initializes automatically via the plugin.
        // Disable collection in debug builds to keep the dashboard clean.
        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            .setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        setupUncaughtExceptionHandler()

        startKoin {
            androidLogger()
            androidContext(this@StreamVaultApp)
            modules(sharedModule, androidAppModule)
        }
        getKoin().get<GooglePlayBillingManager>().initialize()
        EpisodeNotificationWorker.schedule(this)
        TraktSyncWorker.schedule(this)
        DownloadWorker.ensureChannel(this)
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(throwable)
            android.util.Log.e("Torve", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
