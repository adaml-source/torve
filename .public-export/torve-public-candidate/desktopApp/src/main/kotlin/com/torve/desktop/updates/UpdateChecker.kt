package com.torve.desktop.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight update checker for the desktop app.
 *
 * Queries a configurable GitHub Releases endpoint, compares the latest
 * release tag against the running version, and exposes the result as a
 * [StateFlow] so the shell can render a discreet banner when an update
 * is available.
 *
 * Configuration is environment-driven so we don't hard-code a repo into
 * the binary:
 *  - `TORVE_UPDATE_REPO` - `owner/name` (e.g. `anthropics/torve`).
 *  - `TORVE_UPDATE_FEED` - full URL override; takes precedence over the
 *    repo env var (lets you point at a custom appcast/manifest later).
 *
 * When neither is set the checker is a no-op so dev builds and sideloads
 * don't ping GitHub. Real distribution channels set the env at install
 * time.
 *
 * This is intentionally **just** a notifier - it does not download or
 * apply updates. Native installers + Sparkle/WinSparkle are the next
 * Phase 2 step; this checker is the user-visible part shipped first.
 */
class UpdateChecker(
    val currentVersion: String,
    private val repo: String? = resolveDefaultRepo(),
    private val feedOverride: String? = resolveDefaultFeed(),
) {

    @Serializable
    data class UpdateInfo(
        val tag: String,
        val name: String,
        val htmlUrl: String,
        val publishedAt: String?,
        val body: String?,
        /**
         * Direct installer URL extracted from the appcast `<enclosure>`
         * tag, when present. Null for GitHub Releases JSON (no
         * deterministic per-asset selection without filename rules).
         * The banner uses this to offer "Download & install" instead
         * of just "View release".
         */
        val installerUrl: String? = null,
        /**
         * Optional SHA-256 hex from the appcast. When present, the
         * handoff verifies the downloaded installer matches before
         * launching it. Null skips verification - the handoff still
         * launches but logs that the artifact wasn't checksum-pinned.
         */
        val installerSha256: String? = null,
    )

    sealed class Result {
        /** No update available, or the checker is disabled. */
        data object UpToDate : Result()
        /** An update was found and is newer than [UpdateChecker.currentVersion]. */
        data class Available(val info: UpdateInfo) : Result()
        /** The check itself failed - network, rate limit, parse error. */
        data class Failed(val reason: String) : Result()
    }

    private val _state = MutableStateFlow<Result>(Result.UpToDate)
    val state: StateFlow<Result> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    val isEnabled: Boolean get() = feedOverride?.isNotBlank() == true || repo?.isNotBlank() == true

    /**
     * Hit the configured update endpoint, parse the latest release, and
     * publish [Result.Available] when it's newer than the running
     * version. Designed to be called once at app startup and optionally
     * again on a manual "Check for updates" click - never on a polling
     * cadence.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        com.torve.desktop.diagnostics.SentryBootstrap.breadcrumb(
            category = "update",
            message = "check() invoked",
            data = mapOf(
                "feedConfigured" to (feedOverride?.isNotBlank() == true).toString(),
                "repoConfigured" to (repo?.isNotBlank() == true).toString(),
                "currentVersion" to currentVersion,
            ),
        )
        val url = feedOverride?.takeIf { it.isNotBlank() }
            ?: repo?.takeIf { it.isNotBlank() }?.let { "https://api.github.com/repos/$it/releases/latest" }
            ?: run {
                _state.value = Result.UpToDate
                return@withContext Result.UpToDate
            }

        val result = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json, application/xml, application/rss+xml")
                setRequestProperty("User-Agent", "Torve-UpdateChecker/$currentVersion")
            }
            if (conn.responseCode !in 200..299) {
                return@runCatching Result.Failed("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }

            // Format detection: appcast XML (Sparkle / WinSparkle convention)
            // when the URL ends in .xml or contains "appcast", or the body
            // starts with `<?xml` / `<rss`. Otherwise GitHub Releases JSON.
            val looksXml = url.endsWith(".xml", ignoreCase = true) ||
                url.contains("appcast", ignoreCase = true) ||
                body.trimStart().startsWith("<?xml") ||
                body.trimStart().startsWith("<rss")
            val info: UpdateInfo? = if (looksXml) {
                parseAppcast(body)
            } else {
                val release = json.decodeFromString(GithubRelease.serializer(), body)
                UpdateInfo(
                    tag = release.tagName,
                    name = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
                    htmlUrl = release.htmlUrl,
                    publishedAt = release.publishedAt,
                    body = release.body,
                )
            }
            if (info == null) return@runCatching Result.Failed("Empty or unrecognised feed at $url")
            if (isStrictlyNewer(info.tag, currentVersion)) {
                Result.Available(info)
            } else {
                Result.UpToDate
            }
        }.getOrElse { t ->
            println("TORVE UPDATE | check failed: ${t.message}")
            Result.Failed(t.message ?: t::class.simpleName ?: "unknown")
        }
        _state.value = result
        result
    }

    /**
     * Minimal appcast/RSS parser. Picks the first `<item>`'s
     * `sparkle:version` (or version-derived from the enclosure URL),
     * description body, and `enclosure[url]` for the download link.
     *
     * Pure regex - no XML library - because appcast feeds are tiny and
     * the Sparkle conventions use a fixed structure. If the feed is
     * unparseable we return null and the caller treats it as a failure.
     */
    internal fun parseAppcast(body: String): UpdateInfo? {
        val itemBlock = Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).find(body)?.value
            ?: return null
        val title = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(itemBlock)
            ?.groupValues?.getOrNull(1)?.trim()?.removeSurrounding("<![CDATA[", "]]>")?.trim()
        val pubDate = Regex("<pubDate>([\\s\\S]*?)</pubDate>", RegexOption.IGNORE_CASE).find(itemBlock)
            ?.groupValues?.getOrNull(1)?.trim()
        val description = Regex("<description>([\\s\\S]*?)</description>", RegexOption.IGNORE_CASE).find(itemBlock)
            ?.groupValues?.getOrNull(1)?.trim()?.removeSurrounding("<![CDATA[", "]]>")?.trim()
        val link = Regex("<link>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE).find(itemBlock)
            ?.groupValues?.getOrNull(1)?.trim()
        val enclosure = Regex("<enclosure\\b[^>]*>", RegexOption.IGNORE_CASE).find(itemBlock)?.value.orEmpty()
        // Extract all `name="value"` attribute pairs from the enclosure
        // tag into a map. Attribute lookup by name is then a simple map
        // hit, so we don't depend on attribute *order* or on regex word
        // boundaries (an earlier `\burl=...` regex silently failed to
        // match in some attribute orderings — caught by B4 smoke
        // 2026-05-03 when the Download & install button never appeared).
        val attrs = Regex("""([\w:]+)\s*=\s*"([^"]*)"""")
            .findAll(enclosure)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val sparkleVersion = attrs["sparkle:version"]
        val enclosureUrl = attrs["url"]
        // Sparkle's optional `sparkle:installerSha256` attr (or `sha256`,
        // or `sparkle:edSignature` - last is technically Ed25519 not
        // SHA-256, so we ignore it for hash verification but accept it
        // as "feed signed" indicator). 64 hex chars only.
        val sha256 = (attrs["sparkle:installersha256"] ?: attrs["sha256"])
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()
        val tag = sparkleVersion ?: title?.substringAfterLast(' ')?.trim() ?: return null
        return UpdateInfo(
            tag = tag,
            name = title?.takeIf { it.isNotBlank() } ?: tag,
            htmlUrl = link?.takeIf { it.isNotBlank() } ?: enclosureUrl ?: "",
            publishedAt = pubDate,
            body = description,
            installerUrl = enclosureUrl?.takeIf { it.isNotBlank() },
            installerSha256 = sha256,
        )
    }

    /**
     * Compare two version strings using a permissive semver-like rule:
     * leading 'v' stripped, dot-separated numeric parts compared
     * left-to-right. Non-numeric parts force a string compare.
     *
     * Returns true when [candidate] is strictly greater than [current].
     */
    internal fun isStrictlyNewer(candidate: String, current: String): Boolean {
        val a = candidate.trim().removePrefix("v").removePrefix("V")
        val b = current.trim().removePrefix("v").removePrefix("V")
        val ap = a.split('.', '-', '+')
        val bp = b.split('.', '-', '+')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val ai = ap.getOrNull(i) ?: "0"
            val bi = bp.getOrNull(i) ?: "0"
            val an = ai.toIntOrNull()
            val bn = bi.toIntOrNull()
            if (an != null && bn != null) {
                if (an != bn) return an > bn
            } else {
                val cmp = ai.compareTo(bi)
                if (cmp != 0) return cmp > 0
            }
        }
        return false
    }

    /** GitHub's release JSON shape - only the fields we actually use. */
    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("name") val name: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("published_at") val publishedAt: String? = null,
        @SerialName("body") val body: String? = null,
    )

    companion object {
        const val REPO_ENV: String = "TORVE_UPDATE_REPO"
        const val FEED_ENV: String = "TORVE_UPDATE_FEED"
        const val FEED_PROPERTY: String = "torve.update.feed"
        const val REPO_PROPERTY: String = "torve.update.repo"

        /**
         * Resolves the feed URL the running app should poll. Precedence:
         *
         *   1. `TORVE_UPDATE_FEED` environment variable — for dev / QA
         *      who need to point a packaged build at a Sandbox tunnel
         *      or staging feed without rebuilding.
         *   2. `-Dtorve.update.feed=…` system property — baked into the
         *      packaged build by the gradle `application { jvmArgs }`
         *      block at packaging time. This is what ships to end users
         *      so auto-update works out of the box without any env-var
         *      ceremony.
         *   3. null — checker becomes a no-op (matches the dev-build
         *      contract: unconfigured Torve doesn't ping any URL).
         */
        fun resolveDefaultFeed(): String? =
            System.getenv(FEED_ENV)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(FEED_PROPERTY)?.takeIf { it.isNotBlank() }

        /** Same precedence as [resolveDefaultFeed] for the GitHub-repo fallback. */
        fun resolveDefaultRepo(): String? =
            System.getenv(REPO_ENV)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(REPO_PROPERTY)?.takeIf { it.isNotBlank() }
    }
}
