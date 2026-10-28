package com.torve.presentation.library

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.isAppFeatureEnabled

/** Only requested titles are polled, independently of the Requests screen. */
class LibraryRequestMonitor(
    private val service: MediaLifecycleService,
    private val prefs: PreferencesRepository,
    private val userIds: UserIdProvider? = null,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable
    private data class Record(
        val tmdbId: Int, val type: MediaType, val title: String,
        val state: MediaLifecycleState, val seasons: List<Int> = emptyList(),
        val is4k: Boolean = false, val pendingNotice: Boolean = false,
        val updatedAt: Long = 0,
    ) {
        val key: String get() = "${type.name}:$tmdbId:$is4k"
    }
    data class Notice(val key: String, val title: String, val state: MediaLifecycleState) {
        val available: Boolean get() = state == MediaLifecycleState.AVAILABLE
        val message: String get() = when (state) {
            MediaLifecycleState.AVAILABLE -> "$title is now available in your library"
            MediaLifecycleState.DECLINED -> "Library request declined: $title"
            MediaLifecycleState.FAILED -> "Library request needs attention: $title"
            MediaLifecycleState.DELETED -> "$title was removed from your library"
            else -> "Library request updated: $title"
        }
    }

    private suspend fun <T> scoped(block: suspend () -> T): T =
        if (userIds != null) userIds.withPersonalDataScope(block) else block()
    private suspend fun read(): List<Record> = prefs.getString(KEY)?.let {
        runCatching { json.decodeFromString<List<Record>>(it) }.getOrNull()
    }.orEmpty()
    private suspend fun write(records: List<Record>) {
        prefs.setString(KEY, json.encodeToString(records.takeLast(64)))
    }

    suspend fun track(status: MediaLifecycleStatus, title: String, seasons: List<Int> = emptyList()) = scoped {
        lock.withLock {
            if (status.state == MediaLifecycleState.UNCONFIGURED || status.state == MediaLifecycleState.NOT_REQUESTED) return@withLock
            val records = read().toMutableList()
            val fresh = Record(status.tmdbId, status.mediaType, title, status.state,
                seasons.distinct().sorted(), status.is4k, updatedAt = Clock.System.now().toEpochMilliseconds())
            val old = records.firstOrNull { it.key == fresh.key }
            val notice = old?.pendingNotice == true || (old != null && old.state != fresh.state &&
                fresh.state in NOTICE_STATES)
            records.removeAll { it.key == fresh.key }
            records += fresh.copy(pendingNotice = notice,
                seasons = fresh.seasons.ifEmpty { old?.seasons.orEmpty() })
            write(records)
        }
    }

    suspend fun poll(): List<Notice> = scoped {
        lock.withLock {
            if (!prefs.isAppFeatureEnabled(OptionalAppFeature.ARR_STACK)) return@withLock emptyList()
            val records = read().toMutableList()
            // No request-list, ARR-library or catalogue work on navigation/focus.
            for (index in records.indices) {
                val old = records[index]
                if (!old.state.isActiveRequest()) continue
                val status = try {
                    service.getStatus(old.tmdbId, old.type, old.seasons, old.is4k)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    continue // A transient outage must keep the request pending.
                }
                if (status.state == MediaLifecycleState.UNCONFIGURED) continue
                records[index] = old.copy(state = status.state,
                    pendingNotice = old.pendingNotice || (old.state != status.state && status.state in NOTICE_STATES))
            }
            if (records.isNotEmpty()) write(records)
            records.filter { it.pendingNotice }.map { Notice(it.key, it.title, it.state) }
        }
    }

    suspend fun acknowledge(key: String) = scoped {
        lock.withLock { write(read().map { if (it.key == key) it.copy(pendingNotice = false) else it }) }
    }
    suspend fun forget(tmdbId: Int, type: MediaType) = scoped {
        lock.withLock { write(read().filterNot { it.tmdbId == tmdbId && it.type == type }) }
    }
    companion object {
        private const val KEY = "library_request_monitor_v1"
        private val NOTICE_STATES = setOf(MediaLifecycleState.AVAILABLE, MediaLifecycleState.DECLINED,
            MediaLifecycleState.FAILED, MediaLifecycleState.DELETED)
    }
}

private fun MediaLifecycleState.isActiveRequest(): Boolean = this in setOf(
    MediaLifecycleState.PENDING_APPROVAL, MediaLifecycleState.APPROVED,
    MediaLifecycleState.PROCESSING, MediaLifecycleState.PARTIALLY_AVAILABLE, MediaLifecycleState.UNKNOWN,
)
