package com.torve.android.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.catalog.CatalogScreen
import com.torve.android.ui.home.HomeScreen
import com.torve.android.ui.settings.HomeLayoutScreen
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.koin.core.context.GlobalContext
import java.lang.reflect.Proxy

class MobileBrowseStabilityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    // Freeze background fetching in this isolated debug process, then drive the actual screens
    // with deterministic content. Production setters still handle the editor interactions.
    private fun homeModel(): HomeViewModel = GlobalContext.get().get<HomeViewModel>().also { vm ->
        HomeViewModel::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(vm).let { (it as CoroutineScope).cancel() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> setHome(vm: HomeViewModel, field: String, value: T) {
        (HomeViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }
            .get(vm) as MutableStateFlow<T>).value = value
    }
    private val movies = (1..30).map { MediaItem(id = "stability-$it", title = "Title $it", type = MediaType.MOVIE) }

    @Test fun extensionDragAndVisibilityAreReflectedOnHome() {
        lateinit var home: HomeViewModel
        var editing by mutableStateOf(true)
        rule.runOnIdle {
            home = homeModel()
            setHome(home, "_sectionConfigs", emptyList<HomeSectionConfig>())
            setHome(home, "_customSections", emptyList<CustomSection>())
            setHome(home, "_homeLayoutOrder", listOf("addon:a", "addon:b"))
            setHome(home, "_addonShelfVisibility", mapOf("a" to true, "b" to true))
            setHome(home, "_state", HomeUiState(isLoading = false,
                addonShelves = listOf(CatalogShelf("a", "Extension A", movies), CatalogShelf("b", "Extension B", movies)),
                // A stale content snapshot must not override the editor's current preference.
                addonShelfVisibility = mapOf("a" to false, "b" to false)))
        }
        rule.setContent { MaterialTheme {
            if (editing) HomeLayoutScreen(onBack = { editing = false }, viewModel = home)
            else HomeScreen(onMediaClick = {}, viewModel = home, isActive = false)
        } }
        rule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            swipe(center, Offset(center.x, center.y + 170f), 600)
        }
        rule.runOnIdle { assertEquals(listOf("addon:b", "addon:a"), home.homeLayoutOrder.value) }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Extension B").assertIsDisplayed()
        rule.onNodeWithText("Extension A").assertIsDisplayed()
        val b = rule.onNodeWithText("Extension B").fetchSemanticsNode().boundsInRoot.top
        val a = rule.onNodeWithText("Extension A").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Home must follow the extension order from Settings", b < a)
        rule.runOnIdle { home.toggleAddonShelfVisibility("b") }
        rule.onNodeWithText("Extension B").assertDoesNotExist()
        rule.onNodeWithText("Extension A").assertIsDisplayed()
    }

    @Test fun movieFiltersKeepTheirScreenPosition() = verifyFilters("movie", "Action")
    @Test fun tvFiltersKeepTheirScreenPosition() = verifyFilters("tv", "Action & Adventure")

    private fun verifyFilters(type: String, genre: String) {
        val repo = Proxy.newProxyInstance(MetadataRepository::class.java.classLoader,
            arrayOf(MetadataRepository::class.java)) { _, method, _ ->
            when {
                method.name == "discover" || method.name.endsWith("Paged") -> PagedResult(movies, 1, 1, movies.size)
                method.name == "toString" -> "StabilityMetadata"
                else -> emptyList<MediaItem>()
            }
        } as MetadataRepository
        lateinit var catalog: CatalogViewModel
        lateinit var home: HomeViewModel
        rule.runOnIdle {
            home = homeModel()
            setHome(home, "_state", HomeUiState(isLoading = false))
            catalog = CatalogViewModel(repo, type)
        }
        try {
            rule.setContent { MaterialTheme {
                CatalogScreen(viewModel = catalog, mediaType = type, onMediaClick = {}, isActive = false, homeViewModel = home)
            } }
            rule.waitUntil(5_000) { !catalog.state.value.isLoading }
            rule.onNodeWithTag("catalog_grid").performScrollToIndex(1)
            val before = rule.onNode(hasText("Trending") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
            rule.onNodeWithText(genre).performClick()
            rule.waitForIdle()
            assertEquals(before, rule.onNode(hasText("Trending") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.onAllNodesWithText("Popular")[0].performClick()
            rule.waitForIdle()
            assertEquals(before, rule.onNode(hasText("Trending") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally { rule.runOnIdle { catalog.close() } }
    }
}
