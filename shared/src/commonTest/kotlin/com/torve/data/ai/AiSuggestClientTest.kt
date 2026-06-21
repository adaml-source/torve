package com.torve.data.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiSuggestClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun deserialize_discover_mode() {
        val jsonStr = """
            {
                "mode": "discover",
                "title": "Horror Movies",
                "genreIds": [27],
                "keywordTerms": ["zombie"],
                "yearFrom": 2020,
                "sortBy": "vote_average.desc",
                "minRating": 7.0,
                "mediaType": "movie"
            }
        """.trimIndent()
        val result = json.decodeFromString<AiSuggestResult>(jsonStr)
        assertEquals("discover", result.mode)
        assertEquals("Horror Movies", result.title)
        assertEquals(listOf(27), result.genreIds)
        assertEquals(listOf("zombie"), result.keywordTerms)
        assertEquals(2020, result.yearFrom)
        assertEquals("vote_average.desc", result.sortBy)
        assertEquals(7.0f, result.minRating)
        assertEquals("movie", result.mediaType)
    }

    @Test
    fun deserialize_specific_mode() {
        val jsonStr = """
            {
                "mode": "specific",
                "title": "Movies about time loops",
                "specificTitles": [
                    {"title": "Groundhog Day", "year": 1993, "mediaType": "movie"},
                    {"title": "Edge of Tomorrow", "year": 2014, "mediaType": "movie"}
                ]
            }
        """.trimIndent()
        val result = json.decodeFromString<AiSuggestResult>(jsonStr)
        assertEquals("specific", result.mode)
        assertEquals(2, result.specificTitles.size)
        assertEquals("Groundhog Day", result.specificTitles[0].title)
        assertEquals(1993, result.specificTitles[0].year)
    }

    @Test
    fun deserialize_person_credits_mode() {
        val jsonStr = """
            {
                "mode": "person_credits",
                "title": "Movies with Tom Hanks",
                "personName": "Tom Hanks",
                "personRole": "acting",
                "mediaType": "movie"
            }
        """.trimIndent()
        val result = json.decodeFromString<AiSuggestResult>(jsonStr)
        assertEquals("person_credits", result.mode)
        assertEquals("Tom Hanks", result.personName)
        assertEquals("acting", result.personRole)
    }

    @Test
    fun missing_fields_fall_back_to_defaults() {
        val jsonStr = """{"mode": "discover"}"""
        val result = json.decodeFromString<AiSuggestResult>(jsonStr)
        assertEquals("discover", result.mode)
        assertEquals("", result.title)
        assertEquals(emptyList(), result.genreIds)
        assertEquals(emptyList(), result.keywordTerms)
        assertEquals(null, result.yearFrom)
        assertEquals(null, result.yearTo)
        assertEquals("popularity.desc", result.sortBy)
        assertEquals(null, result.minRating)
        assertEquals(null, result.mediaType)
        assertEquals(emptyList(), result.specificTitles)
        assertEquals(null, result.personName)
        assertEquals(null, result.personRole)
    }

    @Test
    fun empty_json_object_uses_all_defaults() {
        val jsonStr = "{}"
        val result = json.decodeFromString<AiSuggestResult>(jsonStr)
        assertEquals("discover", result.mode)
        assertEquals("", result.title)
    }

    @Test
    fun aiSpecificTitle_partial_fields() {
        val jsonStr = """{"title": "Inception"}"""
        val result = json.decodeFromString<AiSpecificTitle>(jsonStr)
        assertEquals("Inception", result.title)
        assertEquals(null, result.year)
        assertEquals(null, result.mediaType)
    }
}
