package com.torve.presentation.session

/**
 * Pure decision function for "after a successful auth event, where
 * should the user land?" extracted from NavGraph so the rules can be
 * unit-tested without spinning up Compose. Mirrors the
 * `LaunchedEffect(authUser?.id, ...)` block in
 * `androidApp/src/main/kotlin/com/torve/android/ui/navigation/NavGraph.kt`.
 *
 * The contract Prompt 15 codifies:
 *  - New account setup is entered directly from the login /
 *    verify-email routes: Verify email -> Setup choice ->
 *    Wizard/manual -> Home.
 *  - This background router must not force setup from the normal app
 *    shell, because persisted onboarding flags can outlive provider
 *    readiness during startup.
 *
 *  - Existing configured account: always land on Home unless email
 *    unverified or a real device-cap block applies.
 *  - Manage Devices only opens for explicit backend reasons:
 *    device_cap_reached / activation_slot_exhausted /
 *    no_activation_slots / swap_limit_reached.
 *
 * Anything not in those rules degrades to "go Home"; the UI must not
 * surprise the user with a setup or device-management screen for
 * reasons the backend hasn't explicitly named.
 */

/** Symbolic destinations the router can return. The platform navigation
 * layer translates these to its own route strings. */
enum class PostAuthDestination {
    /** No nav change required (e.g. user is already on the right screen). */
    STAY,
    /** Force the user to verify their email before doing anything else. */
    VERIFY_EMAIL,
    /** New-account first-run hub: pick guided wizard vs manual setup. */
    SETUP_CHOICE,
    /** The app shell (movies / channels / etc.). */
    HOME,
    /** Hard device-cap block — must remove a device to continue. */
    DEVICE_LIMIT_REACHED,
    /** Soft device-management requirement (slot exhausted, swap limit). */
    MANAGE_DEVICES,
}

/**
 * Inputs to the post-auth routing decision. All fields come from the
 * auth + subscription + onboarding state surfaces that already exist
 * in shared/.
 */
data class PostAuthInputs(
    /** True when there's a signed-in user; false on signed-out. */
    val isSignedIn: Boolean,
    /** Email-verified flag from AuthClient. */
    val isEmailVerified: Boolean,
    /** True iff this user has registered (vs logged in) in this session
     * AND hasn't completed onboarding yet. Set by the
     * AuthEvent.Registered observer in NavGraph. */
    val mobileOnboardingRequired: Boolean,
    /** True once the user reaches the home screen with a configured
     * setup OR explicitly closes the setup hub. */
    val mobileOnboardingComplete: Boolean,
    /** True when the setup hub considers at least one path ready. */
    val canStartWatching: Boolean,
    /** Subscription state: does the user have a premium entitlement? */
    val hasEntitlement: Boolean,
    /** True iff this device is currently activated for this account. */
    val isDeviceActivated: Boolean,
    /** Backend-provided reason for device block. Only specific values
     * trigger device-management routing; anything else is treated as
     * "no block" and the user lands on Home. */
    val deviceBlockReason: String?,
    /** True when the backend explicitly says the device cap is full. */
    val deviceCapReached: Boolean,
    /** Current route the user is on, for "don't bounce them off the
     * verify-email or device-limit screen they're actively using". */
    val currentRoute: String?,
)

object MobilePostAuthRouter {

    /** Backend reasons that warrant routing to the device-management
     * surface. Other reasons (e.g. `device_not_registered`) are
     * recoverable via a refresh and must NOT trigger a device-cap
     * navigation because that would just confuse the user. */
    private val MANAGE_DEVICES_REASONS = setOf(
        "device_cap_reached",
        "activation_slot_exhausted",
        "no_activation_slots",
        "swap_limit_reached",
    )

    /** Routes the post-auth decision should not interfere with; these
     * are auth/onboarding screens the user is mid-flow on. */
    private val PROTECTED_ROUTES = setOf(
        "login",
        "verify_email",
        "setup_choice",
        "setup",
        "setup_guided",
        "device_limit_reached",
    )

    fun decide(inputs: PostAuthInputs): PostAuthDestination {
        if (!inputs.isSignedIn) return PostAuthDestination.STAY
        if (inputs.currentRoute in PROTECTED_ROUTES) return PostAuthDestination.STAY

        // Email verification is non-skippable; route there before any
        // setup / home decision.
        if (!inputs.isEmailVerified) return PostAuthDestination.VERIFY_EMAIL

        // Device-cap block is the only post-verify reason a configured
        // user gets bounced off Home. Order matters: hard cap (limit
        // reached screen) takes precedence over soft management.
        if (inputs.hasEntitlement && inputs.deviceCapReached) {
            return PostAuthDestination.DEVICE_LIMIT_REACHED
        }
        if (inputs.hasEntitlement &&
            !inputs.isDeviceActivated &&
            inputs.deviceBlockReason in MANAGE_DEVICES_REASONS
        ) {
            return PostAuthDestination.MANAGE_DEVICES
        }

        // New-account setup is owned by the login / verify-email
        // routes. The shell router intentionally defaults to Home so a
        // stale persisted onboarding flag cannot trap returning users in
        // setup after a normal sign-in.
        return PostAuthDestination.HOME
    }
}
