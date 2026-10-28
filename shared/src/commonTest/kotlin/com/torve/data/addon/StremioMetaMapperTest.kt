package com.torve.data.addon

import kotlin.test.*

class StremioMetaMapperTest {
    @Test fun configuredAioProviderStructuredCreditsAndImdbIdentityAreRetained() {
        val payload = """{"id":"tmdb:106379","type":"series","name":"Fallout","imdb_id":"tt12637874","app_extras":{"cast":[{"name":"Ella Purnell","character":"Lucy MacLean","photo":"https://image.tmdb.org/ella.jpg"},{"name":"Aaron Moten","character":"Maximus"}]},"cast":["Ella Purnell","Walton Goggins"]}"""
        val value = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<StremioMeta>(payload).toMediaItem()
        assertEquals("tt12637874", value.imdbId)
        assertEquals(listOf("Ella Purnell", "Aaron Moten", "Walton Goggins"), value.cast.map { it.name })
        assertEquals("Lucy MacLean", value.cast.first().character)
        assertEquals("https://image.tmdb.org/ella.jpg", value.cast.first().profileUrl)
        assertEquals(3, value.cast.map { it.id }.distinct().size)
    }
    @Test fun extensionCreditsAreRetainedWithoutInventingTmdbPeople() {
        val value = StremioMeta(id = "tt12637874", type = "series", name = "Fallout",
            description = "A synopsis", logo = "logo.png", runtime = "65 min",
            cast = listOf(" Ella Purnell ", "Aaron Moten", "Walton Goggins", "Ella Purnell", " ")).toMediaItem()
        assertEquals(listOf("Ella Purnell", "Aaron Moten", "Walton Goggins"), value.cast.map { it.name })
        assertTrue(value.cast.all { it.id < 0 }); assertEquals(3, value.cast.map { it.id }.distinct().size)
        assertEquals("A synopsis", value.overview); assertEquals("logo.png", value.logoUrl); assertEquals(65, value.runtime)
    }
}
