package com.torve.presentation.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the post-auth routing rules from Prompt 15.
 * Each test names the scenario it locks in — when one of these fails,
 * the failure message tells you which trust-leak you re-introduced.
 *
 * Cases the prompt explicitly requires:
 *  - existing configured login
 *  - new unverified login
 *  - verified new login
 *  - stale device_not_registered
 *  - explicit device cap
 */
class MobilePostAuthRouterTest {

    private fun base() = PostAuthInputs(
        isSignedIn = true,
        isEmailVerified = true,
        mobileOnboardingRequired = false,
        mobileOnboardingComplete = true,
        canStartWatching = true,
        hasEntitlement = true,
        isDeviceActivated = true,
        deviceBlockReason = null,
        deviceCapReached = false,
        currentRoute = "home",
    )

    @Test
    fun `existing configured login lands on Home`() {
        // The most common case: returning user, everything wired,
        // device activated. Must NOT bounce them through setup or
        // device management.
        val result = MobilePostAuthRouter.decide(base())
        assertEquals(PostAuthDestination.HOME, result)
    }

    @Test
    fun `new unverified login routes to Verify Email`() {
        // Brand-new account that hasn't clicked the verification link
        // yet. Must hit verify-email before anything else. Setup state
        // doesn't matter here — verification gates everything.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isEmailVerified = false,
                mobileOnboardingRequired = true,
                mobileOnboardingComplete = false,
                canStartWatching = false,
            ),
        )
        assertEquals(PostAuthDestination.VERIFY_EMAIL, result)
    }

    @Test
    fun `stale onboarding-required flag on app shell still lands on Home`() {
        // Prompt 22: stale persisted onboarding flags must not bounce
        // returning/configured users into setup from the app shell.
        // The login / verify-email routes own first-run setup routing.
        // Once the user is in the app shell, this router must not infer
        // setup from stale flags or temporarily-empty provider state.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isEmailVerified = true,
                mobileOnboardingRequired = true,
                mobileOnboardingComplete = false,
                canStartWatching = false,
                hasEntitlement = false,
                isDeviceActivated = false,
            ),
        )
        assertEquals(PostAuthDestination.HOME, result)
    }

    @Test
    fun `stale device_not_registered does NOT trigger device management`() {
        // device_not_registered is recoverable via subscriptionViewModel
        // .refreshAccess(); routing the user to Manage Devices for it
        // would be a false alarm. The router should stay quiet (Home);
        // NavGraph triggers the refresh elsewhere.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isDeviceActivated = false,
                deviceBlockReason = "device_not_registered",
                deviceCapReached = false,
            ),
        )
        assertEquals(
            PostAuthDestination.HOME,
            result,
            "device_not_registered must not surface device management; only the explicit cap reasons should",
        )
    }

    @Test
    fun `explicit device cap routes to Device Limit Reached`() {
        // Backend says the cap is full — this is the only "hard"
        // device block and gets the dedicated DeviceLimitReached
        // screen with the swap UX.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isDeviceActivated = false,
                deviceBlockReason = "device_cap_reached",
                deviceCapReached = true,
            ),
        )
        assertEquals(PostAuthDestination.DEVICE_LIMIT_REACHED, result)
    }

    @Test
    fun `activation_slot_exhausted routes to Manage Devices`() {
        // Soft block: there's no free activation slot but the cap
        // isn't reached either. Send the user to the device manager
        // so they can free a slot deliberately.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isDeviceActivated = false,
                deviceBlockReason = "activation_slot_exhausted",
                deviceCapReached = false,
            ),
        )
        assertEquals(PostAuthDestination.MANAGE_DEVICES, result)
    }

    @Test
    fun `swap_limit_reached routes to Manage Devices`() {
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isDeviceActivated = false,
                deviceBlockReason = "swap_limit_reached",
                deviceCapReached = false,
            ),
        )
        assertEquals(PostAuthDestination.MANAGE_DEVICES, result)
    }

    @Test
    fun `no_activation_slots routes to Manage Devices`() {
        val result = MobilePostAuthRouter.decide(
            base().copy(
                isDeviceActivated = false,
                deviceBlockReason = "no_activation_slots",
                deviceCapReached = false,
            ),
        )
        assertEquals(PostAuthDestination.MANAGE_DEVICES, result)
    }

    @Test
    fun `no entitlement does NOT trigger device management even when not activated`() {
        // Free-tier user can't be device-blocked because there's no
        // entitlement to gate. Sending them to Manage Devices would
        // be confusing because they have no devices to manage.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                hasEntitlement = false,
                isDeviceActivated = false,
                deviceBlockReason = "device_cap_reached",
                deviceCapReached = true,
            ),
        )
        assertEquals(PostAuthDestination.HOME, result)
    }

    @Test
    fun `signed-out user yields STAY`() {
        // The router only acts on signed-in users. A signed-out
        // session is the auth screen's responsibility; the router
        // must not interfere.
        val result = MobilePostAuthRouter.decide(
            base().copy(isSignedIn = false),
        )
        assertEquals(PostAuthDestination.STAY, result)
    }

    @Test
    fun `user already on protected route is left alone`() {
        // Mid-flow on verify-email or device-limit-reached must not
        // be re-routed by the router; those screens own their own
        // exit conditions.
        for (route in listOf("login", "verify_email", "setup_choice", "setup", "setup_guided", "device_limit_reached")) {
            val result = MobilePostAuthRouter.decide(base().copy(currentRoute = route))
            assertEquals(
                PostAuthDestination.STAY,
                result,
                "user on protected route '$route' must not be re-routed",
            )
        }
    }

    @Test
    fun `new account that already configured setup elsewhere skips Setup Choice`() {
        // A user who registered on device A, configured Panda there,
        // then signs in on device B should NOT see the setup choice
        // hub because their setup-summary already reports ready. The
        // canStartWatching guard is what protects this case.
        val result = MobilePostAuthRouter.decide(
            base().copy(
                mobileOnboardingRequired = true,
                mobileOnboardingComplete = false,
                canStartWatching = true,
            ),
        )
        assertEquals(PostAuthDestination.HOME, result)
    }
}
