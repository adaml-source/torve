package com.torve.data.simkl

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.PersonalDataScope
import com.torve.domain.integrations.*
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SimklPendingWrite(val path: String, val body: SimklSyncBody)

/** Persist before sending, and never replay one connection's writes to another account. */
class SimklMutationJournal(private val preferences: PreferencesRepository,
    private val secrets: IntegrationSecretStore, private val ids: UserIdProvider) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var lastWriteAt = 0L
    private suspend fun read() = preferences.getString(KEY)?.let {
        runCatching { json.decodeFromString<List<SimklPendingWrite>>(it) }.getOrNull()
    }.orEmpty()
    private suspend fun persist(entries: List<SimklPendingWrite>) = preferences.setString(KEY, json.encodeToString(entries))
    private suspend fun forConnection(token: String): List<SimklPendingWrite> {
        if (secrets.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, OWNER) != token) {
            persist(emptyList())
            secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, token, OWNER)
        }
        return read()
    }
    suspend fun submit(token: String, path: String, body: SimklSyncBody,
        send: suspend (String, SimklSyncBody) -> Unit): Unit = withContext(com.torve.data.auth.capturePersonalDataScope(ids)) {
        mutex.withLock {
            val pending = forConnection(token)
            val write = SimklPendingWrite(path, body)
            check(pending.size < 2_000) { "SIMKL has many pending changes. Reconnect and sync before making more changes." }
            if (write !in pending) persist(pending + write)
            drain(send)
            Unit
        }
    }
    suspend fun flush(token: String, send: suspend (String, SimklSyncBody) -> Unit): Boolean =
        withContext(com.torve.data.auth.capturePersonalDataScope(ids)) {
            mutex.withLock { forConnection(token); drain(send) }
        }
    private suspend fun drain(send: suspend (String, SimklSyncBody) -> Unit): Boolean {
        val remaining = read().toMutableList()
        while (remaining.isNotEmpty()) {
            val wait = 1_050L - (Clock.System.now().toEpochMilliseconds() - lastWriteAt)
            if (wait > 0) delay(wait)
            val next = remaining.first()
            try {
                lastWriteAt = Clock.System.now().toEpochMilliseconds()
                send(next.path, next.body)
                remaining.removeAt(0)
                persist(remaining)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return false
            }
        }
        return true
    }
    companion object {
        const val KEY = "simkl_pending_writes_v1"
        private const val OWNER = "mutation_queue_owner"
    }
}
