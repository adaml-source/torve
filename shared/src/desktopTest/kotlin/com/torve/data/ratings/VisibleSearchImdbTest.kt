package com.torve.data.ratings

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class VisibleSearchImdbTest {
    @Test fun missingVisibleScoresUseRealImdbMetadataAndThenThePersistentCache() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.startsWith("/meta/movie/"))
            val id = request.url.encodedPath.substringAfterLast('/').removeSuffix(".json")
            requested += id
            val body = if (id == "tt0000003") """{"meta":{"id":"tt0000003","imdbRating":"N/A"}}"""
                else """{"meta":{"id":"$id","imdbRating":"8.2","imdbVotes":"1,234"}}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            val cache = RatingsCacheRepository(device.database)
            val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client), TraktClient(client, Json), cache,
                OmdbClient(client, device.preferences, device.secrets), detailImdbApi = CinemetaImdbRatingsApi(client))
            val items = (1..3).map { id -> MediaItem("tmdb:$id", tmdbId = id, imdbId = "tt000000$id", title = "Movie $id",
                type = MediaType.MOVIE, ratings = MediaRatings(tmdbScore = 9.9f)) }
            val first = enricher.enrichVisibleImdbList(items)
            assertEquals(listOf(8.2f, 8.2f, null), first.map { it.ratings?.imdbScore })
            assertTrue(first.all { it.ratings?.tmdbScore == 9.9f })
            assertEquals(1234, first[0].ratings?.imdbVotes)
            assertEquals(8.2f, cache.getCached("MOVIE:1")?.imdbScore)
            assertEquals(first, enricher.enrichVisibleImdbList(items))
            assertEquals(setOf("tt0000001", "tt0000002", "tt0000003"), requested.toSet())
            assertEquals(3, requested.size)
        }
    }
}
