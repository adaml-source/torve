package com.streamvault.data.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Int,
    val expiresIn: Int,
)

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val createdAt: Long,
)

sealed class TraktPollResult {
    data class Success(val tokens: TraktTokens) : TraktPollResult()
    data object Pending : TraktPollResult()
    data object SlowDown : TraktPollResult()
    data object Expired : TraktPollResult()
    data object AlreadyUsed : TraktPollResult()
    data object Denied : TraktPollResult()
    data class Error(val message: String) : TraktPollResult()
}

data class TraktUser(
    val username: String,
    val name: String? = null,
    val vip: Boolean = false,
    val joined: String? = null,
    val avatar: String? = null,
)

// --- API response models ---

@Serializable
data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
)

@Serializable
data class TraktUserResponse(
    val username: String = "",
    val name: String? = null,
    val vip: Boolean = false,
    @SerialName("joined_at") val joinedAt: String? = null,
    val images: TraktImages? = null,
)

@Serializable
data class TraktImages(
    val avatar: TraktImageSize? = null,
)

@Serializable
data class TraktImageSize(
    val full: String? = null,
)

@Serializable
data class TraktIds(
    val trakt: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
data class TraktHistoryMovie(
    val ids: TraktIds,
)

@Serializable
data class TraktHistoryShow(
    val ids: TraktIds,
)

@Serializable
data class TraktHistoryBody(
    val movies: List<TraktHistoryMovie>? = null,
    val shows: List<TraktHistoryShow>? = null,
)

@Serializable
data class TraktScrobbleBody(
    val movie: TraktScrobbleMovie? = null,
    val show: TraktScrobbleShow? = null,
    val episode: TraktScrobbleEpisode? = null,
    val progress: Double = 0.0,
)

@Serializable
data class TraktScrobbleMovie(
    val ids: TraktIds,
)

@Serializable
data class TraktScrobbleShow(
    val ids: TraktIds,
)

@Serializable
data class TraktScrobbleEpisode(
    val season: Int,
    val number: Int,
)

// Stats
data class TraktStats(
    val moviesWatched: Int = 0,
    val episodesWatched: Int = 0,
    val showsWatched: Int = 0,
    val minutesWatched: Int = 0,
)

@Serializable
data class TraktStatsResponse(
    val movies: TraktStatsMovies? = null,
    val episodes: TraktStatsEpisodes? = null,
    val shows: TraktStatsShows? = null,
)

@Serializable
data class TraktStatsMovies(
    val plays: Int = 0,
    val watched: Int = 0,
    val minutes: Int = 0,
)

@Serializable
data class TraktStatsEpisodes(
    val plays: Int = 0,
    val watched: Int = 0,
    val minutes: Int = 0,
)

@Serializable
data class TraktStatsShows(
    val watched: Int = 0,
)

// Remove from history
@Serializable
data class TraktRemoveHistoryBody(
    val movies: List<TraktHistoryMovie>? = null,
    val shows: List<TraktHistoryShow>? = null,
)

// Watchlist (reuses TraktHistoryMovie/Show for items)
@Serializable
data class TraktWatchlistBody(
    val movies: List<TraktHistoryMovie>? = null,
    val shows: List<TraktHistoryShow>? = null,
)

// Watchlist GET response
@Serializable
data class TraktWatchlistItemResponse(
    val rank: Int = 0,
    val id: Long = 0,
    @SerialName("listed_at") val listedAt: String = "",
    val type: String = "",
    val movie: TraktWatchlistMediaResponse? = null,
    val show: TraktWatchlistMediaResponse? = null,
)

@Serializable
data class TraktWatchlistMediaResponse(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIds? = null,
)

// Watch history response
@Serializable
data class TraktHistoryResponse(
    val id: Long = 0,
    @SerialName("watched_at") val watchedAt: String = "",
    val action: String = "",
    val type: String = "",
    val movie: TraktWatchlistMediaResponse? = null,
    val show: TraktWatchlistMediaResponse? = null,
    val episode: TraktPlaybackEpisode? = null,
)

// Playback progress (in-progress items)
@Serializable
data class TraktPlaybackResponse(
    val progress: Double = 0.0,
    @SerialName("paused_at") val pausedAt: String = "",
    val id: Long = 0,
    val type: String = "",
    val movie: TraktWatchlistMediaResponse? = null,
    val show: TraktWatchlistMediaResponse? = null,
    val episode: TraktPlaybackEpisode? = null,
)

@Serializable
data class TraktPlaybackEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
    val ids: TraktIds? = null,
)

// Calendar
data class TraktCalendarEpisode(
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val firstAired: String,
    val showTmdbId: Int? = null,
)

@Serializable
data class TraktCalendarResponse(
    @SerialName("first_aired") val firstAired: String = "",
    val episode: TraktCalendarEpisodeResponse? = null,
    val show: TraktCalendarShowResponse? = null,
)

@Serializable
data class TraktCalendarEpisodeResponse(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
)

@Serializable
data class TraktCalendarShowResponse(
    val title: String = "",
    val ids: TraktIds? = null,
)
