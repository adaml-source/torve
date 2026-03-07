package com.streamvault.di

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module

fun initKoin(vararg platformModules: Module) {
    startKoin {
        modules(sharedModule)
        modules(platformModules.toList())
    }
}

object KoinHelper {
    fun getKoin(): Koin = org.koin.core.context.GlobalContext.get()
}
