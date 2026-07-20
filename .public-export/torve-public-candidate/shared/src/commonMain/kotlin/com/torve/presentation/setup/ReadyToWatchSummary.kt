package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.ProviderRepairAction
import com.torve.presentation.providerhealth.ProviderRepairMapper

/**
 * Render-ready snapshot of "can the user start watching yet?".
 *
 * Computed off the per-intent state map plus the provider-health rows so
 * the coordinator and the existing `ProviderHealthCoordinator` always
 * agree on what's ready (any divergence means the validator and the
 * checker for the same intent disagree, which is a bug to surface in the
 * UI rather than hide).
 */
data class ReadyToWatchSummary(
    /** Intents whose validator returned READY. */
    val ready: List<SetupIntent>,
    /** Intents whose validator returned NEEDS_ATTENTION (yellow). */
    val warnings: List<SetupIntent>,
    /** Intents whose validator returned INVALID (red). */
    val invalid: List<SetupIntent>,
    /** Intents the user has started but not validated yet. */
    val inProgress: List<SetupIntent>,
    /** Intents the user has not visited at all. */
    val notStarted: List<SetupIntent>,
    /**
     * Per-intent repair actions, populated only for intents that need them
     * (NEEDS_ATTENTION, INVALID, or NOT_STARTED with a transferable
     * provider-health row). Empty list for READY intents.
     */
    val repairActions: Map<SetupIntent, List<ProviderRepairAction>>,
) {
    /**
     * The headline answer: can the user start watching right now?
     * True iff at least one intent is READY.
     */
    val canStartWatching: Boolean get() = ready.isNotEmpty()

    /** Total count of "needs your attention" rows. */
    val attentionCount: Int get() = warnings.size + invalid.size

    /**
     * Provider-health-resolved per-intent status — what the row badge
     * should display. Without this, the summary card and the per-row
     * badge can disagree (summary says "Ready: IPTV" while the IPTV row
     * still shows "Not started" because the row was reading raw
     * SetupIntentState.status, missing the GREEN provider-health row
     * that flipped IPTV into ready).
     */
    fun resolvedStatusFor(intent: SetupIntent): SetupIntentStatus = when {
        intent in ready -> SetupIntentStatus.READY
        intent in warnings -> SetupIntentStatus.NEEDS_ATTENTION
        intent in invalid -> SetupIntentStatus.INVALID
        intent in inProgress -> SetupIntentStatus.IN_PROGRESS
        else -> SetupIntentStatus.NOT_STARTED
    }

    companion object {
        /**
         * Computes a [ReadyToWatchSummary] from the per-intent state map and
         * the provider-health rows. Either side may be empty.
         *
         * When per-intent status disagrees with the worst provider-health
         * row for that intent, the worse status wins — a row reporting RED
         * after a previously-validated GREEN means something rotated and
         * the user must re-fix it. The summary's `repairActions` reflect
         * the resolved status, not the optimistic one.
         */
        fun from(
            states: Map<SetupIntent, SetupIntentState>,
            healthRows: List<ProviderHealthEntry>,
        ): ReadyToWatchSummary {
            val ready = mutableListOf<SetupIntent>()
            val warnings = mutableListOf<SetupIntent>()
            val invalid = mutableListOf<SetupIntent>()
            val inProgress = mutableListOf<SetupIntent>()
            val notStarted = mutableListOf<SetupIntent>()
            val repair = mutableMapOf<SetupIntent, List<ProviderRepairAction>>()

            for (intent in SetupIntent.entries) {
                val perIntent = states[intent]?.status ?: SetupIntentStatus.NOT_STARTED
                val matchingRows = healthRows.filter { it.category in intent.healthCategories }
                val resolved = resolveStatus(perIntent, matchingRows)

                when (resolved) {
                    SetupIntentStatus.READY -> ready += intent
                    SetupIntentStatus.NEEDS_ATTENTION -> warnings += intent
                    SetupIntentStatus.INVALID -> invalid += intent
                    SetupIntentStatus.IN_PROGRESS -> inProgress += intent
                    // VALIDATING collapses to IN_PROGRESS for summary purposes
                    // (the spinner is a UI detail, not a planning detail).
                    SetupIntentStatus.VALIDATING -> inProgress += intent
                    SetupIntentStatus.NOT_STARTED -> notStarted += intent
                }

                val actions = repairActionsFor(resolved, matchingRows)
                if (actions.isNotEmpty()) repair[intent] = actions
            }

            return ReadyToWatchSummary(
                ready = ready,
                warnings = warnings,
                invalid = invalid,
                inProgress = inProgress,
                notStarted = notStarted,
                repairActions = repair,
            )
        }

        /**
         * Pessimistic merge: if any provider-health row for the intent is
         * worse than the persisted setup state, the row wins. Empty rows
         * fall through to whatever the per-intent state already says.
         */
        internal fun resolveStatus(
            perIntent: SetupIntentStatus,
            rows: List<ProviderHealthEntry>,
        ): SetupIntentStatus {
            if (rows.isEmpty()) return perIntent
            val worstRow = rows.maxBy { it.status.healthSeverity() }
            val rowAsIntent = worstRow.status.toIntentStatus()
            return if (rowAsIntent.severityRank() > perIntent.severityRank()) rowAsIntent else perIntent
        }

        private fun repairActionsFor(
            status: SetupIntentStatus,
            rows: List<ProviderHealthEntry>,
        ): List<ProviderRepairAction> {
            return when (status) {
                SetupIntentStatus.READY,
                SetupIntentStatus.VALIDATING,
                SetupIntentStatus.IN_PROGRESS -> emptyList()
                SetupIntentStatus.NOT_STARTED,
                SetupIntentStatus.NEEDS_ATTENTION,
                SetupIntentStatus.INVALID -> {
                    // Pick the worst row's repair actions. If no rows yet
                    // (NOT_STARTED case), fall back to a synthetic
                    // "unconfigured" entry so the user still sees a way in.
                    val worst = rows.maxByOrNull { it.status.healthSeverity() }
                    if (worst != null) {
                        ProviderRepairMapper.actionsFor(worst)
                    } else {
                        listOf(ProviderRepairAction.ReenterCredentials)
                    }
                }
            }
        }

        private fun ProviderHealthStatus.healthSeverity(): Int = when (this) {
            ProviderHealthStatus.RED -> 4
            ProviderHealthStatus.YELLOW -> 3
            ProviderHealthStatus.UNKNOWN -> 2
            ProviderHealthStatus.GREEN -> 1
            ProviderHealthStatus.UNCONFIGURED -> 0
        }

        private fun ProviderHealthStatus.toIntentStatus(): SetupIntentStatus = when (this) {
            ProviderHealthStatus.GREEN -> SetupIntentStatus.READY
            ProviderHealthStatus.YELLOW -> SetupIntentStatus.NEEDS_ATTENTION
            ProviderHealthStatus.RED -> SetupIntentStatus.INVALID
            ProviderHealthStatus.UNKNOWN -> SetupIntentStatus.VALIDATING
            ProviderHealthStatus.UNCONFIGURED -> SetupIntentStatus.NOT_STARTED
        }

        private fun SetupIntentStatus.severityRank(): Int = when (this) {
            SetupIntentStatus.INVALID -> 5
            SetupIntentStatus.NEEDS_ATTENTION -> 4
            SetupIntentStatus.VALIDATING -> 3
            SetupIntentStatus.IN_PROGRESS -> 2
            SetupIntentStatus.READY -> 1
            SetupIntentStatus.NOT_STARTED -> 0
        }
    }
}
