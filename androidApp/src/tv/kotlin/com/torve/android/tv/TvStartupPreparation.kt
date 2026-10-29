package com.torve.android.tv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Account identity must settle before either branch reads profile-scoped caches. */
internal class TvStartupPreparation<Appearance, Home>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    restoreAccount: suspend () -> Unit,
    loadAppearance: suspend () -> Appearance,
    loadHome: suspend () -> Home,
) {
    private val account = scope.async(dispatcher) { restoreAccount() }
    val appearance = scope.async(dispatcher) { account.await(); loadAppearance() }
    val home = scope.async(dispatcher) { account.await(); loadHome() }
}
