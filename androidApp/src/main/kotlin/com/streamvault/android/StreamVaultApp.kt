package com.streamvault.android

import android.app.Application
import com.streamvault.android.di.androidAppModule
import com.streamvault.android.notification.EpisodeNotificationWorker
import com.streamvault.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class StreamVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@StreamVaultApp)
            modules(sharedModule, androidAppModule)
        }
        EpisodeNotificationWorker.schedule(this)
    }
}
