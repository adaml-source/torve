package com.torve.android.tv.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val TV_UI_PREFS_NAME = "tv_ui_prefs"
const val TV_REDUCE_MOTION_KEY = "tv_reduce_motion"
const val TV_BROWSE_LAYOUT_KEY = "tv_browse_layout"

fun isTvReduceMotionEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(TV_REDUCE_MOTION_KEY, false)
}

fun setTvReduceMotionEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(TV_REDUCE_MOTION_KEY, enabled)
        .apply()
}

@Composable
fun rememberTvReduceMotionPreference(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(TV_REDUCE_MOTION_KEY, false)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TV_REDUCE_MOTION_KEY) {
                enabled = prefs.getBoolean(TV_REDUCE_MOTION_KEY, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return enabled
}
