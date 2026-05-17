package com.torve.android.di

import com.torve.android.billing.AmazonBillingManager
import com.torve.android.billing.BillingManager
import com.torve.android.billing.NoOpBillingManager
import com.torve.android.billing.isStripeFireTvBillingBuild
import com.torve.android.cast.AmazonCastService
import com.torve.android.cast.CastService
import com.torve.android.security.ClientIntegrityTokenProvider
import com.torve.android.security.NoOpClientIntegrityTokenProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storeBillingModule = module {
    single<BillingManager> {
        if (isStripeFireTvBillingBuild()) {
            NoOpBillingManager()
        } else {
            AmazonBillingManager(androidContext())
        }
    }
    single<CastService> { AmazonCastService() }
    single<ClientIntegrityTokenProvider> { NoOpClientIntegrityTokenProvider }
}
