package com.torve.android.tv.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.*
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.Locale

/** Checks actual layouts and remote navigation in every supported language. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FireTvLanguageAuditDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()

    private fun assertVisibleTextFits() {
        compose.onAllNodes(hasText("", substring = true) and hasAnyAncestor(hasTestTag("audit.grouped.root")), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            val results = mutableListOf<TextLayoutResult>()
            action?.invoke(results)
            results.forEach { result ->
                // Font metrics can exceed the rounded integer layout by a fraction
                // of one pixel. Real ellipsis/extra lines still fail this check.
                val clipped = (0 until result.lineCount).any { line ->
                    result.isLineEllipsized(line) || result.getLineLeft(line) < -1f ||
                        result.getLineRight(line) > result.size.width + 1f ||
                        result.getLineBottom(line) > result.size.height + 1f
                }
                assertFalse("Clipped text: ${result.layoutInput.text.text}; size=${result.size}; constraints=${result.layoutInput.constraints}; lines=${result.lineCount}; paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}", clipped)
            }
        }
    }

    @Test fun everyLanguageFitsEveryGroupedMenu_andBackRetainsItsOrigin() {
        val language = mutableStateOf("en")
        compose.setContent {
            val base = LocalContext.current
            val config = remember(language.value) { Configuration(base.resources.configuration).apply { setLocale(Locale(language.value)) } }
            val context = remember(config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                MaterialTheme {
                    val rail = remember { FocusRequester() }
                    Row {
                        Button({}, Modifier.width(60.dp).focusRequester(rail)) { Text("Home") }
                        Box(Modifier.weight(1f).testTag("audit.grouped.root")) {
                            TvGroupedSettingsBrowser(rail, null, true, false, {}, {}, {}, { false }) { _, first, _, _, _, _ ->
                                Button({}, Modifier.focusRequester(first).testTag("audit.form")) { Text(tvSettingsLabel("Save")) }
                            }
                        }
                    }
                }
            }
        }
        val expected = mapOf(
            "en" to "Playback", "de" to "Wiedergabe", "es" to "Reproducción", "fr" to "Lecture",
            "it" to "Riproduzione", "pt" to "Reprodução", "tr" to "Oynatma",
        )
        fun back() { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
        fun visit(children: List<com.torve.android.ui.community.SettingsMenuEntry>) {
            children.forEach { item ->
                compose.onNodeWithTag("settings.item.${item.id}").performScrollTo().assertIsDisplayed().performClick()
                compose.waitForIdle()
                assertVisibleTextFits()
                if (item.children.isNotEmpty()) visit(item.children)
                back()
                compose.onNodeWithTag("settings.item.${item.id}").assertIsFocused()
            }
        }
        AppLanguage.entries.forEach { locale ->
            compose.runOnIdle { language.value = locale.code }
            compose.waitForIdle()
            compose.onNodeWithTag("settings.category.playback").assertTextContains(expected.getValue(locale.code))
            tvSettingsGroups.forEach { category ->
                compose.onNodeWithTag("settings.category.${category.id}").performClick()
                compose.waitForIdle()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(compose.activity.getExternalFilesDir(null), "audit-${locale.code}-${category.id}.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertVisibleTextFits()
                visit(category.children)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "TV_LANGUAGE_AUDIT language=${locale.code} allGroupedMenusFit=true backOriginRestored=true\n")
            })
        }
    }

    @Test fun localizedPresentationIsRenderedByTheActualMediaRail() {
        val vm: SettingsViewModel = GlobalContext.get().get()
        compose.waitUntil(60_000) { vm.state.value.playbackSettingsLoaded }
        val originalLanguage = vm.state.value.appLanguage
        assertTrue("Existing QA metadata consent is required", vm.state.value.tmdbEnrichmentEnabled)
        val source = MediaItem("audit:provider:238", type = MediaType.MOVIE, title = "The Godfather", tmdbId = 238,
            imdbId = "tt0068646", posterUrl = "https://example.invalid/english.jpg")
        val translated = source.copy(title = "Der Pate", posterUrl = "https://example.invalid/german.jpg")
        compose.runOnIdle {
            vm.setAppLanguage(AppLanguage.GERMAN)
            cacheTvLocalizedPresentation(tvLocalizedPresentationKey(source, vm.state.value.personalDataUserId, "de"), translated)
        }
        try {
            compose.setContent {
                MaterialTheme {
                    TvMediaRails(listOf(TvContentRail("audit-localization", "", listOf(source))),
                        remember { FocusRequester() }, {}, {}, {}, screenId = "audit-localization",
                        shouldAutoFocus = false, browseLayout = TvBrowseLayout.POSTER_ONLY, forcePosterTitles = true)
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Der Pate").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Der Pate").assertIsDisplayed()
            compose.onAllNodesWithText("The Godfather").assertCountEquals(0)
        } finally {
            compose.runOnIdle { vm.setAppLanguage(originalLanguage) }
            val prefs: com.torve.domain.repository.PreferencesRepository = GlobalContext.get().get()
            compose.waitUntil(15_000) { runBlocking { prefs.getString(SettingsViewModel.KEY_APP_LANGUAGE) } == originalLanguage.name }
        }
    }
}
