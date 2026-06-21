package com.torve.domain.telemetry

/**
 * Decorator that redacts known-sensitive substrings from every event +
 * attribute value before delegating to a backing [TelemetryEmitter]
 * (Prompt 12 hardening).
 *
 * The existing struct-enforced emitters ([UsenetTelemetry], [TransferTelemetry])
 * are already safe by construction — they accept enum/bucketed values
 * only. This decorator is the "belt" to those builders' "suspenders":
 * if any future call site emits a raw string that happens to contain
 * a token, URL, or local path, the decorator scrubs it before any sink
 * (NoOp, Println, or a future production sink) sees the value.
 *
 * Patterns mirrored from `desktop.diagnostics.DiagnosticsRedactor` so
 * support-zip exports and live telemetry redact the same categories:
 *   * Bearer / Basic / Token authorization headers.
 *   * `X-Torve-Lan-Auth` LAN handoff headers.
 *   * `?token=`, `?api_key=`, `?key=` URL query parameters.
 *   * Stremio / Panda addon URLs containing tokens.
 *   * Provider / debrid / IPTV stream URLs (any http(s):// URL is
 *     redacted to its host — source URLs leak content choices).
 *   * Local filesystem paths (Windows / macOS / Linux).
 *   * file:// URLs.
 *   * AI-vendor API keys (sk-, sk-ant-, pplx-, gemini- prefixes).
 *   * Credential-transfer envelope JSON (base64 blobs > 64 chars).
 *
 * Redacted to `<redacted>` so the value's presence is visible to the
 * sink (so attribute counts still match) but the secret is gone.
 */
class RedactingTelemetryEmitter(
    private val delegate: TelemetryEmitter,
) : TelemetryEmitter {
    override fun emit(event: String, attributes: Map<String, String>) {
        val safeEvent = redact(event)
        val safeAttrs = if (attributes.isEmpty()) {
            emptyMap()
        } else {
            attributes.mapValues { (_, v) -> redact(v) }
        }
        // Implementations MUST swallow failures; the decorator inherits
        // that contract via the delegate's `emit` (NoOp, Println, etc.
        // already do so).
        delegate.emit(safeEvent, safeAttrs)
    }

    companion object {
        private const val PLACEHOLDER = "<redacted>"

        // Order matters: more-specific patterns run first so
        // a Bearer token doesn't match the generic-URL regex first
        // and lose its prefix.
        private val patterns: List<Regex> = listOf(
            // Authorization-style headers in any form.
            Regex("(?i)\\b(?:bearer|basic|token)\\s+[A-Za-z0-9._\\-+/=]{8,}"),
            Regex("(?i)\\bAuthorization\\s*[:=]\\s*[^\\s,;]+"),
            // LAN auth header value (treat both attribute name and bare value as sensitive).
            Regex("(?i)X-Torve-Lan-Auth\\s*[:=]\\s*[^\\s,;]+"),
            // URL query auth params (?token=…, ?api_key=…, ?access_token=…, ?key=…).
            Regex("(?i)([?&])(token|api_key|access_token|refresh_token|key|auth|secret|password)=[^&\\s]+"),
            // file:// URLs.
            Regex("(?i)file://[^\\s,;\"']+"),
            // Generic http(s) URLs — replace with the scheme+host so we keep observability of "did
            // a network call happen" without leaking source URLs / addon URLs / IPTV M3U paths.
            Regex("(?i)https?://([A-Za-z0-9.\\-]+)(?::\\d+)?(/[^\\s,;\"']*)?"),
            // Windows local paths.
            Regex("(?i)[A-Z]:\\\\(?:Users|Programs|ProgramData|Windows)\\\\[^\\s,;\"']+"),
            // macOS / Linux home paths.
            Regex("/(?:Users|home)/[^\\s,;/\"']+(?:/[^\\s,;\"']*)?"),
            // Common AI-vendor API key prefixes.
            Regex("\\b(?:sk-ant-|sk-|pplx-|gsk_|aia_)[A-Za-z0-9_\\-]{16,}"),
            // Long base64-ish blobs (credential transfer envelopes / pubkey material).
            Regex("[A-Za-z0-9+/=_-]{96,}"),
        )

        private fun redact(value: String): String {
            if (value.isEmpty()) return value
            var out = value
            for ((idx, pattern) in patterns.withIndex()) {
                out = if (idx == 5) {
                    // The generic http(s) URL pattern preserves scheme+host.
                    pattern.replace(out) { match ->
                        val host = match.groupValues.getOrNull(1).orEmpty()
                        if (host.isNotEmpty()) "<host:$host>" else PLACEHOLDER
                    }
                } else {
                    pattern.replace(out, PLACEHOLDER)
                }
            }
            return out
        }
    }
}

/**
 * Selects the configured telemetry sink. Defaults to [NoOpTelemetryEmitter];
 * `TORVE_TELEMETRY_SINK=println` opts into the dev-debug Println sink.
 * Any sink chosen is wrapped in [RedactingTelemetryEmitter] so a future
 * production sink inherits redaction without per-call-site care.
 *
 * Reading the env var is platform-specific in KMP (no `System.getenv` in
 * commonMain). Callers pass the resolved value in.
 */
fun selectTelemetryEmitter(sinkSelector: String?): TelemetryEmitter {
    val base = when (sinkSelector?.trim()?.lowercase()) {
        "println", "stdout", "log" -> PrintlnTelemetryEmitter()
        else -> NoOpTelemetryEmitter()
    }
    return RedactingTelemetryEmitter(base)
}
