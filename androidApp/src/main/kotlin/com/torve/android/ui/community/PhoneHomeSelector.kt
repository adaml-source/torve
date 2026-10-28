package com.torve.android.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.menuPanelColor

@Composable
fun PhoneHomeSelector(selected: String, onSelect: (String) -> Unit) {
    if (selected !in setOf("home", "movies", "tv_shows")) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            Row(Modifier.clip(CircleShape).background(menuPanelColor())
                .selectableGroup().testTag("home_content_selector")) {
                listOf(
                    Triple("home", R.string.nav_home, Icons.Rounded.Home),
                    Triple("movies", R.string.nav_movies, Icons.Rounded.Theaters),
                    Triple("tv_shows", R.string.nav_tv_shows, Icons.Rounded.LiveTv),
                ).forEach { (route, label, icon) ->
                    val active = selected == route
                    Box(Modifier.size(48.dp)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(route) })
                        .testTag("home_content_$route"), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(40.dp).background(
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                            CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, stringResource(label), Modifier.size(24.dp),
                                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
    }
}
