package com.torve.data.sourceavailability

import com.torve.domain.model.MediaType
import com.torve.domain.sourceavailability.SourceAvailabilityAggregator
import com.torve.domain.sourceavailability.SourceAvailabilityProvider
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Fans out a probe to every [SourceAvailabilityProvider] in parallel and
 * collects their non-null results. A small in-process cache (keyed by
 * `(tmdbId, mediaType)`) keeps repeated UI re-renders cheap; the cache
 * is short-lived by design (process-scoped, no TTL beyond app lifetime)
 * because the underlying services already cache where appropriate.
 */
class DefaultSourceAvailabilityAggregator(
    private val providers: List<SourceAvailabilityProvider>,
) : SourceAvailabilityAggregator {

    private val cache = mutableMapOf<Pair<Int, MediaType>, SourceAvailabilityRecord>()

    override suspend fun lookup(tmdbId: Int, mediaType: MediaType): SourceAvailabilityRecord {
        val key = tmdbId to mediaType
        cached(key)?.let { return it }
        return coroutineScope {
            val deferred = providers.map { provider ->
                async { runCatching { provider.probe(tmdbId, mediaType) }.getOrNull() }
            }
            val signals = deferred.mapNotNull { it.await() }
            val record = SourceAvailabilityRecord(
                tmdbId = tmdbId,
                mediaType = mediaType,
                signals = signals,
            )
            store(key, record)
            record
        }
    }

    override suspend fun lookupBatch(
        items: List<Pair<Int, MediaType>>,
    ): Map<Int, SourceAvailabilityRecord> = coroutineScope {
        val deferred = items.distinct().map { (tmdbId, mediaType) ->
            async { lookup(tmdbId, mediaType) }
        }
        deferred.associate { d ->
            val record = d.await()
            record.tmdbId to record
        }
    }

    private fun cached(key: Pair<Int, MediaType>): SourceAvailabilityRecord? =
        cache[key]

    private fun store(key: Pair<Int, MediaType>, record: SourceAvailabilityRecord) {
        cache[key] = record
    }

    /** Test-only / sign-out hook. */
    fun invalidate() {
        cache.clear()
    }
}
