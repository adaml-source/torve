package com.torve.presentation.apple

import com.torve.domain.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class AppleCardStyleEditorTest {
    @Test fun oldSavedStylesAndCustomRowsRetainTheirDefaults() {
        val style = Json.decodeFromString<CardStyle>("""{"appearance":{"showYear":false}}""")
        assertFalse(style.appearance.showYear)
        assertEquals(CardOverlayPosition.TOP_LEFT, style.appearance.yearPosition)
        assertEquals(0, style.appearance.postersPerRow)
        assertNull(Json.decodeFromString<CustomSection>("""{"id":"saved","title":"Saved row"}""").presetId)
    }
    @Test fun editingMetadataRetainsOtherAppearanceAndRatingPreferences() {
        val original = CardStyle(appearance = CardAppearancePrefs(showBorder = true, cornerRadiusDp = 24),
            ratingPrefs = RatingDisplayPrefs(enabledProviders = listOf(RatingSource.IMDB), maxRatingsOnCard = 1))
        val year = editAppleCardStyle(original, "year", "BOTTOM_RIGHT")
        assertEquals(CardOverlayPosition.BOTTOM_RIGHT, year.appearance.yearPosition)
        assertEquals(original.ratingPrefs, year.ratingPrefs)
        assertTrue(year.appearance.showBorder)
        val ratings = editAppleCardStyle(year, "ratings", "BELOW")
        assertEquals(RatingPillPosition.OUTSIDE, ratings.ratingPrefs.pillPosition)
        assertEquals(listOf(RatingSource.IMDB), ratings.ratingPrefs.enabledProviders)
        assertEquals(1, ratings.ratingPrefs.maxRatingsOnCard)
        assertFalse(editAppleCardStyle(ratings, "year", "HIDDEN").appearance.showYear)
    }
    @Test fun invalidEditsDoNotResetThePresetAndCountsAreBounded() {
        val original = CardStyle()
        assertEquals(original, editAppleCardStyle(original, "orientation", "wrong"))
        assertEquals(original, editAppleCardStyle(original, "postersPerRow", "invalid"))
        assertEquals(12, editAppleCardStyle(original, "postersPerRow", "400").appearance.postersPerRow)
        assertEquals(0, editAppleCardStyle(original, "ratingsCount", "-5").ratingPrefs.maxRatingsOnCard)
        assertEquals(CardOrientation.LANDSCAPE, editAppleCardStyle(original, "orientation", "LANDSCAPE").size.orientation)
    }
    @Test fun customRowPresetReachesTheNativeHomeShelf() {
        val row = com.torve.presentation.home.AppleHomeLayoutRow("custom:one", "Row", presetId = "wide")
        val shelf = AppleTvHomeLayout.shelves(listOf(row), emptyMap()) { error("No items") }.single()
        assertEquals("wide", shelf.presetId)
    }
}
