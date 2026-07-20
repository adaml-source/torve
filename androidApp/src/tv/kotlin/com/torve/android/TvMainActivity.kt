package com.torve.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkParser
import com.torve.android.tv.TvRoot
import com.torve.android.ui.system.configureTorveEdgeToEdge
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
 * TV entry point. The platform splash remains visible until dependency
 * injection is ready, then the Home UI is composed immediately.
 */
class TvMainActivity : AppCompatActivity() {
    companion object {
        private const val DIRECTIONAL_REPEAT_THROTTLE_MS = 90L
        private const val DIRECTIONAL_REPEAT_THROTTLE_AFTER_COUNT = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var composeStarted = false
    private var authEventCollectionStarted = false
    private var hasResumedBefore = false
    private var pendingAppLink by mutableStateOf<TorveAppLink?>(null)
    private var lastDirectionalRepeatKeyCode = 0
    private var lastDirectionalRepeatAtMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shouldThrottleDirectionalRepeat(event)) return true
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            // Compose focus can throw while a TV lazy list is recycling the currently
            // pinned row during rapid D-pad repeats. Consume the key so it cannot
            // continue through fallback dispatch and crash the process.
            android.util.Log.w("TvMainActivity", "Focus dispatch error swallowed", e)
            true
        }
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
        if (hasResumedBefore && composeStarted) {
            activityScope.launch {
                val authClient: AuthClient = getKoin().get()
                val accountSessionCoordinator: AccountSessionCoordinator = getKoin().get()
                accountSessionCoordinator.onAppForeground()
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
        pendingAppLink = TorveAppLinkParser.parse(intent?.data)
        configureTorveEdgeToEdge()
        pollForKoinReady()
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
            val authClient: AuthClient = getKoin().get()
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
        setContent {
            TorveTheme {
                TvRoot(
                    appLink = pendingAppLink,
                    onAppLinkConsumed = { pendingAppLink = null },
                )
            }
        }
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent END")
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
}
