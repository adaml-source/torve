package com.torve.desktop.reminders

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted EPG reminder. Survives app restart.
 *
 * The serialized shape is intentionally narrow: we only need the channel
 * URL and name (so the user can recognise it in the toast), the show title,
 * and the start/end timestamps. Full programme metadata is pulled live from
 * the loaded EPG when it's available.
 */
@Serializable
data class StoredReminder(
    val key: String,
    val channelUrl: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * File-backed store for EPG reminders. Lives under the OS app-data dir so
 * uninstall removes it cleanly; per-user, no cross-device sync (a SQLDelight
 * + sync coordinator path is a follow-up).
 */
class EpgReminderStore(rootDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val file: File = File(rootDir, "epg_reminders.json").apply {
        parentFile?.mkdirs()
    }

    @Volatile
    private var cache: MutableMap<String, StoredReminder> = loadFromDisk().associateBy { it.key }.toMutableMap()

    /**
     * Observable view of the current reminder set. Surfaces (Settings'
     * Reminder list page, the EPG context menu) collect this so they
     * stay in sync as add/remove fires.
     */
    private val _state = MutableStateFlow(cache.values.toList())
    val state: StateFlow<List<StoredReminder>> = _state.asStateFlow()

    fun snapshot(): List<StoredReminder> = synchronized(this) { cache.values.toList() }

    fun contains(key: String): Boolean = synchronized(this) { cache.containsKey(key) }

    fun add(reminder: StoredReminder) {
        synchronized(this) {
            cache[reminder.key] = reminder
            persist()
            _state.value = cache.values.toList()
        }
    }

    fun remove(key: String) {
        synchronized(this) {
            if (cache.remove(key) != null) {
                persist()
                _state.value = cache.values.toList()
            }
        }
    }

    /**
     * Push the reminder's `startMs` forward by [additionalMs]. The
     * scheduler observes this via [state] and re-arms the underlying
     * delay coroutine. No-op if the key isn't present.
     */
    fun snooze(key: String, additionalMs: Long) {
        synchronized(this) {
            val existing = cache[key] ?: return
            val updated = existing.copy(startMs = existing.startMs + additionalMs)
            cache[key] = updated
            persist()
            _state.value = cache.values.toList()
        }
    }

    /**
     * Drop reminders whose start time is more than [graceMs] in the past so the
     * file doesn't grow forever with stale entries from skipped shows.
     */
    fun pruneExpired(nowMs: Long, graceMs: Long = 10 * 60_000L) {
        synchronized(this) {
            val cutoff = nowMs - graceMs
            val toRemove = cache.values.filter { it.startMs < cutoff }.map { it.key }
            if (toRemove.isEmpty()) return
            toRemove.forEach { cache.remove(it) }
            persist()
            _state.value = cache.values.toList()
        }
    }

    private val listSerializer = ListSerializer(StoredReminder.serializer())

    private fun loadFromDisk(): List<StoredReminder> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            json.decodeFromString(listSerializer, file.readText())
        }.getOrElse { t ->
            println("TORVE REMINDERS | corrupt store, ignoring: ${t.message}")
            // Don't delete - keep the bad file around for inspection. Next
            // write will overwrite it with a valid one.
            emptyList()
        }
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(listSerializer, cache.values.toList()))
            // Atomic-ish swap so a crash mid-write can't corrupt the canonical
            // file.
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { t ->
            println("TORVE REMINDERS | persist failed: ${t.message}")
        }
    }

    companion object {
        fun reminderKey(channel: Channel, programme: EpgProgramme): String =
            "${channel.url}|${programme.startTime}"
    }
}
