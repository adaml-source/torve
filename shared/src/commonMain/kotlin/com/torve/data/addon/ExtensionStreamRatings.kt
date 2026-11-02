package com.torve.data.addon

import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Compatibility with installed rating-only extensions that use Stremio's stream resource. */
object ExtensionStreamRatings {
    fun supports(manifest: AddonManifest): Boolean =
        "stream" in manifest.resources && manifest.catalogs.isEmpty() && "meta" !in manifest.resources &&
            (manifest.id == "imdb.ratings.local" || Regex("(?i)\\bratings?\\b").containsMatchIn(manifest.name))

    fun parse(streams: List<StremioStream>, imdbId: String): MediaRatings? {
        if (!imdbId.matches(Regex("tt[0-9]+"))) return null
        val rows = streams.take(32).mapNotNull { stream ->
            if (listOf(stream.url, stream.infoHash, stream.infoHashSnake, stream.hash, stream.magnet, stream.ytId)
                    .any { !it.isNullOrBlank() }) return@mapNotNull null
            val link = try { Url(stream.externalUrl ?: return@mapNotNull null) } catch (_: Exception) { return@mapNotNull null }
            if (link.protocol.name != "https" || link.host.lowercase() !in setOf("imdb.com", "www.imdb.com") ||
                !Regex("/title/${Regex.escape(imdbId)}(?:/|/parentalguide/?)?").matches(link.encodedPath)) return@mapNotNull null
            val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString("\n").take(8192)
            val match = Regex("(?i)\\bIMDb\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*10\\b").find(text)
                ?: return@mapNotNull null
            val score = match.groupValues[1].replace(',', '.').toFloatOrNull()
                ?.takeIf { it.isFinite() && it > 0f && it <= 10f } ?: return@mapNotNull null
            val votes = Regex("(?i)\\(([0-9][0-9, \\u00a0]*)\\s+votes\\)").find(text)?.groupValues?.get(1)
                ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            MediaRatings(imdbScore = score, imdbVotes = votes)
        }
        // Conflicting rows are not evidence for an unambiguous title rating.
        if (rows.map { it.imdbScore }.distinct().size != 1) return null
        return rows.firstOrNull()
    }

    suspend fun enrich(item: MediaItem, addons: List<InstalledAddon>,
        fetch: suspend (InstalledAddon, String, String) -> List<StremioStream>): MediaItem {
        val imdb = (listOfNotNull(item.imdbId, item.id).firstOrNull { it.matches(Regex("tt[0-9]+")) }) ?: return item
        val type = if (item.type == MediaType.SERIES) "series" else "movie"
        val rating = withTimeoutOrNull(3000) {
            for (addon in addons.filter { it.isEnabled && supports(it.manifest) &&
                (it.manifest.types.isEmpty() || type in it.manifest.types) &&
                (it.manifest.idPrefixes.isEmpty() || it.manifest.idPrefixes.any(imdb::startsWith))
            }.sortedBy { it.priority }.take(2)) {
                val streams = try { withTimeoutOrNull(1500) { fetch(addon, type, imdb) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                parse(streams, imdb)?.let { return@withTimeoutOrNull it }
            }
            null
        } ?: return item
        return item.copy(ratings = (item.ratings ?: MediaRatings()).copy(
            imdbScore = rating.imdbScore, imdbVotes = rating.imdbVotes))
    }
}
