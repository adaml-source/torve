package com.torve.presentation.player

import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktScrobbleBody
import com.torve.data.trakt.TraktScrobbleEpisode
import com.torve.data.trakt.TraktScrobbleMovie
import com.torve.data.trakt.TraktScrobbleShow
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.domain.model.MediaType

/**
 * Handles Trakt scrobbling during media playback.
 * Call start() when playback begins, pause() when paused, stop() when finished.
 */
class TraktScrobbler(
    private val traktClient: TraktClient,
    private val authorizedApi: TraktAuthorizedApi,
) {
    private var isScrobbling = false

    suspend fun start(
        accessToken: String,
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (traktClient.clientId.isBlank()) return
        if (type == MediaType.SERIES && (season == null || episode == null)) return
        try {
            val body = buildScrobbleBody(tmdbId, imdbId, type, progress, season, episode)
            authorizedApi.scrobbleStart(body)
            isScrobbling = true
        } catch (_: Exception) {
            // Scrobbling is best-effort
        }
    }

    suspend fun pause(
        accessToken: String,
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (!isScrobbling) return
        if (type == MediaType.SERIES && (season == null || episode == null)) return
        try {
            val body = buildScrobbleBody(tmdbId, imdbId, type, progress, season, episode)
            authorizedApi.scrobblePause(body)
        } catch (_: Exception) {}
    }

    suspend fun stop(
        accessToken: String,
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (!isScrobbling) return
        if (type == MediaType.SERIES && (season == null || episode == null)) {
            isScrobbling = false
            return
        }
        try {
            val body = buildScrobbleBody(tmdbId, imdbId, type, progress, season, episode)
            authorizedApi.scrobbleStop(body)
            isScrobbling = false
        } catch (_: Exception) {}
    }

    private fun buildScrobbleBody(
        tmdbId: Int,
        imdbId: String?,
        type: MediaType,
        progress: Double,
        season: Int?,
        episode: Int?,
    ): TraktScrobbleBody {
        val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
        return when (type) {
            MediaType.MOVIE -> TraktScrobbleBody(
                movie = TraktScrobbleMovie(ids = ids),
                progress = progress,
            )
            MediaType.SERIES -> TraktScrobbleBody(
                show = TraktScrobbleShow(ids = ids),
                episode = TraktScrobbleEpisode(season = season!!, number = episode!!),
                progress = progress,
            )
        }
    }
}
