package com.torve.android.tv.components

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.torve.android.tv.withTvHeroDetail
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.toMediaItem
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.model.*
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

private val visibleMetadataBudget = Semaphore(2)
private val visibleMetadataJson = Json { ignoreUnknownKeys = true }
private val visibleMetadataAttempts = linkedMapOf<String, Long>()

/** Visible tiles get cache/artwork work before background catalogue sweeps. */
@Composable
internal fun rememberTvVisibleMetadata(items: List<MediaItem>, scope: String, needsOverview: Boolean = false,
    maximumCandidates: Int = 16, needsDetails: Boolean = false): List<MediaItem> {
    val context = LocalContext.current
    val koin = org.koin.compose.getKoin()
    val settings: SettingsViewModel = koinInject()
    val metadata: MetadataRepository = koinInject()
    val localizedMetadata: com.torve.data.metadata.LocalizedMediaRepository = koinInject()
    val ratings: RatingsEnricher = koinInject()
    val addons: AddonRepository = koinInject()
    val client: StremioAddonClient = koinInject()
    val ids: UserIdProvider = koinInject()
    val state by settings.state.collectAsState()
    val profileScope = "${state.personalDataUserId}:${state.appLanguage}:$scope"
    val candidates = items.filter { it.isTvPreviewTitle() }
        .distinctBy { "${it.type}:${it.tmdbId ?: it.imdbId ?: it.id}" }.take(maximumCandidates.coerceIn(1, 80))
    val signature = candidates.map { "${it.type}:${it.id}:${it.posterUrl.orEmpty()}:${com.torve.android.ui.community.artworkFailureStamp(it)}" }
    LaunchedEffect(profileScope, signature, needsOverview, needsDetails, state.tmdbEnrichmentEnabled, state.tmdbCatalogueEnabled, state.mdblistApiKey.hashCode()) {
        if (candidates.isEmpty()) return@LaunchedEffect
        val prefs = context.getSharedPreferences("tv_visible_metadata", Context.MODE_PRIVATE)
        val storageKey = "localized_v2:$profileScope".hashCode().toString()
        val saved = withContext(Dispatchers.IO) {
            if (System.currentTimeMillis() - prefs.getLong("$storageKey:time", 0) > 7L * 86_400_000) emptyList()
            else runCatching { visibleMetadataJson.decodeFromString(ListSerializer(MediaItem.serializer()), prefs.getString(storageKey, "[]").orEmpty()) }.getOrDefault(emptyList())
        }
        val visibleKeys = candidates.map { it.type to (it.tmdbId?.toString() ?: it.id) }.toSet()
        saved.filter { (it.type to (it.tmdbId?.toString() ?: it.id)) in visibleKeys }.forEach {
            cacheTvBrowsePreviewEnrichedItem(it)
            cacheTvLocalizedPresentation(tvLocalizedPresentationKey(it, state.personalDataUserId, state.appLanguage.code), it)
        }
        val localArtwork = withContext(Dispatchers.IO) { ids.withPersonalDataScope {
            val missing = candidates.map(::withCachedTvBrowsePreviewEnrichment).filter { it.posterUrl.isNullOrBlank() || it.backdropUrl.isNullOrBlank() }
            if (missing.isEmpty()) emptyList() else {
                val library = koin.get<WatchlistRepository>().getAll()
                val history = koin.get<WatchHistoryRepository>()
                missing.map { item ->
                    val fromLibrary = tvSavedArtwork(item, library, emptyList())
                    if (!fromLibrary.posterUrl.isNullOrBlank() && !fromLibrary.backdropUrl.isNullOrBlank()) fromLibrary
                    else tvSavedArtwork(fromLibrary, emptyList(), history.getForMedia(item.tvPreviewTmdbId()?.toString() ?: item.id))
                }
            }
        } }
        localArtwork.forEach(::cacheTvBrowsePreviewEnrichedItem)
        val hydrated = withContext(Dispatchers.IO) { ratings.hydrateListFromCache(candidates) }
        hydrated.forEach(::cacheTvBrowsePreviewEnrichedItem)
        // Let focus and cached tiles render first. Rapidly passing a row cancels
        // its work before it joins the bounded network queue.
        delay(900)
        val enabledAddons = withContext(Dispatchers.IO) { ids.withPersonalDataScope { addons.getEnabledAddons() } }
        supervisorScope {
            launch {
                val ratingTargets = candidates.map(::withCachedTvBrowsePreviewEnrichment).filter {
                    (it.ratings?.imdbScore == null || it.ratings?.rottenTomatoesScore == null) &&
                        System.currentTimeMillis() - (visibleMetadataAttempts["$profileScope:ratings:${it.type}:${it.id}"] ?: 0) > 60_000
                }
                if (ratingTargets.isNotEmpty()) {
                    try {
                        for (batch in ratingTargets.chunked(4)) {
                            val loaded = withTimeoutOrNull(8_000) { ratings.enrichList(batch, state.mdblistApiKey) }
                            loaded?.forEach(::cacheTvBrowsePreviewEnrichedItem)
                            if (loaded != null) batch.forEach { visibleMetadataAttempts["$profileScope:ratings:${it.type}:${it.id}"] = System.currentTimeMillis() }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            }
            candidates.map { original -> async {
                val attemptKey = "$profileScope:$needsOverview:$needsDetails:${original.type}:${original.tmdbId ?: original.imdbId ?: original.id}"
                val checked = visibleMetadataAttempts[attemptKey]
                val imageFailure = com.torve.android.ui.community.artworkFailureStamp(original)
                if (checked != null && System.currentTimeMillis() - checked < 60_000 && imageFailure <= checked) return@async
                visibleMetadataBudget.withPermit {
                    var enriched = withCachedTvBrowsePreviewEnrichment(original)
                    try {
                        val localizationKey = tvLocalizedPresentationKey(original, state.personalDataUserId, state.appLanguage.code)
                        val needsLocalization = state.tmdbEnrichmentEnabled && !hasTvLocalizedPresentation(localizationKey)
                        val needsPresentation = enriched.posterUrl.isNullOrBlank() || enriched.backdropUrl.isNullOrBlank() || enriched.isStubDetail ||
                            needsLocalization ||
                            imageFailure > (checked ?: 0) || (needsOverview && enriched.overview.isNullOrBlank()) ||
                            (needsDetails && (enriched.year == null || enriched.runtime == null || enriched.cast.isEmpty() || enriched.logoUrl.isNullOrBlank()))
                        val needsIdentity = enriched.tvPreviewTmdbId() == null && enriched.ratings?.rottenTomatoesScore == null
                        if (needsPresentation || needsIdentity) {
                            val detail = withTimeoutOrNull(7_000) { withContext(Dispatchers.IO) {
                                val type = if (original.type == MediaType.MOVIE) "movie" else "tv"
                                val imdb = original.imdbId ?: original.id.extractImdbIdOrNull()
                                val id = original.tvPreviewTmdbId()
                                if (needsLocalization) {
                                    return@withContext localizedMetadata.presentation(id?.toString() ?: imdb ?: original.id,
                                        type, state.appLanguage.code, includeCast = needsDetails)
                                }
                                if (state.tmdbEnrichmentEnabled || state.tmdbCatalogueEnabled) {
                                    val match = if (id == null && imdb != null) metadata.findByImdbId(imdb, type) else null
                                    val resolved = id ?: match?.tmdbId
                                    if (resolved != null) return@withContext if (needsPresentation) metadata.getDetail(type, resolved) else match
                                }
                                if (imdb != null) {
                                    val addonType = if (original.type == MediaType.MOVIE) "movie" else "series"
                                    for (addon in enabledAddons.filter { "meta" in it.manifest.resources &&
                                        (it.manifest.types.isEmpty() || addonType in it.manifest.types) }.take(2)) {
                                        val value = client.fetchMeta(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'), addonType, imdb).meta?.toMediaItem()
                                        if (value != null && !value.posterUrl.isNullOrBlank() && value.catalogueNotice == null) return@withContext value
                                    }
                                }
                                null
                            } }
                            if (detail != null && !detail.isStubDetail && !detail.isContentPlaceholder) {
                                if (needsLocalization) {
                                    cacheTvLocalizedPresentation(localizationKey, detail)
                                    cacheTvLocalizedPresentation(tvLocalizedPresentationKey(detail, state.personalDataUserId, state.appLanguage.code), detail)
                                }
                                enriched = enriched.withTvHeroDetail(detail).copy(isStubDetail = false, year = enriched.year ?: detail.year,
                                    posterUrl = com.torve.android.ui.community.preferUsableArtwork(enriched.posterUrl, detail.posterUrl),
                                    backdropUrl = com.torve.android.ui.community.preferUsableArtwork(enriched.backdropUrl, detail.backdropUrl))
                                cacheTvBrowsePreviewEnrichedItem(enriched)
                            }
                        }
                        visibleMetadataAttempts[attemptKey] = System.currentTimeMillis()
                        while (visibleMetadataAttempts.size > 512) visibleMetadataAttempts.remove(visibleMetadataAttempts.keys.first())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep cached presentation; retry when revisiting. */ }
                }
            } }.awaitAll()
            // IMDb-only extension results can acquire their TMDB identity in
            // the metadata pass. Resolve those through the backend batch API too.
            val newlyIdentified = candidates.filter { it.tvPreviewTmdbId() == null }
                .map(::withCachedTvBrowsePreviewEnrichment).filter { it.tmdbId != null && it.ratings?.rottenTomatoesScore == null }
            if (newlyIdentified.isNotEmpty()) {
                try { withTimeoutOrNull(12_000) { ratings.enrichList(newlyIdentified, state.mdblistApiKey) }?.forEach(::cacheTvBrowsePreviewEnrichedItem) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
        val retained = (saved + candidates.map { localizedTvPresentation(withCachedTvBrowsePreviewEnrichment(it), state.personalDataUserId, state.appLanguage.code, state.tmdbEnrichmentEnabled) })
            .associateBy { "${it.type}:${it.id}" }.values.toList().takeLast(120)
            .map { it.copy(cast = it.cast.take(6), seasons = emptyList(), overview = it.overview?.take(1200)) }
        withContext(Dispatchers.IO) {
            prefs.edit().putString(storageKey, visibleMetadataJson.encodeToString(ListSerializer(MediaItem.serializer()), retained))
                .putLong("$storageKey:time", System.currentTimeMillis()).apply()
        }
    }
    return items.map { localizedTvPresentation(withCachedTvBrowsePreviewEnrichment(it), state.personalDataUserId, state.appLanguage.code, state.tmdbEnrichmentEnabled) }
}

/** The focused title must not wait behind a whole rail or restart when its
 * neighbours receive artwork/ratings. Sparse rows get their own bounded lookup. */
@Composable
internal fun RememberFocusedTvPreviewDetails(item: MediaItem?, screen: String) {
    val metadata: MetadataRepository = koinInject()
    val identities: com.torve.data.trakt.TraktClient = koinInject()
    val addons: AddonRepository = koinInject()
    val client: StremioAddonClient = koinInject()
    val ids: UserIdProvider = koinInject()
    val settings: SettingsViewModel = koinInject()
    val state by settings.state.collectAsState()
    val owner = state.personalDataUserId
    val context = LocalContext.current
    val checked = remember(owner, state.appLanguage, screen, state.tmdbCatalogueEnabled, state.tmdbEnrichmentEnabled) { mutableMapOf<String, Long>() }
    val identity = item?.takeIf { it.isTvPreviewTitle() }?.let { "${it.type}:${it.id}" }
    LaunchedEffect(owner, state.appLanguage, identity, state.tmdbExtensionsLoaded, state.tmdbCatalogueEnabled, state.tmdbEnrichmentEnabled) {
        if (!state.tmdbExtensionsLoaded) return@LaunchedEffect
        val original = item?.takeIf { it.isTvPreviewTitle() } ?: return@LaunchedEffect
        val key = identity ?: return@LaunchedEffect
        if (System.currentTimeMillis() - (checked[key] ?: 0) < 30_000) return@LaunchedEffect
        if (!withCachedTvBrowsePreviewEnrichment(original).needsTvPreviewDetails()) return@LaunchedEffect
        val prefs = context.getSharedPreferences("tv_focused_preview_v1", Context.MODE_PRIVATE)
        val storageKey = "$owner:${state.appLanguage}".hashCode().toString()
        val retained = withContext(Dispatchers.IO) {
            if (System.currentTimeMillis() - prefs.getLong("$storageKey:time", 0) > 7L * 86_400_000) emptyList()
            else runCatching { visibleMetadataJson.decodeFromString(ListSerializer(MediaItem.serializer()), prefs.getString(storageKey, "[]").orEmpty()) }.getOrDefault(emptyList())
        }
        retained.filter { it.type == original.type && (it.id == original.id ||
            (original.tvPreviewTmdbId() != null && it.tvPreviewTmdbId() == original.tvPreviewTmdbId())) }.forEach(::cacheTvBrowsePreviewEnrichedItem)
        var current = withCachedTvBrowsePreviewEnrichment(original)
        if (!current.needsTvPreviewDetails()) return@LaunchedEffect
        // Disk-cached details are available without a focus dwell. Debounce
        // only the network work when the remote passes quickly through rows.
        delay(160)
        android.util.Log.i("TvPreview", "start screen=$screen type=${current.type} id=${current.id} tmdb=${state.tmdbCatalogueEnabled || state.tmdbEnrichmentEnabled} synopsis=${!current.overview.isNullOrBlank()} logo=${!current.logoUrl.isNullOrBlank()} cast=${current.cast.size}")
        var partial: MediaItem? = null
        suspend fun publish(value: MediaItem) {
            partial = value
            withContext(Dispatchers.Main.immediate) {
                if (settings.state.value.personalDataUserId == owner) cacheTvBrowsePreviewEnrichedItem(value)
            }
        }
        val detail = try { withTimeoutOrNull(8_000) {
            withContext(Dispatchers.IO) {
                ids.withPersonalDataScope {
                    if ((current.imdbId ?: current.id.extractImdbIdOrNull()).isNullOrBlank()) {
                        current = current.withSavedTvPreviewIdentity(org.koin.mp.KoinPlatform.getKoin().get<WatchlistRepository>().getAll())
                    }
                    if (state.tmdbCatalogueEnabled || state.tmdbEnrichmentEnabled) {
                        try {
                            withTimeoutOrNull(3_500) { loadTvPreviewDetails(current, RepositoryTvPreviewLookup(metadata)) }?.let {
                                current = it; publish(it)
                                if (!it.needsTvPreviewDetails()) return@withPersonalDataScope it
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Installed metadata providers can still supply the title. */ }
                    }
                    loadTvAddonPreviewDetails(current, addons.getEnabledAddons(), ClientTvAddonPreviewLookup(client, identities), ::publish)
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        ensureActive()
        if (settings.state.value.personalDataUserId != owner) return@LaunchedEffect
        val result = detail ?: partial
        if (result != null) {
            cacheTvBrowsePreviewEnrichedItem(result)
            val saved = (retained + result).associateBy { "${it.type}:${it.id}" }.values.toList().takeLast(100)
                .map { it.copy(cast = it.cast.take(6), seasons = emptyList(), overview = it.overview?.take(1200)) }
            withContext(Dispatchers.IO) {
                prefs.edit().putString(storageKey, visibleMetadataJson.encodeToString(ListSerializer(MediaItem.serializer()), saved))
                    .putLong("$storageKey:time", System.currentTimeMillis()).apply()
            }
        }
        android.util.Log.i("TvPreview", "complete screen=$screen type=${original.type} id=${original.id} synopsis=${!result?.overview.isNullOrBlank()} logo=${!result?.logoUrl.isNullOrBlank()} cast=${result?.cast?.size ?: 0}")
        checked[key] = System.currentTimeMillis()
        while (checked.size > 120) checked.remove(checked.keys.first())
    }
}
