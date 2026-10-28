package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.home.*
import kotlin.test.*

class AppleTvIosAlignmentTest {
    private fun history(id: String, media: String = "42", type: String = "movie", time: Long = 1,
        episode: Int? = null) = WatchHistoryEntry(id, media, type, "Saved title", null, null, time, 0,
        episode?.let { 1 }, episode, episode?.let { "Saved show" })

    @Test fun historyUsesTheLatestMovieOrShowEventAndRecoversArtwork() {
        val old = history("old", type = "series", episode = 1).copy(posterUrl = "poster", backdropUrl = "scene")
        val newest = history("new", "tmdb:tv:42", "tv", 9, 2)
        val movie = history("movie", time = 5)
        val rows = AppleTvLibraryLayout.history(listOf(old, movie, newest))
        assertEquals(listOf("tv:42", "movie:42"), rows.map { it.id })
        val show = rows.first()
        assertEquals(9, show.watchedAt)
        assertEquals(2, show.episode)
        assertEquals("42", show.targetId)
        assertEquals("poster", show.artwork)
        assertEquals("scene", show.backdrop)
        assertEquals("history", show.status)
        assertFalse(show.subtitle.contains("0m"))
    }
    @Test fun historyLimitAppliesAfterGroupingSoDuplicateSessionsCannotHideOtherTitles() {
        val duplicates = (1..250).map { history("duplicate$it", time = it.toLong()) }
        val other = history("other", "43", time = 0)
        assertEquals(listOf("42", "43"), AppleTvLibraryLayout.history(duplicates + other).map { it.targetId })
    }
    @Test fun aSoughtEpisodeRetainsItsExactIdentityAndTruthfulBookmark() {
        val progress = WatchProgress("tmdb:tv:42", MediaType.SERIES, "Show", posterUrl = "poster", backdropUrl = "scene",
            positionMs = 59_000, durationMs = 60_000, seasonNumber = 3, episodeNumber = 7, verifiedWatched = false)
        val row = AppleTvLibraryLayout.progress(progress)
        assertEquals("42", row.targetId)
        assertEquals(3, row.season); assertEquals(7, row.episode)
        assertEquals("continue", row.status)
        assertFalse(row.watched)
        assertEquals("Less than a minute remaining", row.subtitle)
        assertEquals("scene", row.backdrop)
    }
    @Test fun anEndBookmarkDoesNotPretendPlaybackWasCompleted() {
        val row = AppleTvLibraryLayout.progress(WatchProgress("42", MediaType.MOVIE, "Movie",
            positionMs = 60_000, durationMs = 60_000, verifiedWatched = false))
        assertEquals("Playback stopped", row.subtitle)
        assertFalse(row.watched)
    }
    private fun layout(catalogue: Boolean): List<AppleTvShelf> {
        val sections = listOf(HomeSection.TRENDING_MOVIES, HomeSection.HERO, HomeSection.UP_NEXT, HomeSection.ADDON_SHELVES)
        val configs = sections.map { HomeSectionConfig(it, true, it.defaultOrder) }
        return AppleTvHomeLayout.shelves(AppleHomeLayoutPolicy.rows(HomeUiState(), configs, emptyList(), emptyList()),
            mapOf(HomeSection.HERO to listOf(AppleTvRow("public", "Cached public title")),
                HomeSection.UP_NEXT to listOf(AppleTvRow("personal", "Personal episode"))), catalogue) { AppleTvRow(it.id, it.title) }
    }
    @Test fun disabledCatalogueHidesCachedPublicDiscoveryWithoutHidingPersonalRows() {
        val rows = layout(false)
        assertTrue(rows.none { it.section in setOf("HERO", "TRENDING_MOVIES") })
        assertEquals("personal", rows.first { it.section == "UP_NEXT" }.items.single().id)
    }
    @Test fun explicitOptInRetainsConfiguredDiscoveryOrderAndItems() {
        assertEquals("public", layout(true).first { it.section == "HERO" }.items.single().id)
        assertTrue(layout(true).any { it.section == "TRENDING_MOVIES" })
    }
    @Test fun providerIdentifiersAndDisabledTmdbUseTheSameDetailPolicyAsIphone() {
        val custom = MediaItem("provider:42", tmdbId = 123, type = MediaType.MOVIE, title = "Saved")
        assertTrue(AppleTvCataloguePolicy.needsExtensionDetail(custom, true))
        val numeric = custom.copy(id = "123")
        assertTrue(AppleTvCataloguePolicy.needsExtensionDetail(numeric, false))
        assertFalse(AppleTvCataloguePolicy.needsExtensionDetail(numeric, true))
        assertFalse(AppleTvCataloguePolicy.needsExtensionDetail(custom.copy(id = "tv_123"), true))
        assertEquals(null, AppleTvCataloguePolicy.catalogueId("provider:42"))
        assertEquals("provider:42", AppleTvLibraryLayout.progress(WatchProgress("provider:42", MediaType.MOVIE,
            "Saved", positionMs = 1, durationMs = 100)).targetId)
        assertEquals("provider:42", AppleTvLibraryLayout.history(listOf(history("saved", "provider:42"))).single().targetId)
    }
    @Test fun aCatalogueSelectionPreservesProviderIdentityArtworkAndMediaType() {
        val row = AppleTvRow("row-id", "Saved show", artwork = "poster", mediaType = "tv",
            targetId = "provider:show:42", backdrop = "scene", overview = "Synopsis", year = 2026)
        val seed = AppleTvCataloguePolicy.seed(row)
        assertEquals("provider:show:42", seed.id)
        assertEquals(MediaType.SERIES, seed.type)
        assertEquals("Saved show", seed.title)
        assertEquals("poster", seed.posterUrl)
        assertEquals("scene", seed.backdropUrl)
        assertEquals("Synopsis", seed.overview)
    }
}
