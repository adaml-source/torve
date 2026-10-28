package com.torve.android.ui.search

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.torve.android.ui.components.mobileGlassSurface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.torve.android.R

@Composable
internal fun SearchAiControls(enabled: Boolean, canSearch: Boolean, searching: Boolean,
    onEnabled: (Boolean) -> Unit, onSearch: () -> Unit) {
    val label = stringResource(R.string.search_ai_toggle)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconToggleButton(checked = enabled, onCheckedChange = onEnabled,
            modifier = Modifier.size(48.dp).mobileGlassSurface(CircleShape, selected = enabled, quiet = true).testTag("search.ai.toggle").semantics { contentDescription = label },
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = Color.Transparent,
                checkedContentColor = MaterialTheme.colorScheme.primary)) {
            Text(localizedUiText("AI"), style = MaterialTheme.typography.labelLarge)
        }
        if (enabled) IconButton(onClick = onSearch, enabled = canSearch && !searching,
            modifier = Modifier.size(48.dp).testTag("search.ai.submit")) {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(androidx.compose.material.icons.Icons.Rounded.Search, stringResource(R.string.search_ai_submit))
        }
    }
}
