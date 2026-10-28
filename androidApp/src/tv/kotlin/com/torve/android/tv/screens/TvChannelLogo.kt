package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Silver
import com.torve.domain.model.Channel

/** A stable logo slot, including while an image loads or when its URL fails. */
@Composable
internal fun TvChannelLogo(channel: Channel?, size: Dp, modifier: Modifier = Modifier) {
    val logo = channel?.tvgLogo?.takeIf(String::isNotBlank)
    var loaded by remember(logo) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Charcoal.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) Icon(
            Icons.Filled.LiveTv, contentDescription = null, tint = Silver,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
        if (logo != null) AsyncImage(
            model = logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { loaded = true },
            onError = { loaded = false },
            modifier = Modifier.fillMaxSize().padding(3.dp),
        )
    }
}
