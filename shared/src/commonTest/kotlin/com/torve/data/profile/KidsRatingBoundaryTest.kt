package com.torve.data.profile

import com.torve.domain.model.*
import kotlin.test.*

class KidsRatingBoundaryTest {
    @Test fun restoredChildRulesRequirePositiveAgeEvidenceEvenIfOlderClientAllowedUnrated() {
        val state = FamilyAccessState(activeProfileId = "child", configuration = FamilyConfiguration(
            enabled = true, kids = mapOf("child" to KidsProfileRules(maximumAge = 0, hideUnrated = false))))
        val rules = assertNotNull(state.effectiveKidsRules)
        val title = MediaItem(id = "1", type = MediaType.MOVIE, title = "Example", adult = false)
        assertFalse(FamilyPolicy.allows(title, rules))
        assertTrue(FamilyPolicy.allows(title, rules, listOf(AgeCertification("US", "G"))))
        assertFalse(FamilyPolicy.allows(title, rules, listOf(AgeCertification("US", "PG"))))
    }

    @Test fun selectedCeilingIncludesLowerRatingsAndRejectsConflictingHigherEvidence() {
        val state = FamilyAccessState(activeProfileId = "child", configuration = FamilyConfiguration(
            enabled = true, kids = mapOf("child" to KidsProfileRules(maximumAge = 13))))
        val rules = assertNotNull(state.effectiveKidsRules)
        val title = MediaItem(id = "1", type = MediaType.MOVIE, title = "Example", adult = false)
        for (rating in listOf("G", "PG", "PG-13")) assertTrue(FamilyPolicy.allows(title, rules, listOf(AgeCertification("US", rating))))
        assertFalse(FamilyPolicy.allows(title, rules, listOf(AgeCertification("US", "G"), AgeCertification("US", "R"))))
    }
}
