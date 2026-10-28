package com.torve.android.ui.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvFocusPreviewGrid
import com.torve.android.tv.settings.setTvFocusPreview
import com.torve.android.tv.settings.tvFocusPreviewPolicy
import com.torve.android.tv.screens.scrollToComposedTvDetailsRow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.koin.mp.KoinPlatform

@OptIn(ExperimentalTestApi::class)
class TvDetailsRelatedNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private var owner: String? = null
    private var previousGlobalPreview = false

    @Before fun enableTheExpandedPreviewShownInTheReportedScreen() {
        owner = KoinPlatform.getKoin().get<SettingsViewModel>().state.value.personalDataUserId
        previousGlobalPreview = tvFocusPreviewPolicy(composeRule.activity, owner).global
        setTvFocusPreview(composeRule.activity, owner, "global", true)
    }

    @After fun restorePreviewPreference() {
        setTvFocusPreview(composeRule.activity, owner, "global", previousGlobalPreview)
    }

    @Test fun upFromRelatedPreviewRecomposesAndFocusesCastAboveTheGrid() {
        val castRequester = FocusRequester()
        val entryRequester = FocusRequester()
        val items = (0..7).map { MediaItem(id = "related-$it", title = "Related $it", type = MediaType.MOVIE) }
        composeRule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    item(key = "cast") {
                        Text("Cast destination", Modifier.focusRequester(castRequester).clickable {})
                    }
                    item(key = "spacer") { Column(Modifier.height(800.dp)) {} }
                    item(key = "related") {
                        TvFocusPreviewGrid(items, "details:related-navigation-test", 6,
                            railFocusRequester = castRequester, entryRequester = entryRequester,
                            modifier = Modifier.fillMaxWidth().height(430.dp),
                            onExitUp = {
                                scope.launch {
                                    list.scrollToComposedTvDetailsRow(0)
                                    withFrameNanos { }
                                    castRequester.requestFocus()
                                }
                            }, onClick = {})
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    list.scrollToComposedTvDetailsRow(2)
                    withFrameNanos { }
                    entryRequester.requestFocus()
                }
            }
        }
        val first = composeRule.onNodeWithTag("catalog_poster_card:related-0")
        first.assertIsDisplayed().assertIsFocused()
        // Moving back from row two must stay inside the grid, then the next UP
        // leaves it and restores a Cast node that the outer lazy list disposed.
        first.performKeyInput { pressKey(Key.DirectionDown) }
        val second = composeRule.onNodeWithTag("catalog_poster_card:related-6")
        second.assertIsFocused()
        second.performKeyInput { pressKey(Key.DirectionUp) }
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Cast destination").assertIsDisplayed().assertIsFocused()
    }
}
