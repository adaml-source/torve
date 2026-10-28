package com.torve.android.tv.screens

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Publish each completed row. A slow neighbour can never hold a ready row behind awaitAll. */
internal suspend fun <K, V : Any> loadProgressiveCatalogRows(
    keys: List<K>,
    rowTimeoutMs: Long = 6_000,
    fetch: suspend (K) -> V?,
    publish: suspend (K, V) -> Unit,
) = supervisorScope {
    val permits = Semaphore(2)
    val publication = Mutex()
    keys.distinct().map { key -> launch {
        permits.withPermit {
            val value = try { withTimeoutOrNull(rowTimeoutMs) { fetch(key) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (value != null) publication.withLock { publish(key, value) }
        }
    } }.joinAll()
}

/** Update in place and append new rows; loading a higher priority row must not shift current focus. */
internal fun <T> mergeProgressiveCatalogRows(existing: List<T>, incoming: List<T>, key: (T) -> String): List<T> {
    val rows = linkedMapOf<String, T>()
    existing.forEach { rows[key(it)] = it }
    incoming.forEach { rows[key(it)] = it }
    return rows.values.toList()
}
