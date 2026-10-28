package com.torve.android.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Artwork follows the measured foreground so logos or large text cannot
 * squeeze action labels out of the header. Series stay compact when they fit. */
@Composable
internal fun TvDetailsHeroLayout(
    isSeries: Boolean,
    background: @Composable BoxScope.() -> Unit,
    foreground: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        background()
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = if (isSeries) 250.dp else 330.dp)
                .padding(horizontal = 24.dp, vertical = if (isSeries) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (isSeries) 8.dp else 10.dp, Alignment.Bottom),
            content = foreground,
        )
    }
}
