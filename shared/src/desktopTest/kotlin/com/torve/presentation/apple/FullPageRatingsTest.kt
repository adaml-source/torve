package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.ratings.*
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.seeall.SeeAllViewModel
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

class FullPageRatingsTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unexpected ${method.name}")
    } as T

    @Test fun allThirtyOneTitlesReceiveBackendImdbIncludingTitlesAfterTheFirstTwelve() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val batches = mutableListOf<List<Int>>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/me/ratings/batch", request.url.encodedPath)
            val content = (request.body as io.ktor.http.content.TextContent).text
            val ids = Json.parseToJsonElement(content).jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("tmdb_id").jsonPrimitive.int }
            batches += ids
            respond("""{"items":[${ids.joinToString(",") { """{"media_type":"movie","tmdb_id":$it,"imdb_score":8.2}""" }}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            PersonalDeviceFixture(client).use { device ->
                device.signIn()
                val enricher = RatingsEnricher(MdbListApi(client), TmdbApiClient(client, apiKeyProvider = { "fixture" }),
                    TraktClient(client, Json), RatingsCacheRepository(device.database), OmdbClient(client, device.preferences, device.secrets),
                    BackendRatingsApi(client, { "https://fixture.invalid" }, { "fixture-token" }))
                val items = (1..31).map { MediaItem(id = "$it", tmdbId = it, title = "Movie $it", type = MediaType.MOVIE) }
                SeeAllViewModel.pendingItems["full_page_fixture"] = "Whole universe" to items
                val vm = SeeAllViewModel(unused<MetadataRepository>(), unused<WatchHistoryRepository>(), unused<WatchlistRepository>(),
                    device.preferences, unused<WatchProgressRepository>(), ratingsEnricher = enricher)
                try {
                    vm.loadSection("shelf:full_page_fixture")
                    withContext(Dispatchers.Default) { withTimeout(10_000) {
                        while (vm.state.value.items.count { it.ratings?.imdbScore == 8.2f } != 31) {
                            delay(10)
                        }
                    } }
                    assertEquals(listOf(12, 12, 7), batches.map { it.size })
                    assertEquals((1..31).toList(), batches.flatten())
                    assertEquals(8.2f, vm.state.value.items.last().ratings?.imdbScore)
                } finally { vm.close(); SeeAllViewModel.pendingItems.remove("full_page_fixture") }
            }
        } finally { client.close(); Dispatchers.resetMain() }
    }
}
