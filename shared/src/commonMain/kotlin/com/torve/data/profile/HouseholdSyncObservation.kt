package com.torve.data.profile

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Invalidations already carry the change signal; executing the query adds no information. */
internal fun <T : Any> Query<T>.householdChangeSignals(): Flow<Unit> = asFlow().map { Unit }

/** A recoverable observer/database failure pauses sync rather than escaping an application scope. */
internal suspend fun retryHouseholdObservation(
    onFailure: () -> Unit,
    retryDelayMs: Long = 15_000L,
    observe: suspend () -> Unit,
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            observe()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onFailure()
            delay(retryDelayMs)
        }
    }
}
