package com.torve.domain.sourceavailability

import com.torve.domain.model.MediaType

/**
 * One pluggable per-source probe. Implementations wrap an existing
 * service (DownloadRepository, PlexLibraryOverlayService, …) and answer
 * "does the user have THIS item via THIS source right now?".
 *
 * Implementations MUST never throw — return null on any error so the
 * aggregator simply treats this source as silent for that item.
 */
interface SourceAvailabilityProvider {
    val kind: SourceAvailabilityKind
    suspend fun probe(tmdbId: Int, mediaType: MediaType): SourceAvailabilitySignal?
}

/**
 * Fans out to every registered [SourceAvailabilityProvider] and merges
 * the resulting signals into one [SourceAvailabilityRecord] per item.
 */
interface SourceAvailabilityAggregator {
    suspend fun lookup(tmdbId: Int, mediaType: MediaType): SourceAvailabilityRecord
    suspend fun lookupBatch(items: List<Pair<Int, MediaType>>): Map<Int, SourceAvailabilityRecord>
}
