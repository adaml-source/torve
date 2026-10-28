package com.torve.data.replays

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.repository.DeviceLocalSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.Koin

/** Private replay bookmarks and ephemeral request headers, shared by Android form factors. */
class ReplayPlaybackStore internal constructor(
    private val local: DeviceLocalSettingsRepository,
    private val owner: () -> String,
    private val personalScope: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    companion object {
        internal fun create(koin: Koin): ReplayPlaybackStore {
            val ids = koin.get<UserIdProvider>()
            return ReplayPlaybackStore(koin.get(), { ids.currentDataUserId() },
                { block -> ids.withPersonalDataScope { block() } })
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun key() = if (owner() == UserIdProvider.SIGNED_OUT_USER_ID)
        "web_replays_guest_web_replays_v1_progress" else "web_replays_v1_progress"
    private suspend fun read(): Map<String, ReplayProgress> = runCatching {
        json.decodeFromString<Map<String, ReplayProgress>>(local.getString(key()).orEmpty())
    }.getOrDefault(emptyMap())
    suspend fun startPosition(id: String): Long {
        val expected = owner(); var result = 0L
        personalScope { if (owner() == expected) result = ReplayProgressPolicy.start(read()[id]) }
        return result.takeIf { owner() == expected } ?: 0L
    }
    suspend fun save(expectedOwner: String, id: String, position: Long, duration: Long, hasRenderedOutput: Boolean) {
        personalScope { lock.withLock {
            if (owner() != expectedOwner || !hasRenderedOutput) return@withLock
            val saved = read()
            if (owner() != expectedOwner) return@withLock
            val updated = ReplayProgressPolicy.record(saved, id, position, duration, true, Clock.System.now().toEpochMilliseconds())
            if (updated != saved) local.setString(key(), json.encodeToString(updated))
        } }
    }
    fun stage(stream: ResolvedReplay) = PendingReplayHeaders.value.stage(owner(), stream)
    fun consumeHeaders(url: String): Map<String, String>? = PendingReplayHeaders.value.consume(owner(), url)
    /** Final bookmark outlives the departing player composition. */
    fun record(expectedOwner: String, id: String, position: Long, duration: Long, hasRenderedOutput: Boolean) {
        writes.launch { runCatching { save(expectedOwner, id, position, duration, hasRenderedOutput) } }
    }
}
