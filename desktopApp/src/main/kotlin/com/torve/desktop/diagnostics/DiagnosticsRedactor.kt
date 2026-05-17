package com.torve.desktop.diagnostics

/**
 * Scrubs sensitive material out of diagnostics-bound text before it ends up
 * in a support zip.
 *
 * Conservative - over-redacts rather than leaks. Any caller that produces
 * a text artifact for the diagnostics export must run the content through
 * [redact] first; binary files are skipped at the exporter level.
 *
 * Categories covered:
 *   - Local user paths (Windows / macOS / Linux home directories)
 *   - HTTP basic-auth in URLs (`https://user:pass@host`)
 *   - URL query auth params (`?api_key=...`, `?token=...`,
 *     `?username=...&password=...`)
 *   - Stremio-style addon URLs with embedded tokens
 *     (`/u/<token>/manifest.json`)
 *   - Panda-style manifest URLs (`/p/<token>/manifest.json`)
 *   - Bearer / basic Authorization headers
 *   - Common JSON / properties key=value secret entries (api_key,
 *     access_token, refresh_token, password, debrid_api_key, panda_token,
 *     management_token, manifest_token, openai/claude/gemini/perplexity/
 *     deepseek/mdblist/omdb keys, etc.)
 *
 * The output keeps the surrounding shape (key names, scheme, host) so a
 * support reader can still see "this is an addon URL with a token" - they
 * just can't see the token.
 */
object DiagnosticsRedactor {

    private const val MASK = "<redacted>"

    /** Apply every redaction rule in order. Idempotent and null-safe. */
    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var s: String = input
        for (rule in rules) {
            s = rule.regex.replace(s, rule.replacement)
        }
        return s
    }

    private data class Rule(val regex: Regex, val replacement: String)

    private val rules: List<Rule> = buildList {
        // ── Local user-home paths ────────────────────────────────────
        // Windows: C:\Users\Alice\... or C:/Users/Alice/...
        add(Rule(Regex("""(?i)([A-Z]:[\\/]Users[\\/])([^\\/\s"']+)"""), """$1<redacted>"""))
        // macOS: /Users/alice/...
        add(Rule(Regex("""(/Users/)([^/\s"']+)"""), """$1<redacted>"""))
        // Linux: /home/alice/...
        add(Rule(Regex("""(/home/)([^/\s"']+)"""), """$1<redacted>"""))

        // ── URL basic-auth: https://user:pass@host ───────────────────
        add(Rule(Regex("""(https?://)([^/\s"'@]+):([^/\s"'@]+)@"""), """$1<redacted>:<redacted>@"""))

        // ── URL query auth params (case-insensitive) ─────────────────
        // Single-token params: api_key, apikey, apiKey, key, token,
        // access_token, refresh_token, password, pwd, secret.
        val queryAuthKeys = listOf(
            "api_key", "apikey", "apiKey",
            "access_token", "accessToken",
            "refresh_token", "refreshToken",
            "auth_token", "authToken",
            "session_token", "sessionToken",
            "integrity_token", "integrityToken",
            "play_integrity_token", "playIntegrityToken",
            "provider_token", "providerToken",
            "token",
            "key",
            "password", "pwd", "pass",
            "username",
            "user",
            "secret",
            "client_secret", "clientSecret",
        )
        queryAuthKeys.forEach { name ->
            add(Rule(
                Regex("""([?&])${Regex.escape(name)}=([^&\s"']+)""", RegexOption.IGNORE_CASE),
                """$1$name=$MASK""",
            ))
        }

        // ── Stremio-style addon manifest URLs with embedded tokens ──
        // /u/<token>/manifest.json - the user-bound addon shape.
        add(Rule(Regex("""/u/([^/\s"']+)/manifest\.json"""), """/u/$MASK/manifest.json"""))
        // /p/<token>/manifest.json - Panda-style manifest URLs.
        add(Rule(Regex("""/p/([^/\s"']+)/manifest\.json"""), """/p/$MASK/manifest.json"""))
        // Generic /addons/<token>/configure or /catalog/<token>/...
        add(Rule(
            Regex("""/(addons?|catalog|stream|meta)/([A-Za-z0-9._\-+/]{24,})/"""),
            """/$1/$MASK/""",
        ))

        // ── HTTP Authorization headers ───────────────────────────────
        add(Rule(Regex("""(?i)(Authorization\s*:\s*Bearer\s+)([^\s"']+)"""), """$1$MASK"""))
        add(Rule(Regex("""(?i)(Authorization\s*:\s*Basic\s+)([^\s"']+)"""), """$1$MASK"""))

        // ── JSON / properties key=value or "key": "value" pairs ─────
        // Covers any sensitive key name we know about across both
        // JSON style ("key": "value") and properties style (key=value).
        val secretFieldNames = listOf(
            // Generic
            "api_key", "apiKey", "apikey",
            "access_token", "accessToken",
            "refresh_token", "refreshToken",
            "auth_token", "authToken",
            "session_token", "sessionToken",
            "integrity_token", "integrityToken",
            "play_integrity_token", "playIntegrityToken",
            "provider_token", "providerToken",
            "client_secret", "clientSecret",
            "password", "pwd", "passwd",
            "secret",
            "token",
            // Torve / Panda specific
            "debrid_api_key", "debridApiKey",
            "panda_token", "pandaToken",
            "management_token", "managementToken",
            "manifest_token", "manifestToken",
            "torbox_api_key", "torboxApiKey",
            // AI providers
            "openai_api_key", "openaiApiKey", "openai_key", "openaiKey",
            "anthropic_api_key", "anthropicApiKey", "claude_api_key", "claudeApiKey",
            "gemini_api_key", "geminiApiKey",
            "perplexity_api_key", "perplexityApiKey",
            "deepseek_api_key", "deepseekApiKey",
            // Metadata providers
            "omdb_api_key", "omdbApiKey",
            "mdblist_api_key", "mdblistApiKey",
            "tmdb_api_key", "tmdbApiKey",
            // IPTV / Newznab
            "newznab_api_key", "newznabApiKey",
            "iptv_password", "iptvPassword",
            "iptv_username", "iptvUsername",
            "source_key", "sourceKey",
            "playback_url", "playbackUrl",
            "stream_url", "streamUrl",
            "debrid_url", "debridUrl",
        )
        secretFieldNames.forEach { name ->
            // JSON form: "key" : "value"
            add(Rule(
                Regex(""""${Regex.escape(name)}"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE),
                """"$name": "$MASK"""",
            ))
            // Properties / env form: key=value (until newline / whitespace)
            add(Rule(
                Regex("""(?im)^(\s*${Regex.escape(name)}\s*=)\s*(.+)$"""),
                """$1$MASK""",
            ))
        }
    }
}
