package com.streamvault.android

import android.app.Application
import com.streamvault.android.di.androidAppModule
import com.streamvault.android.download.DownloadWorker
import com.streamvault.android.notification.EpisodeNotificationWorker
import com.streamvault.android.sync.TraktSyncWorker
import com.streamvault.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class StreamVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // TODO: Initialize crash reporting SDK here (e.g. Firebase Crashlytics, Sentry).
        //  Example for Crashlytics:
        //    FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        //  Example for Sentry:
        //    SentryAndroid.init(this) { options -> options.dsn = BuildConfig.SENTRY_DSN }
        setupUncaughtExceptionHandler()

        startKoin {
            androidLogger()
            androidContext(this@StreamVaultApp)
            modules(sharedModule, androidAppModule)
        }
        EpisodeNotificationWorker.schedule(this)
        TraktSyncWorker.schedule(this)
        DownloadWorker.ensureChannel(this)
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // TODO: Forward to crash reporting service before delegating to default handler.
            //  e.g. FirebaseCrashlytics.getInstance().recordException(throwable)
            android.util.Log.e("StreamVault", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
