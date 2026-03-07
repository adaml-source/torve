package com.torve.android.di

import com.torve.android.billing.AmazonBillingManager
import com.torve.android.billing.BillingManager
import com.torve.android.cast.AmazonCastService
import com.torve.android.cast.CastService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storeBillingModule = module {
    single<BillingManager> { AmazonBillingManager(androidContext()) }
    single<CastService> { AmazonCastService() }
}
