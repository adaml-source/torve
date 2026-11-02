package com.torve.data.addon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class NativeCatalogueRoutingTest {
    @Test fun wrappedTmdbRowsUseCanonicalBackendRoutesWithoutAggregatorConfiguration() = runTest {
        val urls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request -> urls += request.url.toString(); respond("""{"metas":[]}""") })
        try {
            val client = StremioAddonClient(http, Json, nativeCatalogueBaseUrl = "https://api.torve.app/apple-metadata/v1", nativeCatalogueLanguage = { "tr" })
            for ((id, kind) in listOf("trending" to "series", "language" to "movie", "year" to "series", "top" to "movie", "latest" to "series", "search" to "movie")) {
                client.fetchCatalog("https://aiostreams.elfhosted.com/private-access-token/config", kind, "78de3b0.tmdb.$id", skip = 24, failOnError = true)
                assertEquals("https://api.torve.app/apple-metadata/v1/native/catalog/$kind/tmdb.$id/skip=24.json?language=tr", urls.last())
            }
            assertTrue(urls.none { it.contains("private-access-token") || it.contains("78de3b0") })
        } finally { http.close() }
    }

    @Test fun wrappingDoesNotRedirectOtherCatalogueSources() {
        assertNull(nativeCatalogueId("https://catalogue.example/config", "78de3b0.tmdb.trending"))
        assertNull(nativeCatalogueId("https://aiostreams.elfhosted.com/config", "78de3b0.trakt.trending"))
        assertNull(nativeCatalogueId("https://aiostreams.elfhosted.com/config", "tmdb.trending"))
        assertNull(nativeCatalogueId("https://aiostreams.elfhosted.com/config", "78de3b0.tmdb.unsupported"))
    }

    @Test fun migratedRowsUseSharedBackendWithExplicitLocaleAndNoAddonSecrets() = runTest {
        val urls = mutableListOf<String>()
        var locale = "de"
        val http = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            respond("""{"metas":[{"id":"tmdb:155","type":"movie","name":"Dark Knight","poster":"https://api.torve.app/images/test","torve_ratings":{"traktScore":80},"torve_presentation_pending":true}]}""")
        })
        try {
            val client = StremioAddonClient(http, Json { ignoreUnknownKeys = true },
                nativeCatalogueBaseUrl = "https://api.torve.app/apple-metadata/v1",
                nativeCatalogueLanguage = { locale })
            val response = client.fetchCatalog("https://configured-tmdb-addon.baby-beamup.club/private-key/config", "movie",
                "tmdb.language", genre = "German", skip = 20, failOnError = true)
            assertTrue(urls.single().startsWith("https://api.torve.app/apple-metadata/v1/native/catalog/movie/tmdb.language/"))
            assertTrue(urls.single().contains("genre=German&skip=20.json?language=de"))
            assertFalse(urls.single().contains("private-key"))
            val item = response.metas.single().toMediaItem()
            assertEquals(155, item.tmdbId)
            assertEquals(80f, item.ratings?.traktScore)
            assertNull(item.ratings?.imdbScore)
            assertTrue(item.presentationPending)
            locale = "tr"
            client.fetchCatalog("https://configured-tmdb-addon.baby-beamup.club", "series", "tmdb.year")
            assertTrue(urls.last().endsWith("?language=tr"))
        } finally { http.close() }
    }

    @Test fun unrelatedAddonsAndNonAppleClientsKeepTheirOwnTransport() = runTest {
        val urls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request -> urls += request.url.toString(); respond("""{"metas":[]}""") })
        try {
            StremioAddonClient(http, Json, nativeCatalogueBaseUrl = "https://api.torve.app").fetchCatalog(
                "https://catalogue.example/config", "movie", "tmdb.year")
            StremioAddonClient(http, Json, nativeCatalogueBaseUrl = null).fetchCatalog(
                "https://configured-tmdb-addon.baby-beamup.club/config", "movie", "tmdb.year")
            assertEquals("https://catalogue.example/config/catalog/movie/tmdb.year.json", urls[0])
            assertEquals("https://configured-tmdb-addon.baby-beamup.club/config/catalog/movie/tmdb.year.json", urls[1])
        } finally { http.close() }
        assertFalse(usesNativeCatalogue("https://configured-tmdb-addon.baby-beamup.club.evil.example", "tmdb.year"))
    }
}
