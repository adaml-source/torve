package com.torve.desktop.ui.onboarding

/**
 * Hand-off slot for deep-links the user requested **during** onboarding
 * (e.g. "I want to set up Plex/Jellyfin now") that can only be acted on
 * **after** the shell transitions to the post-onboarding [V2App].
 *
 * Onboarding writes [pending], then triggers `onCompleteOnboarding()` to
 * leave the shell. V2App reads [consume] on first composition; it
 * returns the pending target exactly once and clears the slot so a
 * second app launch doesn't re-pop the same screen.
 *
 * The holder is bound as a Koin `single` so both the onboarding shell
 * and V2App see the same instance.
 *
 * Carries no secrets - only an enum-tagged target.
 */
class DesktopOnboardingDeepLink {

    sealed interface Target {
        /** Open Settings → Integrations (Plex / Jellyfin / Trakt / Simkl). */
        data object Integrations : Target

        /** Open Panda multi-step setup (Usenet stack). */
        data object PandaSetup : Target
    }

    @Volatile
    private var pending: Target? = null

    /** Onboarding writes the deep-link before triggering completion. */
    fun set(target: Target) {
        pending = target
    }

    /**
     * V2App reads the deep-link exactly once. Returns null and leaves
     * the slot null when nothing is pending, so the default V2App home
     * landing still works.
     */
    fun consume(): Target? {
        val current = pending
        pending = null
        return current
    }
}
