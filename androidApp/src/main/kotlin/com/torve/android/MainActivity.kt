package com.torve.android

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkParser
import com.torve.android.ui.navigation.TorveNavGraph
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.auth.AuthEvent
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.device.DeviceGovernanceViewModel
import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import com.torve.data.auth.AuthClient
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.subscription.SubscriptionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : AppCompatActivity() {
    companion object {
        /** Survives configuration changes, but resets when the app process does. */
        private var hasCreatedActivityInProcess = false
    }

    private var keepSplash = true
    private var hasResumedBefore = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingAppLink by mutableStateOf<TorveAppLink?>(null)

    private fun localeTagsFor(language: AppLanguage): String = language.code

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
            val subscriptionViewModel: SubscriptionViewModel = getKoin().get()
            val deviceGovernanceViewModel: DeviceGovernanceViewModel = getKoin().get()
            CoroutineScope(Dispatchers.Main).launch {
                val result = accountSessionCoordinator.onAppForeground()
                if (result.settingsResult?.appliedChanges == true) {
                    homeViewModel.refresh()
                }
                subscriptionViewModel.loadSubscription()
                deviceGovernanceViewModel.fetchAccessState()
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
        // A new task or process should always enter Home at the top. A pure
        // configuration recreation (for example rotation) keeps its position.
        val resetMobileHomeScroll = savedInstanceState == null || !hasCreatedActivityInProcess
        hasCreatedActivityInProcess = true
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }

        // Compose and Navigation both restore rememberSaveable/back-stack state from
        // this bundle.  On a genuine new process/task that would otherwise override
        // the explicit Home selection below and reopen the last tab (or the middle
        // of Settings). Keep the bundle only for an in-process configuration change.
        super.onCreate(if (resetMobileHomeScroll) null else savedInstanceState)
        pendingAppLink = TorveAppLinkParser.parse(intent?.data)
        configureTorveEdgeToEdge()
        requestNotificationPermission()
        val authClient: AuthClient = getKoin().get()
        val settingsViewModel: SettingsViewModel = getKoin().get()

        activityScope.launch {
            authClient.authEvents.collectLatest { event ->
                when (event) {
                    is AuthEvent.SessionExpired -> {
                        getKoin().get<AccountSessionCoordinator>()
                            .clearLocalAccountData(reason = "session_expired")
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                    // Handled by NavGraph's AuthEvent observer (sets the
                    // mobile-onboarding-required flag); nothing to do
                    // at the activity level.
                    is AuthEvent.Registered -> Unit
                }
            }
        }

        // Restore saved locale via AppCompat (works on all API levels 24+).
        // AppCompatDelegate handles API 33+ internally via LocaleManager.
        val currentAppLocales = AppCompatDelegate.getApplicationLocales()
        if (currentAppLocales.isEmpty) {
            val saved = settingsViewModel.state.value.appLanguage
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(localeTagsFor(saved)),
            )
        }

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            LaunchedEffect(settingsState.appLanguage) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(localeTagsFor(settingsState.appLanguage)),
                )
            }

            TorveTheme(themeMode = settingsState.themeMode) {
                // Keep only Android's native launch splash. Composing the real
                // navigation graph immediately lets content loading begin on
                // the first Compose frame.
                LaunchedEffect(Unit) { keepSplash = false }
                TorveNavGraph(
                    appLink = pendingAppLink,
                    onAppLinkConsumed = { pendingAppLink = null },
                    resetMobileHomeScrollOnLaunch = resetMobileHomeScroll,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAppLink = TorveAppLinkParser.parse(intent.data)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
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
