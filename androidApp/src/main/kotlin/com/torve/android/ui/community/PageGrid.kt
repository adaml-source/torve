package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.*
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Stable
class PageGridState(initial: Int, private val persist: (Int) -> Unit) {
    var columns by mutableIntStateOf(initial)
        private set
    fun select(value: Int) { columns = value.coerceIn(2, 6); persist(columns) }
    internal fun refresh(value: Int) { columns = value.coerceIn(2, 6) }
}

@Composable
fun rememberPageGrid(page: String, defaultColumns: Int = 3, settings: SettingsViewModel = koinInject()): PageGridState {
    val owner by settings.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("poster_grid_v1", 0) }
    val key = "${owner.personalDataUserId ?: "guest"}:$page"
    val state = remember(key) { PageGridState(prefs.getInt(key, defaultColumns).coerceIn(2, 6)) { prefs.edit().putInt(key, it).apply() } }
    DisposableEffect(key, state) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) state.refresh(prefs.getInt(key, 3))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun PageGridMenu(state: PageGridState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("grid.density")) {
            Icon(Icons.Rounded.GridView, contentDescription = localizedUiText("Grid density"))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (2..6).forEach { columns -> DropdownMenuItem(
                text = { Text(localizedUiText("$columns columns${if (state.columns == columns) " ✓" else ""}")) },
                onClick = { state.select(columns); expanded = false }) }
        }
    }
}

/** Respect large text rather than squeezing six illegible posters into a narrow display. */
@Composable
fun PageGridState.adaptiveColumns(): Int {
    val config = LocalConfiguration.current
    return columns.coerceAtMost((config.screenWidthDp / (90 * config.fontScale.coerceAtLeast(1f))).toInt().coerceAtLeast(1))
}
