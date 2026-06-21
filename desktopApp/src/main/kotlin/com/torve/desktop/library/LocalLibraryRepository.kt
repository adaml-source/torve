package com.torve.desktop.library

import com.torve.desktop.platform.desktopDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local file library - Phase 2 first cut.
 *
 * Tracks a list of root folders (configured by the user) and surfaces the
 * video files found inside them. No metadata matching yet - entries are
 * keyed by absolute path and labelled with their filename. Real
 * TMDB-backed enrichment is a follow-up; the bones (data model, scan,
 * persistence, UI surface) come first.
 *
 * Persistence: a single JSON file `local_library.json` under
 * `desktopDataDir()`. Atomic-ish writes via temp + rename. Lost only on
 * uninstall.
 */
class LocalLibraryRepository(rootDir: File = desktopDataDir()) {

    @Serializable
    data class FolderConfig(
        val path: String,
        val addedAtMs: Long,
    )

    @Serializable
    data class LibraryEntry(
        val absolutePath: String,
        val displayName: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val folderPath: String,
        // ── TMDB metadata (filled in by enrichAllNeeded) ──────────────
        val tmdbId: Int? = null,
        val matchedTitle: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val overview: String? = null,
        val isSeries: Boolean = false,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val episodeTitle: String? = null,
        // 0 = never attempted, >0 = epoch ms of the last attempt (success
        // or failure). Lets us back off on persistent misses without
        // re-querying every app start.
        val matchedAtMs: Long = 0,
    )

    @Serializable
    data class LibrarySnapshot(
        val folders: List<FolderConfig> = emptyList(),
        val entries: List<LibraryEntry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val foldersSerializer = ListSerializer(FolderConfig.serializer())
    private val entriesSerializer = ListSerializer(LibraryEntry.serializer())

    private val foldersFile = File(rootDir, "local_library_folders.json").apply { parentFile?.mkdirs() }
    private val entriesFile = File(rootDir, "local_library_entries.json").apply { parentFile?.mkdirs() }

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<LibrarySnapshot> = _state.asStateFlow()

    /**
     * Enrichment progress so the UI can show "Matching X / Y" while a
     * batch run is in flight. `total` is the work the latest run was
     * given; `done` ticks up per entry processed. Both reset to 0 once
     * a run completes.
     */
    data class EnrichmentProgress(val done: Int, val total: Int) {
        val isActive: Boolean get() = total > 0 && done < total
    }
    private val _enrichmentProgress = kotlinx.coroutines.flow.MutableStateFlow(EnrichmentProgress(0, 0))
    val enrichmentProgress: kotlinx.coroutines.flow.StateFlow<EnrichmentProgress> = _enrichmentProgress.asStateFlow()

    fun isEmpty(): Boolean = _state.value.folders.isEmpty()

    /**
     * Add a root folder. No-op if the folder is already tracked. Triggers
     * a fresh scan of the new folder; existing entries from other folders
     * are preserved.
     */
    suspend fun addFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        val absolute = File(path).absoluteFile
        if (!absolute.exists() || !absolute.isDirectory) return@withContext false
        val abs = absolute.absolutePath
        val current = _state.value
        if (current.folders.any { it.path == abs }) return@withContext false
        val newFolder = FolderConfig(path = abs, addedAtMs = System.currentTimeMillis())
        val previous = current.entries.associateBy { it.absolutePath }
        val scanned = scanFolder(absolute).map { fresh ->
            previous[fresh.absolutePath]?.let { old ->
                fresh.copy(
                    tmdbId = old.tmdbId,
                    matchedTitle = old.matchedTitle,
                    year = old.year,
                    posterUrl = old.posterUrl,
                    overview = old.overview,
                    isSeries = old.isSeries,
                    seasonNumber = old.seasonNumber,
                    episodeNumber = old.episodeNumber,
                    matchedAtMs = old.matchedAtMs,
                )
            } ?: fresh
        }
        val next = LibrarySnapshot(
            folders = current.folders + newFolder,
            entries = (current.entries + scanned).distinctBy { it.absolutePath },
        )
        _state.value = next
        persist(next)
        true
    }

    /**
     * Drop a folder and every entry under it. Other folders' entries are
     * untouched.
     */
    suspend fun removeFolder(path: String) = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current.folders.none { it.path == path }) return@withContext
        val next = LibrarySnapshot(
            folders = current.folders.filterNot { it.path == path },
            entries = current.entries.filterNot { it.folderPath == path },
        )
        _state.value = next
        persist(next)
    }

    /**
     * Walk every entry that hasn't yet been matched (or whose previous
     * match attempt is older than [retryAfterMs]) and look it up via
     * TMDB. Single-flight, polite - one entry at a time, with a small
     * inter-call delay so we never blast the API.
     *
     * Cancellable via the calling coroutine - partial progress is
     * persisted as we go so a kill mid-walk doesn't lose work.
     */
    suspend fun enrichAllNeeded(
        metadataRepository: com.torve.domain.repository.MetadataRepository,
        retryAfterMs: Long = 24L * 60L * 60L * 1000L,
        perRequestDelayMs: Long = 80L,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val candidates = _state.value.entries
            .asSequence()
            .filter { it.tmdbId == null && (now - it.matchedAtMs) > retryAfterMs }
            .toList()
        if (candidates.isEmpty()) return@withContext
        _enrichmentProgress.value = EnrichmentProgress(done = 0, total = candidates.size)

        for ((index, entry) in candidates.withIndex()) {
            // Re-read state - earlier iterations may have updated it,
            // and folder removal could have nuked this entry mid-loop.
            val live = _state.value.entries.firstOrNull { it.absolutePath == entry.absolutePath }
                ?: continue
            val parsed = LibraryFilenameParser.parse(File(entry.absolutePath).name)
            val match = lookupBestMatch(metadataRepository, parsed)
            val updated = live.copy(
                tmdbId = match?.tmdbId,
                matchedTitle = match?.title,
                year = match?.year ?: parsed.year,
                posterUrl = match?.posterUrl,
                overview = match?.overview,
                isSeries = parsed.isSeries,
                seasonNumber = parsed.seasonNumber,
                episodeNumber = parsed.episodeNumber,
                matchedAtMs = System.currentTimeMillis(),
            )
            mergeEntry(updated)
            _enrichmentProgress.value = EnrichmentProgress(done = index + 1, total = candidates.size)
            kotlinx.coroutines.delay(perRequestDelayMs)
        }
        _enrichmentProgress.value = EnrichmentProgress(0, 0)
    }

    private suspend fun lookupBestMatch(
        repo: com.torve.domain.repository.MetadataRepository,
        parsed: LibraryFilenameParser.Parsed,
    ): com.torve.domain.model.MediaItem? {
        if (parsed.title.isBlank()) return null
        val type = if (parsed.isSeries) "tv" else "movie"
        return runCatching {
            val results = repo.searchMultiPaged(query = parsed.title, page = 1, type = type).items
            // Prefer matches whose year matches the parsed year, falling
            // back to the first result. TMDB's relevance ranking is
            // already pretty good - this just nudges it toward the right
            // re-release year when the user has specifically tagged one.
            val byYear = parsed.year?.let { y -> results.firstOrNull { it.year == y } }
            byYear ?: results.firstOrNull()
        }.onFailure { t ->
            println("TORVE LIBRARY | TMDB lookup failed for '${parsed.title}': ${t.message}")
        }.getOrNull()
    }

    /**
     * Resolve TMDB episode names for any series entry that's been
     * matched (tmdbId set) but doesn't yet have [LibraryEntry.episodeTitle].
     *
     * Batched by (tmdbId, seasonNumber) - one TMDB `getSeasonDetail`
     * call covers the whole season's episodes. 100ms inter-call delay
     * to stay polite. Cancel-safe: persists per batch.
     */
    suspend fun enrichEpisodeTitles(
        metadataRepository: com.torve.domain.repository.MetadataRepository,
        perRequestDelayMs: Long = 100L,
    ) = withContext(Dispatchers.IO) {
        val needs = _state.value.entries
            .asSequence()
            .filter { it.isSeries && it.tmdbId != null && it.seasonNumber != null && it.episodeTitle.isNullOrBlank() }
            .toList()
        if (needs.isEmpty()) return@withContext
        val byBatch = needs.groupBy { it.tmdbId!! to it.seasonNumber!! }
        for ((batch, entries) in byBatch) {
            val (tmdbId, seasonNumber) = batch
            val season = runCatching {
                metadataRepository.getSeasonDetail(tmdbId, seasonNumber)
            }.onFailure { t ->
                println("TORVE LIBRARY | season detail failed tmdbId=$tmdbId season=$seasonNumber: ${t.message}")
            }.getOrNull() ?: continue
            val byEpisode = season.episodes.associateBy { it.episodeNumber }
            entries.forEach { entry ->
                val ep = entry.episodeNumber?.let(byEpisode::get)
                if (ep != null && ep.name.isNotBlank()) {
                    mergeEntry(entry.copy(episodeTitle = ep.name))
                }
            }
            kotlinx.coroutines.delay(perRequestDelayMs)
        }
    }

    private fun mergeEntry(updated: LibraryEntry) {
        val current = _state.value
        val next = current.copy(
            entries = current.entries.map { existing ->
                if (existing.absolutePath == updated.absolutePath) updated else existing
            },
        )
        _state.value = next
        persist(next)
    }

    /** Re-scan every tracked folder, preserving any TMDB enrichment we
     * already had for unchanged paths. */
    suspend fun rescanAll() = withContext(Dispatchers.IO) {
        val current = _state.value
        val previous = current.entries.associateBy { it.absolutePath }
        val refreshed = current.folders.flatMap { scanFolder(File(it.path)) }
            .distinctBy { it.absolutePath }
            .map { fresh ->
                // Carry TMDB metadata across - file size / mtime can
                // change without invalidating the match.
                previous[fresh.absolutePath]?.let { old ->
                    fresh.copy(
                        tmdbId = old.tmdbId,
                        matchedTitle = old.matchedTitle,
                        year = old.year,
                        posterUrl = old.posterUrl,
                        overview = old.overview,
                        isSeries = old.isSeries,
                        seasonNumber = old.seasonNumber,
                        episodeNumber = old.episodeNumber,
                        matchedAtMs = old.matchedAtMs,
                    )
                } ?: fresh
            }
        val next = current.copy(entries = refreshed)
        _state.value = next
        persist(next)
    }

    private fun scanFolder(root: File): List<LibraryEntry> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val rootAbs = root.absolutePath
        // Walk all files, filter to recognised video extensions. Hidden
        // files and dot-directories skipped. Capped at MAX_ENTRIES per
        // root to keep the in-memory list manageable on huge libraries -
        // metadata-matching upgrade can lift this once entries become
        // queryable rather than fully loaded.
        val out = ArrayList<LibraryEntry>()
        root.walkTopDown()
            .onEnter { dir ->
                !dir.name.startsWith(".") && dir.canRead()
            }
            .filter { f -> f.isFile && !f.isHidden && f.extension.lowercase() in VIDEO_EXTENSIONS }
            .take(MAX_ENTRIES)
            .forEach { f ->
                out += LibraryEntry(
                    absolutePath = f.absolutePath,
                    displayName = displayNameFromFile(f),
                    sizeBytes = runCatching { f.length() }.getOrDefault(0L),
                    modifiedAtMs = runCatching { f.lastModified() }.getOrDefault(0L),
                    folderPath = rootAbs,
                )
            }
        return out
    }

    private fun loadFromDisk(): LibrarySnapshot {
        val folders = if (foldersFile.exists() && foldersFile.length() > 0L) {
            runCatching { json.decodeFromString(foldersSerializer, foldersFile.readText()) }
                .getOrElse { emptyList() }
        } else emptyList()
        val entries = if (entriesFile.exists() && entriesFile.length() > 0L) {
            runCatching { json.decodeFromString(entriesSerializer, entriesFile.readText()) }
                .getOrElse { emptyList() }
        } else emptyList()
        return LibrarySnapshot(folders = folders, entries = entries)
    }

    private fun persist(snap: LibrarySnapshot) {
        runCatching {
            val tmpFolders = File(foldersFile.parentFile, foldersFile.name + ".tmp")
            tmpFolders.writeText(json.encodeToString(foldersSerializer, snap.folders))
            if (foldersFile.exists()) foldersFile.delete()
            tmpFolders.renameTo(foldersFile)

            val tmpEntries = File(entriesFile.parentFile, entriesFile.name + ".tmp")
            tmpEntries.writeText(json.encodeToString(entriesSerializer, snap.entries))
            if (entriesFile.exists()) entriesFile.delete()
            tmpEntries.renameTo(entriesFile)
        }.onFailure { t ->
            println("TORVE LIBRARY | persist failed: ${t.message}")
        }
    }

    private fun displayNameFromFile(file: File): String {
        // Strip extension and quality/release tags so the displayed
        // title looks like "The Matrix (1999)" instead of
        // "The.Matrix.1999.1080p.BluRay.x264-FOO.mkv".
        val withoutExt = file.nameWithoutExtension
        return withoutExt
            .replace(Regex("[._]"), " ")
            .replace(Regex("\\s*\\b(1080p|720p|2160p|4K|UHD|HDR|10bit|x264|x265|HEVC|BluRay|WEBRip|WEB-DL|REMUX|REPACK|PROPER)\\b.*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { withoutExt }
    }

    companion object {
        private val VIDEO_EXTENSIONS: Set<String> = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv",
            "mpg", "mpeg", "m2ts", "ts", "vob", "ogv", "3gp", "divx", "xvid",
        )
        /** Soft cap per folder so an absurdly deep tree can't OOM the in-memory list. */
        private const val MAX_ENTRIES: Int = 5_000
    }
}
