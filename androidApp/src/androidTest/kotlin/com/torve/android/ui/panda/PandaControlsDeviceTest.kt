package com.torve.android.ui.panda

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.TorveTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PandaControlsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun remoteFieldsHaveOneFocusTargetAndKeepSavedValuesAfterDropdownChanges() {
        var savedUrl by mutableStateOf("https://indexer.example")
        var savedKey by mutableStateOf("")
        val first = FocusRequester()
        rule.setContent {
            TorveTheme {
                CompositionLocalProvider(LocalPandaTvClickToEditFields provides true) {
                    Column(Modifier.width(620.dp).background(Obsidian).padding(24.dp).testTag("panda.controls"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PandaEditableOutlinedTextField(savedUrl, { savedUrl = it },
                            Modifier.fillMaxWidth().testTag("url"), entryFocusRequester = first, label = { Text("Indexer URL") })
                        PandaEditableOutlinedTextField(savedKey, { savedKey = it },
                            Modifier.fillMaxWidth().testTag("api"), label = { Text("API key") })
                        var indexer by remember { mutableStateOf("custom") }
                        PandaDropdown(indexer, listOf("custom" to "Custom URL", "maps" to "Treasure Maps"),
                            { indexer = it }, label = "Indexer")
                    }
                    LaunchedEffect(Unit) { first.requestFocus() }
                }
            }
        }
        rule.onNodeWithTag("url").assertIsFocused()
        rule.onNodeWithTag("url").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("api").assertIsFocused()
        rule.onNodeWithTag("api").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("url").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNode(hasSetTextAction()).assertIsFocused().performTextReplacement("https://indexer.example/api")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.onNodeWithText("https://indexer.example/api").assertIsDisplayed()
        rule.onNodeWithTag("api").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("sample-api-key")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.onNodeWithText("Custom URL").performClick()
        rule.onNodeWithText("Treasure Maps").performClick()
        rule.onNodeWithText("https://indexer.example/api").assertIsDisplayed()
        rule.onNodeWithText("sample-api-key").assertIsDisplayed()
        rule.onNodeWithTag("url").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("discard-this-edit")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("https://indexer.example/api").assertIsDisplayed()
        rule.onNodeWithText("discard-this-edit").assertDoesNotExist()
        rule.runOnIdle { first.requestFocus() }
        rule.onNodeWithTag("panda.controls").captureToImage().asAndroidBitmap().let { bitmap ->
            File(rule.activity.cacheDir, "panda-focused-controls.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
