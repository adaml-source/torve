package com.torve.presentation.lists

import com.torve.data.PersonalDeviceFixture
import com.torve.data.trakt.*
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class TraktListsViewModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val title = MediaItem("10", tmdbId = 10, type = MediaType.MOVIE, title = "A film")
    private val list = TraktPersonalList(name = "Cinema", ids = TraktIds(trakt = 7))
    private val metadata = Proxy.newProxyInstance(MetadataRepository::class.java.classLoader,
        arrayOf(MetadataRepository::class.java)) { _, _, _ -> error("Membership checks do not load metadata") } as MetadataRepository

    private suspend fun viewModel(device: PersonalDeviceFixture, scope: CoroutineScope): TraktListsViewModel {
        val tokens = TraktTokenStore(device.secrets, json)
        tokens.write(TraktTokens("test-token", "test-refresh", 3600, System.currentTimeMillis() / 1000))
        return TraktListsViewModel(TraktAuthorizedApi(TraktClient(device.http, json), tokens,
            personalScopeProvider = device.ids::currentDataUserId), metadata, device.ids, scope)
    }

    @Test fun delayedMembershipRefreshCannotUndoASuccessfulRemoval() = runTest {
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        var reads = 0
        val http = HttpClient(MockEngine { request ->
            val response = when {
                request.url.encodedPath == "/users/me/lists" -> """[{"name":"Cinema","ids":{"trakt":7}}]"""
                request.method == HttpMethod.Get -> {
                    if (++reads == 2) { firstReadStarted.complete(Unit); releaseFirstRead.await() }
                    """[{"type":"movie","movie":{"title":"A film","ids":{"tmdb":10}}}]"""
                }
                else -> """{"added":{"movies":1},"not_found":{}}"""
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        PersonalDeviceFixture(http).use { device ->
            device.signIn()
            val model = viewModel(device, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
            try {
                model.load(title); model.state.first { it.membership[7] == true }
                model.load(title); firstReadStarted.await()
                model.toggleMembership(list)
                model.state.first { !it.busy && it.membership[7] == false }
                releaseFirstRead.complete(Unit); advanceUntilIdle()
                assertEquals(false, model.state.value.membership[7])
                assertEquals(2, reads)
            } finally { model.clear() }
        }
    }

    @Test fun switchingProfileWhileReadingMembershipCannotSendAWriteForTheNextPerson() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var writes = 0
        var pauseReads = false
        val http = HttpClient(MockEngine { request ->
            val response = when {
                request.url.encodedPath == "/users/me/lists" -> """[{"name":"Cinema","ids":{"trakt":7}}]"""
                request.method == HttpMethod.Get -> {
                    if (pauseReads) { readStarted.complete(Unit); releaseRead.await() }
                    "[]"
                }
                else -> { writes++; """{"added":{"movies":1}}""" }
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        PersonalDeviceFixture(http).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, null, 2)
            val model = viewModel(device, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
            try {
                model.load(title); model.state.first { it.membership[7] == false }
                // A fresh screen must fetch membership before it can decide whether to add or remove.
                model.load(title.copy(id = "20", tmdbId = 20)); pauseReads = true
                readStarted.await(); model.toggleMembership(list)
                device.select("kids"); releaseRead.complete(Unit); advanceUntilIdle()
                assertEquals(0, writes)
                assertNull(model.state.value.membership[7])
            } finally { model.clear() }
        }
    }
}
