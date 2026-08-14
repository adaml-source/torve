package com.torve.data.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InFlightRefreshCoalescerTest {
    @Test
    fun concurrentCallersShareOneOperation() = runTest {
        val coalescer = InFlightRefreshCoalescer()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var operations = 0
        val first = async {
            coalescer.run("playlist") {
                operations++
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val second = async { coalescer.run("playlist") { operations++ } }
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, operations)
    }

    @Test
    fun failureIsSharedAndNextCallCanRetry() = runTest {
        val coalescer = InFlightRefreshCoalescer()
        assertFailsWith<IllegalStateException> {
            coalescer.run("playlist") { error("failed") }
        }
        var retried = false
        coalescer.run("playlist") { retried = true }
        assertEquals(true, retried)
    }
}
