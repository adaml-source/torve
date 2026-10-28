package com.torve.data.mdblist

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.home.HomeLayoutSourceSelector
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class MdbListManagementTest {
    private class Prefs : PreferencesRepository {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private fun api(status: HttpStatusCode = HttpStatusCode.OK) = MdbListApi(HttpClient(MockEngine {
        respond("""{"id":12,"name":"Whole collection","items":24}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })

    @Test fun addEnableAndRemovePersistAndNotifyHomeWithoutChangingWatchlist() = runTest {
        val prefs = Prefs(); val notifier = SettingsRefreshNotifier()
        val repo = MdbListRepository(api(), prefs, notifier)
        repo.addList(12, "Collection", "fixture-key")
        assertEquals(1, MdbListRepository(api(), prefs).getSavedLists().size)
        repo.toggleList(12, false)
        assertFalse(repo.getSavedLists().single().enabled)
        assertTrue(notifier.events.first() > 0)
        assertEquals(setOf("mdblist_shelves", "home_section_configs"), prefs.values.keys)
        assertTrue(Json.decodeFromString<List<HomeSectionConfig>>(prefs.values.getValue("home_section_configs")).single().enabled)
        repo.removeList(12)
        assertTrue(repo.getSavedLists().isEmpty())
    }

    @Test fun enablingAListShowsItsCurrentHomeSectionAndPreservesOtherLayoutChoices() = runTest {
        val prefs = Prefs()
        val key = HomeLayoutSourceSelector.KEY_SECTION_CONFIGS_DESKTOP
        prefs.values[HomeLayoutSourceSelector.KEY_LAYOUT_SOURCE] = HomeLayoutSourceSelector.DESKTOP_OWN
        val custom = HomeSectionConfig(HomeSection.TRENDING_MOVIES, false, 3, "My picks", "poster-small")
        val mdb = HomeSectionConfig(HomeSection.MDBLIST_SHELVES, false, 2, "My lists", "landscape-default")
        prefs.values[key] = Json.encodeToString(listOf(custom, mdb))
        prefs.values[HomeLayoutSourceSelector.KEY_SECTION_CONFIGS_SHARED] = "[]"
        val repo = MdbListRepository(api(), prefs)
        repo.addList(12, "Collection", "fixture-key")
        assertEquals(listOf(custom, mdb.copy(enabled = true)), Json.decodeFromString<List<HomeSectionConfig>>(prefs.values.getValue(key)))
        repo.toggleList(12, false)
        prefs.values[key] = Json.encodeToString(listOf(custom, mdb))
        repo.toggleList(12, true)
        assertEquals(listOf(custom, mdb.copy(enabled = true)), Json.decodeFromString<List<HomeSectionConfig>>(prefs.values.getValue(key)))
        assertEquals("[]", prefs.values[HomeLayoutSourceSelector.KEY_SECTION_CONFIGS_SHARED])
    }

    @Test fun unreadableHomeLayoutIsPreservedAndDoesNotSaveAnInvisibleList() = runTest {
        val prefs = Prefs()
        prefs.values[HomeLayoutSourceSelector.KEY_SECTION_CONFIGS_SHARED] = "invalid layout"
        val repo = MdbListRepository(api(), prefs)
        assertFails { repo.addList(12, "Collection", "fixture-key") }
        assertTrue(repo.getSavedLists().isEmpty())
        assertEquals("invalid layout", prefs.values[HomeLayoutSourceSelector.KEY_SECTION_CONFIGS_SHARED])
    }

    @Test fun failedListLookupDoesNotPersistAnUnusableCollection() = runTest {
        val prefs = Prefs(); val repo = MdbListRepository(api(HttpStatusCode.Unauthorized), prefs)
        assertFails { repo.addList(12, "Collection", "fixture-key") }
        assertTrue(repo.getSavedLists().isEmpty())
    }

    @Test fun mixedItemsUseRealPaginationAndTmdbIds() = runTest {
        val api = MdbListApi(HttpClient(MockEngine { request ->
            assertEquals("100", request.url.parameters["offset"])
            assertNull(request.url.parameters["page"])
            assertEquals("true", request.url.parameters["unified"])
            respond("""[{"id":45,"title":"Movie","mediatype":"movie","release_year":2026,"ids":{"tmdb":123}},{"id":123,"title":"Show","mediatype":"show"}]""",
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Has-More" to listOf("false")))
        }))
        val page = api.getListPage(12, "fixture-key", page = 2)
        assertFalse(page.hasMore)
        assertEquals(listOf(123, 123), page.items.map { it.tmdbId })
        assertEquals(2026, page.items.first().year)
        assertEquals(listOf("movie", "show"), page.items.map { it.mediaType })
    }

    @Test fun bucketResponsePreservesMovieAndShowTypes() = runTest {
        val api = MdbListApi(HttpClient(MockEngine {
            respond("""{"movies":[{"id":7,"title":"Movie"}],"shows":[{"id":7,"title":"Show"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        assertEquals(listOf("movie", "show"), api.getListItems(12, "fixture-key").map { it.mediaType })
    }
}
