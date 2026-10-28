package com.torve.android.ui.channels

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torve.android.R
import com.torve.android.ui.components.*
import java.util.Locale

@Composable
internal fun ChannelCountryDialog(availableCountries: List<String>, selectedCountries: Set<String>,
    onCountryToggle: (String) -> Unit, onClearCountries: () -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val options = remember(availableCountries, locale) {
        availableCountries.map { code -> code to Locale.forLanguageTag("und-$code").getDisplayCountry(locale).ifBlank { code } }
            .sortedBy { it.second }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.tv_live_countries), contentPadding = PaddingValues(0.dp))
                MobileGlassTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("channel_country_search"),
                    placeholder = { Text(stringResource(R.string.channels_search_countries)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MobileFilterPill(stringResource(R.string.channels_all_countries), selectedCountries.isEmpty(),
                        onClearCountries, Modifier.testTag("channel_country_all"))
                    Spacer(Modifier.weight(1f))
                    if (selectedCountries.isNotEmpty()) Text(localizedUiText("${selectedCountries.size} selected"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val filtered = options.filter { (code, name) -> name.contains(query.trim(), true) || code.contains(query.trim(), true) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("channel_country_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (filtered.isEmpty()) item { Text(stringResource(R.string.channels_no_countries), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(filtered, key = { it.first }) { (code, name) ->
                        val selected = code in selectedCountries
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .mobileGlassSurface(RoundedCornerShape(16.dp), selected = selected, quiet = true)
                            .selectable(selected, role = Role.Checkbox, onClick = { onCountryToggle(code) })
                            .testTag("channel_country_$code").padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                MobileGlassButton(onDismiss, Modifier.fillMaxWidth().testTag("channel_country_done"), accented = true) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

@Composable
internal fun ChannelCountryChip(selectedCountries: Set<String>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val selected = selectedCountries.sorted()
    MobileGlassOutlinedButton(onClick, modifier.testTag("channel_country_toolbar"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
        Text(if (selected.isEmpty()) stringResource(R.string.channels_all_countries)
            else selected.take(2).joinToString(", ") + if (selected.size > 2) " +${selected.size - 2}" else "",
            Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
    }
}
