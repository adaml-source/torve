package com.torve.android.tv.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A roomy, contrasting TV focus frame. Reserved space keeps neighbouring controls still. */
@Composable
fun Modifier.tvSettingsFocusFrame(focused: Boolean): Modifier = if (LocalTvSettingsStyle.current) this
    .padding(4.dp)
    .border(if (focused) 2.dp else 1.dp,
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        RoundedCornerShape(20.dp))
else this
    .padding(4.dp)
    .zIndex(if (focused) 1f else 0f)
    .graphicsLayer { scaleX = if (focused) 1.015f else 1f; scaleY = if (focused) 1.035f else 1f }
    .border(if (focused) 3.dp else 1.dp,
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        RoundedCornerShape(20.dp))
