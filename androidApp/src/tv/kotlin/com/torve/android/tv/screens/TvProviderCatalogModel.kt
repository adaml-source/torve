package com.torve.android.tv.screens

import com.torve.android.tv.components.TvContentRail
import com.torve.domain.model.MediaItem

internal enum class TvProviderCatalogBucket {
    POPULAR_MOVIES,
    POPULAR_SERIES,
    RECENT_MOVIES,
    RECENT_SERIES,
    TOP_RATED_MOVIES,
    TOP_RATED_SERIES,
}

internal data class TvProviderCatalogQuery(
    val bucket: TvProviderCatalogBucket,
    val mediaType: String,
    val sortBy: String,
    val minRating: Float? = null,
    val startYear: Int? = null,
    val endYear: Int? = null,
)

internal fun tvProviderCatalogQueryPlan(currentYear: Int): List<TvProviderCatalogQuery> = listOf(
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.POPULAR_MOVIES,
        mediaType = "movie",
        sortBy = "popularity.desc",
    ),
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.POPULAR_SERIES,
        mediaType = "tv",
        sortBy = "popularity.desc",
    ),
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.RECENT_MOVIES,
        mediaType = "movie",
        sortBy = "primary_release_date.desc",
        startYear = currentYear - 1,
        endYear = currentYear,
    ),
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.RECENT_SERIES,
        mediaType = "tv",
        sortBy = "first_air_date.desc",
        startYear = currentYear - 1,
        endYear = currentYear,
    ),
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.TOP_RATED_MOVIES,
        mediaType = "movie",
        sortBy = "vote_average.desc",
        minRating = 7f,
    ),
    TvProviderCatalogQuery(
        bucket = TvProviderCatalogBucket.TOP_RATED_SERIES,
        mediaType = "tv",
        sortBy = "vote_average.desc",
        minRating = 7f,
    ),
)

internal fun buildTvProviderCatalogRails(
    providerId: Int,
    providerName: String,
    region: String,
    itemsByBucket: Map<TvProviderCatalogBucket, List<MediaItem>>,
): List<TvContentRail> {
    fun bucket(key: TvProviderCatalogBucket): List<MediaItem> =
        itemsByBucket[key].orEmpty().providerCatalogItems()

    val recentAll = (
        bucket(TvProviderCatalogBucket.RECENT_MOVIES) +
            bucket(TvProviderCatalogBucket.RECENT_SERIES)
        )
        .providerCatalogItems()
        .sortedWith(providerRecentComparator)
    val popularMoviesAll = bucket(TvProviderCatalogBucket.POPULAR_MOVIES)
    val popularSeriesAll = bucket(TvProviderCatalogBucket.POPULAR_SERIES)
    val topRatedAll = (
        bucket(TvProviderCatalogBucket.TOP_RATED_MOVIES) +
            bucket(TvProviderCatalogBucket.TOP_RATED_SERIES)
        )
        .providerCatalogItems()
        .sortedWith(providerRatingComparator)

    val prefix = "streaming_catalog_${providerId}_${region.lowercase()}"
    val rails = mutableListOf<TvContentRail>()
    recentAll.takeIf { it.isNotEmpty() }?.let {
        rails += TvContentRail(
            key = "${prefix}_recent",
            title = "New & Recent on $providerName",
            items = it.take(TV_PROVIDER_CATALOG_RAIL_LIMIT),
            seeAllItems = it,
        )
    }
    popularMoviesAll.takeIf { it.isNotEmpty() }?.let {
        rails += TvContentRail(
            key = "${prefix}_popular_movies",
            title = "Popular Movies",
            items = it.take(TV_PROVIDER_CATALOG_RAIL_LIMIT),
            seeAllItems = it,
        )
    }
    popularSeriesAll.takeIf { it.isNotEmpty() }?.let {
        rails += TvContentRail(
            key = "${prefix}_popular_series",
            title = "Popular Series",
            items = it.take(TV_PROVIDER_CATALOG_RAIL_LIMIT),
            seeAllItems = it,
        )
    }
    topRatedAll.takeIf { it.isNotEmpty() }?.let {
        rails += TvContentRail(
            key = "${prefix}_top_rated",
            title = "Top Rated on $providerName",
            items = it.take(TV_PROVIDER_CATALOG_RAIL_LIMIT),
            seeAllItems = it,
        )
    }

    val genreSource = itemsByBucket.values
        .flatten()
        .providerCatalogItems()
        .sortedWith(providerPopularityComparator)
    val genreRails = genreSource
        .flatMap { item -> item.providerGenreIds().map { genreId -> genreId to item } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, items) -> items.providerCatalogItems() }
        .entries
        .filter { providerGenreLabel(it.key) != null && it.value.isNotEmpty() }
        .sortedBy { providerGenreLabel(it.key) }

    genreRails.forEach { (genreId, items) ->
        val label = providerGenreLabel(genreId) ?: return@forEach
        val sortedItems = items.sortedWith(providerPopularityComparator)
        rails += TvContentRail(
            key = "${prefix}_genre_$genreId",
            title = label,
            items = sortedItems.take(TV_PROVIDER_CATALOG_RAIL_LIMIT),
            seeAllItems = sortedItems,
        )
    }
    return rails
}

private fun List<MediaItem>.providerCatalogItems(): List<MediaItem> =
    asSequence()
        .filter { !it.posterUrl.isNullOrBlank() }
        .distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
        .toList()

private fun MediaItem.providerGenreIds(): List<Int> =
    (genreIds + genres.map { it.id }).distinct()

private val providerRecentComparator =
    compareByDescending<MediaItem> { it.releaseDate.orEmpty() }
        .thenByDescending { it.year ?: 0 }
        .thenByDescending { it.popularity ?: 0.0 }

private val providerRatingComparator =
    compareByDescending<MediaItem> { it.rating ?: 0.0 }
        .thenByDescending { it.voteCount ?: 0 }
        .thenByDescending { it.popularity ?: 0.0 }

private val providerPopularityComparator =
    compareByDescending<MediaItem> { it.popularity ?: 0.0 }
        .thenByDescending { it.rating ?: 0.0 }
        .thenByDescending { it.voteCount ?: 0 }

private fun providerGenreLabel(id: Int): String? = when (id) {
    28 -> "Action"
    12 -> "Adventure"
    16 -> "Animation"
    35 -> "Comedy"
    80 -> "Crime"
    99 -> "Documentaries"
    18 -> "Drama"
    10751 -> "Family"
    14 -> "Fantasy"
    36 -> "History"
    27 -> "Horror"
    10402 -> "Music"
    9648 -> "Mystery"
    10749 -> "Romance"
    878 -> "Science Fiction"
    10770 -> "TV Movies"
    53 -> "Thrillers"
    10752 -> "War"
    37 -> "Westerns"
    10759 -> "Action & Adventure"
    10762 -> "Kids"
    10763 -> "News"
    10764 -> "Reality"
    10765 -> "Sci-Fi & Fantasy"
    10766 -> "Soap"
    10767 -> "Talk"
    10768 -> "War & Politics"
    else -> null
}

private const val TV_PROVIDER_CATALOG_RAIL_LIMIT = 24
