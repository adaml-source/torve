package com.torve.di

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module

private var iosKoin: Koin? = null

fun initKoin(vararg platformModules: Module) {
    iosKoin = startKoin {
        modules(sharedModule)
        modules(platformModules.toList())
    }.koin
}

object KoinHelper {
    fun getKoin(): Koin = iosKoin ?: error("Koin has not been initialized. Call initKoin first.")
}
