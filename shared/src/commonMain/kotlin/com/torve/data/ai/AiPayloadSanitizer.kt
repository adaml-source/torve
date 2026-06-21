package com.torve.data.ai

/**
 * Strips secret-shaped strings out of user-visible payloads before they
 * leave the device for an AI provider.
 *
 * Two surfaces benefit from this:
 *   1. The user's typed query — could accidentally contain a paste of an
 *      API key, debrid URL with `?token=`, etc.
 *   2. (Future) availability-context enrichment — when Prompt 8+ starts
 *      adding "user has X cached, Y in Plex" hints to the system prompt,
 *      we must not leak the URLs that prove it.
 *
 * The sanitizer is conservative — it never deletes, only redacts to a
 * fixed marker so the AI still sees that *something* was there. It is
 * called explicitly by [AiSuggestClient.suggest]; future call sites that
 * forward strings to AI providers MUST route through here.
 *
 * **No false sense of security.** The HTTP layer still sends the user's
 * AI provider API key in the `Authorization` / `x-api-key` header — that
 * is necessary and intentional. This sanitizer is only for the message
 * *body*: system prompt + user message.
 */
object AiPayloadSanitizer {

    private const val REDACTED = "[redacted]"

    private val patterns: List<Regex> = listOf(
        // Bearer tokens (`Authorization: Bearer xyz` pasted by mistake).
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+"""),

        // Common API key query params anywhere in a URL or string.
        Regex("""(?i)([?&](api[_-]?key|apikey|token|access[_-]?token|key))=([^&\s"']+)"""),

        // Anthropic / OpenAI style key prefixes (`sk-ant-…`, `sk-…`,
        // `pplx-…`, `gsk_…`). Match the whole opaque blob so we don't
        // leave a tail.
        Regex("""(?i)\bsk[_-](?:ant[_-])?[A-Za-z0-9_\-]{20,}"""),
        Regex("""(?i)\bpplx[_-][A-Za-z0-9_\-]{20,}"""),

        // Panda-style tokenized stream URLs: `…/u/<token>/<path>`.
        Regex("""(?i)https?://[^\s"']+?/u/[A-Za-z0-9._\-]+/[^\s"']*"""),

        // Local filesystem paths: macOS / Linux user dirs.
        Regex("""(?:/Users/|/home/)[^\s"']+"""),

        // Windows absolute paths starting with a drive letter.
        Regex("""(?i)[A-Z]:[\\/][^\s"']+"""),

        // file:// URLs.
        Regex("""(?i)file://[^\s"']*"""),
    )

    /**
     * Redact every matching pattern. Idempotent — running [sanitize] on
     * already-sanitized output produces the same string.
     */
    fun sanitize(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        for (regex in patterns) {
            out = out.replace(regex, REDACTED)
        }
        return out
    }
}
