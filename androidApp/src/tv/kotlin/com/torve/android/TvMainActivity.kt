package com.torve.android

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkParser
import com.torve.android.tv.TvRoot
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.auth.AuthEvent
import com.torve.data.auth.AuthClient
import com.torve.presentation.session.AccountSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import android.graphics.Color as AndroidColor

/**
 * TV entry point.  Startup uses 2 stages:
 *
 * Stage 0 (onCreate): Plain Android View — "TORVE" logo. Zero Compose.
 * Stage 1 (Koin ready): Single setContent with a flag that starts as
 *   placeholder, then transitions to the full TvRoot UI.  Using ONE
 *   setContent avoids the composition-replacement gap that caused ANRs
 *   when two separate setContent calls were used.
 */
class TvMainActivity : AppCompatActivity() {
    companion object {
        private const val FULL_UI_START_DELAY_MS = 5_000L
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

        val logo = TextView(this).apply {
            text = "TORVE"
            setTextColor(AndroidColor.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.parseColor("#0D0F14"))
            addView(logo, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(root)

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

    /**
     * Single setContent — transitions from placeholder to TvRoot via state flag.
     * The Compose input handler stays alive throughout, so key events arriving
     * during TvRoot's first composition are processed normally instead of
     * causing an ANR from a composition-replacement gap.
     */
    private fun showComposeContent() {
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent BEGIN")
        startAuthEventCollection()
        setContent {
            var showFullUi by remember { mutableStateOf(false) }
            var startupMessage by remember { mutableStateOf("Preparing Torve") }

            LaunchedEffect(Unit) {
                val startedAt = System.currentTimeMillis()
                startupMessage = "Restoring local cache"
                withContext(Dispatchers.IO) {
                    runCatching {
                        val koin = getKoin()
                        koin.get<com.torve.db.TorveDatabase>()
                        koin.get<com.torve.data.auth.AuthClient>().getAuthenticatedUser()
                        koin.get<com.torve.domain.repository.ChannelRepository>().getPlaylistSummaries()
                        koin.get<com.torve.presentation.channels.ChannelsViewModel>()
                        koin.get<com.torve.domain.repository.MetadataRepository>()
                        koin.get<com.torve.presentation.settings.SettingsViewModel>()
                    }
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < FULL_UI_START_DELAY_MS) {
                    startupMessage = "Preparing navigation"
                    delay(FULL_UI_START_DELAY_MS - elapsed)
                }
                com.torve.android.debug.AnrDebugLogger.log("STARTUP showFullUi = true")
                showFullUi = true
            }

            TorveTheme {
                if (!showFullUi) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Obsidian),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "TORVE",
                                color = Color.White,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                            )
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = startupMessage,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 16.sp,
                            )
                        }
                    }
                } else {
                    TvRoot(
                        appLink = pendingAppLink,
                        onAppLinkConsumed = { pendingAppLink = null },
                    )
                }
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
