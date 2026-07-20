package com.torve.domain.recommendation

import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository

enum class Mood(
    val label: String,
    val emoji: String,
    val movieGenreIds: List<Int>,
    val tvGenreIds: List<Int>,
    val sortBy: String = "popularity.desc",
    val minRating: Float? = null,
) {
    FUN(
        label = "Fun & Light",
        emoji = "😄",
        movieGenreIds = listOf(35, 16, 10751), // Comedy, Animation, Family
        tvGenreIds = listOf(35, 16, 10751),
    ),
    MIND_BENDING(
        label = "Mind-Bending",
        emoji = "🧠",
        movieGenreIds = listOf(878, 9648, 53), // Sci-Fi, Mystery, Thriller
        tvGenreIds = listOf(10765, 9648),       // Sci-Fi & Fantasy, Mystery
        minRating = 7.0f,
    ),
    EDGE_OF_SEAT(
        label = "Edge of Seat",
        emoji = "😰",
        movieGenreIds = listOf(28, 53, 80), // Action, Thriller, Crime
        tvGenreIds = listOf(10759, 80),     // Action & Adventure, Crime
    ),
    FEEL_GOOD(
        label = "Feel Good",
        emoji = "🥰",
        movieGenreIds = listOf(10749, 35, 10402), // Romance, Comedy, Music
        tvGenreIds = listOf(10749, 35),
        sortBy = "vote_average.desc",
        minRating = 7.0f,
    ),
    DARK_GRITTY(
        label = "Dark & Gritty",
        emoji = "🌑",
        movieGenreIds = listOf(27, 53, 80, 10752), // Horror, Thriller, Crime, War
        tvGenreIds = listOf(80, 10759),
    ),
    FAMILY(
        label = "Family Night",
        emoji = "👨‍👩‍👧‍👦",
        movieGenreIds = listOf(10751, 16, 12), // Family, Animation, Adventure
        tvGenreIds = listOf(10751, 16, 10762), // Family, Animation, Kids
        minRating = 6.5f,
    ),
}

data class MoodResult(
    val item: MediaItem,
    val reason: String,
)

class MoodMatcher(
    private val metadataRepo: MetadataRepository,
) {
    suspend fun getRecommendations(
        mood: Mood,
        includeMovies: Boolean = true,
        includeTv: Boolean = true,
        maxResults: Int = 10,
    ): List<MoodResult> {
        val results = mutableListOf<MoodResult>()

        if (includeMovies && mood.movieGenreIds.isNotEmpty()) {
            try {
                val movies = metadataRepo.discover(
                    type = "movie",
                    sortBy = mood.sortBy,
                    withGenres = mood.movieGenreIds.joinToString(","),
                    minRating = mood.minRating,
                ).items.take(maxResults)
                results.addAll(movies.map { MoodResult(it, "${mood.label} movie pick") })
            } catch (_: Exception) { }
        }

        if (includeTv && mood.tvGenreIds.isNotEmpty()) {
            try {
                val shows = metadataRepo.discover(
                    type = "tv",
                    sortBy = mood.sortBy,
                    withGenres = mood.tvGenreIds.joinToString(","),
                    minRating = mood.minRating,
                ).items.take(maxResults)
                results.addAll(shows.map { MoodResult(it, "${mood.label} TV pick") })
            } catch (_: Exception) { }
        }

        return results
            .distinctBy { it.item.id }
            .shuffled()
            .take(maxResults)
    }
}
