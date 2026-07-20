package com.torve.desktop.ui.v2.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryFilterConfigTest {
    @Test
    fun tvConfigEnablesProviderAndNetworkButNotStudio() {
        val config = tvDiscoveryFilterConfig(
            providerAvailable = true,
            networkAvailable = true,
            aiAvailable = true,
            geminiReadyAvailable = true,
        )

        assertEquals(ContentDiscoveryType.TvShows, config.contentType)
        assertTrue(config.showProviderFilter)
        assertTrue(config.showNetworkFilter)
        assertFalse(config.showStudioFilter)
        assertTrue(config.showGenreChips)
        assertTrue(config.showMoodChips)
        assertTrue(config.showSortFilter)
    }

    @Test
    fun movieConfigDisablesProviderNetworkAndStudio() {
        val config = movieDiscoveryFilterConfig(
            aiAvailable = true,
            geminiReadyAvailable = false,
        )

        assertEquals(ContentDiscoveryType.Movies, config.contentType)
        assertFalse(config.showProviderFilter)
        assertFalse(config.showNetworkFilter)
        assertFalse(config.showStudioFilter)
        assertTrue(config.showYearFilter)
        assertTrue(config.showRuntimeFilter)
        assertTrue(config.showRatingFilter)
        assertTrue(config.showGenreChips)
        assertTrue(config.showMoodChips)
        assertTrue(config.showSortFilter)
    }

    @Test
    fun mixedConfigDoesNotEnableTvSpecificNetworkByDefault() {
        val config = mixedDiscoveryFilterConfig(
            aiAvailable = true,
            geminiReadyAvailable = false,
        )

        assertEquals(ContentDiscoveryType.Mixed, config.contentType)
        assertFalse(config.showProviderFilter)
        assertFalse(config.showNetworkFilter)
        assertFalse(config.showStudioFilter)
    }

    @Test
    fun resolverUsesAbsoluteLogoUrlAsIs() {
        val url = "https://cdn.example.com/netflix.png"

        assertEquals(url, resolveBrandLogoUrl(absoluteLogoUrl = url, tmdbLogoPath = "/wrong.png"))
    }

    @Test
    fun resolverConvertsTmdbLogoPath() {
        val url = resolveBrandLogoUrl(
            tmdbLogoPath = "/abc123.svg",
            tmdbImageBase = "https://image.tmdb.org/t/p",
            size = "w92",
        )

        assertEquals("https://image.tmdb.org/t/p/w92/abc123.svg", url)
    }

    @Test
    fun resolverReturnsNullForMissingLogo() {
        assertNull(resolveBrandLogoUrl())
    }

    @Test
    fun resolverDoesNotGuessUnknownBrandLogos() {
        assertNull(resolveBrandLogoUrl(absoluteLogoUrl = null, tmdbLogoPath = null))
    }
}

