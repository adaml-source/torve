package com.torve.data.catalog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.metadata.TmdbApiClient
import com.torve.db.TorveDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleCatalogueCacheMigrationTest {
    @Test fun appleDoesNotRestoreLegacySqlArtworkAndKeepsAbsoluteProviderUrls() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = TorveDatabase(driver).also { TorveDatabase.Schema.create(driver) }
        db.torveQueries.insertTopItem("movie", 18, 0, 240, "Old cached title", "/retired.jpg", null, null, null, null, 8.0, null, 1)
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            val body = if (request.url.parameters["page"] == "1")
                """{"page":1,"results":[{"id":240,"title":"The Godfather Part II","poster_path":"https://api.torve.app/apple-metadata/v1/images/poster"}]}"""
                else """{"page":2,"results":[]}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val api = TmdbApiClient(http, resolvedHostProvider = { null }, baseUrl = "https://fixture.test/apple-metadata/v1")
            val repo = CatalogTopCacheRepository(db, api)
            assertFalse(repo.hasCacheFor("movie", 18))
            assertEquals(1, repo.refreshGenre("movie", 18))
            assertEquals("https://api.torve.app/apple-metadata/v1/images/poster", repo.getTop("movie", 18).single().posterUrl)
            assertFalse(repo.supportsBackgroundWarmup)
            val before = requests
            CatalogTopCacheWorker(repo).runNow()
            assertEquals(before, requests)
            api.contentLanguage = "de"
            assertFalse(repo.hasCacheFor("movie", 18))
            assertEquals(1L, db.torveQueries.countTopForGenre("movie", 18).executeAsOne())
        } finally { http.close(); driver.close() }
    }
}
