package com.torve.domain.model

import kotlin.test.*

class FamilyPolicyTest {
    private val movie = MediaItem("10", type = MediaType.MOVIE, title = "Example", adult = false, rating = 9.8, genreIds = listOf(16))
    @Test fun reviewScoresAndGenresNeverEstablishAnAgeLimit() {
        assertFalse(FamilyPolicy.allows(movie, KidsProfileRules()))
        assertFalse(FamilyPolicy.allows(movie.copy(rating = 1.0), KidsProfileRules()))
    }
    @Test fun usesTheSelectedCountryAndMostRestrictiveReleaseCertificate() {
        val rules = KidsProfileRules(maximumAge = 12, ratingCountry = "DE")
        assertFalse(FamilyPolicy.allows(movie.copy(ageCertifications = listOf(AgeCertification("US", "G"))), rules))
        assertTrue(FamilyPolicy.allows(movie.copy(ageCertifications = listOf(AgeCertification("DE", "12"))), rules))
        assertFalse(FamilyPolicy.allows(movie.copy(ageCertifications = listOf(AgeCertification("DE", "6"), AgeCertification("DE", "16"))), rules))
    }
    @Test fun unknownAndAdultContentCannotUseUnratedAsAnAdultOverride() {
        val allowUnrated = KidsProfileRules(hideUnrated = false)
        assertTrue(FamilyPolicy.allows(movie, allowUnrated))
        assertFalse(FamilyPolicy.allows(movie.copy(adult = null), allowUnrated))
        assertFalse(FamilyPolicy.allows(movie.copy(adult = true, ageCertifications = listOf(AgeCertification("US", "G"))), allowUnrated))
    }
    @Test fun blockedTitlesMatchBothImdbAndTmdbIdentity() {
        val item = movie.copy(imdbId = "tt0010", tmdbId = 10, ageCertifications = listOf(AgeCertification("US", "G")))
        assertFalse(FamilyPolicy.allows(item, KidsProfileRules(blockedTitles = setOf("MOVIE:tt0010"))))
        assertFalse(FamilyPolicy.allows(item.copy(id = "extension-id"), KidsProfileRules(blockedTitles = setOf("MOVIE:10"))))
        assertTrue(FamilyPolicy.allows(item.copy(type = MediaType.SERIES), KidsProfileRules(blockedTitles = setOf("MOVIE:10"))))
    }
    @Test fun regionalCertificationsRecognizeRestrictiveRatingsAndUnknowns() {
        listOf("DE" to "18", "US" to "TV-MA", "GB" to "R18", "FR" to "18", "AU" to "X18+").forEach { (country, label) ->
            assertEquals(18, FamilyPolicy.minimumAge(AgeCertification(country, label)))
        }
        assertNull(FamilyPolicy.minimumAge(AgeCertification("US", "Not Rated")))
        assertNull(FamilyPolicy.minimumAge(AgeCertification("ZZ", "12")))
    }
}
