package com.torve.android

import android.graphics.Color
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.torve.android.tv.TvRoot
import com.torve.android.ui.system.configureTorveEdgeToEdge
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
    private val handler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var composeStarted = false
    private var hasResumedBefore = false

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
        configureTorveEdgeToEdge()
        val authClient: AuthClient = getKoin().get()

        activityScope.launch {
            authClient.authEvents.collectLatest { event ->
                when (event) {
                    is AuthEvent.SessionExpired -> {
                        Toast.makeText(this@TvMainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val logo = TextView(this).apply {
            text = "TORVE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D0F14"))
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

    /**
     * Single setContent — transitions from placeholder to TvRoot via state flag.
     * The Compose input handler stays alive throughout, so key events arriving
     * during TvRoot's first composition are processed normally instead of
     * causing an ANR from a composition-replacement gap.
     */
    private fun showComposeContent() {
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent BEGIN")
        setContent {
            TorveTheme {
                TvRoot()
            }
        }
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent END")
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
