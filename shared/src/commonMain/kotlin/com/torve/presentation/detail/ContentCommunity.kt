package com.torve.presentation.detail

import com.torve.data.metadata.*
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ContentFact(val label: String, val value: String)
@Serializable data class ContentCommentUser(val username: String = "", val name: String? = null)
@Serializable data class ContentComment(
    val id: Long, val comment: String = "", val spoiler: Boolean = false,
    @SerialName("created_at") val createdAt: String = "", val likes: Int = 0,
    @SerialName("user_rating") val userRating: Int? = null,
    val user: ContentCommentUser = ContentCommentUser(),
) {
    val containsSpoilers: Boolean get() = spoiler || comment.contains("[spoiler]", ignoreCase = true)
    val author: String get() = user.name?.takeIf { it.isNotBlank() } ?: user.username.ifBlank { "Trakt member" }
    val permalink: String get() = "https://trakt.tv/comments/$id"
}
data class ContentCommentPage(val items: List<ContentComment>, val page: Int, val hasMore: Boolean)

/** Public, read-only community data. No user history or access token is sent. */
class ContentCommunity(private val tmdb: TmdbApiClient, private val trakt: TraktClient) {
    @Throws(Exception::class)
    suspend fun facts(item: MediaItem): List<ContentFact> {
        val id = item.tmdbId ?: return baseFacts(item)
        return if (item.type == MediaType.SERIES) tvFacts(tmdb.getTvDetail(id)) else movieFacts(tmdb.getMovieDetail(id))
    }

    @Throws(Exception::class)
    suspend fun comments(item: MediaItem, page: Int): ContentCommentPage {
        var imdb = item.imdbId
        if (imdb.isNullOrBlank() && item.tmdbId != null) {
            imdb = if (item.type == MediaType.SERIES) tmdb.getTvDetail(item.tmdbId).externalIds?.imdbId
                else tmdb.getMovieDetail(item.tmdbId).let { it.imdbId ?: it.externalIds?.imdbId }
        }
        if (imdb.isNullOrBlank() || !Regex("tt[0-9]+").matches(imdb)) return ContentCommentPage(emptyList(), 1, false)
        return trakt.getContentComments(imdb, item.type == MediaType.SERIES, page.coerceAtLeast(1))
    }
}

internal fun baseFacts(item: MediaItem): List<ContentFact> = buildList {
    fact("Tagline", item.tagline); fact("Status", item.status)
    fact(if (item.type == MediaType.SERIES) "First aired" else "Release date", item.releaseDate)
    fact("Studios", item.studios.joinToString(", ") { it.name })
}
private fun MutableList<ContentFact>.fact(label: String, value: String?) {
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ContentFact(label, it)) }
}
internal fun movieFacts(movie: TmdbMovie): List<ContentFact> = buildList {
    fact("Tagline", movie.tagline); fact("Status", movie.status); fact("Release date", movie.releaseDate)
    fact("Original title", movie.originalTitle); fact("Studios", movie.productionCompanies.joinToString(", ") { it.name })
    movie.budget?.takeIf { it > 0 }?.let { fact("Budget (USD)", it.toString()) }
    movie.revenue?.takeIf { it > 0 }?.let { fact("Revenue (USD)", it.toString()) }
    fact("Original language", movie.originalLanguage?.uppercase())
    fact("Origin country", movie.productionCountries.joinToString(", ") { it.name.ifBlank { it.code } })
}
internal fun tvFacts(show: TmdbTv): List<ContentFact> = buildList {
    fact("Tagline", show.tagline); fact("Status", show.status); fact("First aired", show.firstAirDate)
    fact("Original title", show.originalName)
    fact("Studios", show.productionCompanies.joinToString(", ") { it.name })
    fact("Networks", show.networks.joinToString(", ") { it.name })
    fact("Original language", show.originalLanguage?.uppercase())
    fact("Origin country", show.productionCountries.joinToString(", ") { it.name.ifBlank { it.code } }.ifBlank { show.originCountry.joinToString(", ") })
}
