package com.torve.android.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.torve.android.ui.system.configureTorveEdgeToEdge

class TorveTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTorveEdgeToEdge()
    }
}
