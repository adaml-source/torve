package com.torve.android.tv.components

import com.torve.domain.model.*
import com.torve.android.ui.community.PersonalAppearance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvPreviewPresentationTest {
    private val sparse = MediaItem("trakt:123", type = MediaType.MOVIE, title = "Saved film", year = 2024,
        ratings = MediaRatings(imdbScore = 8.3f, rottenTomatoesScore = 94))
    private class Lookup : TvPreviewLookup {
        var results = emptyList<MediaItem>()
        var found: MediaItem? = null
        var loaded = MediaItem("42", tmdbId = 42, type = MediaType.MOVIE, title = "Saved film", overview = "A full synopsis",
            cast = listOf(CastMember(1, "An actor")), runtime = 99)
        var logo: String? = "logo.png"
        var failLogo = false
        val detailIds = mutableListOf<Int>()
        override suspend fun find(imdb: String, type: String) = found
        override suspend fun search(title: String) = results
        override suspend fun detail(type: String, id: Int): MediaItem { detailIds += id; return loaded }
        override suspend fun logo(type: String, id: Int): String? { if (failLogo) error("Unavailable"); return logo }
    }
    @Test fun ratingsDoNotMakeASparseTitleComplete() {
        assertTrue(sparse.needsTvPreviewDetails())
        assertFalse(sparse.copy(overview="Plot", cast=listOf(CastMember(1,"Actor")), logoUrl="logo.png", runtime=90).needsTvPreviewDetails())
        assertTrue(sparse.copy(year = null, overview="Plot", cast=listOf(CastMember(1,"Actor")), logoUrl="logo.png", runtime=90).needsTvPreviewDetails())
    }
    @Test fun sparseSavedIdentityGetsFullDetailsAndLogoWithoutChangingItsId() = runBlocking {
        val source=Lookup().apply { results=listOf(loaded.copy(year=2024)) }
        val result=loadTvPreviewDetails(sparse, source)!!
        assertEquals(sparse.id,result.id); assertEquals(sparse.ratings,result.ratings)
        assertEquals("A full synopsis",result.overview); assertEquals("An actor",result.cast.single().name)
        assertEquals("logo.png",result.logoUrl); assertEquals(listOf(42),source.detailIds)
    }
    @Test fun similarTitlesWrongYearsAndWrongMediaTypesCannotSupplyCast() = runBlocking {
        val source=Lookup().apply { results=listOf(loaded.copy(year=2023),loaded.copy(title="Saved film sequel",year=2024),loaded.copy(type=MediaType.SERIES,year=2024)) }
        assertNull(loadTvPreviewDetails(sparse,source)); assertTrue(source.detailIds.isEmpty())
    }
    @Test fun ambiguousExactTitlesAreNotGuessed() = runBlocking {
        val source=Lookup().apply { results=listOf(loaded.copy(year=2024),loaded.copy(id="43",tmdbId=43,year=2024)) }
        assertNull(loadTvPreviewDetails(sparse,source))
    }
    @Test fun logoFailureDoesNotDiscardSynopsisOrActors() = runBlocking {
        val source=Lookup().apply { failLogo=true }
        val result=loadTvPreviewDetails(sparse.copy(tmdbId=42),source)!!
        assertNull(result.logoUrl); assertEquals("An actor",result.cast.single().name); assertFalse(result.overview.isNullOrBlank())
    }
    @Test fun catalogueNamesIdentifyTheContentAndPeopleAreNotTreatedAsFilms() {
        assertEquals("Popular Movies",tvRailDisplayTitle(TvContentRail("addon:popular","Popular",listOf(sparse))))
        assertEquals("Popular TV Shows",tvRailDisplayTitle(TvContentRail("addon:tv","Popular",listOf(sparse.copy(type=MediaType.SERIES)))))
        for (type in listOf("movie", "tv")) for ((key, title) in listOf("trending" to "Trending", "popular" to "Popular", "top_rated" to "Top Rated")) {
            assertEquals(title, tvRailDisplayTitle(TvContentRail("${key}_$type", title, listOf(sparse))))
        }
        val person=sparse.copy(id="person:2")
        assertEquals("Popular Actors",tvRailDisplayTitle(TvContentRail("popular_actors","Popular",listOf(person))))
        assertFalse(person.isTvPreviewTitle()); assertFalse(sparse.copy(familySourceKey="web-replays").isTvPreviewTitle())
    }
    @Test fun backgroundChoicesSuppressArtworkOnlyWhenChosen() {
        assertFalse(PersonalAppearance("standard").active)
        for (preset in listOf("none","gold","image")) assertTrue(PersonalAppearance(preset).active)
    }
}
