package com.torve.desktop.ui.v2.movies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun rememberExtraRails(
    metadataRepo: MetadataRepository,
    mediaType: String, // "movie" or "tv"
): Map<String, List<MediaItem>> {
    val state = remember(mediaType) { mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()) }
    LaunchedEffect(mediaType) {
        val defs = if (mediaType == "movie") movieExtraRails else tvExtraRails
        val loaded = coroutineScope {
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
        state.value = loaded
    }
    return state.value
}
