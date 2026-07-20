package com.torve.data.auth

/**
 * Single source of truth for "who is signed in right now?" exposed to any repository
 * that needs to filter SQLDelight queries by user id without pulling in the full
 * AuthClient dependency graph.
 *
 * Signed-out reads use an empty string so they don't bleed per-user rows across
 * accounts — rows are written with the actual user id only while authenticated.
 *
 * The AuthClient is supplied via a lazy provider to break a Koin cycle:
 * AuthClient depends on DeviceLocalSettingsRepository (= PreferencesRepositoryImpl),
 * and PreferencesRepositoryImpl depends on UserIdProvider. Injecting AuthClient
 * eagerly would deadlock DI. A lambda lets Koin finish wiring before the first
 * resolution happens.
 */
class UserIdProvider(
    private val authClientProvider: () -> AuthClient,
) {
    companion object {
        const val SIGNED_OUT_USER_ID = "__torve_signed_out__"
    }

    /** Current user id, or null when signed out. */
    fun currentUserIdOrNull(): String? =
        authClientProvider().authUserFlow.value?.id?.takeIf { it.isNotBlank() }

    /** Current user id, or a non-user sentinel when signed out. */
    fun currentUserId(): String = currentUserIdOrNull() ?: SIGNED_OUT_USER_ID

    /** Whether a user is currently signed in. */
    fun isSignedIn(): Boolean = currentUserIdOrNull() != null
}
