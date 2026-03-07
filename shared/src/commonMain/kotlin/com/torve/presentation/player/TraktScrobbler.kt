package com.torve.presentation.player

import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktScrobbleBody
import com.torve.data.trakt.TraktScrobbleEpisode
import com.torve.data.trakt.TraktScrobbleMovie
import com.torve.data.trakt.TraktScrobbleShow
import com.torve.domain.model.MediaType

/**
 * Handles Trakt scrobbling during media playback.
 * Call start() when playback begins, pause() when paused, stop() when finished.
 */
class TraktScrobbler(
    private val traktClient: TraktClient,
) {
    private var isScrobbling = false

    suspend fun start(
        accessToken: String,
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (accessToken.isBlank() || traktClient.clientId.isBlank()) return
        try {
            val body = buildScrobbleBody(tmdbId, type, progress, season, episode)
            traktClient.scrobbleStart(accessToken, body)
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
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (!isScrobbling || accessToken.isBlank()) return
        try {
            val body = buildScrobbleBody(tmdbId, type, progress, season, episode)
            traktClient.scrobblePause(accessToken, body)
        } catch (_: Exception) {}
    }

    suspend fun stop(
        accessToken: String,
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (!isScrobbling || accessToken.isBlank()) return
        try {
            val body = buildScrobbleBody(tmdbId, type, progress, season, episode)
            traktClient.scrobbleStop(accessToken, body)
            isScrobbling = false
        } catch (_: Exception) {}
    }

    private fun buildScrobbleBody(
        tmdbId: Int,
        type: MediaType,
        progress: Double,
        season: Int?,
        episode: Int?,
    ): TraktScrobbleBody {
        val ids = TraktIds(tmdb = tmdbId)
        return when (type) {
            MediaType.MOVIE -> TraktScrobbleBody(
                movie = TraktScrobbleMovie(ids = ids),
                progress = progress,
            )
            MediaType.SERIES -> TraktScrobbleBody(
                show = TraktScrobbleShow(ids = ids),
                episode = if (season != null && episode != null) {
                    TraktScrobbleEpisode(season = season, number = episode)
                } else null,
                progress = progress,
            )
        }
    }
}
