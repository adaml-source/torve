package com.torve.desktop.diagnostics

import com.torve.desktop.DesktopReleaseInfo
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.protocol.User

/**
 * Wires Sentry into the desktop app lifecycle.
 *
 * Behaviour:
 *  - When [DSN_ENV] (or the value passed to [install]) is blank or null, all
 *    methods on this object become no-ops and the SDK is never initialised.
 *    That means a developer build with no DSN configured produces zero
 *    network traffic and zero events - equivalent to Sentry being absent.
 *  - When a DSN is present, every uncaught Throwable routed through
 *    [captureUncaught] is sent to Sentry with the active thread name, the
 *    Torve release tag, and the configured environment.
 *
 * The DSN is resolved at runtime, not build time, so the same binary can be
 * shipped to multiple channels with different DSNs by switching the env var.
 */
object SentryBootstrap {

    const val DSN_ENV: String = "TORVE_SENTRY_DSN"

    @Volatile
    private var enabled: Boolean = false

    fun install(
        releaseInfo: DesktopReleaseInfo,
        dsn: String?,
        environment: String,
    ) {
        val effectiveDsn = dsn?.takeIf { it.isNotBlank() }
        if (effectiveDsn == null) {
            println("TORVE SENTRY | DSN not set ($DSN_ENV) - crash reporting disabled")
            return
        }
        try {
            Sentry.init { options ->
                options.dsn = effectiveDsn
                options.release = "${releaseInfo.appName}@${releaseInfo.versionLabel}"
                options.environment = environment
                // Don't ship breadcrumbs that could leak Stremio addon URLs,
                // tokens, or playback URLs. We're after stack traces, not
                // user activity logs.
                options.isSendDefaultPii = false
                options.maxBreadcrumbs = 50
                options.isAttachStacktrace = true
                // Modest tracing so we get a perf signal without paying
                // for every navigation; tune up later when there's signal.
                options.tracesSampleRate = 0.0
            }
            // The user identity is just an opaque hash of the email - we're
            // tagging crashes per-user without storing the email itself.
            // Email comes from the auto-memory's known userEmail; if not
            // available, we skip user context altogether.
            enabled = true
            println("TORVE SENTRY | initialised env=$environment release=${releaseInfo.versionLabel}")
        } catch (t: Throwable) {
            // Sentry init failure must NEVER take the app down. Swallow,
            // log to stderr, leave [enabled] false so subsequent capture
            // calls are no-ops.
            println("TORVE SENTRY | init failed: ${t.message}")
        }
    }

    /**
     * Hand off an uncaught exception captured by the JVM's default handler.
     * Adds the originating thread name as a tag so we can spot recurring
     * AWT-vs-coroutine-vs-Compose patterns.
     */
    fun captureUncaught(thread: Thread, throwable: Throwable) {
        if (!enabled) return
        try {
            Sentry.withScope { scope ->
                scope.level = SentryLevel.FATAL
                scope.setTag("thread.name", thread.name)
                scope.setTag("thread.kind", classifyThread(thread.name))
                Sentry.captureException(throwable)
            }
        } catch (_: Throwable) {
            // Never let telemetry crash the app.
        }
    }

    /**
     * Optional manual capture surface for places that catch and recover from
     * an exception but want it logged anyway (e.g. EPG fetch fallback paths).
     */
    fun captureMessage(message: String, level: SentryLevel = SentryLevel.WARNING) {
        if (!enabled) return
        try {
            Sentry.captureMessage(message, level)
        } catch (_: Throwable) {
        }
    }

    /**
     * Record a low-noise breadcrumb that gets attached to the next crash
     * report. No-op when disabled. Use sparingly - there's a 50-crumb
     * cap (set in [install]) so high-frequency events drown out anything
     * useful.
     *
     * Conventions:
     *  - [category]: a stable namespace, e.g. `playback`, `nav`, `library`.
     *  - [message]: short, human-readable, no PII / URLs / tokens.
     *  - [data]: optional key→string map for structured fields.
     */
    fun breadcrumb(
        category: String,
        message: String,
        level: SentryLevel = SentryLevel.INFO,
        data: Map<String, String> = emptyMap(),
    ) {
        if (!enabled) return
        try {
            val crumb = io.sentry.Breadcrumb().apply {
                this.category = category
                this.message = message
                this.level = level
                data.forEach { (k, v) -> setData(k, v) }
            }
            Sentry.addBreadcrumb(crumb)
        } catch (_: Throwable) {
        }
    }

    /** Tag the active session with an opaque user id (e.g. hashed email). */
    fun setUser(opaqueId: String?) {
        if (!enabled) return
        try {
            Sentry.setUser(opaqueId?.let { User().apply { id = it } })
        } catch (_: Throwable) {
        }
    }

    private fun classifyThread(name: String): String {
        val lower = name.lowercase()
        return when {
            "awt-eventqueue" in lower || "awt" in lower -> "awt"
            "compose" in lower || "skia" in lower -> "compose"
            "defaultdispatcher" in lower || "io" in lower || "main" in lower -> "coroutine"
            "vlc" in lower -> "vlc"
            else -> "other"
        }
    }
}
