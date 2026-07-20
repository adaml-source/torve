package com.torve.domain.recommendation

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

class GetRecommendationsUseCase(
    private val watchProgressRepo: WatchProgressRepository,
    private val metadataRepo: MetadataRepository,
) {
    suspend fun execute(limit: Int = 20): List<ScoredMediaItem> {
        // 1. Get local watch history
        val allProgress = watchProgressRepo.getAllProgress()
        if (allProgress.isEmpty()) return emptyList()

        // Only consider items watched >25% — take most recent 10
        val significantProgress = allProgress
            .filter { it.durationMs > 0 && (it.positionMs.toDouble() / it.durationMs) > 0.25 }
            .sortedByDescending { it.updatedAt }
            .take(10)

        if (significantProgress.isEmpty()) return emptyList()

        // 2. Fetch full MediaItem details for watched items (for genre/cast data)
        val watchedItems = coroutineScope {
            significantProgress.map { progress ->
                async {
                    try {
                        val type = when (progress.mediaType) {
                            MediaType.SERIES -> "tv"
                            else -> "movie"
                        }
                        metadataRepo.getDetail(type, progress.mediaId.extractTmdbIdOrNull() ?: return@async null)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }

        if (watchedItems.isEmpty()) return emptyList()

        // 3. Build taste profile
        val profile = buildTasteProfile(watchedItems)

        // 4. Generate candidates from multiple sources in parallel
        val candidates = coroutineScope {
            val similarJobs = watchedItems.take(5)
                .filter { it.tmdbId != null }
                .map { item ->
                    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                    val id = item.tmdbId!!
                    async {
                        try {
                            metadataRepo.getSimilar(type, id) + metadataRepo.getRecommendations(type, id)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }

            val genreJobs = profile.genreScores.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { (genreId, _) ->
                    async {
                        try {
                            val minRating = (profile.avgRating - 1.0).coerceAtLeast(5.0).toFloat()
                            metadataRepo.discover(
                                type = "movie",
                                withGenres = genreId.toString(),
                                minRating = minRating,
                            ).items
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }

            val allCandidates = mutableSetOf<MediaItem>()
            similarJobs.forEach { allCandidates.addAll(it.await()) }
            genreJobs.forEach { allCandidates.addAll(it.await()) }
            allCandidates
        }

        // 5. Filter out already-watched items
        val watchedIds = allProgress.map { it.mediaId }.toSet()
        val filtered = candidates.filter { it.id !in watchedIds }

        // 6. Score, rank, deduplicate
        return filtered
            .map { ScoredMediaItem(it, calculateScore(it, profile), generateReason(it, profile)) }
            .distinctBy { it.item.id }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun buildTasteProfile(items: List<MediaItem>): TasteProfile {
        val genreCounts = mutableMapOf<Int, Int>()
        val genreNames = mutableMapOf<Int, String>()
        val actorCounts = mutableMapOf<String, Int>()
        val ratings = mutableListOf<Double>()
        val runtimes = mutableListOf<Int>()

        items.forEach { item ->
            item.genreIds.forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
            item.genres.forEach { g -> genreNames[g.id] = g.name }
            item.cast.take(3).forEach { a -> actorCounts[a.name] = (actorCounts[a.name] ?: 0) + 1 }
            item.rating?.let { ratings.add(it) }
            item.runtime?.let { runtimes.add(it) }
        }

        val total = items.size.toDouble().coerceAtLeast(1.0)
        return TasteProfile(
            genreScores = genreCounts.mapValues { it.value / total },
            genreNames = genreNames,
            actorScores = actorCounts.mapValues { it.value / total },
            avgRating = if (ratings.isNotEmpty()) ratings.average() else 6.0,
            avgRuntime = if (runtimes.isNotEmpty()) runtimes.average().toInt() else 120,
        )
    }

    private fun calculateScore(item: MediaItem, profile: TasteProfile): Double {
        var score = 0.0

        // Genre match (40%)
        val genreOverlap = item.genreIds.sumOf { profile.genreScores[it] ?: 0.0 }
        score += genreOverlap * 0.4

        // Rating quality (20%)
        score += ((item.rating ?: 5.0) / 10.0) * 0.2

        // Actor match (15%)
        val actorOverlap = item.cast.take(5).sumOf { profile.actorScores[it.name] ?: 0.0 }
        score += actorOverlap.coerceAtMost(1.0) * 0.15

        // Runtime preference (10%)
        val rtDiff = abs((item.runtime ?: 120) - profile.avgRuntime)
        score += (1.0 - (rtDiff / 120.0).coerceIn(0.0, 1.0)) * 0.1

        // Recency bonus (10%)
        val yearBonus = item.year?.let { ((it - 2000) / 25.0).coerceIn(0.0, 1.0) } ?: 0.5
        score += yearBonus * 0.1

        // Popularity (5%)
        val popBonus = when {
            (item.rating ?: 0.0) > 7.5 -> 1.0
            (item.rating ?: 0.0) > 6.0 -> 0.5
            else -> 0.2
        }
        score += popBonus * 0.05

        return score
    }

    private fun generateReason(item: MediaItem, profile: TasteProfile): String {
        val matchedActor = item.cast.firstOrNull { it.name in profile.actorScores }
        val topGenre = item.genreIds
            .maxByOrNull { profile.genreScores[it] ?: 0.0 }
            ?.let { profile.genreNames[it] ?: item.genres.find { g -> g.id == it }?.name }

        return when {
            matchedActor != null -> "Because you like ${matchedActor.name}"
            topGenre != null -> "Popular in $topGenre"
            (item.rating ?: 0.0) > 8.0 -> "Highly rated"
            else -> "Recommended for you"
        }
    }
}
