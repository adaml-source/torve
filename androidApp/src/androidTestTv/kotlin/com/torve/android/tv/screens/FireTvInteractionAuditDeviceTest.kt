package com.torve.android.tv.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.community.ExtensionsScreen
import com.torve.android.ui.replays.*
import com.torve.android.ui.theme.TorveTheme
import com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine
import com.torve.data.replays.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FireTvInteractionAuditDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    private fun waitFocus(node: SemanticsNodeInteraction) {
        try { compose.waitUntil(5_000) { runCatching { node.assertIsFocused() }.isSuccess } }
        catch (error: Throwable) {
            screenshot("settings-focus-failure")
            java.io.File(compose.activity.getExternalFilesDir(null), "settings-restored-failure-semantics.txt")
                .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw error
        }
    }
    @Composable private fun panel(publish: (FocusRequester) -> Unit = {}, extensions: () -> Unit = {}) {
        TvAlignedSettingsPanel(rail = remember { FocusRequester() }, entry = null, isActive = true,
            railFocused = false, publish = publish, contentFocused = {}, moveToRail = {},
            extensions = extensions, mdbList = {}, appearance = {}, home = {}, ratings = {}, profiles = {},
            backup = {}, receive = {}, jellyfin = {}, collections = {},
            legacyForm = { _, _, _, _ -> Text("Other account controls") })
    }

    @Test fun extensionsStartOnAFilterAndReturnToTheirSourcesMenuItem() {
        var published: FocusRequester? = null
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) {
            var external by remember { mutableStateOf(false) }
            val holder = rememberSaveableStateHolder()
            if (external) ExtensionsScreen({ external = false }, isTv = true)
            else holder.SaveableStateProvider("settings") { panel({ published = it }, { external = true }) }
        } }
        compose.onNodeWithTag("settings.item.streaming").performClick()
        compose.onNodeWithTag("settings.item.extensions").performClick()
        waitFocus(compose.onNodeWithTag("extensions.section.0"))
        screenshot("extensions-filter-entry")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("settings.item.extensions").assertExists() }.isSuccess }
        compose.runOnIdle { checkNotNull(published).requestFocus() }
        waitFocus(compose.onNodeWithTag("settings.item.extensions"))
    }

    @Test fun settingsRememberTheFormControlAndScrollAcrossCategoriesAndBack() {
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) { panel() } }
        compose.onNodeWithTag("settings.category.playback").performClick()
        compose.onNodeWithTag("settings.item.advanced_playback").performClick()
        val selected = compose.onNodeWithText("Unknown source language")
        selected.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        waitFocus(selected)
        val top = selected.getUnclippedBoundsInRoot().top
        selected.performKeyInput { pressKey(Key.DirectionLeft) }
        waitFocus(compose.onNodeWithTag("settings.category.playback"))
        compose.onNodeWithTag("settings.category.playback").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("settings.menu.connections").assertExists()
        compose.onNodeWithTag("settings.category.playback").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        waitFocus(selected)
        assertTrue("Returning must retain the control's scroll position", kotlin.math.abs(top.value - selected.getUnclippedBoundsInRoot().top.value) < 4f)
        screenshot("settings-restored-control")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFocus(compose.onNodeWithTag("settings.item.advanced_playback"))
        compose.onNodeWithTag("settings.item.advanced_playback").performClick()
        waitFocus(selected)
    }

    @Test fun emailRowImmediatelyEditsAndNextOpensPassword() {
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) {
            TvSettingsScreen(railFocusRequester = remember { FocusRequester() }, onFirstContentRequester = {}, onContentFocused = {},
                embeddedCategory = TvSettingsCategory.ACCOUNT,
                settingsFocusController = rememberTvSettingsFocusStateMachine("audit-sign-in", TvSettingsCategory.ACCOUNT))
        } }
        compose.onNodeWithText("Email").performScrollTo().performClick()
        val email = compose.onNodeWithTag("account.entry.email")
        waitFocus(email)
        email.performTextInput("viewer@example.invalid")
        email.performImeAction()
        waitFocus(compose.onNodeWithTag("account.entry.password"))
        compose.onNodeWithTag("account.entry.email").assertDoesNotExist()
        screenshot("account-password-ready")
        // Cancel before submission: no real account or credentials are changed.
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun confirmingPasswordSubmitsOnceAndOrdinaryFocusDoesNotEdit() {
        var submits = 0
        var emailValue = ""
        var passwordValue = ""
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) {
            var step by remember { mutableStateOf<TvAccountEntryStep?>(null) }
            Button({ step = TvAccountEntryStep.EMAIL }) { Text("Email entry") }
            step?.let { current -> TvAccountEntryDialog(current, "", false, onConfirm = { value ->
                if (current == TvAccountEntryStep.EMAIL) { emailValue = value; step = TvAccountEntryStep.PASSWORD }
                else { passwordValue = value; step = null; submits++ }
            }, onDismiss = { step = null }) }
            if (submits > 0) Text("Submitted once")
        } }
        compose.onNodeWithText("Email entry").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("account.entry.email").assertDoesNotExist()
        compose.onNodeWithText("Email entry").performClick()
        waitFocus(compose.onNodeWithTag("account.entry.email"))
        compose.onNodeWithTag("account.entry.email").performTextInput("viewer@example.invalid")
        compose.onNodeWithTag("account.entry.email").performImeAction()
        waitFocus(compose.onNodeWithTag("account.entry.password"))
        compose.onNodeWithTag("account.entry.password").performTextInput("test-only-password")
        compose.onNodeWithTag("account.entry.password").performImeAction()
        compose.onNodeWithText("Submitted once").assertIsDisplayed()
        assertEquals(1, submits)
        assertEquals("viewer@example.invalid", emailValue)
        assertTrue(passwordValue.isNotBlank())
    }

    @Test fun replayUpAndDownVisitHighlightsBetweenBackAndEverySource() {
        val options = (1..3).map { ReplaySourceOption("$it", "Server $it", "https://example.invalid/$it", "https://example.invalid") }
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) {
            ReplayDetailContent(ReplayEvent("qa-f1", "qa", "United States Grand Prix — Race replay", "https://example.invalid",
                competition = "Formula 1", date = "2026-10-25"), true, options, false, false,
                {}, {}, {}, {}, {})
        } }
        val back = compose.onNodeWithTag("replay.back")
        waitFocus(back)
        back.performKeyInput { pressKey(Key.DirectionDown) }
        waitFocus(compose.onNodeWithTag("replay.highlights"))
        compose.onNodeWithTag("replay.highlights").performKeyInput { pressKey(Key.DirectionDown) }
        waitFocus(compose.onNodeWithTag("replay.source.1"))
        compose.onNodeWithTag("replay.source.1").performKeyInput { pressKey(Key.DirectionUp) }
        waitFocus(compose.onNodeWithTag("replay.highlights"))
        compose.onNodeWithTag("replay.highlights").performKeyInput { pressKey(Key.DirectionUp) }
        waitFocus(back)
        back.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("replay.highlights").performKeyInput { pressKey(Key.DirectionDown) }
        for (index in 1..2) {
            waitFocus(compose.onNodeWithTag("replay.source.$index"))
            compose.onNodeWithTag("replay.source.$index").performKeyInput { pressKey(Key.DirectionDown) }
        }
        waitFocus(compose.onNodeWithTag("replay.source.3"))
        screenshot("replay-glass-actions")
        for (index in 3 downTo 2) {
            compose.onNodeWithTag("replay.source.$index").performKeyInput { pressKey(Key.DirectionUp) }
            waitFocus(compose.onNodeWithTag("replay.source.${index - 1}"))
        }
    }

    @Test fun highlightCardsKeepUsefulNamesDurationsAndNoScoreHeadlines() {
        var selected = false
        compose.setContent { TorveTheme(com.torve.presentation.settings.ThemeMode.DARK) { Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Highlights", style = MaterialTheme.typography.headlineMedium)
            ReplayHighlightChoice(ReplayHighlight("gtfEW_f1-b8", "Paris wins 99-90! Full game highlights", "EuroLeague", 590,
                isOfficial = true, matchupLabel = "Paris vs Valencia", eventDate = "2026-10-24", competitionLabel = "EuroLeague"), true) { selected = true }
            ReplayHighlightChoice(ReplayHighlight("M7lc1UVf-VE", "Extended highlights", "EuroLeague", 1220,
                isOfficial = true, matchupLabel = "Paris vs Valencia"), true) {}
        } } }
        compose.onNodeWithText("99-90", substring = true).assertDoesNotExist()
        compose.onNodeWithText("9:50").assertIsDisplayed()
        compose.onNodeWithText("20:20").assertIsDisplayed()
        compose.onNodeWithTag("highlight.choice.gtfEW_f1-b8").performSemanticsAction(SemanticsActions.RequestFocus)
        screenshot("highlights-glass-cards")
        compose.onNodeWithTag("highlight.choice.gtfEW_f1-b8").performClick()
        assertTrue(selected)
    }
}
