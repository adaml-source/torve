package com.torve.desktop.adult

import com.torve.domain.model.MediaItem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Two-layer on-disk cache for the Latest-on-Usenet pipeline.
 *
 *   1. **Poster lookups** - the `<title>|<year>` → TMDB Match.Found map
 *      built up by [NzbPosterCache]. Persists positive hits only;
 *      negative cache stays in-memory because it's cheap to recompute.
 *      TTL: 24h. Saving is debounced so a 100-row page doesn't fsync
 *      100 times.
 *
 *   2. **Catalog pages** - the matched-release list per
 *      `(indexerType, language, query)` tuple maintained by
 *      [NzbCatalogService]. TTL: 1h. On stale hit we still hydrate
 *      from disk for instant rendering then fire a background refresh.
 *
 * Files live under `<localAppData>\Torve\nzb_cache\` on Windows or
 * `~/.torve/nzb_cache/` elsewhere.
 */
internal object NzbDiskCache {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private const val POSTER_TTL_MS: Long = 24L * 60 * 60 * 1000  // 24h
    private const val CATALOG_TTL_MS: Long = 60L * 60 * 1000       // 1h

    private val cacheDir: File by lazy {
        File(rootDataDir(), "nzb_cache").also { runCatching { it.mkdirs() } }
    }

    private fun rootDataDir(): File {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        return if (localAppData != null) {
            File(localAppData, "Torve")
        } else {
            File(System.getProperty("user.home"), ".torve")
        }
    }

    // ── Poster cache ──────────────────────────────────────────────

    @Serializable
    private data class PosterCacheFile(
        val savedAtMs: Long,
        val entries: Map<String, NzbPosterCache.Match.Found>,
    )

    fun loadPosterCache(): Map<String, NzbPosterCache.Match.Found> {
        val file = File(cacheDir, "poster_cache.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val parsed = json.decodeFromString<PosterCacheFile>(file.readText())
            // Drop the whole map if it's older than the TTL - a stale
            // cache risks pinning posters to renamed/removed TMDB rows.
            if (System.currentTimeMillis() - parsed.savedAtMs > POSTER_TTL_MS) emptyMap()
            else parsed.entries
        }.getOrElse { emptyMap() }
    }

    fun savePosterCache(entries: Map<String, NzbPosterCache.Match.Found>) {
        if (entries.isEmpty()) return
        runCatching {
            val payload = PosterCacheFile(
                savedAtMs = System.currentTimeMillis(),
                entries = entries,
            )
            File(cacheDir, "poster_cache.json").writeText(json.encodeToString(payload))
        }
    }

    // ── Catalog cache (per-filter) ────────────────────────────────

    @Serializable
    private data class CatalogCacheFile(
        val savedAtMs: Long,
        val nextOffset: Int,
        val hasMore: Boolean,
        val releases: List<SerializableMatchedRelease>,
    )

    /**
     * Disk-friendly mirror of [NzbCatalogService.MatchedRelease]. We
     * keep [MatchedRelease] itself a plain data class so the runtime
     * code doesn't have to deal with the `kotlinx.serialization` dance,
     * and serialize through this DTO.
     */
    @Serializable
    private data class SerializableMatchedRelease(
        val nzb: NewznabItem,
        val match: NzbPosterCache.Match.Found,
        val mediaItem: MediaItem,
    )

    data class CatalogSnapshot(
        val savedAtMs: Long,
        val nextOffset: Int,
        val hasMore: Boolean,
        val releases: List<NzbCatalogService.MatchedRelease>,
        val isStale: Boolean,
    )

    fun loadCatalog(filterKey: String): CatalogSnapshot? {
        val file = catalogFile(filterKey)
        if (!file.exists()) return null
        return runCatching {
            val parsed = json.decodeFromString<CatalogCacheFile>(file.readText())
            CatalogSnapshot(
                savedAtMs = parsed.savedAtMs,
                nextOffset = parsed.nextOffset,
                hasMore = parsed.hasMore,
                releases = parsed.releases.map {
                    NzbCatalogService.MatchedRelease(
                        nzb = it.nzb,
                        match = it.match,
                        mediaItem = it.mediaItem,
                    )
                },
                isStale = System.currentTimeMillis() - parsed.savedAtMs > CATALOG_TTL_MS,
            )
        }.getOrNull()
    }

    fun saveCatalog(
        filterKey: String,
        nextOffset: Int,
        hasMore: Boolean,
        releases: List<NzbCatalogService.MatchedRelease>,
    ) {
        if (releases.isEmpty()) return
        runCatching {
            val payload = CatalogCacheFile(
                savedAtMs = System.currentTimeMillis(),
                nextOffset = nextOffset,
                hasMore = hasMore,
                releases = releases.map {
                    SerializableMatchedRelease(it.nzb, it.match, it.mediaItem)
                },
            )
            catalogFile(filterKey).writeText(json.encodeToString(payload))
        }
    }

    private fun catalogFile(filterKey: String): File {
        val dir = File(cacheDir, "catalog").also { runCatching { it.mkdirs() } }
        // Filter keys come from FilterKey.toString() and may contain
        // path-unsafe characters. Hash to a stable hex name.
        val safe = filterKey.hashCode().toUInt().toString(16)
        return File(dir, "$safe.json")
    }
}
