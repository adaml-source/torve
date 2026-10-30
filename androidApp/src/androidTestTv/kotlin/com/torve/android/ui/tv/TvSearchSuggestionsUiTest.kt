package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.TvSearchQueryPopup
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.domain.repository.MetadataRepository
import org.koin.core.context.GlobalContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvSearchSuggestionsUiTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val candice = MediaItem("candice", type = MediaType.SERIES, title = "Candice Renoir", voteCount = 6000)

    @Test fun livePrefixCompletionResolvesCandiceRenoir() {
        org.junit.Assume.assumeTrue("Opt in to the live catalogue check", InstrumentationRegistry.getArguments().getString("liveTitleCompletions") == "true")
        val results = runBlocking {
            withTimeout(15_000) { GlobalContext.get().get<MetadataRepository>().titleSuggestions("Candi", "tv").last() }
        }
        assertTrue("Candi must resolve the catalogue record for Candice Renoir", results.any { it.title.equals("Candice Renoir", true) && it.type == MediaType.SERIES && it.tmdbId != null })
    }

    @Test fun partialInputOffersASelectableTitleCompletion() {
        var submitted: String? = null
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme {
            TvSearchQueryPopup("Candi", false, false, emptyList(), { submitted = it }, {}, {}, {}, {},
                suggestTitles = { text -> assertEquals("Candi", text); flowOf(listOf(candice)) })
        } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("search_suggestion:candice").fetchSemanticsNodes().isNotEmpty() }
        java.io.File(rule.activity.getExternalFilesDir(null), "search-candi-suggestions.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithTag("search_query_input").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("search_suggestion:candice").assertIsFocused().assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("Candice Renoir", submitted) }
    }

    @Test fun typingChangesTheCompletionRequestWithoutSubmittingTheSearch() {
        val requests = mutableListOf<String>()
        var submissions = 0
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent { MaterialTheme {
            TvSearchQueryPopup("", false, false, emptyList(), { submissions++ }, {}, {}, {}, {},
                suggestTitles = { text -> requests += text; flowOf(if (text == "Candi") listOf(candice) else emptyList()) })
        } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("search_query_input").assertIsFocused() }.isSuccess }
        rule.onNodeWithTag("search_query_input").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val editor = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()
        editor.performTextInput("Candi")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("search_suggestion:candice").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.contains("Candi")); assertEquals(0, submissions)
        editor.performTextReplacement("Xyz")
        rule.waitUntil(5_000) { requests.contains("Xyz") }
        rule.onNodeWithTag("search_suggestion:candice").assertDoesNotExist()
        assertEquals(0, submissions)
    }
}
