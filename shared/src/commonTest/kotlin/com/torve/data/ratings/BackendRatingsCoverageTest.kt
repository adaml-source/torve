package com.torve.data.ratings

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BackendRatingsCoverageTest {
    @Test fun completePagesUseTwelveTitleRequestsWithoutDroppingTheRemainder() = runTest {
        val sizes = mutableListOf<Int>()
        val client = HttpClient(MockEngine { request ->
            val values = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("items").jsonArray
            sizes += values.size
            val result = values.map { value ->
                val id = value.jsonObject.getValue("tmdb_id").jsonPrimitive.int
                """{"media_type":"movie","tmdb_id":$id,"imdb_score":8.1,"rotten_tomatoes_score":91}"""
            }.joinToString(",")
            respond("""{"items":[$result]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val result = BackendRatingsApi(client, { "https://ratings.example" }, { "fixture-token" })
                .fetch((1..31).map { MediaItem(id = "tmdb:$it", title = "Movie $it", type = MediaType.MOVIE) })
            assertEquals(listOf(12, 12, 7), sizes)
            assertTrue(result.reachable)
            assertEquals(31, result.ratings.size)
            assertTrue(result.ratings.values.all { it.imdbScore == 8.1f && it.rottenTomatoesScore == 91 })
        } finally { client.close() }
    }

    @Test fun oneFailedBatchKeepsSuccessfulRatingsFromOtherBatches() = runTest {
        var batch = 0
        val client = HttpClient(MockEngine {
            batch++
            if (batch == 2) respond("{}", HttpStatusCode.TooManyRequests)
            else respond("""{"items":[{"media_type":"movie","tmdb_id":1,"imdb_score":8.1,"rotten_tomatoes_score":91}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val result = BackendRatingsApi(client, { "https://ratings.example" }, { "fixture-token" })
                .fetch((1..13).map { MediaItem(id = "$it", tmdbId = it, title = "Movie $it", type = MediaType.MOVIE) })
            assertFalse(result.reachable)
            assertEquals(91, result.ratings["MOVIE:1"]?.rottenTomatoesScore)
        } finally { client.close() }
    }
}
