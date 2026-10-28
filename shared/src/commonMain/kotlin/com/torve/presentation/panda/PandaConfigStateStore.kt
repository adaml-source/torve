package com.torve.presentation.panda

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton mirror of the latest [PandaSetupUiState].
 *
 * The account-scoped [PandaSetupViewModel] publishes here so provider-health
 * checkers and settings destinations observe the same synchronized config.
 *
 * On construction the VM publishes its initial state and attaches a
 * `state.collect { … }` so every VM-side update mirrors here. Checkers
 * read [current] (a snapshot) or observe [state] (a flow). The default
 * value is [PandaSetupUiState] with `isEditMode = false`, which every
 * Panda checker already treats as UNCONFIGURED — so a fresh-install
 * device that has never opened the wizard reports "UNCONFIGURED" via
 * the checkers without any special-casing.
 *
 * Carries no secrets that aren't already in [PandaSetupUiState].
 */
class PandaConfigStateStore {

    private val _state = MutableStateFlow(PandaSetupUiState())
    val state: StateFlow<PandaSetupUiState> = _state.asStateFlow()

    /** Latest snapshot the VM has published, or the default unconfigured state. */
    val current: PandaSetupUiState get() = _state.value

    /**
     * Called by [PandaSetupViewModel] on every `_state.value` change.
     * Idempotent: publishing the same state twice is a no-op for
     * downstream `StateFlow` consumers.
     */
    fun publish(state: PandaSetupUiState) {
        _state.value = state
    }
}
