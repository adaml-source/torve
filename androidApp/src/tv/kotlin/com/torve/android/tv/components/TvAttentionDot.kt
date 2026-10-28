package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Ruby

/** An informational marker; it never adds another remote-control focus stop. */
@Composable
internal fun TvAttentionDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(Ruby, CircleShape)
            .semantics { contentDescription = "Needs attention" },
    )
}
