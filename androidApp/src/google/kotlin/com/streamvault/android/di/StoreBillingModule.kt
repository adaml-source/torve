package com.streamvault.android.di

import com.streamvault.android.billing.BillingManager
import com.streamvault.android.billing.GooglePlayBillingManager
import com.streamvault.android.cast.CastService
import com.streamvault.android.cast.GoogleCastService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storeBillingModule = module {
    single<BillingManager> { GooglePlayBillingManager(androidContext()) }
    single<CastService> { GoogleCastService(androidContext()) }
}
