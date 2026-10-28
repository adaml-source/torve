package com.torve.android.ui.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.android.ui.components.MobileGlassOutlinedButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

@Composable
fun PhoneGroupedSettingsMenu(
    entries: List<SettingsMenuEntry>, title: String, nodeId: String?,
    onNodeChanged: (String?) -> Unit, onBack: () -> Unit, onOpen: (String) -> Unit,
) {
    val current = nodeId?.let { SettingsMenus.find(entries, it) }
    fun back() { if (current == null) onBack() else onNodeChanged(SettingsMenus.parent(entries, current.id)?.id) }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("settings.sources.menu")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.torve.android.ui.components.BackButton(onClick = ::back)
            Text(parityLabel(current?.title ?: title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(parityLabel(current?.summary ?: "Choose a section."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(current?.children ?: entries, key = { it.id }) { item ->
                Surface(modifier = Modifier.mobileGlassSurface(quiet = true), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().clickable {
                        if (item.children.isNotEmpty()) onNodeChanged(item.id) else item.action?.let(onOpen)
                    }.testTag("settings.source.${item.id}").padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(parityLabel(item.title), style = MaterialTheme.typography.titleMedium)
                            Text(parityLabel(item.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
