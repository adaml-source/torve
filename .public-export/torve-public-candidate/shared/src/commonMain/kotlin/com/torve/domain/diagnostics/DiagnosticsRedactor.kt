package com.torve.domain.diagnostics

/**
 * Multiplatform mirror of the desktop's `DiagnosticsRedactor`. Scrubs
 * tokens, paths, and authenticated URLs out of any text bound for the
 * support / diagnostics bundle. Conservative — over-redacts rather
 * than leaks.
 *
 * Categories covered:
 *  - Local user-home paths (Windows, macOS, Linux)
 *  - HTTP basic-auth in URLs (`https://user:pass@host`)
 *  - URL query auth params (`?api_key=`, `?token=`, `?username=`,
 *    `?password=`, etc.)
 *  - Stremio-style addon manifest URLs (`/u/<token>/manifest.json`,
 *    `/p/<token>/manifest.json`)
 *  - Authorization headers (Bearer + Basic)
 *  - JSON / properties key=value pairs whose key is a known secret
 *    name (api_key, access_token, password, panda_token, etc.)
 *
 * The output keeps the shape (key names, scheme, host) so a support
 * reader can still see "this is an addon URL with a token" — they
 * just can't read the token. Idempotent and null-safe.
 */
object DiagnosticsRedactor {

    private const val MASK = "<redacted>"

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
        // Local user-home paths
        add(Rule(Regex("""(?i)([A-Z]:[\\/]Users[\\/])([^\\/\s"']+)"""), """$1<redacted>"""))
        add(Rule(Regex("""(/Users/)([^/\s"']+)"""), """$1<redacted>"""))
        add(Rule(Regex("""(/home/)([^/\s"']+)"""), """$1<redacted>"""))

        // URL basic-auth
        add(Rule(Regex("""(https?://)([^/\s"'@]+):([^/\s"'@]+)@"""), """$1<redacted>:<redacted>@"""))

        // URL query auth params
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
            add(
                Rule(
                    Regex("""([?&])${Regex.escape(name)}=([^&\s"']+)""", RegexOption.IGNORE_CASE),
                    """$1$name=$MASK""",
                ),
            )
        }

        // Stremio / Panda manifest URLs
        add(Rule(Regex("""/u/([^/\s"']+)/manifest\.json"""), """/u/$MASK/manifest.json"""))
        add(Rule(Regex("""/p/([^/\s"']+)/manifest\.json"""), """/p/$MASK/manifest.json"""))
        add(
            Rule(
                Regex("""/(addons?|catalog|stream|meta)/([A-Za-z0-9._\-+/]{24,})/"""),
                """/$1/$MASK/""",
            ),
        )

        // Authorization headers
        add(Rule(Regex("""(?i)(Authorization\s*:\s*Bearer\s+)([^\s"']+)"""), """$1$MASK"""))
        add(Rule(Regex("""(?i)(Authorization\s*:\s*Basic\s+)([^\s"']+)"""), """$1$MASK"""))

        // JSON / properties key=value or "key": "value" pairs
        val secretFieldNames = listOf(
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
            "debrid_api_key", "debridApiKey",
            "panda_token", "pandaToken",
            "management_token", "managementToken",
            "manifest_token", "manifestToken",
            "torbox_api_key", "torboxApiKey",
            "openai_api_key", "openaiApiKey", "openai_key", "openaiKey",
            "anthropic_api_key", "anthropicApiKey", "claude_api_key", "claudeApiKey",
            "gemini_api_key", "geminiApiKey",
            "perplexity_api_key", "perplexityApiKey",
            "deepseek_api_key", "deepseekApiKey",
            "omdb_api_key", "omdbApiKey",
            "mdblist_api_key", "mdblistApiKey",
            "tmdb_api_key", "tmdbApiKey",
            "newznab_api_key", "newznabApiKey",
            "iptv_password", "iptvPassword",
            "iptv_username", "iptvUsername",
            "source_key", "sourceKey",
            "playback_url", "playbackUrl",
            "stream_url", "streamUrl",
            "debrid_url", "debridUrl",
        )
        secretFieldNames.forEach { name ->
            add(
                Rule(
                    Regex(""""${Regex.escape(name)}"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE),
                    """"$name": "$MASK"""",
                ),
            )
            add(
                Rule(
                    Regex("""(?im)^(\s*${Regex.escape(name)}\s*=)\s*(.+)$"""),
                    """$1$MASK""",
                ),
            )
        }
    }
}
