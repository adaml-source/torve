package com.torve.android.ui.tv

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.R
import com.torve.android.TvMainActivity
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.koin.core.context.GlobalContext

/** Real Fire TV windows/configuration callbacks, without a virtual Compose clock. */
class FireTvLanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<TvMainActivity>
    private var originalLanguage: AppLanguage? = null
    private var closed = false
    private fun report(value: String) = instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "$value\n") })
    private fun settings(): SettingsViewModel = GlobalContext.get().get()
    private fun waitFor(timeout: Long = 15_000, label: String = "Fire TV UI", condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { "Timed out waiting for $label" }; SystemClock.sleep(100) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(root)
        return result.filter { it.isVisibleToUser && it.packageName?.toString() == instrumentation.targetContext.packageName }
    }
    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString()?.removePrefix("✓")?.trim() == text || node.contentDescription?.toString() == text
    private fun visible(text: String): Boolean = nodes().any { matches(it, text) }
    private fun click(text: String) {
        waitFor {
            var node = nodes().firstOrNull { matches(it, text) } ?: return@waitFor false
            while (!node.isClickable && node.parent != null) node = node.parent
            if (node.isFocusable) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        SystemClock.sleep(250)
    }
    private fun textResource(id: Int): String {
        var result = ""
        scenario.onActivity { result = it.getString(id) }
        return result
    }
    private fun languageFocused(label: String): Boolean = nodes().any {
        if (!matches(it, label)) false else {
            var node = it
            while (!node.isFocusable && node.parent != null) node = node.parent
            node.isFocused
        }
    }
    private fun ready(): AppLanguage {
        scenario = ActivityScenario.launch(TvMainActivity::class.java)
        waitFor(60_000) { runCatching { settings().state.value.playbackSettingsLoaded }.getOrDefault(false) }
        val original = settings().state.value.appLanguage
        originalLanguage = original
        instrumentation.runOnMainSync { settings().setAppLanguage(AppLanguage.ENGLISH) }
        waitFor { visible("Settings") }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        click("Settings")
        click("Appearance")
        click("Theme & language")
        return original
    }
    private fun choose(language: AppLanguage) {
        val previous = settings().state.value.appLanguage
        click(textResource(R.string.tv_settings_language))
        waitFor { visible(textResource(R.string.common_cancel)) }
        SystemClock.sleep(350)
        // A dropdown can open scrolled to the selected language. Start from the
        // top, then search forwards, rather than scrolling past a late UI node.
        repeat(6) {
            if (nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true) SystemClock.sleep(150)
        }
        val scroll = if (language.ordinal < previous.ordinal) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        repeat(8) {
            if (!visible(language.displayName)) {
                nodes().firstOrNull { it.isScrollable }?.performAction(scroll)
                SystemClock.sleep(180)
            }
        }
        click(language.displayName)
        waitFor { settings().state.value.appLanguage == language && visible(textResource(R.string.tv_settings_language)) }
        val prefs = GlobalContext.get().get<PreferencesRepository>()
        waitFor(label = "saved ${language.name}; UI=${settings().state.value.appLanguage}") { runBlocking { prefs.getString(SettingsViewModel.KEY_APP_LANGUAGE) } == language.name }
        waitFor { languageFocused(textResource(R.string.tv_settings_language)) }
    }
    private fun restore(original: AppLanguage) {
        if (closed) return
        instrumentation.runOnMainSync { settings().setAppLanguage(original) }
        val prefs = GlobalContext.get().get<PreferencesRepository>()
        try {
            waitFor(label = "restored ${original.name}") { runBlocking { prefs.getString(SettingsViewModel.KEY_APP_LANGUAGE) } == original.name }
        } finally { scenario.close(); closed = true }
    }
    @After fun cleanup() {
        if (::scenario.isInitialized && !closed) {
            originalLanguage?.let(::restore) ?: scenario.close()
        }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(1_200)
        val target = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name)
        target.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun everyPickerLanguageUpdatesTheInterface_withoutLeavingSettings() {
        val original = ready()
        var firstActivity: TvMainActivity? = null
        scenario.onActivity { firstActivity = it }
        try {
            val expected = listOf(
                Triple(AppLanguage.GERMAN, "Sprache", "Design & Sprache"), Triple(AppLanguage.SPANISH, "Idioma", "Tema e idioma"),
                Triple(AppLanguage.FRENCH, "Langue", "Thème et langue"), Triple(AppLanguage.ITALIAN, "Lingua", "Tema e lingua"),
                Triple(AppLanguage.PORTUGUESE, "Idioma", "Tema e idioma"), Triple(AppLanguage.TURKISH, "Dil", "Tema ve dil"),
                Triple(AppLanguage.ENGLISH, "Language", "Theme & language"))
            for ((language, label, heading) in expected) {
                choose(language)
                waitFor { visible(heading) && visible(label) }
                scenario.onActivity { assertSame("Switching keeps the current TV page", firstActivity, it) }
                assertTrue(visible("Home"))
                screenshot("language-${language.code}.png")
                report("TV_LANGUAGE language=${language.code} visibleHeading=$heading languageRowFocused=true homeUnchanged=true")
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor { visible("Display mode and app language") }
        } catch (failure: Throwable) { failure.printStackTrace(); screenshot("language-failure.png"); throw failure }
        finally { restore(original) }
    }
    @Test fun savedGermanLanguageSurvivesActivityRecreation() {
        val original = ready()
        try {
            choose(AppLanguage.GERMAN)
            scenario.recreate()
            waitFor(60_000) {
                var localized = false
                scenario.onActivity { localized = it.resources.configuration.locales[0].language == "de" && it.getString(R.string.tv_settings_language) == "Sprache" }
                localized
            }
            assertEquals(AppLanguage.GERMAN, settings().state.value.appLanguage)
            screenshot("language-de-recreated.png")
        } finally { restore(original) }
    }
    @Test fun selectedLanguageAlsoChangesMovieTitleAndPoster() {
        val original = ready()
        try {
            org.junit.Assume.assumeTrue("Uses the existing TMDB consent", settings().state.value.tmdbCatalogueEnabled || settings().state.value.tmdbEnrichmentEnabled)
            val api = GlobalContext.get().get<com.torve.data.metadata.TmdbApiClient>()
            fun detail(language: String) = runBlocking { withTimeout(45_000) {
                com.torve.data.metadata.TmdbMappers.movieToMediaItem(api.getMoviePresentation(238, language), language)
            } }
            val english = detail("en")
            assertEquals("The Godfather", english.title)
            assertNotNull(english.posterUrl)
            report("TV_LOCALIZED_ARTWORK language=en title=${english.title} posterAvailable=true localizedPoster=false")
            AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
                // The separate picker test covers remote interaction in all seven
                // locales. Exercise the same model command here without mixing
                // accessibility-window transitions with live network loading.
                instrumentation.runOnMainSync { settings().setAppLanguage(language) }
                val prefs = GlobalContext.get().get<PreferencesRepository>()
                waitFor(label = "metadata language ${language.code}") {
                    settings().state.value.appLanguage == language &&
                        runBlocking { prefs.getString(SettingsViewModel.KEY_APP_LANGUAGE) } == language.name
                }
                val localized = detail(settings().state.value.appLanguage.code)
                assertNotNull("Poster for ${language.code}", localized.posterUrl)
                if (language != AppLanguage.ENGLISH) assertNotEquals("Localized title for ${language.code}", english.title, localized.title)
                if (language == AppLanguage.GERMAN) {
                    assertEquals("Der Pate", localized.title)
                    assertNotEquals("A German poster is available for this fixture", english.posterUrl, localized.posterUrl)
                }
                report("TV_LOCALIZED_ARTWORK language=${language.code} title=${localized.title} posterAvailable=true localizedPoster=${english.posterUrl != localized.posterUrl}")
            }
        } finally { restore(original) }
    }
}
