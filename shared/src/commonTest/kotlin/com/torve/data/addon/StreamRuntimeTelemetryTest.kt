package com.torve.data.addon

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamRuntimeTelemetryTest {
    @BeforeTest
    fun reset() = StreamRuntimeTelemetry.clearForTest()

    @AfterTest
    fun cleanup() = StreamRuntimeTelemetry.clearForTest()

    @Test
    fun reportsInsufficientDataWithoutInventingLatency() {
        val empty = StreamRuntimeTelemetry.performanceSnapshot()
        assertEquals(PlaybackStartupSloStatus.INSUFFICIENT_DATA, empty.status)
        assertEquals(null, empty.p50StartupMs)
        assertEquals(null, empty.p95StartupMs)
    }

    @Test
    fun evaluatesMeasuredFirstFrameSamplesAgainstTargets() {
        repeat(20) { index ->
            StreamRuntimeTelemetry.recordPlayAttempt("healthy")
            StreamRuntimeTelemetry.recordStartupSuccess(
                hostKey = "healthy",
                startupMs = if (index == 19) 9_000L else 3_000L,
            )
        }

        val snapshot = StreamRuntimeTelemetry.performanceSnapshot("healthy")
        assertEquals(20, snapshot.sampleCount)
        assertEquals(3_000L, snapshot.p50StartupMs)
        assertEquals(3_000L, snapshot.p95StartupMs)
        assertEquals(PlaybackStartupSloStatus.MEETS_TARGET, snapshot.status)
        assertEquals(1.0, snapshot.successRate)
    }

    @Test
    fun failedStartsLowerSuccessRateAndFailTheTarget() {
        repeat(19) {
            StreamRuntimeTelemetry.recordPlayAttempt("mixed")
            StreamRuntimeTelemetry.recordStartupSuccess("mixed", 2_500L)
        }
        StreamRuntimeTelemetry.recordPlayAttempt("mixed")
        StreamRuntimeTelemetry.recordStartupTimeout("mixed", 10_000L)

        val snapshot = StreamRuntimeTelemetry.performanceSnapshot("mixed")
        assertNotNull(snapshot.successRate)
        assertTrue(snapshot.successRate < 0.98)
        assertEquals(PlaybackStartupSloStatus.BELOW_TARGET, snapshot.status)
    }

    @Test
    fun timeoutAndFatalCallbacksCountAsOneFailedAttempt() {
        repeat(19) {
            StreamRuntimeTelemetry.recordPlayAttempt("deduped")
            StreamRuntimeTelemetry.recordStartupSuccess("deduped", 2_000L)
        }
        StreamRuntimeTelemetry.recordPlayAttempt("deduped")
        StreamRuntimeTelemetry.recordStartupTimeout("deduped", 10_000L)
        StreamRuntimeTelemetry.recordFatalError("deduped")

        val snapshot = StreamRuntimeTelemetry.performanceSnapshot("deduped")

        assertEquals(20, snapshot.sampleCount)
        assertEquals(19, snapshot.successfulStarts)
        assertEquals(1, snapshot.failedStarts)
        assertEquals(0.95, snapshot.successRate)
    }
}
