package com.torve.domain.diagnostics

import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot

/**
 * Producer of the one-tap "Export diagnostics" text bundle Prompt 26
 * promises. Pure: takes structured inputs, returns a single redacted
 * string ready for an Android share sheet, a desktop file save, or a
 * paste into a support email.
 *
 * The bundle is plain text (Markdown-style headers) — easier for a
 * support reader than JSON, and easier for the user to skim and
 * decide whether to send. Every text section runs through
 * [DiagnosticsRedactor] before it leaves.
 *
 * Bundle layout (sections are stable so support tooling can grep):
 *   # Torve diagnostics
 *   ## App
 *   ## Device
 *   ## Account
 *   ## Provider status
 *   ## Transfer (credential transfer / automatic transfer)
 *   ## Last failure
 *   ## What is NOT in this bundle
 */
object DiagnosticsBundleBuilder {

    data class AppInfo(
        val versionName: String,
        val versionCode: String,
        val storeFlavor: String,
        val activeEngineId: String,
    )

    data class DeviceInfo(
        val platform: String,
        val deviceModel: String,
        val osVersion: String,
        val locale: String,
    )

    data class AccountInfo(
        val signedIn: Boolean,
        val emailVerified: Boolean,
        val accessTier: String?,
        val deviceActivated: Boolean?,
    )

    data class FailureInfo(
        val category: String,
        val recordedAtEpochMs: Long?,
        val message: String?,
    )

    /**
     * Render the bundle text. Every input is optional so a caller can
     * include only what it has — missing sections render as
     * "(not available)" rather than crashing or leaking nulls.
     *
     * @param nowEpochMs the timestamp embedded in the header so two
     *                   bundles can be compared by recency.
     */
    fun build(
        app: AppInfo,
        device: DeviceInfo,
        account: AccountInfo? = null,
        providerEntries: List<ProviderHealthEntry> = emptyList(),
        transfer: TransferDiagnosticsSnapshot? = null,
        lastFailure: FailureInfo? = null,
        nowEpochMs: Long,
    ): String {
        val raw = buildString {
            appendLine("# Torve diagnostics")
            appendLine("Generated at epoch_ms=$nowEpochMs")
            appendLine()

            appendLine("## App")
            appendLine("- versionName: ${app.versionName}")
            appendLine("- versionCode: ${app.versionCode}")
            appendLine("- store: ${app.storeFlavor}")
            appendLine("- active player engine: ${app.activeEngineId}")
            appendLine()

            appendLine("## Device")
            appendLine("- platform: ${device.platform}")
            appendLine("- model: ${device.deviceModel}")
            appendLine("- OS: ${device.osVersion}")
            appendLine("- locale: ${device.locale}")
            appendLine()

            appendLine("## Account")
            if (account == null) {
                appendLine("(not available)")
            } else {
                appendLine("- signed in: ${account.signedIn}")
                appendLine("- email verified: ${account.emailVerified}")
                appendLine("- access tier: ${account.accessTier ?: "(none)"}")
                appendLine("- device activated: ${account.deviceActivated ?: "(unknown)"}")
            }
            appendLine()

            appendLine("## Provider status")
            if (providerEntries.isEmpty()) {
                appendLine("(no provider entries)")
            } else {
                providerEntries.forEach { entry ->
                    appendLine(
                        "- ${entry.label} [${statusLabel(entry.status)}] " +
                            "lastChecked=${entry.lastCheckedAt ?: "never"} " +
                            "msg=${entry.message ?: "(none)"} " +
                            "next=${entry.nextAction ?: "(none)"}",
                    )
                }
            }
            appendLine()

            appendLine("## Transfer (credential transfer / automatic transfer)")
            if (transfer == null) {
                appendLine("(not available)")
            } else {
                appendLine("- crypto engine available: ${transfer.cryptoEngineAvailable}")
                appendLine("- signed in: ${transfer.signedIn}")
                appendLine("- relay reachable: ${reachabilityLabel(transfer.relayReachable)}")
                val attempt = transfer.lastAttempt
                if (attempt == null) {
                    appendLine("- last attempt: none recorded on this device")
                } else {
                    appendLine(
                        "- last attempt: role=${attempt.role.name.lowercase()} " +
                            "outcome=${outcomeLabel(attempt.outcome)} " +
                            "errorCategory=${attempt.errorCategory?.value ?: "(none)"} " +
                            "at=${attempt.recordedAtEpochMs}",
                    )
                }
            }
            appendLine()

            appendLine("## Last failure")
            if (lastFailure == null) {
                appendLine("(no failure recorded)")
            } else {
                appendLine("- category: ${lastFailure.category}")
                appendLine("- recorded: ${lastFailure.recordedAtEpochMs ?: "(unknown)"}")
                appendLine("- message: ${lastFailure.message ?: "(none)"}")
            }
            appendLine()

            appendLine("## What is NOT in this bundle")
            appendLine("- API keys, OAuth tokens, refresh tokens")
            appendLine("- Stream URLs with credentials (Xtream user/pass paths, query-string tokens)")
            appendLine("- Email address, password, payment info")
            appendLine("- Watch history titles, library content")
            appendLine("- Local file paths beyond the user-home stem")
            appendLine()
            appendLine(
                "If something sensitive surfaces above, treat it as a redaction bug " +
                    "and report it via support.",
            )
        }
        return DiagnosticsRedactor.redact(raw)
    }

    private fun statusLabel(status: ProviderHealthStatus): String = when (status) {
        ProviderHealthStatus.GREEN -> "GREEN"
        ProviderHealthStatus.YELLOW -> "YELLOW"
        ProviderHealthStatus.RED -> "RED"
        ProviderHealthStatus.UNCONFIGURED -> "UNCONFIGURED"
        ProviderHealthStatus.UNKNOWN -> "UNKNOWN"
    }

    private fun reachabilityLabel(r: RelayReachability): String = when (r) {
        RelayReachability.UNKNOWN -> "unknown"
        RelayReachability.REACHABLE -> "reachable"
        RelayReachability.UNAVAILABLE -> "unavailable"
        RelayReachability.UNAUTHORIZED -> "unauthorized"
        RelayReachability.NETWORK_ERROR -> "network_error"
        RelayReachability.NOT_SIGNED_IN -> "not_signed_in"
        RelayReachability.NO_CRYPTO_ENGINE -> "no_crypto_engine"
    }

    private fun outcomeLabel(o: AttemptOutcome): String = when (o) {
        AttemptOutcome.REGISTERED -> "registered"
        AttemptOutcome.DELIVERED -> "delivered"
        AttemptOutcome.IMPORTED -> "imported"
        AttemptOutcome.FAILED -> "failed"
        AttemptOutcome.RELAY_UNAVAILABLE -> "relay_unavailable"
    }
}
