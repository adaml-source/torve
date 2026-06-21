package com.torve.android.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.session.PostSignInRefresh
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.TransferApplyResult
import com.torve.domain.transfer.TransferDecryptResult
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.RelayStatus
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.presentation.transfer.TransferImportResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mobile + TV credential-transfer receive surface. Shows a QR + plain
 * session string for the sender to scan/paste, a 1 Hz countdown to
 * expiry, a relay-status banner that explains whether auto-import is
 * on, and a manual sealed-code paste field as a permanent fallback.
 *
 * `largeQr = true` (TV) renders the QR as a fixed large block so it
 * stays scannable from couch distance and the paste field tucks under
 * an "Advanced" toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsTransferReceiveScreen(
    viewModel: SecretsTransferReceiverViewModel,
    onBack: () -> Unit,
    largeQr: Boolean = false,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val closeRequester = remember { FocusRequester() }
    var fullRefreshStarted by remember { mutableStateOf(false) }

    fun closeReceiveScreen() {
        viewModel.cancel()
        onBack()
    }

    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(largeQr) {
        if (largeQr) {
            delay(80)
            runCatching { closeRequester.requestFocus() }
        }
    }
    LaunchedEffect(state) {
        if (state is ReceiverState.Imported) {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            runCatching {
                koin.get<com.torve.presentation.settings.SettingsViewModel>().refreshSettings()
            }
            runCatching {
                if (largeQr) {
                    PostSignInRefresh.enqueueAfterCredentialImport(context)
                }
                koin.get<com.torve.presentation.channels.ChannelsViewModel>()
                    .loadPlaylists(recoverEmptyCatalog = !largeQr)
                koin.get<com.torve.presentation.channels.ChannelsViewModel>().loadFavorites()
            }
            runCatching {
                koin.get<com.torve.presentation.watchlist.WatchlistViewModel>().loadWatchlist()
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }
    BackHandler { closeReceiveScreen() }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                closeReceiveScreen()
                true
            } else {
                false
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_receive_title)) },
                navigationIcon = {
                    IconButton(onClick = ::closeReceiveScreen) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            if (largeQr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        modifier = Modifier.focusRequester(closeRequester),
                        onClick = ::closeReceiveScreen,
                    ) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }

            when (val s = state) {
                ReceiverState.Idle -> Text(
                    text = stringResource(R.string.transfer_preparing_handshake),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ReceiverState.Active -> ActiveBlock(
                    state = s,
                    largeQr = largeQr,
                    onEnvelopeChanged = viewModel::updateEnvelopeText,
                    onImport = { scope.launch { viewModel.acceptEnvelopeJson() } },
                )
                is ReceiverState.Imported -> ImportedBlock(
                    result = s.result,
                    refreshStarted = fullRefreshStarted,
                    onRefreshAll = {
                        fullRefreshStarted = true
                        scope.launch {
                            try {
                                val koin = org.koin.java.KoinJavaComponent.getKoin()
                                runCatching {
                                    koin.get<com.torve.presentation.session.AccountSessionCoordinator>()
                                        .refreshAccountDataAfterCredentialTransfer()
                                }
                                runCatching {
                                    koin.get<com.torve.presentation.settings.SettingsViewModel>().refreshSettings()
                                }
                                runCatching {
                                    koin.get<com.torve.presentation.channels.ChannelsViewModel>()
                                        .loadPlaylists(recoverEmptyCatalog = !largeQr)
                                    koin.get<com.torve.presentation.channels.ChannelsViewModel>().loadFavorites()
                                }
                                runCatching {
                                    koin.get<com.torve.presentation.watchlist.WatchlistViewModel>().loadWatchlist()
                                }
                                PostSignInRefresh.enqueueContentWarmupAfterAccountActivation(context)
                            } finally {
                                fullRefreshStarted = false
                            }
                        }
                    },
                )
                ReceiverState.Expired -> ExpiredBlock(
                    onRestart = { scope.launch { viewModel.restart() } },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActiveBlock(
    state: ReceiverState.Active,
    largeQr: Boolean,
    onEnvelopeChanged: (String) -> Unit,
    onImport: () -> Unit,
) {
    val qrBitmap = remember(state.sessionString) {
        runCatching { AndroidTransferQrRenderer.render(state.sessionString) }.getOrNull()
    }

    val explainer = if (largeQr) {
        stringResource(R.string.transfer_receive_explainer_tv)
    } else {
        stringResource(R.string.transfer_receive_explainer_desktop)
    }
    Text(
        text = explainer,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // On TV, lay QR and details side-by-side so a phone camera can
    // frame the QR without it dominating the screen, and the receiver
    // code + copy button stay above the fold. On mobile/portrait,
    // stack vertically as before.
    if (largeQr) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            QrSurface(qrBitmap = qrBitmap, modifier = Modifier.size(320.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CountdownChip(remainingSeconds = state.remainingSeconds)
                RelayStatusBanner(state.relayStatus)
                SessionStringBlock(sessionString = state.sessionString, onCopy = {})
            }
        }
    } else {
        QrSurface(
            qrBitmap = qrBitmap,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(top = 4.dp),
        )
        CountdownChip(remainingSeconds = state.remainingSeconds)
        RelayStatusBanner(state.relayStatus)
        SessionStringBlock(sessionString = state.sessionString, onCopy = {})
    }

    AdvancedPasteSection(
        envelopeText = state.envelopeText,
        importing = state.importing,
        importResult = state.importResult,
        relayStatus = state.relayStatus,
        onChange = onEnvelopeChanged,
        onImport = onImport,
    )
}

@Composable
private fun QrSurface(
    qrBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = stringResource(R.string.transfer_qr_code_cd),
                    modifier = Modifier.aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            } else {
                Text(stringResource(R.string.transfer_qr_unavailable), color = Color.Black)
            }
        }
    }
}

@Composable
private fun CountdownChip(remainingSeconds: Long) {
    val mm = (remainingSeconds / 60L).coerceAtLeast(0L)
    val ss = (remainingSeconds % 60L).coerceAtLeast(0L)
    val tone = when {
        remainingSeconds <= 30L -> MaterialTheme.colorScheme.error
        remainingSeconds <= 120L -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .background(tone.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.transfer_expires_in, mm, ss),
                style = MaterialTheme.typography.labelMedium,
                color = tone,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RelayStatusBanner(status: RelayStatus) {
    when (status) {
        RelayStatus.NotConfigured -> {
            // No relay was injected; surface only the manual paste flow.
        }
        RelayStatus.Registering -> StatusBanner(
            title = stringResource(R.string.transfer_auto_import_setting_up),
            body = stringResource(R.string.transfer_auto_import_setting_up_body),
            tone = TransferBannerTone.Info,
        )
        is RelayStatus.Registered -> StatusBanner(
            title = stringResource(R.string.transfer_auto_import_on),
            body = stringResource(R.string.transfer_auto_import_on_body),
            tone = TransferBannerTone.Success,
        )
        is RelayStatus.Unavailable -> StatusBanner(
            title = stringResource(R.string.transfer_auto_import_unavailable),
            body = stringResource(R.string.transfer_auto_import_unavailable_body, status.reason),
            tone = TransferBannerTone.Warning,
        )
    }
}

@Composable
private fun SessionStringBlock(
    sessionString: String,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.transfer_receive_short_code_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        ) {
            SelectionContainer {
                Text(
                    text = sessionString,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        TextButton(onClick = {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.transfer_clipboard_receive_code), sessionString))
            onCopy()
        }) { Text(stringResource(R.string.transfer_copy_receiver_code)) }
    }
}

@Composable
private fun AdvancedPasteSection(
    envelopeText: String,
    importing: Boolean,
    importResult: TransferImportResult?,
    relayStatus: RelayStatus,
    onChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    val relayRegistered = relayStatus is RelayStatus.Registered
    var advancedOpen by remember(relayRegistered) { mutableStateOf(!relayRegistered) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (relayRegistered) {
            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                Text(
                    if (advancedOpen) {
                        stringResource(R.string.transfer_hide_manual_paste)
                    } else {
                        stringResource(R.string.transfer_advanced_paste_sealed_code)
                    },
                )
            }
        }
        if (advancedOpen) {
            OutlinedTextField(
                value = envelopeText,
                onValueChange = onChange,
                label = { Text(stringResource(R.string.transfer_sealed_code_from_sender)) },
                placeholder = { Text(stringResource(R.string.transfer_sealed_code_placeholder)) },
                singleLine = false,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            )
            Button(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.transfer_importing))
                } else {
                    Text(stringResource(R.string.transfer_import_sealed_code))
                }
            }
            importResult?.let { ImportResultBanner(it) }
        }
    }
}

@Composable
private fun ImportedBlock(
    result: TransferImportResult.Success,
    refreshStarted: Boolean,
    onRefreshAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBanner(
            title = stringResource(R.string.transfer_credentials_imported),
            body = importDescription(result),
            tone = TransferBannerTone.Success,
        )
        Button(
            onClick = onRefreshAll,
            enabled = !refreshStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (refreshStarted) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.transfer_refresh_all_after_import_running))
            } else {
                Text(stringResource(R.string.transfer_refresh_all_after_import))
            }
        }
    }
}

@Composable
private fun ExpiredBlock(onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBanner(
            title = stringResource(R.string.transfer_receive_code_expired),
            body = stringResource(R.string.transfer_receive_code_expired_body),
            tone = TransferBannerTone.Warning,
        )
        // Primary action — receive codes are a low-friction operation,
        // so we lead with regenerate instead of forcing the user to
        // back out and re-enter.
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transfer_generate_new_code))
        }
    }
}

@Composable
private fun ImportResultBanner(result: TransferImportResult) {
    when (result) {
        is TransferImportResult.Success -> StatusBanner(
            title = stringResource(R.string.transfer_credentials_imported),
            body = importDescription(result),
            tone = TransferBannerTone.Success,
        )
        is TransferImportResult.MalformedEnvelope -> StatusBanner(
            title = stringResource(R.string.transfer_invalid_sealed_code),
            body = result.reason,
            tone = TransferBannerTone.Error,
        )
        is TransferImportResult.DecryptFailure -> StatusBanner(
            title = decryptTitle(result.result),
            body = decryptDescription(result.result),
            tone = TransferBannerTone.Error,
        )
        is TransferImportResult.ApplyFailure -> StatusBanner(
            title = stringResource(R.string.transfer_could_not_apply_credentials),
            body = applyDescription(result.result),
            tone = TransferBannerTone.Error,
        )
        TransferImportResult.NoActiveSession -> StatusBanner(
            title = stringResource(R.string.transfer_no_active_receive_session),
            body = stringResource(R.string.transfer_generate_receive_code_first),
            tone = TransferBannerTone.Error,
        )
        TransferImportResult.MissingPrivateKey -> StatusBanner(
            title = stringResource(R.string.transfer_receive_session_unusable),
            body = stringResource(R.string.transfer_generate_receive_code_try_again),
            tone = TransferBannerTone.Error,
        )
    }
}

@Composable
private fun decryptTitle(result: TransferDecryptResult): String = when (result) {
    TransferDecryptResult.Expired -> stringResource(R.string.transfer_sealed_code_expired)
    TransferDecryptResult.AuthenticationFailure -> stringResource(R.string.transfer_could_not_decrypt_code)
    is TransferDecryptResult.UnsupportedVersion -> stringResource(R.string.transfer_unsupported_transfer_version)
    TransferDecryptResult.Replayed -> stringResource(R.string.transfer_code_already_used)
    TransferDecryptResult.EnvelopePayloadMismatch -> stringResource(R.string.transfer_code_integrity_failed)
    is TransferDecryptResult.Malformed -> stringResource(R.string.transfer_malformed_sealed_code)
    is TransferDecryptResult.Success -> stringResource(R.string.transfer_credentials_imported)
}

@Composable
private fun decryptDescription(result: TransferDecryptResult): String = when (result) {
    TransferDecryptResult.Expired -> stringResource(R.string.transfer_ask_sender_fresh_code)
    TransferDecryptResult.AuthenticationFailure -> stringResource(R.string.transfer_code_not_for_session)
    is TransferDecryptResult.UnsupportedVersion -> stringResource(R.string.transfer_cannot_read_version, result.seenVersion)
    TransferDecryptResult.Replayed -> stringResource(R.string.transfer_nonce_already_consumed)
    TransferDecryptResult.EnvelopePayloadMismatch -> stringResource(R.string.transfer_envelope_payload_mismatch)
    is TransferDecryptResult.Malformed -> result.reason
    is TransferDecryptResult.Success -> stringResource(R.string.transfer_imported_records, result.payload.secrets.size)
}

@Composable
private fun applyDescription(result: TransferApplyResult): String = when (result) {
    TransferApplyResult.DuplicateNonce -> stringResource(R.string.transfer_nonce_already_consumed)
    is TransferApplyResult.NothingApplied -> stringResource(R.string.transfer_no_known_keys)
    is TransferApplyResult.StoreFailure -> {
        val rollbackMessage = if (result.rollbackAttempted) {
            if (result.rollbackSucceeded) {
                stringResource(R.string.transfer_rollback_succeeded)
            } else {
                stringResource(R.string.transfer_rollback_failed)
            }
        } else {
            null
        }
        listOfNotNull(result.message, rollbackMessage).joinToString(" ")
    }
    is TransferApplyResult.Success -> stringResource(R.string.transfer_imported_records, result.applied)
}

@Composable
private fun importDescription(result: TransferImportResult.Success): String {
    val configCount = result.applyResult.configApplied
    val applied = result.applyResult.applied
    val parts = mutableListOf<String>()
    parts += if (configCount > 0) {
        stringResource(R.string.transfer_imported_with_setup)
    } else {
        stringResource(R.string.transfer_imported_credentials_only)
    }
    parts += if (configCount > 0) {
        stringResource(R.string.transfer_imported_records_with_config, applied, configCount)
    } else {
        stringResource(R.string.transfer_imported_records, applied)
    }
    if (result.applyResult.skippedKeyNames.isNotEmpty()) {
        parts += stringResource(R.string.transfer_skipped_unknown_keys, result.applyResult.skippedKeyNames.joinToString())
    }
    if (result.applyResult.skippedConfigKeys.isNotEmpty()) {
        parts += stringResource(R.string.transfer_skipped_config_keys, result.applyResult.skippedConfigKeys.joinToString())
    }
    if (result.applyResult.categoriesMissingCompanionConfig.isNotEmpty()) {
        val categoryTitles = transferCategoryTitles()
        val names = result.applyResult.categoriesMissingCompanionConfig.joinToString { categoryTitles.getValue(it) }
        parts += stringResource(R.string.transfer_import_missing_companion_config, names)
    }
    return parts.joinToString(" ")
}

@Composable
private fun transferCategoryTitle(category: SecretCategory): String = when (category) {
    SecretCategory.DEBRID -> stringResource(R.string.transfer_category_debrid)
    SecretCategory.IPTV -> stringResource(R.string.transfer_category_iptv)
    SecretCategory.PLEX_JELLYFIN -> stringResource(R.string.transfer_category_plex_jellyfin)
    SecretCategory.TRAKT_SIMKL -> stringResource(R.string.transfer_category_trakt_simkl)
    SecretCategory.AI_KEYS -> stringResource(R.string.transfer_category_ai_keys)
    SecretCategory.PANDA -> stringResource(R.string.transfer_category_panda)
}

@Composable
private fun transferCategoryTitles(): Map<SecretCategory, String> = mapOf(
    SecretCategory.DEBRID to stringResource(R.string.transfer_category_debrid),
    SecretCategory.IPTV to stringResource(R.string.transfer_category_iptv),
    SecretCategory.PLEX_JELLYFIN to stringResource(R.string.transfer_category_plex_jellyfin),
    SecretCategory.TRAKT_SIMKL to stringResource(R.string.transfer_category_trakt_simkl),
    SecretCategory.AI_KEYS to stringResource(R.string.transfer_category_ai_keys),
    SecretCategory.PANDA to stringResource(R.string.transfer_category_panda),
)

@Composable
private fun StatusBanner(title: String, body: String, tone: TransferBannerTone) {
    TransferStatusBanner(title = title, body = body, tone = tone)
}
