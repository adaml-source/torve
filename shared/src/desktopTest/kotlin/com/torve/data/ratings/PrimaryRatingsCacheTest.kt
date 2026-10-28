package com.torve.data.ratings

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PrimaryRatingsCacheTest {
    @Test fun anonymousPrimaryLookupUsesCacheWithoutCallingServicesThatCannotSupplyImdbOrRt() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; error("No primary provider is configured") })
        try {
            PersonalDeviceFixture(client).use { device ->
                device.signIn()
                val cache = RatingsCacheRepository(device.database)
                cache.put("MOVIE:42", MediaRatings(imdbScore = 8.4f, imdbVotes = 1200, rottenTomatoesScore = 92))
                val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client, apiKeyProvider = { "fixture" }),
                    TraktClient(client, Json), cache, OmdbClient(client, device.preferences, device.secrets),
                    BackendRatingsApi(client, { "https://fixture.invalid" }, { null }))
                val result = enricher.enrichList(listOf(MediaItem("42", tmdbId = 42, title = "Cached", type = MediaType.MOVIE),
                    MediaItem("43", tmdbId = 43, title = "Unknown", type = MediaType.MOVIE)), "")
                assertEquals(8.4f, result[0].ratings?.imdbScore)
                assertEquals(92, result[0].ratings?.rottenTomatoesScore)
                assertNull(result[1].ratings?.imdbScore)
                assertEquals(0, calls)
            }
        } finally { client.close() }
    }
}
