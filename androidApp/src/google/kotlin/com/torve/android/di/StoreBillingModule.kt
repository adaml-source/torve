package com.torve.android.di

import com.torve.android.billing.BillingManager
import com.torve.android.billing.GooglePlayBillingManager
import com.torve.android.cast.CastService
import com.torve.android.cast.GoogleCastService
import com.torve.android.security.ClientIntegrityTokenProvider
import com.torve.android.security.GooglePlayIntegrityTokenProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storeBillingModule = module {
    single<BillingManager> { GooglePlayBillingManager(androidContext()) }
    single<CastService> { GoogleCastService(androidContext()) }
    single<ClientIntegrityTokenProvider> { GooglePlayIntegrityTokenProvider(androidContext()) }
}
