package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.presentation.tvhome.TvProviderBanner
import com.torve.presentation.tvhome.TvProviderBannerTone
import kotlinx.coroutines.delay

internal const val TV_PROVIDER_NOTICE_DURATION_MS = 10_000L

/** Show at most once per unresolved incident, on the active Home screen. */
@Composable
internal fun rememberTvProviderNoticeVisible(hasIssue: Boolean, isActive: Boolean): Boolean {
    var shownForIncident by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hasIssue, isActive) {
        visible = false
        if (!hasIssue) {
            shownForIncident = false
        } else if (isActive && !shownForIncident) {
            shownForIncident = true
            visible = true
            delay(TV_PROVIDER_NOTICE_DURATION_MS)
            visible = false
        }
    }
    return visible && hasIssue && isActive
}

/**
 * Brief informational notice. Settings owns the persistent attention marker
 * and the repair actions, so dismissing this notice cannot remove remote focus.
 */
@Composable
internal fun TvProviderHealthBanner(
    banner: TvProviderBanner?,
    modifier: Modifier = Modifier,
) {
    if (banner == null) return
    val accent = when (banner.tone) {
        TvProviderBannerTone.ERROR -> Color(0xFFE57373)
        TvProviderBannerTone.WARNING -> Color(0xFFFFB74D)
    }
    val container = when (banner.tone) {
        TvProviderBannerTone.ERROR -> Color(0x33E57373)
        TvProviderBannerTone.WARNING -> Color(0x33FFB74D)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .background(container, RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = when (banner.tone) {
                TvProviderBannerTone.ERROR -> "⚠"
                TvProviderBannerTone.WARNING -> "ℹ"
            },
            color = accent,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = localizedUiText(banner.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = localizedUiText(banner.description),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                text = localizedUiText("Open Settings with the red dot to review these connections."),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
