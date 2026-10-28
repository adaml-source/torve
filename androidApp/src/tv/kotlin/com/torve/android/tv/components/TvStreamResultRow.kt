package com.torve.android.tv.components
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import com.torve.android.R
import com.torve.data.addon.SourceLanguages
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
@Composable
internal fun TvStreamResultRow(
    presentation: TvStreamPresentation,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    selectionExplanation: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val languages = presentation.streamLanguages?.let { SourceLanguages.codes(it) }.orEmpty()
        .joinToString(" + ") { Locale.forLanguageTag(it).getDisplayLanguage(locale).replaceFirstChar { c -> c.titlecase(locale) } }
    val summary = if (languages.isNotEmpty()) presentation.summary.split(" · ").let {
        listOf(it.first(), languages, it.last()).distinct().joinToString(" · ")
    } else presentation.summary
    val statusColor = when (presentation.availability) {
        TvStreamAvailability.READY -> Emerald
        TvStreamAvailability.PREPARING -> AmberLight
        TvStreamAvailability.UNAVAILABLE -> Ruby
        else -> Silver
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Gunmetal else Graphite)
            .border(if (focused) 2.dp else 1.dp, if (focused) AmberLight else Steel, RoundedCornerShape(16.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = presentation.provider.ifBlank { summary },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Silver,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(statusColor, RoundedCornerShape(50)))
                Text(text = tvSourceLabel(presentation.availability.label),
                    style = MaterialTheme.typography.labelSmall, color = Silver)
            }
        }
        if (presentation.filename.isNotBlank()) {
            Text(presentation.filename, color = Snow, style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(summary, color = Silver, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        presentation.streamLanguages?.let {
            Text(stringResource(if (it.trackLanguagesVerified) R.string.source_tracks_verified else R.string.source_tracks_hint),
                color = Silver, style = MaterialTheme.typography.labelSmall)
        }
        selectionExplanation?.let { Text(it, color = Silver, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (presentation.technicalDetails.isNotBlank()) {
            Text(presentation.technicalDetails, color = Silver, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable
private fun tvSourceLabel(value: String): String = stringResource(when (value) {
    "Ready now" -> R.string.tv_source_status_ready
    "Preparing" -> R.string.tv_source_status_preparing
    "Unavailable" -> R.string.tv_source_status_unavailable
    "Check on play" -> R.string.tv_source_status_check
    "Availability unconfirmed" -> R.string.tv_source_status_unconfirmed
    "Direct source" -> R.string.tv_source_status_direct
    else -> R.string.tv_source_status_unconfirmed
})
