package com.torve.android.tv.components

import com.torve.android.tv.withTvHeroDetail
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.toMediaItem
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal interface TvAddonPreviewLookup {
    suspend fun imdbId(type: MediaType, tmdbId: Int): String? = null
    suspend fun meta(addon: InstalledAddon, type: String, id: String): MediaItem?
    suspend fun search(addon: InstalledAddon, catalog: AddonCatalog, title: String): List<MediaItem>
}

internal class ClientTvAddonPreviewLookup(private val client: StremioAddonClient,
    private val identities: TraktClient? = null) : TvAddonPreviewLookup {
    // Public ID conversion respects disabled TMDB catalogues and enrichment.
    override suspend fun imdbId(type: MediaType, tmdbId: Int): String? =
        identities?.getImdbIdByTmdb(tmdbId, series = type == MediaType.SERIES)
    private fun InstalledAddon.base() = manifestUrl.removeSuffix("/manifest.json").trimEnd('/')
    override suspend fun meta(addon: InstalledAddon, type: String, id: String) =
        client.fetchMeta(addon.base(), type, id).meta?.toMediaItem()
    override suspend fun search(addon: InstalledAddon, catalog: AddonCatalog, title: String) =
        client.fetchCatalog(addon.base(), catalog.type, catalog.id, search = title).metas.map { it.toMediaItem() }
}

internal fun MediaItem.withSavedTvPreviewIdentity(saved: List<WatchlistItem>): MediaItem {
    if (!(imdbId ?: id.extractImdbIdOrNull()).isNullOrBlank()) return this
    val tmdb = tvPreviewTmdbId() ?: return this
    val match = saved.filter { it.mediaType == type && it.tmdbId == tmdb && !it.imdbId.isNullOrBlank() }
        .distinctBy { it.imdbId }.singleOrNull() ?: return this
    return copy(imdbId = match.imdbId, year = year ?: match.year)
}

/** Descriptions come from installed providers. Resolve numeric identities first;
 * an IMDb-only provider must never receive a TMDB number or a guessed remake. */
internal suspend fun loadTvAddonPreviewDetails(
    item: MediaItem, installed: List<InstalledAddon>, lookup: TvAddonPreviewLookup,
    onPartial: suspend (MediaItem) -> Unit = {},
): MediaItem? {
    if (!item.isTvPreviewTitle()) return null
    val type = if (item.type == MediaType.MOVIE) "movie" else "series"
    var result = item
    var received = false
    val providers = installed.filter { it.isEnabled && "meta" in it.manifest.resources &&
        (it.manifest.types.isEmpty() || type in it.manifest.types) }.take(2)
    if (providers.isNotEmpty() && (result.imdbId ?: result.id.extractImdbIdOrNull()).isNullOrBlank()) {
        result.tvPreviewTmdbId()?.let { tmdbId ->
            val imdb = try { withTimeoutOrNull(2_500) { lookup.imdbId(item.type, tmdbId) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (imdb?.matches(Regex("tt[0-9]+")) == true) {
                result = result.copy(imdbId = imdb)
                onPartial(result)
                received = true
            }
        }
    }
    suspend fun accept(value: MediaItem?) {
        if (value == null || value.type != item.type || !value.isTvPreviewTitle()) return
        result = result.withTvHeroDetail(value)
        received = true
        onPartial(result)
    }
    for (addon in providers) {
        try {
            val providerId = result.imdbId ?: result.id.extractImdbIdOrNull() ?: result.id
            val prefixes = addon.manifest.idPrefixes
            val supported = prefixes.isEmpty() || prefixes.any { providerId.startsWith(it) }
            if (supported) accept(withTimeoutOrNull(2_500) { lookup.meta(addon, type, providerId) })
            if (!result.needsTvPreviewDetails()) return result
            // Exact title + known year/type, unique identity only. Never choose
            // the first search result when several remakes have the same name.
            val catalog = addon.manifest.catalogs.firstOrNull { it.type == type && it.extra.any { e -> e.name == "search" } }
            if ((!supported || (result.imdbId ?: result.id.extractImdbIdOrNull()).isNullOrBlank()) && catalog != null) {
                val title = item.tvPreviewShowTitle()
                val matches = withTimeoutOrNull(2_500) { lookup.search(addon, catalog, title) }.orEmpty()
                    .filter { it.type == item.type && it.title.trim().equals(title.trim(), true) &&
                        (item.year == null || it.year == item.year) }.distinctBy { it.id }
                matches.singleOrNull()?.let { match ->
                    accept(match)
                    if (result.needsTvPreviewDetails()) accept(withTimeoutOrNull(2_500) { lookup.meta(addon, type, match.id) })
                }
            }
            if (!result.needsTvPreviewDetails()) return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve fields from the other enabled provider. */ }
    }
    return result.takeIf { received }
}
