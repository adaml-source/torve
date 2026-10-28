package com.torve.data.simkl

import kotlinx.serialization.json.Json
import kotlin.test.*

class SimklLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun `anime cour episodes keep the mapped season and offset`() {
        val item = json.decodeFromString<SimklLibraryResponse>("""{"anime":[{"status":"watching","show":{"title":"Attack on Titan S3 Part 2","ids":{"simkl":931899,"tmdb":"1429"}},"seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-10-06T20:00:00Z","tvdb":{"season":3,"episode":13}},{"number":2,"tvdb":{"season":3,"episode":14}}]}]}]}""")
            .items("anime").single()
        assertEquals("1429", item.toMedia()?.id)
        assertEquals(listOf(3 to 13), item.watchedEpisodes().map { it.season to it.episode })
    }
    @Test fun `unmapped anime is never marked as season one`() {
        val item = json.decodeFromString<SimklLibraryResponse>("""{"anime":[{"show":{"ids":{"simkl":1}},"seasons":[{"number":1,"episodes":[{"number":199,"watched_at":"2026-10-06T20:00:00Z"}]}]}]}""")
            .items("anime").single()
        assertNull(item.toMedia())
        assertTrue(item.watchedEpisodes().isEmpty())
    }
    @Test fun `an episode write cannot mark the entire show watched`() {
        val body = simklEpisodeBody(SimklIds(tmdb = 1429), 3, 13)
        val show = body.shows!!.single()
        assertTrue(show.useTvdbAnimeSeasons)
        assertEquals(3, show.seasons!!.single().number)
        assertEquals(13, show.seasons.single().episodes.single().number)
    }
    @Test fun `movie watchlist status does not imply watched`() {
        val item = json.decodeFromString<SimklLibraryResponse>("""{"movies":[{"status":"plantowatch","movie":{"title":"Test","ids":{"simkl":1,"tmdb":55}}}]}""").items("movies").single()
        assertEquals("plantowatch", item.status)
        assertNull(item.lastWatchedAt)
        assertTrue(item.watchedEpisodes().isEmpty())
    }
}
