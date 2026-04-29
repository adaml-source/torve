package com.torve.presentation.setup

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * UI-side state for the credential-first setup hub. Combines the persisted
 * provider-health rows with the four [SetupIntent]s into one render-ready
 * list of [SetupIntentSummary] cards.
 */
class SetupIntentsViewModel(
    private val coordinator: ProviderHealthCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _summaries = MutableStateFlow<List<SetupIntentSummary>>(initialSummaries())
    val summaries: StateFlow<List<SetupIntentSummary>> = _summaries.asStateFlow()

    val rawEntries: StateFlow<List<ProviderHealthEntry>> get() = coordinator.entries

    init {
        scope.launch {
            // Re-derive summaries whenever the underlying health rows change.
            combine(coordinator.entries, _summaries) { rows, _ -> rows }.collect { rows ->
                _summaries.value = SetupIntent.entries.map { intent ->
                    summarize(intent, rows)
                }
            }
        }
    }

    /** Re-run every checker; UI shows a spinner until completion. */
    fun refreshAll(): Job = coordinator.runAll()

    /** Re-run a single provider. */
    fun refresh(providerKey: String): Job? = coordinator.runCheck(providerKey)

    private fun initialSummaries(): List<SetupIntentSummary> = SetupIntent.entries.map { intent ->
        SetupIntentSummary(
            intent = intent,
            status = ProviderHealthStatus.UNCONFIGURED,
            primaryMessage = intent.tagline,
            entries = emptyList(),
        )
    }

    companion object {
        /**
         * Aggregates the rows belonging to an intent into one summary:
         *   - status: worst across the rows (RED > YELLOW > UNKNOWN >
         *     GREEN > UNCONFIGURED). UNCONFIGURED if no rows match yet.
         *   - primaryMessage: the message of the worst-status row (or the
         *     intent tagline if nothing is configured).
         */
        fun summarize(
            intent: SetupIntent,
            rows: List<ProviderHealthEntry>,
        ): SetupIntentSummary {
            val matching = rows.filter { it.category in intent.healthCategories }
            if (matching.isEmpty()) {
                return SetupIntentSummary(
                    intent = intent,
                    status = ProviderHealthStatus.UNCONFIGURED,
                    primaryMessage = intent.tagline,
                    entries = emptyList(),
                )
            }
            val worst = matching.maxBy { it.status.severity() }
            return SetupIntentSummary(
                intent = intent,
                status = worst.status,
                primaryMessage = worst.message ?: intent.tagline,
                entries = matching,
            )
        }

        private fun ProviderHealthStatus.severity(): Int = when (this) {
            ProviderHealthStatus.RED -> 4
            ProviderHealthStatus.YELLOW -> 3
            ProviderHealthStatus.UNKNOWN -> 2
            ProviderHealthStatus.GREEN -> 1
            ProviderHealthStatus.UNCONFIGURED -> 0
        }
    }
}

/**
 * Render-ready row for one of the four setup intent cards.
 */
data class SetupIntentSummary(
    val intent: SetupIntent,
    val status: ProviderHealthStatus,
    val primaryMessage: String,
    val entries: List<ProviderHealthEntry>,
)
