package com.torve.android.tv.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSeeAllCatalogQueryPolicyTest {
    @Test
    fun movieCatalogRailsUseGlobalTmdbQueries() {
        assertTrue(supportsGlobalTmdbSeeAllQuery("trending_movie"))
        assertTrue(supportsGlobalTmdbSeeAllQuery("popular_movie"))
        assertTrue(supportsGlobalTmdbSeeAllQuery("top_rated_movie"))
        assertTrue(supportsGlobalTmdbSeeAllQuery("genre_movie_28"))
        assertTrue(supportsGlobalTmdbSeeAllQuery("now-playing"))
        assertTrue(supportsGlobalTmdbSeeAllQuery("upcoming"))
    }

    @Test
    fun localAndPersonalRailsRemainSourceScoped() {
        assertFalse(supportsGlobalTmdbSeeAllQuery("continue_watching_movie"))
        assertFalse(supportsGlobalTmdbSeeAllQuery("watchlist_movie"))
        assertFalse(supportsGlobalTmdbSeeAllQuery("person_credits_42"))
    }

    @Test
    fun categoryGenreIsCombinedWithSelectedGenresWithoutBeingLost() {
        assertEquals("28,12|53", buildTmdbGenreQuery(28, setOf(53, 12)))
        assertEquals("28", buildTmdbGenreQuery(28, setOf(28)))
        assertEquals("12|53", buildTmdbGenreQuery(null, setOf(53, 12)))
        assertNull(buildTmdbGenreQuery(null, emptySet()))
    }
}
