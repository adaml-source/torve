package com.torve.android.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Preview visibility follows the result set, so moving focus never resizes the grid. */
@Composable
internal fun TvSearchResultsLayout(
    modifier: Modifier = Modifier,
    showPreview: Boolean,
    preview: @Composable (Modifier) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        content()
        if (showPreview) {
            preview(Modifier.width(228.dp).testTag("search_selected_preview"))
        }
    }
}
