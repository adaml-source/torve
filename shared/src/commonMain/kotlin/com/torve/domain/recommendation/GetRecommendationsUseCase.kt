package com.torve.domain.recommendation

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

class GetRecommendationsUseCase(
    private val watchProgressRepo: WatchProgressRepository,
    private val metadataRepo: MetadataRepository,
    private val historyRepo: WatchHistoryRepository? = null,
    private val watchlistRepo: WatchlistRepository? = null,
    private val prefsRepo: PreferencesRepository? = null,
    private val ids: UserIdProvider? = null,
) {
    private val feedbackLock = Mutex()
    private suspend fun <T> scoped(block: suspend () -> T): T = ids?.withPersonalDataScope(block) ?: block()
    private suspend fun rejected(): Set<String> = prefsRepo?.getString(FEEDBACK_KEY)?.let {
        runCatching { Json.decodeFromString<List<String>>(it).toSet() }.getOrNull()
    }.orEmpty()
    suspend fun dismiss(item: MediaItem) = scoped { feedbackLock.withLock {
        prefsRepo?.setString(FEEDBACK_KEY, Json.encodeToString((rejected() + item.recommendationKey()).toList().takeLast(500)))
    } }
    suspend fun resetFeedback() = scoped { feedbackLock.withLock { prefsRepo?.remove(FEEDBACK_KEY) } }
    suspend fun execute(limit: Int = 20): List<ScoredMediaItem> = scoped { executeScoped(limit) }
    private suspend fun executeScoped(limit: Int): List<ScoredMediaItem> {
        if (limit <= 0) return emptyList()
        val rejected = rejected()
        val allProgress = watchProgressRepo.getAllProgress()
        val history = historyRepo?.getAll().orEmpty().filterNot { it.isContentPlaceholder }
        val saved = watchlistRepo?.getAll().orEmpty()
        // Imported and manually marked history is just as useful as in-app playback.
        // Deduplicate episodes before limiting seeds, so one long-running show
        // cannot occupy the whole taste profile. Saved titles also solve cold start.
        val seeds = (history.map {
            RecommendationSeed(it.mediaId, MediaType.fromString(it.mediaType), it.watchedAt, false)
        } + allProgress.filter { !it.isContentPlaceholder && it.progressPercent > .25f }.map {
            RecommendationSeed(it.mediaId, it.mediaType, it.updatedAt, false)
        }).sortedByDescending { it.at }.distinctBy { it.key }.take(8) + saved
            .sortedByDescending { it.addedAt }.map {
                RecommendationSeed(it.tmdbId.takeIf { id -> id > 0 }?.toString() ?: it.mediaId, it.mediaType, it.addedAt, true)
            }.distinctBy { it.key }.take(2)
        if (seeds.isEmpty()) return emptyList()
        val limiter = Semaphore(4)
        val watchedItems = coroutineScope {
            seeds.distinctBy { it.key }.map { seed ->
                async { limiter.withPermit {
                    try {
                        val type = seed.type.metadataType()
                        val id = seed.mediaId.extractTmdbIdOrNull()
                        val item = if (id != null) metadataRepo.getDetail(type, id)
                            else if (seed.mediaId.startsWith("tt")) metadataRepo.findByImdbId(seed.mediaId, type)
                            else null
                        item?.takeUnless { it.isContentPlaceholder || it.isStubDetail }?.let { it to seed.saved }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                } }
            }.mapNotNull { it.await() }.distinctBy { it.first.recommendationKey() }
        }
        if (watchedItems.isEmpty()) return emptyList()
        val profiles = watchedItems.groupBy { it.first.type }.mapValues { (_, values) -> buildTasteProfile(values.map { it.first }) }
        val candidates = coroutineScope {
            val similarJobs = watchedItems.take(8).map { (item, savedSeed) -> async { limiter.withPermit {
                try {
                    val id = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@withPermit emptyList<Pair<MediaItem, String>>()
                    val type = item.type.metadataType()
                    val reason = "Because you ${if (savedSeed) "saved" else "watched"} ${item.title}"
                    (metadataRepo.getSimilar(type, id) + metadataRepo.getRecommendations(type, id)).map { it to reason }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    emptyList()
                }
            } } }
            val genreJobs = profiles.flatMap { (type, profile) ->
                profile.genreScores.entries.sortedByDescending { it.value }.take(2).map { (genreId, _) -> async { limiter.withPermit {
                    try {
                        metadataRepo.discover(type = type.metadataType(), withGenres = genreId.toString(),
                            minRating = (profile.avgRating - 1.0).coerceAtLeast(5.0).toFloat()).items.map {
                            it to generateReason(it, profile)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        emptyList()
                    }
                } } }
            }
            (similarJobs + genreJobs).flatMap { it.await() }
        }
        val excluded = (history.map { RecommendationSeed(it.mediaId, MediaType.fromString(it.mediaType), 0, false).key } +
            allProgress.map { RecommendationSeed(it.mediaId, it.mediaType, 0, false).key } +
            saved.map { RecommendationSeed(it.tmdbId.takeIf { id -> id > 0 }?.toString() ?: it.mediaId, it.mediaType, 0, true).key } +
            watchedItems.map { it.first.recommendationKey() }).toSet()
        return candidates.filterNot { (item, _) -> item.recommendationKey() in excluded || item.recommendationKey() in rejected ||
            item.imdbId?.let { "${item.type}:$it" in excluded } == true || item.isContentPlaceholder || item.isStubDetail }
            .distinctBy { it.first.recommendationKey() }.map { (item, reason) ->
                val profile = profiles[item.type] ?: buildTasteProfile(watchedItems.map { it.first })
                ScoredMediaItem(item, calculateScore(item, profile), reason)
            }.sortedByDescending { it.score }.take(limit)
    }

    private fun buildTasteProfile(items: List<MediaItem>): TasteProfile {
        val genreCounts = mutableMapOf<Int, Int>()
        val genreNames = mutableMapOf<Int, String>()
        val actorCounts = mutableMapOf<String, Int>()
        val ratings = mutableListOf<Double>()
        val runtimes = mutableListOf<Int>()

        items.forEach { item ->
            (item.genreIds + item.genres.map { it.id }).distinct().forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
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
        val genreOverlap = (item.genreIds + item.genres.map { it.id }).distinct().sumOf { profile.genreScores[it] ?: 0.0 }
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
    companion object { const val FEEDBACK_KEY = "recommendation_hidden_titles" }
}

private data class RecommendationSeed(val mediaId: String, val type: MediaType, val at: Long, val saved: Boolean) {
    val key: String get() = "$type:${mediaId.extractTmdbIdOrNull() ?: mediaId}"
}
private fun MediaType.metadataType() = if (this == MediaType.SERIES) "tv" else "movie"
private fun MediaItem.recommendationKey() = "$type:${tmdbId ?: id.extractTmdbIdOrNull() ?: id}"
