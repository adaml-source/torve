package com.torve.presentation.contentpolicy

import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentPolicyState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies ContentPolicyState lock/unlock logic and state transitions.
 */
class ContentPolicyStateTest {

    @Test
    fun unrestrictedPolicyIsNeverLocked() {
        val policy = ContentPolicyState.unrestricted()
        assertFalse(policy.isLocked)
        assertFalse(policy.adultEnabled)
        assertFalse(policy.enforcementEnabled)
    }

    @Test
    fun lockedBootstrapIsLocked() {
        val policy = ContentPolicyState.lockedBootstrap(enforcementEnabled = true)
        assertTrue(policy.isLocked)
        assertFalse(policy.adultEnabled)
    }

    @Test
    fun signedOutUserIsLocked() {
        val policy = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = false,
        )
        assertTrue(policy.isLocked)
        assertFalse(policy.adultEnabled)
    }

    @Test
    fun unknownAgeBandIsLocked() {
        val policy = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = true,
            ageBand = ContentAgeBand.UNKNOWN,
            adultEligible = false,
            sensitiveMaterialEnabled = false,
        )
        assertTrue(policy.isLocked)
        assertFalse(policy.adultEnabled)
    }

    @Test
    fun underageUserIsLocked() {
        val policy = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = true,
            ageBand = ContentAgeBand.UNDER_18,
            adultEligible = false,
            sensitiveMaterialEnabled = false,
        )
        assertTrue(policy.isLocked)
        assertFalse(policy.adultEnabled)
    }

    @Test
    fun adultEligibleButSensitiveDisabledIsLocked() {
        val policy = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = true,
            ageBand = ContentAgeBand.ADULT,
            adultEligible = true,
            sensitiveMaterialEnabled = false,
        )
        assertTrue(policy.isLocked)
        assertFalse(policy.adultEnabled)
    }

    @Test
    fun fullyUnlockedAdultIsNotLocked() {
        val policy = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = true,
            ageBand = ContentAgeBand.ADULT,
            adultEligible = true,
            sensitiveMaterialEnabled = true,
        )
        assertFalse(policy.isLocked)
        assertTrue(policy.adultEnabled)
    }

    @Test
    fun relockTransitionFlipsIsLocked() {
        val unlocked = ContentPolicyState(
            enforcementEnabled = true,
            isSignedIn = true,
            ageBand = ContentAgeBand.ADULT,
            adultEligible = true,
            sensitiveMaterialEnabled = true,
        )
        assertFalse(unlocked.isLocked)

        // Simulate disable-sensitive response
        val relocked = unlocked.copy(sensitiveMaterialEnabled = false)
        assertTrue(relocked.isLocked)
        assertFalse(relocked.adultEnabled)
    }

    @Test
    fun ageBandFromBackendMapsCorrectly() {
        assertEquals(ContentAgeBand.ADULT, ContentAgeBand.fromBackend("ADULT"))
        assertEquals(ContentAgeBand.ADULT, ContentAgeBand.fromBackend("OVER_18"))
        assertEquals(ContentAgeBand.ADULT, ContentAgeBand.fromBackend("18_PLUS"))
        assertEquals(ContentAgeBand.UNDER_18, ContentAgeBand.fromBackend("UNDER_18"))
        assertEquals(ContentAgeBand.UNDER_18, ContentAgeBand.fromBackend("MINOR"))
        assertEquals(ContentAgeBand.UNKNOWN, ContentAgeBand.fromBackend(null))
        assertEquals(ContentAgeBand.UNKNOWN, ContentAgeBand.fromBackend(""))
        assertEquals(ContentAgeBand.UNKNOWN, ContentAgeBand.fromBackend("something_else"))
    }

    private fun assertEquals(expected: ContentAgeBand, actual: ContentAgeBand) {
        kotlin.test.assertEquals(expected, actual)
    }
}
