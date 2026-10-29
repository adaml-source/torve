package com.torve.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemoteDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkParser
import com.torve.android.tv.TvRoot
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.auth.AuthEvent
import com.torve.data.auth.AuthClient
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

/**
 * TV entry point. The native window background provides an immediate static
 * shell while dependencies settle; Compose is installed once with the real
 * TV root so startup does not pay for a redundant placeholder composition.
 */
class TvMainActivity : AppCompatActivity() {
    companion object {
        private const val DIRECTIONAL_REPEAT_THROTTLE_MS = 90L
        private const val DIRECTIONAL_REPEAT_THROTTLE_AFTER_COUNT = 2
        private const val APP_CONTROL_LONG_BACK_MS = 650L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val usageReporter = com.torve.android.usage.AppUsageReporter()
    private var usageStartJob: kotlinx.coroutines.Job? = null
    override fun onStart() {
        super.onStart()
        usageStartJob = activityScope.launch {
            (application as TorveApp).tvUiReady.await()
            usageReporter.start()
        }
    }
    override fun onStop() { usageStartJob?.cancel(); usageReporter.stop(); super.onStop() }
    override fun onLocalesChanged(locales: androidx.core.os.LocaleListCompat) {
        super.onLocalesChanged(locales)
        if (composeStarted) {
            // AppCompat updates resources in place on older Fire OS versions.
            // Forward the new configuration to Compose without leaving this page.
            window.decorView.dispatchConfigurationChanged(android.content.res.Configuration(resources.configuration))
        }
    }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var composeStarted = false
    internal var startupPreparation: com.torve.android.tv.TvStartupPreparation<
        com.torve.presentation.settings.SettingsViewModel, com.torve.android.tv.TvRootDependencies>? = null
        private set
    private var authEventCollectionStarted = false
    private var hasResumedBefore = false
    private var pendingAppLink by mutableStateOf<TorveAppLink?>(null)
    private var lastDirectionalRepeatKeyCode = 0
    private var lastDirectionalRepeatAtMs = 0L
    private var appControlBackHeld = false
    private var appControlLongBackTriggered = false
    private var showAppControlDialog by mutableStateOf(false)
    private var inputFocusRecoveryNonce by mutableIntStateOf(0)
    private val appControlLongBack = Runnable {
        if (appControlBackHeld) {
            appControlLongBackTriggered = true
            showAppControlDialog = true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((BuildConfig.DEBUG || BuildConfig.STARTUP_METRICS) && event.action == KeyEvent.ACTION_DOWN) {
            com.torve.android.debug.AnrDebugLogger.log("INPUT key=${event.keyCode} queuedMs=${android.os.SystemClock.uptimeMillis() - event.eventTime}")
        }
        if (handleFullScreenPlaybackMenu(event)) return true
        if (handleBackgroundPlaybackMenu(event)) return true
        if (handleAppControlBack(event)) return true
        if (shouldThrottleDirectionalRepeat(event)) return true
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            // Compose focus can throw while a TV lazy list is recycling the currently
            // pinned row during rapid D-pad repeats. Consume the key so it cannot
            // continue through fallback dispatch and crash the process.
            android.util.Log.w("TvMainActivity", "Focus dispatch error swallowed", e)
            if (event.action == KeyEvent.ACTION_DOWN) inputFocusRecoveryNonce += 1
            true
        }
    }

    private fun handleFullScreenPlaybackMenu(event: KeyEvent): Boolean {
        if (
            event.keyCode != KeyEvent.KEYCODE_MENU ||
            !ActivePlaybackState.hasFullScreenPlaybackMenuOwner()
        ) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            ActivePlaybackState.requestFullScreenPlaybackMenu()
        }
        // Activity-level ownership makes Menu reliable even when the Android video
        // surface, rather than a Compose node, currently owns input focus.
        return true
    }

    private fun handleBackgroundPlaybackMenu(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU || !hasBackgroundPlayback()) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            ActivePlaybackState.requestPlaybackBarFocus()
        }
        // Own both DOWN and UP so Menu cannot also leak into the focused screen.
        return true
    }

    @SuppressLint("GestureBackNavigation")
    private fun handleAppControlBack(event: KeyEvent): Boolean {
        // This TV-only shortcut needs the physical key's DOWN/repeat/UP
        // sequence to distinguish long Back. Short Back is still delegated
        // to onBackPressedDispatcher below.
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    appControlBackHeld = true
                    appControlLongBackTriggered = false
                    handler.removeCallbacks(appControlLongBack)
                    handler.postDelayed(appControlLongBack, APP_CONTROL_LONG_BACK_MS)
                } else if (event.isLongPress && !appControlLongBackTriggered) {
                    handler.removeCallbacks(appControlLongBack)
                    appControlLongBack.run()
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                val dialogWasTriggered = appControlLongBackTriggered
                cancelAppControlLongBack()
                if (!dialogWasTriggered) {
                    if (showAppControlDialog) {
                        showAppControlDialog = false
                    } else {
                        // Preserve ordinary short-Back behavior while waiting long
                        // enough to distinguish the explicit app-control shortcut.
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                true
            }

            else -> {
                cancelAppControlLongBack()
                true
            }
        }
    }

    private fun hasBackgroundPlayback(): Boolean =
        ActivePlaybackState.session != null && !ActivePlaybackState.isFullScreenPlayerVisible

    private fun cancelAppControlLongBack() {
        handler.removeCallbacks(appControlLongBack)
        appControlBackHeld = false
        appControlLongBackTriggered = false
    }

    private fun exitTorve() {
        ActivePlaybackState.stopAndClear()
        showAppControlDialog = false
        finishAndRemoveTask()
    }

    private fun restartTorve() {
        ActivePlaybackState.stopAndClear()
        showAppControlDialog = false
        startActivity(Intent.makeRestartActivityTask(componentName))
    }

    private fun shouldThrottleDirectionalRepeat(event: KeyEvent): Boolean {
        if (
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount <= DIRECTIONAL_REPEAT_THROTTLE_AFTER_COUNT
        ) {
            return false
        }
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val eventTime = event.eventTime
        val elapsedMs = eventTime - lastDirectionalRepeatAtMs
        val shouldThrottle = keyCode == lastDirectionalRepeatKeyCode &&
            elapsedMs >= 0 &&
            elapsedMs < DIRECTIONAL_REPEAT_THROTTLE_MS
        if (!shouldThrottle) {
            lastDirectionalRepeatKeyCode = keyCode
            lastDirectionalRepeatAtMs = eventTime
        }
        return shouldThrottle
    }

    override fun onResume() {
        super.onResume()
        com.torve.android.debug.AnrDebugLogger.log("STARTUP Activity.onResume retained=$hasResumedBefore")
        if (hasResumedBefore && composeStarted) {
            activityScope.launch {
                val authClient: AuthClient = getKoin().get()
                val accountSessionCoordinator: AccountSessionCoordinator = getKoin().get()
                accountSessionCoordinator.onAppForeground(
                    promoteLegacyTvJellyfin = true,
                )
                val user = authClient.getCurrentUser()
                if (user != null && !user.isVerified) {
                    authClient.checkVerificationStatus()
                }
            }
        }
        hasResumedBefore = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("torveVerboseLogging", false)) {
            com.torve.platform.TorveRuntimeDebug.verboseLoggingEnabled = true
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("torveTraceStartup", false)) {
            com.torve.android.debug.AnrDebugLogger.sampleStartup(filesDir)
        }
        pendingAppLink = parseSearchOrAppLink(intent)
        configureTorveEdgeToEdge()
        requestNotificationPermission()
        pollForKoinReady()
    }

    private fun requestNotificationPermission() {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun pollForKoinReady() {
        handler.post {
            if (composeStarted) return@post
            val app = application as TorveApp
            if (app.koinReady.count == 0L) {
                composeStarted = true
                showComposeContent()
            } else {
                handler.postDelayed(::pollForKoinReady, 100)
            }
        }
    }

    private fun startAuthEventCollection() {
        if (authEventCollectionStarted) return
        authEventCollectionStarted = true
        activityScope.launch {
            val authClient = kotlinx.coroutines.withContext(Dispatchers.IO) { getKoin().get<AuthClient>() }
            authClient.authEvents.collectLatest { event ->
                when (event) {
                    is AuthEvent.SessionExpired -> {
                        getKoin().get<AccountSessionCoordinator>()
                            .clearLocalAccountData(reason = "session_expired")
                        Toast.makeText(this@TvMainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                    // No TV-side onboarding equivalent today; the
                    // event is observed on mobile to mark
                    // mobileOnboardingRequired. Ignore on TV.
                    is AuthEvent.Registered -> Unit
                }
            }
        }
    }

    private fun showComposeContent() {
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent BEGIN")
        startAuthEventCollection()
        val preparation = com.torve.android.tv.TvStartupPreparation(
            scope = activityScope,
            restoreAccount = {
                val cachedAccount = getKoin().get<AuthClient>().restoreCachedSessionUser()
                android.util.Log.i("TvAuthRestore", "cached_account_restored=${cachedAccount != null}")
                com.torve.android.debug.AnrDebugLogger.log("STARTUP local account ready")
            },
            loadAppearance = { getKoin().get<com.torve.presentation.settings.SettingsViewModel>() },
            loadHome = {
                com.torve.android.tv.loadTvRootDependencies().also {
                    activityScope.launch(Dispatchers.IO) {
                        runCatching { com.torve.android.tv.screens.loadTvHomeServices() }
                    }
                }
            },
        )
        startupPreparation = preparation
        setContent {
            val settings by androidx.compose.runtime.produceState<com.torve.presentation.settings.SettingsViewModel?>(null) {
                value = preparation.appearance.await()
            }
            val readySettings = settings
            if (readySettings == null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth())
                return@setContent
            }
            val appearance by readySettings.state.collectAsState()
            var appliedLanguage by remember { mutableStateOf<com.torve.presentation.settings.AppLanguage?>(null) }
            LaunchedEffect(appearance.playbackSettingsLoaded, appearance.appLanguage) {
                // Wait for saved preferences before applying a locale. The default
                // state must not replace the user's persisted language at startup.
                if (appearance.playbackSettingsLoaded) {
                    val previous = appliedLanguage
                    appliedLanguage = appearance.appLanguage
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(appearance.appLanguage.code),
                    )
                    if (previous != null && previous != appearance.appLanguage) {
                        val preferences = getKoin().get<com.torve.domain.repository.PreferencesRepository>()
                        kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            while (preferences.getString(com.torve.presentation.settings.SettingsViewModel.KEY_APP_LANGUAGE) != appearance.appLanguage.name) {
                                kotlinx.coroutines.delay(25)
                            }
                        }
                        getKoin().get<com.torve.presentation.settings.SettingsRefreshNotifier>()
                            .notifyRefresh(System.currentTimeMillis())
                    }
                }
            }
            TorveTheme(themeMode = appearance.themeMode) {
                com.torve.android.ui.community.PersonalAppearanceProvider {
                com.torve.android.ui.profile.FamilyAppGate {
                TvRoot(
                    appLink = pendingAppLink,
                    onAppLinkConsumed = { pendingAppLink = null },
                    inputFocusRecoveryNonce = inputFocusRecoveryNonce,
                )
                }
                }
                if (showAppControlDialog) {
                    TvAppControlDialog(
                        onDismiss = { showAppControlDialog = false },
                        onExit = ::exitTorve,
                        onRestart = ::restartTorve,
                    )
                }
            }
        }
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent END")
    }

    private fun parseSearchOrAppLink(value: Intent?): TorveAppLink? {
        if (value?.action == Intent.ACTION_SEARCH) {
            val query = value.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.take(160).orEmpty()
            return TorveAppLinkParser.parse(android.net.Uri.parse("https://torve.app/?search=${android.net.Uri.encode(query)}"))
        }
        return TorveAppLinkParser.parse(value?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAppLink = parseSearchOrAppLink(intent)
    }

    override fun onDestroy() {
        cancelAppControlLongBack()
        activityScope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun TvAppControlDialog(
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onRestart: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { cancelFocusRequester.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 520.dp, max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text(
                    text = stringResource(R.string.tv_app_control_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.tv_app_control_message),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvAppControlButton(
                        text = stringResource(R.string.tv_app_control_exit),
                        onClick = onExit,
                    )
                    TvAppControlButton(
                        text = stringResource(R.string.tv_app_control_restart),
                        onClick = onRestart,
                    )
                    TvAppControlButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocusRequester),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAppControlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .scale(if (focused) 1.08f else 1f)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Snow else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            ),
        shape = shape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (focused) AmberLight else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (focused) Obsidian else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text)
    }
}
