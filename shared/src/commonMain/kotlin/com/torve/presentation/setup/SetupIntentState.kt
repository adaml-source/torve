package com.torve.presentation.setup

import kotlinx.serialization.Serializable

/**
 * Per-intent progress state for the credential-first setup wizard.
 *
 * The state machine is intentionally narrow: the wizard never tries to be
 * a full project planner. It just records "did the user start this?",
 * "did we validate it?", and the headline result. Detailed per-row info
 * stays in the provider-health rows the wizard's validators delegate to.
 */
@Serializable
enum class SetupIntentStatus {
    /** User hasn't visited this path yet. */
    NOT_STARTED,

    /** User entered some credential but has not run a validation pass. */
    IN_PROGRESS,

    /** Validation is currently in flight — UI shows a spinner. */
    VALIDATING,

    /** Green: ready to watch from this path. */
    READY,

    /** Yellow: usable but degraded — partial / missing companion config. */
    NEEDS_ATTENTION,

    /** Red: invalid credentials, unreachable provider, or unsupported. */
    INVALID,
}

/**
 * One persisted slot per [SetupIntent].
 *
 * `message` and `nextAction` are short, human-readable strings the
 * coordinator copies from the validator's [SetupIntentValidation]. They
 * are NOT secret-bearing — validators promise to scrub credentials before
 * producing them, same contract as [com.torve.domain.providerhealth.ProviderHealthEntry].
 */
@Serializable
data class SetupIntentState(
    val intent: SetupIntent,
    val status: SetupIntentStatus = SetupIntentStatus.NOT_STARTED,
    val message: String? = null,
    val nextAction: String? = null,
    /** Epoch millis of the last status change. Null until first transition. */
    val updatedAtMs: Long? = null,
)

/**
 * Result of a single intent validation. Mirrors [SetupIntentStatus] minus
 * the in-flight state — coordinator owns the VALIDATING transition.
 */
data class SetupIntentValidation(
    val status: SetupIntentStatus,
    val message: String? = null,
    val nextAction: String? = null,
) {
    init {
        require(status != SetupIntentStatus.VALIDATING) {
            "Validators must not return VALIDATING — that's a coordinator-only state."
        }
    }

    companion object {
        fun ready(message: String? = null) =
            SetupIntentValidation(SetupIntentStatus.READY, message, nextAction = null)

        fun needsAttention(message: String, nextAction: String? = null) =
            SetupIntentValidation(SetupIntentStatus.NEEDS_ATTENTION, message, nextAction)

        fun invalid(message: String, nextAction: String? = null) =
            SetupIntentValidation(SetupIntentStatus.INVALID, message, nextAction)

        fun notStarted(message: String? = null, nextAction: String? = null) =
            SetupIntentValidation(SetupIntentStatus.NOT_STARTED, message, nextAction)

        fun inProgress(message: String? = null, nextAction: String? = null) =
            SetupIntentValidation(SetupIntentStatus.IN_PROGRESS, message, nextAction)
    }
}

/**
 * One pluggable per-intent validator. Mirrors the
 * [com.torve.presentation.providerhealth.ProviderHealthChecker] contract:
 * MUST NOT throw, MUST NOT include secret values in returned strings.
 */
interface IntentValidator {
    val intent: SetupIntent
    suspend fun validate(): SetupIntentValidation
}
