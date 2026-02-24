package com.streamvault.android

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.streamvault.android.ui.navigation.StreamVaultNavGraph
import com.streamvault.android.ui.splash.TorveSplashScreen
import com.streamvault.android.ui.theme.StreamVaultTheme
import com.streamvault.android.ui.tv.isRunningOnTv
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : ComponentActivity() {
    private var keepSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = isRunningOnTv(this)
        val settingsViewModel: SettingsViewModel = getKoin().get()

        // Apply saved language on startup (API 33+ uses system LocaleManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val saved = settingsViewModel.state.value.appLanguage
            if (saved.code != "en") {
                getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(saved.code)
            }
        }

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            var showSplash by remember { mutableStateOf(true) }

            StreamVaultTheme(themeMode = settingsState.themeMode) {
                if (showSplash) {
                    // Release native splash immediately when compose is ready
                    LaunchedEffect(Unit) { keepSplash = false }

                    TorveSplashScreen(
                        onSplashComplete = { showSplash = false },
                    )
                } else {
                    StreamVaultNavGraph(isTvMode = isTv)
                }
            }
        }
    }
}
