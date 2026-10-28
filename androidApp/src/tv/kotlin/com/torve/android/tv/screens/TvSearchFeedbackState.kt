package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

@Composable
internal fun TvSearchFeedbackState(
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    firstRequester: FocusRequester,
    upRequester: FocusRequester,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val secondary = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localizedUiText(title), color = Snow, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(localizedUiText(description), color = Silver, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(primaryLabel, Modifier.focusRequester(firstRequester).focusProperties {
                up = upRequester; left = firstRequester; right = secondary
            }, isPrimary = true, onFocused = { onContentFocused(firstRequester) }, onClick = onPrimary)
            TvActionButton(secondaryLabel, Modifier.focusRequester(secondary).focusProperties {
                up = upRequester; left = firstRequester; right = secondary
            }, onFocused = { onContentFocused(secondary) }, onClick = onSecondary)
        }
    }
}
