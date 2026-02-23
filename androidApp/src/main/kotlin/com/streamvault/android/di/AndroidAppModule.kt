package com.streamvault.android.di

import com.streamvault.platform.DatabaseDriverFactory
import com.streamvault.platform.NetworkMonitor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidAppModule = module {
    single { DatabaseDriverFactory(androidContext()) }
    single { NetworkMonitor(androidContext()) }
}
