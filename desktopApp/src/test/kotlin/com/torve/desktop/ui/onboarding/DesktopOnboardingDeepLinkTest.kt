package com.torve.desktop.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopOnboardingDeepLinkTest {

    @Test
    fun everyOutcomeIsConsumedExactlyOnce() {
        val targets = listOf(
            DesktopOnboardingDeepLink.Target.StreamingSources,
            DesktopOnboardingDeepLink.Target.PersonalLibrary,
            DesktopOnboardingDeepLink.Target.LiveTv,
        )

        targets.forEach { target ->
            val deepLink = DesktopOnboardingDeepLink()
            deepLink.set(target)

            assertEquals(target, deepLink.consume())
            assertNull(deepLink.consume())
        }
    }

    @Test
    fun newestOutcomeReplacesAnUnconsumedOutcome() {
        val deepLink = DesktopOnboardingDeepLink()
        deepLink.set(DesktopOnboardingDeepLink.Target.PersonalLibrary)
        deepLink.set(DesktopOnboardingDeepLink.Target.LiveTv)

        assertEquals(DesktopOnboardingDeepLink.Target.LiveTv, deepLink.consume())
        assertNull(deepLink.consume())
    }
}
