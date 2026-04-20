package com.torve.domain.integrations

enum class IntegrationSecretKey {
    TRAKT_TOKENS,
    TRAKT_ACCESS_TOKEN,
    TRAKT_REFRESH_TOKEN,
    TRAKT_CLIENT_SECRET,
    DEBRID_API_KEY,              // legacy single-key (migration only)
    DEBRID_API_KEY_REAL_DEBRID,
    DEBRID_API_KEY_ALL_DEBRID,
    DEBRID_API_KEY_PREMIUMIZE,
    DEBRID_API_KEY_TORBOX,
    DEBRID_RD_REFRESH_TOKEN,
    DEBRID_RD_CLIENT_ID,
    DEBRID_RD_CLIENT_SECRET,
    SIMKL_ACCESS_TOKEN,
    JELLYFIN_API_KEY,
    PLEX_ACCESS_TOKEN,
    CLAUDE_API_KEY,
    CHATGPT_API_KEY,
    GEMINI_API_KEY,
    PERPLEXITY_API_KEY,
    DEEPSEEK_API_KEY,
    MDBLIST_API_KEY,
    OMDB_API_KEY,
    PANDA_TOKEN,
}

/**
 * Where an integration credential is persisted.
 * Users choose this when saving a credential — the app must clearly disclose
 * the behavior of each mode.
 */
enum class IntegrationStorageMode {
    /** Saved to Torve account; restored when signing in on another device. */
    ACCOUNT,
    /** Stored only on this device; never synced to the backend. */
    DEVICE_ONLY,
}

/**
 * Runtime state of an integration, derived from storage mode + local secret presence
 * + restore result. Separate from [IntegrationStorageMode] to avoid conflating
 * "where it's stored" with "is it usable right now".
 */
enum class IntegrationRuntimeState {
    /** Local secret present and usable. */
    CONNECTED,
    /** Account-mode integration whose credential restore failed or hasn't completed.
     *  The user may need to re-authenticate or re-enter the credential. */
    NEEDS_REAUTH,
    /** Device-only integration without a local secret on this device.
     *  The user must enter credentials manually. */
    NEEDS_CREDENTIALS,
    /** No integration metadata exists — never configured. */
    NOT_CONFIGURED,
}

/** Derive the runtime state from storage mode, local secret presence, and backend metadata. */
fun resolveRuntimeState(
    mode: IntegrationStorageMode?,
    hasLocalSecret: Boolean,
    hasBackendCredentials: Boolean = false,
): IntegrationRuntimeState = when {
    mode == null -> IntegrationRuntimeState.NOT_CONFIGURED
    hasLocalSecret -> IntegrationRuntimeState.CONNECTED
    mode == IntegrationStorageMode.ACCOUNT && hasBackendCredentials -> IntegrationRuntimeState.NEEDS_REAUTH
    mode == IntegrationStorageMode.DEVICE_ONLY -> IntegrationRuntimeState.NEEDS_CREDENTIALS
    mode == IntegrationStorageMode.ACCOUNT -> IntegrationRuntimeState.NEEDS_REAUTH
    else -> IntegrationRuntimeState.NOT_CONFIGURED
}

interface IntegrationSecretStore {
    suspend fun put(key: IntegrationSecretKey, value: String)
    suspend fun get(key: IntegrationSecretKey): String?
    suspend fun remove(key: IntegrationSecretKey)

    /** Persist the storage mode chosen by the user for [key]. */
    suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode)

    /** Read the storage mode for [key]. Defaults to [IntegrationStorageMode.DEVICE_ONLY]. */
    suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode

    /** Remove ALL locally stored integration secrets and their mode flags.
     *  Called on logout/account-switch to prevent cross-user credential bleed.
     *  Account-mode secrets can be restored from the backend on next sign-in.
     *  Device-only secrets are gone — the user must re-enter them. */
    suspend fun clearAllSecrets()

    /** Check if a usable local secret exists for [key]. */
    suspend fun hasSecret(key: IntegrationSecretKey): Boolean = get(key)?.isNotBlank() == true

    /** Keys in the preferences DB that may contain legacy secret fallbacks.
     *  Must be cleared on logout alongside the secure store. */
    val legacyPreferenceSecretKeys: List<String>
        get() = listOf(
            "debrid_api_key",
            "trakt_access_token",
            "trakt_refresh_token",
            "simkl_access_token",
            "claude_api_key",
            "chatgpt_api_key",
            "gemini_api_key",
            "perplexity_api_key",
            "deepseek_api_key",
            "omdb_api_key",
            "mdblist_api_key",
            "jellyfin_api_key",
            "jellyfin_server_url",
            "plex_server_url",
        )
}
