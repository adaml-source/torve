package com.torve.data.recording

import com.torve.domain.recording.RecordingSeriesPass
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Durable household rules used to rematerialize DVR schedules after EPG refreshes and restarts. */
class RecordingSeriesPassStore(
    private val preferences: PreferencesRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mutex = Mutex()
    private val _passes = MutableStateFlow<List<RecordingSeriesPass>>(emptyList())
    val passes: StateFlow<List<RecordingSeriesPass>> = _passes.asStateFlow()

    suspend fun load(): List<RecordingSeriesPass> = mutex.withLock {
        val decoded = preferences.getString(KEY_SERIES_PASSES)
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching {
                    json.decodeFromString(ListSerializer(RecordingSeriesPass.serializer()), encoded)
                }.getOrNull()
            }
            .orEmpty()
            .filter { it.id.isNotBlank() && it.playlistId.isNotBlank() && it.channelId.isNotBlank() && it.titleMatch.isNotBlank() }
            .distinctBy(RecordingSeriesPass::id)
            .sortedBy(RecordingSeriesPass::createdAtMs)
        _passes.value = decoded
        decoded
    }

    suspend fun upsert(pass: RecordingSeriesPass) = mutex.withLock {
        val next = (_passes.value.filterNot { it.id == pass.id } + pass)
            .sortedBy(RecordingSeriesPass::createdAtMs)
        persist(next)
    }

    suspend fun remove(passId: String) = mutex.withLock {
        persist(_passes.value.filterNot { it.id == passId })
    }

    private suspend fun persist(next: List<RecordingSeriesPass>) {
        if (next.isEmpty()) {
            preferences.remove(KEY_SERIES_PASSES)
        } else {
            preferences.setString(
                KEY_SERIES_PASSES,
                json.encodeToString(ListSerializer(RecordingSeriesPass.serializer()), next),
            )
        }
        _passes.value = next
    }

    companion object {
        internal const val KEY_SERIES_PASSES = "recording_series_passes_v1"
    }
}
