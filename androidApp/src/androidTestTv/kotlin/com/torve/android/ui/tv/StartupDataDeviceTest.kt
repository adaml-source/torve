package com.torve.android.ui.tv

import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.di.ActiveProfileIdCache
import com.torve.android.diagnostics.AndroidDiagnosticsRecorder
import com.torve.db.TorveDatabase
import org.junit.Assert.*
import org.junit.Test

class StartupDataDeviceTest {
    @Test fun realProfileTableWritesInvalidateTheCachedSelectionSynchronously() {
        val database = org.koin.java.KoinJavaComponent.getKoin().get<TorveDatabase>()
        val owner = "qa-profile-cache-${System.nanoTime()}"
        val query = database.torveQueries
        val cache = ActiveProfileIdCache.observing(database)
        try {
            query.insertProfile(owner, "a", "A", 0, 1, null, null, 0)
            query.insertProfile(owner, "b", "B", 0, 0, null, null, 0)
            assertEquals("a", cache.current(owner))
            query.setActiveProfile("b", owner)
            assertEquals("b", cache.current(owner))
            query.deleteProfile(owner, "b")
            assertNull(cache.current(owner))
        } finally {
            query.deleteProfile(owner, "a")
            query.deleteProfile(owner, "b")
        }
    }

    @Test fun queuedDiagnosticsAreRedactedAndImmediatelyAvailableForExport() {
        val marker = "qa-log-${System.nanoTime()}"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AndroidDiagnosticsRecorder.recordLog("WARN", "qa", "$marker Authorization: Bearer qa_sensitive_token_12345")
        }
        val line = AndroidDiagnosticsRecorder.logsForReport().single { marker in it }
        assertFalse(line.contains("qa_sensitive_token_12345"))
        assertTrue(marker in line)
    }
}
