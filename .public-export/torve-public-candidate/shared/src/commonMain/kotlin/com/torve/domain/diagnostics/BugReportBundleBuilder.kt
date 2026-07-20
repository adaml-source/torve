package com.torve.domain.diagnostics

/**
 * Builds the user-facing bug report body sent to support.
 *
 * User-entered text and pasted logs are always redacted together with
 * the generated diagnostics bundle. This keeps the support flow useful
 * without trusting users to manually remove tokens, playlist URLs, or
 * provider credentials from copied logs.
 */
object BugReportBundleBuilder {
    fun build(
        issueType: String,
        userDescription: String,
        pastedLogs: String,
        diagnosticsBundle: String?,
        nowEpochMs: Long,
    ): String {
        val raw = buildString {
            appendLine("# Torve bug report")
            appendLine("Generated at epoch_ms=$nowEpochMs")
            appendLine()
            appendLine("## Issue type")
            appendLine(issueType.ifBlank { "Unspecified" })
            appendLine()
            appendLine("## What happened")
            appendLine(userDescription.ifBlank { "(not provided)" })
            appendLine()
            appendLine("## Pasted logs")
            appendLine(pastedLogs.ifBlank { "(none pasted)" })
            appendLine()
            appendLine("## Diagnostics")
            appendLine(diagnosticsBundle?.takeIf { it.isNotBlank() } ?: "(not attached)")
            appendLine()
            appendLine("## Redaction")
            appendLine("This report was automatically redacted before sending.")
        }
        return DiagnosticsRedactor.redact(raw)
    }
}
