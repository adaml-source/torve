package com.streamvault.data.metadata

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.ShelfType
import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MetadataRepositoryImpl(
    private val api: TmdbApiClient,
) : MetadataRepository {

    override suspend fun getTrending(type: String, page: Int): List<MediaItem> {
        return api.getTrending(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getPopular(type: String, page: Int): List<MediaItem> {
        return if (type == "tv") {
            api.getPopular(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
        } else {
            api.getPopular(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
        }
    }

    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> {
        return api.getTopRated(type, page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getUpcoming(page: Int): List<MediaItem> {
        return api.getUpcoming(page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getNowPlaying(page: Int): List<MediaItem> {
        return api.getNowPlaying(page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getAiringToday(page: Int): List<MediaItem> {
        return api.getAiringToday(page).results.map { TmdbMappers.tvToMediaItem(it) }
    }

    override suspend fun searchMulti(query: String, page: Int): List<MediaItem> {
        return api.searchMulti(query, page).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { TmdbMappers.multiToMediaItem(it) }
    }

    override suspend fun getDetail(type: String, id: Int): MediaItem {
        return if (type == "movie") {
            TmdbMappers.movieToMediaItem(api.getMovieDetail(id))
        } else {
            TmdbMappers.tvToMediaItem(api.getTvDetail(id))
        }
    }

    override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> {
        return api.getSimilar(type, id, page).results.map { TmdbMappers.movieToMediaItem(it) }
    }

    override suspend fun getHomeShelves(): List<CatalogShelf> = coroutineScope {
        val trendingMovies = async { api.getTrending("movie") }
        val trendingTv = async { api.getTrending("tv") }
        val nowPlaying = async { api.getNowPlaying() }
        val popularMovies = async { api.getPopular("movie") }
        val upcoming = async { api.getUpcoming() }
        val popularTv = async { api.getAiringToday() }
        val topRated = async { api.getTopRated("movie") }
        val airingToday = async { api.getAiringToday() }

        listOf(
            CatalogShelf(
                id = "trending-movies",
                title = "Trending Movies",
                items = trendingMovies.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "trending-tv",
                title = "Trending TV Shows",
                items = trendingTv.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "now-playing",
                title = "Now Playing",
                items = nowPlaying.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.LANDSCAPE,
            ),
            CatalogShelf(
                id = "popular-movies",
                title = "Popular Movies",
                items = popularMovies.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "upcoming",
                title = "Upcoming",
                items = upcoming.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "popular-tv",
                title = "Popular TV Shows",
                items = popularTv.await().results.map { TmdbMappers.tvToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "top-rated",
                title = "Top Rated",
                items = topRated.await().results.map { TmdbMappers.movieToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
            CatalogShelf(
                id = "airing-today",
                title = "Airing Today",
                items = airingToday.await().results.map { TmdbMappers.tvToMediaItem(it) },
                type = ShelfType.POSTER,
            ),
        )
    }
}
