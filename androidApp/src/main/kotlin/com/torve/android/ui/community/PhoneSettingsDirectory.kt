package com.torve.android.ui.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.torve.android.ui.components.mobileGlassSurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

val phoneSettingsGroups = linkedMapOf(
    "account" to ("Account & profiles" to "Profiles and sign-in"),
    "devices" to ("Devices & TV" to "Pair devices, TV companion and remote control"),
    "connections" to ("Sources & services" to "Sources, trackers and media servers"),
    "appearance" to ("Appearance & discovery" to "Theme, language, Home and cards"),
    "playback" to ("Playback" to "Autoplay, languages and stream quality"),
    "downloads" to ("Downloads & storage" to "Offline viewing and cache"),
    "advanced" to ("Help & advanced" to "Backup, AI Search, diagnostics and help"),
)

@Composable
fun PhoneSettingsDirectory(onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("settings.directory"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(parityLabel("Settings"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) }
        phoneSettingsGroups.forEach { (key, text) ->
            item(key = key) {
                Surface(modifier = Modifier.mobileGlassSurface(quiet = true), shape = RoundedCornerShape(20.dp), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().clickable { onSelect(key) }.padding(20.dp).testTag("settings.group.$key"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(parityLabel(text.first), style = MaterialTheme.typography.titleMedium)
                            Text(parityLabel(text.second), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
