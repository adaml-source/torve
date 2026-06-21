package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.presentation.tvhome.TvProviderBanner
import com.torve.presentation.tvhome.TvProviderBannerTone

/**
 * Non-blocking provider-health banner for TV Home (Prompt 11).
 *
 * Surfaces above the rails so the user sees it without losing focus
 * on a content tile. Tapping (OK) routes to provider-health
 * diagnostics or transfer-receive — the caller wires the action.
 *
 * Hidden when [banner] is null (no providers in trouble).
 */
@Composable
internal fun TvProviderHealthBanner(
    banner: TvProviderBanner?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
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
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable()
            .clickable(onClick = onClick)
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
                text = banner.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = banner.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
