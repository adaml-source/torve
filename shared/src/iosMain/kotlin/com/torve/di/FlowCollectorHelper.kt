package com.torve.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * iOS bridge: collects a StateFlow and calls a Swift callback on Main dispatcher.
 * Returns a Closeable that cancels the collection job.
 */
object FlowCollectorHelper {

    fun <T> collect(
        flow: StateFlow<T>,
        callback: (T) -> Unit,
    ): Closeable {
        val job = CoroutineScope(Dispatchers.Main).launch {
            flow.collect { value ->
                callback(value)
            }
        }
        return Closeable(job)
    }
}

class Closeable(private val job: Job) {
    fun close() {
        job.cancel()
    }
}
