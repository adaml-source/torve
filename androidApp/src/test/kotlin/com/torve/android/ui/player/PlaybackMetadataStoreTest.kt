package com.torve.android.ui.player

import com.torve.domain.model.*
import com.torve.presentation.detail.DetailUiState
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataStoreTest {
    @Test fun providerOwnedMovieKeepsItsOriginalDescription() {
        val movie = MediaItem(id = "provider:movie:42", type = MediaType.MOVIE, title = "Provider title", overview = "The provider plot")
        PlaybackMetadataStore.stage("movie-owner", DetailUiState(mediaItem = movie))
        val state = PlaybackMetadataStore.snapshot("movie-owner", movie.id, MediaType.MOVIE)!!
        assertNull(state.mediaItem!!.tmdbId)
        assertEquals("The provider plot", PlaybackMetadataStore.synopsis("movie-owner", movie.id, MediaType.MOVIE, null, null)!!.overview)
    }

    @Test fun snapshotsCannotCrossOwnersOrMediaTypes() {
        val movie = MediaItem(id = "42", type = MediaType.MOVIE, title = "Movie")
        PlaybackMetadataStore.stage("first-owner", DetailUiState(mediaItem = movie))
        assertNull(PlaybackMetadataStore.snapshot("second-owner", "42", MediaType.MOVIE))
        assertNull(PlaybackMetadataStore.snapshot("first-owner", "42", MediaType.SERIES))
        PlaybackMetadataStore.stage("second-owner", DetailUiState(mediaItem = movie))
        assertNull(PlaybackMetadataStore.snapshot("first-owner", "42", MediaType.MOVIE))
    }

    @Test fun episodeSynopsisNeverFallsBackToTheShowOrAnotherEpisode() {
        val show = MediaItem(id = "provider:show", type = MediaType.SERIES, title = "Show", overview = "Show plot")
        val season = Season(seasonNumber = 2, episodeCount = 4, episodes = listOf(Episode(episodeNumber = 3, overview = "Exact episode")))
        PlaybackMetadataStore.stage("episode-owner", DetailUiState(mediaItem = show, seasonDetail = season))
        assertEquals("Exact episode", PlaybackMetadataStore.synopsis("episode-owner", show.id, MediaType.SERIES, 2, 3)!!.overview)
        assertEquals("", PlaybackMetadataStore.synopsis("episode-owner", show.id, MediaType.SERIES, 2, 4)!!.overview)
    }
}
