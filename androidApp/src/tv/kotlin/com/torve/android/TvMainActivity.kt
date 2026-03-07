package com.torve.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.tv.TvRoot
import com.torve.android.ui.splash.TorveEyeSplashScreen
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import org.koin.java.KoinJavaComponent.getKoin

class TvMainActivity : AppCompatActivity() {
    private var keepSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsViewModel: SettingsViewModel = getKoin().get()

        val currentAppLocales = AppCompatDelegate.getApplicationLocales()
        if (currentAppLocales.isEmpty) {
            val saved = settingsViewModel.state.value.appLanguage
            if (saved != AppLanguage.ENGLISH) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(saved.code),
                )
            }
        }

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            var showSplash by rememberSaveable { mutableStateOf(true) }

            TorveTheme(themeMode = settingsState.themeMode) {
                LaunchedEffect(Unit) { keepSplash = false }

                if (showSplash) {
                    TorveEyeSplashScreen(
                        onSplashComplete = { showSplash = false },
                    )
                } else {
                    TvRoot()
                }
            }
        }
    }
}
