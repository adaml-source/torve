package com.torve.domain.transfer

import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistent replay-guard for [SecretsTransferProtocol]. Records every
 * `transferNonce` that's been successfully applied so a relay-replay
 * (or local re-import of the same envelope) is rejected.
 *
 * Retention: entries are pruned 30 days after they're recorded — longer
 * than any reasonable envelope TTL so replay protection survives well
 * past the original valid window, while keeping the prefs row from
 * growing unbounded.
 */
class ConsumedNonceStore(
    private val prefs: PreferencesRepository,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {
    private val mutex = Mutex()
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    /**
     * @return true iff [nonce] has been recorded as consumed AND its
     * record is still within the retention window. Side-effect: prunes
     * any entries older than [retentionMs] from the underlying store.
     */
    suspend fun isConsumed(nonce: String): Boolean = mutex.withLock {
        val pruned = pruneLocked(loadLocked())
        pruned.any { it.nonce == nonce }
    }

    /**
     * Record [nonce] as consumed. Caller is responsible for ordering:
     * mark consumed only AFTER the secrets have been applied
     * successfully (or after rollback failure when the safer course is
     * to refuse the same envelope a second time).
     */
    suspend fun markConsumed(nonce: String) = mutex.withLock {
        val pruned = pruneLocked(loadLocked())
        if (pruned.any { it.nonce == nonce }) return@withLock
        val updated = pruned + Entry(nonce, nowMs())
        persistLocked(updated)
    }

    /**
     * Public hook for test-only scenarios. Production uses the lazy
     * pruning that runs inside [isConsumed] / [markConsumed].
     */
    suspend fun pruneNow() = mutex.withLock {
        val pruned = pruneLocked(loadLocked())
        persistLocked(pruned)
    }

    private suspend fun loadLocked(): List<Entry> {
        val raw = runCatching { prefs.getString(KEY) }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun pruneLocked(entries: List<Entry>): List<Entry> {
        val cutoff = nowMs() - retentionMs
        return entries.filter { it.consumedAtEpochMs >= cutoff }
    }

    private suspend fun persistLocked(entries: List<Entry>) {
        runCatching { prefs.setString(KEY, json.encodeToString(serializer, entries)) }
    }

    @Serializable
    private data class Entry(
        val nonce: String,
        val consumedAtEpochMs: Long,
    )

    companion object {
        const val KEY = "transfer_consumed_nonces_v1"
        const val DEFAULT_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
