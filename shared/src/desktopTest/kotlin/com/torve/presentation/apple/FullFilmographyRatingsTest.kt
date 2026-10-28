package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.*
import com.torve.data.ratings.*
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.detail.PersonViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class FullFilmographyRatingsTest {
    @Test fun allActorCreditsGetImdbWithoutLosingTheirRoleClassification() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val batches = mutableListOf<Int>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/me/ratings/batch", request.url.encodedPath)
            val ids = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text)
                .jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("tmdb_id").jsonPrimitive.int }
            batches += ids.size
            respond("""{"items":[${ids.joinToString(",") { """{"media_type":"movie","tmdb_id":$it,"imdb_score":8.2}""" }}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            PersonalDeviceFixture(client).use { device ->
                device.signIn()
                val tmdb = TmdbApiClient(client, apiKeyProvider = { "fixture" })
                val enricher = RatingsEnricher(MdbListApi(client), tmdb, TraktClient(client, Json),
                    RatingsCacheRepository(device.database), OmdbClient(client, device.preferences, device.secrets),
                    BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }))
                val credits = (1..31).map { MediaItem("$it", tmdbId = it, title = "Film $it", type = MediaType.MOVIE,
                    creditCharacter = if (it == 31) "Self" else "Actor", genreIds = if (it == 31) listOf(99) else emptyList()) }
                val unused = Proxy.newProxyInstance(MetadataRepository::class.java.classLoader, arrayOf(MetadataRepository::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as MetadataRepository
                val metadata = object : MetadataRepository by unused {
                    override suspend fun getPersonDetail(personId: Int) = TmdbPerson(personId, "Actor")
                    override suspend fun getPersonCredits(personId: Int) = credits
                }
                val vm = PersonViewModel(metadata, enricher)
                try {
                    vm.loadPerson(42)
                    withContext(Dispatchers.Default) { withTimeout(10_000) { while (vm.state.value.credits.count { it.ratings?.imdbScore == 8.2f } != 31) delay(10) } }
                    assertEquals(listOf(12, 12, 7), batches)
                    assertEquals("Self", vm.state.value.credits.last().creditCharacter)
                    assertEquals(listOf(99), vm.state.value.credits.last().genreIds)
                } finally { vm.close() }
            }
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
