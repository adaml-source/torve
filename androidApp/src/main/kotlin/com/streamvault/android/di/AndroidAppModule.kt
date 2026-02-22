package com.streamvault.android.di

import com.streamvault.platform.DatabaseDriverFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidAppModule = module {
    single { DatabaseDriverFactory(androidContext()) }
}
