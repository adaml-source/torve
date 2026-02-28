package com.streamvault.presentation.stats

import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class StatsViewModel(
    private val watchHistoryRepo: WatchHistoryRepository,
    private val metadataRepo: MetadataRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()
    private val genreCacheByMediaKey = mutableMapOf<String, List<String>>()

    init {
        loadStats()
    }

    fun loadStats() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val history = watchHistoryRepo.getAll()

                val movies = history.count { it.mediaType.equals(MediaType.MOVIE.name, ignoreCase = true) }
                val episodes = history.count {
                    it.mediaType.equals(MediaType.SERIES.name, ignoreCase = true) || it.mediaType.equals("tv", ignoreCase = true)
                }
                val totalMinutes = history.sumOf { it.durationWatchedMs } / 60_000

                // Week / month watch time
                val now = Clock.System.now().toEpochMilliseconds()
                val weekAgo = now - 7 * 24 * 3600 * 1000L
                val monthAgo = now - 30 * 24 * 3600 * 1000L
                val thisWeekMinutes = history
                    .filter { it.watchedAt >= weekAgo }
                    .sumOf { it.durationWatchedMs } / 60_000
                val thisMonthMinutes = history
                    .filter { it.watchedAt >= monthAgo }
                    .sumOf { it.durationWatchedMs } / 60_000

                // Longest consecutive-day streak
                val longestStreak = computeLongestStreak(history.map { it.watchedAt })

                // Top genres from watched items (resolved from TMDB details and cached in-memory)
                val topGenres = computeTopGenres(history)

                // Watches per day of week
                val tz = TimeZone.currentSystemDefault()
                val activityByDay = history
                    .map { Instant.fromEpochMilliseconds(it.watchedAt).toLocalDateTime(tz).dayOfWeek.name }
                    .groupingBy { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                    .eachCount()

                _state.update {
                    it.copy(
                        isLoading = false,
                        totalMovies = movies,
                        totalEpisodes = episodes,
                        totalMinutes = totalMinutes,
                        thisWeekMinutes = thisWeekMinutes,
                        thisMonthMinutes = thisMonthMinutes,
                        longestStreak = longestStreak,
                        topGenres = topGenres,
                        activityByDay = activityByDay,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load stats") }
            }
        }
    }

    private suspend fun computeTopGenres(history: List<com.streamvault.domain.model.WatchHistoryEntry>): List<GenreStat> {
        if (history.isEmpty()) return emptyList()

        val counts = mutableMapOf<String, Int>()
        for (entry in history) {
            val tmdbId = entry.mediaId.toIntOrNull() ?: continue
            val type = entry.mediaType.toMetadataType() ?: continue
            val key = "$type:$tmdbId"

            val genres = genreCacheByMediaKey[key] ?: run {
                val resolved = runCatching {
                    metadataRepo.getDetail(type, tmdbId)
                        .genres
                        .map { it.name.trim() }
                        .filter { it.isNotEmpty() }
                }.getOrDefault(emptyList())
                genreCacheByMediaKey[key] = resolved
                resolved
            }

            genres.forEach { genre ->
                counts[genre] = (counts[genre] ?: 0) + 1
            }
        }

        return counts
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { GenreStat(name = it.key, count = it.value) }
    }

    private fun String.toMetadataType(): String? = when (lowercase()) {
        "movie" -> "movie"
        "tv", "series" -> "tv"
        else -> null
    }

    private fun computeLongestStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        val tz = TimeZone.currentSystemDefault()
        val days = timestamps
            .map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
            .distinct()
            .sorted()
        if (days.isEmpty()) return 0

        var longest = 1
        var current = 1
        for (i in 1 until days.size) {
            val diff = days[i].toEpochDays() - days[i - 1].toEpochDays()
            if (diff == 1) {
                current++
                if (current > longest) longest = current
            } else {
                current = 1
            }
        }
        return longest
    }
}
