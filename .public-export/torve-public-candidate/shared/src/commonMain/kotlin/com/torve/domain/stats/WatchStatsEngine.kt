package com.torve.domain.stats

import com.torve.domain.model.MediaType

class WatchStatsEngine {
    fun aggregate(
        sessions: List<WatchSession>,
        filters: WatchStatsFilters = WatchStatsFilters(),
        metadataBySessionId: Map<String, WatchStatsMetadata> = emptyMap(),
    ): WatchStatsSummary {
        val filtered = sessions
            .asSequence()
            .filter { session -> filters.matches(session, metadataBySessionId[session.id]) }
            .sortedByDescending { it.startedAt }
            .toList()

        val measuredWatchMs = filtered
            .filter { it.runtimeConfidence == RuntimeConfidence.MEASURED }
            .sumOf { it.countedWatchMs.coerceAtLeast(0L) }
        val estimatedWatchMs = filtered
            .filter { it.runtimeConfidence == RuntimeConfidence.ESTIMATED }
            .sumOf { it.countedWatchMs.coerceAtLeast(0L) }
        val partialWatchMs = filtered
            .filter { it.status == WatchSessionStatus.PARTIAL }
            .sumOf { it.countedTimeForTotals() }

        val completed = filtered.filter { it.isCompletedForStats() }
        val sourceBreakdown = WatchSessionSource.entries.map { source ->
            val sourceSessions = filtered.filter { it.source == source }
            WatchStatsSourceBreakdown(
                source = source,
                sessionCount = sourceSessions.size,
                countedWatchMs = sourceSessions.sumOf { it.countedTimeForTotals() },
            )
        }
        val confidenceBreakdown = RuntimeConfidence.entries.map { confidence ->
            val confidenceSessions = filtered.filter { it.runtimeConfidence == confidence }
            WatchStatsRuntimeConfidenceBreakdown(
                confidence = confidence,
                sessionCount = confidenceSessions.size,
                countedWatchMs = confidenceSessions.sumOf { it.countedTimeForTotals() },
            )
        }

        return WatchStatsSummary(
            totalWatchMs = measuredWatchMs + estimatedWatchMs,
            measuredWatchMs = measuredWatchMs,
            estimatedWatchMs = estimatedWatchMs,
            partialWatchMs = partialWatchMs,
            completedMovies = completed.count { it.mediaType == MediaType.MOVIE },
            completedEpisodes = completed.count { it.isEpisode },
            startedCount = filtered.count { it.status == WatchSessionStatus.STARTED },
            partialCount = filtered.count { it.status == WatchSessionStatus.PARTIAL },
            abandonedCount = filtered.count { it.status == WatchSessionStatus.ABANDONED },
            manualCompletedCount = filtered.count { it.status == WatchSessionStatus.MANUAL_COMPLETED },
            importedCompletedCount = filtered.count { it.status == WatchSessionStatus.IMPORTED_COMPLETED },
            sourceBreakdown = sourceBreakdown,
            runtimeConfidenceBreakdown = confidenceBreakdown,
            recentActivity = filtered.take(50),
            hasLegacyUnknownRuntime = filtered.any {
                it.source == WatchSessionSource.MIGRATED_HISTORY &&
                    it.runtimeConfidence == RuntimeConfidence.UNKNOWN
            },
            advanced = buildAdvancedSummary(filtered, metadataBySessionId),
        )
    }

    private fun buildAdvancedSummary(
        sessions: List<WatchSession>,
        metadataBySessionId: Map<String, WatchStatsMetadata>,
    ): WatchStatsAdvancedSummary {
        if (metadataBySessionId.isEmpty()) return WatchStatsAdvancedSummary()
        val pairs = sessions.mapNotNull { session ->
            metadataBySessionId[session.id]?.takeIf { it.hasAnyMetadata }?.let { metadata -> session to metadata }
        }
        if (pairs.isEmpty()) return WatchStatsAdvancedSummary()

        val availability = WatchStatsAdvancedAvailability(
            hasGenres = pairs.any { it.second.genres.any(String::isNotBlank) },
            hasYears = pairs.any { it.second.year != null },
            hasRatings = pairs.any { it.second.ratingBand() != null },
            hasActors = pairs.any { it.second.actors.any(String::isNotBlank) },
            hasDirectors = pairs.any { it.second.directors.any(String::isNotBlank) },
            hasStudios = pairs.any { it.second.studios.any(String::isNotBlank) },
            hasNetworks = pairs.any { it.second.networks.any(String::isNotBlank) },
            hasProviders = pairs.any { it.second.providers.any(String::isNotBlank) },
            hasCertifications = pairs.any { !it.second.certification.isNullOrBlank() },
        )

        return WatchStatsAdvancedSummary(
            availability = availability,
            genreGroups = buildStringGroups(WatchStatsAdvancedSection.GENRE, pairs) { it.genres },
            yearGroups = buildYearGroups(pairs),
            decadeGroups = buildDecadeGroups(pairs),
            ratingDistribution = buildRatingGroups(pairs),
            actorGroups = buildStringGroups(WatchStatsAdvancedSection.ACTOR, pairs) { it.actors },
            directorGroups = buildStringGroups(WatchStatsAdvancedSection.DIRECTOR, pairs) { it.directors },
            studioGroups = buildStringGroups(WatchStatsAdvancedSection.STUDIO, pairs) { it.studios },
            networkGroups = buildStringGroups(WatchStatsAdvancedSection.NETWORK, pairs) { it.networks },
            providerGroups = buildStringGroups(WatchStatsAdvancedSection.PROVIDER, pairs) { it.providers },
            certificationGroups = buildStringGroups(WatchStatsAdvancedSection.CERTIFICATION, pairs) {
                listOfNotNull(it.certification)
            },
        )
    }

    private fun buildStringGroups(
        section: WatchStatsAdvancedSection,
        pairs: List<Pair<WatchSession, WatchStatsMetadata>>,
        values: (WatchStatsMetadata) -> List<String>,
    ): List<WatchStatsAdvancedGroup> {
        val grouped = linkedMapOf<String, MutableList<WatchSession>>()
        val labels = linkedMapOf<String, String>()
        pairs.forEach { (session, metadata) ->
            values(metadata)
                .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .distinctBy { it.lowercase() }
                .forEach { label ->
                    val key = label.lowercase()
                    if (key !in labels) labels[key] = label
                    grouped.getOrPut(key) { mutableListOf() }.add(session)
                }
        }
        return grouped.map { (key, groupSessions) ->
            buildAdvancedGroup(
                section = section,
                key = key,
                label = labels[key] ?: key,
                sessions = groupSessions,
            )
        }.sortedWith(
            compareByDescending<WatchStatsAdvancedGroup> { it.attributedWatchMs }
                .thenByDescending { it.sessionCount }
                .thenBy { it.label },
        )
    }

    private fun buildYearGroups(
        pairs: List<Pair<WatchSession, WatchStatsMetadata>>,
    ): List<WatchStatsAdvancedGroup> {
        val grouped = pairs
            .mapNotNull { (session, metadata) -> metadata.year?.let { it to session } }
            .groupBy({ it.first }, { it.second })
        return grouped.map { (year, groupSessions) ->
            buildAdvancedGroup(
                section = WatchStatsAdvancedSection.YEAR,
                key = year.toString(),
                label = year.toString(),
                sessions = groupSessions,
            )
        }.sortedByDescending { it.key.toIntOrNull() ?: 0 }
    }

    private fun buildDecadeGroups(
        pairs: List<Pair<WatchSession, WatchStatsMetadata>>,
    ): List<WatchStatsAdvancedGroup> {
        val grouped = pairs
            .mapNotNull { (session, metadata) -> metadata.year?.toDecade()?.let { it to session } }
            .groupBy({ it.first }, { it.second })
        return grouped.map { (decade, groupSessions) ->
            buildAdvancedGroup(
                section = WatchStatsAdvancedSection.DECADE,
                key = decade.toString(),
                label = "${decade}s",
                sessions = groupSessions,
            )
        }.sortedByDescending { it.key.toIntOrNull() ?: 0 }
    }

    private fun buildRatingGroups(
        pairs: List<Pair<WatchSession, WatchStatsMetadata>>,
    ): List<WatchStatsRatingGroup> {
        val grouped = pairs
            .mapNotNull { (session, metadata) -> metadata.ratingBand()?.let { it to session } }
            .groupBy({ it.first }, { it.second })
        return WatchStatsRatingBand.entries.mapNotNull { band ->
            val groupSessions = grouped[band].orEmpty()
            if (groupSessions.isEmpty()) {
                null
            } else {
                WatchStatsRatingGroup(
                    band = band,
                    sessionCount = groupSessions.size,
                    titleCount = groupSessions.distinctBy { it.statsContentKey() }.size,
                    attributedWatchMs = groupSessions.sumOf { it.countedTimeForTotals() },
                    recentActivity = groupSessions.sortedByDescending { it.startedAt }.take(8),
                )
            }
        }
    }

    private fun buildAdvancedGroup(
        section: WatchStatsAdvancedSection,
        key: String,
        label: String,
        sessions: List<WatchSession>,
    ): WatchStatsAdvancedGroup {
        val distinctContent = sessions.distinctBy { it.statsContentKey() }
        return WatchStatsAdvancedGroup(
            section = section,
            key = key,
            label = label,
            sessionCount = sessions.size,
            titleCount = distinctContent.size,
            completedCount = distinctContent.count { it.isCompletedForStats() },
            partialCount = distinctContent.count { it.status == WatchSessionStatus.PARTIAL },
            attributedWatchMs = sessions.sumOf { it.countedTimeForTotals() },
            recentActivity = sessions.sortedByDescending { it.startedAt }.take(8),
        )
    }

    private fun WatchSession.statsContentKey(): String {
        return if (isEpisode) {
            "${showId ?: mediaId}:s${seasonNumber}:e${episodeNumber}"
        } else {
            "${mediaType.name}:${tmdbId ?: imdbId ?: mediaId}"
        }
    }
}
