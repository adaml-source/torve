package com.torve.presentation.home

import com.torve.domain.model.*
import kotlin.test.*

class AppleHomeLayoutPolicyTest {
    private fun config(section: HomeSection, order: Int = section.defaultOrder, enabled: Boolean = true, title: String? = null) =
        HomeSectionConfig(section, enabled, order, title)
    private fun media(id: String, type: MediaType = MediaType.MOVIE) = MediaItem(id = id, title = id, type = type)
    private fun rows(state: HomeUiState = HomeUiState(), configs: List<HomeSectionConfig>,
                     custom: List<CustomSection> = emptyList(), order: List<String> = emptyList()) =
        AppleHomeLayoutPolicy.rows(state, configs, custom, order)

    @Test fun everyEnabledSectionSurvivesBeyondTheFirstSevenRows() {
        val configs = configurableHomeSections.map { config(it) }
        val result = rows(configs = configs)
        assertEquals(configs.size, result.size)
        assertEquals(configurableHomeSections.toSet(), result.mapNotNull { it.section }.toSet())
        assertTrue(result.any { it.section == HomeSection.ACTORS })
        assertTrue(result.any { it.section == HomeSection.DIRECTORS })
        assertTrue(result.any { it.section == HomeSection.STREAMING_SERVICES })
    }

    @Test fun disabledRowsAndLegacyPreferencesCannotReappearFromCatalogData() {
        val state = HomeUiState(shelves = listOf(CatalogShelf("trending-movies", "Trending", listOf(media("movie")))))
        assertTrue(rows(state, listOf(config(HomeSection.TRENDING_MOVIES, enabled = false),
            config(HomeSection.BECAUSE_YOU_WATCHED))).isEmpty())
    }

    @Test fun savedCombinedOrderIncludesCustomRowsAndRetainsNewlyAddedSections() {
        val configs = listOf(config(HomeSection.HERO, 0), config(HomeSection.ACTORS, 5), config(HomeSection.DIRECTORS, 6))
        val custom = listOf(CustomSection("crime", "Crime", order = 1))
        val result = rows(configs = configs, custom = custom,
            order = listOf("custom:crime", "section:ACTORS", "section:HERO", "section:HERO"))
        assertEquals(listOf("custom:crime", "section:ACTORS", "section:HERO", "section:DIRECTORS"), result.map { it.key })
    }

    @Test fun configuredOrderAndCustomTitlesAreAppliedWhenThereIsNoCombinedOrder() {
        val result = rows(configs = listOf(config(HomeSection.HERO, 8), config(HomeSection.ACTORS, 0, title = "People I like")))
        assertEquals(listOf(HomeSection.ACTORS, HomeSection.HERO), result.map { it.section })
        assertEquals("People I like", result.first().title)
    }

    @Test fun peopleAndStreamingRowsHaveTheirOwnIdentityAndUseActualPeopleData() {
        val actor = PersonSummary(10, "Actor", "https://example.invalid/actor.jpg")
        val director = PersonSummary(11, "Director")
        val result = rows(HomeUiState(popularActors = listOf(actor), popularDirectors = listOf(director)),
            listOf(config(HomeSection.ACTORS), config(HomeSection.DIRECTORS), config(HomeSection.STREAMING_SERVICES)))
        assertEquals(listOf(actor), result.first { it.section == HomeSection.ACTORS }.people)
        assertEquals(listOf(director), result.first { it.section == HomeSection.DIRECTORS }.people)
        assertEquals(3, result.map { it.key }.distinct().size)
    }

    @Test fun independentWatchlistRowsRespectMediaTypeIncludingLegacyShelfData() {
        val movie = media("movie")
        val show = media("show", MediaType.SERIES)
        val result = rows(HomeUiState(watchlistShelf = CatalogShelf("watchlist", "Saved", listOf(movie, show))),
            listOf(config(HomeSection.WATCHLIST_MOVIES), config(HomeSection.WATCHLIST_TV)))
        assertEquals(listOf(movie), result.first { it.section == HomeSection.WATCHLIST_MOVIES }.items)
        assertEquals(listOf(show), result.first { it.section == HomeSection.WATCHLIST_TV }.items)
    }

    @Test fun selectedAddonsAndListsExpandToEveryVisibleShelfWithoutATitleCollision() {
        val shelves = (1..12).map { CatalogShelf("addon-$it", "Same title", listOf(media("$it"))) }
        val state = HomeUiState(addonShelves = shelves, addonShelfVisibility = mapOf("addon-3" to false),
            mdbListShelves = listOf(CatalogShelf("list-1", "Same title", listOf(media("list")))))
        val result = rows(state, listOf(config(HomeSection.ADDON_SHELVES), config(HomeSection.MDBLIST_SHELVES)))
        assertEquals(12, result.size)
        assertEquals(12, result.map { it.key }.distinct().size)
        assertFalse(result.any { it.key == "addon:addon-3" })
        assertTrue(rows(state, listOf(config(HomeSection.ADDON_SHELVES, enabled = false))).isEmpty())
    }

    @Test fun customAndSpecialRowsCanOpenTheSameContentInsteadOfAnUnsupportedSeeAllCategory() {
        val state = HomeUiState(customShelves = mapOf("crime" to listOf(media("crime"))), recentlyWatched = listOf(media("watched")))
        val result = rows(state, listOf(config(HomeSection.RECENTLY_WATCHED)), custom = listOf(CustomSection("crime", "Crime")))
        assertEquals("custom:crime", result.first { it.key == "custom:crime" }.seeAllSection)
        assertEquals("shelf:section:RECENTLY_WATCHED", result.first { it.section == HomeSection.RECENTLY_WATCHED }.seeAllSection)
        assertEquals(listOf(media("crime")), result.first { it.key == "custom:crime" }.items)
    }

    @Test fun hiddenCatalogueRemainsEditableAndKeepsItsPositionBetweenBuiltInRows() {
        val state = HomeUiState(addonShelves = listOf(CatalogShelf("netflix", "Netflix", listOf(media("film")))) ,
            addonShelfVisibility = mapOf("netflix" to false))
        val configs = listOf(config(HomeSection.HERO), config(HomeSection.ADDON_SHELVES), config(HomeSection.POPULAR_MOVIES))
        val order = listOf("section:HERO", "addon:netflix", "section:POPULAR_MOVIES", "section:ADDON_SHELVES")
        val editor = AppleHomeLayoutPolicy.editorRows(state, configs, emptyList(), order)
        assertEquals(order, editor.map { it.key })
        assertFalse(editor.single { it.key == "addon:netflix" }.enabled)
        assertFalse(rows(state, configs, order = order).any { it.key == "addon:netflix" })
        val visible = state.copy(addonShelfVisibility = mapOf("netflix" to true))
        assertEquals(order.take(3), rows(visible, configs, order = order).map { it.key })
    }

    @Test fun savedCatalogueAppearanceAndVisibilityOverrideTheGroupAndLegacyPreferences() {
        val state = HomeUiState(addonShelves = listOf(
            CatalogShelf("movies", "Netflix", listOf(media("film"))),
            CatalogShelf("shows", "Netflix", listOf(media("show", MediaType.SERIES)))),
            addonShelfVisibility = mapOf("movies" to false))
        val group = config(HomeSection.ADDON_SHELVES).copy(presetId = "group-style", catalogueRows = mapOf(
            "movies" to HomeCatalogueRowConfig("My movies", "landscape", enabled = true),
            "shows" to HomeCatalogueRowConfig(enabled = false)))
        val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<HomeSectionConfig>(), group)
        val restored = kotlinx.serialization.json.Json.decodeFromString<HomeSectionConfig>(encoded)
        val result = rows(state, listOf(restored))
        assertEquals("My movies", result.single().title)
        assertEquals("landscape", result.single().presetId)
        val editor = AppleHomeLayoutPolicy.editorRows(state, listOf(restored), emptyList(), emptyList())
        assertEquals(listOf("Movies", "TV shows"), editor.filter { it.key.startsWith("addon:") }.map { it.subtitle })
        assertFalse(editor.single { it.key == "addon:shows" }.enabled)
        assertEquals("group-style", rows(state, listOf(group.copy(catalogueRows = mapOf(
            "movies" to HomeCatalogueRowConfig(enabled = true), "shows" to HomeCatalogueRowConfig(enabled = false)))))
            .single().presetId)
    }

    @Test fun reorderingEditorRowsRetainsTemporarilyUnavailableCataloguesAndUnrelatedKeys() {
        val saved = listOf("section:HERO", "addon:offline", "addon:netflix", "custom:winter")
        val changed = AppleHomeLayoutPolicy.reorderedKeys(saved, listOf("custom:winter", "addon:netflix", "section:HERO"))
        assertEquals(listOf("custom:winter", "addon:offline", "addon:netflix", "section:HERO"), changed)
        assertEquals(listOf("addon:netflix", "section:HERO"),
            AppleHomeLayoutPolicy.reorderedKeys(emptyList(), listOf("addon:netflix", "section:HERO", "section:HERO")))
    }

    @Test fun catalogueDefaultsIdentifyMoviesAndShowsAndCustomNamesRemainExact() {
        val state = HomeUiState(addonShelves = listOf(
            CatalogShelf("netflix-movies", "Netflix", listOf(media("film"))),
            CatalogShelf("netflix-series", "Netflix", listOf(media("show", MediaType.SERIES)))))
        val config = config(HomeSection.ADDON_SHELVES)
        val defaults = rows(state, listOf(config))
        assertEquals(listOf("Netflix · Movies", "Netflix · TV shows"), defaults.map { it.title })
        assertEquals(defaults.map { it.title }, AppleHomeLayoutPolicy.editorRows(state, listOf(config), emptyList(), emptyList())
            .filter { it.key.startsWith("addon:") }.map { it.title })
        assertEquals("My Netflix", rows(state, listOf(config.copy(catalogueRows = mapOf(
            "netflix-movies" to HomeCatalogueRowConfig(customTitle = "My Netflix"))))).first().title)
    }

    @Test fun namedTypesAndMixedOrEmptyCataloguesAreNotMislabelled() {
        for ((title, items) in listOf(
            "Popular movies" to listOf(media("movie")),
            "TV shows" to listOf(media("show", MediaType.SERIES)),
            "Mixed" to listOf(media("movie"), media("show", MediaType.SERIES)),
            "Empty" to emptyList())) {
            assertEquals(title, AppleHomeLayoutPolicy.catalogueTitle(CatalogShelf("id", title, items)))
        }
    }
}
