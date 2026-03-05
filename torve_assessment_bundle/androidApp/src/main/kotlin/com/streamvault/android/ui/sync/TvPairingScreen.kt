package com.streamvault.android.ui.sync

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.domain.sync.AccountSyncManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun TvPairingScreen(
    onBack: () -> Unit,
    accountSyncManager: AccountSyncManager = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val status by accountSyncManager.status.collectAsState()
    var pairingCode by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        accountSyncManager.refreshStatus()
    }

    val deepLink = if (pairingCode.isNotBlank()) "torve://pair?code=$pairingCode" else ""
    val qrBitmap = remember(deepLink) {
        if (deepLink.isBlank()) null else runCatching { generateQrBitmap(deepLink, 420) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackButton(onClick = onBack)

        Text(
            text = "Pair with phone",
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!showCode) {
                    Text(
                        text = "Pair with phone to enable Sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                accountSyncManager.enableSync()
                                pairingCode = accountSyncManager.createPairingCode()
                                showCode = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Show code")
                    }
                } else {
                    Text(
                        text = "Code",
                        style = MaterialTheme.typography.labelLarge,
                        color = Silver,
                    )
                    Text(
                        text = pairingCode,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                    )

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .size(220.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                        )
                    }

                    Text(
                        text = "On your phone, open Torve and enter this code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                accountSyncManager.completeTvPairing("Torve TV")
                                infoMessage = "Pairing marked complete."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I paired on phone")
                    }
                }

                if (status.pairedDevicesCount > 0) {
                    Text(
                        text = "Paired devices: ${status.pairedDevicesCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                    )
                }

                infoMessage?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow,
                    )
                }
            }
        }
    }
}

private fun generateQrBitmap(value: String, size: Int): Bitmap {
    val bitMatrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
