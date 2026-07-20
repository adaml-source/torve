package com.torve.data.download

import com.torve.domain.model.CatalogueFilterType
import com.torve.domain.model.CatalogueGrouping
import com.torve.domain.model.CatalogueSection
import com.torve.domain.model.CatalogueSortBy
import com.torve.domain.model.CatalogueState
import com.torve.domain.model.CatalogueWatchFilter
import com.torve.domain.model.DownloadCataloguePrefs
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.DownloadMediaType
import com.torve.domain.model.DownloadSeason
import com.torve.domain.model.DownloadedItem
import kotlinx.datetime.Clock

class DownloadCatalogueBuilder {

    fun buildCatalogue(
        downloads: List<DownloadedItem>,
        prefs: DownloadCataloguePrefs,
    ): CatalogueState {
        if (downloads.isEmpty()) return CatalogueState(isEmpty = true)

        // Step 1: Build groups (movies 1:1, episodes grouped by show)
        val allGroups = buildGroups(downloads)

        // Step 2: Filter
        val filtered = applyFilters(allGroups, prefs)

        // Step 3: Sort
        val sorted = applySorting(filtered, prefs)

        // Step 4: Group into sections
        val sections = applyGrouping(sorted, prefs)

        // Step 5: Special sections
        val specialSections = buildSpecialSections(allGroups, prefs)

        // Step 6: Stats
        val movieCount = allGroups.count { it.type == DownloadGroupType.MOVIE }
        val showCount = allGroups.count { it.type == DownloadGroupType.SHOW }
        val episodeCount = downloads.count { it.type == DownloadMediaType.EPISODE }

        return CatalogueState(
            isEmpty = false,
            specialSections = specialSections,
            sections = sections,
            totalSizeBytes = downloads.sumOf { it.fileSizeBytes },
            movieCount = movieCount,
            showCount = showCount,
            episodeCount = episodeCount,
            totalItemCount = allGroups.size,
            availableGenres = allGroups.flatMap { it.genres ?: emptyList() }.distinct().sorted(),
            availableQualities = downloads.mapNotNull { it.resolution }.distinct()
                .sortedByDescending { resolutionRank(it) },
        )
    }

    private fun buildGroups(downloads: List<DownloadedItem>): List<DownloadGroup> {
        val movies = downloads.filter { it.type == DownloadMediaType.MOVIE }
        val episodes = downloads.filter { it.type == DownloadMediaType.EPISODE }

        val movieGroups = movies.map { movie ->
            DownloadGroup(
                mediaId = movie.mediaId,
                title = movie.title,
                type = DownloadGroupType.MOVIE,
                posterUrl = movie.posterUrl,
                backdropUrl = movie.backdropUrl,
                year = movie.year,
                genres = movie.genres,
                imdbRating = movie.imdbRating,
                contentRating = movie.contentRating,
                totalSizeBytes = movie.fileSizeBytes,
                itemCount = 1,
                totalRuntime = movie.runtime,
                movie = movie,
                watchedCount = if (movie.isWatched) 1 else 0,
                totalCount = 1,
                overallProgress = movie.watchProgress,
                latestDownloadAt = movie.downloadedAt,
                latestWatchedAt = movie.lastWatchedAt,
            )
        }

        val showGroups = episodes
            .groupBy { it.mediaId }
            .map { (mediaId, showEpisodes) ->
                val representative = showEpisodes.first()
                val seasons = showEpisodes
                    .groupBy { it.seasonNumber ?: 0 }
                    .map { (seasonNum, seasonEpisodes) ->
                        val sorted = seasonEpisodes.sortedBy { it.episodeNumber ?: 0 }
                        DownloadSeason(
                            seasonNumber = seasonNum,
                            episodes = sorted,
                            totalSizeBytes = sorted.sumOf { it.fileSizeBytes },
                            watchedCount = sorted.count { it.isWatched },
                        )
                    }
                    .sortedBy { it.seasonNumber }

                DownloadGroup(
                    mediaId = mediaId,
                    title = representative.title,
                    type = DownloadGroupType.SHOW,
                    posterUrl = representative.posterUrl,
                    backdropUrl = representative.backdropUrl,
                    year = representative.year,
                    genres = representative.genres,
                    imdbRating = representative.imdbRating,
                    contentRating = representative.contentRating,
                    totalSizeBytes = showEpisodes.sumOf { it.fileSizeBytes },
                    itemCount = showEpisodes.size,
                    totalRuntime = showEpisodes.mapNotNull { it.runtime }.sum().takeIf { it > 0 },
                    seasons = seasons,
                    watchedCount = showEpisodes.count { it.isWatched },
                    totalCount = showEpisodes.size,
                    overallProgress = showEpisodes.map { it.watchProgress.toDouble() }.average().toFloat(),
                    latestDownloadAt = showEpisodes.maxOf { it.downloadedAt },
                    latestWatchedAt = showEpisodes.mapNotNull { it.lastWatchedAt }.maxOrNull(),
                )
            }

        return movieGroups + showGroups
    }

    private fun applyFilters(
        groups: List<DownloadGroup>,
        prefs: DownloadCataloguePrefs,
    ): List<DownloadGroup> {
        return groups
            .filter { group ->
                when (prefs.filterType) {
                    CatalogueFilterType.ALL -> true
                    CatalogueFilterType.MOVIES_ONLY -> group.type == DownloadGroupType.MOVIE
                    CatalogueFilterType.SHOWS_ONLY -> group.type == DownloadGroupType.SHOW
                }
            }
            .filter { group ->
                when (prefs.filterWatchState) {
                    CatalogueWatchFilter.ALL -> true
                    CatalogueWatchFilter.UNWATCHED -> group.watchedCount == 0 && group.overallProgress == 0f
                    CatalogueWatchFilter.IN_PROGRESS -> group.overallProgress > 0f && group.watchedCount < group.totalCount
                    CatalogueWatchFilter.WATCHED -> group.watchedCount == group.totalCount
                }
            }
            .filter { group ->
                prefs.filterGenre?.let { genre ->
                    group.genres?.any { it.equals(genre, ignoreCase = true) } == true
                } ?: true
            }
            .filter { group ->
                prefs.filterQuality?.let { quality ->
                    when (group.type) {
                        DownloadGroupType.MOVIE -> group.movie?.resolution == quality
                        DownloadGroupType.SHOW -> group.seasons?.any { season ->
                            season.episodes.any { it.resolution == quality }
                        } == true
                    }
                } ?: true
            }
    }

    private fun applySorting(
        groups: List<DownloadGroup>,
        prefs: DownloadCataloguePrefs,
    ): List<DownloadGroup> {
        val sorted = when (prefs.sortBy) {
            CatalogueSortBy.RECENTLY_DOWNLOADED -> groups.sortedByDescending { it.latestDownloadAt }
            CatalogueSortBy.RECENTLY_WATCHED -> groups.sortedByDescending { it.latestWatchedAt ?: 0L }
            CatalogueSortBy.TITLE_AZ -> groups.sortedBy { it.title.lowercase() }
            CatalogueSortBy.TITLE_ZA -> groups.sortedByDescending { it.title.lowercase() }
            CatalogueSortBy.YEAR_NEWEST -> groups.sortedByDescending { it.year ?: 0 }
            CatalogueSortBy.YEAR_OLDEST -> groups.sortedBy { it.year ?: 9999 }
            CatalogueSortBy.RATING_HIGHEST -> groups.sortedByDescending { it.imdbRating ?: 0f }
            CatalogueSortBy.RATING_LOWEST -> groups.sortedBy { it.imdbRating ?: 10f }
            CatalogueSortBy.FILE_SIZE_LARGEST -> groups.sortedByDescending { it.totalSizeBytes }
            CatalogueSortBy.FILE_SIZE_SMALLEST -> groups.sortedBy { it.totalSizeBytes }
            CatalogueSortBy.EPISODE_COUNT -> groups.sortedByDescending { it.itemCount }
            CatalogueSortBy.WATCH_PROGRESS -> groups.sortedByDescending { it.overallProgress }
        }
        return if (prefs.sortAscending) sorted.reversed() else sorted
    }

    private fun applyGrouping(
        groups: List<DownloadGroup>,
        prefs: DownloadCataloguePrefs,
    ): List<CatalogueSection> {
        return when (prefs.groupingMode) {
            CatalogueGrouping.NONE -> listOf(CatalogueSection("All Downloads", groups))

            CatalogueGrouping.BY_TYPE -> buildList {
                val movies = groups.filter { it.type == DownloadGroupType.MOVIE }
                val shows = groups.filter { it.type == DownloadGroupType.SHOW }
                if (movies.isNotEmpty()) add(CatalogueSection("Movies (${movies.size})", movies))
                if (shows.isNotEmpty()) add(CatalogueSection("TV Shows (${shows.size})", shows))
            }

            CatalogueGrouping.BY_GENRE -> {
                groups
                    .flatMap { group ->
                        (group.genres ?: listOf("Unknown")).map { genre -> genre to group }
                    }
                    .groupBy({ it.first }, { it.second })
                    .map { (genre, items) -> CatalogueSection(genre, items.distinct()) }
                    .sortedByDescending { it.items.size }
            }

            CatalogueGrouping.BY_YEAR -> {
                groups
                    .groupBy { it.year ?: 0 }
                    .toList()
                    .sortedByDescending { (year, _) -> year }
                    .map { (year, items) ->
                        CatalogueSection(if (year > 0) "$year" else "Unknown Year", items)
                    }
            }

            CatalogueGrouping.BY_QUALITY -> {
                groups
                    .groupBy { group ->
                        when (group.type) {
                            DownloadGroupType.MOVIE -> group.movie?.resolution ?: "Unknown"
                            DownloadGroupType.SHOW -> group.seasons
                                ?.flatMap { it.episodes }
                                ?.mapNotNull { it.resolution }
                                ?.maxByOrNull { resolutionRank(it) }
                                ?: "Unknown"
                        }
                    }
                    .toList()
                    .sortedByDescending { (quality, _) -> resolutionRank(quality) }
                    .map { (quality, items) -> CatalogueSection(quality, items) }
            }

            CatalogueGrouping.BY_SOURCE -> {
                groups
                    .groupBy { group ->
                        when (group.type) {
                            DownloadGroupType.MOVIE -> group.movie?.downloadSource ?: "Unknown"
                            DownloadGroupType.SHOW -> group.seasons
                                ?.flatMap { it.episodes }
                                ?.firstOrNull()?.downloadSource ?: "Unknown"
                        }
                    }
                    .map { (source, items) -> CatalogueSection(source, items) }
                    .sortedByDescending { it.items.size }
            }

            CatalogueGrouping.BY_DOWNLOAD_DATE -> {
                val nowMs = Clock.System.now().toEpochMilliseconds()
                val dayMs = 86_400_000L
                groups
                    .groupBy { group ->
                        val daysAgo = (nowMs - group.latestDownloadAt) / dayMs
                        when {
                            daysAgo < 1 -> "Today"
                            daysAgo < 2 -> "Yesterday"
                            daysAgo < 7 -> "This Week"
                            daysAgo < 30 -> "This Month"
                            daysAgo < 90 -> "Last 3 Months"
                            else -> "Older"
                        }
                    }
                    .toList()
                    .sortedBy { (label, _) ->
                        listOf("Today", "Yesterday", "This Week", "This Month", "Last 3 Months", "Older")
                            .indexOf(label)
                    }
                    .map { (label, items) -> CatalogueSection(label, items) }
            }
        }
    }

    private fun buildSpecialSections(
        allGroups: List<DownloadGroup>,
        prefs: DownloadCataloguePrefs,
    ): List<CatalogueSection> {
        return buildList {
            // Continue Watching
            if (prefs.showContinueWatchingSection) {
                val continueWatching = allGroups
                    .filter { it.overallProgress > 0f && it.watchedCount < it.totalCount }
                    .sortedByDescending { it.latestWatchedAt ?: 0L }
                if (continueWatching.isNotEmpty()) {
                    add(CatalogueSection("Continue Watching", continueWatching))
                }
            }

            // Recently Added (last 24h)
            if (prefs.showRecentlyAddedSection) {
                val oneDayAgo = Clock.System.now().toEpochMilliseconds() - 86_400_000L
                val recentlyAdded = allGroups
                    .filter { it.latestDownloadAt > oneDayAgo }
                    .sortedByDescending { it.latestDownloadAt }
                if (recentlyAdded.isNotEmpty()) {
                    add(CatalogueSection("Recently Added", recentlyAdded))
                }
            }
        }
    }

    companion object {
        fun resolutionRank(res: String): Int = when (res) {
            "4K", "2160p" -> 4
            "1080p" -> 3
            "720p" -> 2
            "480p" -> 1
            else -> 0
        }
    }
}
