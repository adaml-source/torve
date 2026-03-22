package com.torve.android

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import com.torve.android.ui.navigation.TorveNavGraph
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.splash.TorveEyeSplashScreen
import com.torve.android.ui.theme.TorveTheme
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.data.auth.AuthClient
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : AppCompatActivity() {
    private var keepSplash = true
    private var hasResumedBefore = false

    // ── Picture-in-Picture ──────────────────────────────────────
    // Enter PiP when user presses Home while video is actively playing.
    // ActivePlaybackState is set by PlayerScreen composable.

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ActivePlaybackState.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ActivePlaybackState.isInPipMode = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedBefore) {
            val accountSessionCoordinator: AccountSessionCoordinator = getKoin().get()
            val homeViewModel: HomeViewModel = getKoin().get()
            val authClient: AuthClient = getKoin().get()
            CoroutineScope(Dispatchers.Main).launch {
                val result = accountSessionCoordinator.onAppForeground()
                if (result.settingsResult?.appliedChanges == true) {
                    homeViewModel.refresh()
                }
                // Refresh verification status and ensure SSE is connected
                // for unverified users returning from email/browser.
                val user = authClient.getCurrentUser()
                if (user != null && !user.isVerified) {
                    authClient.startVerificationEvents() // reconnect SSE if needed
                    authClient.checkVerificationStatus()
                }
            }
        }
        hasResumedBefore = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        val settingsViewModel: SettingsViewModel = getKoin().get()

        // Restore saved locale via AppCompat (works on all API levels 24+).
        // AppCompatDelegate handles API 33+ internally via LocaleManager.
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

            LaunchedEffect(settingsState.appLanguage) {
                val languageTags = when (settingsState.appLanguage) {
                    AppLanguage.ENGLISH -> ""
                    else -> settingsState.appLanguage.code
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTags))
            }

            TorveTheme(themeMode = settingsState.themeMode) {
                // Dismiss native splash — MUST be outside if/else so it also
                // fires on recreation (e.g. locale change) when showSplash
                // is restored as false by rememberSaveable.
                LaunchedEffect(Unit) { keepSplash = false }

                if (showSplash) {
                    TorveEyeSplashScreen(
                        onSplashComplete = { showSplash = false },
                    )
                } else {
                    TorveNavGraph()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
