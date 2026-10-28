package com.torve.data.addon

import com.torve.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AiRecommendationRemovalTest {
    @Test fun retiredCataloguesMakeNoNetworkRequestIncludingSearch() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; error("Retired AI catalogue must never be requested") })
        try {
            val aggregator = CatalogAggregator(StremioAddonClient(http, Json { ignoreUnknownKeys = true }))
            val addon = InstalledAddon("https://provider.example/manifest.json", AddonManifest("provider", "Provider", "1",
                catalogs = listOf(AddonCatalog("movie", "ai-recommendations", "AI Recommendations",
                    extra = listOf(AddonExtra("search"))))))
            assertTrue(aggregator.fetchCatalogs(listOf(addon)).isEmpty())
            assertTrue(aggregator.searchAll(listOf(addon), "drama").isEmpty())
            assertEquals(0, calls)
        } finally { http.close() }
    }
    @Test fun providerLabelsAndLegacyIdsAreRemoved() {
        listOf("AI Recommendations · Movies", "AI Recommendations · TV shows", "ai_recommendations",
            "aiRecommendations", "provider-ai-recs::type=movie", "AI-powered recommendations",
            "Recommended by AI").forEach { assertTrue(AiRecommendationCatalogPolicy.isRemoved(it, ""), it) }
        assertTrue(AiRecommendationCatalogPolicy.isRemoved("opaque-provider-id", "AI Recommendations"))
    }

    @Test fun ordinaryDiscoveryAndAiSearchAreKept() {
        listOf("Recommended For You", "Because You Watched", "Hidden Gems", "AI Search",
            "Hawaii Recommendations", "Highly Rated", "Popular").forEach {
            assertFalse(AiRecommendationCatalogPolicy.isRemoved(it, ""), it)
        }
    }

    @Test fun mixedProviderKeepsPlaybackSubtitlesAndOtherCatalogues() {
        val manifest = AddonManifest("aio", "AIOStreams", "1", resources = listOf("stream", "catalog", "subtitles"),
            catalogs = listOf(AddonCatalog("movie", "ai-recommendations", "AI Recommendations"),
                AddonCatalog("movie", "popular", "Popular")))
        val filtered = manifest.withoutAiRecommendations()
        assertEquals(listOf("popular"), filtered.catalogs.map { it.id })
        assertEquals(manifest.resources, filtered.resources)
        assertEquals(manifest.id, filtered.id)
    }

    @Test fun cachedErrorRowsCannotReturnAfterRestart() {
        val shelves = listOf(CatalogShelf("aio-ai-recommendations", "My renamed row", emptyList()),
            CatalogShelf("opaque", "AI Recommendations · Movies", emptyList()),
            CatalogShelf("hidden-gems", "Hidden Gems", emptyList()))
        assertEquals(listOf("hidden-gems"), shelves.withoutAiRecommendations().map { it.id })
    }
}
