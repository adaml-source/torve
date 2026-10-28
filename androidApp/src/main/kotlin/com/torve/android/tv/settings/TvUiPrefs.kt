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
const val TV_SHOW_POSTER_RATINGS_KEY = "tv_show_poster_ratings"
const val TV_BROWSE_LAYOUT_KEY = "tv_browse_layout"
const val TV_SEE_ALL_POSTER_COLUMNS_KEY = "tv_see_all_poster_columns"
const val TV_SEE_ALL_POSTER_COLUMNS_DEFAULT = 4
val TV_SEE_ALL_POSTER_COLUMN_OPTIONS = listOf(4, 5, 6, 7, 8)

private fun previewPrefix(owner: String?) = "focus_preview.v1.${owner ?: "local"}."

fun tvFocusPreviewPolicy(context: Context, owner: String?): TvFocusPreviewPolicy {
    val prefs = context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
    val prefix = previewPrefix(owner)
    return TvFocusPreviewPolicy(
        global = prefs.getBoolean(prefix + "global", prefs.getString(TV_BROWSE_LAYOUT_KEY, "POSTER_ONLY") == "EXPANDING"),
        choices = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is Boolean) key.removePrefix(prefix) to value else null
        }.toMap(),
    )
}

fun setTvFocusPreview(context: Context, owner: String?, key: String, enabled: Boolean) {
    context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(previewPrefix(owner) + key, enabled).apply()
}

@Composable
fun rememberTvFocusPreviewPolicy(owner: String?): TvFocusPreviewPolicy {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var policy by remember(prefs, owner) { mutableStateOf(tvFocusPreviewPolicy(context, owner)) }
    DisposableEffect(prefs, owner) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TV_BROWSE_LAYOUT_KEY || key?.startsWith(previewPrefix(owner)) == true) {
                policy = tvFocusPreviewPolicy(context, owner)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return policy
}

fun setTvExpandingPosters(context: Context, enabled: Boolean) {
    context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(TV_BROWSE_LAYOUT_KEY, if (enabled) "EXPANDING" else "POSTER_ONLY").apply()
}

@Composable
fun rememberTvExpandingPostersPreference(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getString(TV_BROWSE_LAYOUT_KEY, "POSTER_ONLY") == "EXPANDING") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TV_BROWSE_LAYOUT_KEY) enabled = prefs.getString(key, "POSTER_ONLY") == "EXPANDING"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

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

fun setTvPosterRatingsEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(TV_SHOW_POSTER_RATINGS_KEY, enabled).apply()
}

@Composable
fun rememberTvPosterRatingsPreference(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TV_SHOW_POSTER_RATINGS_KEY) {
                enabled = prefs.getBoolean(TV_SHOW_POSTER_RATINGS_KEY, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

fun tvSeeAllPosterColumns(context: Context): Int {
    val value = context
        .getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(TV_SEE_ALL_POSTER_COLUMNS_KEY, TV_SEE_ALL_POSTER_COLUMNS_DEFAULT)
    return value.takeIf { it in TV_SEE_ALL_POSTER_COLUMN_OPTIONS } ?: TV_SEE_ALL_POSTER_COLUMNS_DEFAULT
}

fun setTvSeeAllPosterColumns(context: Context, columns: Int) {
    val safeColumns = columns.takeIf { it in TV_SEE_ALL_POSTER_COLUMN_OPTIONS } ?: TV_SEE_ALL_POSTER_COLUMNS_DEFAULT
    context
        .getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(TV_SEE_ALL_POSTER_COLUMNS_KEY, safeColumns)
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

@Composable
fun rememberTvSeeAllPosterColumnsPreference(): Int {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var columns by remember(prefs) { mutableStateOf(tvSeeAllPosterColumns(context)) }

    DisposableEffect(prefs, context) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TV_SEE_ALL_POSTER_COLUMNS_KEY) {
                columns = tvSeeAllPosterColumns(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return columns
}
