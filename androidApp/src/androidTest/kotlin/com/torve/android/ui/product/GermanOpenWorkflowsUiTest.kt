package com.torve.android.ui.product

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.*
import com.torve.android.ui.system.TorveModalBottomSheet
import com.torve.android.ui.theme.*
import com.torve.domain.model.*
import com.torve.presentation.settings.ThemeMode
import com.torve.presentation.watching.*
import kotlinx.datetime.LocalDate
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class GermanOpenWorkflowsUiTest {
    private val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val localeRule = object : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.app.LocaleManager::class.java)
                val previous = manager.applicationLocales
                try {
                    manager.applicationLocales = LocaleList(Locale.GERMAN)
                    base.evaluate()
                } finally { manager.applicationLocales = previous }
            }
        }
    }
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(localeRule).around(rule)
    private val today = LocalDate(2026, 10, 8)
    private val episode = WatchingEpisode(MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "The Gentlemen"),
        2, Episode(5, "A Suburban A-Road"), today, 4, resume = true, positionMs = 1_260_000, progressPercent = .42f)
    private fun capture(name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "open-workflows-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun germanEpisodeViewLocalizesSpoilerPlaceholderErrorsAndCalendarDates() {
        rule.setContent { TorveTheme(ThemeMode.DARK) { MobileWatchingHubContent(WatchingHubState(calendar = listOf(episode), today = today, hideSpoilers = true,
            errorKind = WatchingHubError.PARTIAL_REFRESH), {}, {}, {}, {}, initialCalendar = true) } }
        rule.onNodeWithText("Deine Serien").assertIsDisplayed()
        rule.onNodeWithText("Heute").assertIsDisplayed()
        rule.onNodeWithText("S2 E5 · Episode 5").assertIsDisplayed()
        rule.onNodeWithText("Einige Episodenpläne konnten nicht aktualisiert werden. Versuche es erneut.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Episodenansicht und Hinweise").performClick()
        capture("phone-german")
        rule.onNodeWithText("Episodeneinstellungen").assertIsDisplayed()
    }
    @Test fun germanLibraryToolsKeepTheirCorrectDestinations() {
        val opened = mutableListOf<String>()
        rule.setContent { TorveTheme(ThemeMode.DARK) { MobileLibraryToolsScreen({}, opened::add) } }
        listOf("Kataloggruppen" to "catalog_groups", "Trakt-Listen" to "trakt_lists", "Haushaltsprofile" to "profiles").forEach { (label, route) ->
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(route, opened.last()) }
        }
        capture("library-german")
    }
    @Test fun catalogEditorKeepsSelectedUnavailableCatalogsAndLocalizedSaveAction() {
        var saved: CatalogGroup? = null
        rule.setContent { TorveTheme(ThemeMode.DARK) { MobileCatalogGroupEditor(CatalogGroup("g", "Abendkino", listOf("missing")), emptyList(), false, null, {}, { saved = it }) } }
        rule.onNodeWithText("Nicht verfügbarer Katalog (beibehalten)").assertIsDisplayed()
        capture("catalog-german")
        rule.onNodeWithText("Gruppe speichern").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("missing"), saved?.catalogIds); assertEquals("Abendkino", saved?.name) }
        capture("catalog-german")
    }
}
