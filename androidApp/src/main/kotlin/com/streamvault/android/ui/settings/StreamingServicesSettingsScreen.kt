package com.streamvault.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.home.ALL_STREAMING_SERVICES
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.presentation.home.HomeViewModel
import org.koin.compose.koinInject

@Composable
fun StreamingServicesSettingsScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = koinInject(),
) {
    val enabledIds by viewModel.enabledServiceIds.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                "Streaming Services",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        Text(
            "Choose which services appear on your home screen. Content from these services will be available to browse and play through Torve.",
            style = MaterialTheme.typography.bodyMedium,
            color = Silver,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(ALL_STREAMING_SERVICES) { service ->
                val enabled = enabledIds.contains(service.tmdbProviderId)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        Modifier.size(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = service.brandColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                service.name.take(1),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Text(
                        service.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Snow,
                        modifier = Modifier.weight(1f),
                    )

                    Switch(
                        checked = enabled,
                        onCheckedChange = { viewModel.toggleStreamingService(service.tmdbProviderId, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = AmberSubtle,
                            uncheckedThumbColor = Steel,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                }
                HorizontalDivider(color = Steel.copy(alpha = 0.2f))
            }
        }
    }
}
