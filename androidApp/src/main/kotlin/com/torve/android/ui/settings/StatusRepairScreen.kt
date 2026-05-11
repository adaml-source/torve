package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.transfer.providerSettingsRouteFor
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import org.koin.compose.koinInject

@Composable
fun StatusRepairScreen(
    onBack: () -> Unit,
    onNavigate: (route: String) -> Unit,
    onDiagnose: () -> Unit,
    coordinator: ProviderHealthCoordinator = koinInject(),
) {
    LaunchedEffect(Unit) { coordinator.runAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton(onClick = onBack)
            Text(
                text = "Status & Repair",
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { coordinator.runAll() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh all")
        }
        Spacer(Modifier.height(16.dp))
        ProviderStatusSection(
            onConfigure = { entry ->
                providerSettingsRouteFor(entry.category)?.let(onNavigate)
            },
            onDiagnose = { onDiagnose() },
        )
    }
}
