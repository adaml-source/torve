package com.torve.android.ui.community

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.device.DeviceFormFactor
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.replays.WebReplaySettings
import com.torve.android.ui.theme.TorveTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import com.torve.presentation.settings.ThemeMode

/** Exercises real shared deep pages on phone and TV without saving connection/account data. */
class NestedLocalizationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private lateinit var previousConfiguration: Configuration
    private lateinit var previousLocale: Locale

    @Before fun prepareDialogLocale() {
        previousLocale = Locale.getDefault()
        previousConfiguration = Configuration(rule.activity.resources.configuration)
        val german = Configuration(previousConfiguration).apply { setLocale(Locale.GERMAN) }
        rule.runOnUiThread {
            Locale.setDefault(Locale.GERMAN)
            @Suppress("DEPRECATION")
            rule.activity.resources.updateConfiguration(german, rule.activity.resources.displayMetrics)
            @Suppress("DEPRECATION")
            rule.activity.applicationContext.resources.updateConfiguration(german, rule.activity.resources.displayMetrics)
        }
    }

    @After fun restoreProcessLocale() {
        rule.runOnUiThread {
            Locale.setDefault(previousLocale)
            @Suppress("DEPRECATION")
            rule.activity.resources.updateConfiguration(previousConfiguration, rule.activity.resources.displayMetrics)
            @Suppress("DEPRECATION")
            rule.activity.applicationContext.resources.updateConfiguration(previousConfiguration, rule.activity.resources.displayMetrics)
        }
    }

    @Test fun sevenLanguagesRecomposeUiCopyAndPreserveInterpolatedNames() {
        var language by mutableStateOf("en")
        val expected = linkedMapOf(
            "en" to "Show on Home", "de" to "Auf Home anzeigen", "es" to "Mostrar en Home",
            "fr" to "Afficher sur Home", "it" to "Mostra su Home", "pt" to "Mostrar em Home",
            "tr" to "Home'da göster",
        )
        rule.setContent {
            Localized(language) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(parityLabel("Show on Home"))
                    Text(parityLabel("Enabled · sports.example"))
                    Text(parityLabel("Websites copied to Profile Home."), Modifier.testTag("localized-copy-sentence"))
                    Text("Profile Home") // user-entered content is deliberately not a localization key
                }
            }
        }
        expected.forEach { (locale, label) ->
            rule.runOnIdle { language = locale }
            capture("catalog-$locale")
            println("UI locale $locale: " + rule.onRoot().printToString())
            rule.onNodeWithText(label).assertIsDisplayed()
            rule.onNodeWithText("Profile Home").assertIsDisplayed()
            rule.onNodeWithText("sports.example", substring = true).assertIsDisplayed()
            rule.onNodeWithTag("localized-copy-sentence").assertIsDisplayed().assert(hasText("Profile Home", substring = true))
            if (locale != "en") {
                rule.onNodeWithText("Enabled · sports.example").assertDoesNotExist()
                rule.onNodeWithText("Websites copied to Profile Home.").assertDoesNotExist()
            }
        }
    }

    @Test fun germanReplaySettingsAndAddDialogAndExtensionsUseLocalizedCopy() {
        var extensions by mutableStateOf(false)
        val tv = DeviceFormFactor.isTv(rule.activity)
        rule.setContent {
            Localized("de") {
                if (extensions) ExtensionsScreen({}, isTv = tv)
                else WebReplaySettings(tv, {}, {})
            }
        }
        rule.onNodeWithText("Auf Home anzeigen").assertExists()
        rule.onNodeWithText("Deine Websites").assertExists()
        rule.onNodeWithText("Website hinzufügen").performScrollTo().performClick()
        rule.onNodeWithText("Replay-Website hinzufügen").assertIsDisplayed()
        rule.onNodeWithText("Website-Adresse").assertIsDisplayed()
        rule.onNodeWithText("Abbrechen").assertIsDisplayed()
        capture("replay-add")
        rule.onNodeWithText("Abbrechen").performClick()
        rule.runOnIdle { extensions = true }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Integrierter Katalog").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Erweiterung hinzufügen")[0].assertIsDisplayed()
        rule.onNodeWithText("JSON- oder Manifest-URL").assertExists()
        rule.onNodeWithText("Link prüfen").assertExists()
        rule.onNodeWithText("Add an extension").assertDoesNotExist()
        rule.onNodeWithText("Preview link").assertDoesNotExist()
        capture("extensions")
    }

    @Composable private fun Localized(language: String, content: @Composable () -> Unit) {
        val original = LocalContext.current
        val configuration = remember(language) {
            Configuration(original.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        }
        val context = remember(language) { original.createConfigurationContext(configuration) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
            TorveTheme(ThemeMode.DARK) { Surface { content() } }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "nested-localization-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
