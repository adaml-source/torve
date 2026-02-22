package com.streamvault.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streamvault.android.ui.navigation.StreamVaultNavGraph
import com.streamvault.android.ui.theme.StreamVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamVaultTheme {
                StreamVaultNavGraph()
            }
        }
    }
}
