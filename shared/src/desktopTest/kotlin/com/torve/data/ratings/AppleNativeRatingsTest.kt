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

class AppleNativeRatingsTest {
    @Test fun appleUsesOneNativeBatchAndDoesNotCallSecondaryEnrichmentProviders() = runTest {
        verify(false)
    }

    @Test fun imdbOnlyItemsWorkWithEnrichmentEnabledAndCatalogueDisabled() = runTest {
        verify(true)
    }

    private suspend fun verify(imdbOnly: Boolean) {
        var calls=0
        val http=HttpClient(MockEngine { request ->
            calls++
            assertEquals("api.torve.app",request.url.host)
            assertEquals("/apple-metadata/v1/ratings",request.url.encodedPath)
            assertEquals(if (imdbOnly) "movie:tt0137523" else "movie:550",request.url.parameters["ids"])
            assertNull(request.url.parameters["api_key"])
            respond("""{"items":[{"id":550,"media_type":"movie","imdb_id":"tt0137523","ratings":{"imdbScore":8.8,"imdbVotes":123,"rottenTomatoesScore":81,"traktScore":87.3}}]}""",
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        try {
            PersonalDeviceFixture(http).use { device ->
                val cache=RatingsCacheRepository(device.database)
                val api=TmdbApiClient(http,apiKeyProvider={error("No TMDB credentials on Apple")},
                    baseUrl="https://api.torve.app/apple-metadata/v1",
                    requestAllowed={ com.torve.data.metadata.TmdbExtensionSelection(catalogue=false,enrichment=true).permits(it) })
                val enricher=RatingsEnricher(MdbListApi(http),api,TraktClient(http,Json),cache,
                    OmdbClient(http,device.preferences,device.secrets),detailImdbApi=CinemetaImdbRatingsApi(http))
                val item=MediaItem(if (imdbOnly) "tt0137523" else "550",tmdbId=if (imdbOnly) null else 550,title="Example",type=MediaType.MOVIE)
                val enriched=enricher.enrichSingle(item,"a-user-configured-key")
                assertEquals(8.8f,enriched.ratings?.imdbScore)
                assertEquals(87.3f,enriched.ratings?.traktScore)
                assertEquals(enriched.ratings,cache.getCached("MOVIE:550"))
                assertEquals("tt0137523",enriched.imdbId)
                assertEquals(550,enriched.tmdbId)
                assertEquals(item.id,enriched.id)
                assertEquals(1,calls)
                // A title carrying complete native values must not repeat enrichment.
                assertEquals(enriched,enricher.enrichSingle(enriched,"a-user-configured-key"))
                assertEquals(1,calls)
            }
        } finally { http.close() }
    }
}
