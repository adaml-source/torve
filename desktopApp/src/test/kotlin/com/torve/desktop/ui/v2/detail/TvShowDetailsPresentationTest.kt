package com.torve.desktop.ui.v2.detail

import com.torve.desktop.search.DesktopSearchDetailUiState
import com.torve.domain.model.Episode
import com.torve.domain.model.Genre
import com.torve.domain.model.MediaCompany
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvShowDetailsPresentationTest {

    @Test
    fun selected_episode_uses_loaded_selected_season_and_episode() {
        val episode = episode(number = 4, name = "Selected Episode")
        val season = season(number = 2, episodes = listOf(episode(number = 1), episode))
        val item = show(seasons = listOf(season(number = 1, episodes = listOf(episode(number = 1))), season))
        val state = DesktopSearchDetailUiState(
            detailItem = item,
            selectedSeasonNumber = 2,
            selectedSeason = season,
            selectedEpisodeNumber = 4,
        )

        assertEquals(TvEpisodeSelection(season, episode), selectedTvEpisodeContext(item, state))
    }

    @Test
    fun selected_episode_falls_back_to_first_episode_in_selected_season() {
        val first = episode(number = 1, name = "Pilot")
        val season = season(number = 1, episodes = listOf(first, episode(number = 2)))
        val item = show(seasons = listOf(season))
        val state = DesktopSearchDetailUiState(
            detailItem = item,
            selectedSeasonNumber = 1,
            selectedSeason = season,
            selectedEpisodeNumber = 99,
        )

        assertEquals(TvEpisodeSelection(season, first), selectedTvEpisodeContext(item, state))
    }

    @Test
    fun episode_hub_rows_only_include_available_real_metadata() {
        val episode = episode(
            number = 2,
            name = "Trouble Don't Last Always",
            airDate = "2019-06-16",
            runtime = 57,
            rating = 8.1,
        )
        val season = season(number = 1, name = "Season One", episodes = listOf(episode))
        val item = show(
            status = "Returning Series",
            releaseDate = "2019-06-16",
            year = 2019,
            studios = listOf(MediaCompany(id = 1, name = "HBO")),
            genres = listOf(Genre(id = 18, name = "Drama")),
            seasons = listOf(season),
        )

        assertEquals(
            listOf(
                MovieDetailInfoRowModel("Selected Episode", "S1 E2"),
                MovieDetailInfoRowModel("Season", "Season One"),
                MovieDetailInfoRowModel("Episode", "Trouble Don't Last Always"),
                MovieDetailInfoRowModel("Air Date", "2019-06-16"),
                MovieDetailInfoRowModel("Runtime", "57m"),
                MovieDetailInfoRowModel("Rating", "8.1/10"),
                MovieDetailInfoRowModel("Release Date", "2019-06-16"),
                MovieDetailInfoRowModel("Network", "HBO"),
                MovieDetailInfoRowModel("Genres", "Drama"),
            ),
            episodeHubInfoRows(item, TvEpisodeSelection(season, episode)),
        )
    }

    @Test
    fun locked_tv_content_does_not_expose_backdrop_artwork() {
        val locked = show(
            backdropUrl = "https://image.example/show-backdrop.jpg",
            posterUrl = "https://image.example/show-poster.jpg",
            isContentPlaceholder = true,
        )

        assertNull(movieBackdropUrl(locked))
    }

    private fun show(
        backdropUrl: String? = null,
        posterUrl: String? = null,
        isContentPlaceholder: Boolean = false,
        status: String? = null,
        releaseDate: String? = null,
        year: Int? = null,
        studios: List<MediaCompany> = emptyList(),
        genres: List<Genre> = emptyList(),
        seasons: List<Season> = emptyList(),
    ): MediaItem = MediaItem(
        id = "show-1",
        type = MediaType.SERIES,
        title = "Test Show",
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        isContentPlaceholder = isContentPlaceholder,
        status = status,
        releaseDate = releaseDate,
        year = year,
        studios = studios,
        genres = genres,
        seasons = seasons,
    )

    private fun season(
        number: Int,
        name: String? = null,
        episodes: List<Episode> = emptyList(),
    ): Season = Season(
        seasonNumber = number,
        episodeCount = episodes.size,
        name = name,
        episodes = episodes,
    )

    private fun episode(
        number: Int,
        name: String = "Episode $number",
        airDate: String? = null,
        runtime: Int? = null,
        rating: Double = 0.0,
    ): Episode = Episode(
        episodeNumber = number,
        name = name,
        airDate = airDate,
        runtime = runtime,
        rating = rating,
    )
}
