package com.torve.android

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkParser
import com.torve.android.tv.TvRoot
import com.torve.android.ui.system.configureTorveEdgeToEdge
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.auth.AuthEvent
import com.torve.data.auth.AuthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        private const val FULL_UI_START_DELAY_MS = 160L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var composeStarted = false
    private var authEventCollectionStarted = false
    private var hasResumedBefore = false
    private var pendingAppLink by mutableStateOf<TorveAppLink?>(null)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            // Compose focusSearch can crash with "FocusRequester is not initialized"
            // when a FocusRequester stored in focusProperties becomes detached during
            // lazy-list recomposition. Swallow instead of crashing the app.
            android.util.Log.w("TvMainActivity", "Focus dispatch error swallowed", e)
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedBefore && composeStarted) {
            CoroutineScope(Dispatchers.Main).launch {
                val authClient: AuthClient = getKoin().get()
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

            LaunchedEffect(Unit) {
                // Render one lightweight Compose frame before loading the full TV
                // shell so slower Fire TV devices do not spend first draw budget
                // in TvRoot composition and focus graph setup.
                kotlinx.coroutines.delay(FULL_UI_START_DELAY_MS)
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
                        Text(
                            text = "TORVE",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                        )
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
