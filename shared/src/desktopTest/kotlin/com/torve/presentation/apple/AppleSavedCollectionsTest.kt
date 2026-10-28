package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.auth.UserIdProvider
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

class AppleSavedCollectionsTest {
    private suspend fun fixture(check: suspend (PersonalDeviceFixture, AppleCollections, () -> Unit) -> Unit) {
        var flip: (() -> Unit)? = null
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method, "Browsing/saving collections must not change a remote watchlist")
            val body = if (request.url.encodedPath.endsWith("/collection/99")) {
                flip?.invoke()
                val parts = (1..24).joinToString(",") { """{"id":$it,"title":"Film $it","release_date":"2026-01-01"}""" }
                """{"id":99,"name":"Whole franchise","parts":[$parts]}"""
            } else when {
                request.url.encodedPath.endsWith("/movie/42/lists") -> """{"page":1,"total_pages":1,"results":[{"id":501,"name":"All character movies","item_count":24},{"id":502,"name":"Whole universe","item_count":40}]}"""
                request.url.encodedPath.endsWith("/search/collection") -> """{"page":1,"results":[{"id":99,"name":"MCU-only franchise"}]}"""
                request.url.encodedPath.endsWith("/list/501") || request.url.encodedPath.endsWith("/list/502") -> {
                    val id = request.url.encodedPath.substringAfterLast('/').toInt()
                    val page = request.url.parameters["page"]?.toInt() ?: 1
                    val entries = (if (page == 1) 1..20 else 21..24).joinToString(",") { """{"id":$it,"title":"Film $it","release_date":"2026-01-01"}""" }
                    """{"id":"$id","name":"${if(id==501) "All character movies" else "Whole universe"}","item_count":24,"items":[$entries]}"""
                }
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        PersonalDeviceFixture(client).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val app = koinApplication { modules(module {
                single<UserIdProvider> { device.ids }
                single<PreferencesRepository> { device.preferences }
                single<IntegrationSecretStore> { device.secrets }
                single { TmdbApiClient(client, apiKeyProvider = { "fixture-key" }, resolvedHostProvider = { null }, diagnostics = {}) }
                single { MdbListApi(client) }
                single { MdbListRepository(get(), get()) }
            }) }
            try { check(device, AppleCollections(app.koin)) { flip = { device.select("other") } } }
            finally { app.close() }
        }
    }

    @Test fun collectionPersistsAsOneGroupWithAllMembersAndIsProfileScoped() = runTest {
        fixture { device, service, _ ->
            service.saveCollection(99)
            service.saveCollection(99)
            assertEquals(1, service.bookmarks().size)
            val page = service.contents("tmdb", 99, 1)
            assertEquals(24, page.items.size)
            assertFalse(page.hasMore)
            device.select("other")
            assertTrue(service.bookmarks().isEmpty())
            device.select("default")
            assertEquals(1, service.bookmarks().size)
            service.remove("tmdb", 99)
            assertTrue(service.bookmarks().isEmpty())
        }
    }

    @Test fun profileSwitchDuringMetadataLookupCannotSaveToAnotherProfile() = runTest {
        fixture { device, service, flip ->
            flip()
            assertFailsWith<CancellationException> { service.saveCollection(99) }
            assertTrue(service.bookmarks().isEmpty())
            device.select("default")
            assertTrue(service.bookmarks().isEmpty())
        }
    }

    @Test fun broaderCollectionsAreDistinctSelectableBookmarksAndRetainPaging() = runTest {
        fixture { device, service, _ ->
            val choices = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { service.choices(42, "Character", 1) }
            assertEquals(setOf("tmdb:99", "tmdb_list:501", "tmdb_list:502"), choices.options.map { it.id }.toSet())
            service.save("tmdb_list", 501); service.save("tmdb_list", 502)
            assertEquals(2, service.bookmarks().size)
            val first = service.contents("tmdb_list", 501, 1)
            val second = service.contents("tmdb_list", 501, 2)
            assertTrue(first.hasMore); assertFalse(second.hasMore)
            assertEquals(24, (first.items + second.items).distinctBy { it.id }.size)
            service.remove("tmdb_list", 501)
            assertEquals("tmdb_list:502", service.bookmarks().single().id)
            device.select("other"); assertTrue(service.bookmarks().isEmpty())
        }
    }
}
