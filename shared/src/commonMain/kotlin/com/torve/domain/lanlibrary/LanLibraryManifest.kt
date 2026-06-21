package com.torve.domain.lanlibrary

import kotlinx.serialization.Serializable

/**
 * Phase 3 Slice C — LAN downloads/library foundation.
 *
 * The manifest a desktop instance publishes to itself (and, in a future
 * slice, to a TV/mobile peer over LAN) so other surfaces can show
 * "available on desktop" without ever seeing a filesystem path.
 *
 * Critical invariant: **no field in this manifest contains a raw local
 * path.** Each entry carries an opaque [LanMediaEntry.id] that the
 * publisher can resolve back to a path on its own; consumers only see
 * the id + token-gated stream URL when the publisher chooses to issue
 * one.
 */
@Serializable
data class LanLibraryManifest(
    val version: Int = MANIFEST_VERSION,
    /** Stable across publisher restarts — used by clients to dedupe. */
    val publisherId: String,
    /** When the manifest was assembled; clients use this to detect staleness. */
    val generatedAtEpochMs: Long,
    val entries: List<LanMediaEntry>,
)

@Serializable
data class LanMediaEntry(
    /**
     * Opaque, publisher-stable id. Implementations should derive this
     * deterministically from a hash of (canonical path, size) so the
     * id survives a relaunch but is meaningless without the publisher's
     * own table to resolve it.
     */
    val id: String,
    val title: String,
    val mediaType: LanMediaType,
    val sizeBytes: Long,
    /** Container hint — `mp4` / `mkv` / `webm` / `m4a`. Lowercase, no dot. */
    val containerExtension: String,
    val mimeType: String,
    val durationSeconds: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
)

@Serializable
enum class LanMediaType {
    MOVIE,
    SHOW_EPISODE,
    OTHER,
}

const val MANIFEST_VERSION: Int = 1
