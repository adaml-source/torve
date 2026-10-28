package com.torve.android.ui.mobile

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.community.*
import com.torve.android.ui.components.MobileBottomDestination
import com.torve.android.ui.components.mobileBottomLabelRes
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.android.ui.setup.SourceSetupScreen
import com.torve.android.ui.setup.UsenetConnectionForm
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.settings.ThemeMode
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.mp.KoinPlatform.getKoin

class MobileSourceSetupDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val languages = listOf("en", "de", "es", "fr", "it", "pt", "tr")

    @Composable private fun Localized(language: String, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language)) }
        val context = LocalContext.current.createConfigurationContext(configuration)
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
            TorveTheme(ThemeMode.DARK) { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } }
        }
    }

    @Test fun everyLanguageCanOpenUsenetDirectlyAndReturnWithoutTheOldWizard() {
        var language by mutableStateOf("en")
        val actions = mutableListOf<String>()
        var accepted by mutableStateOf(false)
        rule.setContent { Localized(language) {
            SourceSetupScreen({}, { actions += it }, accepted, { accepted = true })
        } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_setup.usenet_setup"))
        rule.onNodeWithTag("source_setup.usenet_setup").assertIsNotEnabled()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_setup.terms"))
        rule.onNodeWithTag("source_setup.terms").performClick()
        for (locale in languages) {
            rule.runOnIdle { language = locale }
            rule.onNodeWithTag("source_setup.usenet_setup").performScrollTo().assertIsEnabled().performClick()
            rule.runOnIdle { assertEquals("usenet_setup", actions.last()) }
            rule.onAllNodes(hasText("Panda", substring = true, ignoreCase = true)).assertCountEquals(0)
            capture("source-$locale")
        }
    }

    @Test fun settingsSubmenusAreLocalizedInEveryLanguage() {
        var language by mutableStateOf("en")
        var node by mutableStateOf<String?>(null)
        val entries = SettingsMenus.sources()
        rule.setContent { Localized(language) {
            PhoneGroupedSettingsMenu(entries, "Sources & services", node, { node = it }, {}, {})
        } }
        for (locale in languages) {
            rule.runOnIdle { language = locale; node = null }
            rule.onNodeWithTag("settings.source.streaming").assertIsDisplayed().performClick()
            rule.onNodeWithTag("settings.source.usenet_setup").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Panda", substring = true).assertDoesNotExist()
            capture("settings-$locale")
        }
    }

    @Test fun allSixNavigationCaptionsFitAt320dpAndKeepFullAccessibleNames() {
        var language by mutableStateOf("")
        val clipped = mutableListOf<String>()
        val resources = listOf(R.string.nav_home, R.string.nav_channels, R.string.nav_search,
            R.string.nav_calendar, R.string.nav_library, R.string.nav_settings)
        val labels = mutableListOf<Pair<String, String>>()
        rule.setContent { Localized(language) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .mobileGlassSurface(androidx.compose.foundation.shape.RoundedCornerShape(34.dp)).padding(6.dp)) {
                resources.forEach { id ->
                    val label = stringResource(mobileBottomLabelRes(id))
                    val full = stringResource(id)
                    SideEffect { if (labels.none { it.first == label }) labels += label to full }
                    val icon = when (id) {
                        R.string.nav_home -> Icons.Default.Home
                        R.string.nav_channels -> Icons.Default.LiveTv
                        R.string.nav_search -> Icons.Default.Search
                        R.string.nav_calendar -> Icons.Default.CalendarMonth
                        R.string.nav_library -> Icons.Default.Bookmark
                        else -> Icons.Default.Settings
                    }
                    MobileBottomDestination(label, icon, id == R.string.nav_settings,
                        {}, Modifier.weight(1f), accessibilityLabel = full)
                }
            }
            }
        } }
        for (locale in languages) {
            rule.runOnIdle { labels.clear(); language = locale }
            rule.waitForIdle()
            val localeLabels = labels.toList()
            assertEquals(6, localeLabels.size)
            localeLabels.forEach { (caption, full) ->
                rule.onNodeWithContentDescription(full).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag("bottom_label_$caption", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("No text layout: $locale/$caption", layouts.isNotEmpty())
                if (layouts.first().isLineEllipsized(0)) clipped += "$locale/$caption"
            }
            capture("navigation-$locale")
        }
        assertTrue("Captions clipped: $clipped", clipped.isEmpty())
    }

    @Test fun usenetFormShowsProviderIndexerAndDownloadClientInEveryLanguage() {
        var language by mutableStateOf("en")
        val vm = getKoin().get<PandaSetupViewModel>()
        val fixture = PandaSetupUiState(enableUsenet = true, usenetProvider = "generic")
        rule.setContent { Localized(language) { UsenetConnectionForm(fixture, vm) } }
        for (locale in languages) {
            rule.runOnIdle { language = locale }
            val configuration = Configuration(rule.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val localized = rule.activity.createConfigurationContext(configuration)
            rule.onNodeWithText(localized.getString(R.string.panda_setup_usenet_indexer)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(localized.getString(R.string.panda_setup_usenet_client)).performScrollTo().assertIsDisplayed()
            rule.onAllNodes(hasText("Panda", substring = true, ignoreCase = true)).assertCountEquals(0)
            capture("usenet-$locale")
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "source-setup-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
