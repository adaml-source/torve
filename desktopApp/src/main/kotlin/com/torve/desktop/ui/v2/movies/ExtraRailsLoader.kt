package com.torve.desktop.ui.v2.movies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** (sectionId, displayTitle, genreId-or-null, specialKind-or-null). */
data class ExtraRailDef(
    val sectionId: String,
    val title: String,
    val genreId: Int? = null,
    val special: String? = null, // "now_playing", "upcoming"
)

val movieExtraRails: List<ExtraRailDef> = listOf(
    ExtraRailDef("NOW_PLAYING", "Now Playing", special = "now_playing"),
    ExtraRailDef("UPCOMING", "Upcoming", special = "upcoming"),
    ExtraRailDef("MOVIE_GENRE_28", "Action Movies", genreId = 28),
    ExtraRailDef("MOVIE_GENRE_35", "Comedy Movies", genreId = 35),
    ExtraRailDef("MOVIE_GENRE_18", "Drama Movies", genreId = 18),
    ExtraRailDef("MOVIE_GENRE_878", "Sci-Fi Movies", genreId = 878),
    ExtraRailDef("MOVIE_GENRE_27", "Horror Movies", genreId = 27),
)

val tvExtraRails: List<ExtraRailDef> = listOf(
    ExtraRailDef("TV_GENRE_18", "Drama Shows", genreId = 18),
    ExtraRailDef("TV_GENRE_35", "Comedy Shows", genreId = 35),
    ExtraRailDef("TV_GENRE_10765", "Sci-Fi & Fantasy", genreId = 10765),
    ExtraRailDef("TV_GENRE_16", "Animation", genreId = 16),
    ExtraRailDef("TV_GENRE_99", "Documentaries", genreId = 99),
    ExtraRailDef("TV_GENRE_10759", "Action & Adventure", genreId = 10759),
    ExtraRailDef("TV_GENRE_80", "Crime Shows", genreId = 80),
)

/**
 * Loads the "extra rails" (genre-specific + Now Playing / Upcoming) and
 * enriches them with MDBList/IMDb/Trakt ratings the same way
 * CatalogViewModel enriches the first three rails (Trending / Popular /
 * Top Rated). Earlier this skipped enrichment entirely, so rails 4+
 * showed posters with TMDB-only metadata while rails 1-3 carried the
 * full pill set -- the visible asymmetry the user reported.
 *
 * Two-phase to match the ViewModel's behaviour:
 *   1. Synchronous cache hydrate -- fast, fills pills on first paint
 *      from local RatingsCacheRepository.
 *   2. Async MDBList enrichment -- replaces the rail's items as fresh
 *      ratings come back. Recomposition picks up the new state.
 */
@Composable
fun rememberExtraRails(
    metadataRepo: MetadataRepository,
    mediaType: String, // "movie" or "tv"
): Map<String, List<MediaItem>> {
    val state = remember(mediaType) { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }

    // Resolved lazily via Koin so the function still works in previews
    // / tests where these dependencies aren't wired.
    val ratingsEnricher = remember {
        runCatching {
            org.koin.mp.KoinPlatform.getKoin().get<RatingsEnricher>()
        }.getOrNull()
    }
    val secretStore = remember {
        runCatching {
            org.koin.mp.KoinPlatform.getKoin().get<IntegrationSecretStore>()
        }.getOrNull()
    }

    LaunchedEffect(mediaType) {
        val defs = if (mediaType == "movie") movieExtraRails else tvExtraRails
        val raw = coroutineScope {
            defs.map { def ->
                async(Dispatchers.IO) {
                    val items = runCatching {
                        when {
                            def.special == "now_playing" -> metadataRepo.getNowPlaying(1)
                            def.special == "upcoming" -> metadataRepo.getUpcoming(1)
                            def.genreId != null -> metadataRepo.discover(
                                type = mediaType,
                                withGenres = def.genreId.toString(),
                                sortBy = "popularity.desc",
                                page = 1,
                            ).items
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                    def.sectionId to items
                }
            }.awaitAll().toMap()
        }

        // Phase 1: fast cache hydrate. Render with whatever ratings
        // we already have locally so pills don't pop in late.
        val hydrated = if (ratingsEnricher != null) {
            raw.mapValues { (_, items) -> ratingsEnricher.hydrateListFromCache(items) }
        } else {
            raw
        }
        state.value = hydrated

        // Phase 2: async enrichment from MDBList. Same key resolution
        // as CatalogViewModel's enrichAndUpdateItems().
        if (ratingsEnricher != null) {
            val apiKey = runCatching {
                secretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)

            // Enrich each rail in parallel. Each rail updates the
            // shared state map as soon as it's done, so pills appear
            // progressively rather than waiting on the slowest rail.
            coroutineScope {
                raw.forEach { (sectionId, items) ->
                    async(Dispatchers.IO) {
                        val enriched = runCatching {
                            ratingsEnricher.enrichList(items, apiKey)
                        }.getOrDefault(items)
                        state.value = state.value + (sectionId to enriched)
                    }
                }
            }
        }
    }

    return state.value
}
