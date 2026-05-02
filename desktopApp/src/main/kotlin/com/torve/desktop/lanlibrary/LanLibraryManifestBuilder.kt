package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.LanMediaType
import com.torve.domain.lanlibrary.MANIFEST_VERSION
import java.io.File
import java.security.MessageDigest

/**
 * Phase 3 Slice C - manifest assembly.
 *
 * Walks the desktop's known local-media set, filters every candidate
 * through [DownloadFolderAllowlist], and produces a [LanLibraryManifest]
 * containing zero raw paths. Each surviving entry is registered with
 * the supplied [LanMediaTokenTable] so tokens can later resolve back to
 * the real path.
 *
 * The opaque id is `sha256(canonical-path || sizeBytes)` truncated to
 * 32 hex chars - stable across publisher restarts so peers can dedupe.
 *
 * The builder is platform-light: it takes a sequence of (file, label,
 * metadata) inputs, the allowlist, and the token table; it doesn't talk
 * to `DesktopDownloadManager` directly so wiring stays at the call
 * site.
 */
class LanLibraryManifestBuilder(
    private val publisherIdProvider: () -> String,
    private val allowlist: DownloadFolderAllowlist,
    private val tokenTable: LanMediaTokenTable,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * @param inputs every candidate entry: file + display title + media
     * type + optional season/episode/poster/duration. Files outside the
     * allowlist are silently dropped.
     */
    fun build(inputs: List<MediaEntryInput>): LanLibraryManifest {
        // Replace the token table's view with what we're about to publish
        // so revoked entries don't leave dangling tokens.
        tokenTable.clearAll()

        val entries = inputs.mapNotNull { input ->
            val file = input.file
            if (!allowlist.isAllowed(file)) return@mapNotNull null
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!canonical.isFile) return@mapNotNull null
            val size = canonical.length()
            val id = stableId(canonical, size)
            tokenTable.registerEntry(id, canonical)
            LanMediaEntry(
                id = id,
                title = input.title.ifBlank { canonical.nameWithoutExtension },
                mediaType = input.mediaType,
                sizeBytes = size,
                containerExtension = canonical.extension.lowercase(),
                mimeType = guessMimeType(canonical.extension),
                durationSeconds = input.durationSeconds,
                seasonNumber = input.seasonNumber,
                episodeNumber = input.episodeNumber,
                posterUrl = input.posterUrl,
            )
        }

        return LanLibraryManifest(
            version = MANIFEST_VERSION,
            publisherId = publisherIdProvider(),
            generatedAtEpochMs = nowMs(),
            entries = entries,
        )
    }

    private fun stableId(canonical: File, sizeBytes: Long): String {
        val bytes = (canonical.absolutePath + "|" + sizeBytes).encodeToByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Bare-bones extension → MIME map. Kept tiny on purpose - covers
     * the formats Torve actually downloads; everything else falls
     * through to a generic octet-stream so range streaming still works.
     */
    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    companion object {
        private const val HEX = "0123456789abcdef"
    }
}

/** Caller-supplied per-file metadata. */
data class MediaEntryInput(
    val file: File,
    val title: String,
    val mediaType: LanMediaType,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val durationSeconds: Long? = null,
)
