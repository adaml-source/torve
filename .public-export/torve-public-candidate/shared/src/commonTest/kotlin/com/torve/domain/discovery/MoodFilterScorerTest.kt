package com.torve.domain.discovery

import com.torve.domain.model.Genre
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoodFilterScorerTest {
    private val scorer = MoodFilterScorer()

    @Test
    fun moodClickDoesNotMutateSearchQueryOrEnableAi() {
        val next = applyMoodChipClick(
            MoodSelectionState(
                searchQuery = "Tom Hanks",
                isAiSearchEnabled = false,
                selectedMoodId = "all",
            ),
            clickedMoodId = "date",
        )

        assertEquals("Tom Hanks", next.searchQuery)
        assertFalse(next.isAiSearchEnabled)
        assertEquals("date", next.selectedMoodId)
    }

    @Test
    fun repeatedMoodClickClearsToAllMoods() {
        val next = applyMoodChipClick(
            MoodSelectionState(selectedMoodId = "hidden"),
            clickedMoodId = "hidden",
        )

        assertEquals("all", next.selectedMoodId)
    }

    @Test
    fun genreAndHiddenGemsCombineWithAndSemantics() {
        val documentaryHidden = item(
            title = "Quiet Documentary",
            type = MediaType.SERIES,
            rating = 7.6,
            voteCount = 350,
            popularity = 25.0,
            genres = listOf(Genre(99, "Documentary")),
        )
        val documentaryTooMainstream = item(
            title = "Mainstream Documentary",
            type = MediaType.SERIES,
            rating = 8.4,
            voteCount = 50_000,
            popularity = 220.0,
            genres = listOf(Genre(99, "Documentary")),
        )
        val nonDocumentaryHidden = item(
            title = "Quiet Drama",
            type = MediaType.SERIES,
            rating = 7.7,
            voteCount = 400,
            popularity = 22.0,
            genres = listOf(Genre(18, "Drama")),
        )

        val explicitGenreCandidates = listOf(documentaryHidden, documentaryTooMainstream, nonDocumentaryHidden)
            .filter { item -> item.genres.any { it.name == "Documentary" } }
        val filtered = explicitGenreCandidates.applyMoodFilter("hidden")

        assertEquals(listOf(documentaryHidden), filtered)
        assertTrue(filtered.all { item -> item.genres.any { it.name == "Documentary" } })
    }

    @Test
    fun breakingBadIsExcludedFromHiddenGemsButAllowedForCriticallyAcclaimed() {
        val breakingBad = item(
            title = "Breaking Bad",
            type = MediaType.SERIES,
            rating = 9.4,
            voteCount = 120_000,
            popularity = 240.0,
            genres = listOf(Genre(18, "Drama"), Genre(80, "Crime")),
        )
        val stats = CandidateStats.from(
            listOf(
                breakingBad,
                item("Quiet Drama", MediaType.SERIES, 7.7, 450, 24.0),
                item("Small Comedy", MediaType.SERIES, 7.2, 300, 18.0),
            ),
        )

        assertFalse(scorer.score(breakingBad, MoodFilter.HiddenGems, stats).matches)
        assertTrue(scorer.score(breakingBad, MoodFilter.CriticallyAcclaimed, stats).matches)
    }

    @Test
    fun hiddenGemsIncludesUnderseenQualityItem() {
        val hidden = item("Underseen Quality", MediaType.MOVIE, 7.6, 500, 28.0)
        val stats = CandidateStats.from(
            listOf(
                hidden,
                item("Blockbuster", MediaType.MOVIE, 7.8, 30_000, 210.0),
                item("Obscure No Signal", MediaType.MOVIE, 8.2, 4, 2.0),
            ),
        )

        assertTrue(scorer.score(hidden, MoodFilter.HiddenGems, stats).matches)
    }

    @Test
    fun hiddenGemsExcludesZeroSignalObscurity() {
        val obscure = item("Obscure No Signal", MediaType.MOVIE, 8.2, 4, 2.0)
        val stats = CandidateStats.from(
            listOf(
                obscure,
                item("Underseen Quality", MediaType.MOVIE, 7.6, 500, 28.0),
                item("Blockbuster", MediaType.MOVIE, 7.8, 30_000, 210.0),
            ),
        )

        assertFalse(scorer.score(obscure, MoodFilter.HiddenGems, stats).matches)
    }

    @Test
    fun hiddenGemsUsesContentTypeSpecificVoteThresholds() {
        val tv = item("TV Enough Signal", MediaType.SERIES, 8.0, 90, 35.0)
        val movieTooFewVotes = item("Movie Too Few Votes", MediaType.MOVIE, 8.0, 90, 36.0)
        val stats = CandidateStats.from(
            listOf(
                item("Tiny Release", MediaType.MOVIE, 7.0, 200, 2.0),
                tv,
                movieTooFewVotes,
                item("Big Movie", MediaType.MOVIE, 7.7, 25_000, 200.0),
            ),
        )

        assertTrue(scorer.score(tv, MoodFilter.HiddenGems, stats).matches)
        assertFalse(scorer.score(movieTooFewVotes, MoodFilter.HiddenGems, stats).matches)
    }

    @Test
    fun allMoodChipClicksOnlyChangeMoodSelection() {
        listOf("dark", "funny", "cinematic", "fast", "comfort", "acclaimed", "hidden", "blockbuster", "easy", "date", "mind", "family")
            .forEach { moodId ->
                val next = applyMoodChipClick(
                    MoodSelectionState(
                        searchQuery = "user typed query",
                        isAiSearchEnabled = false,
                        selectedMoodId = "all",
                    ),
                    clickedMoodId = moodId,
                )

                assertEquals("user typed query", next.searchQuery, moodId)
                assertFalse(next.isAiSearchEnabled, moodId)
                assertEquals(moodId, next.selectedMoodId, moodId)
            }
    }

    @Test
    fun dateNightMatchesRomanceComedy() {
        val movie = item(
            title = "Warm Romance",
            type = MediaType.MOVIE,
            rating = 7.1,
            voteCount = 400,
            popularity = 35.0,
            runtime = 112,
            overview = "A charming romantic comedy about love, dating, and second chances.",
            genres = listOf(Genre(10749, "Romance"), Genre(35, "Comedy")),
        )

        assertTrue(scorer.score(movie, MoodFilter.DateNight, CandidateStats.from(listOf(movie))).matches)
    }

    @Test
    fun dateNightMatchesRelationshipDrama() {
        val movie = item(
            title = "Before Evening",
            type = MediaType.MOVIE,
            rating = 7.4,
            voteCount = 700,
            popularity = 28.0,
            runtime = 101,
            overview = "A heartfelt drama about a couple navigating love and a long relationship.",
            genres = listOf(Genre(18, "Drama")),
        )

        assertTrue(scorer.score(movie, MoodFilter.DateNight, CandidateStats.from(listOf(movie))).matches)
    }

    @Test
    fun dateNightExcludesRandomHorrorAndAction() {
        val horror = item(
            title = "Gore House",
            type = MediaType.MOVIE,
            rating = 6.9,
            voteCount = 600,
            popularity = 42.0,
            runtime = 95,
            overview = "A brutal slasher with gore, torture, and a serial killer.",
            genres = listOf(Genre(27, "Horror")),
        )
        val action = item(
            title = "Explosion Run",
            type = MediaType.MOVIE,
            rating = 7.3,
            voteCount = 5_000,
            popularity = 120.0,
            runtime = 118,
            overview = "A lone agent races through missions, fights, and explosions.",
            genres = listOf(Genre(28, "Action")),
        )
        val stats = CandidateStats.from(listOf(horror, action))

        assertFalse(scorer.score(horror, MoodFilter.DateNight, stats).matches)
        assertFalse(scorer.score(action, MoodFilter.DateNight, stats).matches)
    }

    @Test
    fun dateNightMatchesWarmFamilyAnimation() {
        val movie = item(
            title = "Little Lantern",
            type = MediaType.MOVIE,
            rating = 7.0,
            voteCount = 500,
            popularity = 30.0,
            runtime = 96,
            overview = "A heartwarming animated story about family, love, and friendship.",
            genres = listOf(Genre(16, "Animation"), Genre(10751, "Family")),
        )

        assertTrue(scorer.score(movie, MoodFilter.DateNight, CandidateStats.from(listOf(movie))).matches)
    }

    @Test
    fun dateNightExcludesLowRatedJunk() {
        val movie = item(
            title = "Bad Date",
            type = MediaType.MOVIE,
            rating = 4.2,
            voteCount = 80,
            popularity = 22.0,
            runtime = 90,
            overview = "A romantic dating comedy.",
            genres = listOf(Genre(10749, "Romance"), Genre(35, "Comedy")),
        )

        assertFalse(scorer.score(movie, MoodFilter.DateNight, CandidateStats.from(listOf(movie))).matches)
    }

    @Test
    fun broadMovieMoodsReturnUsefulDateNightSet() {
        val candidates = listOf(
            item("Romance Comedy", MediaType.MOVIE, 7.1, 400, 35.0, 108, "A romantic comedy about love.", listOf(Genre(10749, "Romance"), Genre(35, "Comedy"))),
            item("Warm Drama", MediaType.MOVIE, 7.2, 450, 30.0, 118, "A heartfelt relationship drama.", listOf(Genre(18, "Drama"))),
            item("Family Warmth", MediaType.MOVIE, 7.0, 500, 31.0, 96, "A heartwarming family story about love.", listOf(Genre(10751, "Family"))),
            item("Charming Comedy", MediaType.MOVIE, 6.8, 300, 29.0, 100, "A charming feel-good comedy.", listOf(Genre(35, "Comedy"))),
            item("Musical Love", MediaType.MOVIE, 7.3, 350, 27.0, 124, "A musical romance about marriage and dreams.", listOf(Genre(10402, "Music"))),
            item("Slasher", MediaType.MOVIE, 6.8, 600, 42.0, 95, "Gore and torture.", listOf(Genre(27, "Horror"))),
        )

        val matches = candidates.applyMoodFilter("date")

        assertTrue(matches.size >= 5)
        assertFalse(matches.any { it.title == "Slasher" })
    }

    private fun item(
        title: String,
        type: MediaType,
        rating: Double,
        voteCount: Int,
        popularity: Double,
        runtime: Int? = null,
        overview: String? = null,
        genres: List<Genre> = emptyList(),
    ): MediaItem = MediaItem(
        id = title.lowercase().replace(" ", "-"),
        tmdbId = title.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) },
        type = type,
        title = title,
        rating = rating,
        voteCount = voteCount,
        popularity = popularity,
        runtime = runtime,
        overview = overview,
        genres = genres,
        ratings = MediaRatings(tmdbScore = rating.toFloat(), imdbVotes = voteCount),
    )
}
