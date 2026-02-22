package com.streamvault.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.streamvault.android.ui.navigation.StreamVaultNavGraph
import com.streamvault.android.ui.theme.StreamVaultTheme
import com.streamvault.android.ui.tv.isRunningOnTv
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = isRunningOnTv(this)
        val settingsViewModel: SettingsViewModel = getKoin().get()
        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            StreamVaultTheme(themeMode = settingsState.themeMode) {
                StreamVaultNavGraph(isTvMode = isTv)
            }
        }
    }
}
