package com.torve.android.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.product.MobileSearchField
import com.torve.android.ui.theme.TorveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchAiControlsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun togglePreservesQueryAndSearchRequiresExplicitSubmission() {
        var enabled by mutableStateOf(false)
        var query by mutableStateOf("")
        var searching by mutableStateOf(false)
        var submissions = 0
        val submit = { submissions++; searching = true }
        rule.setContent {
            TorveTheme {
                Column(Modifier.width(360.dp)) {
                    MobileSearchField(query, { query = it }, { query = "" }, submit, emptyList(), {})
                    SearchAiControls(enabled, query.length >= 2, searching, { enabled = it }, submit)
                }
            }
        }
        rule.onNodeWithTag("search.ai.toggle").assertIsOff().performClick()
        rule.onNodeWithTag("search.ai.submit").assertIsNotEnabled()
        rule.onNodeWithTag("mobile_search_input").performTextInput("dark sci-fi from the 1990s")
        rule.runOnIdle { assertEquals(0, submissions) }
        rule.onNodeWithTag("search.ai.submit").assertIsEnabled().performClick()
        rule.onNodeWithTag("search.ai.submit").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, submissions); searching = false }
        rule.onNodeWithTag("mobile_search_input").performImeAction()
        rule.runOnIdle { assertEquals(2, submissions) }
        rule.onNodeWithTag("search.ai.toggle").performClick().assertIsOff()
        rule.onNodeWithTag("search.ai.submit").assertDoesNotExist()
        rule.runOnIdle { assertEquals("dark sci-fi from the 1990s", query) }
    }
}
