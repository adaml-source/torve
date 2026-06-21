package com.torve.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.torve.android.tv.TvRoot
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.TorveTheme

/**
 * Single setContent that transitions from placeholder → TvRoot.
 *
 * The placeholder renders in the first Compose frame (only Box + Text classes).
 * After a yield, [showFullUi] flips and TvRoot loads in subsequent frames.
 * Because this is one continuous composition (not two setContent calls),
 * the Compose input handler stays alive and processes key events between
 * frames — preventing ANR even when TvRoot's class loading is slow.
 */
object TvStartupFull {
    fun show(activity: ComponentActivity) {
        activity.setContent {
            var showFullUi by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                // Yield to render the placeholder frame first, then flip.
                // This resets the input-dispatch timer before heavy work starts.
                kotlinx.coroutines.delay(100)
                com.torve.android.debug.AnrDebugLogger.log("STARTUP showFullUi = true")
                showFullUi = true
            }

            if (!showFullUi) {
                // Lightweight placeholder — same as the View logo but in Compose.
                Box(
                    modifier = Modifier.fillMaxSize().background(Obsidian),
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
                TorveTheme {
                    TvRoot()
                }
            }
        }
    }
}
