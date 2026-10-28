package com.torve.android.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.torve.android.BuildConfig
import com.torve.android.ui.system.configureTorveEdgeToEdge

class TorveTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTorveEdgeToEdge()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.FLAVOR_formFactor == "tv") {
            // Native TV fixtures enter through instrumentation, without a remote key
            // to leave Android's default touch mode before requesting Compose focus.
            window.decorView.requestFocusFromTouch()
        }
    }
}
