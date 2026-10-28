package com.torve.android.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.home.HomeScreen
import com.torve.android.ui.settings.HomeLayoutScreen
import com.torve.domain.model.*
import com.torve.presentation.home.*
import com.torve.presentation.watching.*
import com.torve.data.auth.UserIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.koin.core.context.GlobalContext

class MobileHomeControlsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private fun freeze(vm: Any) { (vm.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(vm) as CoroutineScope).cancel() }
    @Suppress("UNCHECKED_CAST") private fun <T> set(vm: Any, field: String, value: T) {
        (vm.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>).value = value
    }
    @Test fun upNextFollowsLayoutAndBothVisibilityControlsWork() {
        lateinit var home: HomeViewModel
        lateinit var watching: WatchingHubViewModel
        var editing by mutableStateOf(false)
        val show = MediaItem(id = "test-show", title = "Test show", type = MediaType.SERIES)
        rule.runOnIdle {
            home = GlobalContext.get().get(); watching = GlobalContext.get().get()
            freeze(home); freeze(watching)
            set(home, "_sectionConfigs", listOf(HomeSectionConfig(HomeSection.UP_NEXT, true, 1)))
            set(home, "_customSections", emptyList<CustomSection>())
            set(home, "_homeLayoutOrder", listOf("addon:test", "section:UP_NEXT"))
            set(home, "_addonShelfVisibility", mapOf("test" to true))
            set(home, "_returningViewerHome", true)
            set(home, "_showPhoneResumeBar", false)
            set(home, "_state", HomeUiState(isLoading = false,
                addonShelves = listOf(CatalogShelf("test", "First shelf", listOf(show)))))
            set(watching, "_state", WatchingHubState(upNext = listOf(WatchingEpisode(show, 1, Episode(2, "Next episode"), null)),
                personalDataUserId = GlobalContext.get().get<UserIdProvider>().currentDataUserId()))
        }
        rule.setContent { MaterialTheme {
            if (editing) HomeLayoutScreen(onBack = { editing = false }, viewModel = home)
            else HomeScreen({}, viewModel = home, watching = watching, isActive = false)
        } }
        rule.onNodeWithTag("home_playback_shortcut").assertDoesNotExist()
        val shelfTop = rule.onNodeWithText("First shelf").fetchSemanticsNode().boundsInRoot.top
        val nextTop = rule.onNodeWithTag("home_up_next").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Up Next must follow the saved order even in compact mode", shelfTop < nextTop)
        rule.runOnIdle { editing = true }
        rule.onNodeWithTag("home_resume_bar_toggle").performClick().assertIsOn()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag("home_playback_shortcut").assertIsDisplayed()
        rule.runOnIdle { editing = true }
        rule.onNodeWithTag("home_resume_bar_toggle").performClick().assertIsOff()
        rule.onNodeWithTag("home_section_toggle_UP_NEXT").performScrollTo().performClick().assertIsOff()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag("home_playback_shortcut").assertDoesNotExist()
        rule.onNodeWithTag("home_up_next").assertDoesNotExist()
        rule.runOnIdle { assertFalse(home.showPhoneResumeBar.value); assertFalse(home.sectionConfigs.value.first { it.section == HomeSection.UP_NEXT }.enabled) }
    }
}
