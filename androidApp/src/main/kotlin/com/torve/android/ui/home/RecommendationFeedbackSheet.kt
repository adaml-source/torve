package com.torve.android.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.product.ProductToolRow
import com.torve.android.ui.system.TorveModalBottomSheet
import com.torve.domain.model.MediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationFeedbackSheet(item: MediaItem, onDismiss: () -> Unit, onHide: () -> Unit, onReset: () -> Unit) {
    TorveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            ProductToolRow(stringResource(R.string.gap_recommendation_hide), stringResource(R.string.gap_recommendation_hide_detail),
                Icons.Rounded.ThumbDownOffAlt, onHide)
            ProductToolRow(stringResource(R.string.gap_recommendation_reset), stringResource(R.string.gap_recommendation_reset_detail),
                Icons.Rounded.RestartAlt, onReset)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
internal fun localizedRecommendationReason(reason: String): String = when {
    reason.startsWith("Because you watched ") -> stringResource(R.string.gap_recommendation_watched, reason.removePrefix("Because you watched "))
    reason.startsWith("Because you saved ") -> stringResource(R.string.gap_recommendation_saved, reason.removePrefix("Because you saved "))
    reason.startsWith("Because you like ") -> stringResource(R.string.gap_recommendation_like, reason.removePrefix("Because you like "))
    reason.startsWith("Popular in ") -> stringResource(R.string.gap_recommendation_genre, reason.removePrefix("Popular in "))
    reason == "Highly rated" -> stringResource(R.string.gap_recommendation_rated)
    else -> stringResource(R.string.home_recommended)
}
