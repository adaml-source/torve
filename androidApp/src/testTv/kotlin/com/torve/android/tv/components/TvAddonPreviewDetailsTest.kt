package com.torve.android.tv.components

import com.torve.domain.model.*
import com.torve.data.addon.StremioMeta
import com.torve.data.addon.toMediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvAddonPreviewDetailsTest {
    private val addon = InstalledAddon("https://example.test/manifest.json", AddonManifest("cinema", "Cinema", "1",
        resources = listOf("meta", "catalog"), types = listOf("movie", "series"), idPrefixes = listOf("tt"),
        catalogs = listOf(AddonCatalog("series", "top", extra = listOf(AddonExtra("search"))),
            AddonCatalog("movie", "top", extra = listOf(AddonExtra("search"))))))
    private val fallout = StremioMeta("tt12637874", "series", "Fallout", description = "A full synopsis",
        releaseInfo = "2024-", logo = "logo.png", runtime = "65 min", cast = listOf("Ella Purnell", "Aaron Moten")).toMediaItem()
    private class Source(val value: MediaItem) : TvAddonPreviewLookup {
        val ids = mutableListOf<String>(); var searchCount = 0; var matches = listOf(value); var direct: MediaItem? = value
        var identity: String? = null
        val identityLookups = mutableListOf<Pair<MediaType, Int>>()
        override suspend fun imdbId(type: MediaType, tmdbId: Int): String? { identityLookups += type to tmdbId; return identity }
        override suspend fun meta(addon: InstalledAddon, type: String, id: String): MediaItem? { ids += id; return direct }
        override suspend fun search(addon: InstalledAddon, catalog: AddonCatalog, title: String): List<MediaItem> { searchCount++; return matches }
    }
    @Test fun numericContinueWatchingIdentityAvoidsAmbiguousIronManTitleSearch() = runBlocking {
        val movie = fallout.copy(id = "tt0371746", type = MediaType.MOVIE, title = "Iron Man", year = 2008, runtime = 126)
        val source = Source(movie).apply {
            identity = "tt0371746"
            matches = listOf(movie, movie.copy(id = "tt0043678", year = 1951), movie.copy(id = "tt3375556", year = 2013))
        }
        val original = MediaItem("1726", tmdbId = 1726, type = MediaType.MOVIE, title = "Iron Man",
            ratings = MediaRatings(imdbScore = 7.9f))
        val result = loadTvAddonPreviewDetails(original, listOf(addon), source)!!
        assertEquals(listOf(MediaType.MOVIE to 1726), source.identityLookups)
        assertEquals(listOf("tt0371746"), source.ids)
        assertEquals(0, source.searchCount)
        assertEquals(original.id, result.id); assertEquals(original.tmdbId, result.tmdbId)
        assertEquals(original.ratings, result.ratings)
        assertEquals("tt0371746", result.imdbId); assertEquals(2008, result.year)
        assertEquals(126, result.runtime); assertFalse(result.overview.isNullOrBlank()); assertEquals(2, result.cast.size)
    }
    @Test fun existingImdbIdentityNeedsNoMappingAndNumericSeriesUsesItsSeriesIdentity() = runBlocking {
        val ready = Source(fallout)
        loadTvAddonPreviewDetails(fallout, listOf(addon), ready)
        assertTrue(ready.identityLookups.isEmpty())
        val source = Source(fallout).apply { identity = fallout.imdbId }
        val result = loadTvAddonPreviewDetails(MediaItem("106379", type = MediaType.SERIES, title = "Fallout"), listOf(addon), source)!!
        assertEquals(listOf(MediaType.SERIES to 106379), source.identityLookups)
        assertEquals("tt12637874", result.imdbId)
        assertEquals(0, source.searchCount)
    }
    @Test fun unknownProviderNamespaceDoesNotBecomeAnUnrelatedTmdbId() = runBlocking {
        val source = Source(fallout)
        val result = loadTvAddonPreviewDetails(MediaItem("trakt:106379", type = MediaType.SERIES, title = "Fallout", year = 2024), listOf(addon), source)!!
        assertTrue(source.identityLookups.isEmpty())
        assertEquals(1, source.searchCount)
        assertEquals("trakt:106379", result.id)
        assertEquals("tt12637874", result.imdbId)
    }
    @Test fun watchlistUsesSavedImdbInsteadOfSendingTmdbNumberToExtension() = runBlocking {
        val sparse = MediaItem("106379", tmdbId=106379, type=MediaType.SERIES, title="Fallout", year=2024)
        val source=Source(fallout)
        val current=sparse.withSavedTvPreviewIdentity(listOf(WatchlistItem("106379",MediaType.SERIES,106379,"tt12637874","Fallout",addedAt=0)))
        val result=loadTvAddonPreviewDetails(current,listOf(addon),source)!!
        assertEquals(listOf("tt12637874"),source.ids); assertEquals(0,source.searchCount)
        assertEquals(sparse.id,result.id); assertEquals("A full synopsis",result.overview)
        assertEquals("logo.png",result.logoUrl); assertEquals(2,result.cast.size)
    }
    @Test fun legacyHistoryUsesUniqueExactProviderSearchWhenItHasOnlyTmdbIdentity() = runBlocking {
        val source=Source(fallout)
        val result=loadTvAddonPreviewDetails(MediaItem("106379",type=MediaType.SERIES,title="Fallout",year=2024),listOf(addon),source)!!
        assertEquals(1,source.searchCount); assertFalse(source.ids.contains("106379"))
        assertEquals("tt12637874",result.imdbId); assertFalse(result.overview.isNullOrBlank()); assertEquals(2,result.cast.size)
    }
    @Test fun remakesAndDifferentMediaTypesAreNotGuessed() = runBlocking {
        val source=Source(fallout).apply { matches=listOf(fallout,fallout.copy(id="tt0390703",year=1994)) }
        assertNull(loadTvAddonPreviewDetails(MediaItem("106379",type=MediaType.SERIES,title="Fallout"),listOf(addon),source))
        assertTrue(source.ids.isEmpty())
        source.matches=listOf(fallout.copy(type=MediaType.MOVIE))
        assertNull(loadTvAddonPreviewDetails(MediaItem("106379",type=MediaType.SERIES,title="Fallout",year=2024),listOf(addon),source))
    }
    @Test fun providerWithUndeclaredIdPrefixesStillRecoversLegacyHistoryThroughSearch() = runBlocking {
        val source = Source(fallout).apply { direct = null }
        val provider = addon.copy(manifest = addon.manifest.copy(idPrefixes = emptyList()))
        val value = loadTvAddonPreviewDetails(MediaItem("106379",type=MediaType.SERIES,title="Fallout",year=2024),listOf(provider),source)!!
        assertEquals(listOf("106379"),source.ids)
        assertEquals(1,source.searchCount)
        assertEquals("tt12637874",value.imdbId); assertEquals(2,value.cast.size)
    }
    @Test fun incompleteFirstProviderDoesNotHideCreditsFromTheNextOne() = runBlocking {
        var count=0
        val source=object:TvAddonPreviewLookup {
            override suspend fun meta(addon:InstalledAddon,type:String,id:String)=if (++count==1) fallout.copy(cast=emptyList(),logoUrl=null) else fallout
            override suspend fun search(addon:InstalledAddon,catalog:AddonCatalog,title:String)=emptyList<MediaItem>()
        }
        val result=loadTvAddonPreviewDetails(fallout.copy(cast=emptyList(),logoUrl=null),listOf(addon,addon.copy(manifestUrl="https://second.test/manifest.json")),source)!!
        assertEquals(2,count); assertEquals(2,result.cast.size); assertEquals("logo.png",result.logoUrl)
    }
}
