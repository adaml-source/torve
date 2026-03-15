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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

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
    private var composeStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        TvStartupFull.show(this)
        com.torve.android.debug.AnrDebugLogger.log("STARTUP setContent END")
    }
}
