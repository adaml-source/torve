package com.torve.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Single source of the credential-transfer banner UI shared by the send
 * and receive screens. Lives in this package because both screens are
 * Android-only; lifting it further (e.g. to a shared Compose Multiplatform
 * module) is out of scope.
 */
internal enum class TransferBannerTone { Info, Success, Warning, Error }

@Composable
internal fun TransferStatusBanner(title: String, body: String, tone: TransferBannerTone) {
    val container = when (tone) {
        TransferBannerTone.Info -> MaterialTheme.colorScheme.surfaceVariant
        TransferBannerTone.Success -> MaterialTheme.colorScheme.tertiaryContainer
        TransferBannerTone.Warning -> MaterialTheme.colorScheme.errorContainer
        TransferBannerTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (tone) {
        TransferBannerTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        TransferBannerTone.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        TransferBannerTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        TransferBannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = onContainer)
            Text(body, style = MaterialTheme.typography.bodySmall, color = onContainer)
        }
    }
}
