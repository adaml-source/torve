package com.torve.presentation.apple

import com.torve.data.addon.StremioMeta
import com.torve.data.addon.toMediaItem
import com.torve.data.addon.toCatalogueMediaItem
import com.torve.domain.model.*
import com.torve.domain.player.MediaIdentityFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A catalogue selection remains usable even when it has no numeric TMDB identity. */
object AppleCatalogueDetail {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(item: MediaItem): String = json.encodeToString(item)
    fun decode(value: String): MediaItem = json.decodeFromString(value)
    /** The selected manifest supplies the complete Stremio record, including ratings and videos. */
    fun extensionSeed(value: String, type: String): String {
        val meta = try { json.decodeFromString<StremioMeta>(value) } catch (_: Exception) {
            // Malformed optional provider fields cannot crash the native UI.
            val raw = json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
            fun text(key: String) = (raw[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            StremioMeta(id = text("id").orEmpty(), name = text("name").orEmpty(), type = text("type").orEmpty(),
                poster = text("poster"), background = text("background"), description = text("description"))
        }
        return encode(meta.copy(type = meta.type.ifBlank { type }).toMediaItem())
    }

    fun withExtensionSource(seedJSON: String, manifestUrl: String): String = encode(decode(seedJSON).copy(
        metadataSourceKey = MediaIdentityFactory.stableHash(manifestUrl)))

    fun extensionCatalogueSeed(value: String, type: String, manifestId: String): String = try {
        val meta = json.decodeFromString<StremioMeta>(value)
        encode(meta.copy(type = meta.type.ifBlank { type }).toCatalogueMediaItem(manifestId))
    } catch (_: Exception) { extensionSeed(value, type) }

    internal suspend fun resolve(
        seed: MediaItem,
        addons: List<InstalledAddon>,
        fetchMeta: suspend (InstalledAddon, String, String) -> StremioMeta?,
        enrich: suspend (MediaItem) -> MediaItem?,
        nativeFirst: Boolean = false,
        extensionAuthoritative: Boolean = false,
    ): MediaItem {
        if (seed.catalogueNotice != null) return seed
        if (nativeFirst) {
            val native = optional { enrich(seed) }
            if (native != null && native.type == seed.type &&
                (seed.tmdbId == null || native.tmdbId == seed.tmdbId) &&
                (seed.imdbId == null || native.imdbId == null || seed.imdbId == native.imdbId)) {
                // The selected provider ID remains the playback identity. Native
                // text/artwork belongs to the requested locale, even when the
                // source card already contained non-empty English fields.
                return fillMissing(native, seed).copy(id = seed.id)
            }
        }
        val type = if (seed.type == MediaType.SERIES) "series" else "movie"
        var item = seed
        // Older saved/library cards retain a numeric TMDB identity without its
        // cross-ID field. A bare number fails a manifest's `tmdb:` prefix check.
        val numericId = seed.id.removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 }
        val tmdbId = seed.tmdbId ?: numericId
        val aliases = if (extensionAuthoritative) listOfNotNull(
            seed.id.takeUnless { numericId != null && !it.startsWith("tmdb:") },
            seed.imdbId, tmdbId?.let { "tmdb:$it" }, tmdbId?.toString()
        ).distinct() else listOf(seed.id)
        val providers = addons.filter { addon ->
            addon.isEnabled && "meta" in addon.manifest.resources &&
                (addon.manifest.types.isEmpty() || type in addon.manifest.types) &&
                (addon.manifest.idPrefixes.isEmpty() || aliases.any { id -> addon.manifest.idPrefixes.any(id::startsWith) })
        }.sortedWith(compareBy<InstalledAddon> {
            if (seed.metadataSourceKey == MediaIdentityFactory.stableHash(it.manifestUrl)) 0 else 1
        }.thenBy { it.priority }).take(3)
        for (addon in providers) {
          for (identity in aliases.filter { id -> addon.manifest.idPrefixes.isEmpty() || addon.manifest.idPrefixes.any(id::startsWith) }) {
            val meta = optional { fetchMeta(addon, type, identity) } ?: continue
            // A provider must not redirect a selected title to a different ID/type.
            val acceptedTypes = if (type == "series") listOf("series", "tv") else listOf("movie")
            val sameIdentity = meta.id == identity || (extensionAuthoritative &&
                identity.matches(Regex("tt[0-9]+")) && meta.imdbId == identity &&
                meta.id.matches(Regex("tmdb:[1-9][0-9]*")))
            // Some user configurations explicitly return IMDb IDs even for a
            // TMDB request. Accept that declared normalization, retain the exact
            // requested TMDB identity, and keep the original playback ID.
            val tmdbToImdb = extensionAuthoritative && tmdbId != null && identity == "tmdb:$tmdbId" &&
                meta.id.matches(Regex("tt[0-9]+")) && meta.imdbId == meta.id
            if ((!sameIdentity && !tmdbToImdb) || (meta.type.isNotBlank() && meta.type !in acceptedTypes)) continue
            item = if (extensionAuthoritative) fillMissing(meta.copy(type = type).toMediaItem(), seed.copy(tmdbId = tmdbId)).copy(id = seed.id, metadataSourceKey = MediaIdentityFactory.stableHash(addon.manifestUrl))
                else fillMissing(seed, meta.copy(type = type).toMediaItem())
            if (extensionAuthoritative) return item
            break
          }
          if (item != seed) break
        }
        return if (nativeFirst) item else optional { enrich(item) }?.takeIf { it.type == seed.type }?.let { fillMissing(item, it) } ?: item
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    internal fun fillMissing(item: MediaItem, detail: MediaItem): MediaItem = item.copy(
        // Catalogue IDs are also playback IDs; enrichment only adds the resolved TMDB ID.
        tmdbId = item.tmdbId ?: detail.tmdbId,
        imdbId = item.imdbId ?: detail.imdbId,
        title = item.title.ifBlank { detail.title },
        year = item.year ?: detail.year,
        overview = item.overview?.takeIf { it.isNotBlank() } ?: detail.overview,
        posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
        backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: detail.backdropUrl,
        logoUrl = item.logoUrl ?: detail.logoUrl,
        logoCandidates = item.logoCandidates.ifEmpty { detail.logoCandidates },
        rating = item.rating ?: detail.rating,
        // The detailed response can add IMDb while the selected catalogue has
        // a separate TMDB score. Keep each provider's score in its own field.
        ratings = item.ratings?.let { r -> r.copy(
            imdbScore = r.imdbScore ?: detail.ratings?.imdbScore,
            imdbVotes = r.imdbVotes ?: detail.ratings?.imdbVotes,
            tmdbScore = r.tmdbScore ?: detail.ratings?.tmdbScore,
            rottenTomatoesScore = r.rottenTomatoesScore ?: detail.ratings?.rottenTomatoesScore,
            rtAudienceScore = r.rtAudienceScore ?: detail.ratings?.rtAudienceScore,
            metacriticScore = r.metacriticScore ?: detail.ratings?.metacriticScore,
            letterboxdScore = r.letterboxdScore ?: detail.ratings?.letterboxdScore,
            traktScore = r.traktScore ?: detail.ratings?.traktScore,
            mdblistScore = r.mdblistScore ?: detail.ratings?.mdblistScore,
            malScore = r.malScore ?: detail.ratings?.malScore,
        ) } ?: detail.ratings,
        runtime = item.runtime ?: detail.runtime,
        genres = item.genres.ifEmpty { detail.genres },
        cast = item.cast.ifEmpty { detail.cast },
        director = item.director ?: detail.director,
        releaseDate = item.releaseDate ?: detail.releaseDate,
        status = item.status ?: detail.status,
        trailerKey = item.trailerKey ?: detail.trailerKey,
        seasons = item.seasons.ifEmpty { detail.seasons },
        tagline = item.tagline ?: detail.tagline,
    )
}
