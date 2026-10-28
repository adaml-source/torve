package com.torve.presentation.apple

import com.torve.data.metadata.*
import com.torve.domain.model.*
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.PersonFilmographyPolicy
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleCommunityFeaturesTest {
    private fun movie(id: String, title: String = id) = MediaItem(id, type = MediaType.MOVIE, title = title)

    @Test fun castMetadataSurvivesDecodingAndMapping() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<TmdbPersonCastCredit>(
            """{"id":7,"name":"Interview","media_type":"tv","character":"Self - Guest","genre_ids":[10767],"vote_count":200,"vote_average":8.4}""")
        val item = TmdbMappers.personCreditToMediaItem(dto)
        assertEquals("Self - Guest", item.creditCharacter)
        assertEquals(listOf(10767), item.genreIds)
        assertEquals(200, item.voteCount)
        assertEquals(8.4, item.rating)
        assertTrue(PersonFilmographyPolicy.isAppearance(item))
    }

    @Test fun interviewsRealityDocumentariesAndCrewAreSeparateFromRoles() {
        val role = movie("1").copy(creditCharacter = "Detective")
        val self = movie("2").copy(creditCharacter = "Herself (archive footage)")
        val talk = movie("3").copy(type = MediaType.SERIES, genreIds = listOf(10767))
        val reality = movie("4").copy(type = MediaType.SERIES, genreIds = listOf(10764))
        val documentary = movie("5").copy(genreIds = listOf(99))
        val crew = movie("6").copy(creditIsCrew = true, creditJob = "Producer")
        val all = listOf(role, self, talk, reality, documentary, crew)
        assertEquals(listOf(role), PersonFilmographyPolicy.items(all, "roles", "popular"))
        assertEquals(5, PersonFilmographyPolicy.items(all, "other", "popular").size)
    }

    @Test fun uncertainCreditsAreNotSilentlyDiscarded() {
        assertEquals(1, PersonFilmographyPolicy.items(listOf(movie("1")), "roles", "popular").size)
        assertFalse(PersonFilmographyPolicy.isAppearance(movie("2").copy(creditCharacter = "Selfridge")))
    }

    @Test fun movieAndTvIdsDoNotCollide() {
        val movie = movie("7").copy(tmdbId = 7)
        val tv = movie.copy(type = MediaType.SERIES)
        assertEquals(2, PersonFilmographyPolicy.items(listOf(movie, tv), "roles", "popular").size)
        assertEquals(listOf(tv), PersonFilmographyPolicy.items(listOf(movie, tv), "tv", "popular"))
    }

    @Test fun newestAndRatingSortsHandleMissingValuesAndTies() {
        val a = movie("1").copy(releaseDate = "2026-01-01", rating = 8.0, voteCount = 2)
        val b = movie("2").copy(releaseDate = "2026-10-01", rating = 8.0, voteCount = 500)
        val c = movie("3")
        assertEquals(listOf(b, a, c), PersonFilmographyPolicy.items(listOf(c, a, b), "roles", "newest"))
        assertEquals(listOf(b, a, c), PersonFilmographyPolicy.items(listOf(c, a, b), "roles", "rating"))
    }

    @Test fun collectionIdentitySurvivesMovieMappingAndMetadataMerge() {
        val tmdb = TmdbMappers.movieToMediaItem(TmdbMovie(8, title = "Film", collection = TmdbCollectionRef(99, "Franchise")))
        assertEquals(99, tmdb.collectionId)
        assertEquals("Franchise", tmdb.collectionName)
        assertEquals(99, listOf(movie("8").copy(tmdbId = 8), tmdb).dedupeByStableKey().single().collectionId)
    }

    @Test fun collectionBookmarksKeepWholeGroupsAndSourceIdentity() {
        val first = AppleCollectionBookmark("tmdb", 1, "First")
        val mdb = AppleCollectionBookmark("mdblist", 1, "Curated universe")
        val current = CollectionBookmarkPolicy.save(listOf(first), mdb)
        assertEquals(2, current.size)
        val updated = CollectionBookmarkPolicy.save(current, first.copy(name = "Renamed"))
        assertEquals(2, updated.size)
        assertEquals("Renamed", updated.single { it.source == "tmdb" }.name)
        assertFailsWith<IllegalArgumentException> { CollectionBookmarkPolicy.save(current, first.copy(sourceId = 0)) }
    }

    @Test fun synopsisUsesTheExactEpisodeNotSeriesOrAnotherSeason() {
        val first = Season(1, episodeCount = 1, episodes = listOf(Episode(1, overview = "Season one plot")))
        val second = Season(2, episodeCount = 2, episodes = listOf(Episode(1, overview = "Correct plot"), Episode(2, overview = "Future spoiler")))
        val show = movie("10", "Series").copy(type = MediaType.SERIES, overview = "Series synopsis", seasons = listOf(first, second))
        val state = DetailUiState(mediaItem = show, seasonDetail = first)
        assertEquals("Correct plot", ApplePlaybackSynopsisPolicy.from(state, 2, 1)?.overview)
        assertEquals("", ApplePlaybackSynopsisPolicy.from(state, 2, 9)?.overview)
        assertNull(ApplePlaybackSynopsisPolicy.from(state, null, null))
    }

    @Test fun collectionPreferencesSyncButServiceKeysDoNot() {
        val policy = com.torve.data.account.AccountSettingsSyncPolicy
        assertTrue(policy.isSharedKey("saved_media_collections_v1"))
        assertTrue(policy.isSharedKey("mdblist_shelves"))
        assertFalse(policy.isSharedKey("mdblist_api_key"))
    }

    @Test fun collectionRequestsRespectOptionalMetadataSelection() {
        assertTrue(TmdbExtensionSelection(enrichment = true).permits("/collection/99"))
        assertFalse(TmdbExtensionSelection().permits("/collection/99"))
        assertFalse(TmdbExtensionSelection(enrichment = true).permits("/collection/search"))
    }

    @Test fun movieSynopsisDoesNotRequireEpisodeIdentity() {
        val state = DetailUiState(mediaItem = movie("1").copy(overview = "Movie plot"))
        assertEquals("Movie plot", ApplePlaybackSynopsisPolicy.from(state, null, null)?.overview)
    }
}
