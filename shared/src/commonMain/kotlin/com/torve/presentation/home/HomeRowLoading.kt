package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** A slow optional source cannot hold back an already completed Home row. */
internal suspend fun loadHomeRows(
    requests: List<suspend () -> CatalogShelf?>,
    concurrency: Int = 2,
    timeoutMs: Long = 6_000,
    publish: suspend (List<CatalogShelf>) -> Unit,
): List<CatalogShelf> = coroutineScope {
    val limiter = Semaphore(concurrency)
    val lock = Mutex()
    val ready = linkedMapOf<Int, CatalogShelf>()
    requests.forEachIndexed { index, request -> launch {
        val row = try { limiter.withPermit { withTimeoutOrNull(timeoutMs) { request() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (row != null && row.items.isNotEmpty()) lock.withLock {
            ready[index] = row
            publish(ready.values.toList())
        }
    } }
    // coroutineScope waits for children after this expression. Read the final
    // collection only after they finish, in the outer scope below.
    ready
}.values.toList()
