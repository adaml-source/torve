package com.torve.android.ui.system

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun Activity.configureTorveEdgeToEdge(
    lightStatusBarIcons: Boolean = false,
    lightNavigationBarIcons: Boolean = false,
): WindowInsetsControllerCompat {
    return configureTorveEdgeToEdge(
        window = window,
        decorView = window.decorView,
        lightStatusBarIcons = lightStatusBarIcons,
        lightNavigationBarIcons = lightNavigationBarIcons,
    )
}

internal fun configureTorveEdgeToEdge(
    window: Window,
    decorView: View,
    lightStatusBarIcons: Boolean = false,
    lightNavigationBarIcons: Boolean = false,
): WindowInsetsControllerCompat {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    return WindowCompat.getInsetsController(window, decorView).apply {
        isAppearanceLightStatusBars = lightStatusBarIcons
        isAppearanceLightNavigationBars = lightNavigationBarIcons
    }
}
