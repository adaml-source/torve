package com.torve.data.replays

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ReplayParsingBudgetTest {
    @Test fun websitesAndRepositoriesShareOneParsingLane() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val active = AtomicInteger(); val maximum = AtomicInteger()
        try {
            (1..6).map { async(dispatcher) { withReplayParsingBudget(dispatcher) { checkpoint ->
                val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                try { Thread.sleep(10); checkpoint() } finally { active.decrementAndGet() }
            } } }.awaitAll()
            assertEquals(1, maximum.get())
        } finally { dispatcher.close() }
    }
    @Test fun cancelledParserStopsAndReleasesItsLaneForTheReplacement() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val started = CountDownLatch(1)
        try {
            val old = launch(dispatcher) { withReplayParsingBudget(dispatcher) { checkpoint ->
                started.countDown()
                while (true) { checkpoint(); Thread.yield() }
            } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(2000) { old.cancelAndJoin() }
            assertEquals("replacement", withTimeout(2000) {
                withReplayParsingBudget(dispatcher) { checkpoint -> checkpoint(); "replacement" }
            })
        } finally { dispatcher.close() }
    }
}
