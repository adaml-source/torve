package com.torve.data.simkl

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SimklLibraryResponse(
    val movies: List<SimklLibraryItem> = emptyList(),
    val shows: List<SimklLibraryItem> = emptyList(),
    val anime: List<SimklLibraryItem> = emptyList(),
) {
    fun items(type: String): List<SimklLibraryItem> = when (type) {
        "movies" -> movies
        "anime" -> anime
        else -> shows
    }.map { it.copy(kind = type) }
}

@Serializable
data class SimklLibraryItem(
    val kind: String = "shows",
    val status: String = "plantowatch",
    val movie: SimklLibraryMedia? = null,
    val show: SimklLibraryMedia? = null,
    val ids: JsonObject = JsonObject(emptyMap()),
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("added_to_watchlist_at") val addedAt: String? = null,
    @SerialName("anime_type") val animeType: String? = null,
    val seasons: List<SimklLibrarySeason> = emptyList(),
    @SerialName("next_to_watch_info") val nextEpisode: SimklNextEpisode? = null,
) {
    val media get() = movie ?: show
    val simklId get() = (media?.ids?.get("simkl") ?: ids["simkl"])?.jsonPrimitive?.contentOrNull
    val key get() = "$kind:$simklId"
    fun toMedia(): MediaItem? {
        val info = media ?: return null
        val tmdb = info.ids["tmdb"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val movieType = kind == "movies" || (kind == "anime" && animeType == "movie")
        return MediaItem(id = tmdb.toString(), tmdbId = tmdb, imdbId = info.ids["imdb"]?.jsonPrimitive?.contentOrNull,
            type = if (movieType) MediaType.MOVIE else MediaType.SERIES, title = info.title,
            year = info.year, runtime = info.runtime,
            posterUrl = info.poster?.takeIf { it.matches(Regex("[A-Za-z0-9/]+")) }
                ?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_m.webp&q=90" })
    }
    fun watchedEpisodes(): List<SimklWatchedEpisode> = seasons.flatMap { season ->
        season.episodes.mapNotNull { ep ->
            // An episode appearing in a full library response is not proof it was watched.
            val timestamp = ep.watchedAt?.let(::simklTimestampMs) ?: return@mapNotNull null
            val number = if (kind == "anime") ep.tvdb ?: return@mapNotNull null
                else SimklEpisodeCoordinates(season.number, ep.number)
            if (number.season < 0 || number.episode <= 0) null
            else SimklWatchedEpisode(number.season, number.episode, timestamp, ep.runtime)
        }
    }.distinctBy { it.season to it.episode }
}

@Serializable
data class SimklLibraryMedia(val title: String = "", val year: Int? = null, val poster: String? = null,
    val runtime: Int? = null, val ids: JsonObject = JsonObject(emptyMap()))
@Serializable
data class SimklLibrarySeason(val number: Int = 1, val episodes: List<SimklLibraryEpisode> = emptyList())
@Serializable
data class SimklLibraryEpisode(val number: Int, @SerialName("watched_at") val watchedAt: String? = null,
    val tvdb: SimklEpisodeCoordinates? = null, val runtime: Int? = null)
@Serializable
data class SimklEpisodeCoordinates(val season: Int, val episode: Int)
@Serializable
data class SimklNextEpisode(val title: String = "", val season: Int? = null, val episode: Int = 0, val date: String? = null)
data class SimklWatchedEpisode(val season: Int, val episode: Int, val watchedAtMs: Long, val runtime: Int?)
internal fun simklTimestampMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()

internal fun simklRemovedChanged(before: JsonObject?, after: JsonObject, type: String): Boolean {
    val key = if (type == "shows") "tv_shows" else type
    return after[key]?.jsonObject?.get("removed_from_list") != before?.get(key)?.jsonObject?.get("removed_from_list")
}

@Serializable
internal data class SimklLibraryCache(val activities: JsonObject, val items: List<SimklLibraryItem>)
