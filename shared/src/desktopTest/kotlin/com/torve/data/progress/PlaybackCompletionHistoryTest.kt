package com.torve.data.progress

import com.torve.data.PersonalDeviceFixture
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepositoryImpl
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlaybackCompletionHistoryTest {
    @Test fun successiveEpisodeCompletionCreatesHistoryOncePerEpisodeAndKeepsProfilesSeparate() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine {
            respond("""{"id":236235,"name":"The Gentlemen","external_ids":{"imdb_id":"tt13210838"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        PersonalDeviceFixture(http).use { device ->
            device.signIn()
            val tokens = TraktTokenStore(device.secrets, json)
            val trakt = TraktAuthorizedApi(TraktClient(http, json), tokens)
            val queue = TraktSyncRepositoryImpl(device.database, trakt, tokens, json, device.ids::currentDataUserId)
            val repository = WatchProgressRepositoryImpl(device.database, trakt, TmdbApiClient(http,
                apiKeyProvider = { "fixture-public-key" }, resolvedHostProvider = { emptyList() }, diagnostics = {}),
                queue, SimklClient(http), device.secrets, device.ids)
            val progress = WatchProgress("236235", MediaType.SERIES, "The Gentlemen", positionMs = 850,
                durationMs = 1000, seasonNumber = 2, episodeNumber = 1)
            repository.saveProgress(progress)
            repository.saveProgress(progress.copy(positionMs = 900))
            repository.saveProgress(progress.copy(episodeNumber = 2))
            repository.saveProgress(progress.copy(episodeNumber = 3))
            val watched = device.database.torveQueries.getAllHistory(device.ids.currentDataUserId()).executeAsList()
            assertEquals(setOf(1L, 2L, 3L), watched.map { it.episode_number }.toSet())
            assertEquals(3, watched.size)
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, "PG", 2)
            device.select("kids")
            repository.saveProgress(progress.copy(episodeNumber = 4))
            assertEquals(listOf(4L), device.database.torveQueries.getAllHistory(device.ids.currentDataUserId()).executeAsList().map { it.episode_number })
            assertEquals(3, device.database.torveQueries.getAllHistory(PersonalDeviceFixture.ACCOUNT).executeAsList().size)
        }
    }
}
