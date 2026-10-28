package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMoreLikeRecommendationsTest {
    @Test
    fun recommendationsAndSimilarTitlesAreCombinedWithoutSeedOrDuplicates() {
        val seed = media(10, "Seed")
        val recommendation = media(20, "Recommendation")
        val similar = media(30, "Similar")

        val result = mergeMoreLikeCandidates(
            seedTmdbId = 10,
            recommendations = listOf(seed, recommendation),
            similar = listOf(recommendation.copy(title = "Duplicate"), similar),
        )

        assertEquals(listOf(20, 30), result.map { it.tmdbId })
        assertEquals("Recommendation", result.first().title)
        assertFalse(result.any { it.tmdbId == 10 })
        assertTrue(result.all { it.tmdbId != null })
    }

    private fun media(tmdbId: Int, title: String) = MediaItem(
        id = tmdbId.toString(),
        tmdbId = tmdbId,
        type = MediaType.MOVIE,
        title = title,
        posterUrl = "/$tmdbId.jpg",
    )
}
