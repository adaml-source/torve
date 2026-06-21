package com.torve.desktop.premium

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide accessor for [DesktopPremiumState]. Lives outside Koin
 * because plenty of UI surfaces (and the Window onFocus handler in
 * Main.kt) need read access without threading the holder through
 * every composable signature.
 *
 * Bind once from `bootstrapDesktop` after Koin resolves, then call
 * [hasPremium] / [refreshNow] from anywhere.
 */
object DesktopPremiumStateHolder {

    @Volatile
    private var instance: DesktopPremiumState? = null

    private val _hasPremium = MutableStateFlow(true)
    /** Read-only compatibility flow. True means free product availability. */
    val hasPremium: StateFlow<Boolean> = _hasPremium

    private val _accessState = MutableStateFlow<com.torve.data.device.AccessStateDto?>(null)
    /** Last fetched access-state - used by Settings to show tier + expiry. */
    val accessState: StateFlow<com.torve.data.device.AccessStateDto?> = _accessState

    fun bind(state: DesktopPremiumState) {
        instance = state
        // Mirror the bound state's flows into our process-wide ones so
        // the rest of the UI can subscribe to one canonical source.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            state.hasPremium.collect { _hasPremium.value = it }
        }
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            state.accessState.collect { _accessState.value = it }
        }
    }

    /** Compatibility helper. Desktop product features are free by default. */
    fun isPremium(): Boolean = true

    /** Trigger an immediate refresh; no-op if not yet bound or signed-out. */
    fun refreshNow() {
        instance?.refreshNow()
    }

    /** Legacy fast-poll hook retained for source compatibility. */
    fun pollAggressivelyFor(seconds: Int = 300) {
        instance?.pollAggressivelyFor(seconds)
    }

    /**
     * Wire the inner state's flow into [hasPremium]. Called once in
     * [bind]; idempotent.
     */
    init {
        // The inner state's flow drives ours via subscribe-on-first-bind.
        // Cleaner than threading a CoroutineScope through this object.
    }

    internal fun pushHasPremium(value: Boolean) {
        _hasPremium.value = value
    }
}
