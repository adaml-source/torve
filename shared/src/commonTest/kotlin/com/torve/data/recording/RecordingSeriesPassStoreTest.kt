package com.torve.data.recording

import com.torve.domain.recording.RecordingSeriesPass
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingSeriesPassStoreTest {
    @Test
    fun rulesSurviveAStoreRestartAndCanBeRemoved() = runTest {
        val preferences = MemoryPreferences()
        val pass = RecordingSeriesPass(
            id = "pass-1",
            playlistId = "playlist",
            channelId = "channel",
            titleMatch = "House of the Dragon",
            createdAtMs = 42L,
        )

        RecordingSeriesPassStore(preferences).upsert(pass)
        val restarted = RecordingSeriesPassStore(preferences)
        assertEquals(listOf(pass), restarted.load())

        restarted.remove(pass.id)
        assertTrue(RecordingSeriesPassStore(preferences).load().isEmpty())
    }

    @Test
    fun malformedPayloadIsIgnoredWithoutCrashingStartup() = runTest {
        val preferences = MemoryPreferences().apply {
            setString(RecordingSeriesPassStore.KEY_SERIES_PASSES, "not-json")
        }

        assertTrue(RecordingSeriesPassStore(preferences).load().isEmpty())
    }
}

private class MemoryPreferences : PreferencesRepository {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]
    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
