package com.torve.android.ui.settings

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.presentation.settings.OptionalAppFeature
import com.torve.presentation.settings.OptionalAppFeatures

@Composable
internal fun AppFeaturesSettingsCard(
    features: OptionalAppFeatures,
    loaded: Boolean,
    onChanged: (OptionalAppFeature, Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(localizedUiText("App features"), style = MaterialTheme.typography.titleMedium)
            Text(localizedUiText("Turn off features you don’t use. Saved connections are kept."),
                style = MaterialTheme.typography.bodySmall)
            OptionalAppFeature.availableEntries.forEach { feature ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedUiText(when (feature) {
                        OptionalAppFeature.JELLYFIN -> "Jellyfin"
                        OptionalAppFeature.SPORTS -> "Sports"
                        OptionalAppFeature.CHANNELS -> "Channels"
                        OptionalAppFeature.ARR_STACK -> "ARR stack"
                    }), modifier = Modifier.weight(1f))
                    Switch(checked = features.isEnabled(feature), enabled = loaded,
                        onCheckedChange = { onChanged(feature, it) },
                        modifier = Modifier.testTag("mobile_feature_${feature.name.lowercase()}"))
                }
            }
        }
    }
}
