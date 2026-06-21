package com.torve.android.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.tvhome.TvSourcePickerOption
import com.torve.presentation.tvhome.TvSourcePickerState
import com.torve.presentation.tvhome.TvSourceTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSourcePickerSheet(
    state: TvSourcePickerState,
    onSelect: (TvSourcePickerOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Charcoal,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Choose source",
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
            state.providerIssue?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
            state.options.forEach { option ->
                MobileSourceRow(option = option, onClick = { onSelect(option) })
            }
            if (!state.canAutoPlay) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No source is ready right now. Add a download or configure a provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun MobileSourceRow(
    option: TvSourcePickerOption,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "${option.label} - ${option.tier.displayLabel()}",
            style = MaterialTheme.typography.titleSmall,
            color = if (option.tier == TvSourceTier.BEST) Amber else Snow,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = option.hint,
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
    }
}

private fun TvSourceTier.displayLabel(): String = when (this) {
    TvSourceTier.BEST -> "Best"
    TvSourceTier.FALLBACK -> "Fallback"
    TvSourceTier.RE_DOWNLOAD -> "Download to play"
}
