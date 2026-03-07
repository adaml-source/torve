package com.streamvault.android.di

import com.streamvault.android.billing.AmazonBillingManager
import com.streamvault.android.billing.BillingManager
import com.streamvault.android.cast.AmazonCastService
import com.streamvault.android.cast.CastService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storeBillingModule = module {
    single<BillingManager> { AmazonBillingManager(androidContext()) }
    single<CastService> { AmazonCastService() }
}
