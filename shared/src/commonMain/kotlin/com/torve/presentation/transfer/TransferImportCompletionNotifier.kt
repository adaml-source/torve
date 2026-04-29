package com.torve.presentation.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Lightweight cross-platform broadcast for "a credential transfer just
 * imported successfully on this device."
 *
 * The single observable, [lastImportEpochMs], is `0L` initially and
 * advances to the current epoch_ms each time the receiver VM applies a
 * payload (manual paste OR relay-driven). Platform recovery surfaces
 * (Android `RestoreSetupRecoveryCard`, desktop V2 Settings, iOS
 * `RestoreSetupRecoveryWrapper`) `collectAsState` / observe the flow
 * and recompute their `ProviderHealthRecoverySnapshot` on every tick —
 * which makes the "Restore setup from another device" card disappear
 * the instant the underlying credentials show up in the secret store.
 *
 * Strict redaction: this notifier never carries a payload — only a
 * monotonic timestamp. There's nothing to leak.
 */
class TransferImportCompletionNotifier(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _lastImportEpochMs = MutableStateFlow(0L)
    val lastImportEpochMs: StateFlow<Long> = _lastImportEpochMs.asStateFlow()

    /** Receiver VM calls this exactly once per successful apply. */
    fun notifyImportSuccess() {
        _lastImportEpochMs.value = nowMs()
    }
}
