package com.torve.android.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DownloadResponsePolicyTest {
    @Test fun interruptedBodyCannotBecomeCompleted() {
        assertThrows(IOException::class.java) { DownloadResponsePolicy.verifyComplete(499, 500) }
        assertThrows(IOException::class.java) { DownloadResponsePolicy.verifyComplete(501, 500) }
        assertThrows(IOException::class.java) { DownloadResponsePolicy.verifyComplete(0, null) }
        DownloadResponsePolicy.verifyComplete(500, 500)
    }
    @Test fun changedRepresentationRestartsRatherThanAppending() {
        val plan = DownloadResponsePolicy.plan(200, 400, 800, null)
        assertFalse(plan.append); assertEquals(800L, plan.totalBytes)
    }
    @Test fun rangeMustStartAtThePersistedOffsetAndMatchItsLength() {
        assertEquals(DownloadResponsePlan(true, 1000), DownloadResponsePolicy.plan(206, 400, 600, "bytes 400-999/1000"))
        for (range in listOf("bytes 0-599/1000", "bytes 400-1000/1000", "bytes 400-999/*", "bytes 400-999/1000 extra")) {
            assertThrows(IOException::class.java) { DownloadResponsePolicy.plan(206, 400, 600, range) }
        }
        assertThrows(IOException::class.java) { DownloadResponsePolicy.plan(206, 400, 599, "bytes 400-999/1000") }
    }
    @Test fun alreadyCompleteRangeIsAcceptedOnlyForAnExactNonemptyFile() {
        assertTrue(DownloadResponsePolicy.plan(416, 1000, null, "bytes */1000").alreadyComplete)
        assertThrows(IOException::class.java) { DownloadResponsePolicy.plan(416, 900, null, "bytes */1000") }
        assertThrows(IOException::class.java) { DownloadResponsePolicy.plan(416, 0, null, "bytes */0") }
    }
    @Test fun authenticationFailureDoesNotLoopButTemporaryFailuresCanRetry() {
        assertFalse(DownloadHttpException(401).retryable)
        assertTrue(DownloadHttpException(429).retryable)
        assertTrue(DownloadHttpException(503).retryable)
    }
}
