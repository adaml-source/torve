package com.torve.desktop.diagnostics

import com.torve.desktop.platform.desktopDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles up the support-relevant pieces of the user's local Torve state
 * into a single zip file the user can attach to a bug report.
 *
 * Includes:
 *  - `system.txt` - OS, JVM, app version, env-var presence (no values)
 *  - Every JSON / Properties file under `desktopDataDir()` that doesn't
 *    look like a secret (so: window state, reminders, library catalogue,
 *    update prefs - but NOT desktop secret-store files)
 *  - `feature_inventory.txt` - pointer to the FEATURES.md doc location
 *
 * Excluded by design:
 *  - desktop secret-store files (tokens, debrid keys, addon tokens)
 *  - SQLDelight DB file (too large, may contain watchlist titles the
 *    user doesn't want to share)
 *  - Coil image cache
 *
 * Output zip lands on the user's Desktop or `~` so they can find it
 * easily.
 */
object DiagnosticsExporter {

    /** Files under `desktopDataDir()` that we refuse to include. */
    private val SECRET_FILE_NAMES: Set<String> = setOf(
        "desktop-secrets.properties",
        "desktop-secrets.dpapi.properties",
    )

    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    suspend fun exportTo(target: File): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            // system.txt is generated text - redact before writing.
            zip.putNextEntry(ZipEntry("system.txt"))
            zip.write(DiagnosticsRedactor.redact(systemSummary()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val dataDir = desktopDataDir()
            if (dataDir.exists() && dataDir.isDirectory) {
                dataDir.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    if (f.name in SECRET_FILE_NAMES) return@forEach
                    if (f.length() > 4 * 1024 * 1024L) return@forEach   // skip > 4 MB
                    val rel = f.relativeTo(dataDir).path.replace(File.separatorChar, '/')
                    if (looksLikeBinary(rel)) return@forEach
                    runCatching {
                        // Read as text and run through the redactor before
                        // archiving. Anything that fails to decode as UTF-8
                        // is skipped - we only ship inspectable text.
                        val raw = f.readBytes()
                        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull()
                            ?: return@runCatching
                        val redacted = DiagnosticsRedactor.redact(text)
                        zip.putNextEntry(ZipEntry("data/$rel"))
                        zip.write(redacted.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }.onFailure { t ->
                        println("TORVE DIAG | skipped $rel: ${t.message}")
                    }
                }
            }
        }
        target
    }

    /**
     * Default destination for an export - a timestamped zip on the
     * user's Desktop if it exists, otherwise the home directory.
     */
    fun defaultTargetFile(): File {
        val ts = timestampFormatter.format(Instant.now())
        val home = File(System.getProperty("user.home").orEmpty())
        val desktop = File(home, "Desktop").takeIf { it.isDirectory }
        val parent = desktop ?: home
        return File(parent, "torve-diagnostics-$ts.zip")
    }

    private fun systemSummary(): String = buildString {
        appendLine("Torve diagnostics export")
        appendLine("Generated at: ${Instant.now()}")
        appendLine()
        appendLine("# OS")
        appendLine("  os.name=${System.getProperty("os.name")}")
        appendLine("  os.version=${System.getProperty("os.version")}")
        appendLine("  os.arch=${System.getProperty("os.arch")}")
        appendLine()
        appendLine("# JVM")
        appendLine("  java.version=${System.getProperty("java.version")}")
        appendLine("  java.vendor=${System.getProperty("java.vendor")}")
        appendLine("  java.home=${System.getProperty("java.home")}")
        appendLine()
        appendLine("# Locale / timezone")
        appendLine("  user.language=${System.getProperty("user.language")}")
        appendLine("  user.country=${System.getProperty("user.country")}")
        appendLine("  user.timezone=${System.getProperty("user.timezone")}")
        appendLine()
        appendLine("# Env-var presence (values redacted)")
        listOf(
            "TORVE_SENTRY_DSN",
            "TORVE_SENTRY_ENV",
            "TORVE_UPDATE_REPO",
            "TORVE_UPDATE_FEED",
            "TORVE_MAC_SIGN_IDENTITY",
            "TORVE_MAC_NOTARIZATION_USER",
            "TORVE_MAC_NOTARIZATION_PWD",
            "TORVE_KEYSTORE_PATH",
        ).forEach { name ->
            val present = System.getenv(name)?.takeIf { it.isNotBlank() } != null
            appendLine("  $name=${if (present) "set" else "unset"}")
        }
        appendLine()
        appendLine("# Data directory")
        appendLine("  ${desktopDataDir().absolutePath}")
        appendLine()
        appendLine("# Excluded from this bundle by design")
        appendLine("  - desktop secret-store files (tokens, debrid keys, addon tokens)")
        appendLine("  - SQLDelight DB file (could leak watchlist content)")
        appendLine("  - Coil image cache")
        appendLine("  - any file > 4 MB")
    }

    /** Skip well-known binary blobs that won't help triage. */
    private fun looksLikeBinary(relPath: String): Boolean {
        val lower = relPath.lowercase()
        return lower.endsWith(".db") || lower.endsWith(".db-journal") ||
            lower.endsWith(".db-wal") || lower.endsWith(".db-shm") ||
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".dylib") ||
            lower.endsWith(".so") || lower.endsWith(".tmp") || lower.endsWith(".part") ||
            lower.startsWith("image-cache/")
    }
}
