package com.torve.desktop.recording

import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingRepository
import com.torve.domain.recording.RecordingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-on-disk recording repository. Modeled on the existing
 * [com.torve.desktop.reminders.EpgReminderStore] - same atomic-rename
 * persistence, same per-process mutex, same lazy-load-on-first-call.
 *
 * No SQLDelight table this slice - recordings are bounded (typically
 * tens of rows on a real install) and the file shape lets the user
 * inspect/clean it in the OS file explorer when triaging a problem.
 */
class FileBackedRecordingRepository(rootDir: File) : RecordingRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Recording.serializer())
    private val file: File = File(rootDir, "recordings.json").apply {
        parentFile?.mkdirs()
    }
    private val mutex = Mutex()

    private val _recordings = MutableStateFlow<List<Recording>>(loadFromDisk())
    override val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    override suspend fun upsert(recording: Recording) {
        mutex.withLock {
            val current = _recordings.value
            val updated = current.filterNot { it.id == recording.id } + recording
            persist(updated)
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            val current = _recordings.value
            val updated = current.filterNot { it.id == id }
            if (updated.size != current.size) persist(updated)
        }
    }

    override suspend fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }

    override suspend fun listByStatus(vararg statuses: RecordingStatus): List<Recording> {
        if (statuses.isEmpty()) return emptyList()
        val want = statuses.toSet()
        return _recordings.value.filter { it.status in want }
    }

    override suspend fun listAll(): List<Recording> = _recordings.value

    override suspend fun clearAll() {
        mutex.withLock { persist(emptyList()) }
    }

    private fun loadFromDisk(): List<Recording> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse {
                println("TORVE RECORDINGS | corrupt store, ignoring: ${it.message}")
                emptyList()
            }
    }

    private fun persist(next: List<Recording>) {
        _recordings.value = next
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { t ->
            println("TORVE RECORDINGS | persist failed: ${t.message}")
        }
    }
}
