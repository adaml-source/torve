package com.torve.presentation.providerhealth

import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.flow.debounce

/**
 * Observes [SettingsRefreshNotifier.events] and re-runs every registered
 * [ProviderHealthChecker] each time settings change. Both
 * `DesktopProviderHealthInit` and `AndroidProviderHealthInit` launch a
 * single long-running collector via [observe].
 *
 * The notifier already fires on every successful `PandaSetupViewModel.
 * saveConfigAndInstall` (the catch branch deliberately skips it on
 * failure), on Debrid token rotations, and on a few other settings
 * pathways — all of which can change provider-health state. This helper
 * is the missing wire that turns those signals into an immediate
 * `coordinator.runAll()` instead of waiting for the next app start /
 * import-completion / "Re-check everything" tap.
 *
 * Debounce keeps a burst of save operations (e.g. a wizard "save +
 * navigate" sequence) from kicking off N parallel checker runs. The
 * coordinator itself is launch-based so concurrent invocations are
 * still safe — debounce is a politeness, not a correctness, lever.
 */
class ProviderHealthRefreshOnSettings(
    private val notifier: SettingsRefreshNotifier,
    private val coordinator: ProviderHealthCoordinator,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    suspend fun observe() {
        val source = if (debounceMs > 0L) notifier.events.debounce(debounceMs) else notifier.events
        source.collect { coordinator.runAll() }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 500L
    }
}
