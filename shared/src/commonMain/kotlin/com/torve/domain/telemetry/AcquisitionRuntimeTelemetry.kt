package com.torve.domain.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Privacy-safe, in-session acquisition health counters for diagnostics.
 *
 * Titles, media IDs, request IDs, queue IDs, server addresses and error text are
 * deliberately excluded. Every value has a small, closed shape so exporting a
 * diagnostic bundle cannot disclose a user's library or credentials.
 */
data class AcquisitionPerformanceSnapshot(
    val refreshSuccesses: Int = 0,
    val refreshFailures: Int = 0,
    val retryRequested: Int = 0,
    val retrySucceeded: Int = 0,
    val retryFailed: Int = 0,
    val cancelRequested: Int = 0,
    val cancelSucceeded: Int = 0,
    val cancelFailed: Int = 0,
    val stageTransitions: Int = 0,
    val becameAvailable: Int = 0,
    val activeItems: Int = 0,
    val attentionItems: Int = 0,
    val lastUpdatedAtMs: Long? = null,
)

enum class AcquisitionTelemetryAction {
    RETRY,
    CANCEL,
}

object AcquisitionRuntimeTelemetry {
    private val state = MutableStateFlow(AcquisitionPerformanceSnapshot())

    fun snapshot(): AcquisitionPerformanceSnapshot = state.value

    fun recordRefreshSuccess(
        activeItems: Int,
        attentionItems: Int,
        stageTransitions: Int,
        becameAvailable: Int,
        updatedAtMs: Long,
    ) {
        state.update { current ->
            current.copy(
                refreshSuccesses = current.refreshSuccesses + 1,
                stageTransitions = current.stageTransitions + stageTransitions.coerceAtLeast(0),
                becameAvailable = current.becameAvailable + becameAvailable.coerceAtLeast(0),
                activeItems = activeItems.coerceAtLeast(0),
                attentionItems = attentionItems.coerceAtLeast(0),
                lastUpdatedAtMs = updatedAtMs,
            )
        }
    }

    fun recordRefreshFailure(updatedAtMs: Long) {
        state.update { current ->
            current.copy(
                refreshFailures = current.refreshFailures + 1,
                lastUpdatedAtMs = updatedAtMs,
            )
        }
    }

    fun recordActionRequested(action: AcquisitionTelemetryAction, updatedAtMs: Long) {
        state.update { current ->
            when (action) {
                AcquisitionTelemetryAction.RETRY -> current.copy(
                    retryRequested = current.retryRequested + 1,
                    lastUpdatedAtMs = updatedAtMs,
                )
                AcquisitionTelemetryAction.CANCEL -> current.copy(
                    cancelRequested = current.cancelRequested + 1,
                    lastUpdatedAtMs = updatedAtMs,
                )
            }
        }
    }

    fun recordActionResult(
        action: AcquisitionTelemetryAction,
        succeeded: Boolean,
        updatedAtMs: Long,
    ) {
        state.update { current ->
            when (action) {
                AcquisitionTelemetryAction.RETRY -> if (succeeded) {
                    current.copy(
                        retrySucceeded = current.retrySucceeded + 1,
                        lastUpdatedAtMs = updatedAtMs,
                    )
                } else {
                    current.copy(
                        retryFailed = current.retryFailed + 1,
                        lastUpdatedAtMs = updatedAtMs,
                    )
                }
                AcquisitionTelemetryAction.CANCEL -> if (succeeded) {
                    current.copy(
                        cancelSucceeded = current.cancelSucceeded + 1,
                        lastUpdatedAtMs = updatedAtMs,
                    )
                } else {
                    current.copy(
                        cancelFailed = current.cancelFailed + 1,
                        lastUpdatedAtMs = updatedAtMs,
                    )
                }
            }
        }
    }

    internal fun clearForTest() {
        state.value = AcquisitionPerformanceSnapshot()
    }
}

internal fun acquisitionCountBucket(value: Int): String = when {
    value <= 0 -> "0"
    value == 1 -> "1"
    value <= 3 -> "2_3"
    value <= 10 -> "4_10"
    value <= 25 -> "11_25"
    else -> "gt_25"
}
